package jadxnvim.daemon;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jadx.api.ICodeInfo;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;

/**
 * Background search engine. Searches run on a worker thread and stream results to the client as
 * {@code searchHits} notifications (batched per class), finishing with {@code searchDone}.
 *
 * <p>Full-text search must decompile every class, so it is inherently heavy on large APKs — hence
 * the streaming + cancellation model rather than a blocking request/response.
 */
final class Search {

	private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "jadx-search");
		t.setDaemon(true);
		return t;
	});
	private final Map<Integer, AtomicBoolean> running = new ConcurrentHashMap<>();
	private final Session.Emitter emitter;
	private int nextId = 0;

	Search(Session.Emitter emitter) {
		this.emitter = emitter;
	}

	static final class Opts {
		int limit = 2000;
		boolean caseSensitive = false;
		boolean regex = false;
		String kind = "all"; // for name search: "class" | "method" | "all"
	}

	synchronized int start(JadxDecompiler jadx, String kind, String query, Opts opts, SearchIndex index,
			String rgPath) {
		int searchId = ++nextId;
		AtomicBoolean cancel = new AtomicBoolean(false);
		running.put(searchId, cancel);
		Pattern pattern = compile(query, opts);
		exec.submit(() -> {
			int count = 0;
			boolean truncated = false;
			try {
				if ("name".equals(kind)) {
					int[] r = null;
					boolean namesRg = index != null && index.namesDir() != null
							&& index.namesDir().isDirectory()
							&& ("class".equals(opts.kind) || "method".equals(opts.kind));
					if (namesRg) {
						// Fast path: ripgrep over the class/method name files built during export.
						r = runNameRg(searchId, query, opts, index, rgPath, cancel);
					}
					if (r == null) {
						r = runName(jadx, searchId, pattern, opts, cancel);
					}
					count = r[0];
					truncated = r[1] == 1;
				} else if (index != null && index.shardsDir().isDirectory()) {
					// Fast path: ripgrep over the shard index. Fall back to the in-memory scan if
					// rg is unavailable.
					int[] r = runRg(searchId, query, opts, index, rgPath, cancel);
					if (r == null) {
						r = runText(jadx, searchId, pattern, opts, cancel);
					}
					count = r[0];
					truncated = r[1] == 1;
				} else {
					int[] r = runText(jadx, searchId, pattern, opts, cancel);
					count = r[0];
					truncated = r[1] == 1;
				}
			} catch (Throwable t) {
				System.err.println("[jadxd] search error: " + t);
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
		return searchId;
	}

	void cancel(int searchId) {
		AtomicBoolean c = running.get(searchId);
		if (c != null) {
			c.set(true);
		}
	}

	private static Pattern compile(String query, Opts opts) {
		int flags = opts.caseSensitive ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
		String pat = opts.regex ? query : Pattern.quote(query);
		return Pattern.compile(pat, flags);
	}

	// Fast full-text search via ripgrep over the exported sources. Returns {count, truncated} or
	// null if rg could not be started (so the caller falls back to the in-memory scan).
	private int[] runRg(int searchId, String query, Opts opts, SearchIndex index, String rgPath,
			AtomicBoolean cancel) {
		List<String> cmd = new ArrayList<>();
		cmd.add(rgPath != null && !rgPath.isEmpty() ? rgPath : "rg");
		cmd.add("--json");
		cmd.add("--line-number");
		if (!opts.caseSensitive) {
			cmd.add("-i");
		}
		if (!opts.regex) {
			cmd.add("-F");
		}
		cmd.add("--");
		cmd.add(query);
		cmd.add(index.shardsDir().getAbsolutePath());

		Process proc;
		try {
			// discard rg's stderr at the OS level so it can never fill the pipe buffer and block
			proc = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		} catch (Exception e) {
			System.err.println("[jadxd] ripgrep unavailable, using in-memory search: " + e);
			return null;
		}
		try {
			proc.getOutputStream().close();
		} catch (Exception ignore) {
			// no stdin needed
		}

		List<Map<String, Object>> batch = new ArrayList<>();
		String batchId = null;
		int count = 0;
		boolean truncated = false;

		try (BufferedReader r = new BufferedReader(
				new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			outer: while ((line = r.readLine()) != null) {
				if (cancel.get()) {
					break;
				}
				JsonObject o;
				try {
					o = JsonParser.parseString(line).getAsJsonObject();
				} catch (Exception e) {
					continue;
				}
				JsonElement typeEl = o.get("type");
				if (typeEl == null || !"match".equals(typeEl.getAsString())) {
					continue;
				}
				JsonObject data = o.getAsJsonObject("data");
				String path = textOf(data.getAsJsonObject("path"));
				String lineText = textOf(data.getAsJsonObject("lines"));
				if (lineText.endsWith("\n")) {
					lineText = lineText.substring(0, lineText.length() - 1);
				}
				int fileLine = data.get("line_number").getAsInt();
				// Map (shard file, line) back to (class id, line-within-class) via the index.
				SearchIndex.Hit hit = index.lookup(path, fileLine);
				if (hit == null) {
					continue;
				}

				if (batchId != null && !batchId.equals(hit.id) && !batch.isEmpty()) {
					flush(searchId, batch);
				}
				batchId = hit.id;

				JsonArray subs = data.getAsJsonArray("submatches");
				for (JsonElement se : subs) {
					int startByte = se.getAsJsonObject().get("start").getAsInt();
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", hit.id);
					m.put("fullName", hit.fullName);
					m.put("line", hit.line);
					m.put("col", byteToChar(lineText, startByte));
					m.put("text", lineText.strip());
					batch.add(m);
					count++;
					if (count >= opts.limit) {
						truncated = true;
						break outer;
					}
				}
			}
			flush(searchId, batch);
		} catch (Exception e) {
			System.err.println("[jadxd] ripgrep read error: " + e);
		} finally {
			proc.destroy();
		}
		return new int[] { count, truncated ? 1 : 0 };
	}

	private static String textOf(JsonObject obj) {
		if (obj == null) {
			return "";
		}
		JsonElement t = obj.get("text");
		return (t != null && !t.isJsonNull()) ? t.getAsString() : "";
	}

	// rg reports a UTF-8 byte offset within the line; map it to a character index (identity for ASCII).
	private static int byteToChar(String lineText, int startByte) {
		if (startByte <= 0) {
			return 0;
		}
		int bytes = 0;
		for (int i = 0; i < lineText.length(); i++) {
			if (bytes >= startByte) {
				return i;
			}
			char c = lineText.charAt(i);
			if (Character.isHighSurrogate(c) && i + 1 < lineText.length()
					&& Character.isLowSurrogate(lineText.charAt(i + 1))) {
				bytes += 4; // supplementary code point: 4 UTF-8 bytes across the surrogate pair
				i++; // the low surrogate is consumed here too
			} else {
				bytes += (c < 0x80) ? 1 : (c < 0x800) ? 2 : 3;
			}
		}
		return lineText.length();
	}

	// returns {count, truncated(0/1)}
	private int[] runText(JadxDecompiler jadx, int searchId, Pattern pattern, Opts opts, AtomicBoolean cancel) {
		int count = 0;
		for (JavaClass cls : jadx.getClasses()) {
			if (cancel.get()) {
				return new int[] { count, 0 };
			}
			String code;
			try {
				code = cls.getCodeInfo().getCodeStr();
			} catch (Exception e) {
				continue;
			}
			List<Map<String, Object>> hits = new ArrayList<>();
			int line = 1;
			int lineStart = 0;
			int len = code.length();
			for (int i = 0; i <= len; i++) {
				if (i == len || code.charAt(i) == '\n') {
					String lineText = code.substring(lineStart, i);
					Matcher m = pattern.matcher(lineText);
					int from = 0;
					while (m.find(from)) {
						Map<String, Object> hit = new LinkedHashMap<>();
						hit.put("id", cls.getRawName());
						hit.put("fullName", cls.getFullName());
						hit.put("line", line);
						hit.put("col", m.start());
						hit.put("text", lineText.strip());
						hits.add(hit);
						count++;
						if (count >= opts.limit) {
							flush(searchId, hits);
							return new int[] { count, 1 };
						}
						from = m.end() > m.start() ? m.end() : m.start() + 1;
						if (from > lineText.length()) {
							break;
						}
					}
					line++;
					lineStart = i + 1;
				}
			}
			flush(searchId, hits);
			// Free the decompiled state so scanning a 400k-class APK in memory can't exhaust the
			// heap (this path is only the fallback when the exported sources aren't ready).
			try {
				cls.unload();
			} catch (Exception ignore) {
				// best effort
			}
		}
		return new int[] { count, 0 };
	}

	// returns {count, truncated(0/1)}
	private int[] runName(JadxDecompiler jadx, int searchId, Pattern pattern, Opts opts, AtomicBoolean cancel) {
		// Class- and method-only searches (the fuzzy finders) stream lightweight matches without
		// decompiling — enumerating names is cheap and never holds the whole (potentially millions
		// of methods) list in memory, so it scales to 400k-class APKs. The declaration position is
		// resolved lazily when the user opens a result.
		if ("class".equals(opts.kind)) {
			return runClassNames(jadx, searchId, pattern, opts, cancel);
		}
		if ("method".equals(opts.kind)) {
			return runMethodNames(jadx, searchId, pattern, opts, cancel);
		}
		// "all": class + method + field names. Match against the raw parsed model (no load()), then
		// decompile only the classes that actually matched to resolve declaration positions.
		int count = 0;
		for (JavaClass cls : jadx.getClasses()) {
			if (cancel.get()) {
				return new int[] { count, 0 };
			}
			ClassNode cn = cls.getClassNode();
			boolean clsMatch = pattern.matcher(cls.getName()).find()
					|| pattern.matcher(cls.getFullName()).find();
			List<MethodNode> mMatched = new ArrayList<>();
			List<MethodNode> mths = cn.getMethods();
			for (MethodNode m : mths) {
				if (m.contains(AFlag.DONT_GENERATE)) {
					continue;
				}
				if (pattern.matcher(m.getMethodInfo().getAlias()).find()) {
					mMatched.add(m);
				}
			}
			List<FieldNode> fMatched = new ArrayList<>();
			for (FieldNode f : cn.getFields()) {
				if (f.contains(AFlag.DONT_GENERATE)) {
					continue;
				}
				if (pattern.matcher(f.getFieldInfo().getAlias()).find()) {
					fMatched.add(f);
				}
			}
			if (!clsMatch && mMatched.isEmpty() && fMatched.isEmpty()) {
				continue;
			}
			// Decompile once (only matched classes) to resolve positions.
			String code = null;
			try {
				code = cls.getCodeInfo().getCodeStr();
			} catch (Exception ignore) {
				// positions fall back to line 1
			}
			List<Map<String, Object>> hits = new ArrayList<>();
			String id = cls.getRawName();
			if (clsMatch) {
				hits.add(nameHit(id, cls.getFullName(), "class", posOf(code, cls.getDefPos())));
				count++;
			}
			for (MethodNode m : mMatched) {
				String label = m.getMethodInfo().getAlias() + "  ·  " + cls.getFullName();
				hits.add(nameHit(id, label, "method", posOf(code, m.getDefPosition())));
				count++;
			}
			for (FieldNode f : fMatched) {
				String label = f.getFieldInfo().getAlias() + "  ·  " + cls.getFullName();
				hits.add(nameHit(id, label, "field", posOf(code, f.getDefPosition())));
				count++;
			}
			if (count >= opts.limit) {
				flush(searchId, hits);
				return new int[] { count, 1 };
			}
			flush(searchId, hits);
		}
		return new int[] { count, 0 };
	}

	private int[] posOf(String code, int pos) {
		if (code != null && pos >= 0) {
			return Positions.toLineCol(code, pos);
		}
		return new int[] { 1, 0 };
	}

	private static Map<String, Object> nameHit(String id, String label, String kind, int[] lc) {
		Map<String, Object> hit = new LinkedHashMap<>();
		hit.put("id", id);
		hit.put("fullName", label);
		hit.put("line", lc[0]);
		hit.put("col", lc[1]);
		hit.put("kind", kind);
		hit.put("text", label);
		return hit;
	}

	// Fast class/method name search: ripgrep the per-shard name files, anchoring the query to the
	// name column only ("^[^\t]*<query>"). Each line already carries the class id (and, for methods,
	// the method index), so results need no decompilation. Returns null if rg can't be launched.
	private int[] runNameRg(int searchId, String query, Opts opts, SearchIndex index, String rgPath,
			AtomicBoolean cancel) {
		boolean methods = "method".equals(opts.kind);
		List<String> cmd = new ArrayList<>();
		cmd.add(rgPath != null && !rgPath.isEmpty() ? rgPath : "rg");
		cmd.add("--no-filename");
		cmd.add("--no-line-number");
		cmd.add("--no-heading");
		cmd.add("--no-ignore");
		if (!opts.caseSensitive) {
			cmd.add("-i");
		}
		cmd.add("-g");
		cmd.add(methods ? "mth_s*.txt" : "cls_s*.txt");
		cmd.add("-e");
		cmd.add("^[^\\t]*" + (opts.regex ? query : rgEscape(query)));
		cmd.add(index.namesDir().getAbsolutePath());

		Process proc;
		try {
			// discard rg's stderr at the OS level so it can never fill the pipe buffer and block
			proc = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		} catch (Exception e) {
			System.err.println("[jadxd] ripgrep unavailable for name search: " + e);
			return null;
		}
		try {
			proc.getOutputStream().close();
		} catch (Exception ignore) {
			// no stdin needed
		}

		List<Map<String, Object>> batch = new ArrayList<>();
		int count = 0;
		boolean truncated = false;
		try (BufferedReader r = new BufferedReader(
				new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (cancel.get()) {
					break;
				}
				String[] f = line.split("\t", -1);
				Map<String, Object> hit = new LinkedHashMap<>();
				if (methods) {
					// name \t classFullName \t id \t index
					if (f.length < 4) {
						continue;
					}
					int idx;
					try {
						idx = Integer.parseInt(f[3]);
					} catch (NumberFormatException e) {
						continue;
					}
					String label = f[0] + "  ·  " + f[1];
					hit.put("id", f[2]);
					hit.put("index", idx);
					hit.put("kind", "method");
					hit.put("fullName", label);
					hit.put("line", 1);
					hit.put("col", 0);
					hit.put("text", label);
				} else {
					// fullName \t id
					if (f.length < 2) {
						continue;
					}
					hit.put("id", f[1]);
					hit.put("kind", "class");
					hit.put("fullName", f[0]);
					hit.put("line", 1);
					hit.put("col", 0);
					hit.put("text", f[0]);
				}
				batch.add(hit);
				count++;
				if (batch.size() >= 500) {
					flush(searchId, batch);
				}
				if (count >= opts.limit) {
					flush(searchId, batch);
					truncated = true;
					break;
				}
			}
		} catch (Exception e) {
			System.err.println("[jadxd] name search read error: " + e);
		} finally {
			proc.destroy();
		}
		flush(searchId, batch);
		return new int[] { count, truncated ? 1 : 0 };
	}

	// Escape a literal string for use inside a ripgrep (Rust) regex.
	private static String rgEscape(String s) {
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

	// Stream class-name matches. Reads only the parsed model (ClassInfo names never trigger the
	// lazy decompile), and iterates top-level classes so no class is loaded during the scan --
	// getClassesWithInners() would call load() on every class. This is the jadx-gui approach.
	private int[] runClassNames(JadxDecompiler jadx, int searchId, Pattern pattern, Opts opts,
			AtomicBoolean cancel) {
		int count = 0;
		List<Map<String, Object>> batch = new ArrayList<>();
		for (JavaClass cls : jadx.getClasses()) {
			if (cancel.get()) {
				break;
			}
			if (pattern.matcher(cls.getName()).find() || pattern.matcher(cls.getFullName()).find()) {
				Map<String, Object> hit = new LinkedHashMap<>();
				hit.put("id", cls.getRawName());
				hit.put("fullName", cls.getFullName());
				hit.put("kind", "class");
				hit.put("line", 1);
				hit.put("col", 0);
				hit.put("text", cls.getFullName());
				batch.add(hit);
				count++;
				if (batch.size() >= 500) {
					flush(searchId, batch);
				}
				if (count >= opts.limit) {
					flush(searchId, batch);
					return new int[] { count, 1 };
				}
			}
		}
		flush(searchId, batch);
		return new int[] { count, 0 };
	}

	// Stream method-name matches from top-level classes. Reads the raw core MethodNode list
	// (cls.getClassNode().getMethods()) instead of cls.getMethods(): the latter calls load() (lazy
	// decompile of every class -> minutes and huge memory on a 400k-class APK) and also sorts/filters
	// so its indices wouldn't match memberPos. The index carried here is the position in the raw
	// list, which memberPos resolves the same way. Matches jadx-gui's MethodSearchProvider.
	private int[] runMethodNames(JadxDecompiler jadx, int searchId, Pattern pattern, Opts opts,
			AtomicBoolean cancel) {
		int count = 0;
		List<Map<String, Object>> batch = new ArrayList<>();
		for (JavaClass cls : jadx.getClasses()) {
			if (cancel.get()) {
				break;
			}
			String clsFull = cls.getFullName();
			String id = cls.getRawName();
			List<MethodNode> methods = cls.getClassNode().getMethods();
			for (int i = 0; i < methods.size(); i++) {
				MethodNode m = methods.get(i);
				if (m.contains(AFlag.DONT_GENERATE)) {
					continue;
				}
				String nm = m.getMethodInfo().getAlias();
				if (pattern.matcher(nm).find()) {
					String label = nm + "  ·  " + clsFull;
					Map<String, Object> hit = new LinkedHashMap<>();
					hit.put("id", id);
					hit.put("index", i);
					hit.put("kind", "method");
					hit.put("fullName", label);
					hit.put("line", 1);
					hit.put("col", 0);
					hit.put("text", label);
					batch.add(hit);
					count++;
					if (batch.size() >= 500) {
						flush(searchId, batch);
					}
					if (count >= opts.limit) {
						flush(searchId, batch);
						return new int[] { count, 1 };
					}
				}
			}
		}
		flush(searchId, batch);
		return new int[] { count, 0 };
	}

	private static String kindOf(JavaNode node) {
		if (node instanceof JavaClass) {
			return "class";
		}
		if (node instanceof JavaMethod) {
			return "method";
		}
		if (node instanceof JavaField) {
			return "field";
		}
		return "node";
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
}
