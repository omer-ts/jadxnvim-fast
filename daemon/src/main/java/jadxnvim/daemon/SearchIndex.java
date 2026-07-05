package jadxnvim.daemon;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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

	/** The shard a class's code/name/xref entries live in — lets a query target one file, not all. */
	int shardIndexOf(String id) {
		return shardOf(id);
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

		// Drop trailing entries whose bytes fall beyond maxBytes. Entries are appended in ascending
		// byteStart order, so trimming from the end is exact. Used on resume: a crash mid-checkpoint
		// can leave the .idx snapshot one batch ahead of the committed .pos position; those extra
		// entries point past the truncated shard data and must be dropped (else codeOf reads garbage).
		void truncateToByte(long maxBytes) {
			while (size > 0 && byteStart[size - 1] + byteLen[size - 1] > maxBytes) {
				size--;
			}
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
			this(shardsDir, namesDir, xrefDir, false);
		}

		private Builder(File shardsDir, File namesDir, File xrefDir, boolean append) throws IOException {
			this.shardsDir = shardsDir;
			this.namesDir = namesDir;
			this.xrefDir = xrefDir;
			shardsDir.mkdirs();
			namesDir.mkdirs();
			xrefDir.mkdirs();
			try {
				for (int i = 0; i < SHARDS; i++) {
					writers[i] = open(new File(shardsDir, shardName(i)), append);
					clsWriters[i] = open(new File(namesDir, classesName(i)), append);
					mthWriters[i] = open(new File(namesDir, methodsName(i)), append);
					refWriters[i] = open(new File(xrefDir, refsName(i)), append);
					declWriters[i] = open(new File(xrefDir, declsName(i)), append);
					locks[i] = new Object();
					meta[i] = new ShardMeta();
				}
			} catch (IOException e) {
				// don't leak the writers already opened if one open() fails mid-construction
				closeWriters();
				throw e;
			}
		}

		private void closeWriters() {
			for (int i = 0; i < SHARDS; i++) {
				closeQuietly(writers[i]);
				closeQuietly(clsWriters[i]);
				closeQuietly(mthWriters[i]);
				closeQuietly(refWriters[i]);
				closeQuietly(declWriters[i]);
			}
		}

		private static Writer open(File f, boolean append) throws IOException {
			if (append) {
				return Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8,
						java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
			}
			return Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8);
		}

		/**
		 * Reopen a partially-built index to continue where a checkpoint left off: truncate every
		 * shard file back to its checkpointed length (dropping any partial tail from the interrupted
		 * batch), reopen writers in append mode, and restore the line index + byte/line counters.
		 */
		static Builder resume(File shardsDir, File namesDir, File xrefDir, File metaDir) throws IOException {
			List<String> pos = Files.readAllLines(new File(metaDir, ".pos").toPath(), StandardCharsets.UTF_8);
			long[] codeB = new long[SHARDS];
			int[] codeL = new int[SHARDS];
			for (int i = 0; i < SHARDS && i < pos.size(); i++) {
				String[] f = pos.get(i).split("\t", -1);
				if (f.length < 6) {
					continue;
				}
				codeB[i] = Long.parseLong(f[0]);
				codeL[i] = Integer.parseInt(f[1]);
				truncate(new File(shardsDir, shardName(i)), codeB[i]);
				truncate(new File(namesDir, classesName(i)), Long.parseLong(f[2]));
				truncate(new File(namesDir, methodsName(i)), Long.parseLong(f[3]));
				truncate(new File(xrefDir, refsName(i)), Long.parseLong(f[4]));
				truncate(new File(xrefDir, declsName(i)), Long.parseLong(f[5]));
			}
			Builder b = new Builder(shardsDir, namesDir, xrefDir, true);
			for (int i = 0; i < SHARDS; i++) {
				b.byteCount[i] = codeB[i];
				b.lineCount[i] = codeL[i];
				loadMeta(new File(metaDir, String.format("s%03d.idx", i)), b.meta[i]);
				// .idx may be one checkpoint ahead of .pos if a crash landed mid-checkpoint; keep only
				// entries within the committed shard data so meta and the truncated shards agree.
				b.meta[i].truncateToByte(codeB[i]);
			}
			return b;
		}

		/** The class ids currently committed to the (possibly resumed) index — the resume "done" set. */
		java.util.Set<String> committedIds() {
			java.util.Set<String> out = new java.util.HashSet<>();
			for (int i = 0; i < SHARDS; i++) {
				ShardMeta m = meta[i];
				for (int j = 0; j < m.size; j++) {
					out.add(m.id[j]);
				}
			}
			return out;
		}

		private static void truncate(File f, long len) {
			try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
				if (raf.length() > len) {
					raf.setLength(len);
				}
			} catch (Exception ignore) {
				// best effort; a missing/short file just gets appended to
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

		/**
		 * Close shard files and write the persisted line index + signatures; returns the index. The
		 * full signature (.sig, input+codeData) marks the index as exactly matching the project; the
		 * input signature (.isig, input only) marks it as structurally reusable after a rename/comment.
		 */
		SearchIndex finish(File metaDir, long inputSignature, long fullSignature) throws IOException {
			closeWriters();
			writeIdx(metaDir);
			atomicWrite(new File(metaDir, ".isig"), Long.toString(inputSignature).getBytes(StandardCharsets.UTF_8));
			atomicWrite(new File(metaDir, ".sig"), Long.toString(fullSignature).getBytes(StandardCharsets.UTF_8));
			return new SearchIndex(shardsDir, namesDir, xrefDir, meta);
		}

		/**
		 * Persist a consistent checkpoint without closing: flush every writer, then record the line
		 * index (.idx) and byte/line positions (.pos). Call only when no worker is mid-add (i.e.
		 * between batches) so the positions match what's on disk. Resume truncates back to these.
		 */
		void checkpoint(File metaDir) throws IOException {
			for (int i = 0; i < SHARDS; i++) {
				writers[i].flush();
				clsWriters[i].flush();
				mthWriters[i].flush();
				refWriters[i].flush();
				declWriters[i].flush();
			}
			// .idx snapshots first (each written atomically), then .pos LAST as the single commit
			// point: resume only trusts what .pos records, and drops any .idx entries beyond it. A
			// crash before .pos lands leaves the previous .pos intact, so the interrupted batch is
			// simply re-indexed — never duplicated, never read at a stale offset.
			writeIdx(metaDir);
			StringBuilder pos = new StringBuilder();
			for (int i = 0; i < SHARDS; i++) {
				// codeBytes \t lineCount \t clsBytes \t mthBytes \t refBytes \t declBytes
				pos.append(byteCount[i]).append('\t').append(lineCount[i]).append('\t')
						.append(new File(namesDir, classesName(i)).length()).append('\t')
						.append(new File(namesDir, methodsName(i)).length()).append('\t')
						.append(new File(xrefDir, refsName(i)).length()).append('\t')
						.append(new File(xrefDir, declsName(i)).length()).append('\n');
			}
			atomicWrite(new File(metaDir, ".pos"), pos.toString().getBytes(StandardCharsets.UTF_8));
		}

		private void writeIdx(File metaDir) throws IOException {
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
				atomicWrite(new File(metaDir, String.format("s%03d.idx", i)), sb.toString().getBytes(StandardCharsets.UTF_8));
			}
		}

		// Write via a temp file + atomic rename, so a crash mid-write never leaves a partial file: a
		// reader sees either the old contents or the new, never a truncated line.
		private static void atomicWrite(File f, byte[] data) throws IOException {
			File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
			Files.write(tmp.toPath(), data);
			try {
				Files.move(tmp.toPath(), f.toPath(),
						StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
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
		return sigMatches(new File(metaDir, ".sig"), signature);
	}

	/**
	 * True when the index was built for this exact input (same .isig), regardless of whether the
	 * code data (renames/comments) has changed since. Such an index describes the same classes at the
	 * same positions and can be served immediately while a background refresh updates stale names.
	 * Older indexes predate .isig and report false, so they rebuild once to gain fast reuse.
	 */
	static boolean structurallyValid(File metaDir, long inputSignature) {
		return sigMatches(new File(metaDir, ".isig"), inputSignature);
	}

	private static boolean sigMatches(File sig, long signature) {
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
			loadMeta(new File(metaDir, String.format("s%03d.idx", i)), m);
			shards[i] = m;
		}
		return new SearchIndex(shardsDir, namesDir, xrefDir, shards);
	}

	// Parse a per-shard .idx ("startLine\tbyteStart\tbyteLen\tid\tfullName") into a ShardMeta.
	private static void loadMeta(File f, ShardMeta m) throws IOException {
		if (!f.isFile()) {
			return;
		}
		for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
			if (line.isEmpty()) {
				continue;
			}
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
}
