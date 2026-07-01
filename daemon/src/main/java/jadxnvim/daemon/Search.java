package jadxnvim.daemon;

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

import jadx.api.ICodeInfo;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;

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
	}

	synchronized int start(JadxDecompiler jadx, String kind, String query, Opts opts) {
		int searchId = ++nextId;
		AtomicBoolean cancel = new AtomicBoolean(false);
		running.put(searchId, cancel);
		Pattern pattern = compile(query, opts);
		exec.submit(() -> {
			int count = 0;
			boolean truncated = false;
			try {
				if ("name".equals(kind)) {
					int[] r = runName(jadx, searchId, pattern, opts, cancel);
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
		}
		return new int[] { count, 0 };
	}

	// returns {count, truncated(0/1)}
	private int[] runName(JadxDecompiler jadx, int searchId, Pattern pattern, Opts opts, AtomicBoolean cancel) {
		int count = 0;
		for (JavaClass cls : jadx.getClassesWithInners()) {
			if (cancel.get()) {
				return new int[] { count, 0 };
			}
			List<JavaNode> matched = new ArrayList<>();
			if (pattern.matcher(cls.getName()).find() || pattern.matcher(cls.getFullName()).find()) {
				matched.add(cls);
			}
			for (JavaMethod mth : cls.getMethods()) {
				if (pattern.matcher(mth.getName()).find()) {
					matched.add(mth);
				}
			}
			for (JavaField fld : cls.getFields()) {
				if (pattern.matcher(fld.getName()).find()) {
					matched.add(fld);
				}
			}
			if (matched.isEmpty()) {
				continue;
			}
			// Resolve positions by decompiling only the (few) classes that matched.
			List<Map<String, Object>> hits = new ArrayList<>();
			for (JavaNode node : matched) {
				JavaClass top = node.getTopParentClass();
				int[] lc = { 1, 0 };
				try {
					String code = top.getCodeInfo().getCodeStr();
					int pos = node.getDefPos();
					if (pos >= 0) {
						lc = Positions.toLineCol(code, pos);
					}
				} catch (Exception ignore) {
					// fall back to line 1
				}
				Map<String, Object> hit = new LinkedHashMap<>();
				hit.put("id", top.getRawName());
				hit.put("fullName", node.getFullName());
				hit.put("line", lc[0]);
				hit.put("col", lc[1]);
				hit.put("kind", kindOf(node));
				hit.put("text", node.getFullName());
				hits.add(hit);
				count++;
				if (count >= opts.limit) {
					flush(searchId, hits);
					return new int[] { count, 1 };
				}
			}
			flush(searchId, hits);
		}
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
