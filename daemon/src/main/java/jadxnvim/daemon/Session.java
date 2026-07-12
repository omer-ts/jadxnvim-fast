package jadxnvim.daemon;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonObject;

import jadx.api.ICodeInfo;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.impl.NoOpCodeCache;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.ResourceFile;
import jadx.core.xmlgen.ResContainer;
import jadx.api.data.ICodeComment;
import jadx.api.data.ICodeRename;
import jadx.api.data.impl.JadxCodeComment;
import jadx.api.data.impl.JadxCodeData;
import jadx.api.data.impl.JadxCodeRename;
import jadx.api.data.impl.JadxCodeRef;
import jadx.api.data.impl.JadxNodeRef;
import jadx.api.JavaVariable;
import jadx.api.metadata.ICodeAnnotation;
import jadx.api.metadata.ICodeMetadata;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.metadata.annotations.NodeDeclareRef;
import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.nodes.MethodNode;

/**
 * Holds the live {@link JadxDecompiler} and implements the RPC methods.
 *
 * <p>Decompilation is lazy: {@link #loadProject} only parses the inputs and builds the class index;
 * a class is decompiled the first time its code is requested.
 */
public final class Session {

	/** Sink for unsolicited notifications (progress, streamed search hits, ...). */
	public interface Emitter {
		void emit(String method, Object params);
	}

	private static final int MAX_USAGES = 5000;
	// Bump when the on-disk export/name-index format or its index semantics change.
	// v6: showInconsistentCode enabled — decompiled output changed, so rebuild any older export.
	private static final long INDEX_FORMAT_VERSION = 6;
	// RPC protocol/feature version, reported in the ready message. Bump when adding methods the
	// plugin depends on (e.g. getResources) so an out-of-date daemon is detected on connect.
	static final int PROTOCOL_VERSION = 3;

	// Cache signature: changes if the input, the index format, OR the code data (renames/comments)
	// change — a rename doesn't touch the input but must invalidate the export so it's rebuilt with
	// the new names (otherwise the stale export/xref would be reused on reopen and xrefs break).
	// Identity of the INPUT + decompiler options, independent of renames/comments. Two indexes with
	// the same input signature describe the same classes at the same positions (modulo renamed names),
	// so one can be reused for the other after a rename without a full re-decompile.
	private static long inputSignature(File input, boolean showInconsistentCode) {
		// Fold in length AND mtime so replacing the input with different content of the same byte
		// length (e.g. a rebuilt APK) invalidates the cache instead of serving a stale export. The
		// showInconsistentCode flag changes the decompiled output, so it's part of the signature too.
		long h = input.length() * 31 + INDEX_FORMAT_VERSION;
		h = h * 1000003 + input.lastModified();
		h = h * 1000003 + (showInconsistentCode ? 1 : 0);
		return h;
	}

	// Full signature: input identity plus the code data (renames/comments), so an index is "exact"
	// only when its names match the current project. A codeData change makes the index name-stale but
	// still structurally valid (same inputSignature) — reusable while a refresh runs in the background.
	private static long signature(File input, JadxCodeData cd, boolean showInconsistentCode) {
		return inputSignature(input, showInconsistentCode) * 1000003 + codeDataHash(cd);
	}

	// The jadx-gui project fields the plugin manages (everything but codeData/files/projectVersion).
	private static final String[] UI_STATE_KEYS = {
			"openTabs", "searchHistory", "treeExpansionsV2", "cacheDir",
			"searchResourcesFilter", "searchResourcesSizeLimit", "enableLiveReload", "mappingsPath",
			"jadxnvimBookmarks" // jadxnvim's richer bookmark list (jadx-gui keeps bookmarks in openTabs)
	};

	private static com.google.gson.JsonObject extractUiState(com.google.gson.JsonObject root) {
		com.google.gson.JsonObject out = new com.google.gson.JsonObject();
		if (root != null) {
			for (String k : UI_STATE_KEYS) {
				if (root.has(k)) {
					out.add(k, root.get(k));
				}
			}
		}
		return out;
	}

	private static long codeDataHash(JadxCodeData cd) {
		if (cd == null) {
			return 0;
		}
		long h = 1;
		if (cd.getRenames() != null) {
			h = h * 1315423911 + cd.getRenames().size();
			for (ICodeRename r : cd.getRenames()) {
				h = h * 31 + (r == null ? 0 : r.toString().hashCode());
			}
		}
		if (cd.getComments() != null) {
			h = h * 1315423911 + cd.getComments().size();
			for (ICodeComment c : cd.getComments()) {
				h = h * 31 + (c == null ? 0 : c.toString().hashCode());
			}
		}
		return h;
	}

