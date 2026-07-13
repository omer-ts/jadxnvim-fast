package jadxnvim.daemon;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.MultiDexContainer;
import com.android.tools.smali.dexlib2.iface.instruction.DualReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.iface.reference.TypeReference;

/**
 * Builds the {@link Db} index directly from an APK's dex tables using dexlib2 — <em>without</em>
 * decompiling. dexlib2's {@code DexBackedDexFile} is memory-mapped and lazy, so a 700MB multi-dex
 * APK is indexed in seconds at a few hundred MB: the complete class/method/field/string inventory
 * plus a bytecode-precision cross-reference graph (the data that powers browse, symbol/string search,
 * go-to-definition and find-references). jadx is never touched here.
 */
public final class DexIndexer {

	/** Progress sink (percent 0-100 as classes are processed). */
	public interface Progress {
		void update(long classesDone, int percent);
	}

	private final Db db;
	private final Progress progress;

	// Commit the write transaction roughly every this many row inserts to bound WAL growth on huge
	// APKs while keeping the build fast.
	private static final int COMMIT_EVERY = 200_000;

	public DexIndexer(Db db, Progress progress) {
		this.db = db;
		this.progress = progress == null ? (c, p) -> {} : progress;
	}

	/** Content signature of the input, used to decide whether a cached DB can be reused. */
	public static String signature(File input) {
		return input.length() + ":" + input.lastModified() + ":v" + Db.SCHEMA_VERSION;
	}

