package jadxnvim.daemon;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed index store for the v2 engine.
 *
 * <p>Everything the user does to <em>navigate</em> a big APK — browse the class tree, symbol/string/
 * full-text search, go-to-definition, find-references — is served from this database, which is built
 * directly from the dex tables by {@link DexIndexer} without decompiling anything. jadx is only ever
 * invoked to render the single class a user opens (see the render engine).
 *
 * <p>The store is opened with WAL + a large {@code mmap_size} so reads are memory-mapped and cheap
 * regardless of APK size. It is disposable: if the schema/format version or the input signature does
 * not match, the caller rebuilds it.
 */
public final class Db implements AutoCloseable {

	/** Bump when the schema or the semantics of any indexed column change. */
	public static final int SCHEMA_VERSION = 5;

	// Symbol kinds (stored in symbols.kind and xrefs.target_kind).
	public static final int KIND_CLASS = 0;
	public static final int KIND_METHOD = 1;
	public static final int KIND_FIELD = 2;
	public static final int KIND_STRING = 3;

	// Xref edge kinds.
	public static final int REF_TYPE = 0;   // a referenced type (new, cast, field type, ...)
	public static final int REF_METHOD = 1; // an invoked method
	public static final int REF_FIELD = 2;  // a read/written field

	private final Connection conn;

	private Db(Connection conn) {
		this.conn = conn;
	}

	public Connection connection() {
		return conn;
	}

	/** Open (creating if absent) the database at {@code path}, applying performance pragmas. */
	public static Db open(String path) throws SQLException {
		Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
		try (Statement st = c.createStatement()) {
			st.execute("PRAGMA journal_mode=WAL");
			st.execute("PRAGMA synchronous=NORMAL");
			st.execute("PRAGMA temp_store=MEMORY");
			st.execute("PRAGMA mmap_size=1073741824"); // 1 GiB memory-mapped reads
			st.execute("PRAGMA cache_size=-65536"); // 64 MiB page cache
			st.execute("PRAGMA foreign_keys=OFF");
		}
		return new Db(c);
	}

