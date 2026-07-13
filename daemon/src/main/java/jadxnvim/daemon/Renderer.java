package jadxnvim.daemon;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import java.util.ArrayList;
import java.util.Map;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.ICodeInfo;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.impl.NoOpCodeCache;
import jadx.api.metadata.ICodeAnnotation;
import jadx.api.metadata.ICodeMetadata;
import jadx.core.codegen.TypeGen;
import jadx.core.dex.info.FieldInfo;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.args.ArgType;

/**
 * Render engine: decompiles a single class by running jadx over a {@link MiniDexExtractor}-produced
 * mini-dex, so a Java view of a class in a 700MB APK costs only what that one class costs — never the
 * whole-APK {@code load()}. Also resolves the symbol under a cursor (via jadx code metadata) and the
 * declaration position of a symbol, which power instant go-to-def / find-usages against the SQLite
 * xref index — all without building jadx's whole-program model.
 */
public final class Renderer {

	private final MiniDexExtractor extractor;
	private final File workDir;
	// Unique temp-file suffix so find-usages renders (which run in parallel) never collide.
	private final java.util.concurrent.atomic.AtomicInteger tmpSeq = new java.util.concurrent.atomic.AtomicInteger();

	public Renderer(File apk, File workDir) {
		this.extractor = new MiniDexExtractor(apk);
		this.workDir = workDir;
	}

	/** A precise usage site within a referencing class. */
	public static final class Usage {
		public final int line;
		public final int col;
		public final String text;

		Usage(int line, int col, String text) {
			this.line = line;
			this.col = col;
			this.text = text;
		}
	}

	public static final class Result {
		public final String fqn;
		public final String code;
		public final int classesInMiniDex;

		Result(String fqn, String code, int classesInMiniDex) {
			this.fqn = fqn;
			this.code = code;
			this.classesInMiniDex = classesInMiniDex;
		}
	}

	/** A symbol resolved from a cursor position, keyed to match the dexlib2 xref index. */
	public static final class ResolvedSymbol {
		public final int kind;            // Db.KIND_CLASS / KIND_METHOD / KIND_FIELD
		public final String targetKey;    // dexlib2 xref target key (matches DexIndexer key builders)
		public final String declClassDesc; // declaring class descriptor, e.g. Lcom/example/Foo;
		public final String rawName;      // raw (runtime) member name, or class fqn for a class
		public final String displayName;  // human name for UI

		ResolvedSymbol(int kind, String targetKey, String declClassDesc, String rawName, String displayName) {
			this.kind = kind;
			this.targetKey = targetKey;
			this.declClassDesc = declClassDesc;
			this.rawName = rawName;
			this.displayName = displayName;
		}
	}

	/** {line, col} plus whether the exact declaration was located. */
	public static final class Pos {
		public final int line;
		public final int col;
		public final boolean exact;

		Pos(int line, int col, boolean exact) {
			this.line = line;
			this.col = col;
			this.exact = exact;
		}
	}

	private interface RenderFn<T> {
		T apply(JadxDecompiler jadx, JavaClass cls, ICodeInfo info, String code) throws Exception;
	}

	// Render a class from a mini-dex and hand (jadx, class, codeInfo, code) to fn, closing after.
	// Single-threaded decompile keeps the output deterministic, so a cursor offset computed against a
	// previously returned getCode() string still resolves to the right node here.
	private <T> T withRenderedClass(String classDesc, boolean closure, T missing,
			RenderFn<T> fn) throws Exception {
		String fqn = DexIndexer.descToFqn(classDesc);
		File miniDex = new File(workDir,
				"minidex-" + (closure ? "c" : "") + Integer.toHexString(classDesc.hashCode()) + ".dex");
		try {
			int n = closure
					? extractor.extractWithClosure(classDesc, miniDex)
					: extractor.extract(classDesc, miniDex);
			if (n == 0) {
				return missing;
			}
			JadxArgs args = new JadxArgs();
			args.getInputFiles().add(miniDex);
			args.setCodeCache(NoOpCodeCache.INSTANCE);
			args.setSkipResources(true);
			args.setShowInconsistentCode(true);
			args.setThreadsCount(1);
			try (JadxDecompiler jadx = new JadxDecompiler(args)) {
				jadx.load();
				JavaClass target = findClass(jadx.getClasses(), fqn);
				if (target == null) {
					return missing;
				}
				ICodeInfo info = target.getCodeInfo();
				return fn.apply(jadx, target, info, info.getCodeStr());
			}
		} finally {
			try {
				Files.deleteIfExists(miniDex.toPath());
			} catch (Exception ignore) {
				// best effort
			}
		}
	}