	/**
	 * Build the full index for {@code input} into {@code db}. Assumes a fresh (empty) schema.
	 */
	public void build(File input) throws Exception {
		db.createSchema();
		db.begin();

		MultiDexContainer<? extends DexBackedDexFile> container =
				DexFileFactory.loadDexContainer(input, Opcodes.getDefault());
		List<String> entries = container.getDexEntryNames();

		// Rough class total for progress (sum across dex entries). Cheap: getClasses() is lazy on size.
		long totalClasses = 0;
		for (String name : entries) {
			DexBackedDexFile dex = container.getEntry(name).getDexFile();
			totalClasses += dex.getClasses().size();
		}
		if (totalClasses == 0) {
			totalClasses = 1;
		}

		long classId = 0;
		long methodId = 0;
		long fieldId = 0;
		long symbolId = 0;
		long strUseId = 0;
		long pending = 0;
		long classesDone = 0;
		int lastPct = -1;

		try (PreparedStatement insClass = db.connection().prepareStatement(
					"INSERT INTO classes(id,desc,fqn,pkg,name,access,super_desc,source_file,dex) "
							+ "VALUES(?,?,?,?,?,?,?,?,?)");
				PreparedStatement insMethod = db.connection().prepareStatement(
					"INSERT INTO methods(id,class_id,name,proto,access,idx) VALUES(?,?,?,?,?,?)");
				PreparedStatement insField = db.connection().prepareStatement(
					"INSERT INTO fields(id,class_id,name,type,access,idx) VALUES(?,?,?,?,?,?)");
				PreparedStatement insXref = db.connection().prepareStatement(
					"INSERT INTO xrefs(target,kind,src_class_id) VALUES(?,?,?)");
				PreparedStatement insSymbol = db.connection().prepareStatement(
					"INSERT INTO symbols(id,kind,name,fqn,alias,class_id,member_idx) VALUES(?,?,?,?,?,?,?)");
				PreparedStatement insSymFts = db.connection().prepareStatement(
					"INSERT INTO sym_fts(rowid,text) VALUES(?,?)");
				PreparedStatement insStrUse = db.connection().prepareStatement(
					"INSERT INTO str_use(id,class_id,value) VALUES(?,?,?)");
				PreparedStatement insStrUseFts = db.connection().prepareStatement(
					"INSERT INTO str_use_fts(rowid,value) VALUES(?,?)")) {

			for (String entryName : entries) {
				DexBackedDexFile dex = container.getEntry(entryName).getDexFile();

				for (ClassDef cls : dex.getClasses()) {
					classId++;
					String desc = cls.getType();
					String fqn = descToFqn(desc);
					int lastSlash = fqn.lastIndexOf('.');
					String pkg = lastSlash >= 0 ? fqn.substring(0, lastSlash) : "";
					String name = lastSlash >= 0 ? fqn.substring(lastSlash + 1) : fqn;

					insClass.setLong(1, classId);
					insClass.setString(2, desc);
					insClass.setString(3, fqn);
					insClass.setString(4, pkg);
					insClass.setString(5, name);
					insClass.setInt(6, cls.getAccessFlags());
					insClass.setString(7, cls.getSuperclass());
					insClass.setString(8, cls.getSourceFile());
					insClass.setString(9, entryName);
					insClass.executeUpdate();

					// jadx-rendered name (for classes jadx renames because the raw name is an invalid Java
					// identifier), plus the combined search text so the class is findable by its original
					// name, a qualified/partial path, OR its jadx name.
					String jadxSimple = Names.jadxSimpleName(name);
					String aliasFqn = jadxSimple.equals(name) ? null
							: (pkg.isEmpty() ? jadxSimple : pkg + "." + jadxSimple);
					StringBuilder clsText = new StringBuilder();
					for (String v : Names.searchVariants(name)) {
						clsText.append(v).append(' ');
					}
					clsText.append(fqn);
					if (aliasFqn != null) {
						clsText.append(' ').append(aliasFqn);
					}

					symbolId++;
					addSymbol(insSymbol, insSymFts, symbolId, Db.KIND_CLASS, name, fqn, aliasFqn,
							clsText.toString(), classId, null);

					int fIdx = 0;
					for (Field f : cls.getFields()) {
						fieldId++;
						insField.setLong(1, fieldId);
						insField.setLong(2, classId);
						insField.setString(3, f.getName());
						insField.setString(4, f.getType());
						insField.setInt(5, f.getAccessFlags());
						insField.setInt(6, fIdx++);
						insField.addBatch();
						symbolId++;
						addSymbol(insSymbol, insSymFts, symbolId, Db.KIND_FIELD, f.getName(),
								fqn + "." + f.getName(), null, f.getName(), classId, fIdx - 1);
						pending++;
					}
					insField.executeBatch();

					java.util.HashSet<String> classStrings = new java.util.HashSet<>();
					// Deduped xref targets for this class: one row per (class, target), not per call site.
					java.util.HashMap<String, Integer> classRefs = new java.util.HashMap<>();
					int mIdx = 0;
					for (Method m : cls.getMethods()) {
						methodId++;
						String proto = proto(m.getParameterTypes(), m.getReturnType());
						insMethod.setLong(1, methodId);
						insMethod.setLong(2, classId);
						insMethod.setString(3, m.getName());
						insMethod.setString(4, proto);
						insMethod.setInt(5, m.getAccessFlags());
						insMethod.setInt(6, mIdx);
						insMethod.addBatch();
						symbolId++;
						addSymbol(insSymbol, insSymFts, symbolId, Db.KIND_METHOD, m.getName(),
								fqn + "." + m.getName() + "()", null, m.getName(), classId, mIdx);
						mIdx++;
						pending++;

						// Cross-references from this method's body (collected + deduped per class).
						MethodImplementation impl = m.getImplementation();
						if (impl != null) {
							for (Instruction insn : impl.getInstructions()) {
								collectRefs(insn, classRefs);
								String s = stringOf(insn);
								if (s != null && s.length() <= 512) {
									classStrings.add(s);
								}
							}
						}
					}
					insMethod.executeBatch();

					// One xref row per (class, target).
					for (Map.Entry<String, Integer> e : classRefs.entrySet()) {
						insXref.setString(1, e.getKey());
						insXref.setInt(2, e.getValue());
						insXref.setLong(3, classId);
						insXref.addBatch();
						pending++;
					}
					insXref.executeBatch();

					// String-literal uses for this class (deduped) → content search.
					for (String s : classStrings) {
						strUseId++;
						insStrUse.setLong(1, strUseId);
						insStrUse.setLong(2, classId);
						insStrUse.setString(3, s);
						insStrUse.addBatch();
						insStrUseFts.setLong(1, strUseId);
						insStrUseFts.setString(2, s);
						insStrUseFts.addBatch();
						pending++;
					}
					insStrUse.executeBatch();
					insStrUseFts.executeBatch();

					classesDone++;
					if (pending >= COMMIT_EVERY) {
						// insXref/insStrUse batches are already flushed per class; only the symbol batches
						// span classes, so flush those before committing.
						insSymbol.executeBatch();
						insSymFts.executeBatch();
						db.commit();
						db.begin();
						pending = 0;
					}
					int pct = (int) (classesDone * 100 / totalClasses);
					if (pct != lastPct) {
						lastPct = pct;
						progress.update(classesDone, pct);
					}
				}
				insSymbol.executeBatch();
				insSymFts.executeBatch();
			}
			insSymbol.executeBatch();
			insSymFts.executeBatch();
		}

		db.commit();

		db.createIndexes();
		db.setMeta("schema_version", Integer.toString(Db.SCHEMA_VERSION));
		db.setMeta("input_signature", signature(input));
		db.setMeta("classes", Long.toString(classId));
		db.setMeta("methods", Long.toString(methodId));
		db.setMeta("fields", Long.toString(fieldId));
		db.setMeta("str_uses", Long.toString(strUseId));
		db.setMeta("complete", "1");
		db.analyze();
		progress.update(classesDone, 100);
	}

