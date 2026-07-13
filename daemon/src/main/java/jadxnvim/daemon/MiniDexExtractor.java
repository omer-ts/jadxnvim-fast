package jadxnvim.daemon;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.MultiDexContainer;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

/**
 * Extracts a single class (plus its inner/anonymous classes and, optionally, its 1-hop reference
 * closure) from a large APK into a tiny standalone {@code .dex}. Feeding that mini-dex to jadx lets
 * the render engine decompile just the class the user is viewing, at MB-scale memory, instead of
 * paying jadx's whole-APK {@code load()}.
 *
 * <p>Class lookup is backed by a resident {@code type -> ClassDef} map (built once, lazily), so
 * extracting a class and its reference closure is O(references) rather than O(all classes) — the map
 * replaces a full scan of every dex on every render. The map holds dexlib2's lightweight
 * (offset-based, memory-mapped) class wrappers, so it stays cheap even for a 700MB / 165k-class APK.
 *
 * <p>References from the class body to types not present in the mini-dex remain valid dex references
 * (framework types resolve from jadx's bundled classpath; unresolved app types render by raw name).
 */
public final class MiniDexExtractor {

	// Cap the reference closure so a class referencing thousands of types can't explode the mini-dex.
	private static final int CLOSURE_CAP = 600;

	private final File apk;
	private final Opcodes opcodes;
	private volatile MultiDexContainer<? extends DexBackedDexFile> container;

	// type descriptor -> its ClassDef (first occurrence wins across multidex duplicates).
	private volatile Map<String, ClassDef> byType;
	// outer type descriptor -> its inner/nested classes (Lcom/ex/Foo; -> [Foo$Bar, Foo$Bar$Baz, ...]).
	private volatile Map<String, List<ClassDef>> innersByOuter;

	public MiniDexExtractor(File apk) {
		this.apk = apk;
		this.opcodes = Opcodes.getDefault();
	}

	private MultiDexContainer<? extends DexBackedDexFile> container() throws Exception {
		MultiDexContainer<? extends DexBackedDexFile> c = container;
		if (c == null) {
			synchronized (this) {
				if (container == null) {
					container = DexFileFactory.loadDexContainer(apk, opcodes);
				}
				c = container;
			}
		}
		return c;
	}

	// Build the type -> ClassDef and outer -> inners maps once (single O(all-classes) pass, reused by
	// every subsequent extraction). Synchronized; concurrent first-callers block until it is ready.
	private void ensureMaps() throws Exception {
		if (byType != null) {
			return;
		}
		synchronized (this) {
			if (byType != null) {
				return;
			}
			Map<String, ClassDef> bt = new HashMap<>(1 << 18);
			Map<String, List<ClassDef>> ibo = new HashMap<>();
			MultiDexContainer<? extends DexBackedDexFile> c = container();
			for (String entry : c.getDexEntryNames()) {
				DexBackedDexFile dex = c.getEntry(entry).getDexFile();
				for (ClassDef cd : dex.getClasses()) {
					String t = cd.getType();
					if (bt.putIfAbsent(t, cd) != null) {
						continue; // duplicate type across multidex — keep the first
					}
					// The first '$' is inside the class name (packages use '/', never '$'), so the part
					// before it is the top-level outer type. Groups Foo$Bar and Foo$Bar$Baz under Foo.
					int dollar = t.indexOf('$');
					if (dollar > 0) {
						String outer = t.substring(0, dollar) + ";";
						ibo.computeIfAbsent(outer, k -> new ArrayList<>()).add(cd);
					}
				}
			}
			innersByOuter = ibo;
			byType = bt; // published last — it is the readiness guard
		}
	}

	// Add a class + its inner/nested classes to the picked list (deduped by type).
	private void addFamily(String classDesc, List<ClassDef> picked, Set<String> types) {
		ClassDef target = byType.get(classDesc);
		if (target == null) {
			return;
		}
		if (types.add(classDesc)) {
			picked.add(target);
		}
		for (ClassDef inner : innersByOuter.getOrDefault(classDesc, List.of())) {
			if (types.add(inner.getType())) {
				picked.add(inner);
			}
		}
	}

