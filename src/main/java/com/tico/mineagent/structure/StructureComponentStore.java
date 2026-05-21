package com.tico.mineagent.structure;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.server.level.ServerPlayer;

import com.tico.mineagent.project.MineAgentProjectStore;
import com.tico.mineagent.sandbox.SandboxSession;

public final class StructureComponentStore {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

	private StructureComponentStore() {
	}

	public static JsonObject view(ServerPlayer player, SandboxSession sandbox) throws IOException {
		Path path = path(player, sandbox);
		JsonObject object = snapshot(player, sandbox, path);
		object.addProperty("file_exists", Files.isRegularFile(path));
		return object;
	}

	public static JsonObject write(ServerPlayer player, SandboxSession sandbox) throws IOException {
		Path path = path(player, sandbox);
		Files.createDirectories(path.getParent());
		JsonObject object = snapshot(player, sandbox, path);
		Files.writeString(path, GSON.toJson(object), StandardCharsets.UTF_8);
		object.addProperty("file_exists", true);
		return object;
	}

	private static JsonObject snapshot(ServerPlayer player, SandboxSession sandbox, Path path) {
		JsonObject object = sandbox.structures().toJson();
		object.addProperty("player", player.getName().getString());
		object.addProperty("player_uuid", player.getUUID().toString());
		object.addProperty("project_id", sandbox.activeProjectId());
		object.addProperty("dimension", player.level().dimension().location().toString());
		object.addProperty("file_path", path.toAbsolutePath().normalize().toString());
		return object;
	}

	private static Path path(ServerPlayer player, SandboxSession sandbox) throws IOException {
		return MineAgentProjectStore.activeProjectRoot(player, sandbox).resolve("structures.json");
	}
}
