package com.tico.mineagent.project;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import com.tico.mineagent.sandbox.SandboxSession;

public final class MineAgentProjectStore {
	public static final String DEFAULT_PROJECT_ID = "default";
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
	private static final int MAX_PROJECT_ID_LENGTH = 64;
	private static final int MAX_PROJECT_TITLE_LENGTH = 120;

	private MineAgentProjectStore() {
	}

	public static String readActiveProjectId(ServerPlayer player) throws IOException {
		Path path = activeProjectPath(player);
		if (!Files.isRegularFile(path)) {
			return DEFAULT_PROJECT_ID;
		}
		return normalizeProjectId(Files.readString(path, StandardCharsets.UTF_8));
	}

	public static JsonObject ensureActiveProject(ServerPlayer player, SandboxSession sandbox) throws IOException {
		String projectId = normalizeProjectId(sandbox.activeProjectId());
		sandbox.setActiveProjectId(projectId);
		Path root = projectRoot(player, projectId);
		ensureProject(player, root, projectId, "");
		writeActiveProject(player, projectId);
		return projectInfo(player, root, projectId);
	}

	public static JsonObject select(ServerPlayer player, SandboxSession sandbox, String rawProjectId, String rawTitle) throws IOException {
		String projectId = normalizeProjectId(rawProjectId);
		Path root = projectRoot(player, projectId);
		ensureProject(player, root, projectId, rawTitle);
		sandbox.setActiveProjectId(projectId);
		writeActiveProject(player, projectId);

		JsonObject object = new JsonObject();
		object.addProperty("selected", true);
		object.add("active_project", projectInfo(player, root, projectId));
		object.add("projects", projects(player));
		return object;
	}

	public static JsonObject index(ServerPlayer player, SandboxSession sandbox) throws IOException {
		ensureActiveProject(player, sandbox);
		JsonObject object = new JsonObject();
		object.addProperty("resource", "projects://index");
		object.addProperty("player", player.getName().getString());
		object.addProperty("player_uuid", player.getUUID().toString());
		object.addProperty("active_project_id", sandbox.activeProjectId());
		object.addProperty("player_root", playerRoot(player).toAbsolutePath().normalize().toString());
		object.addProperty("projects_root", projectsRoot(player).toAbsolutePath().normalize().toString());
		object.add("projects", projects(player));
		return object;
	}

	public static Path activeProjectRoot(ServerPlayer player, SandboxSession sandbox) throws IOException {
		ensureActiveProject(player, sandbox);
		return projectRoot(player, sandbox.activeProjectId());
	}

	public static Path activeDesignRoot(ServerPlayer player, SandboxSession sandbox) throws IOException {
		return activeProjectRoot(player, sandbox).resolve("design");
	}

	public static boolean projectExists(ServerPlayer player, String rawProjectId) {
		String projectId = normalizeProjectId(rawProjectId);
		return Files.isDirectory(projectRoot(player, projectId));
	}

	public static Path playerRootPath(ServerPlayer player) {
		return playerRoot(player);
	}

	public static String normalizeProjectId(String raw) {
		String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
		if (value.isBlank()) {
			value = DEFAULT_PROJECT_ID;
		}
		value = value.replace('\\', '/');
		if (value.contains("/") || value.contains("..")) {
			throw new IllegalArgumentException("Project id must be a single name, not a path.");
		}
		value = value.replaceAll("[^a-z0-9._-]+", "_").replaceAll("_+", "_");
		value = trimProjectId(value);
		if (value.isBlank() || value.equals(".") || value.equals("..")) {
			value = DEFAULT_PROJECT_ID;
		}
		if (!value.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
			throw new IllegalArgumentException("Project id must start with a letter or digit and use only letters, digits, dot, underscore, or hyphen.");
		}
		return value;
	}

	private static JsonArray projects(ServerPlayer player) throws IOException {
		Path root = projectsRoot(player);
		Files.createDirectories(root);
		JsonArray array = new JsonArray();
		try (var stream = Files.list(root)) {
			List<Path> paths = stream
					.filter(Files::isDirectory)
					.sorted(Comparator.comparing(path -> path.getFileName().toString()))
					.toList();
			for (Path path : paths) {
				String id = normalizeProjectId(path.getFileName().toString());
				array.add(projectInfo(player, path, id));
			}
		}
		return array;
	}