	/**
	 * Write a mini-dex containing {@code classDesc} (a raw dex descriptor, e.g. {@code Lcom/ex/Foo;})
	 * and its inner classes to {@code outDex}. Returns the number of classes written (0 if not found).
	 */
	public int extract(String classDesc, File outDex) throws Exception {
		ensureMaps();
		List<ClassDef> picked = new ArrayList<>();
		Set<String> types = new java.util.HashSet<>();
		addFamily(classDesc, picked, types);
		if (picked.isEmpty()) {
			return 0;
		}
		write(picked, outDex);
		return picked.size();
	}

	/**
	 * Like {@link #extract}, but also pulls in the 1-hop reference closure — every app class the
	 * target's method bodies reference (invoked methods' owners, accessed fields' owners, used types,
	 * super/interfaces). jadx then builds nodes for those classes, so a cursor on a cross-class call
	 * resolves to a real node (needed for go-to-def / find-usages). Each closure class is only parsed
	 * (not decompiled), so rendering the target stays cheap. O(references), not O(all classes).
	 */
	public int extractWithClosure(String classDesc, File outDex) throws Exception {
		ensureMaps();
		List<ClassDef> picked = new ArrayList<>();
		Set<String> types = new java.util.HashSet<>();
		addFamily(classDesc, picked, types);
		if (picked.isEmpty()) {
			return 0;
		}
		// Collect the types referenced by the target family, then resolve each via the map (O(refs)).
		Set<String> wanted = new java.util.HashSet<>();
		for (ClassDef cd : new ArrayList<>(picked)) {
			collectRefs(cd, wanted);
		}
		int added = 0;
		for (String w : wanted) {
			if (added >= CLOSURE_CAP) {
				break;
			}
			if (types.contains(w)) {
				continue;
			}
			ClassDef cd = byType.get(w);
			if (cd != null && types.add(w)) {
				picked.add(cd);
				added++;
			}
		}
		write(picked, outDex);
		return picked.size();
	}

	private void write(List<ClassDef> picked, File outDex) throws Exception {
		outDex.getAbsoluteFile().getParentFile().mkdirs();
		DexPool.writeTo(outDex.getAbsolutePath(), new ImmutableDexFile(opcodes, picked));
	}

	// Collect the type descriptors a class's method bodies (and its super/interfaces) reference.
	private static void collectRefs(ClassDef cd, Set<String> out) {
		String sup = cd.getSuperclass();
		if (sup != null) {
			out.add(sup);
		}
		for (String itf : cd.getInterfaces()) {
			out.add(itf);
		}
		for (com.android.tools.smali.dexlib2.iface.Method m : cd.getMethods()) {
			com.android.tools.smali.dexlib2.iface.MethodImplementation impl = m.getImplementation();
			if (impl == null) {
				continue;
			}
			for (com.android.tools.smali.dexlib2.iface.instruction.Instruction insn : impl.getInstructions()) {
				if (insn instanceof com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction) {
					addRefType(((com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction) insn)
							.getReference(), out);
				}
			}
		}
	}

	private static void addRefType(com.android.tools.smali.dexlib2.iface.reference.Reference ref,
			Set<String> out) {
		if (ref instanceof com.android.tools.smali.dexlib2.iface.reference.MethodReference) {
			out.add(((com.android.tools.smali.dexlib2.iface.reference.MethodReference) ref).getDefiningClass());
		} else if (ref instanceof com.android.tools.smali.dexlib2.iface.reference.FieldReference) {
			out.add(((com.android.tools.smali.dexlib2.iface.reference.FieldReference) ref).getDefiningClass());
		} else if (ref instanceof com.android.tools.smali.dexlib2.iface.reference.TypeReference) {
			out.add(((com.android.tools.smali.dexlib2.iface.reference.TypeReference) ref).getType());
		}
	}
}