	/**
	 * Decompile the class with raw descriptor {@code classDesc}. Rendered WITH the reference closure so
	 * the buffer the user sees is the exact same rendering that {@link #resolveAt} / {@link
	 * #declarationPos} resolve cursor positions against — otherwise a closure-only render would use a
	 * different layout and cross-class go-to-def / find-usages in reference-heavy classes (e.g.
	 * Activities) would resolve to the wrong offset or nothing. The closure render also resolves more
	 * app types, so the code reads better. Deterministic (single-threaded + a deterministic mini-dex),
	 * so a re-render for resolveAt lands on identical offsets.
	 */
	public Result decompile(String classDesc) throws Exception {
		String fqn = DexIndexer.descToFqn(classDesc);
		Result r = withRenderedClass(classDesc, true, null,
				(jadx, cls, info, code) -> new Result(fqn, code, 1));
		if (r == null) {
			throw new IllegalArgumentException("class not found in APK: " + fqn);
		}
		return r;
	}

	/**
	 * Resolve the symbol referenced at (line, col) in the rendered class {@code classDesc}, keyed so
	 * it can be looked up in the SQLite xref index. Returns null if nothing resolves there.
	 */
	public ResolvedSymbol resolveAt(String classDesc, int line, int col)
			throws Exception {
		return withRenderedClass(classDesc, true, null, (jadx, cls, info, code) -> {
			int offset = Positions.toOffset(code, line, col);
			JavaNode node = jadx.getJavaNodeAtPosition(info, offset);
			return node == null ? null : symbolOf(node);
		});
	}

	/**
	 * Locate the declaration of {@code target} inside its (top-level) class {@code classDesc}, using
	 * jadx's node positions. Falls back to line 1 if not found.
	 */
	public Pos declarationPos(String classDesc, ResolvedSymbol target)
			throws Exception {
		// Closure render so the returned line matches the class's getCode() buffer (same rendering).
		Pos p = withRenderedClass(classDesc, true, null, (jadx, cls, info, code) -> {
			JavaNode decl = findDecl(cls, target);
			if (decl == null) {
				return null;
			}
			int pos = decl.getDefPos();
			if (pos < 0) {
				return new Pos(1, 0, false);
			}
			int[] lc = Positions.toLineCol(code, pos);
			return new Pos(lc[0], lc[1], true);
		});
		return p == null ? new Pos(1, 0, false) : p;
	}

	/**
	 * Find every precise site in {@code refClassDesc} that references the symbol keyed by
	 * {@code targetKey}. Renders the referencing class together with the target class (so jadx
	 * annotates the references) and reads the code metadata to recover exact line/col + the code line
	 * text — the data that makes find-usages navigable, resolved on demand for just this one class.
	 */
	public java.util.List<Usage> findUsageSites(String refClassDesc, java.util.Set<String> candidateTargets,
			java.util.Set<String> targetKeys) throws Exception {
		String fqn = DexIndexer.descToFqn(refClassDesc);
		File miniDex = new File(workDir, "usages-" + tmpSeq.incrementAndGet() + ".dex");
		try {
			// Light render: the referencing class + only the target classes it references (enough to
			// annotate the calls to the target). Much cheaper than its full closure; the reported line/col
			// may differ slightly from getCode()'s buffer, so the caller re-locates by the code line text.
			int n = extractor.extractForUsages(refClassDesc, candidateTargets, miniDex);
			if (n == 0) {
				return java.util.List.of();
			}
			JadxArgs args = new JadxArgs();
			args.getInputFiles().add(miniDex);
			args.setCodeCache(NoOpCodeCache.INSTANCE);
			args.setSkipResources(true);
			args.setShowInconsistentCode(true);
			args.setThreadsCount(1);
			try (JadxDecompiler jadx = new JadxDecompiler(args)) {
				jadx.load();
				JavaClass refClass = findClass(jadx.getClasses(), fqn);
				if (refClass == null) {
					return java.util.List.of();
				}
				ICodeInfo info = refClass.getCodeInfo();
				ICodeMetadata md = info.getCodeMetadata();
				if (md == null) {
					return java.util.List.of();
				}
				String code = info.getCodeStr();
				String[] lines = code.split("\n", -1);
				java.util.List<Usage> out = new ArrayList<>();
				java.util.Set<Integer> seenLines = new java.util.HashSet<>();
				for (Map.Entry<Integer, ICodeAnnotation> e : md.getAsMap().entrySet()) {
					ICodeAnnotation.AnnType t = e.getValue().getAnnType();
					// References only (not the declaration itself).
					if (t != ICodeAnnotation.AnnType.CLASS && t != ICodeAnnotation.AnnType.METHOD
							&& t != ICodeAnnotation.AnnType.FIELD) {
						continue;
					}
					int off = e.getKey();
					JavaNode node = jadx.getJavaNodeAtPosition(info, off);
					if (node == null) {
						continue;
					}
					ResolvedSymbol s = symbolOf(node);
					if (s == null || !targetKeys.contains(s.targetKey)) {
						continue;
					}
					int[] lc = Positions.toLineCol(code, off);
					if (!seenLines.add(lc[0])) {
						continue; // one entry per line
					}
					String text = lc[0] >= 1 && lc[0] <= lines.length ? lines[lc[0] - 1].strip() : "";
					out.add(new Usage(lc[0], lc[1], text));
				}
				return out;
			}
		} finally {
			try {
				Files.deleteIfExists(miniDex.toPath());
			} catch (Exception ignore) {
				// best effort
			}
		}
	}