	private static JsonObject projectInfo(ServerPlayer player, Path root, String projectId) {
		JsonObject metadata = readMetadata(root);
		JsonObject object = new JsonObject();
		object.addProperty("id", projectId);
		object.addProperty("title", stringOrDefault(metadata, "title", projectId));
		object.addProperty("player_uuid", player.getUUID().toString());
		object.addProperty("root", root.toAbsolutePath().normalize().toString());
		object.addProperty("design_root", root.resolve("design").toAbsolutePath().normalize().toString());
		object.addProperty("structures_path", root.resolve("structures.json").toAbsolutePath().normalize().toString());
		object.addProperty("conversation_state_path", root.resolve("conversation_state.json").toAbsolutePath().normalize().toString());
		object.addProperty("conversation_state_exists", Files.isRegularFile(root.resolve("conversation_state.json")));
		object.addProperty("sandbox_path", root.resolve("sandbox.json").toAbsolutePath().normalize().toString());
		object.addProperty("sandbox_exists", Files.isRegularFile(root.resolve("sandbox.json")));
		object.addProperty("agent_exists", Files.isRegularFile(root.resolve("design").resolve("agent.md")));
		object.addProperty("brief_exists", Files.isRegularFile(root.resolve("design").resolve("brief.md")));
		object.addProperty("feature_count", countFiles(root.resolve("design").resolve("features"), "feature_", ".md"));
		object.addProperty("image_count", countFiles(root.resolve("design").resolve("images"), "", ".png"));
		if (metadata.has("created_at")) {
			object.add("created_at", metadata.get("created_at"));
		}
		if (metadata.has("updated_at")) {
			object.add("updated_at", metadata.get("updated_at"));
		}
		return object;
	}

	private static void ensureProject(ServerPlayer player, Path root, String projectId, String rawTitle) throws IOException {
		Files.createDirectories(root.resolve("design").resolve("features"));
		Files.createDirectories(root.resolve("design").resolve("images"));
		Path metadataPath = root.resolve("project.json");
		JsonObject metadata = readMetadata(root);
		String now = Instant.now().toString();
		if (!metadata.has("created_at")) {
			metadata.addProperty("created_at", now);
		}
		metadata.addProperty("updated_at", now);
		metadata.addProperty("id", projectId);
		metadata.addProperty("title", safeTitle(rawTitle, stringOrDefault(metadata, "title", projectId)));
		metadata.addProperty("player", player.getName().getString());
		metadata.addProperty("player_uuid", player.getUUID().toString());
		metadata.addProperty("schema_version", 1);
		Files.writeString(metadataPath, GSON.toJson(metadata), StandardCharsets.UTF_8);
	}

	private static JsonObject readMetadata(Path root) {
		Path metadataPath = root.resolve("project.json");
		if (!Files.isRegularFile(metadataPath)) {
			return new JsonObject();
		}
		try {
			return JsonParser.parseString(Files.readString(metadataPath, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IOException | RuntimeException exception) {
			return new JsonObject();
		}
	}

	private static String stringOrDefault(JsonObject object, String property, String fallback) {
		if (!object.has(property) || object.get(property).isJsonNull()) {
			return fallback;
		}
		String value = object.get(property).getAsString();
		return value.isBlank() ? fallback : value;
	}

	private static String safeTitle(String raw, String fallback) {
		String value = raw == null || raw.isBlank() ? fallback : raw.trim();
		value = value.replace('\r', ' ').replace('\n', ' ').trim();
		if (value.length() > MAX_PROJECT_TITLE_LENGTH) {
			value = value.substring(0, MAX_PROJECT_TITLE_LENGTH);
		}
		return value.isBlank() ? fallback : value;
	}

	private static int countFiles(Path directory, String prefix, String suffix) {
		if (!Files.isDirectory(directory)) {
			return 0;
		}
		try (var stream = Files.list(directory)) {
			return (int) stream
					.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().startsWith(prefix))
					.filter(path -> path.getFileName().toString().endsWith(suffix))
					.count();
		} catch (IOException exception) {
			return 0;
		}
	}

	private static void writeActiveProject(ServerPlayer player, String projectId) throws IOException {
		Path playerRoot = playerRoot(player);
		Files.createDirectories(playerRoot);
		Files.writeString(activeProjectPath(player), projectId, StandardCharsets.UTF_8);
	}

	private static Path activeProjectPath(ServerPlayer player) {
		return playerRoot(player).resolve("active_project.txt");
	}

	private static Path projectRoot(ServerPlayer player, String projectId) {
		return projectsRoot(player).resolve(normalizeProjectId(projectId));
	}

	private static Path projectsRoot(ServerPlayer player) {
		return playerRoot(player).resolve("projects");
	}

	private static Path playerRoot(ServerPlayer player) {
		return ((ServerLevel) player.level()).getServer().getWorldPath(LevelResource.ROOT)
				.resolve("mineagent")
				.resolve("players")
				.resolve(player.getUUID().toString());
	}

	private static String trimProjectId(String value) {
		while (value.startsWith(".") || value.startsWith("_") || value.startsWith("-")) {
			value = value.substring(1);
		}
		while (value.endsWith(".") || value.endsWith("_") || value.endsWith("-")) {
			value = value.substring(0, value.length() - 1);
		}
		if (value.length() > MAX_PROJECT_ID_LENGTH) {
			value = value.substring(0, MAX_PROJECT_ID_LENGTH);
			while (value.endsWith(".") || value.endsWith("_") || value.endsWith("-")) {
				value = value.substring(0, value.length() - 1);
			}
		}
		return value;
	}
}
