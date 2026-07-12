package jadxnvim.daemon;

import java.io.File;
import java.util.List;

/**
 * Standalone CLI over the v2 index engine, for building/inspecting the SQLite index without a
 * Neovim frontend. Subcommands:
 *
 * <pre>
 *   jadxd index &lt;apk&gt; [--out &lt;db&gt;]     build the index (dexlib2 → SQLite)
 *   jadxd stats &lt;db&gt;                    row counts
 *   jadxd search &lt;db&gt; &lt;query&gt; [limit]   fuzzy symbol search
 *   jadxd xref  &lt;db&gt; &lt;target&gt; [limit]   find references to a type/method/field key
 * </pre>
 */
public final class Cli {

	/** Returns true if {@code argv} was a recognised subcommand (already handled). */
	public static boolean run(String[] argv) throws Exception {
		if (argv.length == 0) {
			return false;
		}
		switch (argv[0]) {
			case "index":
				index(argv);
				return true;
			case "stats":
				stats(argv);
				return true;
			case "search":
				search(argv);
				return true;
			case "xref":
				xref(argv);
				return true;
			case "decompile":
				decompile(argv);
				return true;
			default:
				return false;
		}
	}

	private static void index(String[] argv) throws Exception {
		if (argv.length < 2) {
			System.err.println("usage: jadxd index <apk> [--out <db>]");
			System.exit(2);
		}
		File input = new File(argv[1]);
		String out = defaultDbPath(input);
		for (int i = 2; i < argv.length; i++) {
			if ("--out".equals(argv[i]) && i + 1 < argv.length) {
				out = argv[++i];
			}
		}
		new File(out).getAbsoluteFile().getParentFile().mkdirs();
		// A fresh build assumes an empty schema; start from a clean file.
		new File(out).delete();
		new File(out + "-wal").delete();
		new File(out + "-shm").delete();

		long t0 = System.nanoTime();
		try (Db db = Db.open(out)) {
			DexIndexer indexer = new DexIndexer(db, (done, pct) -> {
				System.err.print("\r[jadxd] indexing " + pct + "%  (" + done + " classes)   ");
			});
			indexer.build(input);
			System.err.println();
			long ms = (System.nanoTime() - t0) / 1_000_000;
			System.out.println("indexed " + input.getName() + " -> " + out + " in " + ms + " ms");
			System.out.println("  classes=" + db.count("classes")
					+ " methods=" + db.count("methods")
					+ " fields=" + db.count("fields")
					+ " str_uses=" + db.count("str_use")
					+ " xrefs=" + db.count("xrefs"));
		}
	}

	private static void stats(String[] argv) throws Exception {
		if (argv.length < 2) {
			System.err.println("usage: jadxd stats <db>");
			System.exit(2);
		}
		try (Db db = Db.open(argv[1])) {
			System.out.println("classes=" + db.count("classes"));
			System.out.println("methods=" + db.count("methods"));
			System.out.println("fields=" + db.count("fields"));
			System.out.println("str_uses=" + db.count("str_use"));
			System.out.println("xrefs=" + db.count("xrefs"));
			System.out.println("symbols=" + db.count("symbols"));
			System.out.println("source_fts=" + db.count("source_fts"));
			System.out.println("input_signature=" + db.getMeta("input_signature"));
		}
	}

	private static void search(String[] argv) throws Exception {
		if (argv.length < 3) {
			System.err.println("usage: jadxd search <db> <query> [limit]");
			System.exit(2);
		}
		int limit = argv.length > 3 ? Integer.parseInt(argv[3]) : 30;
		try (Db db = Db.open(argv[1])) {
			List<Db.SymbolHit> hits = db.searchSymbols(argv[2], -1, limit);
			for (Db.SymbolHit h : hits) {
				System.out.println(kindTag(h.kind) + "  " + h.fqn);
			}
			System.out.println("(" + hits.size() + " hits)");
		}
	}

	private static void xref(String[] argv) throws Exception {
		if (argv.length < 3) {
			System.err.println("usage: jadxd xref <db> <target> [limit]");
			System.exit(2);
		}
		int limit = argv.length > 3 ? Integer.parseInt(argv[3]) : 50;
		try (Db db = Db.open(argv[1])) {
			List<Db.XrefHit> hits = db.xrefsTo(argv[2], limit);
			for (Db.XrefHit h : hits) {
				System.out.println(refTag(h.kind) + "  " + h.srcClassFqn);
			}
			System.out.println("(" + hits.size() + " references)");
		}
	}

	private static void decompile(String[] argv) throws Exception {
		if (argv.length < 3) {
			System.err.println("usage: jadxd decompile <apk> <fqn|descriptor> [--db <db>]");
			System.exit(2);
		}
		File apk = new File(argv[1]);
		String target = argv[2];
		String db = defaultDbPath(apk);
		for (int i = 3; i < argv.length; i++) {
			if ("--db".equals(argv[i]) && i + 1 < argv.length) {
				db = argv[++i];
			}
		}
		// Accept either a raw descriptor (Lcom/example/Foo;) or a dotted fqn (com.example.Foo).
		String desc = target.startsWith("L") && target.endsWith(";")
				? target
				: "L" + target.replace('.', '/') + ";";

		// Look up the owning dex entry from the index to avoid scanning every dex (best effort).
		String dexHint = null;
		File dbFile = new File(db);
		if (dbFile.isFile()) {
			try (Db d = Db.open(db);
					java.sql.PreparedStatement ps = d.connection().prepareStatement(
							"SELECT dex FROM classes WHERE desc=?")) {
				ps.setString(1, desc);
				try (java.sql.ResultSet rs = ps.executeQuery()) {
					if (rs.next()) {
						dexHint = rs.getString(1);
					}
				}
			}
		}

		File work = new File(System.getProperty("java.io.tmpdir"), "jadxd-render");
		long t0 = System.nanoTime();
		Renderer renderer = new Renderer(apk, work);
		Renderer.Result r = renderer.decompile(desc, dexHint);
		long ms = (System.nanoTime() - t0) / 1_000_000;
		System.out.println(r.code);
		System.err.println("[jadxd] decompiled " + r.fqn + " (mini-dex: " + r.classesInMiniDex
				+ " classes) in " + ms + " ms");
	}

	private static String defaultDbPath(File input) {
		String name = input.getName();
		int dot = name.lastIndexOf('.');
		String base = dot > 0 ? name.substring(0, dot) : name;
		File dir = new File(input.getAbsoluteFile().getParentFile(), base + ".jadxnvim");
		return new File(dir, "index.db").getAbsolutePath();
	}

	private static String kindTag(int kind) {
		switch (kind) {
			case Db.KIND_CLASS: return "class ";
			case Db.KIND_METHOD: return "method";
			case Db.KIND_FIELD: return "field ";
			default: return "?     ";
		}
	}

	private static String refTag(int kind) {
		switch (kind) {
			case Db.REF_TYPE: return "type  ";
			case Db.REF_METHOD: return "call  ";
			case Db.REF_FIELD: return "field ";
			default: return "?     ";
		}
	}

	private Cli() {
	}
}