	private static void addSymbol(PreparedStatement insSymbol, PreparedStatement insSymFts, long id,
			int kind, String name, String fqn, String alias, String ftsText, long classId, Integer memberIdx)
			throws SQLException {
		insSymbol.setLong(1, id);
		insSymbol.setInt(2, kind);
		insSymbol.setString(3, name);
		insSymbol.setString(4, fqn);
		insSymbol.setString(5, alias);
		insSymbol.setLong(6, classId);
		if (memberIdx == null) {
			insSymbol.setNull(7, java.sql.Types.INTEGER);
		} else {
			insSymbol.setInt(7, memberIdx);
		}
		insSymbol.addBatch();
		insSymFts.setLong(1, id);
		insSymFts.setString(2, ftsText);
		insSymFts.addBatch();
	}

	// Collect the xref target keys an instruction references into the per-class dedup map (target -> kind).
	private static void collectRefs(Instruction insn, Map<String, Integer> out) {
		if (insn instanceof ReferenceInstruction) {
			collectRef(((ReferenceInstruction) insn).getReference(), out);
		}
		if (insn instanceof DualReferenceInstruction) {
			collectRef(((DualReferenceInstruction) insn).getReference2(), out);
		}
	}

	// The string literal an instruction loads (const-string), or null.
	private static String stringOf(Instruction insn) {
		if (insn instanceof ReferenceInstruction) {
			Reference ref = ((ReferenceInstruction) insn).getReference();
			if (ref instanceof StringReference) {
				return ((StringReference) ref).getString();
			}
		}
		return null;
	}

	private static void collectRef(Reference ref, Map<String, Integer> out) {
		String target;
		int kind;
		if (ref instanceof MethodReference) {
			MethodReference mr = (MethodReference) ref;
			target = methodKey(mr.getDefiningClass(), mr.getName(), mr.getParameterTypes(), mr.getReturnType());
			kind = Db.REF_METHOD;
		} else if (ref instanceof FieldReference) {
			FieldReference fr = (FieldReference) ref;
			target = fieldKey(fr.getDefiningClass(), fr.getName(), fr.getType());
			kind = Db.REF_FIELD;
		} else if (ref instanceof TypeReference) {
			target = ((TypeReference) ref).getType();
			kind = Db.REF_TYPE;
		} else {
			// StringReference and others are not xref targets here (strings are searched separately).
			return;
		}
		out.putIfAbsent(target, kind);
	}

	// --- key builders (must match on both the definition and reference sides) -----------------

	/** {@code Lcom/example/Foo;->bar(I)V} style key for a method target. */
	public static String methodKey(String definingClass, String name, List<? extends CharSequence> params,
			String ret) {
		StringBuilder sb = new StringBuilder(definingClass).append("->").append(name).append('(');
		if (params != null) {
			for (CharSequence p : params) {
				sb.append(p);
			}
		}
		return sb.append(')').append(ret == null ? "" : ret).toString();
	}

	/** {@code Lcom/example/Foo;->bar:I} style key for a field target. */
	public static String fieldKey(String definingClass, String name, String type) {
		return definingClass + "->" + name + ":" + type;
	}

	/** {@code Lcom/example/Foo;} → {@code com.example.Foo}. */
	public static String descToFqn(String desc) {
		if (desc == null || desc.isEmpty()) {
			return desc;
		}
		if (desc.charAt(0) == 'L' && desc.charAt(desc.length() - 1) == ';') {
			return desc.substring(1, desc.length() - 1).replace('/', '.');
		}
		return desc.replace('/', '.');
	}

	private static String proto(List<? extends CharSequence> params, String ret) {
		StringBuilder sb = new StringBuilder("(");
		if (params != null) {
			for (CharSequence p : params) {
				sb.append(p);
			}
		}
		return sb.append(')').append(ret == null ? "" : ret).toString();
	}
}
