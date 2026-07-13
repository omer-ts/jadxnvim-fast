package jadxnvim.daemon;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.MultiDexContainer;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

/**
 * Extracts a single class (plus its inner/anonymous classes) from a large APK into a tiny standalone
 * {@code .dex}. Feeding that mini-dex to jadx lets the render engine decompile just the class the user
 * is viewing, at MB-scale memory, instead of paying jadx's whole-APK {@code load()} (minutes and
 * multi-GB — jadx has no lazy class loading, so {@code --single-class} does not avoid it).
 *
 * <p>References from the class body to types not present in the mini-dex remain valid dex references
 * (framework types are resolved from jadx's bundled classpath; unresolved app types render by their
 * raw name). Including the inner classes keeps anonymous/nested rendering intact.
 */
public final class MiniDexExtractor {

	private final File apk;
	private final Opcodes opcodes;
	private volatile MultiDexContainer<? extends DexBackedDexFile> container;

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

	/**
	 * Write a mini-dex containing {@code classDesc} and its inner classes to {@code outDex}.
	 * {@code classDesc} is a raw dex type descriptor, e.g. {@code Lcom/example/Foo;}. When
	 * {@code dexEntryHint} is non-null it is scanned first (the class's owning dex, from the index),
	 * avoiding a scan of every dex entry.
	 *
	 * @return the number of classes written (0 if the target was not found)
	 */
	public int extract(String classDesc, String dexEntryHint, File outDex) throws Exception {
		String innerPrefix = classDesc.substring(0, classDesc.length() - 1) + "$"; // Lcom/ex/Foo$
		List<ClassDef> picked = new ArrayList<>();
		// Dedup by type: a class present in more than one dex (legal in multidex) would otherwise be
		// written twice and DexPool.writeTo rejects duplicate types.
		java.util.Set<String> pickedTypes = new java.util.HashSet<>();

		MultiDexContainer<? extends DexBackedDexFile> c = container();
		List<String> entries = new ArrayList<>();
		if (dexEntryHint != null && c.getDexEntryNames().contains(dexEntryHint)) {
			entries.add(dexEntryHint);
		} else {
			entries.addAll(c.getDexEntryNames());
		}
		boolean foundTarget = false;
		for (String entry : entries) {
			DexBackedDexFile dex = c.getEntry(entry).getDexFile();
			for (ClassDef cd : dex.getClasses()) {
				String t = cd.getType();
				if (t.equals(classDesc)) {
					if (pickedTypes.add(t)) {
						picked.add(cd);
					}
					foundTarget = true;
				} else if (t.startsWith(innerPrefix) && pickedTypes.add(t)) {
					picked.add(cd);
				}
			}
			if (foundTarget && dexEntryHint != null) {
				// Inner classes almost always live in the same dex as the outer; if the hint located the
				// target, we already collected its inners here. (Rare cross-dex inners degrade to missing
				// nested rendering, not a failure.)
				break;
			}
		}
		if (!foundTarget && !entries.equals(c.getDexEntryNames())) {
			// Hint missed (class not in the hinted dex): fall back to a full scan.
			return extract(classDesc, null, outDex);
		}
		if (picked.isEmpty()) {
			return 0;
		}
		outDex.getAbsoluteFile().getParentFile().mkdirs();
		DexPool.writeTo(outDex.getAbsolutePath(), new ImmutableDexFile(opcodes, picked));
		return picked.size();
	}

	/**
	 * Extract several class families (each = a class + its inner classes) into one mini-dex. Used by
	 * find-usages to render a referencing class together with the target class, so jadx annotates the
	 * references to the target and their precise offsets can be recovered. {@code hints} (may be null,
	 * or hold nulls) name each class's owning dex to avoid scanning every entry.
	 */
	public int extractFamilies(String[] descs, String[] hints, File outDex) throws Exception {
		MultiDexContainer<? extends DexBackedDexFile> c = container();
		List<String> allEntries = c.getDexEntryNames();
		List<ClassDef> picked = new ArrayList<>();
		java.util.Set<String> pickedTypes = new java.util.HashSet<>();

		for (int k = 0; k < descs.length; k++) {
			String desc = descs[k];
			if (desc == null) {
				continue;
			}
			String hint = hints != null ? hints[k] : null;
			String innerPrefix = desc.substring(0, desc.length() - 1) + "$";
			boolean found = collectFamily(c, desc, innerPrefix,
					hint != null && allEntries.contains(hint) ? List.of(hint) : allEntries,
					picked, pickedTypes);
			if (!found && hint != null) {
				collectFamily(c, desc, innerPrefix, allEntries, picked, pickedTypes); // hint missed
			}
		}
		if (picked.isEmpty()) {
			return 0;
		}
		outDex.getAbsoluteFile().getParentFile().mkdirs();
		DexPool.writeTo(outDex.getAbsolutePath(), new ImmutableDexFile(opcodes, picked));
		return picked.size();
	}

