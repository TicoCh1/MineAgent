package com.tico.mineagent.sandbox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import com.tico.mineagent.project.MineAgentProjectStore;

public final class SandboxConfigStore {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
	private static final String GLOBAL_SANDBOX_FILE = "global_sandbox.json";
	private static final String PROJECT_SANDBOX_FILE = "sandbox.json";

	private SandboxConfigStore() {
	}

	public static JsonObject summary(ServerPlayer player, SandboxSession sandbox) throws IOException {
		JsonObject object = new JsonObject();
		object.add("active_session", sessionJson(sandbox));
		object.add("global", readConfig(globalPath(player), "global", ""));
		object.add("project", readConfig(projectPath(player, sandbox), "project", sandbox.activeProjectId()));
		return object;
	}

	public static JsonObject saveGlobal(ServerPlayer player, SandboxSession sandbox, BlockPos min, BlockPos max, SandboxPermissionMode permissionMode, boolean apply) throws IOException {
		JsonObject config = configJson(player, sandbox, "global", "", min, max, permissionMode);
		Path path = globalPath(player);
		Files.createDirectories(path.getParent());
		Files.writeString(path, GSON.toJson(config), StandardCharsets.UTF_8);
		if (apply) {
			applyToSession(sandbox, config);
		}
		return result("global", path, config, apply);
	}

	public static JsonObject saveProject(ServerPlayer player, SandboxSession sandbox, BlockPos min, BlockPos max, SandboxPermissionMode permissionMode, boolean apply) throws IOException {
		Path path = projectPath(player, sandbox);
		JsonObject config = configJson(player, sandbox, "project", sandbox.activeProjectId(), min, max, permissionMode);
		Files.createDirectories(path.getParent());
		Files.writeString(path, GSON.toJson(config), StandardCharsets.UTF_8);
		if (apply) {
			applyToSession(sandbox, config);
		}
		return result("project", path, config, apply);
	}

	public static JsonObject applyProjectOrGlobal(ServerPlayer player, SandboxSession sandbox) throws IOException {
		Path projectPath = projectPath(player, sandbox);
		if (Files.isRegularFile(projectPath)) {
			JsonObject config = readConfig(projectPath, "project", sandbox.activeProjectId());
			applyToSession(sandbox, config);
			return result("project", projectPath, config, true);
		}

		Path globalPath = globalPath(player);
		if (Files.isRegularFile(globalPath)) {
			JsonObject config = readConfig(globalPath, "global", "");
			applyToSession(sandbox, config);
			return result("global", globalPath, config, true);
		}

		JsonObject object = new JsonObject();
		object.addProperty("applied", false);
		object.addProperty("reason", "No project or global MineAgent sandbox config exists.");
		object.add("active_session", sessionJson(sandbox));
		return object;
	}

	public static JsonObject saveCurrent(ServerPlayer player, SandboxSession sandbox, String scope) throws IOException {
		if (!sandbox.hasCompleteBounds()) {
			throw new IllegalStateException("MineAgent sandbox is incomplete; select bounds before saving a sandbox config.");
		}
		SandboxPermissionMode permission = sandbox.permissionMode();
		if ("global".equals(scope)) {
			return saveGlobal(player, sandbox, sandbox.min(), sandbox.max(), permission, false);
		}
		if ("project".equals(scope)) {
			return saveProject(player, sandbox, sandbox.min(), sandbox.max(), permission, false);
		}
		throw new IllegalArgumentException("Sandbox config scope must be global or project.");
	}

	private static JsonObject configJson(ServerPlayer player, SandboxSession sandbox, String scope, String projectId, BlockPos min, BlockPos max, SandboxPermissionMode permissionMode) {
		JsonObject object = new JsonObject();
		object.addProperty("schema_version", 1);
		object.addProperty("updated_at", Instant.now().toString());
		object.addProperty("scope", scope);
		object.addProperty("player", player.getName().getString());
		object.addProperty("player_uuid", player.getUUID().toString());
		object.addProperty("project_id", projectId == null ? "" : projectId);
		object.addProperty("complete", true);
		object.add("min", posJson(min));
		object.add("max", posJson(max));
		object.addProperty("permission_mode", permissionMode == null ? sandbox.permissionMode().id() : permissionMode.id());
		return object;
	}

	private static JsonObject readConfig(Path path, String scope, String projectId) throws IOException {
		JsonObject object;
		if (!Files.isRegularFile(path)) {
			object = new JsonObject();
			object.addProperty("exists", false);
			object.addProperty("scope", scope);
			object.addProperty("project_id", projectId == null ? "" : projectId);
			object.addProperty("path", path.toAbsolutePath().normalize().toString());
			return object;
		}
		try {
			object = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (RuntimeException exception) {
			object = new JsonObject();
			object.addProperty("parse_error", exception.getMessage());
		}
		object.addProperty("exists", true);
		object.addProperty("scope", scope);
		object.addProperty("project_id", projectId == null ? "" : projectId);
		object.addProperty("path", path.toAbsolutePath().normalize().toString());
		return object;
	}

	private static void applyToSession(SandboxSession sandbox, JsonObject config) {
		if (!config.has("min") || !config.has("max")) {
			throw new IllegalArgumentException("Sandbox config is missing min or max coordinates.");
		}
		sandbox.setBounds(pos(config.getAsJsonObject("min")), pos(config.getAsJsonObject("max")));
		String rawMode = config.has("permission_mode") ? config.get("permission_mode").getAsString() : "";
		SandboxPermissionMode mode = SandboxPermissionMode.byId(rawMode);
		if (mode != null) {
			sandbox.setPermissionMode(mode);
		}
	}

	private static JsonObject result(String scope, Path path, JsonObject config, boolean applied) {
		JsonObject object = new JsonObject();
		object.addProperty("scope", scope);
		object.addProperty("path", path.toAbsolutePath().normalize().toString());
		object.addProperty("saved_or_loaded", true);
		object.addProperty("applied", applied);
		object.add("config", config);
		return object;
	}

	private static JsonObject sessionJson(SandboxSession sandbox) {
		JsonObject object = new JsonObject();
		object.addProperty("active_project_id", sandbox.activeProjectId());
		object.addProperty("complete", sandbox.hasCompleteBounds());
		object.addProperty("permission_mode", sandbox.permissionMode().id());
		if (sandbox.hasCompleteBounds()) {
			object.add("min", posJson(sandbox.min()));
			object.add("max", posJson(sandbox.max()));
			object.addProperty("bounds", sandbox.boundsSummary());
		}
		return object;
	}

	private static JsonObject posJson(BlockPos pos) {
		JsonObject object = new JsonObject();
		object.addProperty("x", pos.getX());
		object.addProperty("y", pos.getY());
		object.addProperty("z", pos.getZ());
		return object;
	}

	private static BlockPos pos(JsonObject object) {
		return new BlockPos(
				object.get("x").getAsInt(),
				object.get("y").getAsInt(),
				object.get("z").getAsInt());
	}

	private static Path globalPath(ServerPlayer player) {
		return MineAgentProjectStore.playerRootPath(player).resolve(GLOBAL_SANDBOX_FILE);
	}

	private static Path projectPath(ServerPlayer player, SandboxSession sandbox) throws IOException {
		return MineAgentProjectStore.activeProjectRoot(player, sandbox).resolve(PROJECT_SANDBOX_FILE);
	}
}