	// Find the class/method/field node in cls (recursively through inner classes) whose key matches.
	private static JavaNode findDecl(JavaClass cls, ResolvedSymbol target) {
		if (target.kind == Db.KIND_CLASS) {
			if (target.targetKey.equals(descOf(cls.getRawName()))) {
				return cls;
			}
		} else if (target.kind == Db.KIND_METHOD) {
			for (JavaMethod m : cls.getMethods()) {
				if (target.targetKey.equals(methodKey(m))) {
					return m;
				}
			}
		} else if (target.kind == Db.KIND_FIELD) {
			for (JavaField f : cls.getFields()) {
				if (target.targetKey.equals(fieldKey(f))) {
					return f;
				}
			}
		}
		for (JavaClass inner : cls.getInnerClasses()) {
			JavaNode r = findDecl(inner, target);
			if (r != null) {
				return r;
			}
		}
		return null;
	}

	private static ResolvedSymbol symbolOf(JavaNode node) {
		if (node instanceof JavaMethod) {
			JavaMethod jm = (JavaMethod) node;
			JavaClass decl = jm.getDeclaringClass();
			if (decl == null) {
				return null;
			}
			String key = methodKey(jm);
			MethodInfo mi = jm.getMethodNode().getMethodInfo();
			return new ResolvedSymbol(Db.KIND_METHOD, key, descOf(decl.getRawName()),
					mi.getName(), mi.getName());
		}
		if (node instanceof JavaField) {
			JavaField jf = (JavaField) node;
			JavaClass decl = jf.getDeclaringClass();
			if (decl == null) {
				return null;
			}
			return new ResolvedSymbol(Db.KIND_FIELD, fieldKey(jf), descOf(decl.getRawName()),
					jf.getRawName(), jf.getName());
		}
		if (node instanceof JavaClass) {
			JavaClass jc = (JavaClass) node;
			String desc = descOf(jc.getRawName());
			return new ResolvedSymbol(Db.KIND_CLASS, desc, desc, jc.getFullName(), jc.getName());
		}
		return null;
	}

	// dexlib2-style keys, matching DexIndexer.methodKey/fieldKey exactly (built from raw descriptors).
	private static String methodKey(JavaMethod jm) {
		JavaClass decl = jm.getDeclaringClass();
		MethodInfo mi = jm.getMethodNode().getMethodInfo();
		StringBuilder args = new StringBuilder();
		for (ArgType a : mi.getArgumentsTypes()) {
			args.append(TypeGen.signature(a));
		}
		return descOf(decl.getRawName()) + "->" + mi.getName() + "(" + args + ")"
				+ TypeGen.signature(mi.getReturnType());
	}

	private static String fieldKey(JavaField jf) {
		JavaClass decl = jf.getDeclaringClass();
		FieldInfo fi = jf.getFieldNode().getFieldInfo();
		return descOf(decl.getRawName()) + "->" + jf.getRawName() + ":" + TypeGen.signature(fi.getType());
	}

	private static String descOf(String dottedRawName) {
		return "L" + dottedRawName.replace('.', '/') + ";";
	}

	private static JavaClass findClass(List<JavaClass> classes, String fqn) {
		for (JavaClass c : classes) {
			if (fqn.equals(c.getFullName()) || fqn.equals(c.getRawName())) {
				return c;
			}
		}
		return classes.size() == 1 ? classes.get(0) : null;
	}
}
