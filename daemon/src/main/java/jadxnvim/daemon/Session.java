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
import jadx.api.impl.NoOpCodeCache;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.data.ICodeComment;
import jadx.api.data.ICodeRename;
import jadx.api.data.impl.JadxCodeComment;
import jadx.api.data.impl.JadxCodeData;
import jadx.api.data.impl.JadxCodeRename;
import jadx.api.data.impl.JadxNodeRef;
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
	private static final long INDEX_FORMAT_VERSION = 3;

	private Emitter emitter = (m, p) -> {
	};
	private boolean export = true;
	private boolean temp = false;
	private boolean noUsage = false;
	private java.util.concurrent.atomic.AtomicBoolean exportCancel;
	private File exportDir;
	private File indexDir;
	private File namesDir;
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
		cancelExport();
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
		this.exportDir = new File(projFile.getAbsoluteFile().getParentFile(), stripExt(projFile.getName()) + ".jadxnvim");
		this.indexDir = new File(exportDir, "index");
		this.namesDir = new File(exportDir, "index-names");
		this.metaDir = new File(exportDir, "index-meta");
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
				ProjectIO.save(projFile, this.inputFile, this.codeData);
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
		File mDir = this.metaDir;
		File input = this.inputFile;
		Thread t = new Thread(() -> {
			try {
				// Signature includes an index-format version so a format change (e.g. the method
				// index moving from JavaClass.getMethods() order to the raw MethodNode order that
				// memberPos now uses) invalidates stale caches and forces a rebuild.
				long sig = input.length() * 31 + INDEX_FORMAT_VERSION;
				// Reuse the cache only if it also has the name index (older caches predate it, so
				// they rebuild once to gain fast class/method search).
				boolean hasNames = new File(nmDir, SearchIndex.classesName(0)).isFile();
				if (SearchIndex.isValid(mDir, sig) && hasNames) {
					searchIndex = SearchIndex.load(idxDir, nmDir, mDir);
					sourcesReady = true;
					emitter.emit("loadDone", Map.of("total", 0, "cached", true));
					return;
				}
				searchIndex = buildIndex(d, idxDir, nmDir, mDir, sig, cancel);
				if (searchIndex != null && !cancel.get()) {
					sourcesReady = true;
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
	private SearchIndex buildIndex(JadxDecompiler d, File idxDir, File nmDir, File mDir, long sig,
			java.util.concurrent.atomic.AtomicBoolean cancel) throws Exception {
		deleteDir(idxDir);
		deleteDir(nmDir);
		deleteDir(mDir);
		SearchIndex.Builder builder = new SearchIndex.Builder(idxDir, nmDir);
		List<JavaClass> all = d.getClasses();
		int total = all.size();
		if (total == 0) {
			return builder.finish(mDir, sig);
		}
		// Cap concurrency: each in-flight decompile holds a class's transient state, so on a
		// many-core box too many at once spikes peak memory (a cause of OOM-kills during export on
		// memory-limited servers). Tunable via -Djadxnvim.indexThreads.
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
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
			Thread th = new Thread(r, "jadx-index");
			th.setDaemon(true);
			th.setPriority(Thread.MIN_PRIORITY);
			return th;
		});
		java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
		java.util.concurrent.atomic.AtomicInteger lastPct = new java.util.concurrent.atomic.AtomicInteger(-1);
		for (JavaClass cls : all) {
			pool.submit(() -> {
				if (cancel.get()) {
					return;
				}
				try {
					// Raw parsed method list: same order memberPos uses, so the stored index maps
					// back correctly. Skipped (DONT_GENERATE) slots stay as null to keep positions.
					List<String> methodNames = new ArrayList<>();
					try {
						for (MethodNode mth : cls.getClassNode().getMethods()) {
							if (mth.contains(AFlag.DONT_GENERATE)) {
								methodNames.add(null);
							} else {
								methodNames.add(mth.getMethodInfo().getAlias());
							}
						}
					} catch (Throwable ignore) {
						// no methods indexed for this class
					}
					builder.add(cls.getRawName(), cls.getFullName(), cls.getCode(), methodNames);
				} catch (Throwable ignore) {
					// keep going; a failed class just isn't indexed
				} finally {
					try {
						cls.unload();
					} catch (Exception ignore) {
						// best effort
					}
					int c = done.incrementAndGet();
					int pct = (int) ((long) c * 100 / total);
					if (pct != lastPct.getAndSet(pct)) {
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("done", c);
						m.put("total", total);
						m.put("percent", pct);
						emitter.emit("loadProgress", m);
					}
				}
			});
		}
		pool.shutdown();
		pool.awaitTermination(6, java.util.concurrent.TimeUnit.HOURS);
		if (cancel.get()) {
			return null;
		}
		return builder.finish(mDir, sig);
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
		// With the usage graph disabled there is no getUseIn() data; tell the client to fall back to
		// a name-based text search over the exported sources.
		result.put("usageFallback", noUsage);
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
		if (params.has("kind") && !params.get("kind").isJsonNull()) {
			opts.kind = params.get("kind").getAsString();
		}
		// Text search uses the ripgrep shard index once it's ready; name search and the
		// not-yet-indexed case use the in-memory scan.
		// Pass the index for both text (shard scan) and name (class/method name files) searches once
		// the export is ready; Search decides how to use it and falls back to the in-memory scan.
		SearchIndex idx = sourcesReady ? searchIndex : null;
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
