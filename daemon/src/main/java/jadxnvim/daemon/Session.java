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
import jadx.api.data.impl.JadxNodeRef;
import jadx.api.metadata.ICodeAnnotation;
import jadx.api.metadata.ICodeMetadata;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.metadata.annotations.NodeDeclareRef;
import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.IMethodDetails;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.MethodOverrideAttr;

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
	private static long signature(File input, JadxCodeData cd, boolean showInconsistentCode) {
		// Fold in length AND mtime so replacing the input with different content of the same byte
		// length (e.g. a rebuilt APK) invalidates the cache instead of serving a stale export. The
		// showInconsistentCode flag changes the decompiled output, so it's part of the signature too.
		long h = input.length() * 31 + INDEX_FORMAT_VERSION;
		h = h * 1000003 + input.lastModified();
		h = h * 1000003 + (showInconsistentCode ? 1 : 0);
		h = h * 1000003 + codeDataHash(cd);
		return h;
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
	private final Map<String, ICodeInfo> freshInfo = new java.util.HashMap<>();

	// Method override hierarchy, captured during export. jadx's live override attribute is torn down
	// by the per-class unload() that bounds export memory (re-decompiling an interface later yields an
	// impl-less attr), so we snapshot it while every class is still processed. Key = "rawName#name#argc".
	private final java.util.List<String[]> overrideEdges = java.util.Collections.synchronizedList(new ArrayList<>());
	private final Map<String, Boolean> overrideAbstract = new java.util.concurrent.ConcurrentHashMap<>();
	private final Map<String, String> overrideRaw = new java.util.concurrent.ConcurrentHashMap<>(); // key -> raw method name
	private volatile Map<String, String> overrideRoot; // methodKey -> union-find root
	private volatile Map<String, java.util.List<String>> overrideGroup; // root -> member method keys
	private volatile Map<String, java.util.List<String>> overrideByName; // "classId#name" -> member keys (lean/disk)
	// Directed "overrides" edges [childKey, baseKey] captured during export, and their transitive
	// closure per method (a method -> every base/ancestor method it overrides). find-usages expands to
	// these so a call made through a base type is reported, whether the base is abstract or concrete.
	private final java.util.List<String[]> overrideBaseEdges = java.util.Collections.synchronizedList(new ArrayList<>());
	private volatile Map<String, java.util.List<String>> overrideBases; // childKey -> all ancestor keys

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

	// The MethodInfos this method overrides (its direct base declarations, abstract or concrete), from
	// jadx's override attribute. Empty if the method overrides nothing or the attr isn't present.
	private static java.util.Set<MethodInfo> baseInfosOf(JavaMethod jm) {
		java.util.Set<MethodInfo> out = new java.util.HashSet<>();
		try {
			MethodOverrideAttr attr = jm.getMethodNode().get(AType.METHOD_OVERRIDE);
			if (attr != null) {
				for (IMethodDetails b : attr.getBaseMethods()) {
					MethodInfo bi = b.getMethodInfo();
					if (bi != null) {
						out.add(bi);
					}
				}
			}
		} catch (Throwable ignore) {
			// best-effort
		}
		return out;
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
				java.util.Set<MethodInfo> bases = baseInfosOf(jm); // which related methods jm overrides
				for (JavaMethod r : rel) {
					if (r == null) {
						continue;
					}
					String rk = methodKey(r);
					overrideAbstract.putIfAbsent(rk, r.getAccessFlags().isAbstract());
					overrideRaw.putIfAbsent(rk, rawMethodName(r));
					overrideEdges.add(new String[] { k, rk });
					try {
						if (bases.contains(r.getMethodNode().getMethodInfo())) {
							overrideBaseEdges.add(new String[] { k, rk }); // k overrides (is below) r
						}
					} catch (Throwable ignore) {
						// best-effort
					}
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

		// Transitive closure of the directed "overrides" edges: childKey -> every ancestor it overrides
		// (walks A<-B<-C so gr on C reaches A even when jadx only records the immediate base).
		Map<String, java.util.List<String>> directBases = new java.util.HashMap<>();
		String[][] bedges;
		synchronized (overrideBaseEdges) {
			bedges = overrideBaseEdges.toArray(new String[0][]);
		}
		for (String[] e : bedges) {
			directBases.computeIfAbsent(e[0], x -> new ArrayList<>()).add(e[1]);
		}
		Map<String, java.util.List<String>> bases = new java.util.HashMap<>();
		for (String k : directBases.keySet()) {
			java.util.LinkedHashSet<String> acc = new java.util.LinkedHashSet<>();
			java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>(directBases.get(k));
			while (!stack.isEmpty()) {
				String b = stack.pop();
				if (b.equals(k) || !acc.add(b)) {
					continue;
				}
				java.util.List<String> next = directBases.get(b);
				if (next != null) {
					stack.addAll(next);
				}
			}
			bases.put(k, new ArrayList<>(acc));
		}
		this.overrideBases = bases;
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
			this.noUsage = true; // the graph would be wasted work if we drop the model anyway
		}
	}

	public void setShowInconsistentCode(boolean show) {
		this.showInconsistentCode = show;
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
		overrideBases = null;
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
		long sig = signature(input, cd, showInconsistentCode);

		// Lean fast-open: with a valid cached export, skip building the jadx model entirely (no
		// multi-GB parse peak) and serve browse/search/tree from disk right away. The model is built
		// on demand the first time a semantic op needs it.
		if (lean && export && !temp && SearchIndex.isValid(metaDir, sig)
				&& new File(namesDir, SearchIndex.classesName(0)).isFile()) {
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
			emitter.emit("modelUnloaded", Map.of("lean", true));
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
				// Signature includes the index format version and the code data, so a format change or
				// a rename/comment (which changes names but not the input) invalidates the cache.
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
					emitter.emit("loadDone", Map.of("total", 0, "cached", true));
					if (lean) {
						unloadModel();
					}
					return;
				}
				searchIndex = buildIndex(d, idxDir, nmDir, xrDir, mDir, sig, cancel);
				if (searchIndex != null && !cancel.get()) {
					sourcesReady = true;
					writeResourceList(d); // so lean mode can list resources without reloading the model
					emitter.emit("loadDone", Map.of("total", 1));
					if (lean) {
						unloadModel();
					}
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

	private SearchIndex buildIndex(JadxDecompiler d, File idxDir, File nmDir, File xrDir, File mDir, long sig,
			java.util.concurrent.atomic.AtomicBoolean cancel) throws Exception {
		List<JavaClass> all = d.getClasses();
		int total = all.size();
		boolean withXref = lean;
		// fresh override capture for this build
		overrideEdges.clear();
		overrideBaseEdges.clear();
		overrideAbstract.clear();
		overrideRaw.clear();
		overrideRoot = null;
		overrideGroup = null;
		overrideByName = null;
		overrideBases = null;

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
			return builder.finish(mDir, sig);
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
		// Process in batches; checkpoint between batches (no worker active) so a later OOM-kill
		// resumes from the last checkpoint instead of re-indexing everything.
		int batch = 20000;
		try {
			String bp = System.getProperty("jadxnvim.indexBatch");
			if (bp != null) {
				batch = Math.max(1, Integer.parseInt(bp.trim()));
			}
		} catch (Exception ignore) {
			// keep default
		}
		final int BATCH = batch;
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
				// checkpoint: no workers active now, so positions match what's on disk. Write the
				// (cosmetic) progress gate first, then checkpoint() commits atomically via .pos last —
				// so the last durable write is always the authoritative one.
				doneAll.addAll(batchIds);
				writeProgress(progressFile, doneAll.size(), total, sig);
				b.checkpoint(mDir);
			}
		} finally {
			pool.shutdown();
		}
		if (cancel.get()) {
			return null;
		}
		SearchIndex idx = b.finish(mDir, sig);
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
		// Lean mode: read the exported copy straight off disk (no model, no re-decompile) as long as
		// the project is unedited. This is also exactly the text the search index was built from, so
		// search line numbers land precisely.
		if (servingFromDisk() && codeDataVersion == 0 && searchIndex.hasCode(id)) {
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
		int offset = Positions.toOffset(info.getCodeStr(), line, col);
		return d.getJavaNodeAtPosition(info, offset);
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
		int[] lc = defPos >= 0 ? Positions.toLineCol(ptop.getCodeInfo().getCodeStr(), defPos) : new int[] { 1, 0 };
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

		// For a method, also search the base methods it overrides: a call made through a base-typed
		// value (interface or abstract/concrete superclass) is recorded against that base declaration,
		// so find-usages on a concrete override would otherwise miss those virtual-dispatch call sites.
		// We add exactly the ANCESTOR methods (the ones this method overrides, up the hierarchy) — not
		// sibling overrides, so we never report direct calls to other implementations.
		List<JavaNode> targets = new ArrayList<>();
		targets.add(node);
		if (node instanceof JavaMethod) {
			java.util.List<String> baseKeys = overrideBases == null ? null : overrideBases.get(methodKey((JavaMethod) node));
			if (baseKeys != null) {
				for (String mk : baseKeys) {
					JavaMethod jm = resolveMethodKey(mk);
					if (jm != null) {
						targets.add(jm);
					}
				}
			} else {
				// live fallback (non-exported / attr intact): the method's base declarations
				java.util.Set<MethodInfo> bases = baseInfosOf((JavaMethod) node);
				for (JavaMethod rel : ((JavaMethod) node).getOverrideRelatedMethods()) {
					try {
						if (rel != null && bases.contains(rel.getMethodNode().getMethodInfo())) {
							targets.add(rel);
						}
					} catch (Throwable ignore) {
						// best-effort
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
				ICodeInfo info = cls.getCodeInfo();
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
		if (codeDataVersion > 0) {
			// After an edit, reload once and CACHE the ICodeInfo. With NoOpCodeCache, getCodeInfo()
			// re-decompiles on every call and jadx's parallel decompile isn't deterministic, so
			// re-fetching would hand getCode and gd/gr different line/offset layouts -> broken xrefs.
			// Caching the reloaded copy keeps every access to a class consistent. Bounded to classes
			// touched since the last edit; cleared on the next edit.
			ICodeInfo cached = freshInfo.get(id);
			if (cached != null) {
				return cached;
			}
			ICodeInfo info = cls.reload();
			freshInfo.put(id, info);
			return info;
		}
		return cls.getCodeInfo();
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
		JadxDecompiler d = servingFromDisk() ? null : ensureLoaded();
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
		// the export is ready; Search decides how to use it and falls back to the in-memory scan.
		// After an edit (codeDataVersion > 0) the export is stale, so search the live model instead.
		SearchIndex idx = (sourcesReady && codeDataVersion == 0) ? searchIndex : null;
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

	/** Rebuild the model on demand (lean mode) for an op that needs jadx semantics. */
	private synchronized JadxDecompiler reloadModel() {
		if (jadx != null) {
			return jadx;
		}
		emitter.emit("modelReloading", Map.of("reason", "semantic op needs the jadx model"));
		this.jadx = buildModel(this.inputFile, this.codeData);
		this.packageIndex = null;
		return jadx;
	}

	/** True when running lean with the model currently dropped (serve from disk). */
	private boolean servingFromDisk() {
		// After an in-session edit (rename/comment) the on-disk export is stale, so stop serving from
		// it and use the (now-loaded) model instead.
		return jadx == null && sourcesReady && searchIndex != null && codeDataVersion == 0;
	}

	private JadxDecompiler ensureLoaded() {
		if (jadx == null) {
			if (lean && inputFile != null) {
				return reloadModel();
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
