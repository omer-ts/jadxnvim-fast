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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

	/** Result of loading a project: the resolved input file, its code data, and the raw project JSON
	 *  (so UI-state fields — openTabs, searchHistory, treeExpansionsV2, cacheDir — can be passed
	 *  through to the plugin, which owns their jadx-gui format). */
	static final class Loaded {
		File input;
		JadxCodeData codeData = new JadxCodeData();
		JsonObject root = new JsonObject();
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
		Loaded loaded = new Loaded();
		JsonObject root;
		try (Reader r = Files.newBufferedReader(jadxFile.toPath(), StandardCharsets.UTF_8)) {
			JsonElement parsed = JsonParser.parseReader(r);
			root = (parsed != null && parsed.isJsonObject()) ? parsed.getAsJsonObject() : new JsonObject();
		}
		loaded.root = root;
		if (root.has("codeData") && root.get("codeData").isJsonObject()) {
			loaded.codeData = gson().fromJson(root.get("codeData"), JadxCodeData.class);
		}
		if (loaded.codeData == null) {
			loaded.codeData = new JadxCodeData();
		}
		Path base = jadxFile.getAbsoluteFile().getParentFile().toPath();
		if (root.has("files") && root.get("files").isJsonArray()) {
			JsonArray arr = root.getAsJsonArray("files");
			if (arr.size() > 0) {
				loaded.input = base.resolve(arr.get(0).getAsString()).normalize().toFile();
			}
		}
		return loaded;
	}

	static void save(File jadxFile, File input, JadxCodeData codeData) throws IOException {
		save(jadxFile, input, codeData, null);
	}

	/**
	 * Save the project. {@code extras} (if non-null) supplies UI-state fields the plugin manages —
	 * {@code openTabs}, {@code searchHistory}, {@code treeExpansionsV2}, {@code cacheDir} — which are
	 * merged verbatim into the project JSON (the plugin builds them in jadx-gui's format). Any other
	 * existing fields are preserved.
	 */
	static void save(File jadxFile, File input, JadxCodeData codeData, JsonObject extras) throws IOException {
		Gson gson = gson();
		Path base = jadxFile.getAbsoluteFile().getParentFile().toPath();
		Path inputPath = input.getAbsoluteFile().toPath();
		String rel;
		try {
			rel = base.relativize(inputPath).toString();
		} catch (IllegalArgumentException e) {
			rel = inputPath.toString();
		}

		// Preserve any fields we don't manage (openTabs, treeExpansions, ...) so re-saving a
		// jadx-gui project keeps its state; only replace projectVersion/files/codeData.
		JsonObject root = null;
		if (jadxFile.exists()) {
			try (Reader r = Files.newBufferedReader(jadxFile.toPath(), StandardCharsets.UTF_8)) {
				JsonElement parsed = JsonParser.parseReader(r);
				if (parsed != null && parsed.isJsonObject()) {
					root = parsed.getAsJsonObject();
				}
			} catch (Exception ignore) {
				root = null;
			}
		}
		if (root == null) {
			root = new JsonObject();
		}
		if (!root.has("projectVersion")) {
			root.addProperty("projectVersion", PROJECT_VERSION);
		}
		JsonArray files = new JsonArray();
		files.add(rel.replace('\\', '/'));
		root.add("files", files);
		root.add("codeData", gson.toJsonTree(codeData));
		if (extras != null) {
			for (java.util.Map.Entry<String, JsonElement> e : extras.entrySet()) {
				if (e.getValue() == null || e.getValue().isJsonNull()) {
					root.remove(e.getKey());
				} else {
					root.add(e.getKey(), e.getValue());
				}
			}
		}

		File parent = jadxFile.getAbsoluteFile().getParentFile();
		if (parent != null) {
			parent.mkdirs();
		}
		try (Writer w = Files.newBufferedWriter(jadxFile.toPath(), StandardCharsets.UTF_8)) {
			gson.toJson(root, w);
		}
	}

	private ProjectIO() {
	}
}
