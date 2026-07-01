package jadxnvim.daemon;

import java.io.File;
import java.io.IOException;
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
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.data.ICodeComment;
import jadx.api.data.ICodeRename;
import jadx.api.data.impl.JadxCodeComment;
import jadx.api.data.impl.JadxCodeData;
import jadx.api.data.impl.JadxCodeRename;
import jadx.api.data.impl.JadxNodeRef;

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

	private Emitter emitter = (m, p) -> {
	};
	private boolean prefetch = false;
	private java.util.concurrent.atomic.AtomicBoolean warmupCancel;
	private JadxDecompiler jadx;
	private String inputPath;
	private Map<String, List<JavaClass>> packageIndex;
	private List<Map<String, Object>> classList;
	private List<Map<String, Object>> methodList;
	private Search searchEngine;
	private JadxCodeData codeData = new JadxCodeData();
	private File inputFile;
	private File projectFile;
	// Bumped whenever code data changes; classes decompiled at an older version need a reload.
	private int codeDataVersion = 0;
	private final java.util.Set<String> freshCode = new java.util.HashSet<>();

	public void setEmitter(Emitter emitter) {
		this.emitter = emitter;
	}

	public void setInitialInput(String path) {
		this.inputPath = path;
	}

	public void setPrefetch(boolean prefetch) {
		this.prefetch = prefetch;
	}

	public Object dispatch(String method, JsonObject params) throws Exception {
		switch (method) {
			case "loadProject":
				return loadProject(str(params, "path"));
			case "getPackages":
				return getPackages();
			case "getClasses":
				return getClasses(reqStr(params, "package"));
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
		cancelWarmup();
		File given = new File(this.inputPath);
		if (!given.exists()) {
			throw new IllegalArgumentException("input not found: " + given.getAbsolutePath());
		}

		File input;
		JadxCodeData cd;
		File projFile;
		if (given.getName().toLowerCase().endsWith(".jadx")) {
			ProjectIO.Loaded loaded = ProjectIO.load(given);
			if (loaded.input == null || !loaded.input.exists()) {
				throw new IllegalArgumentException("project input not found, referenced by " + given.getName());
			}
			input = loaded.input;
			cd = loaded.codeData;
			projFile = given.getAbsoluteFile();
		} else {
			input = given;
			cd = new JadxCodeData();
			projFile = defaultProjectFile(input);
		}

		JadxArgs args = new JadxArgs();
		args.getInputFiles().add(input);
		args.setCodeData(cd);

		JadxDecompiler decompiler = new JadxDecompiler(args);
		decompiler.load();

		JadxDecompiler previous = this.jadx;
		this.jadx = decompiler;
		this.codeData = cd;
		this.inputFile = input.getAbsoluteFile();
		this.projectFile = projFile;
		this.packageIndex = null;
		this.classList = null;
		this.methodList = null;
		this.codeDataVersion = 0;
		this.freshCode.clear();
		if (previous != null) {
			try {
				previous.close();
			} catch (Exception ignore) {
				// best effort
			}
		}

		Map<String, Object> info = new LinkedHashMap<>();
		info.put("input", input.getAbsolutePath());
		info.put("project", projFile.getAbsolutePath());
		info.put("classes", decompiler.getClassesWithInners().size());
		info.put("renames", cd.getRenames() == null ? 0 : cd.getRenames().size());
		info.put("comments", cd.getComments() == null ? 0 : cd.getComments().size());
		emitter.emit("ready", info);

		if (prefetch) {
			startWarmup(decompiler);
		}
		return info;
	}

	/**
	 * Background pass that decompiles every top-level class so the client can show a real 0-100%
	 * progress. Browsing stays available throughout; this only pre-fills the code cache. Runs at
	 * low priority and is cancelled when a new project loads or the daemon shuts down.
	 */
	private void startWarmup(JadxDecompiler d) {
		java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean(false);
		this.warmupCancel = cancel;
		Thread t = new Thread(() -> {
			try {
				List<JavaClass> all = d.getClasses();
				int total = all.size();
				if (total == 0) {
					emitter.emit("loadDone", Map.of("total", 0));
					return;
				}
				int done = 0;
				int lastPct = -1;
				for (JavaClass cls : all) {
					if (cancel.get()) {
						return;
					}
					try {
						cls.getCodeInfo();
					} catch (Throwable ignore) {
						// keep going; some classes fail to decompile
					}
					done++;
					int pct = (int) ((long) done * 100 / total);
					if (pct != lastPct) {
						lastPct = pct;
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("done", done);
						m.put("total", total);
						m.put("percent", pct);
						emitter.emit("loadProgress", m);
					}
				}
				if (!cancel.get()) {
					emitter.emit("loadDone", Map.of("total", total));
				}
			} catch (Throwable err) {
				System.err.println("[jadxd] warmup error: " + err);
			}
		}, "jadx-warmup");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	private void cancelWarmup() {
		if (warmupCancel != null) {
			warmupCancel.set(true);
			warmupCancel = null;
		}
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

	/** Return the top-level classes of a single package (lazy tree expansion). */
	public Map<String, Object> getClasses(String pkgName) {
		ensureLoaded();
		String key = pkgName == null ? "" : pkgName;
		List<JavaClass> classes = packageIndex().getOrDefault(key, List.of());
		List<Map<String, Object>> out = new ArrayList<>(classes.size());
		for (JavaClass cls : classes) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("id", cls.getRawName());
			entry.put("name", cls.getName());
			entry.put("fullName", cls.getFullName());
			out.add(entry);
		}
		out.sort(Comparator.comparing(m -> (String) m.get("name")));
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("package", key);
		result.put("classes", out);
		return result;
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
		JadxDecompiler d = ensureLoaded();
		JavaClass cls = d.searchJavaClassByOrigFullName(id);
		if (cls == null) {
			throw new IllegalArgumentException("class not found: " + id);
		}
		List<JavaMethod> methods = cls.getMethods();
		if (index < 0 || index >= methods.size()) {
			throw new IllegalArgumentException("method index out of range: " + index);
		}
		JavaMethod mth = methods.get(index);
		ICodeInfo info = freshCodeInfo(cls, id);
		int pos = mth.getDefPos();
		int[] lc = pos >= 0 ? Positions.toLineCol(info.getCodeStr(), pos) : new int[] { 1, 0 };
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id);
		result.put("fullName", cls.getFullName());
		result.put("line", lc[0]);
		result.put("col", lc[1]);
		return result;
	}

	public Map<String, Object> getCode(String id) {
		JadxDecompiler d = ensureLoaded();
		JavaClass cls = d.searchJavaClassByOrigFullName(id);
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

	/** Resolve the jadx node referenced at (line, col) inside class {@code id}, or null. */
	private JavaNode nodeAt(String id, int line, int col) {
		JadxDecompiler d = ensureLoaded();
		JavaClass cls = d.searchJavaClassByOrigFullName(id);
		if (cls == null) {
			throw new IllegalArgumentException("class not found: " + id);
		}
		ICodeInfo info = freshCodeInfo(cls, id);
		int offset = Positions.toOffset(info.getCodeStr(), line, col);
		return d.getJavaNodeAtPosition(info, offset);
	}

	public Map<String, Object> gotoDef(String id, int line, int col) {
		JavaNode node = nodeAt(id, line, col);
		Map<String, Object> result = new LinkedHashMap<>();
		if (node == null) {
			result.put("found", false);
			return result;
		}
		JavaClass target = node.getTopParentClass();
		String targetCode = target.getCodeInfo().getCodeStr();
		int defPos = node.getDefPos();
		int[] lc = defPos >= 0 ? Positions.toLineCol(targetCode, defPos) : new int[] { 1, 0 };
		result.put("found", true);
		result.put("id", target.getRawName());
		result.put("fullName", target.getFullName());
		result.put("name", node.getName());
		result.put("line", lc[0]);
		result.put("col", lc[1]);
		return result;
	}

	public Map<String, Object> findUsages(String id, int line, int col) {
		JavaNode node = nodeAt(id, line, col);
		Map<String, Object> result = new LinkedHashMap<>();
		List<Map<String, Object>> usages = new ArrayList<>();
		result.put("usages", usages);
		if (node == null) {
			result.put("name", null);
			return result;
		}
		result.put("name", node.getName());

		// Collect the unique top-level classes that reference this node, then ask each for the
		// precise offsets of the reference within its decompiled code.
		Map<String, JavaClass> classes = new LinkedHashMap<>();
		for (JavaNode use : node.getUseIn()) {
			JavaClass top = use.getTopParentClass();
			if (top != null) {
				classes.putIfAbsent(top.getRawName(), top);
			}
		}

		int budget = MAX_USAGES;
		boolean truncated = false;
		for (JavaClass cls : classes.values()) {
			ICodeInfo info = cls.getCodeInfo();
			String code = info.getCodeStr();
			List<Integer> places = cls.getUsePlacesFor(info, node);
			for (int offset : places) {
				if (budget-- <= 0) {
					truncated = true;
					break;
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
			if (truncated) {
				break;
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
		ProjectIO.save(target, inputFile, codeData);
		this.projectFile = target.getAbsoluteFile();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ok", true);
		result.put("project", projectFile.getAbsolutePath());
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
		if (codeDataVersion > 0 && !freshCode.contains(id)) {
			ICodeInfo info = cls.reload();
			freshCode.add(id);
			return info;
		}
		return cls.getCodeInfo();
	}

	/** Re-apply the code data to the live decompiler and persist the project file. */
	private void applyCodeData() throws IOException {
		jadx.reloadCodeData();
		this.codeDataVersion++;
		this.freshCode.clear();
		this.packageIndex = null;
		this.classList = null;
		this.methodList = null;
		saveProject(null);
	}

	private Search search() {
		if (this.searchEngine == null) {
			this.searchEngine = new Search(this.emitter);
		}
		return this.searchEngine;
	}

	private Map<String, Object> startSearch(String kind, JsonObject params) {
		JadxDecompiler d = ensureLoaded();
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
		int searchId = search().start(d, kind, query, opts);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("searchId", searchId);
		return result;
	}

	public Map<String, Object> getSmali(String id) {
		JadxDecompiler d = ensureLoaded();
		JavaClass cls = d.searchJavaClassByOrigFullName(id);
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
		cancelWarmup();
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

	private JadxDecompiler ensureLoaded() {
		if (jadx == null) {
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
