package jadxnvim.daemon;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;

import jadx.api.data.ICodeComment;
import jadx.api.data.ICodeRename;
import jadx.api.data.IJavaCodeRef;
import jadx.api.data.IJavaNodeRef;
import jadx.api.data.impl.JadxCodeComment;
import jadx.api.data.impl.JadxCodeData;
import jadx.api.data.impl.JadxCodeRef;
import jadx.api.data.impl.JadxCodeRename;
import jadx.api.data.impl.JadxNodeRef;
import jadx.core.utils.GsonUtils;

/**
 * Reads and writes jadx-gui's {@code .jadx} project file so that projects round-trip between
 * jadxnvim and jadx-gui with renames/comments intact.
 *
 * <p>We deliberately do not depend on the {@code jadx-gui} module (it pulls in Swing). Instead we
 * mirror the on-disk shape of {@code ProjectData} ({@code projectVersion}, {@code files},
 * {@code codeData}) and reuse jadx-core's {@code GsonUtils} + the same interface-replace type
 * adapters jadx-gui uses, so the serialized {@code codeData} is byte-compatible. Fields we don't
 * manage (open tabs, tree expansions, ...) are simply omitted and default when jadx-gui loads.
 */
final class ProjectIO {

	static final int PROJECT_VERSION = 2;

	/** Minimal mirror of jadx-gui's ProjectData (only the fields we read/write). */
	static final class ProjectFile {
		int projectVersion = PROJECT_VERSION;
		List<String> files = new ArrayList<>();
		JadxCodeData codeData = new JadxCodeData();
	}

	/** Result of loading a project: the resolved input file and its code data. */
	static final class Loaded {
		File input;
		JadxCodeData codeData = new JadxCodeData();
	}

	private static Gson gson() {
		return GsonUtils.defaultGsonBuilder()
				.registerTypeAdapter(ICodeComment.class, GsonUtils.interfaceReplace(JadxCodeComment.class))
				.registerTypeAdapter(ICodeRename.class, GsonUtils.interfaceReplace(JadxCodeRename.class))
				.registerTypeAdapter(IJavaNodeRef.class, GsonUtils.interfaceReplace(JadxNodeRef.class))
				.registerTypeAdapter(IJavaCodeRef.class, GsonUtils.interfaceReplace(JadxCodeRef.class))
				.create();
	}

	static Loaded load(File jadxFile) throws IOException {
		try (Reader r = Files.newBufferedReader(jadxFile.toPath(), StandardCharsets.UTF_8)) {
			ProjectFile pf = gson().fromJson(r, ProjectFile.class);
			Loaded loaded = new Loaded();
			if (pf != null && pf.codeData != null) {
				loaded.codeData = pf.codeData;
			}
			Path base = jadxFile.getAbsoluteFile().getParentFile().toPath();
			if (pf != null && pf.files != null && !pf.files.isEmpty()) {
				loaded.input = base.resolve(pf.files.get(0)).normalize().toFile();
			}
			return loaded;
		}
	}

	static void save(File jadxFile, File input, JadxCodeData codeData) throws IOException {
		ProjectFile pf = new ProjectFile();
		pf.codeData = codeData;
		Path base = jadxFile.getAbsoluteFile().getParentFile().toPath();
		Path inputPath = input.getAbsoluteFile().toPath();
		String rel;
		try {
			rel = base.relativize(inputPath).toString();
		} catch (IllegalArgumentException e) {
			rel = inputPath.toString();
		}
		pf.files.add(rel.replace('\\', '/'));
		File parent = jadxFile.getAbsoluteFile().getParentFile();
		if (parent != null) {
			parent.mkdirs();
		}
		try (Writer w = Files.newBufferedWriter(jadxFile.toPath(), StandardCharsets.UTF_8)) {
			gson().toJson(pf, w);
		}
	}

	private ProjectIO() {
	}
}
