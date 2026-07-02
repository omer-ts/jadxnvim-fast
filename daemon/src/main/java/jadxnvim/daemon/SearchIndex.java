package jadxnvim.daemon;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A ripgrep-friendly full-text index for decompiled sources.
 *
 * <p>Instead of one file per class (a 400k-class APK produces ~300k tiny files, which cripples
 * ripgrep with per-file overhead), all class code is concatenated into a small number of "shard"
 * files. A line index records, per shard, the first line of every class, so a ripgrep match at
 * (shard, line) maps back to (class id, line-within-class). Search then scans ~32 files instead of
 * hundreds of thousands.
 */
final class SearchIndex {

	private static final int SHARDS = 32;

	private final File shardsDir;
	private final File namesDir;
	private final File xrefDir;
	private final ShardMeta[] shards;
	// class id -> {shardIndex, byteStart, byteLen} for reading a class's exported code off disk.
	private final Map<String, long[]> codeLoc;

	private SearchIndex(File shardsDir, File namesDir, File xrefDir, ShardMeta[] shards) {
		this.shardsDir = shardsDir;
		this.namesDir = namesDir;
		this.xrefDir = xrefDir;
		this.shards = shards;
		this.codeLoc = new HashMap<>();
		for (int i = 0; i < shards.length; i++) {
			ShardMeta m = shards[i];
			for (int j = 0; j < m.size; j++) {
				codeLoc.put(m.id[j], new long[] { i, m.byteStart[j], m.byteLen[j] });
			}
		}
	}

	File shardsDir() {
		return shardsDir;
	}

	/** True if this class's exported code can be read from disk via {@link #codeOf(String)}. */
	boolean hasCode(String id) {
		return codeLoc.containsKey(id);
	}

	/** All indexed top-level classes as {id, fullName} — lets the tree be built without the model. */
	List<String[]> classEntries() {
		List<String[]> out = new java.util.ArrayList<>();
		for (ShardMeta m : shards) {
			for (int j = 0; j < m.size; j++) {
				out.add(new String[] { m.id[j], m.fullName[j] });
			}
		}
		return out;
	}

