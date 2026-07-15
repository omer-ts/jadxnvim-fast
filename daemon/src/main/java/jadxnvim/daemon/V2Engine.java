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
	// Rendered smali cache so toggling Java⟷Smali repeatedly is instant after the first render.
	private final Map<String, String> smaliCache = new LinkedHashMap<>(64, 0.75f, true) {
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
			case "getSmali":
				return getSmali(reqStr(params, "id"));
			case "memberPos":
				return memberPos(reqStr(params, "id"), reqInt(params, "index"));
			case "gotoDef":
				return gotoDef(reqStr(params, "id"), reqInt(params, "line"), reqInt(params, "col"));
			case "findUsages":
				return findUsages(reqStr(params, "id"), reqInt(params, "line"), reqInt(params, "col"));
			case "resolveTask":
				return resolveTask(reqStr(params, "id"), reqInt(params, "line"), reqInt(params, "col"),
						intList(params, "taskIds"));
			case "typeHierarchy":
				return typeHierarchy(params);
			case "callHierarchy":
				return callHierarchy(params);
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

	private Map<String, Object> getSmali(String id) throws Exception {
		String smali;
		synchronized (smaliCache) {
			smali = smaliCache.get(id);
		}
		if (smali == null) {
			Renderer.Result r = renderer.smali(descOf(id));
			smali = r.code;
			synchronized (smaliCache) {
				smaliCache.put(id, smali);
			}
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id);
		result.put("fullName", id);
		result.put("smali", smali);
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
		Renderer.Result r = renderer.decompile(desc);
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
	// Cap referencing classes we list at all.
	private static final int REF_CLASS_CAP = 5000;
	// Cap referencing classes we render for PRECISE per-site lines (each is a full-closure decompile).
	// Beyond this, classes are listed class-granular and relocate by name on open — keeps a hot
	// interface's gr complete but bounded (~seconds instead of minutes).
	private static final int RENDER_CAP = 30;

	private Map<String, Object> gotoDef(String id, int line, int col) throws Exception {
		Map<String, Object> result = new LinkedHashMap<>();
		String desc = descOf(id);
		Renderer.ResolvedSymbol sym = renderer.resolveAt(desc, line, col);
		if (sym == null) {
			result.put("found", false);
			return result;
		}
		String topFqn = topLevel(DexIndexer.descToFqn(sym.declClassDesc));
		String topDesc = descOf(topFqn);
		if (db.classIdOf(topDesc) < 0) {
			// Declaring class is not in this APK (a framework/library type) — nothing to open.
			result.put("found", false);
			return result;
		}
		Renderer.Pos pos = renderer.declarationPos(topDesc, sym);
		result.put("found", true);
		result.put("kind", kindName(sym.kind));
		result.put("name", sym.displayName);
		result.put("id", topFqn);
		result.put("line", pos.line);
		result.put("col", pos.col);

		// go-to-implementations: for a method, offer every class that declares/overrides it (the
		// declaration + concrete implementations across the type hierarchy), so gd on an abstract or
		// interface method lets you jump to any implementation rather than just the declaration.
		if (sym.kind == Db.KIND_METHOD) {
			List<Db.ImplHit> impls = db.implementations(sym.targetKey, 400);
			if (impls.size() > 1) {
				List<Map<String, Object>> targets = new ArrayList<>();
				java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
				for (Db.ImplHit im : impls) {
					String implTop = topLevel(im.fqn);
					if (!seen.add(implTop)) {
						continue;
					}
					Map<String, Object> t = new LinkedHashMap<>();
					t.put("id", implTop);
					t.put("fullName", im.fqn);
					t.put("name", sym.displayName);
					t.put("rawName", sym.rawName);
					t.put("abstract", (im.access & 0x0400) != 0); // ACC_ABSTRACT
					targets.add(t);
				}
				if (targets.size() > 1) {
					result.put("targets", targets);
				}
			}
		}
		return result;
	}

	private Map<String, Object> findUsages(String id, int line, int col) throws Exception {
		Map<String, Object> result = new LinkedHashMap<>();
		List<Map<String, Object>> usages = new ArrayList<>();
		result.put("usages", usages);
		result.put("usageFallback", false);
		String desc = descOf(id);
		Renderer.ResolvedSymbol sym = renderer.resolveAt(desc, line, col);
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

		// Declaring classes of the override group — the candidate targets a referencing class might call.
		java.util.Set<String> candidateTargets = new java.util.HashSet<>();
		for (String key : targetKeys) {
			int arrow = key.indexOf("->");
			if (arrow > 0) {
				candidateTargets.add(key.substring(0, arrow));
			}
		}

		// Render only the first RENDER_CAP referencing classes for precise per-call-site lines. Every
		// remaining referencing class is still listed (class-granular); opening it relocates to the
		// symbol by name. So find-usages stays complete AND bounded.
		List<String> ordered = new ArrayList<>(refClasses);
		int renderN = Math.min(RENDER_CAP, ordered.size());

		int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
				threads, r -> {
					Thread t = new Thread(r, "jadxd-usages");
					t.setDaemon(true);
					return t;
				});
		try {
			java.util.List<java.util.concurrent.Future<Object[]>> futures = new ArrayList<>();
			for (int i = 0; i < renderN; i++) {
				String top = ordered.get(i);
				futures.add(pool.submit(() -> {
					String d = descOf(top);
					java.util.List<Renderer.Usage> sites =
							renderer.findUsageSites(d, candidateTargets, targetKeys);
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
					// Rendered but no precise site — still list the class, navigable by symbol name.
					usages.add(classGranularHit(top, sym.rawName));
				} else {
					for (Renderer.Usage u : sites) {
						usages.add(preciseHit(top, u.line, u.col, u.text, sym.rawName, u.ordinal));
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
		// Class-granular entries for the referencing classes we didn't render (beyond RENDER_CAP):
		// listed once each, and opening one relocates to the symbol by name.
		for (int i = renderN; i < ordered.size() && usages.size() < MAX_USAGES; i++) {
			usages.add(classGranularHit(ordered.get(i), sym.rawName));
		}
		result.put("truncated", truncated);
		return result;
	}

	// A precise call site: exact line/col + the code line (relocated via `find`). If the light render's
	// line text doesn't match the opened buffer, `ordinal` (the k-th call to the member in this class,
	// source order) relocates to the k-th call; `member` is the last-resort name relocation.
	private static Map<String, Object> preciseHit(String id, int line, int col, String text, String member,
			int ordinal) {
		Map<String, Object> u = new LinkedHashMap<>();
		u.put("id", id);
		u.put("fullName", id);
		u.put("line", line);
		u.put("col", col);
		u.put("text", text);
		u.put("find", text); // relocate by the code line
		u.put("member", member); // fallback: relocate to the referenced member by name
		u.put("ordinal", ordinal); // fallback: relocate to the k-th call of the member
		return u;
	}

	// A class-granular usage (not rendered): opening it jumps to the symbol by name (`member`).
	private static Map<String, Object> classGranularHit(String id, String memberName) {
		Map<String, Object> u = new LinkedHashMap<>();
		u.put("id", id);
		u.put("fullName", id);
		u.put("line", 1);
		u.put("col", 0);
		u.put("text", id);
		u.put("member", memberName); // relocate to the referenced member by name
		return u;
	}

	private static String descOf(String fqn) {
		return "L" + fqn.replace('.', '/') + ";";
	}

	// --- merged-dispatcher task resolver -------------------------------------

	/**
	 * Resolve a merged-lambda / merged-callback dispatcher call to the branch it runs. Optimizers
	 * (R8 / Meta's Redex) merge many small lambdas/Runnables into one class dispatched by an integer id
	 * (rendered as {@code switch (this.$t)}) — so a call site {@code new X.Uez(.., 5)} really means
	 * "run case 5 of X.Uez". Given the class referenced at the cursor and the integer literals on that
	 * call, this finds {@code case <id>:} in the dispatch switch and returns its position.
	 */
	private Map<String, Object> resolveTask(String hostId, int line, int col, List<Integer> taskIds)
			throws Exception {
		Map<String, Object> result = new LinkedHashMap<>();
		Renderer.ResolvedSymbol sym = renderer.resolveAt(descOf(hostId), line, col);
		if (sym == null) {
			result.put("found", false);
			result.put("reason", "no class/constructor under the cursor");
			return result;
		}
		// The dispatcher is the class referenced at the cursor (a type, or the class declaring the
		// constructor/factory being called).
		String dispFqn = topLevel(DexIndexer.descToFqn(sym.declClassDesc));
		String dispDesc = descOf(dispFqn);
		if (db.classIdOf(dispDesc) < 0) {
			result.put("found", false);
			result.put("reason", dispFqn + " is not in this APK");
			return result;
		}
		String code = renderClass(dispFqn);
		int[] loc = findTaskCase(code, taskIds); // {line, taskId}
		if (loc == null) {
			result.put("found", false);
			result.put("reason", "no matching case in " + dispFqn
					+ (taskIds.isEmpty() ? " (no integer id on this line)" : " for id " + taskIds));
			return result;
		}
		result.put("found", true);
		result.put("id", dispFqn);
		result.put("line", loc[0]);
		result.put("col", 0);
		result.put("task", loc[1]);
		result.put("name", sym.displayName);
		return result;
	}

	// Locate `case <id>:` in the dispatch switch. Prefers `switch (this.$t)` (the merged-lambda id
	// field); else falls back to the switch with the most cases. Returns {line, chosenId} or null.
	private static int[] findTaskCase(String code, List<Integer> taskIds) {
		if (taskIds.isEmpty()) {
			return null;
		}
		String[] lines = code.split("\n", -1);
		int swLine = -1;
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].contains("switch (this.$t)")) {
				swLine = i;
				break;
			}
		}
		if (swLine < 0) {
			swLine = largestSwitchLine(lines); // fallback: dispatcher may name the id field differently
		}
		if (swLine < 0) {
			return null;
		}
		// Collect case label -> first line, for cases after the switch.
		Map<Integer, Integer> caseLine = new java.util.HashMap<>();
		java.util.regex.Pattern casePat = java.util.regex.Pattern.compile("^\\s*case\\s+(-?\\d+):");
		for (int i = swLine + 1; i < lines.length; i++) {
			java.util.regex.Matcher m = casePat.matcher(lines[i]);
			if (m.find()) {
				caseLine.putIfAbsent(Integer.parseInt(m.group(1)), i + 1);
			}
		}
		// Choose the last candidate id that is a real case ($t is commonly the last constructor arg).
		for (int k = taskIds.size() - 1; k >= 0; k--) {
			Integer id = taskIds.get(k);
			Integer ln = caseLine.get(id);
			if (ln != null) {
				return new int[] { ln, id };
			}
		}
		return null;
	}

	// Line index (0-based) of the `switch (` that has the most `case` labels after it — a heuristic for
	// the dispatch switch when the id field isn't literally $t.
	private static int largestSwitchLine(String[] lines) {
		java.util.regex.Pattern casePat = java.util.regex.Pattern.compile("^\\s*case\\s+-?\\d+:");
		int bestLine = -1;
		int bestCount = 0;
		for (int i = 0; i < lines.length; i++) {
			if (!lines[i].contains("switch (")) {
				continue;
			}
			int count = 0;
			for (int j = i + 1; j < lines.length && !lines[j].contains("switch ("); j++) {
				if (casePat.matcher(lines[j]).find()) {
					count++;
				}
			}
			if (count > bestCount) {
				bestCount = count;
				bestLine = i;
			}
		}
		return bestCount >= 3 ? bestLine : -1;
	}

	private static List<Integer> intList(JsonObject params, String key) {
		List<Integer> out = new ArrayList<>();
		if (params != null && params.has(key) && params.get(key).isJsonArray()) {
			for (com.google.gson.JsonElement e : params.getAsJsonArray(key)) {
				try {
					out.add(e.getAsInt());
				} catch (Exception ignore) {
					// skip non-ints
				}
			}
		}
		return out;
	}

	// --- type hierarchy ------------------------------------------------------

	// Recursion caps so a wide/deep (or cyclic via interfaces) hierarchy can't blow up the response.
	private static final int HIER_NODE_BUDGET = 2000;

	/**
	 * The super/subtype tree of a class, served entirely from the SQLite hierarchy (no rendering).
	 * {@code supers} walks up (superclass + interfaces, transitively), {@code subs} walks down
	 * (subclasses + implementors). The class under the cursor can be given as {@code id}; if
	 * {@code line}/{@code col} are supplied and resolve to a type, that type is used instead.
	 */
	private Map<String, Object> typeHierarchy(JsonObject params) throws Exception {
		String id = reqStr(params, "id");
		String desc = descOf(id);
		// If the cursor is on a type reference, use that type rather than the whole buffer's class.
		if (params.has("line") && params.has("col")) {
			try {
				Renderer.ResolvedSymbol sym = renderer.resolveAt(desc, optInt(params, "line", 1),
						optInt(params, "col", 0));
				if (sym != null && sym.kind == Db.KIND_CLASS && db.classIdOf(sym.declClassDesc) >= 0) {
					desc = sym.declClassDesc;
				}
			} catch (Exception ignore) {
				// fall back to the buffer's class
			}
		}
		Map<String, Object> result = new LinkedHashMap<>();
		if (db.classIdOf(desc) < 0) {
			result.put("found", false);
			return result;
		}
		String fqn = DexIndexer.descToFqn(desc);
		int access = classAccess(desc);
		int[] budget = { HIER_NODE_BUDGET };
		java.util.Set<String> upSeen = new java.util.HashSet<>();
		upSeen.add(desc);
		java.util.Set<String> downSeen = new java.util.HashSet<>();
		downSeen.add(desc);
		result.put("found", true);
		result.put("id", topLevel(fqn));
		result.put("fullName", fqn);
		result.put("name", simpleName(fqn));
		result.put("kind", (access & 0x200) != 0 ? "interface" : "class");
		result.put("supers", hierChildren(desc, true, upSeen, budget));
		result.put("subs", hierChildren(desc, false, downSeen, budget));
		return result;
	}

	private List<Map<String, Object>> hierChildren(String desc, boolean up, java.util.Set<String> visited,
			int[] budget) throws Exception {
		List<Map<String, Object>> out = new ArrayList<>();
		if (budget[0] <= 0) {
			return out;
		}
		List<Db.TypeRef> refs = up ? db.supersOf(desc) : db.subsOf(desc);
		for (Db.TypeRef r : refs) {
			if (budget[0] <= 0) {
				break;
			}
			budget[0]--;
			Map<String, Object> node = new LinkedHashMap<>();
			node.put("id", topLevel(r.fqn));
			node.put("fullName", r.fqn);
			node.put("name", simpleName(r.fqn));
			node.put("kind", r.isInterface() ? "interface" : "class");
			node.put("inApk", r.inApk);
			// Recurse only into in-APK types not already on this path (guards interface DAGs/cycles).
			if (r.inApk && visited.add(r.desc)) {
				List<Map<String, Object>> kids = hierChildren(r.desc, up, visited, budget);
				if (!kids.isEmpty()) {
					node.put(up ? "supers" : "subs", kids);
				}
			}
			out.add(node);
		}
		return out;
	}

	private int classAccess(String desc) throws Exception {
		try (PreparedStatement ps = db.connection().prepareStatement("SELECT access FROM classes WHERE desc=?")) {
			ps.setString(1, desc);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		}
	}

	// --- call hierarchy (incoming callers) -----------------------------------

	/**
	 * The incoming callers of a method — "who calls this", one entry per caller method, each itself
	 * expandable (pass its {@code key} back). Resolves the method either from the cursor
	 * ({@code id}/{@code line}/{@code col}) for the initial request, or directly from a {@code key} for
	 * an expansion. Callers are resolved over the method's whole override group (so virtual-dispatch
	 * callers aren't missed), rendering up to {@link #RENDER_CAP} referencing classes for precise
	 * caller methods and listing the rest class-granular — complete but bounded.
	 */
	private Map<String, Object> callHierarchy(JsonObject params) throws Exception {
		Map<String, Object> result = new LinkedHashMap<>();
		String key;
		String rootName;
		if (params.has("key") && !params.get("key").isJsonNull()) {
			key = reqStr(params, "key");
			rootName = optStr(params, "name", rawNameOfKey(key));
		} else {
			Renderer.ResolvedSymbol sym = renderer.resolveAt(descOf(reqStr(params, "id")),
					reqInt(params, "line"), reqInt(params, "col"));
			if (sym == null || sym.kind != Db.KIND_METHOD) {
				result.put("found", false);
				result.put("reason", "put the cursor on a method to see its callers");
				return result;
			}
			key = sym.targetKey;
			rootName = sym.displayName;
		}
		int arrow = key.indexOf("->");
		if (arrow < 0) {
			result.put("found", false);
			result.put("reason", "not a method");
			return result;
		}
		String rootClassFqn = topLevel(DexIndexer.descToFqn(key.substring(0, arrow)));
		String targetRawName = rawNameOfKey(key);

		Map<String, Object> root = new LinkedHashMap<>();
		root.put("key", key);
		root.put("name", rootName);
		root.put("id", rootClassFqn);
		root.put("fullName", DexIndexer.descToFqn(key.substring(0, arrow)) + "." + targetRawName);

		boolean[] truncated = { false };
		List<Map<String, Object>> callers = computeCallers(key, targetRawName, truncated);
		result.put("found", true);
		result.put("root", root);
		result.put("callers", callers);
		result.put("truncated", truncated[0]);
		return result;
	}

	private List<Map<String, Object>> computeCallers(String key, String targetRawName, boolean[] truncated)
			throws Exception {
		// Whole override group of the target, so callers via a super/interface/subtype are found.
		java.util.Set<String> targetKeys = new java.util.LinkedHashSet<>(db.overrideKeys(key, 300));
		java.util.Set<String> candidateTargets = new java.util.HashSet<>();
		for (String k : targetKeys) {
			int a = k.indexOf("->");
			if (a > 0) {
				candidateTargets.add(k.substring(0, a));
			}
		}
		List<Db.XrefHit> hits = db.xrefsToAny(new ArrayList<>(targetKeys), MAX_USAGES * 8);
		java.util.LinkedHashSet<String> refClasses = new java.util.LinkedHashSet<>();
		for (Db.XrefHit h : hits) {
			String top = topLevel(h.srcClassFqn);
			if (refClasses.size() >= REF_CLASS_CAP) {
				truncated[0] = true;
				break;
			}
			refClasses.add(top);
		}
		List<String> ordered = new ArrayList<>(refClasses);
		int renderN = Math.min(RENDER_CAP, ordered.size());

		List<Map<String, Object>> callers = new ArrayList<>();
		java.util.Set<String> seenKeys = new java.util.HashSet<>();
		int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
				threads, r -> {
					Thread t = new Thread(r, "jadxd-callers");
					t.setDaemon(true);
					return t;
				});
		try {
			List<java.util.concurrent.Future<Object[]>> futures = new ArrayList<>();
			for (int i = 0; i < renderN; i++) {
				String top = ordered.get(i);
				futures.add(pool.submit(() -> new Object[] { top,
						renderer.findCallerMethods(descOf(top), candidateTargets, targetKeys) }));
			}
			for (java.util.concurrent.Future<Object[]> f : futures) {
				Object[] r;
				try {
					r = f.get();
				} catch (Exception e) {
					continue;
				}
				String top = (String) r[0];
				@SuppressWarnings("unchecked")
				List<Renderer.Caller> found = (List<Renderer.Caller>) r[1];
				if (found.isEmpty()) {
					callers.add(classGranularCaller(top, targetRawName));
				} else {
					for (Renderer.Caller c : found) {
						if (c.callerKey != null && !seenKeys.add(c.callerKey)) {
							continue;
						}
						callers.add(callerHit(top, c, targetRawName));
					}
				}
			}
		} finally {
			pool.shutdownNow();
		}
		// Referencing classes beyond the render cap: listed but not method-resolved (open to inspect).
		for (int i = renderN; i < ordered.size(); i++) {
			callers.add(classGranularCaller(ordered.get(i), targetRawName));
		}
		return callers;
	}

	// A resolved caller: opens the caller class at the call site (relocated by code line / k-th call),
	// and carries `key` so it can itself be expanded to ITS callers.
	private static Map<String, Object> callerHit(String id, Renderer.Caller c, String targetRawName) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("key", c.callerKey);          // null when the call is outside any method (not expandable)
		m.put("name", c.callerName);
		m.put("fullName", c.callerClassFqn + "." + c.callerName);
		m.put("line", c.line);
		m.put("col", c.col);
		m.put("text", c.text);
		m.put("find", c.text);              // relocate to the call site by code line
		m.put("member", targetRawName);     // fallback: the called member's name
		m.put("ordinal", c.ordinal);        // fallback: the k-th call of the member
		m.put("expandable", c.callerKey != null);
		return m;
	}

	// A referencing class we didn't render (beyond the cap) or with no method-level site: open it and
	// relocate to the called member by name; not expandable (no caller method resolved).
	private static Map<String, Object> classGranularCaller(String id, String targetRawName) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("name", id);
		m.put("fullName", id);
		m.put("line", 1);
		m.put("col", 0);
		m.put("text", id);
		m.put("member", targetRawName);
		m.put("expandable", false);
		return m;
	}

	// The runtime (raw) method name encoded in a dexlib2 method key: between "->" and "(".
	private static String rawNameOfKey(String key) {
		int arrow = key.indexOf("->");
		if (arrow < 0) {
			return key;
		}
		int paren = key.indexOf('(', arrow);
		return paren > arrow ? key.substring(arrow + 2, paren) : key.substring(arrow + 2);
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

	// Simple (last-segment) name of a dotted fqn, keeping any Outer$Inner tail.
	private static String simpleName(String fqn) {
		int d = fqn.lastIndexOf('.');
		return d >= 0 ? fqn.substring(d + 1) : fqn;
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