	private static boolean collectFamily(MultiDexContainer<? extends DexBackedDexFile> c, String desc,
			String innerPrefix, List<String> entries, List<ClassDef> picked, java.util.Set<String> pickedTypes)
			throws Exception {
		boolean found = false;
		for (String entry : entries) {
			DexBackedDexFile dex = c.getEntry(entry).getDexFile();
			for (ClassDef cd : dex.getClasses()) {
				String t = cd.getType();
				if ((t.equals(desc) || t.startsWith(innerPrefix)) && pickedTypes.add(t)) {
					picked.add(cd);
				}
				if (t.equals(desc)) {
					found = true;
				}
			}
			if (found && entries.size() == 1) {
				break;
			}
		}
		return found;
	}

	// Cap the reference closure so a class referencing thousands of types can't explode the mini-dex.
	private static final int CLOSURE_CAP = 600;

	/**
	 * Like {@link #extract}, but also pulls in the 1-hop reference closure — every app class the
	 * target's method bodies reference (invoked methods' owners, accessed fields' owners, used types,
	 * super/interfaces). jadx then builds nodes for those classes, so a cursor on a cross-class call
	 * resolves to a real node (needed for go-to-def / find-usages). More classes to parse, but each is
	 * only parsed (not decompiled), so rendering the target stays cheap.
	 */
	public int extractWithClosure(String classDesc, String dexEntryHint, File outDex) throws Exception {
		String innerPrefix = classDesc.substring(0, classDesc.length() - 1) + "$";
		MultiDexContainer<? extends DexBackedDexFile> c = container();
		List<String> allEntries = c.getDexEntryNames();

		// Pass 1: target family (target + inner classes) and the type descriptors they reference.
		List<ClassDef> family = new ArrayList<>();
		java.util.Set<String> pickedTypes = new java.util.HashSet<>(); // dedup across multidex duplicates
		java.util.Set<String> wanted = new java.util.HashSet<>();
		List<String> firstScan = new ArrayList<>();
		if (dexEntryHint != null && allEntries.contains(dexEntryHint)) {
			firstScan.add(dexEntryHint);
		} else {
			firstScan.addAll(allEntries);
		}
		boolean found = false;
		for (String entry : firstScan) {
			DexBackedDexFile dex = c.getEntry(entry).getDexFile();
			for (ClassDef cd : dex.getClasses()) {
				String t = cd.getType();
				if ((t.equals(classDesc) || t.startsWith(innerPrefix)) && pickedTypes.add(t)) {
					family.add(cd);
					collectRefs(cd, wanted);
				}
				if (t.equals(classDesc)) {
					found = true;
				}
			}
			if (found && dexEntryHint != null) {
				break;
			}
		}
		if (!found && dexEntryHint != null) {
			return extractWithClosure(classDesc, null, outDex); // hint missed; full scan
		}
		if (family.isEmpty()) {
			return 0;
		}
		for (ClassDef cd : family) {
			wanted.remove(cd.getType());
		}

		// Pass 2: pull in the referenced app classes (bounded, deduped by type).
		List<ClassDef> picked = new ArrayList<>(family);
		if (!wanted.isEmpty()) {
			int added = 0;
			outer:
			for (String entry : allEntries) {
				DexBackedDexFile dex = c.getEntry(entry).getDexFile();
				for (ClassDef cd : dex.getClasses()) {
					String t = cd.getType();
					if (wanted.contains(t) && pickedTypes.add(t)) {
						picked.add(cd);
						if (++added >= CLOSURE_CAP) {
							break outer;
						}
					}
				}
			}
		}
		outDex.getAbsoluteFile().getParentFile().mkdirs();
		DexPool.writeTo(outDex.getAbsolutePath(), new ImmutableDexFile(opcodes, picked));
		return picked.size();
	}

	// Collect the type descriptors a class's method bodies (and its super/interfaces) reference.
	private static void collectRefs(ClassDef cd, java.util.Set<String> out) {
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
			java.util.Set<String> out) {
		if (ref instanceof com.android.tools.smali.dexlib2.iface.reference.MethodReference) {
			out.add(((com.android.tools.smali.dexlib2.iface.reference.MethodReference) ref).getDefiningClass());
		} else if (ref instanceof com.android.tools.smali.dexlib2.iface.reference.FieldReference) {
			out.add(((com.android.tools.smali.dexlib2.iface.reference.FieldReference) ref).getDefiningClass());
		} else if (ref instanceof com.android.tools.smali.dexlib2.iface.reference.TypeReference) {
			out.add(((com.android.tools.smali.dexlib2.iface.reference.TypeReference) ref).getType());
		}
	}
}