	private Emitter emitter = (m, p) -> {
	};
	private boolean export = true;
	private boolean temp = false;
	private boolean noUsage = false;
	// Emit partially-decompiled ("inconsistent") code rather than a stub for methods jadx can't fully
	// decompile (jadx-gui's "show inconsistent code" / --show-bad-code). On by default.
	private boolean showInconsistentCode = true;
	// Lean mode: after the export finishes, drop the in-memory jadx model and serve browse/search/
	// navigate from the on-disk export. The model is rebuilt on demand for semantic ops (rename,
	// comment, smali). Implies noUsage.
	private boolean lean = false;
	// Keep the parsed jadx model resident even in lean mode (browse/search/navigate still serve from
	// the on-disk export; the model only backs edits + accurate views). NoOpCodeCache keeps decompiled
	// text out of the heap, so the retained model is just parsed structures (~0.5-1.5 GB even on a
	// 400k-class APK). This makes the first rename/comment instant instead of re-parsing the whole APK
	// (a multi-minute stall that froze the daemon). Opt out with --drop-model on RAM-constrained hosts.
	private boolean keepModel = true;
	// True while browse/search/navigate are being served from the on-disk export (independent of
	// whether the model happens to be resident, so a warm model doesn't disable disk-serving).
	private volatile boolean leanServing = false;
	// True when the loaded index is structurally valid for this input but was built with older code
	// data (renames/comments changed since) — its class/method names may be stale. Views re-decompile
	// from the model (correct names) until a background refresh rebuilds and swaps the index in.
	private volatile boolean indexNamesStale = false;
	// v2 fast engine: when enabled, browse/code/name+content search are served from the SQLite index
	// (dexlib2-built) and the on-demand mini-dex renderer, with no whole-APK jadx model. Advanced ops
	// (gd/gr/rename/resources) fall through to the legacy path, which builds the model lazily.
	private boolean v2mode = false;
	private volatile V2Engine v2;
	private java.util.concurrent.atomic.AtomicBoolean exportCancel;
	private File exportDir;
	private File indexDir;
	private File namesDir;
	private File xrefDir;
	private File metaDir;
	private volatile SearchIndex searchIndex;
	private volatile boolean sourcesReady = false;
	private String rgPath = "rg";
	private JadxDecompiler jadx;
	private String inputPath;
	private Map<String, List<JavaClass>> packageIndex;
	private List<Map<String, Object>> classList;
	private List<Map<String, Object>> methodList;
	private Search searchEngine;
	private JadxCodeData codeData = new JadxCodeData();
	// jadx-gui UI-state fields the plugin owns (openTabs, searchHistory, treeExpansionsV2, cacheDir),
	// kept verbatim and written back into the .jadx so they round-trip with jadx-gui.
	private com.google.gson.JsonObject projectUiState = new com.google.gson.JsonObject();
	private File inputFile;
	private File projectFile;
	// Bumped whenever code data changes; classes decompiled at an older version need a reload.
	private int codeDataVersion = 0;
	// Per-class decompiled layout cache. With NoOpCodeCache, cls.getCodeInfo() re-decompiles on every
	// call and jadx's parallel decompile is NOT deterministic, so two calls can return different
	// line/offset layouts. getCode fills the buffer the user sees; a later gd/gr must resolve the
	// cursor against the SAME layout, or getJavaNodeAtPosition lands on the wrong offset (or nothing).
	// Caching the first ICodeInfo per class keeps every access consistent. LRU-bounded (viewed classes
	// only); cleared on edit/reload.
	private static final int FRESH_INFO_MAX = 128;
	private final Map<String, ICodeInfo> freshInfo =
			new java.util.LinkedHashMap<String, ICodeInfo>(256, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, ICodeInfo> e) {
					return size() > FRESH_INFO_MAX;
				}
			};

	// Method override hierarchy, captured during export. jadx's live override attribute is torn down
	// by the per-class unload() that bounds export memory (re-decompiling an interface later yields an
	// impl-less attr), so we snapshot it while every class is still processed. Key = "rawName#name#argc".
	private final java.util.List<String[]> overrideEdges = java.util.Collections.synchronizedList(new ArrayList<>());
	private final Map<String, Boolean> overrideAbstract = new java.util.concurrent.ConcurrentHashMap<>();
	private final Map<String, String> overrideRaw = new java.util.concurrent.ConcurrentHashMap<>(); // key -> raw method name
	private volatile Map<String, String> overrideRoot; // methodKey -> union-find root
	private volatile Map<String, java.util.List<String>> overrideGroup; // root -> member method keys
	private volatile Map<String, java.util.List<String>> overrideByName; // "classId#name" -> member keys (lean/disk)

	private static String methodKey(JavaMethod jm) {
		JavaClass top = jm.getTopParentClass();
		return (top != null ? top.getRawName() : "?") + "#" + jm.getName() + "#" + jm.getArguments().size();
	}

	// The method's runtime (raw) name — what a Frida hook must use, even if renamed in jadx.
	private static String rawMethodName(JavaMethod jm) {
		try {
			return jm.getMethodNode().getMethodInfo().getName();
		} catch (Throwable e) {
			return jm.getName();
		}
	}

	// Record a class's override relationships into overrideEdges/overrideAbstract (called per class
	// during export, while the class is decompiled and its override attribute is intact).
	private void captureOverrides(JavaClass cls) {
		try {
			for (JavaMethod jm : cls.getMethods()) {
				java.util.List<JavaMethod> rel = jm.getOverrideRelatedMethods();
				if (rel == null || rel.isEmpty()) {
					continue;
				}
				String k = methodKey(jm);
				overrideAbstract.putIfAbsent(k, jm.getAccessFlags().isAbstract());
				overrideRaw.putIfAbsent(k, rawMethodName(jm));
				for (JavaMethod r : rel) {
					if (r == null) {
						continue;
					}
					String rk = methodKey(r);
					overrideAbstract.putIfAbsent(rk, r.getAccessFlags().isAbstract());
					overrideRaw.putIfAbsent(rk, rawMethodName(r));
					overrideEdges.add(new String[] { k, rk });
				}
			}
		} catch (Throwable ignore) {
			// override capture is best-effort; gd/gr fall back to the live attr
		}
	}

	private static String ufFind(Map<String, String> parent, String x) {
		String r = x;
		while (!r.equals(parent.getOrDefault(r, r))) {
			r = parent.get(r);
		}
		while (!parent.getOrDefault(x, x).equals(r)) {
			String nx = parent.get(x);
			parent.put(x, r);
			x = nx;
		}
		return r;
	}

	// After export, collapse the captured edges into connected components (a method's full override
	// group), so gd/gr can list every implementation regardless of which member you start from.
	private void finalizeOverrides() {
		Map<String, String> parent = new java.util.HashMap<>();
		String[][] edges;
		synchronized (overrideEdges) {
			edges = overrideEdges.toArray(new String[0][]);
		}
		for (String[] e : edges) {
			parent.put(ufFind(parent, e[0]), ufFind(parent, e[1]));
		}
		Map<String, String> root = new java.util.HashMap<>();
		Map<String, java.util.List<String>> group = new java.util.HashMap<>();
		for (String k : overrideAbstract.keySet()) {
			String r = ufFind(parent, k);
			root.put(k, r);
			group.computeIfAbsent(r, x -> new ArrayList<>()).add(k);
		}
		// Secondary index by "classId#name" (no arg count) for the lean disk path, which resolves a
		// method by name only. Overloads collapse — acceptable for the override list.
		Map<String, java.util.List<String>> byName = new java.util.HashMap<>();
		for (String k : root.keySet()) {
			int h2 = k.lastIndexOf('#');
			String nameKey = h2 > 0 ? k.substring(0, h2) : k;
			byName.putIfAbsent(nameKey, group.get(root.get(k)));
		}
		this.overrideRoot = root;
		this.overrideGroup = group;
		this.overrideByName = byName;
	}

	// The member keys of a method's override group (incl. itself), or null if not captured.
	private java.util.List<String> overrideGroupOf(JavaNode node) {
		if (!(node instanceof JavaMethod) || overrideRoot == null) {
			return null;
		}
		String r = overrideRoot.get(methodKey((JavaMethod) node));
		return r == null ? null : overrideGroup.get(r);
	}

	// Build a gd target from a captured method key (no decompile: id/name come from the key).
	private void addKeyTarget(List<Map<String, Object>> targets, java.util.Set<String> seen, String mk) {
		int h1 = mk.indexOf('#');
		int h2 = mk.lastIndexOf('#');
		if (h1 < 0 || h2 <= h1) {
			return;
		}
		String id = mk.substring(0, h1);
		String name = mk.substring(h1 + 1, h2);
		if (!seen.add(id + "#" + name)) {
			return;
		}
		Map<String, Object> t = new LinkedHashMap<>();
		t.put("id", id);
		t.put("fullName", id); // raw name for display; avoids decompiling every implementation
		t.put("name", name);
		t.put("rawName", overrideRaw.getOrDefault(mk, name)); // runtime name for Frida
		t.put("abstract", Boolean.TRUE.equals(overrideAbstract.get(mk)));
		targets.add(t);
	}

	// Resolve a captured method key back to a live JavaMethod (for its getUseIn()). The key's class
	// part is the TOP-level class, but the method may live in a nested class (very common for callback
	// interfaces like MenuPresenter.Callback), so search the top class and its inner classes.
	private JavaMethod resolveMethodKey(String mk) {
		int h1 = mk.indexOf('#');
		if (h1 < 0) {
			return null;
		}
		JavaClass c = findClass(mk.substring(0, h1));
		return c == null ? null : findMethodInTree(c, mk);
	}

	private JavaMethod findMethodInTree(JavaClass cls, String mk) {
		for (JavaMethod jm : cls.getMethods()) {
			if (methodKey(jm).equals(mk)) {
				return jm;
			}
		}
		for (JavaClass inner : cls.getInnerClasses()) {
			JavaMethod r = findMethodInTree(inner, mk);
			if (r != null) {
				return r;
			}
		}
		return null;
	}

	public void setEmitter(Emitter emitter) {
		this.emitter = emitter;
	}

	public void setInitialInput(String path) {
		this.inputPath = path;
	}

	public void setExport(boolean export) {
		this.export = export;
	}

	public void setRgPath(String rgPath) {
		this.rgPath = rgPath;
	}

	public void setTemp(boolean temp) {
		this.temp = temp;
	}

	public void setNoUsage(boolean noUsage) {
		this.noUsage = noUsage;
	}

	public void setLean(boolean lean) {
		this.lean = lean;
		if (lean) {
			this.noUsage = true; // gd/gr serve from the disk xref index, so skip the in-memory graph
		}
	}

	public void setKeepModel(boolean keepModel) {
		this.keepModel = keepModel;
	}

	public void setShowInconsistentCode(boolean show) {
		this.showInconsistentCode = show;
	}

	public void setV2(boolean v2mode) {
		this.v2mode = v2mode;
	}

	public Object dispatch(String method, JsonObject params) throws Exception {
		// RPC dispatch is single-threaded, so this lock only serializes ops against the background
		// export thread's unloadModel()/reloadModel() (also synchronized) — preventing a model
		// use-after-close race at the export->unload transition in lean mode. Reentrant, so nested
		// ensureLoaded()/reloadModel() calls are fine.
		synchronized (this) {
			return dispatch0(method, params);
		}
	}

	private Object dispatch0(String method, JsonObject params) throws Exception {
		// v2 fast path: serve browse/code/search from the SQLite index + renderer. Anything v2 doesn't
		// handle (gd/gr/rename/resources/smali) returns NOT_HANDLED and falls through to legacy below,
		// which builds the jadx model lazily via ensureLoaded().
		if (v2 != null && !"loadProject".equals(method) && !"shutdown".equals(method)) {
			Object r = v2.handle(method, params);
			if (r != V2Engine.NOT_HANDLED) {
				return r;
			}
		}
		switch (method) {
			case "loadProject":
				return loadProject(str(params, "path"));
			case "getPackages":
				return getPackages();
			case "getClasses":
				return getClasses(reqStr(params, "package"));
			case "getResources":
				return getResources();
			case "getResource":
				return getResource(reqStr(params, "name"));
			case "listClasses":
				return listClasses(optInt(params, "limit", 100000));
			case "listMethods":
				return listMethods(optInt(params, "limit", 100000));
			case "memberPos":
				return memberPos(reqStr(params, "id"), reqInt(params, "index"));
			case "getCode":
				return getCode(reqStr(params, "id"));
			case "getSmali":
				return getSmali(reqStr(params, "id"));
			case "gotoDef":
				return gotoDef(reqStr(params, "id"), reqInt(params, "line"), reqInt(params, "col"));
			case "findUsages":
				return findUsages(reqStr(params, "id"), reqInt(params, "line"), reqInt(params, "col"));
			case "searchName":
				return startSearch("name", params);
			case "searchText":
				return startSearch("text", params);
			case "cancelSearch":
				search().cancel(reqInt(params, "searchId"));
				return Map.of("ok", true);
			case "rename":
				return rename(reqStr(params, "id"), reqInt(params, "line"), reqInt(params, "col"),
						reqStr(params, "newName"));
			case "comment":
				return comment(reqStr(params, "id"), reqInt(params, "line"), reqInt(params, "col"),
						str(params, "comment"));
			case "saveProject":
				return saveProject(str(params, "path"));
			case "setProjectState":
				return setProjectState(params);
			case "shutdown":
				return shutdown();
			default:
				throw new IllegalArgumentException("unknown method: " + method);
		}
	}

	// --- methods -------------------------------------------------------------

	public Map<String, Object> loadProject(String path) throws Exception {
		if (path != null && !path.isEmpty()) {
			this.inputPath = path;
		}
		if (this.inputPath == null) {
			throw new IllegalStateException("no input path provided");
		}
		cancelExport();
		// drop any override graph from a previously-opened project (a cached open skips the rebuild)
		overrideRoot = null;
		overrideGroup = null;
		overrideByName = null;
		File given = new File(this.inputPath);
		if (!given.exists()) {
			throw new IllegalArgumentException("input not found: " + given.getAbsolutePath());
		}

		File input;
		JadxCodeData cd;
		File projFile;
		com.google.gson.JsonObject loadedRoot = null;
		if (given.getName().toLowerCase().endsWith(".jadx")) {
			ProjectIO.Loaded loaded = ProjectIO.load(given);
			if (loaded.input == null || !loaded.input.exists()) {
				throw new IllegalArgumentException("project input not found, referenced by " + given.getName());
			}
			input = loaded.input;
			cd = loaded.codeData;
			loadedRoot = loaded.root;
			projFile = given.getAbsoluteFile();
		} else {
			input = given;
			projFile = defaultProjectFile(input);
			// If a sidecar .jadx already exists, load its renames/comments (and UI state) so they
			// persist across reopens of the APK (opening the APK is equivalent to opening its project).
			JadxCodeData loaded = null;
			if (!temp && projFile.exists()) {
				try {
					ProjectIO.Loaded l = ProjectIO.load(projFile);
					loaded = l.codeData;
					loadedRoot = l.root;
				} catch (Exception e) {
					System.err.println("[jadxd] could not load sidecar project " + projFile.getName() + ": " + e);
				}
			}
			cd = loaded != null ? loaded : new JadxCodeData();
		}
		this.projectUiState = extractUiState(loadedRoot);

		// Export paths (used by the cached fast-open check below and by startExport).
		this.exportDir = new File(projFile.getAbsoluteFile().getParentFile(), stripExt(projFile.getName()) + ".jadxnvim");
		// Declare a cache dir for the project (jadxnvim keeps its own on-disk export index here). Use a
		// dedicated subdir for jadx-gui's cache so it never manages/clears our index files.
		if (!projectUiState.has("cacheDir")) {
			projectUiState.addProperty("cacheDir", new File(exportDir, "gui-cache").getAbsolutePath());
		}
		this.indexDir = new File(exportDir, "index");
		this.namesDir = new File(exportDir, "index-names");
		this.xrefDir = new File(exportDir, "index-xref");
		this.metaDir = new File(exportDir, "index-meta");

		// v2 fast engine: build/open the SQLite index and serve from it — no jadx model parse. Only for
		// dex-based inputs (.apk/.dex); dexlib2 can't read .jar/.class/.aar, so those use the legacy
		// engine even when fast mode is on.
		if (v2mode && isDexInput(input)) {
			return loadProjectV2(input, cd, projFile);
		}

		long inputSig = inputSignature(input, showInconsistentCode);
		long sig = signature(input, cd, showInconsistentCode);
		boolean exactCache = SearchIndex.isValid(metaDir, sig);
		// Structurally valid = same input, possibly older names (renames/comments changed since it was
		// built). Serve it immediately anyway and refresh names in the background, instead of blocking
		// the user for minutes on a full re-decompile after every edit.
		boolean structCache = exactCache || SearchIndex.structurallyValid(metaDir, inputSig);
		boolean hasNames = new File(namesDir, SearchIndex.classesName(0)).isFile();

		// Lean fast-open: with a (structurally) valid cached export, skip the blocking model parse and
		// serve browse/search/tree from disk right away. Keep-model then pre-warms the parsed model in
		// the background so the first edit is instant; --drop-model leaves it lazy.
		if (lean && export && !temp && structCache && hasNames) {
			JadxDecompiler prev = this.jadx;
			this.jadx = null;
			this.codeData = cd;
			this.inputFile = input.getAbsoluteFile();
			this.projectFile = projFile;
			this.packageIndex = null;
			this.classList = null;
			this.methodList = null;
			this.diskPkgIndex = null;
			this.diskNames = null;
			this.codeDataVersion = 0;
			this.freshInfo.clear();
			this.searchIndex = SearchIndex.load(indexDir, namesDir, xrefDir, metaDir);
			this.sourcesReady = true;
			this.leanServing = true;
			this.indexNamesStale = !exactCache; // stale names → views re-decompile from the model
			if (prev != null) {
				try {
					prev.close();
				} catch (Exception ignore) {
					// best effort
				}
			}
			System.gc();
			if (!projFile.exists()) {
				try {
					ProjectIO.save(projFile, this.inputFile, this.codeData, projectUiState);
				} catch (IOException e) {
					System.err.println("[jadxd] could not create project file: " + e);
				}
			}
			Map<String, Object> info = new LinkedHashMap<>();
			info.put("input", input.getAbsolutePath());
			info.put("project", projFile.getAbsolutePath());
			info.put("temp", false);
			info.put("classes", searchIndex.classEntries().size());
			info.put("renames", cd.getRenames() == null ? 0 : cd.getRenames().size());
			info.put("comments", cd.getComments() == null ? 0 : cd.getComments().size());
			info.put("lean", true);
			info.put("protocol", PROTOCOL_VERSION);
			info.put("projectState", projectUiState);
			emitter.emit("ready", info);
			emitter.emit("loadDone", Map.of("total", 0, "cached", true));
			if (keepModel) {
				warmModelAsync(); // pre-warm so the first edit (and any stale-name view) is instant
			} else if (exactCache) {
				emitter.emit("modelUnloaded", Map.of("lean", true));
			}
			if (!exactCache) {
				// Names changed since the export was built: rebuild it to current names in the
				// background and swap it in (reusing the model warmed above, so no duplicate parse).
				refreshIndexAsync();
			}
			return info;
		}

		JadxDecompiler decompiler = buildModel(input, cd);

		JadxDecompiler previous = this.jadx;
		this.jadx = decompiler;
		this.codeData = cd;
		this.inputFile = input.getAbsoluteFile();
		this.projectFile = projFile;
		this.packageIndex = null;
		this.classList = null;
		this.methodList = null;
		this.diskPkgIndex = null;
		this.diskNames = null;
		this.codeDataVersion = 0;
		this.freshInfo.clear();
		this.searchIndex = null;
		this.sourcesReady = false;
		this.leanServing = false;
		this.indexNamesStale = false;
		if (previous != null) {
			try {
				previous.close();
			} catch (Exception ignore) {
				// best effort
			}
		}

		// Always materialize the project file so a plain "open" produces a jadx project (unless
		// running with --temp). Never rewrite an already-existing .jadx here (that happens lazily
		// on the first edit) to avoid churn.
		if (!temp && !projFile.exists()) {
			try {
				ProjectIO.save(projFile, this.inputFile, this.codeData, projectUiState);
			} catch (IOException e) {
				System.err.println("[jadxd] could not create project file: " + e);
			}
		}

		Map<String, Object> info = new LinkedHashMap<>();
		info.put("input", input.getAbsolutePath());
		info.put("project", temp ? null : projFile.getAbsolutePath());
		info.put("temp", temp);
		info.put("classes", decompiler.getClassesWithInners().size());
		info.put("renames", cd.getRenames() == null ? 0 : cd.getRenames().size());
		info.put("comments", cd.getComments() == null ? 0 : cd.getComments().size());
		info.put("protocol", PROTOCOL_VERSION);
		info.put("projectState", projectUiState);
		emitter.emit("ready", info);

		if (export && !temp) {
			startExport(decompiler);
		}
		return info;
	}

	/**
	 * v2 open: build or reuse the SQLite index (dexlib2, no decompilation) and stand up the {@link
	 * V2Engine}. Returns as soon as the index is ready — seconds for a 700MB APK at a few hundred MB —
	 * without parsing a jadx model. The model is built lazily only if an advanced op needs it.
	 */
	private Map<String, Object> loadProjectV2(File input, JadxCodeData cd, File projFile) throws Exception {
		// Tear down any previous state (legacy model or a prior v2 engine).
		V2Engine prevV2 = this.v2;
		this.v2 = null;
		if (prevV2 != null) {
			prevV2.close();
		}
		JadxDecompiler prevJadx = this.jadx;
		this.jadx = null;
		if (prevJadx != null) {
			try {
				prevJadx.close();
			} catch (Exception ignore) {
				// best effort
			}
		}
		this.codeData = cd;
		this.inputFile = input.getAbsoluteFile();
		this.projectFile = projFile;
		this.searchIndex = null;
		this.leanServing = false;
		this.sourcesReady = false;
		this.packageIndex = null;
		this.classList = null;
		this.methodList = null;
		this.diskPkgIndex = null;
		this.diskNames = null;
		this.codeDataVersion = 0;
		this.freshInfo.clear();

		exportDir.mkdirs();
		File dbFile = new File(exportDir, "index.db");
		String sig = DexIndexer.signature(input);
		Db db = Db.open(dbFile.getAbsolutePath());
		boolean cached = db.isValid(sig);
		if (!cached) {
			db.close();
			// Fresh build assumes an empty schema; start from a clean file.
			dbFile.delete();
			new File(dbFile.getAbsolutePath() + "-wal").delete();
			new File(dbFile.getAbsolutePath() + "-shm").delete();
			db = Db.open(dbFile.getAbsolutePath());
			try {
				DexIndexer indexer = new DexIndexer(db, (done, pct) ->
						emitter.emit("loadProgress", Map.of("done", done, "total", 0, "percent", pct)));
				indexer.build(input);
			} catch (Exception e) {
				try {
					db.close(); // don't leak the connection if indexing fails
				} catch (Exception ignore) {
					// best effort
				}
				throw e;
			}
		}
		File work = new File(exportDir, "render");
		this.v2 = new V2Engine(db, this.inputFile, work, this.emitter);
		long classes = this.v2.classCount();

		if (!temp && !projFile.exists()) {
			try {
				ProjectIO.save(projFile, this.inputFile, this.codeData, projectUiState);
			} catch (IOException e) {
				System.err.println("[jadxd] could not create project file: " + e);
			}
		}

		Map<String, Object> info = new LinkedHashMap<>();
		info.put("input", input.getAbsolutePath());
		info.put("project", temp ? null : projFile.getAbsolutePath());
		info.put("temp", temp);
		info.put("classes", classes);
		info.put("renames", cd.getRenames() == null ? 0 : cd.getRenames().size());
		info.put("comments", cd.getComments() == null ? 0 : cd.getComments().size());
		info.put("v2", true);
		info.put("protocol", PROTOCOL_VERSION);
		info.put("projectState", projectUiState);
		emitter.emit("ready", info);
		emitter.emit("loadDone", Map.of("total", 0, "cached", cached));
		return info;
	}

	/**
	 * Background pass that decompiles all classes and writes the Java sources to disk, so full-text
	 * search can use ripgrep instead of re-scanning in memory. Browsing stays available throughout.
	 * If the export already exists and matches the input, it is reused (instant). Reports 0-100%
	 * progress via loadProgress/loadDone, and is cancelled on reload/shutdown.
	 */
	private void startExport(JadxDecompiler d) {
		java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean(false);
		this.exportCancel = cancel;
		File idxDir = this.indexDir;
		File nmDir = this.namesDir;
		File xrDir = this.xrefDir;
		File mDir = this.metaDir;
		File input = this.inputFile;
		Thread t = new Thread(() -> {
			try {
				// The full signature folds in the code data, so a rename/comment marks the cache
				// name-stale; the input signature (input + options only) stays valid across edits.
				long inputSig = inputSignature(input, showInconsistentCode);
				long sig = signature(input, codeData, showInconsistentCode);
				// Reuse the cache only if it also has the name index (older caches predate it, so
				// they rebuild once to gain fast class/method search).
				boolean hasNames = new File(nmDir, SearchIndex.classesName(0)).isFile();
				if (SearchIndex.isValid(mDir, sig) && hasNames) {
					searchIndex = SearchIndex.load(idxDir, nmDir, xrDir, mDir);
					sourcesReady = true;
					if (!resourcesFile().isFile()) {
						writeResourceList(d); // older caches predate the resource list; backfill it once
					}
					// Establish disk-serving (and settle the model) BEFORE announcing loadDone, so a
					// search/navigate arriving right after loadDone sees the export ready, not a
					// half-set state that falls back to the slow in-memory path.
					finishLeanServing();
					emitter.emit("loadDone", Map.of("total", 0, "cached", true));
					return;
				}
				searchIndex = buildIndex(d, idxDir, nmDir, xrDir, mDir, inputSig, sig, cancel);
				if (searchIndex != null && !cancel.get()) {
					sourcesReady = true;
					indexNamesStale = false;
					writeResourceList(d); // so lean mode can list resources without reloading the model
					finishLeanServing();
					emitter.emit("loadDone", Map.of("total", 1));
				}
			} catch (Throwable err) {
				System.err.println("[jadxd] export error: " + err);
				emitter.emit("loadDone", Map.of("total", 0, "error", String.valueOf(err.getMessage())));
			}
		}, "jadx-export");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	/**
	 * Decompile every top-level class and stream it into the ripgrep shard index. Each class is
	 * unloaded right after writing so peak memory stays bounded even on 400k-class APKs.
	 */
	// A stable cross-reference key for an annotation's node: type tag + node identity. The same node
	// yields the same key whether it appears as a reference (CLASS/METHOD/FIELD) or a DECLARATION.
	private static String edgeKey(ICodeAnnotation ann) {
		ICodeAnnotation.AnnType t = ann.getAnnType();
		if (t == ICodeAnnotation.AnnType.CLASS || t == ICodeAnnotation.AnnType.METHOD
				|| t == ICodeAnnotation.AnnType.FIELD) {
			return t.name().charAt(0) + ":" + ann;
		}
		if (t == ICodeAnnotation.AnnType.DECLARATION && ann instanceof NodeDeclareRef) {
			ICodeNodeRef node = ((NodeDeclareRef) ann).getNode();
			if (node != null) {
				return node.getAnnType().name().charAt(0) + ":" + node;
			}
		}
		return null;
	}

	// Turn a class's code metadata into rg-searchable edge lines "key\tclassId\tline\toffset":
	// references go to refOut (find-usages results / go-to-def sources), declarations to declOut
	// (go-to-def targets). Offsets are char positions in the exported code (canonical for matching).
	private static void extractEdges(String code, int[] starts, ICodeMetadata md, String classId,
			List<String> refOut, List<String> declOut) {
		if (md == null) {
			return;
		}
		Map<Integer, ICodeAnnotation> map = md.getAsMap();
		if (map == null || map.isEmpty()) {
			return;
		}
		for (Map.Entry<Integer, ICodeAnnotation> e : map.entrySet()) {
			Integer offObj = e.getKey();
			ICodeAnnotation ann = e.getValue();
			if (offObj == null || ann == null) {
				continue;
			}
			ICodeAnnotation.AnnType t = ann.getAnnType();
			boolean decl = t == ICodeAnnotation.AnnType.DECLARATION;
			boolean ref = t == ICodeAnnotation.AnnType.CLASS || t == ICodeAnnotation.AnnType.METHOD
					|| t == ICodeAnnotation.AnnType.FIELD;
			if (!decl && !ref) {
				continue;
			}
			String key = edgeKey(ann);
			if (key == null || key.indexOf('\t') >= 0 || key.indexOf('\n') >= 0) {
				continue;
			}
			int off = offObj;
			int line = lineOf(starts, off);
			String ln = key + "\t" + classId + "\t" + line + "\t" + off;
			(decl ? declOut : refOut).add(ln);
		}
	}

	private static int[] lineStarts(String code) {
		int count = 1;
		for (int i = 0; i < code.length(); i++) {
			if (code.charAt(i) == '\n') {
				count++;
			}
		}
		int[] starts = new int[count];
		int idx = 1;
		for (int i = 0; i < code.length(); i++) {
			if (code.charAt(i) == '\n') {
				starts[idx++] = i + 1;
			}
		}
		return starts;
	}

	// 1-based line containing char offset (largest starts[i] <= off).
	private static int lineOf(int[] starts, int off) {
		int lo = 0;
		int hi = starts.length - 1;
		int res = 0;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			if (starts[mid] <= off) {
				res = mid;
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}
		return res + 1;
	}

	// Cap concurrency: each in-flight decompile holds a class's transient state, so too many at once
	// spikes peak memory (a cause of OOM-kills). Default min(cores, 8); tunable via the OOM-retry
	// ladder / -Djadxnvim.indexThreads.
	private int indexThreads() {
		int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
		int threads = Math.max(1, Math.min(cores, 8));
		try {
			String prop = System.getProperty("jadxnvim.indexThreads");
			if (prop != null) {
				threads = Math.max(1, Integer.parseInt(prop.trim()));
			}
		} catch (Exception ignore) {
			// keep default
		}
		return threads;
	}

	// Index one class into the builder (decompile -> code + names + xref).
	private void indexClass(SearchIndex.Builder builder, JavaClass cls, boolean withXref) {
		String id = cls.getRawName();
		ICodeInfo ci = cls.getCodeInfo();
		String code = ci.getCodeStr();
		captureOverrides(cls); // snapshot override edges before the export unloads this class
		int[] starts = lineStarts(code);
		// Raw parsed method list: same order memberPos uses, so the stored index maps back correctly.
		// Skipped (DONT_GENERATE) slots stay null to keep positions. The method's declaration line
		// (getDefPosition in this exported code) is stored so lean-mode memberPos resolves off disk.
		List<String> methodNames = new ArrayList<>();
		List<String> methodLines = new ArrayList<>();
		try {
			for (MethodNode mth : cls.getClassNode().getMethods()) {
				if (mth.contains(AFlag.DONT_GENERATE)) {
					methodNames.add(null);
					methodLines.add(null);
				} else {
					methodNames.add(mth.getMethodInfo().getAlias());
					int dp = mth.getDefPosition();
					methodLines.add(dp >= 0 ? Integer.toString(lineOf(starts, dp)) : null);
				}
			}
		} catch (Throwable ignore) {
			// no methods indexed for this class
		}
		List<String> refLines = null;
		List<String> declLines = null;
		if (withXref) {
			refLines = new ArrayList<>();
			declLines = new ArrayList<>();
			try {
				extractEdges(code, starts, ci.getCodeMetadata(), id, refLines, declLines);
			} catch (Throwable ignore) {
				// no xref for this class
			}
		}
		try {
			builder.add(id, cls.getFullName(), code, methodNames, methodLines, refLines, declLines);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private SearchIndex buildIndex(JadxDecompiler d, File idxDir, File nmDir, File xrDir, File mDir, long inputSig,
			long sig, java.util.concurrent.atomic.AtomicBoolean cancel) throws Exception {
		List<JavaClass> all = d.getClasses();
		int total = all.size();
		boolean withXref = lean;
		// fresh override capture for this build
		overrideEdges.clear();
		overrideAbstract.clear();
		overrideRaw.clear();
		overrideRoot = null;
		overrideGroup = null;
		overrideByName = null;

		// Resume a compatible partial checkpoint if one exists; any resume error falls back to a
		// clean rebuild, so a corrupt checkpoint can never produce a bad index.
		File progressFile = new File(mDir, ".progress");
		File doneFile = new File(mDir, ".done");
		java.util.Set<String> doneAll = new java.util.HashSet<>();
		SearchIndex.Builder builder = null;
		if (checkpointMatches(progressFile, sig) && new File(mDir, ".pos").isFile()) {
			try {
				builder = SearchIndex.Builder.resume(idxDir, nmDir, xrDir, mDir);
				// The committed index (resumed from the atomic .pos) IS the done set — a single source
				// of truth, so .pos and "what to skip" can never disagree after a crash.
				doneAll.addAll(builder.committedIds());
				emitter.emit("loadProgress", Map.of("done", doneAll.size(), "total", total, "percent",
						total == 0 ? 0 : (int) ((long) doneAll.size() * 100 / total), "resumed", true));
			} catch (Exception e) {
				System.err.println("[jadxd] index resume failed, rebuilding: " + e);
				builder = null;
				doneAll.clear();
			}
		}
		if (builder == null) {
			deleteDir(idxDir);
			deleteDir(nmDir);
			deleteDir(xrDir);
			deleteDir(mDir);
			builder = new SearchIndex.Builder(idxDir, nmDir, xrDir);
		}
		mDir.mkdirs(); // .progress/.pos/.idx live here; ensure it exists before the first checkpoint write
		if (total == 0) {
			return builder.finish(mDir, inputSig, sig);
		}

		List<JavaClass> remaining = new ArrayList<>();
		for (JavaClass c : all) {
			if (!doneAll.contains(c.getRawName())) {
				remaining.add(c);
			}
		}

		final SearchIndex.Builder b = builder;
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(indexThreads(), r -> {
			Thread th = new Thread(r, "jadx-index");
			th.setDaemon(true);
			th.setPriority(Thread.MIN_PRIORITY);
			return th;
		});
		java.util.concurrent.atomic.AtomicInteger progress = new java.util.concurrent.atomic.AtomicInteger(doneAll.size());
		java.util.concurrent.atomic.AtomicInteger lastPct = new java.util.concurrent.atomic.AtomicInteger(-1);
		// Process in batches; between batches (no worker active) we can take a durable checkpoint so a
		// later OOM-kill resumes from it instead of re-indexing everything. The batch is only a quiesce
		// window — keep it small so quiesce points come often. The actual checkpoint is wall-clock gated
		// (below): under memory pressure a batch can slow to minutes, and a coarse "checkpoint every
		// batch" then leaves long gaps where an OOM-kill discards the whole in-flight batch and the
		// retry redoes the same classes and dies again — no forward progress. Gating on elapsed time
		// instead bounds the work an OOM can throw away, so restarts always advance.
		int batch = 4000;
		try {
			String bp = System.getProperty("jadxnvim.indexBatch");
			if (bp != null) {
				batch = Math.max(1, Integer.parseInt(bp.trim()));
			}
		} catch (Exception ignore) {
			// keep default
		}
		final int BATCH = batch;
		long checkpointMs = 15000;
		try {
			String cp = System.getProperty("jadxnvim.checkpointMs");
			if (cp != null) {
				checkpointMs = Math.max(0, Long.parseLong(cp.trim()));
			}
		} catch (Exception ignore) {
			// keep default
		}
		final long CHECKPOINT_MS = checkpointMs;
		long lastCheckpoint = System.currentTimeMillis();
		// Stall watchdog: if the indexed-class count stops advancing (e.g. a jadx decompile loops on a
		// pathological/obfuscated method and pins a worker), log it to stderr instead of hanging
		// silently. Purely diagnostic — it never touches the build state.
		final java.util.concurrent.atomic.AtomicBoolean watchDone = new java.util.concurrent.atomic.AtomicBoolean(false);
		Thread watchdog = new Thread(() -> {
			int last = progress.get();
			long lastMoved = System.currentTimeMillis();
			while (!watchDone.get()) {
				try {
					Thread.sleep(15000);
				} catch (InterruptedException e) {
					break;
				}
				int cur = progress.get();
				if (cur != last) {
					last = cur;
					lastMoved = System.currentTimeMillis();
					continue;
				}
				long stalledSec = (System.currentTimeMillis() - lastMoved) / 1000;
				System.err.println("[jadxd] index stalled at " + cur + "/" + total + " — no class indexed for "
						+ stalledSec + "s (a slow or looping decompile can pin a worker)");
			}
		}, "jadx-index-watchdog");
		watchdog.setDaemon(true);
		watchdog.start();
		try {
			for (int s = 0; s < remaining.size(); s += BATCH) {
				if (cancel.get()) {
					return null;
				}
				int end = Math.min(s + BATCH, remaining.size());
				List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
				List<String> batchIds = java.util.Collections.synchronizedList(new ArrayList<>());
				for (int k = s; k < end; k++) {
					JavaClass cls = remaining.get(k);
					futures.add(pool.submit(() -> {
						if (cancel.get()) {
							return;
						}
						try {
							indexClass(b, cls, withXref);
							batchIds.add(cls.getRawName());
						} catch (Throwable ignore) {
							// keep going; a failed class just isn't indexed
						} finally {
							try {
								cls.unload();
							} catch (Exception ignore) {
								// best effort
							}
							int c = progress.incrementAndGet();
							int pct = (int) ((long) c * 100 / total);
							if (pct != lastPct.getAndSet(pct)) {
								emitter.emit("loadProgress", Map.of("done", c, "total", total, "percent", pct));
							}
						}
					}));
				}
				for (java.util.concurrent.Future<?> f : futures) {
					try {
						f.get();
					} catch (Exception ignore) {
						// a worker failure already logged; keep going
					}
				}
				if (cancel.get()) {
					return null;
				}
				// No workers active now, so positions match what's on disk — a safe point to checkpoint.
				// Only actually persist one every CHECKPOINT_MS, though: checkpoint() rewrites the full
				// .idx (O(classes so far)), so doing it every small batch would be O(n^2) I/O. Time-gating
				// keeps total checkpoint cost bounded while still capping how much an OOM-kill can lose.
				// The (in-memory) doneAll grows every batch, but resume rebuilds it from the committed
				// .pos — never from this — so it is safe for doneAll to run ahead of the last checkpoint.
				doneAll.addAll(batchIds);
				long now = System.currentTimeMillis();
				if (now - lastCheckpoint >= CHECKPOINT_MS) {
					// progress gate first (cosmetic), then checkpoint() commits atomically via .pos last —
					// so the last durable write is always the authoritative one.
					writeProgress(progressFile, doneAll.size(), total, sig);
					b.checkpoint(mDir);
					lastCheckpoint = now;
				}
			}
		} finally {
			pool.shutdown();
			watchDone.set(true);
			watchdog.interrupt();
		}
		if (cancel.get()) {
			return null;
		}
		SearchIndex idx = b.finish(mDir, inputSig, sig);
		finalizeOverrides(); // collapse the captured edges into per-method override groups
		// completed — the resume artifacts are no longer needed
		progressFile.delete();
		doneFile.delete();
		new File(mDir, ".pos").delete();
		return idx;
	}

	private static boolean checkpointMatches(File f, long sig) {
		if (!f.isFile()) {
			return false;
		}
		try {
			String[] p = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim().split("\t");
			return p.length >= 3 && Long.parseLong(p[2].trim()) == sig;
		} catch (Exception e) {
			return false;
		}
	}

	private static void writeProgress(File f, int done, int total, long sig) throws IOException {
		Files.write(f.toPath(), (done + "\t" + total + "\t" + sig).getBytes(StandardCharsets.UTF_8));
	}

	private static void deleteDir(File dir) {
		if (dir == null || !dir.exists()) {
			return;
		}
		File[] files = dir.listFiles();
		if (files != null) {
			for (File f : files) {
				if (f.isDirectory()) {
					deleteDir(f);
				} else {
					f.delete();
				}
			}
		}
		dir.delete();
	}

	private void cancelExport() {
		if (exportCancel != null) {
			exportCancel.set(true);
			exportCancel = null;
		}
	}

	// dexlib2 (the v2 index engine) reads dex-based containers only. Java bytecode inputs
	// (.jar/.class/.aar) must use the legacy jadx path (jadx-java-convert).
	private static boolean isDexInput(File input) {
		String n = input.getName().toLowerCase();
		return n.endsWith(".apk") || n.endsWith(".dex");
	}

	private static String stripExt(String name) {
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	private static File defaultProjectFile(File input) {
		String name = input.getName();
		int dot = name.lastIndexOf('.');
		String base = dot > 0 ? name.substring(0, dot) : name;
		File parent = input.getAbsoluteFile().getParentFile();
		return new File(parent, base + ".jadx");
	}

	/**
	 * Return the package list with class counts only (no per-class data). Cheap even for huge
	 * APKs, so the tree can render instantly and expand packages lazily via {@link #getClasses}.
	 */
	public Map<String, Object> getPackages() {
		if (servingFromDisk()) {
			Map<String, List<String[]>> index = diskPackages();
			List<Map<String, Object>> packages = new ArrayList<>(index.size());
			for (Map.Entry<String, List<String[]>> e : index.entrySet()) {
				Map<String, Object> pkg = new LinkedHashMap<>();
				pkg.put("name", e.getKey());
				pkg.put("count", e.getValue().size());
				packages.add(pkg);
			}
			return Map.of("packages", packages);
		}
		ensureLoaded();
		Map<String, List<JavaClass>> index = packageIndex();
		List<Map<String, Object>> packages = new ArrayList<>(index.size());
		for (Map.Entry<String, List<JavaClass>> e : index.entrySet()) {
			Map<String, Object> pkg = new LinkedHashMap<>();
			pkg.put("name", e.getKey());
			pkg.put("count", e.getValue().size());
			packages.add(pkg);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("packages", packages);
		return result;
	}

	private File resourcesFile() {
		return new File(exportDir, "resources.txt");
	}

	// Persist the resource list ("deepName \t TYPE" per line) so lean mode can list resources from
	// disk without reloading the jadx model (loading a resource's content still needs the model).
	private void writeResourceList(JadxDecompiler d) {
		if (d == null) {
			return;
		}
		try {
			List<ResourceFile> res = d.getResources();
			StringBuilder sb = new StringBuilder();
			if (res != null) {
				for (ResourceFile rf : res) {
					if (rf == null) {
						continue;
					}
					String name = rf.getOriginalName();
					if (name == null) {
						name = String.valueOf(rf);
					}
					sb.append(name.replace('\t', ' ').replace('\n', ' ')).append('\t')
							.append(rf.getType()).append('\n');
				}
			}
			Files.write(resourcesFile().toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
		} catch (Throwable ignore) {
			// resources are best-effort; a failure here just means no Resources section
		}
	}

	private static Map<String, Object> resEntry(String name, String type) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", name);
		m.put("type", type);
		return m;
	}

	/** The APK's resource files (deep name + type). Served from disk in lean mode. */
	public Map<String, Object> getResources() throws IOException {
		List<Map<String, Object>> out = new ArrayList<>();
		File rf = resourcesFile();
		if (rf.isFile() && (servingFromDisk() || jadx == null)) {
			for (String ln : Files.readAllLines(rf.toPath(), StandardCharsets.UTF_8)) {
				if (ln.isEmpty()) {
					continue;
				}
				int t = ln.lastIndexOf('\t');
				out.add(resEntry(t < 0 ? ln : ln.substring(0, t), t < 0 ? "" : ln.substring(t + 1)));
			}
		} else {
			ensureLoaded();
			List<ResourceFile> res = jadx.getResources();
			if (res != null) {
				for (ResourceFile r : res) {
					if (r != null) {
						out.add(resEntry(r.getOriginalName(), String.valueOf(r.getType())));
					}
				}
			}
		}
		return Map.of("resources", out);
	}

	/** Decoded text of a resource by deep name (reloads the model in lean mode). */
	public Map<String, Object> getResource(String name) {
		ensureLoaded();
		ResourceFile match = null;
		List<ResourceFile> res = jadx.getResources();
		if (res != null) {
			for (ResourceFile r : res) {
				if (r != null && name.equals(r.getOriginalName())) {
					match = r;
					break;
				}
			}
		}
		if (match == null) {
			throw new IllegalArgumentException("resource not found: " + name);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("name", name);
		out.put("type", String.valueOf(match.getType()));
		try {
			ResContainer rc = match.loadContent();
			ICodeInfo text = rc == null ? null : rc.getText();
			String code = text == null ? null : text.getCodeStr();
			if (code != null) {
				out.put("text", code);
				out.put("binary", false);
			} else {
				out.put("binary", true);
				out.put("text", "// binary resource (" + match.getType() + ") — cannot show as text");
			}
		} catch (Throwable e) {
			out.put("binary", true);
			out.put("text", "// could not decode resource: " + e);
		}
		return out;
	}

	/** Return the top-level classes of a single package (lazy tree expansion). */
	public Map<String, Object> getClasses(String pkgName) {
		String key = pkgName == null ? "" : pkgName;
		List<Map<String, Object>> out = new ArrayList<>();
		if (servingFromDisk()) {
			for (String[] e : diskPackages().getOrDefault(key, List.of())) {
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("id", e[0]);
				entry.put("name", e[1]);
				entry.put("fullName", e[2]);
				out.add(entry);
			}
		} else {
			ensureLoaded();
			for (JavaClass cls : packageIndex().getOrDefault(key, List.of())) {
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("id", cls.getRawName());
				entry.put("name", cls.getName());
				entry.put("fullName", cls.getFullName());
				out.add(entry);
			}
		}
		out.sort(Comparator.comparing(m -> (String) m.get("name")));
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("package", key);
		result.put("classes", out);
		return result;
	}

	private Map<String, List<String[]>> diskPkgIndex;

	/** Package -> [{id, name, fullName}] built from the export index (no model needed). */
	private Map<String, List<String[]>> diskPackages() {
		if (diskPkgIndex == null) {
			Map<String, List<String[]>> idx = new TreeMap<>();
			for (String[] e : searchIndex.classEntries()) {
				String fullName = e[1];
				int dot = fullName.lastIndexOf('.');
				String pkg = dot >= 0 ? fullName.substring(0, dot) : "";
				String name = dot >= 0 ? fullName.substring(dot + 1) : fullName;
				idx.computeIfAbsent(pkg, k -> new ArrayList<>()).add(new String[] { e[0], name, fullName });
			}
			diskPkgIndex = idx;
		}
		return diskPkgIndex;
	}

	/** Lazily built, cached map of package name -> top-level classes; invalidated on (re)load. */
	private Map<String, List<JavaClass>> packageIndex() {
		Map<String, List<JavaClass>> index = this.packageIndex;
		if (index == null) {
			index = new TreeMap<>();
			for (JavaClass cls : ensureLoaded().getClasses()) {
				String pkg = cls.getPackage();
				index.computeIfAbsent(pkg == null ? "" : pkg, k -> new ArrayList<>()).add(cls);
			}
			this.packageIndex = index;
		}
		return index;
	}

	/** Flat list of all top-level classes for the fuzzy finder: {id, label}. Cached. */
	public Map<String, Object> listClasses(int limit) {
		ensureLoaded();
		if (classList == null) {
			List<Map<String, Object>> items = new ArrayList<>();
			for (JavaClass cls : jadx.getClasses()) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", cls.getRawName());
				m.put("label", cls.getFullName());
				items.add(m);
			}
			items.sort(Comparator.comparing(m -> (String) m.get("label")));
			classList = items;
		}
		boolean truncated = classList.size() > limit;
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("items", truncated ? classList.subList(0, limit) : classList);
		result.put("truncated", truncated);
		return result;
	}

	/** Flat list of all top-level classes' methods: {id, index, label}. Cached. */
	public Map<String, Object> listMethods(int limit) {
		ensureLoaded();
		if (methodList == null) {
			List<Map<String, Object>> items = new ArrayList<>();
			for (JavaClass cls : jadx.getClasses()) {
				List<JavaMethod> methods = cls.getMethods();
				for (int i = 0; i < methods.size(); i++) {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", cls.getRawName());
					m.put("index", i);
					m.put("label", methods.get(i).getName() + "  ·  " + cls.getFullName());
					items.add(m);
				}
			}
			methodList = items;
		}
		boolean truncated = methodList.size() > limit;
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("items", truncated ? methodList.subList(0, limit) : methodList);
		result.put("truncated", truncated);
		return result;
	}

	/** Resolve the declaration position of a method (by class id + index from {@link #listMethods}). */
	public Map<String, Object> memberPos(String id, int index) {
		// Lean mode: the method's declaration line was stored in the name index at export time
		// ("name\tfullName\tid\tindex\tline"), so we can resolve it without the model.
		if (servingFromDisk()) {
			int line = 1;
			for (String ln : rgLines(searchIndex.namesDir(), SearchIndex.methodsName(searchIndex.shardIndexOf(id)),
					"\t" + rgRegex(id) + "\t" + index + "\t")) {
				String[] f = ln.split("\t", -1);
				// name, fullName, id, index, line
				if (f.length >= 5 && f[2].equals(id) && f[3].equals(Integer.toString(index))) {
					line = parseIntSafe(f[4], 1);
					break;
				}
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("id", id);
			result.put("fullName", diskFullName(id));
			result.put("line", line);
			result.put("col", 0);
			return result;
		}
		JadxDecompiler d = ensureLoaded();
		JavaClass cls = findClass(id);
		if (cls == null) {
			throw new IllegalArgumentException("class not found: " + id);
		}
		// Use the raw core method list (parsed order), matching how the name index/search assign the
		// index — JavaClass.getMethods() filters + sorts, so its index would not line up.
		List<MethodNode> methods = cls.getClassNode().getMethods();
		if (index < 0 || index >= methods.size()) {
			throw new IllegalArgumentException("method index out of range: " + index);
		}
		MethodNode mth = methods.get(index);
		ICodeInfo info = freshCodeInfo(cls, id);
		int pos = mth.getDefPosition();
		int[] lc = pos >= 0 ? Positions.toLineCol(info.getCodeStr(), pos) : new int[] { 1, 0 };
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id);
		result.put("fullName", cls.getFullName());
		result.put("line", lc[0]);
		result.put("col", lc[1]);
		return result;
	}

	public Map<String, Object> getCode(String id) {
		// Lean mode: read the exported copy straight off disk (no re-decompile) as long as the project
		// is unedited AND the export's names are current. This is exactly the text the search index was
		// built from, so search line numbers land precisely. When names are stale (renames changed
		// since the export), re-decompile from the model so the viewed class shows correct names while
		// the background refresh rebuilds the index.
		if (servingFromDisk() && !indexNamesStale && codeDataVersion == 0 && searchIndex.hasCode(id)) {
			String code = searchIndex.codeOf(id);
			if (code != null) {
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("id", id);
				result.put("fullName", diskFullName(id));
				result.put("code", code);
				return result;
			}
		}
		JadxDecompiler d = ensureLoaded();
		JavaClass cls = findClass(id);
		if (cls == null) {
			throw new IllegalArgumentException("class not found: " + id);
		}
		ICodeInfo code = freshCodeInfo(cls, id);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id);
		result.put("fullName", cls.getFullName());
		result.put("code", code.getCodeStr());
		return result;
	}

	private Map<String, String> diskNames;

	/** id -> fullName from the export index (for disk-served results). */
	private String diskFullName(String id) {
		if (diskNames == null) {
			Map<String, String> m = new java.util.HashMap<>();
			for (String[] e : searchIndex.classEntries()) {
				m.put(e[0], e[1]);
			}
			diskNames = m;
		}
		return diskNames.getOrDefault(id, id);
	}

	// --- disk cross-reference (lean mode go-to-def / find-usages, no model) -------------------

	private boolean xrefReady() {
		// Position-based (needs the buffer the user sees to match the index layout): only while serving
		// exact disk text. After an in-session edit the buffer is re-decompiled from the model with a
		// possibly different layout, so gd/gr resolve against the model instead.
		return servingFromDisk() && searchIndex.xrefDir() != null && searchIndex.xrefDir().isDirectory();
	}

	// Escape a literal for a ripgrep (Rust) regex.
	private static String rgRegex(String s) {
		StringBuilder sb = new StringBuilder(s.length() + 8);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if ("\\.+*?()|[]{}^$".indexOf(c) >= 0) {
				sb.append('\\');
			}
			sb.append(c);
		}
		return sb.toString();
	}

	private List<String> rgXref(String glob, String pattern) {
		return rgLines(searchIndex.xrefDir(), glob, pattern);
	}

	// Run ripgrep over an index dir with a glob + pattern; return matched lines (bounded).
	private List<String> rgLines(File dir, String glob, String pattern) {
		List<String> out = new ArrayList<>();
		if (dir == null || !dir.isDirectory()) {
			return out;
		}
		List<String> cmd = new ArrayList<>();
		cmd.add(rgPath != null && !rgPath.isEmpty() ? rgPath : "rg");
		cmd.add("--no-filename");
		cmd.add("--no-line-number");
		cmd.add("--no-heading");
		cmd.add("--no-ignore");
		cmd.add("-g");
		cmd.add(glob);
		cmd.add("-e");
		cmd.add(pattern);
		cmd.add(dir.getAbsolutePath());
		Process p = null;
		try {
			p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
			p.getOutputStream().close();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				String ln;
				int n = 0;
				while ((ln = r.readLine()) != null) {
					out.add(ln);
					if (++n >= MAX_USAGES) {
						break;
					}
				}
			}
		} catch (Exception e) {
			System.err.println("[jadxd] xref rg failed: " + e);
		} finally {
			if (p != null) {
				p.destroy();
			}
		}
		return out;
	}

	// Resolve the cross-reference key of the token at (line, col) in class id, from the edge index.
	private String resolveKeyAt(String id, int line, int col) {
		String code = searchIndex.codeOf(id);
		if (code == null) {
			return null;
		}
		int cursor = Positions.toOffset(code, line, col);
		String pat = "\t" + rgRegex(id) + "\t" + line + "\t";
		// Edges for this class live only in its shard — scan that one file, not all of them.
		int sh = searchIndex.shardIndexOf(id);
		List<String> lines = rgXref(SearchIndex.refsName(sh), pat);
		lines.addAll(rgXref(SearchIndex.declsName(sh), pat));
		String bestKey = null;
		int bestOff = -1;
		for (String ln : lines) {
			int t = ln.lastIndexOf('\t');
			if (t < 0) {
				continue;
			}
			int off;
			try {
				off = Integer.parseInt(ln.substring(t + 1));
			} catch (NumberFormatException e) {
				continue;
			}
			// token at/just before the cursor on this line
			if (off <= cursor && off > bestOff) {
				bestOff = off;
				bestKey = ln.substring(0, ln.indexOf('\t'));
			}
		}
		return bestKey;
	}

	private static String keyName(String key) {
		if (key == null) {
			return "symbol";
		}
		String s = key.startsWith("C:") || key.startsWith("M:") || key.startsWith("F:") ? key.substring(2) : key;
		int paren = s.indexOf('(');
		if (paren >= 0) {
			s = s.substring(0, paren);
		}
		int dot = s.lastIndexOf('.');
		return dot >= 0 && dot < s.length() - 1 ? s.substring(dot + 1) : s;
	}

	private Map<String, Object> diskGotoDef(String id, int line, int col) {
		Map<String, Object> result = new LinkedHashMap<>();
		String key = resolveKeyAt(id, line, col);
		if (key != null) {
			List<String> decls = rgXref("decl_s*.txt", "^" + rgRegex(key) + "\t");
			if (!decls.isEmpty()) {
				// key \t classId \t line \t offset
				String[] f = decls.get(0).split("\t", -1);
				if (f.length >= 3) {
					result.put("found", true);
					result.put("id", f[1]);
					result.put("fullName", diskFullName(f[1]));
					String nm = keyName(key);
					result.put("name", nm);
					result.put("line", parseIntSafe(f[2], 1));
					result.put("col", 0);
					// go-to-implementations in lean mode: the override graph captured at export is in
					// memory even though we serve from disk. Look it up by classId#name.
					java.util.List<String> group = overrideByName == null ? null
							: overrideByName.get(f[1] + "#" + nm);
					if (group != null && group.size() > 1) {
						List<Map<String, Object>> targets = new ArrayList<>();
						java.util.Set<String> seen = new java.util.HashSet<>();
						for (String mk : group) {
							addKeyTarget(targets, seen, mk);
						}
						result.put("targets", targets);
						result.put("kind", "method");
					}
					return result;
				}
			}
		}
		result.put("found", false);
		return result;
	}

	private Map<String, Object> diskFindUsages(String id, int line, int col) {
		Map<String, Object> result = new LinkedHashMap<>();
		List<Map<String, Object>> usages = new ArrayList<>();
		result.put("usages", usages);
		result.put("usageFallback", false);
		String key = resolveKeyAt(id, line, col);
		result.put("name", key != null ? keyName(key) : null);
		if (key == null) {
			return result;
		}
		Map<String, String[]> lineCache = new java.util.HashMap<>();
		boolean truncated = false;
		for (String ln : rgXref("ref_s*.txt", "^" + rgRegex(key) + "\t")) {
			String[] f = ln.split("\t", -1);
			if (f.length < 3) {
				continue;
			}
			String useId = f[1];
			int useLine = parseIntSafe(f[2], 1);
			String text = "";
			String[] codeLines = lineCache.computeIfAbsent(useId, k -> {
				String c = searchIndex.codeOf(k);
				return c == null ? null : c.split("\n", -1);
			});
			if (codeLines != null && useLine >= 1 && useLine <= codeLines.length) {
				text = codeLines[useLine - 1].strip();
			}
			Map<String, Object> u = new LinkedHashMap<>();
			u.put("id", useId);
			u.put("fullName", diskFullName(useId));
			u.put("line", useLine);
			u.put("col", 0);
			u.put("text", text);
			usages.add(u);
			if (usages.size() >= MAX_USAGES) {
				truncated = true;
				break;
			}
		}
		result.put("truncated", truncated);
		return result;
	}

	private static int parseIntSafe(String s, int fallback) {
		try {
			return Integer.parseInt(s.trim());
		} catch (Exception e) {
			return fallback;
		}
	}

	/** Resolve the jadx node referenced at (line, col) inside class {@code id}, or null. */
	private JavaNode nodeAt(String id, int line, int col) {
		JadxDecompiler d = ensureLoaded();
		JavaClass cls = findClass(id);
		if (cls == null) {
			throw new IllegalArgumentException("class not found: " + id);
		}
		ICodeInfo info = freshCodeInfo(cls, id);
		String code = info.getCodeStr();
		int offset = Positions.toOffset(code, line, col);
		JavaNode n = d.getJavaNodeAtPosition(info, offset);
		if (n != null) {
			return n;
		}
		// Position-based resolution can miss a method/field DECLARATION inside a nested class (jadx
		// doesn't always annotate the declaration name — seen for callback impls like
		// ActionMenuView$ActionMenuPresenterCallback.onOpenSubMenu). Fall back to the member declared on
		// this line: getDefPos() is in the top class's code coordinates, matching `code`.
		return declAtLine(cls, lineStarts(code), line);
	}

	// A method or field whose declaration lands on `line` (searching the class and its inner classes).
	private JavaNode declAtLine(JavaClass cls, int[] starts, int line) {
		for (JavaMethod m : cls.getMethods()) {
			int dp = m.getDefPos();
			if (dp >= 0 && lineOf(starts, dp) == line) {
				return m;
			}
		}
		for (jadx.api.JavaField f : cls.getFields()) {
			int dp = f.getDefPos();
			if (dp >= 0 && lineOf(starts, dp) == line) {
				return f;
			}
		}
		for (JavaClass inner : cls.getInnerClasses()) {
			JavaNode r = declAtLine(inner, starts, line);
			if (r != null) {
				return r;
			}
		}
		return null;
	}

	public Map<String, Object> gotoDef(String id, int line, int col) {
		if (xrefReady()) {
			return diskGotoDef(id, line, col);
		}
		JavaNode node = nodeAt(id, line, col);
		Map<String, Object> result = new LinkedHashMap<>();
		if (node == null) {
			result.put("found", false);
			return result;
		}
		// A call on an interface/base-typed value resolves to the base method; offer every override
		// implementation too, so gd on a virtual call can jump to any concrete implementation. The
		// targets are lightweight (class id + method name, no per-target decompile) — the plugin opens
		// the class and relocates on the name — so even a functional interface with hundreds of
		// implementations stays fast; only the primary target's exact position is computed here.
		List<Map<String, Object>> targets = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		// Prefer the override group captured at export (survives the post-export unload). Fall back to
		// the live override attribute (fresh model: no-export / cached open) when it wasn't captured.
		java.util.List<String> group = overrideGroupOf(node);
		if (group != null) {
			for (String mk : group) {
				addKeyTarget(targets, seen, mk);
			}
		}
		if (targets.isEmpty()) {
			List<JavaNode> defs = new ArrayList<>();
			defs.add(node);
			if (node instanceof JavaMethod) {
				for (JavaMethod rel : ((JavaMethod) node).getOverrideRelatedMethods()) {
					if (rel != null) {
						defs.add(rel);
					}
				}
			}
			for (JavaNode d : defs) {
				JavaClass top = d.getTopParentClass();
				if (top == null) {
					continue;
				}
				if (!seen.add(top.getRawName() + "#" + d.getName())) {
					continue;
				}
				Map<String, Object> t = new LinkedHashMap<>();
				t.put("id", top.getRawName());
				t.put("fullName", top.getFullName());
				t.put("name", d.getName());
				t.put("rawName", (d instanceof JavaMethod) ? rawMethodName((JavaMethod) d) : d.getName());
				t.put("abstract", (d instanceof JavaMethod) && ((JavaMethod) d).getAccessFlags().isAbstract());
				targets.add(t);
			}
		}
		result.put("found", true);
		result.put("targets", targets);
		result.put("kind", (node instanceof JavaMethod) ? "method"
				: (node instanceof JavaClass) ? "class" : "other");

		// Primary target: the resolved node, with its exact position (one decompile) for the common
		// single-target direct jump and older clients.
		JavaClass ptop = node.getTopParentClass();
		int defPos = node.getDefPos();
		int[] lc = defPos >= 0
				? Positions.toLineCol(freshCodeInfo(ptop, ptop.getRawName()).getCodeStr(), defPos)
				: new int[] { 1, 0 };
		result.put("id", ptop.getRawName());
		result.put("fullName", ptop.getFullName());
		result.put("name", node.getName());
		result.put("rawName", (node instanceof JavaMethod) ? rawMethodName((JavaMethod) node) : node.getName());
		result.put("line", lc[0]);
		result.put("col", lc[1]);
		return result;
	}

	public Map<String, Object> findUsages(String id, int line, int col) {
		if (xrefReady()) {
			return diskFindUsages(id, line, col);
		}
		JavaNode node = nodeAt(id, line, col);
		Map<String, Object> result = new LinkedHashMap<>();
		List<Map<String, Object>> usages = new ArrayList<>();
		result.put("usages", usages);
		// With the usage graph disabled there is no getUseIn() data; tell the client to fall back to
		// a name-based text search over the exported sources.
		result.put("usageFallback", noUsage);
		if (node == null) {
			result.put("name", null);
			return result;
		}
		result.put("name", node.getName());
		// The searched symbol's own class (for generating a Frida hook of the target itself).
		JavaClass declaring = node.getTopParentClass();
		if (declaring != null) {
			result.put("targetId", declaring.getRawName());
			result.put("targetKind", (node instanceof JavaMethod) ? "method"
					: (node instanceof JavaClass) ? "class" : "other");
			result.put("targetRawName", (node instanceof JavaMethod) ? rawMethodName((JavaMethod) node) : node.getName());
		}

		// For a method, search the whole override-related set (the base declaration AND every
		// implementation), exactly as jadx-gui's Find Usages does. A virtual-dispatch call is recorded
		// against whichever declaration the call's static type names, so both directions matter:
		//   - gr on an override reaches calls made through a base type (interface/abstract/superclass);
		//   - gr on an interface/base method reaches calls made through a concrete implementation type.
		// (This is captured at export and survives the post-export unload; else we read the live attr.)
		List<JavaNode> targets = new ArrayList<>();
		targets.add(node);
		if (node instanceof JavaMethod) {
			java.util.List<String> group = overrideGroupOf(node);
			if (group != null) {
				String self = methodKey((JavaMethod) node);
				for (String mk : group) {
					if (mk.equals(self)) {
						continue;
					}
					JavaMethod jm = resolveMethodKey(mk);
					if (jm != null) {
						targets.add(jm);
					}
				}
			} else {
				for (JavaMethod rel : ((JavaMethod) node).getOverrideRelatedMethods()) {
					if (rel != null) {
						targets.add(rel);
					}
				}
			}
		}

		int budget = MAX_USAGES;
		boolean truncated = false;
		java.util.Set<String> seen = new java.util.HashSet<>();
		outer:
		for (JavaNode target : targets) {
			// Collect the unique top-level classes that reference this node, then ask each for the
			// precise offsets of the reference within its decompiled code.
			Map<String, JavaClass> classes = new LinkedHashMap<>();
			for (JavaNode use : target.getUseIn()) {
				JavaClass top = use.getTopParentClass();
				if (top != null) {
					classes.putIfAbsent(top.getRawName(), top);
				}
			}
			for (JavaClass cls : classes.values()) {
				// Use the cached layout: the reported line/col must match the buffer the plugin opens
				// for this class (getCode uses the same freshCodeInfo), or the jump lands in the wrong
				// place — jadx's parallel decompile is non-deterministic across calls.
				ICodeInfo info = freshCodeInfo(cls, cls.getRawName());
				String code = info.getCodeStr();
				for (int offset : cls.getUsePlacesFor(info, target)) {
					if (!seen.add(cls.getRawName() + ":" + offset)) {
						continue; // same call site reachable via several related methods
					}
					if (budget-- <= 0) {
						truncated = true;
						break outer;
					}
					int[] lc = Positions.toLineCol(code, offset);
					Map<String, Object> u = new LinkedHashMap<>();
					u.put("id", cls.getRawName());
					u.put("fullName", cls.getFullName());
					u.put("line", lc[0]);
					u.put("col", lc[1]);
					u.put("text", Positions.lineText(code, offset).strip());
					usages.add(u);
				}
			}
		}
		result.put("truncated", truncated);
		return result;
	}

	public Map<String, Object> rename(String id, int line, int col, String newName) throws Exception {
		ensureLoaded();
		JavaNode node = nodeAt(id, line, col);
		if (node == null) {
			throw new IllegalArgumentException("no symbol under cursor");
		}
		// A local variable is not a global node: it's identified by a code reference (its slot within
		// the method), so it needs a JadxCodeRename carrying the method's node ref + a VAR code ref.
		if (node instanceof JavaVariable) {
			return renameVariable((JavaVariable) node, newName);
		}
		// A constructor's name IS the class name — renaming the <init> method is a no-op in jadx, so
		// redirect to the declaring class (matching jadx-gui, where renaming a constructor renames the
		// class and every other constructor with it).
		if (node instanceof JavaMethod && ((JavaMethod) node).isConstructor()) {
			JavaClass dc = node.getDeclaringClass();
			if (dc != null) {
				node = dc;
			}
		}
		JadxNodeRef ref = JadxNodeRef.forJavaNode(node);
		if (ref == null) {
			throw new IllegalArgumentException("this element cannot be renamed");
		}
		List<ICodeRename> renames = new ArrayList<>(currentRenames());
		renames.removeIf(r -> ref.equals(r.getNodeRef()) && r.getCodeRef() == null);
		if (newName != null && !newName.isEmpty()) {
			renames.add(new JadxCodeRename(ref, newName));
		}
		codeData.setRenames(renames);
		applyCodeData();

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ok", true);
		result.put("newName", newName);
		result.put("project", projectFile.getAbsolutePath());
		return result;
	}

	// Rename a local variable via a code reference (method node ref + VAR code ref). An empty newName
	// clears any existing rename for that variable.
	private Map<String, Object> renameVariable(JavaVariable var, String newName) throws Exception {
		JadxNodeRef mthRef = JadxNodeRef.forMth(var.getMth());
		JadxCodeRef codeRef = JadxCodeRef.forVar(var.getVarNode());
		List<ICodeRename> renames = new ArrayList<>(currentRenames());
		renames.removeIf(r -> mthRef.equals(r.getNodeRef()) && codeRef.equals(r.getCodeRef()));
		if (newName != null && !newName.isEmpty()) {
			renames.add(new JadxCodeRename(mthRef, codeRef, newName));
		}
		codeData.setRenames(renames);
		applyCodeData();

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ok", true);
		result.put("newName", newName);
		result.put("kind", "variable");
		result.put("project", projectFile.getAbsolutePath());
		return result;
	}

	public Map<String, Object> comment(String id, int line, int col, String text) throws Exception {
		ensureLoaded();
		JavaNode node = nodeAt(id, line, col);
		if (node == null) {
			throw new IllegalArgumentException("no symbol under cursor");
		}
		JadxNodeRef ref = JadxNodeRef.forJavaNode(node);
		if (ref == null) {
			throw new IllegalArgumentException("this element cannot be commented");
		}
		List<ICodeComment> comments = new ArrayList<>(currentComments());
		comments.removeIf(c -> ref.equals(c.getNodeRef()) && c.getCodeRef() == null);
		if (text != null && !text.isEmpty()) {
			comments.add(new JadxCodeComment(ref, text));
		}
		codeData.setComments(comments);
		applyCodeData();

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ok", true);
		result.put("project", projectFile.getAbsolutePath());
		return result;
	}

	public Map<String, Object> saveProject(String path) throws IOException {
		if (inputFile == null) {
			throw new IllegalStateException("project not loaded");
		}
		File target = (path != null && !path.isEmpty()) ? new File(path) : projectFile;
		ProjectIO.save(target, inputFile, codeData, projectUiState);
		this.projectFile = target.getAbsoluteFile();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ok", true);
		result.put("project", projectFile.getAbsolutePath());
		return result;
	}

	/**
	 * Update the jadx-gui UI-state the plugin manages (openTabs / searchHistory / treeExpansionsV2 /
	 * cacheDir, in jadx-gui's own format) and persist it to the .jadx. The plugin calls this as tabs
	 * open/close and searches are made, so the project round-trips with jadx-gui.
	 */
	public Map<String, Object> setProjectState(JsonObject params) throws IOException {
		if (params != null) {
			for (String k : UI_STATE_KEYS) {
				if (params.has(k)) {
					com.google.gson.JsonElement v = params.get(k);
					if (v == null || v.isJsonNull()) {
						projectUiState.remove(k);
					} else {
						projectUiState.add(k, v);
					}
				}
			}
		}
		if (!temp && inputFile != null && projectFile != null) {
			ProjectIO.save(projectFile, inputFile, codeData, projectUiState);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ok", true);
		return result;
	}

	private List<ICodeRename> currentRenames() {
		return codeData.getRenames() == null ? List.of() : codeData.getRenames();
	}

	private List<ICodeComment> currentComments() {
		return codeData.getComments() == null ? List.of() : codeData.getComments();
	}

	/**
	 * Return the class's code, re-decompiling it if its cache predates the latest code-data change.
	 * {@code reloadCodeData()} only updates rename/comment mappings; cached decompiled text must be
	 * regenerated via {@link JavaClass#reload()} for changes to appear.
	 */
	private ICodeInfo freshCodeInfo(JavaClass cls, String id) {
		ICodeInfo cached = freshInfo.get(id);
		if (cached != null) {
			return cached;
		}
		// After an edit the cached text is stale (reloadCodeData only updates rename/comment mappings),
		// so regenerate via reload(); otherwise take the current decompile. Either way cache it so every
		// subsequent access to this class (getCode, gd, gr) sees the identical layout.
		ICodeInfo info = codeDataVersion > 0 ? cls.reload() : cls.getCodeInfo();
		freshInfo.put(id, info);
		return info;
	}

	/** Re-apply the code data to the live decompiler and persist the project file. */
	private void applyCodeData() throws IOException {
		jadx.reloadCodeData();
		this.codeDataVersion++;
		this.freshInfo.clear();
		this.packageIndex = null;
		this.classList = null;
		this.methodList = null;
		if (!temp) {
			saveProject(null);
		}
	}

	private Search search() {
		if (this.searchEngine == null) {
			this.searchEngine = new Search(this.emitter);
		}
		return this.searchEngine;
	}

	private Map<String, Object> startSearch(String kind, JsonObject params) {
		// When the on-disk index can serve the search (ripgrep over shards/name files), don't touch
		// the model — in lean mode that avoids re-materializing it. The in-memory fallback only runs
		// when the index isn't ready, in which case the model is still loaded.
		JadxDecompiler d = navServableFromDisk() ? null : ensureLoaded();
		String query = reqStr(params, "query");
		Search.Opts opts = new Search.Opts();
		if (params.has("limit") && !params.get("limit").isJsonNull()) {
			opts.limit = params.get("limit").getAsInt();
		}
		if (params.has("caseSensitive") && !params.get("caseSensitive").isJsonNull()) {
			opts.caseSensitive = params.get("caseSensitive").getAsBoolean();
		}
		if (params.has("regex") && !params.get("regex").isJsonNull()) {
			opts.regex = params.get("regex").getAsBoolean();
		}
		if (params.has("kind") && !params.get("kind").isJsonNull()) {
			opts.kind = params.get("kind").getAsString();
		}
		// Pass the index for both text (shard scan) and name (class/method name files) searches once
		// the export is ready; Search decides how to use it and falls back to the in-memory scan. The
		// index stays structurally valid across edits, so keep using ripgrep (just-renamed symbols may
		// read under their old name until the background refresh completes) rather than a slow scan.
		SearchIndex idx = navServableFromDisk() ? searchIndex : null;
		int searchId = search().start(d, kind, query, opts, idx, rgPath);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("searchId", searchId);
		return result;
	}

	/** Resolve a class by id, accepting either the original (raw) or the jadx alias full name. */
	private JavaClass findClass(String id) {
		JadxDecompiler d = ensureLoaded();
		JavaClass cls = d.searchJavaClassByOrigFullName(id);
		if (cls == null) {
			cls = d.searchJavaClassByAliasFullName(id);
		}
		return cls;
	}

	public Map<String, Object> getSmali(String id) {
		JadxDecompiler d = ensureLoaded();
		JavaClass cls = findClass(id);
		if (cls == null) {
			throw new IllegalArgumentException("class not found: " + id);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id);
		result.put("fullName", cls.getFullName());
		result.put("smali", cls.getSmali());
		return result;
	}

	public Map<String, Object> shutdown() {
		cancelExport();
		if (v2 != null) {
			try {
				v2.close();
			} catch (Exception ignore) {
				// best effort
			}
		}
		if (jadx != null) {
			try {
				jadx.close();
			} catch (Exception ignore) {
				// best effort
			}
		}
		Thread exit = new Thread(() -> {
			try {
				Thread.sleep(50);
			} catch (InterruptedException ignore) {
				Thread.currentThread().interrupt();
			}
			System.exit(0);
		});
		exit.setDaemon(true);
		exit.start();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ok", true);
		return result;
	}

	// --- helpers -------------------------------------------------------------

	// Build (parse + load) a jadx model for the given input and code data.
	private JadxDecompiler buildModel(File input, JadxCodeData cd) {
		JadxArgs args = new JadxArgs();
		args.getInputFiles().add(input);
		args.setCodeData(cd);
		// Emit partially-decompiled ("inconsistent") code instead of a stub for methods jadx can't
		// fully decompile (jadx-gui's "show inconsistent code" / --show-bad-code), so obfuscated
		// classes still show something browsable/searchable rather than a comment. Toggleable.
		args.setShowInconsistentCode(showInconsistentCode);
		// Don't retain decompiled code in memory. On huge APKs (e.g. 400k classes) the default
		// in-memory cache exhausts the heap and GC-thrashes during the full export; without it the
		// export streams to disk at low, constant memory. Browsing re-decompiles per class (cheap,
		// and the plugin caches the buffer), and search reads the exported files via ripgrep.
		args.setCodeCache(NoOpCodeCache.INSTANCE);
		// Optionally skip building the global usage/xref graph at load (a large, permanent heap cost
		// on huge APKs). find-usages then falls back to a ripgrep scan of the exported sources.
		if (noUsage) {
			args.setUsageInfoCache(new UsageOff.Cache());
		}
		JadxDecompiler d = new JadxDecompiler(args);
		d.load();
		return d;
	}

	/**
	 * The export is ready to serve browse/search/navigate from disk. Keep the parsed model resident
	 * (default) so the first edit is instant, or drop it to a low memory baseline (--drop-model).
	 */
	private void finishLeanServing() {
		if (!lean) {
			return;
		}
		leanServing = true;
		if (keepModel) {
			emitter.emit("modelWarm", Map.of("lean", true));
		} else {
			unloadModel();
		}
	}

	/** Lean mode: drop the in-memory model once the export can serve browse/search/navigate. */
	private synchronized void unloadModel() {
		JadxDecompiler d = this.jadx;
		if (d == null) {
			return;
		}
		this.jadx = null;
		this.packageIndex = null;
		this.classList = null;
		this.methodList = null;
		try {
			d.close();
		} catch (Exception ignore) {
			// best effort
		}
		System.gc();
		emitter.emit("modelUnloaded", Map.of("lean", true));
	}

	/**
	 * Build the parsed model on a background thread and publish it, so browse/search/navigate stay
	 * responsive (served from disk) during the multi-minute parse instead of freezing the daemon. Used
	 * to pre-warm after a cached fast-open so the first rename/comment is instant. The build runs OFF
	 * the dispatch lock; only the brief publish takes it.
	 */
	private void warmModelAsync() {
		final File in = this.inputFile;
		final JadxCodeData cd = this.codeData;
		if (in == null) {
			return;
		}
		synchronized (this) {
			if (jadx != null || modelWarming) {
				return; // already resident or a warm is in flight
			}
			modelWarming = true;
		}
		Thread t = new Thread(() -> {
			JadxDecompiler d = null;
			try {
				d = buildModel(in, cd);
			} catch (Throwable e) {
				System.err.println("[jadxd] model warm failed: " + e);
			}
			synchronized (this) {
				modelWarming = false;
				if (d != null) {
					if (jadx == null && in.equals(this.inputFile)) {
						this.jadx = d;
						this.packageIndex = null;
						emitter.emit("modelWarm", Map.of("lean", true));
					} else {
						try {
							d.close(); // lost the race (an edit already reloaded) or the project changed
						} catch (Exception ignore) {
							// best effort
						}
					}
				}
				this.notifyAll(); // wake any edit waiting on the warm (reloadModel)
			}
		}, "jadx-warm");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	private volatile boolean modelWarming = false;
	private volatile boolean refreshing = false;

	/**
	 * Rebuild the on-disk export to match the current code data (renames/comments), then atomically
	 * swap it in for what we're serving. Runs entirely in the background: the fresh index is built into
	 * sibling ".next" directories (off the dispatch lock, so browse/search stay live on the old index),
	 * and only the final directory swap + reload takes the lock — at which point no request is reading
	 * the files (all dispatch, including ripgrep, is serialized on {@code this}). Clears the stale flag.
	 */
	private void refreshIndexAsync() {
		final File input = this.inputFile;
		if (input == null) {
			return;
		}
		synchronized (this) {
			if (refreshing) {
				return;
			}
			refreshing = true;
		}
		final File cIdx = this.indexDir, cNm = this.namesDir, cXr = this.xrefDir, cMeta = this.metaDir;
		final File bIdx = nextDir(cIdx), bNm = nextDir(cNm), bXr = nextDir(cXr), bMeta = nextDir(cMeta);
		final java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean(false);
		this.exportCancel = cancel;
		Thread t = new Thread(() -> {
			boolean builtLocally = false;
			JadxDecompiler d = null;
			try {
				synchronized (this) {
					d = this.jadx;
				}
				// Prefer the model being pre-warmed alongside us — wait briefly for it to publish so we
				// don't parse the APK a second time. buildModel already runs on the warm thread.
				for (int w = 0; d == null && modelWarming && w < 1800; w++) {
					Thread.sleep(100);
					synchronized (this) {
						d = this.jadx;
					}
				}
				if (d == null) {
					d = buildModel(input, this.codeData); // parse off-lock; publish below if keeping
					builtLocally = true;
				}
				if (builtLocally && keepModel) {
					synchronized (this) {
						if (this.jadx == null && input.equals(this.inputFile)) {
							this.jadx = d;
							this.packageIndex = null;
							builtLocally = false; // now owned by the session
							emitter.emit("modelWarm", Map.of("lean", true));
						}
					}
				}
				long inputSig = inputSignature(input, showInconsistentCode);
				long fullSig = signature(input, this.codeData, showInconsistentCode);
				deleteDir(bIdx);
				deleteDir(bNm);
				deleteDir(bXr);
				deleteDir(bMeta);
				SearchIndex fresh = buildIndex(d, bIdx, bNm, bXr, bMeta, inputSig, fullSig, cancel);
				if (fresh == null || cancel.get()) {
					deleteDir(bIdx);
					deleteDir(bNm);
					deleteDir(bXr);
					deleteDir(bMeta);
					return;
				}
				synchronized (this) {
					if (!input.equals(this.inputFile) || cancel.get()) {
						return; // project changed or cancelled — discard the fresh build
					}
					// No request is reading the served files here (dispatch, incl. rg, holds `this`),
					// so it is safe to replace the canonical directories in place.
					replaceDir(bIdx, cIdx);
					replaceDir(bNm, cNm);
					replaceDir(bXr, cXr);
					replaceDir(bMeta, cMeta);
					this.searchIndex = SearchIndex.load(cIdx, cNm, cXr, cMeta);
					this.diskPkgIndex = null;
					this.diskNames = null;
					this.indexNamesStale = false;
					this.sourcesReady = true;
				}
				emitter.emit("indexRefreshed", Map.of("lean", true));
			} catch (Throwable e) {
				System.err.println("[jadxd] index refresh error: " + e);
				deleteDir(bIdx);
				deleteDir(bNm);
				deleteDir(bXr);
				deleteDir(bMeta);
			} finally {
				refreshing = false;
				if (builtLocally && d != null) {
					try {
						d.close(); // transient model (drop-model mode) — release it
					} catch (Exception ignore) {
						// best effort
					}
				}
			}
		}, "jadx-index-refresh");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	// Sibling scratch directory ("<name>.next") that a background rebuild writes into before the swap.
	private static File nextDir(File dir) {
		return new File(dir.getParentFile(), dir.getName() + ".next");
	}

	// Move src onto dst (same parent → same filesystem), replacing dst. Caller holds `this`, so no
	// reader touches these files during the swap.
	private static void replaceDir(File src, File dst) {
		deleteDir(dst);
		if (!src.renameTo(dst)) {
			// Rename can fail if a stray handle lingers; fall back to a recursive copy + drop.
			copyDir(src, dst);
			deleteDir(src);
		}
	}

	private static void copyDir(File src, File dst) {
		if (src == null || !src.exists()) {
			return;
		}
		dst.mkdirs();
		File[] files = src.listFiles();
		if (files == null) {
			return;
		}
		for (File f : files) {
			File target = new File(dst, f.getName());
			if (f.isDirectory()) {
				copyDir(f, target);
			} else {
				try {
					java.nio.file.Files.copy(f.toPath(), target.toPath(),
							java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		}
	}

	/** Rebuild the model on demand (lean mode) for an op that needs jadx semantics. */
	private synchronized JadxDecompiler reloadModel() {
		if (jadx != null) {
			return jadx;
		}
		// A background warm may already be parsing the APK. Wait for it rather than parsing a second
		// time. wait() releases `this` (fully, despite dispatch's reentrant hold), so the warm thread
		// can publish and other RPCs stay responsive during the wait.
		while (jadx == null && modelWarming) {
			try {
				this.wait(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		if (jadx != null) {
			return jadx;
		}
		emitter.emit("modelReloading", Map.of("reason", "semantic op needs the jadx model"));
		this.jadx = buildModel(this.inputFile, this.codeData);
		this.packageIndex = null;
		return jadx;
	}

	/** True when exact class TEXT should be served from the on-disk export (verbatim, current names). */
	private boolean servingFromDisk() {
		// Serve exact text from disk only while it matches what we'd decompile: no in-session edit and
		// names not stale. Otherwise re-decompile from the model for correct names. A resident model
		// doesn't change this (it only backs edits).
		return leanServing && sourcesReady && searchIndex != null && codeDataVersion == 0;
	}

	/**
	 * True when the on-disk export can back NAVIGATION and SEARCH (go-to-def, find-usages, name/text
	 * search). Unlike {@link #servingFromDisk()} this stays true across in-session edits: the index is
	 * structurally valid (same classes, node identities, near-identical positions) even after a rename,
	 * so gd/gr/search keep using ripgrep over the shards instead of the slow in-memory scan or the
	 * usage-less model. Names for just-edited symbols may lag until the background refresh swaps in.
	 */
	private boolean navServableFromDisk() {
		return leanServing && sourcesReady && searchIndex != null;
	}

	private JadxDecompiler ensureLoaded() {
		if (jadx == null) {
			if (lean && inputFile != null) {
				return reloadModel();
			}
			// v2 mode serves browse/code/search from SQLite without a model; an advanced op (gd/gr/
			// rename/resources/smali) fell through, so build the full jadx model on demand. One-time
			// cost (minutes + multi-GB on a huge APK); reused for later ops.
			if (v2 != null && inputFile != null) {
				System.err.println("[jadxd] v2: building jadx model on demand for an advanced operation "
						+ "(this is the heavy path — first gd/gr/rename on a large APK)");
				this.jadx = buildModel(inputFile, codeData);
				return this.jadx;
			}
			throw new IllegalStateException("project not loaded");
		}
		return jadx;
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	private static String reqStr(JsonObject o, String key) {
		String v = str(o, key);
		if (v == null) {
			throw new IllegalArgumentException("missing required param: " + key);
		}
		return v;
	}

	private static int reqInt(JsonObject o, String key) {
		if (!o.has(key) || o.get(key).isJsonNull()) {
			throw new IllegalArgumentException("missing required param: " + key);
		}
		return o.get(key).getAsInt();
	}

	private static int optInt(JsonObject o, String key, int fallback) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : fallback;
	}
}