	/** Read a class's exported decompiled code straight from its shard (seek + read), or null. */
	String codeOf(String id) {
		long[] loc = codeLoc.get(id);
		if (loc == null) {
			return null;
		}
		File shard = new File(shardsDir, shardName((int) loc[0]));
		try (RandomAccessFile raf = new RandomAccessFile(shard, "r")) {
			raf.seek(loc[1]);
			byte[] buf = new byte[(int) loc[2]];
			raf.readFully(buf);
			return new String(buf, StandardCharsets.UTF_8);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Directory of per-shard class/method name files ({@code cls_s*.txt}, {@code mth_s*.txt}) that
	 * back the fast (ripgrep) class/method finders. Separate from {@link #shardsDir()} so full-text
	 * search never scans it. Null/absent means only the in-memory name scan is available.
	 */
	File namesDir() {
		return namesDir;
	}

	/**
	 * Directory of the cross-reference index ({@code ref_s*.txt} usages, {@code decl_s*.txt}
	 * declarations), each line {@code key\tclassId\tline\tcol}. Backs disk go-to-def / find-usages
	 * so lean mode answers them without the model. Null/absent means fall back to the model.
	 */
	File xrefDir() {
		return xrefDir;
	}

	static String shardName(int i) {
		return String.format("s%03d.txt", i);
	}

	static String classesName(int i) {
		return String.format("cls_s%03d.txt", i);
	}

	static String methodsName(int i) {
		return String.format("mth_s%03d.txt", i);
	}

	static String refsName(int i) {
		return String.format("ref_s%03d.txt", i);
	}

	static String declsName(int i) {
		return String.format("decl_s%03d.txt", i);
	}

	private static int shardOf(String id) {
		return Math.floorMod(id.hashCode(), SHARDS);
	}

	/** Sorted per-shard index: parallel arrays of a class's first line, byte range, id and name. */
	private static final class ShardMeta {
		int[] startLine = new int[0];
		long[] byteStart = new long[0];
		int[] byteLen = new int[0];
		String[] id = new String[0];
		String[] fullName = new String[0];
		int size = 0;

		void ensure(int cap) {
			if (cap <= startLine.length) {
				return;
			}
			int n = Math.max(cap, Math.max(16, startLine.length * 2));
			startLine = java.util.Arrays.copyOf(startLine, n);
			byteStart = java.util.Arrays.copyOf(byteStart, n);
			byteLen = java.util.Arrays.copyOf(byteLen, n);
			id = java.util.Arrays.copyOf(id, n);
			fullName = java.util.Arrays.copyOf(fullName, n);
		}

		void add(int line, long bStart, int bLen, String cid, String fn) {
			ensure(size + 1);
			startLine[size] = line;
			byteStart[size] = bStart;
			byteLen[size] = bLen;
			id[size] = cid;
			fullName[size] = fn;
			size++;
		}

		// Largest index whose startLine <= line, or -1.
		int find(int line) {
			int lo = 0;
			int hi = size - 1;
			int res = -1;
			while (lo <= hi) {
				int mid = (lo + hi) >>> 1;
				if (startLine[mid] <= line) {
					res = mid;
					lo = mid + 1;
				} else {
					hi = mid - 1;
				}
			}
			return res;
		}
	}

	/** Result of mapping a (shard, line) match back to a class. */
	static final class Hit {
		final String id;
		final String fullName;
		final int line; // 1-based line within the class (matches getCode output)

		Hit(String id, String fullName, int line) {
			this.id = id;
			this.fullName = fullName;
			this.line = line;
		}
	}

	Hit lookup(String shardFileName, int fileLine) {
		int idx = shardIndexFromName(shardFileName);
		if (idx < 0 || idx >= shards.length) {
			return null;
		}
		ShardMeta m = shards[idx];
		int p = m.find(fileLine);
		if (p < 0) {
			return null;
		}
		int local = fileLine - m.startLine[p] + 1;
		return new Hit(m.id[p], m.fullName[p], local);
	}

	private static int shardIndexFromName(String name) {
		// s%03d.txt
		try {
			int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
			String base = slash >= 0 ? name.substring(slash + 1) : name;
			if (base.startsWith("s") && base.endsWith(".txt")) {
				return Integer.parseInt(base.substring(1, base.length() - 4));
			}
		} catch (Exception ignore) {
			// not a shard file
		}
		return -1;
	}

	// --- building ------------------------------------------------------------

	/** Concurrent-safe builder that streams class code into shard files and records the index. */
	static final class Builder {
		private final File shardsDir;
		private final File namesDir;
		private final File xrefDir;
		private final Writer[] writers = new Writer[SHARDS];
		private final Writer[] clsWriters = new Writer[SHARDS];
		private final Writer[] mthWriters = new Writer[SHARDS];
		private final Writer[] refWriters = new Writer[SHARDS];
		private final Writer[] declWriters = new Writer[SHARDS];
		private final Object[] locks = new Object[SHARDS];
		private final int[] lineCount = new int[SHARDS];
		private final long[] byteCount = new long[SHARDS];
		private final ShardMeta[] meta = new ShardMeta[SHARDS];

		Builder(File shardsDir, File namesDir, File xrefDir) throws IOException {
			this.shardsDir = shardsDir;
			this.namesDir = namesDir;
			this.xrefDir = xrefDir;
			shardsDir.mkdirs();
			namesDir.mkdirs();
			xrefDir.mkdirs();
			for (int i = 0; i < SHARDS; i++) {
				writers[i] = Files.newBufferedWriter(new File(shardsDir, shardName(i)).toPath(),
						StandardCharsets.UTF_8);
				clsWriters[i] = Files.newBufferedWriter(new File(namesDir, classesName(i)).toPath(),
						StandardCharsets.UTF_8);
				mthWriters[i] = Files.newBufferedWriter(new File(namesDir, methodsName(i)).toPath(),
						StandardCharsets.UTF_8);
				refWriters[i] = Files.newBufferedWriter(new File(xrefDir, refsName(i)).toPath(),
						StandardCharsets.UTF_8);
				declWriters[i] = Files.newBufferedWriter(new File(xrefDir, declsName(i)).toPath(),
						StandardCharsets.UTF_8);
				locks[i] = new Object();
				meta[i] = new ShardMeta();
			}
		}

		// methodLines.get(i) is method i's declaration line in the exported code (or null), stored so
		// lean-mode memberPos resolves without the model. refLines/declLines are pre-formatted
		// "key\tid\tline\toffset" cross-reference edges (usages and declaration sites).
		void add(String id, String fullName, String code, List<String> methodNames,
				List<String> methodLines, List<String> refLines, List<String> declLines) throws IOException {
			if (code == null) {
				code = "";
			}
			if (!code.endsWith("\n")) {
				code = code + "\n";
			}
			int lines = 0;
			for (int i = 0; i < code.length(); i++) {
				if (code.charAt(i) == '\n') {
					lines++;
				}
			}
			byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
			int shard = shardOf(id);
			synchronized (locks[shard]) {
				int start = lineCount[shard] + 1;
				long byteStart = byteCount[shard];
				writers[shard].write(code);
				lineCount[shard] += lines;
				byteCount[shard] += codeBytes.length;
				meta[shard].add(start, byteStart, codeBytes.length, id, fullName);
				// name files: class line "fullName\tid", method line "name\tfullName\tid\tindex".
				// Names/ids are Java identifiers/dotted names, so they never contain tab/newline.
				clsWriters[shard].write(fullName);
				clsWriters[shard].write('\t');
				clsWriters[shard].write(id);
				clsWriters[shard].write('\n');
				if (methodNames != null) {
					Writer mw = mthWriters[shard];
					for (int i = 0; i < methodNames.size(); i++) {
						String nm = methodNames.get(i);
						if (nm == null || nm.isEmpty()) {
							continue;
						}
						mw.write(nm);
						mw.write('\t');
						mw.write(fullName);
						mw.write('\t');
						mw.write(id);
						mw.write('\t');
						mw.write(Integer.toString(i));
						mw.write('\t');
						String mline = (methodLines != null && i < methodLines.size()) ? methodLines.get(i) : null;
						mw.write(mline == null ? "1" : mline);
						mw.write('\n');
					}
				}
				if (refLines != null) {
					Writer rw = refWriters[shard];
					for (String ln : refLines) {
						rw.write(ln);
						rw.write('\n');
					}
				}
				if (declLines != null) {
					Writer dw = declWriters[shard];
					for (String ln : declLines) {
						dw.write(ln);
						dw.write('\n');
					}
				}
			}
		}

		/** Close shard files and write the persisted line index; returns the queryable index. */
		SearchIndex finish(File metaDir, long signature) throws IOException {
			for (int i = 0; i < SHARDS; i++) {
				closeQuietly(writers[i]);
				closeQuietly(clsWriters[i]);
				closeQuietly(mthWriters[i]);
				closeQuietly(refWriters[i]);
				closeQuietly(declWriters[i]);
			}
			metaDir.mkdirs();
			for (int i = 0; i < SHARDS; i++) {
				ShardMeta m = meta[i];
				StringBuilder sb = new StringBuilder();
				for (int j = 0; j < m.size; j++) {
					// startLine \t byteStart \t byteLen \t id \t fullName
					sb.append(m.startLine[j]).append('\t').append(m.byteStart[j]).append('\t')
							.append(m.byteLen[j]).append('\t').append(m.id[j]).append('\t')
							.append(m.fullName[j]).append('\n');
				}
				Files.write(new File(metaDir, String.format("s%03d.idx", i)).toPath(),
						sb.toString().getBytes(StandardCharsets.UTF_8));
			}
			Files.write(new File(metaDir, ".sig").toPath(),
					Long.toString(signature).getBytes(StandardCharsets.UTF_8));
			return new SearchIndex(shardsDir, namesDir, xrefDir, meta);
		}

		private static void closeQuietly(Writer w) {
			try {
				if (w != null) {
					w.close();
				}
			} catch (Exception ignore) {
				// best effort
			}
		}
	}

	// --- persistence ---------------------------------------------------------

	static boolean isValid(File metaDir, long signature) {
		File sig = new File(metaDir, ".sig");
		if (!sig.isFile()) {
			return false;
		}
		try {
			String s = new String(Files.readAllBytes(sig.toPath()), StandardCharsets.UTF_8).trim();
			return s.equals(Long.toString(signature));
		} catch (Exception e) {
			return false;
		}
	}

	static SearchIndex load(File shardsDir, File namesDir, File xrefDir, File metaDir) throws IOException {
		ShardMeta[] shards = new ShardMeta[SHARDS];
		for (int i = 0; i < SHARDS; i++) {
			ShardMeta m = new ShardMeta();
			File f = new File(metaDir, String.format("s%03d.idx", i));
			if (f.isFile()) {
				List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
				for (String line : lines) {
					if (line.isEmpty()) {
						continue;
					}
					// startLine \t byteStart \t byteLen \t id \t fullName
					int t1 = line.indexOf('\t');
					int t2 = line.indexOf('\t', t1 + 1);
					int t3 = line.indexOf('\t', t2 + 1);
					int t4 = line.indexOf('\t', t3 + 1);
					if (t1 < 0 || t2 < 0 || t3 < 0 || t4 < 0) {
						continue;
					}
					int start = Integer.parseInt(line.substring(0, t1));
					long byteStart = Long.parseLong(line.substring(t1 + 1, t2));
					int byteLen = Integer.parseInt(line.substring(t2 + 1, t3));
					String id = line.substring(t3 + 1, t4);
					String fn = line.substring(t4 + 1);
					m.add(start, byteStart, byteLen, id, fn);
				}
			}
			shards[i] = m;
		}
		return new SearchIndex(shardsDir, namesDir, xrefDir, shards);
	}
}