	/** Create the schema (idempotent). Called by the indexer before a fresh build. */
	public void createSchema() throws SQLException {
		try (Statement st = conn.createStatement()) {
			st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)");
			st.execute("CREATE TABLE IF NOT EXISTS classes ("
					+ "id INTEGER PRIMARY KEY,"
					+ "desc TEXT NOT NULL,"          // raw dex type descriptor, e.g. Lcom/example/Foo;
					+ "fqn TEXT NOT NULL,"           // dotted name, e.g. com.example.Foo
					+ "pkg TEXT NOT NULL,"           // com.example
					+ "name TEXT NOT NULL,"          // simple name, e.g. Foo (or Outer$Inner)
					+ "access INTEGER NOT NULL,"
					+ "super_desc TEXT,"
					+ "source_file TEXT,"
					+ "dex TEXT)");                  // owning dex entry name
			st.execute("CREATE TABLE IF NOT EXISTS methods ("
					+ "id INTEGER PRIMARY KEY,"
					+ "class_id INTEGER NOT NULL,"
					+ "name TEXT NOT NULL,"
					+ "proto TEXT NOT NULL,"         // shorty-ish signature for display/dedup
					+ "access INTEGER NOT NULL,"
					+ "idx INTEGER NOT NULL)");      // position within the class (parsed order)
			st.execute("CREATE TABLE IF NOT EXISTS fields ("
					+ "id INTEGER PRIMARY KEY,"
					+ "class_id INTEGER NOT NULL,"
					+ "name TEXT NOT NULL,"
					+ "type TEXT NOT NULL,"
					+ "access INTEGER NOT NULL,"
					+ "idx INTEGER NOT NULL)");
			// Implemented interfaces per class → lets find-usages expand a method to its whole override
			// group (calls made through a super/interface/subtype), so virtual-dispatch calls aren't missed.
			st.execute("CREATE TABLE IF NOT EXISTS class_iface ("
					+ "class_id INTEGER NOT NULL,"
					+ "iface_desc TEXT NOT NULL)");
			// Cross-references, deduped per (src_class, target): one row per referencing class, not per
			// call site. find-usages is class-granular in fast mode, so this keeps the table small
			// (thousands of duplicate StringBuilder.append edges collapse to one) without losing results.
			st.execute("CREATE TABLE IF NOT EXISTS xrefs ("
					+ "target TEXT NOT NULL,"        // target key (type descriptor / method / field key)
					+ "kind INTEGER NOT NULL,"       // REF_TYPE / REF_METHOD / REF_FIELD
					+ "src_class_id INTEGER NOT NULL)");
			// Which classes use a given string literal (const-string), deduped per class. Powers
			// instant "content" search over string constants with class locations, no decompilation.
			st.execute("CREATE TABLE IF NOT EXISTS str_use ("
					+ "id INTEGER PRIMARY KEY,"
					+ "class_id INTEGER NOT NULL,"
					+ "value TEXT NOT NULL)");
			// Unified searchable symbol table (classes + methods + fields).
			st.execute("CREATE TABLE IF NOT EXISTS symbols ("
					+ "id INTEGER PRIMARY KEY,"
					+ "kind INTEGER NOT NULL,"
					+ "name TEXT NOT NULL,"          // simple name shown in results
					+ "fqn TEXT NOT NULL,"           // fully-qualified raw name (original, pre-jadx)
					+ "alias TEXT,"                  // jadx-rendered fqn when jadx renames the class; else null
					+ "class_id INTEGER NOT NULL,"
					+ "member_idx INTEGER)");        // method/field index within class; null for classes
			// Trigram FTS over a combined searchable text per symbol (raw simple + raw fqn + jadx-alias
			// forms), so a search matches the original name, a qualified/partial path, OR the jadx name.
			st.execute("CREATE VIRTUAL TABLE IF NOT EXISTS sym_fts USING fts5("
					+ "text, tokenize='trigram', content='')");
			// Trigram FTS over string-literal uses → substring content search (rowid = str_use.id).
			st.execute("CREATE VIRTUAL TABLE IF NOT EXISTS str_use_fts USING fts5("
					+ "value, tokenize='trigram', content='')");
			// Java-source FTS, filled lazily as classes are decompiled by the render engine (rowid =
			// classes.id). A standard (contentful) FTS5 table so a class can be re-indexed (DELETE+INSERT)
			// without duplicates; trigram so substring/regex-ish queries work.
			st.execute("CREATE VIRTUAL TABLE IF NOT EXISTS source_fts USING fts5("
					+ "source, tokenize='trigram')");
		}
	}

	/** Create secondary indexes. Called once after the bulk insert for faster build. */
	public void createIndexes() throws SQLException {
		try (Statement st = conn.createStatement()) {
			st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_classes_desc ON classes(desc)");
			st.execute("CREATE INDEX IF NOT EXISTS idx_classes_pkg ON classes(pkg)");
			st.execute("CREATE INDEX IF NOT EXISTS idx_methods_class ON methods(class_id)");
			st.execute("CREATE INDEX IF NOT EXISTS idx_methods_name ON methods(name)");
			st.execute("CREATE INDEX IF NOT EXISTS idx_fields_class ON fields(class_id)");
			st.execute("CREATE INDEX IF NOT EXISTS idx_xrefs_target ON xrefs(target)");
			st.execute("CREATE INDEX IF NOT EXISTS idx_classes_super ON classes(super_desc)");
			st.execute("CREATE INDEX IF NOT EXISTS idx_iface_desc ON class_iface(iface_desc)");
			st.execute("CREATE INDEX IF NOT EXISTS idx_iface_class ON class_iface(class_id)");
		}
	}

	public void setMeta(String key, String value) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement(
				"INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
			ps.setString(1, key);
			ps.setString(2, value);
			ps.executeUpdate();
		}
	}

	public String getMeta(String key) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM meta WHERE key=?")) {
			ps.setString(1, key);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	/**
	 * Whether an existing DB is valid for {@code signature} at the current schema version. A mismatch
	 * (or missing DB) means the caller should rebuild.
	 */
	public boolean isValid(String signature) {
		try {
			String v = getMeta("schema_version");
			String sig = getMeta("input_signature");
			String complete = getMeta("complete");
			return v != null && Integer.parseInt(v) == SCHEMA_VERSION
					&& signature.equals(sig) && "1".equals(complete);
		} catch (Exception e) {
			return false;
		}
	}

	public void begin() throws SQLException {
		conn.setAutoCommit(false);
	}

	public void commit() throws SQLException {
		conn.commit();
		conn.setAutoCommit(true);
	}

	public long count(String table) throws SQLException {
		try (Statement st = conn.createStatement();
				ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
			return rs.next() ? rs.getLong(1) : 0;
		}
	}

	/** Analyze for the query planner after a build. */
	public void analyze() throws SQLException {
		try (Statement st = conn.createStatement()) {
			st.execute("ANALYZE");
		}
	}

	// --- read queries (used by CLI + later by Session/Search) ----------------

	public static final class SymbolHit {
		public final int kind;
		public final String name;
		public final String fqn;
		public final String alias; // jadx-rendered fqn when renamed, else null
		public final long classId;
		public final Integer memberIdx;

		SymbolHit(int kind, String name, String fqn, String alias, long classId, Integer memberIdx) {
			this.kind = kind;
			this.name = name;
			this.fqn = fqn;
			this.alias = alias;
			this.classId = classId;
			this.memberIdx = memberIdx;
		}
	}

	/**
	 * Substring/fuzzy symbol search via the trigram FTS index. Matches the combined per-symbol text
	 * (raw simple name, raw fully-qualified name, and jadx-alias forms), so a query can be the original
	 * name, a qualified/partial path, or the jadx-rendered name. {@code kind} restricts results to a
	 * single symbol kind ({@link #KIND_CLASS}/{@link #KIND_METHOD}/{@link #KIND_FIELD}); pass -1 for
	 * all kinds. The kind filter is applied inside SQL so it composes with {@code limit} correctly.
	 */
	public synchronized List<SymbolHit> searchSymbols(String query, int kind, int limit) throws SQLException {
		List<SymbolHit> out = new ArrayList<>();
		// Trigram FTS needs >= 3 chars; fall back to a LIKE scan (name/fqn/alias) for short queries.
		boolean useFts = query != null && query.length() >= 3;
		String kindClause = kind >= 0 ? " AND s.kind=?" : "";
		String sql = useFts
				? "SELECT s.kind,s.name,s.fqn,s.alias,s.class_id,s.member_idx FROM sym_fts f "
						+ "JOIN symbols s ON s.id=f.rowid WHERE f.text MATCH ?" + kindClause + " LIMIT ?"
				: "SELECT s.kind,s.name,s.fqn,s.alias,s.class_id,s.member_idx FROM symbols s "
						+ "WHERE (s.name LIKE ? OR s.fqn LIKE ? OR s.alias LIKE ?)" + kindClause + " LIMIT ?";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			int i = 1;
			if (useFts) {
				ps.setString(i++, "\"" + query.replace("\"", "\"\"") + "\"");
			} else {
				String like = "%" + query + "%";
				ps.setString(i++, like);
				ps.setString(i++, like);
				ps.setString(i++, like);
			}
			if (kind >= 0) {
				ps.setInt(i++, kind);
			}
			ps.setInt(i, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					int mi = rs.getInt(6);
					Integer memberIdx = rs.wasNull() ? null : mi;
					out.add(new SymbolHit(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
							rs.getLong(5), memberIdx));
				}
			}
		}
		return out;
	}

	public static final class XrefHit {
		public final String srcClassDesc;
		public final String srcClassFqn;
		public final int kind;

		XrefHit(String srcClassDesc, String srcClassFqn, int kind) {
			this.srcClassDesc = srcClassDesc;
			this.srcClassFqn = srcClassFqn;
			this.kind = kind;
		}
	}

	/**
	 * The override group of a method target key: the same {@code name(proto)ret} keyed against every
	 * class in the method's type hierarchy (ancestors via superclass/interfaces, and descendants via
	 * subclasses/implementors). Searching xrefs for ALL of these catches virtual-dispatch calls made
	 * through a super/interface/subtype, which a single-key search misses. Bounded by {@code cap}.
	 */
	public synchronized List<String> overrideKeys(String targetKey, int cap) throws SQLException {
		int arrow = targetKey.indexOf("->");
		if (arrow < 0) {
			return List.of(targetKey);
		}
		String declDesc = targetKey.substring(0, arrow);
		String rest = targetKey.substring(arrow + 2); // name(proto)ret
		java.util.LinkedHashSet<String> hier = new java.util.LinkedHashSet<>();
		hier.add(declDesc);
		// ancestors: superclass chain + interfaces, transitively
		java.util.ArrayDeque<String> up = new java.util.ArrayDeque<>();
		up.add(declDesc);
		while (!up.isEmpty() && hier.size() < cap) {
			String d = up.poll();
			String sup = queryOne("SELECT super_desc FROM classes WHERE desc=?", d);
			if (sup != null && !sup.equals("Ljava/lang/Object;") && hier.add(sup)) {
				up.add(sup);
			}
			for (String iface : queryList(
					"SELECT ci.iface_desc FROM class_iface ci JOIN classes c ON c.id=ci.class_id WHERE c.desc=?",
					d)) {
				if (hier.add(iface)) {
					up.add(iface);
				}
			}
		}
		// descendants: subclasses + implementors, transitively
		java.util.ArrayDeque<String> down = new java.util.ArrayDeque<>();
		down.add(declDesc);
		java.util.Set<String> seen = new java.util.HashSet<>(hier);
		while (!down.isEmpty() && hier.size() < cap) {
			String d = down.poll();
			for (String sub : queryList("SELECT desc FROM classes WHERE super_desc=?", d)) {
				if (seen.add(sub)) {
					hier.add(sub);
					down.add(sub);
				}
			}
			for (String impl : queryList(
					"SELECT c.desc FROM class_iface ci JOIN classes c ON c.id=ci.class_id WHERE ci.iface_desc=?",
					d)) {
				if (seen.add(impl)) {
					hier.add(impl);
					down.add(impl);
				}
			}
		}
		List<String> keys = new ArrayList<>(hier.size());
		for (String h : hier) {
			keys.add(h + "->" + rest);
		}
		return keys;
	}

	private String queryOne(String sql, String arg) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, arg);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	private List<String> queryList(String sql, String arg) throws SQLException {
		List<String> out = new ArrayList<>();
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, arg);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(rs.getString(1));
				}
			}
		}
		return out;
	}

	public static final class ImplHit {
		public final String desc;
		public final String fqn;
		public final int access;

		ImplHit(String desc, String fqn, int access) {
			this.desc = desc;
			this.fqn = fqn;
			this.access = access;
		}
	}

	/**
	 * Classes that declare the method of {@code targetKey} — its declaring class plus every descendant
	 * (subclass/implementor, transitively) that overrides it. Used for go-to-implementations on an
	 * abstract/interface method: gd offers all concrete implementations, not just the declaration.
	 */
	public synchronized List<ImplHit> implementations(String targetKey, int cap) throws SQLException {
		int arrow = targetKey.indexOf("->");
		if (arrow < 0) {
			return List.of();
		}
		String declDesc = targetKey.substring(0, arrow);
		String rest = targetKey.substring(arrow + 2); // name(proto)ret
		int paren = rest.indexOf('(');
		if (paren < 0) {
			return List.of();
		}
		String name = rest.substring(0, paren);
		String proto = rest.substring(paren);

		// declaring class + descendants (subclasses + implementors), transitively
		java.util.LinkedHashSet<String> hier = new java.util.LinkedHashSet<>();
		hier.add(declDesc);
		java.util.ArrayDeque<String> down = new java.util.ArrayDeque<>();
		down.add(declDesc);
		java.util.Set<String> seen = new java.util.HashSet<>(hier);
		while (!down.isEmpty() && hier.size() < cap) {
			String d = down.poll();
			for (String sub : queryList("SELECT desc FROM classes WHERE super_desc=?", d)) {
				if (seen.add(sub)) {
					hier.add(sub);
					down.add(sub);
				}
			}
			for (String impl : queryList(
					"SELECT c.desc FROM class_iface ci JOIN classes c ON c.id=ci.class_id WHERE ci.iface_desc=?",
					d)) {
				if (seen.add(impl)) {
					hier.add(impl);
					down.add(impl);
				}
			}
		}
		// Which of those classes actually declare the method (name + proto).
		StringBuilder in = new StringBuilder();
		for (int i = 0; i < hier.size(); i++) {
			in.append(i == 0 ? "?" : ",?");
		}
		String sql = "SELECT c.desc, c.fqn, m.access FROM methods m JOIN classes c ON c.id=m.class_id "
				+ "WHERE c.desc IN (" + in + ") AND m.name=? AND m.proto=?";
		List<ImplHit> out = new ArrayList<>();
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			int i = 1;
			for (String h : hier) {
				ps.setString(i++, h);
			}
			ps.setString(i++, name);
			ps.setString(i, proto);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new ImplHit(rs.getString(1), rs.getString(2), rs.getInt(3)));
				}
			}
		}
		return out;
	}

	/** Referencing classes for ANY of the given target keys (deduped, class-granular). */
	public synchronized List<XrefHit> xrefsToAny(List<String> targets, int limit) throws SQLException {
		if (targets.isEmpty()) {
			return List.of();
		}
		StringBuilder in = new StringBuilder();
		for (int i = 0; i < targets.size(); i++) {
			in.append(i == 0 ? "?" : ",?");
		}
		String sql = "SELECT DISTINCT c.desc,c.fqn,x.kind FROM xrefs x JOIN classes c ON c.id=x.src_class_id "
				+ "WHERE x.target IN (" + in + ") LIMIT ?";
		List<XrefHit> out = new ArrayList<>();
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			int i = 1;
			for (String t : targets) {
				ps.setString(i++, t);
			}
			ps.setInt(i, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new XrefHit(rs.getString(1), rs.getString(2), rs.getInt(3)));
				}
			}
		}
		return out;
	}

	/** The (deduped, class-granular) classes that reference {@code target}. */
	public synchronized List<XrefHit> xrefsTo(String target, int limit) throws SQLException {
		List<XrefHit> out = new ArrayList<>();
		String sql = "SELECT c.desc,c.fqn,x.kind FROM xrefs x "
				+ "JOIN classes c ON c.id=x.src_class_id WHERE x.target=? LIMIT ?";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, target);
			ps.setInt(2, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new XrefHit(rs.getString(1), rs.getString(2), rs.getInt(3)));
				}
			}
		}
		return out;
	}

	public static final class StringHit {
		public final long classId;
		public final String classFqn;
		public final String value;

		StringHit(long classId, String classFqn, String value) {
			this.classId = classId;
			this.classFqn = classFqn;
			this.value = value;
		}
	}

	/** Content search over string literals: which classes use a string matching {@code query}. */
	public synchronized List<StringHit> searchStrings(String query, int limit) throws SQLException {
		List<StringHit> out = new ArrayList<>();
		boolean useFts = query != null && query.length() >= 3;
		String sql;
		if (useFts) {
			sql = "SELECT DISTINCT su.class_id, c.fqn, su.value FROM str_use_fts f "
					+ "JOIN str_use su ON su.id=f.rowid JOIN classes c ON c.id=su.class_id "
					+ "WHERE f.value MATCH ? LIMIT ?";
		} else {
			sql = "SELECT DISTINCT su.class_id, c.fqn, su.value FROM str_use su "
					+ "JOIN classes c ON c.id=su.class_id WHERE su.value LIKE ? LIMIT ?";
		}
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, useFts ? "\"" + query.replace("\"", "\"\"") + "\"" : "%" + query + "%");
			ps.setInt(2, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new StringHit(rs.getLong(1), rs.getString(2), rs.getString(3)));
				}
			}
		}
		return out;
	}

	/** The owning dex entry name for a class descriptor (for the render engine's mini-dex hint). */
	public synchronized String dexEntryOf(String desc) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("SELECT dex FROM classes WHERE desc=?")) {
			ps.setString(1, desc);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	/** The class row id for a descriptor, or -1 if absent. */
	public synchronized long classIdOf(String desc) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM classes WHERE desc=?")) {
			ps.setString(1, desc);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getLong(1) : -1;
			}
		}
	}

	/**
	 * Index (or re-index) a class's decompiled Java into the source FTS (rowid = class id), so
	 * subsequent full-text searches cover its method bodies. Called lazily by the render engine as
	 * classes are viewed.
	 */
	public synchronized void indexSource(long classId, String source) throws SQLException {
		try (PreparedStatement del = conn.prepareStatement("DELETE FROM source_fts WHERE rowid=?")) {
			del.setLong(1, classId);
			del.executeUpdate();
		}
		try (PreparedStatement ins = conn.prepareStatement(
				"INSERT INTO source_fts(rowid, source) VALUES(?,?)")) {
			ins.setLong(1, classId);
			ins.setString(2, source);
			ins.executeUpdate();
		}
	}

	/** Full-text search over the (lazily filled) Java-source FTS: classes whose code matches. */
	public synchronized List<StringHit> searchSource(String query, int limit) throws SQLException {
		List<StringHit> out = new ArrayList<>();
		if (query == null || query.length() < 3) {
			return out; // trigram FTS needs >= 3 chars
		}
		String sql = "SELECT c.id, c.fqn FROM source_fts f JOIN classes c ON c.id=f.rowid "
				+ "WHERE f.source MATCH ? LIMIT ?";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, "\"" + query.replace("\"", "\"\"") + "\"");
			ps.setInt(2, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new StringHit(rs.getLong(1), rs.getString(2), null));
				}
			}
		}
		return out;
	}

	@Override
	public synchronized void close() throws SQLException {
		conn.close();
	}
}
