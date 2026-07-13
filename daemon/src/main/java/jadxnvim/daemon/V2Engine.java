package jadxnvim.daemon;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonObject;

/**
 * v2 fast-path RPC handler: serves browse / code / name+content search directly from the SQLite
 * index ({@link Db}) and the on-demand {@link Renderer}, without ever building jadx's whole-APK
 * model. Methods it does not handle return {@link #NOT_HANDLED} so {@link Session} falls through to
 * the legacy jadx path (which lazily builds the model), keeping advanced ops (gd/gr/rename) working.
 */
final class V2Engine {

	/** Sentinel: this method isn't served by v2; the caller should fall through to legacy. */
	static final Object NOT_HANDLED = new Object();

	private final Db db;
	private final Renderer renderer;
	private final Session.Emitter emitter;

	// Rendered-code cache so re-opening a class (and memberPos) is instant after the first view.
	private static final int CODE_CACHE_MAX = 64;
	private final Map<String, String> codeCache = new LinkedHashMap<>(128, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, String> e) {
			return size() > CODE_CACHE_MAX;
		}
	};

	private final ExecutorService searchExec = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "jadxd-v2-search");
		t.setDaemon(true);
		return t;
	});
	private final Map<Integer, AtomicBoolean> running = new ConcurrentHashMap<>();
	private int nextSearchId = 0;

	V2Engine(Db db, File apk, File workDir, Session.Emitter emitter) {
		this.db = db;
		this.renderer = new Renderer(apk, workDir);
		this.emitter = emitter;
	}

	Object handle(String method, JsonObject params) throws Exception {
		switch (method) {
			case "getPackages":
				return getPackages();
			case "getClasses":
				return getClasses(optStr(params, "package", ""));
			case "listClasses":
				return listClasses(optInt(params, "limit", 100000));
			case "listMethods":
				return listMethods(optInt(params, "limit", 100000));
			case "getCode":
				return getCode(reqStr(params, "id"));
			case "memberPos":
				return memberPos(reqStr(params, "id"), reqInt(params, "index"));
			case "gotoDef":
				return gotoDef(reqStr(params, "id"), reqInt(params, "line"), reqInt(params, "col"));
			case "findUsages":
				return findUsages(reqStr(params, "id"), reqInt(params, "line"), reqInt(params, "col"));
			case "searchName":
				return startSearch("name", params);
			case "searchText":
				return startSearch("text", params);
			case "cancelSearch":
				cancel(reqInt(params, "searchId"));
				return Map.of("ok", true);
			default:
				return NOT_HANDLED;
		}
	}

	// --- tree ---------------------------------------------------------------

	private Map<String, Object> getPackages() throws Exception {
		List<Map<String, Object>> packages = new ArrayList<>();
		// Top-level classes only (dex inner classes carry '$' and are rendered within their outer).
		String sql = "SELECT pkg, COUNT(*) FROM classes WHERE name NOT LIKE '%$%' GROUP BY pkg ORDER BY pkg";
		try (PreparedStatement ps = db.connection().prepareStatement(sql);
				ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				Map<String, Object> pkg = new LinkedHashMap<>();
				pkg.put("name", rs.getString(1));
				pkg.put("count", rs.getInt(2));
				packages.add(pkg);
			}
		}
		return Map.of("packages", packages);
	}

	private Map<String, Object> getClasses(String pkg) throws Exception {
		List<Map<String, Object>> classes = new ArrayList<>();
		String sql = "SELECT fqn, name FROM classes WHERE pkg=? AND name NOT LIKE '%$%' ORDER BY name";
		try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
			ps.setString(1, pkg);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String fqn = rs.getString(1);
					String name = rs.getString(2);
					Map<String, Object> e = new LinkedHashMap<>();
					e.put("id", fqn); // raw name — the id getCode/gd/gr key on
					e.put("name", name);
					e.put("fullName", fqn);
					// jadx-rendered name shown alongside the raw name when jadx renames the class.
					String jadx = Names.jadxSimpleName(name);
					if (!jadx.equals(name)) {
						e.put("alias", jadx);
					}
					classes.add(e);
				}
			}
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("package", pkg);
		result.put("classes", classes);
		return result;
	}

	private Map<String, Object> listClasses(int limit) throws Exception {
		List<Map<String, Object>> items = new ArrayList<>();
		String sql = "SELECT fqn, name FROM classes WHERE name NOT LIKE '%$%' ORDER BY fqn LIMIT ?";
		try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
			ps.setInt(1, limit + 1);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String fqn = rs.getString(1);
					String name = rs.getString(2);
					String jadx = Names.jadxSimpleName(name);
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", fqn);
					// Label shows the raw fqn plus the jadx name when the class is renamed.
					m.put("label", jadx.equals(name) ? fqn : fqn + "  (jadx: " + jadx + ")");
					items.add(m);
				}
			}
		}
		boolean truncated = items.size() > limit;
		if (truncated) {
			items = items.subList(0, limit);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("items", items);
		result.put("truncated", truncated);
		return result;
	}

	private Map<String, Object> listMethods(int limit) throws Exception {
		List<Map<String, Object>> items = new ArrayList<>();
		// id = top-level class fqn (for the buffer); index = the unique method row id, which memberPos
		// resolves back to (owning class, name) to locate the declaration line in rendered code.
		String sql = "SELECT m.id, m.name, c.fqn FROM methods m JOIN classes c ON c.id=m.class_id "
				+ "ORDER BY c.fqn, m.idx LIMIT ?";
		try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
			ps.setInt(1, limit + 1);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					long mid = rs.getLong(1);
					String name = rs.getString(2);
					String fqn = rs.getString(3);
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", topLevel(fqn));
					m.put("index", (int) mid);
					m.put("label", name + "  ·  " + fqn);
					items.add(m);
				}
			}
		}
		boolean truncated = items.size() > limit;
		if (truncated) {
			items = items.subList(0, limit);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("items", items);
		result.put("truncated", truncated);
		return result;
	}

	// --- code ---------------------------------------------------------------

	private Map<String, Object> getCode(String id) throws Exception {
		String code = renderClass(id);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id);
		result.put("fullName", id);
		result.put("code", code);
		return result;
	}

	/** Render (and cache) a class by fqn id; the id is a top-level class. */
	private String renderClass(String id) throws Exception {
		synchronized (codeCache) {
			String c = codeCache.get(id);
			if (c != null) {
				return c;
			}
		}
		String desc = "L" + id.replace('.', '/') + ";";
		String hint = db.dexEntryOf(desc);
		Renderer.Result r = renderer.decompile(desc, hint);
		synchronized (codeCache) {
			codeCache.put(id, r.code);
		}
		// Lazily fill the Java-source FTS so future content searches cover this class's method bodies.
		try {
			long classId = db.classIdOf(desc);
			if (classId >= 0) {
				db.indexSource(classId, r.code);
			}
		} catch (Exception e) {
			System.err.println("[jadxd] source-fts index failed for " + id + ": " + e);
		}
		return r.code;
	}

	/**
	 * Resolve a method's declaration line by rendering its top-level class and locating the
	 * declaration by name. {@code index} is the method's row id (from {@link #listMethods}).
	 */
	private Map<String, Object> memberPos(String id, int index) throws Exception {
		String methodName = null;
		String ownerFqn = null;
		try (PreparedStatement ps = db.connection().prepareStatement(
				"SELECT m.name, c.fqn FROM methods m JOIN classes c ON c.id=m.class_id WHERE m.id=?")) {
			ps.setInt(1, index);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					methodName = rs.getString(1);
					ownerFqn = rs.getString(2);
				}
			}
		}
		int line = 1;
		if (methodName != null) {
			String code = renderClass(topLevel(ownerFqn));
			line = declarationLine(code, methodName);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id);
		result.put("fullName", id);
		result.put("line", line);
		result.put("col", 0);
		return result;
	}

	// First line that looks like a declaration of {@code name} (a "name(" not preceded by '.').
	private static int declarationLine(String code, String name) {
		String[] lines = code.split("\n", -1);
		String needle = name + "(";
		for (int i = 0; i < lines.length; i++) {
			String ln = lines[i];
			int idx = ln.indexOf(needle);
			while (idx >= 0) {
				boolean afterDot = idx > 0 && ln.charAt(idx - 1) == '.';
				boolean identCharBefore = idx > 0 && Character.isJavaIdentifierPart(ln.charAt(idx - 1));
				if (!afterDot && !identCharBefore) {
					return i + 1;
				}
				idx = ln.indexOf(needle, idx + 1);
			}
		}
		return 1;
	}

	// --- go-to-def / find-usages (SQLite xref index, no jadx model) ----------

	private static final int MAX_USAGES = 5000;
	// Cap referencing classes we render for precise sites (bounds gr latency on hot symbols).
	private static final int REF_CLASS_CAP = 400;

	private Map<String, Object> gotoDef(String id, int line, int col) throws Exception {
		Map<String, Object> result = new LinkedHashMap<>();
		String desc = descOf(id);
		Renderer.ResolvedSymbol sym = renderer.resolveAt(desc, db.dexEntryOf(desc), line, col);
		if (sym == null) {
			result.put("found", false);
			return result;
		}
		String topFqn = topLevel(DexIndexer.descToFqn(sym.declClassDesc));
		String topDesc = descOf(topFqn);
		String topHint = db.dexEntryOf(topDesc);
		if (topHint == null) {
			// Declaring class is not in this APK (a framework/library type) — nothing to open.
			result.put("found", false);
			return result;
		}
		Renderer.Pos pos = renderer.declarationPos(topDesc, topHint, sym);
		result.put("found", true);
		result.put("kind", kindName(sym.kind));
		result.put("name", sym.displayName);
		result.put("id", topFqn);
		result.put("line", pos.line);
		result.put("col", pos.col);
		return result;
	}

	private Map<String, Object> findUsages(String id, int line, int col) throws Exception {
		Map<String, Object> result = new LinkedHashMap<>();
		List<Map<String, Object>> usages = new ArrayList<>();
		result.put("usages", usages);
		result.put("usageFallback", false);
		String desc = descOf(id);
		Renderer.ResolvedSymbol sym = renderer.resolveAt(desc, db.dexEntryOf(desc), line, col);
		if (sym == null) {
			result.put("name", "symbol");
			result.put("truncated", false);
			return result;
		}
		result.put("name", sym.displayName);
		// Fully-qualified path of the resolved symbol (declaring class + member), shown when no usages
		// are found so the user knows exactly what was searched.
		String declFqn = DexIndexer.descToFqn(sym.declClassDesc);
		result.put("path", sym.kind == Db.KIND_CLASS ? declFqn : declFqn + "." + sym.rawName);
		String targetTopFqn = topLevel(declFqn);
		// Frida-hook target (the searched symbol's own class).
		result.put("targetId", targetTopFqn);
		result.put("targetKind", kindName(sym.kind));
		result.put("targetRawName", sym.rawName);

		// Expand a method target to its whole override group (calls via a super/interface/subtype),
		// so virtual-dispatch usages aren't missed; a class/field is just its single key.
		java.util.Set<String> targetKeys = new java.util.LinkedHashSet<>();
		if (sym.kind == Db.KIND_METHOD) {
			targetKeys.addAll(db.overrideKeys(sym.targetKey, 300));
		} else {
			targetKeys.add(sym.targetKey);
		}

		// Distinct referencing (top-level) classes from the xref index, capped.
		List<Db.XrefHit> hits = db.xrefsToAny(new ArrayList<>(targetKeys), MAX_USAGES * 8);
		java.util.LinkedHashSet<String> refClasses = new java.util.LinkedHashSet<>();
		boolean truncated = false;
		for (Db.XrefHit h : hits) {
			String top = topLevel(h.srcClassFqn);
			if (refClasses.contains(top)) {
				continue;
			}
			if (refClasses.size() >= REF_CLASS_CAP) {
				truncated = true;
				break;
			}
			refClasses.add(top);
		}

		// Resolve precise call-site lines by rendering each referencing class in parallel and reading
		// jadx metadata. This is what makes gr navigable — each entry jumps to the actual call, with the
		// code line as its text — without decompiling the whole APK.
		int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
				threads, r -> {
					Thread t = new Thread(r, "jadxd-usages");
					t.setDaemon(true);
					return t;
				});
		try {
			java.util.List<java.util.concurrent.Future<Object[]>> futures = new ArrayList<>();
			for (String top : refClasses) {
				futures.add(pool.submit(() -> {
					String d = descOf(top);
					java.util.List<Renderer.Usage> sites =
							renderer.findUsageSites(d, db.dexEntryOf(d), targetKeys);
					return new Object[] { top, sites };
				}));
			}
			for (java.util.concurrent.Future<Object[]> f : futures) {
				Object[] r;
				try {
					r = f.get();
				} catch (Exception e) {
					continue; // a class that failed to render just contributes no precise sites
				}
				String top = (String) r[0];
				@SuppressWarnings("unchecked")
				java.util.List<Renderer.Usage> sites = (java.util.List<Renderer.Usage>) r[1];
				if (sites.isEmpty()) {
					// Rendered but no precise site (e.g. inlined/synthetic ref) — still list the class.
					usages.add(usageHit(top, top, 1, 0, top));
				} else {
					for (Renderer.Usage u : sites) {
						usages.add(usageHit(top, top, u.line, u.col, u.text));
						if (usages.size() >= MAX_USAGES) {
							truncated = true;
							break;
						}
					}
				}
				if (usages.size() >= MAX_USAGES) {
					truncated = true;
					break;
				}
			}
		} finally {
			pool.shutdownNow();
		}
		result.put("truncated", truncated);
		return result;
	}

	private static Map<String, Object> usageHit(String id, String fullName, int line, int col, String text) {
		Map<String, Object> u = new LinkedHashMap<>();
		u.put("id", id);
		u.put("fullName", fullName);
		u.put("line", line);
		u.put("col", col);
		u.put("text", text);
		return u;
	}

	private static String descOf(String fqn) {
		return "L" + fqn.replace('.', '/') + ";";
	}

	// --- search (streamed) --------------------------------------------------

	private Map<String, Object> startSearch(String kind, JsonObject params) {
		int searchId = ++nextSearchId;
		AtomicBoolean cancel = new AtomicBoolean(false);
		running.put(searchId, cancel);
		String query = reqStr(params, "query");
		int limit = optInt(params, "limit", 2000);
		String nameKind = optStr(params, "kind", "all"); // class | method | field | all
		searchExec.submit(() -> {
			int count = 0;
			boolean truncated = false;
			try {
				if ("name".equals(kind)) {
					int[] r = runNameSearch(searchId, query, nameKind, limit, cancel);
					count = r[0];
					truncated = r[1] == 1;
				} else {
					int[] r = runTextSearch(searchId, query, limit, cancel);
					count = r[0];
					truncated = r[1] == 1;
				}
			} catch (Throwable t) {
				System.err.println("[jadxd] v2 search error: " + t);
			} finally {
				running.remove(searchId);
				Map<String, Object> done = new LinkedHashMap<>();
				done.put("searchId", searchId);
				done.put("count", count);
				done.put("truncated", truncated);
				done.put("cancelled", cancel.get());
				emitter.emit("searchDone", done);
			}
		});
		return Map.of("searchId", searchId);
	}

	private void cancel(int searchId) {
		AtomicBoolean c = running.get(searchId);
		if (c != null) {
			c.set(true);
		}
	}

	boolean isSearchActive(int searchId) {
		return running.containsKey(searchId);
	}

	private int[] runNameSearch(int searchId, String query, String nameKind, int limit, AtomicBoolean cancel)
			throws Exception {
		int wantKind = "class".equals(nameKind) ? Db.KIND_CLASS
				: "method".equals(nameKind) ? Db.KIND_METHOD
				: "field".equals(nameKind) ? Db.KIND_FIELD : -1;
		List<Db.SymbolHit> hits = db.searchSymbols(query, wantKind, limit);
		List<Map<String, Object>> batch = new ArrayList<>();
		int count = 0;
		for (Db.SymbolHit h : hits) {
			if (cancel.get()) {
				break;
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", topLevel(fqnClassOf(h)));
			m.put("kind", kindName(h.kind));
			// Show the jadx-rendered name alongside the raw name when the class was renamed.
			String label = (h.alias != null && !h.alias.equals(h.fqn)) ? h.fqn + "  (jadx: " + h.alias + ")" : h.fqn;
			m.put("fullName", label);
			m.put("line", 1);
			m.put("col", 0);
			m.put("text", label);
			batch.add(m);
			count++;
			if (batch.size() >= 500) {
				flush(searchId, batch);
			}
			if (count >= limit) {
				break;
			}
		}
		flush(searchId, batch);
		return new int[] { count, count >= limit ? 1 : 0 };
	}

	// The class fqn that owns a symbol hit (for classes, the fqn itself; for members, strip the trailer).
	private String fqnClassOf(Db.SymbolHit h) throws Exception {
		if (h.kind == Db.KIND_CLASS) {
			return h.fqn;
		}
		try (PreparedStatement ps = db.connection().prepareStatement("SELECT fqn FROM classes WHERE id=?")) {
			ps.setLong(1, h.classId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : h.fqn;
			}
		}
	}

	private int[] runTextSearch(int searchId, String query, int limit, AtomicBoolean cancel) throws Exception {
		// Content search, class-granular, no decompilation on the hot path:
		//  (a) decompiled Java bodies of classes already viewed (source_fts, filled lazily on getCode),
		//  (b) string constants of every class (str_use), which need no decompilation.
		// Dedup by top-level class; source-body matches (more specific) take precedence.
		List<Map<String, Object>> batch = new ArrayList<>();
		java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
		int count = 0;

		for (Db.StringHit h : db.searchSource(query, limit)) {
			if (cancel.get()) {
				break;
			}
			String top = topLevel(h.classFqn);
			if (!seen.add(top)) {
				continue;
			}
			batch.add(textHit(top, h.classFqn, "source", "matched in decompiled body"));
			count++;
			if (batch.size() >= 500) {
				flush(searchId, batch);
			}
			if (count >= limit) {
				flush(searchId, batch);
				return new int[] { count, 1 };
			}
		}

		for (Db.StringHit h : db.searchStrings(query, limit)) {
			if (cancel.get()) {
				break;
			}
			String top = topLevel(h.classFqn);
			if (!seen.add(top)) {
				continue;
			}
			batch.add(textHit(top, h.classFqn, "string", '"' + h.value + '"'));
			count++;
			if (batch.size() >= 500) {
				flush(searchId, batch);
			}
			if (count >= limit) {
				count = limit;
				flush(searchId, batch);
				return new int[] { count, 1 };
			}
		}
		flush(searchId, batch);
		return new int[] { count, 0 };
	}

	private static Map<String, Object> textHit(String id, String fqn, String kind, String text) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("kind", kind);
		m.put("fullName", fqn);
		m.put("line", 1);
		m.put("col", 0);
		m.put("text", text);
		return m;
	}

	private void flush(int searchId, List<Map<String, Object>> hits) {
		if (hits.isEmpty()) {
			return;
		}
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("searchId", searchId);
		params.put("items", new ArrayList<>(hits));
		emitter.emit("searchHits", params);
		hits.clear();
	}

	// --- helpers ------------------------------------------------------------

	private static String topLevel(String fqn) {
		int d = fqn.indexOf('$');
		return d >= 0 ? fqn.substring(0, d) : fqn;
	}

	private static String kindName(int kind) {
		switch (kind) {
			case Db.KIND_CLASS: return "class";
			case Db.KIND_METHOD: return "method";
			case Db.KIND_FIELD: return "field";
			default: return "symbol";
		}
	}

	long classCount() throws Exception {
		return db.count("classes");
	}

	void close() {
		searchExec.shutdownNow();
		try {
			db.close();
		} catch (Exception ignore) {
			// best effort
		}
	}

	// --- param helpers ------------------------------------------------------

	private static String reqStr(JsonObject p, String key) {
		if (p == null || !p.has(key) || p.get(key).isJsonNull()) {
			throw new IllegalArgumentException("missing param: " + key);
		}
		return p.get(key).getAsString();
	}

	private static int reqInt(JsonObject p, String key) {
		if (p == null || !p.has(key) || p.get(key).isJsonNull()) {
			throw new IllegalArgumentException("missing param: " + key);
		}
		return p.get(key).getAsInt();
	}

	private static String optStr(JsonObject p, String key, String def) {
		return (p != null && p.has(key) && !p.get(key).isJsonNull()) ? p.get(key).getAsString() : def;
	}

	private static int optInt(JsonObject p, String key, int def) {
		return (p != null && p.has(key) && !p.get(key).isJsonNull()) ? p.get(key).getAsInt() : def;
	}
}
