package com.tico.mineagent.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

import com.tico.mineagent.MineAgent;

public final class AgentRunLogger implements AutoCloseable {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
	private static final int MAX_STRING_LENGTH = 200_000;
	private final String runId;
	private final Path path;
	private final BufferedWriter writer;
	private boolean closed;

	private AgentRunLogger(String runId, Path path, BufferedWriter writer) {
		this.runId = runId;
		this.path = path;
		this.writer = writer;
	}

	public static AgentRunLogger create(ServerPlayer player, AgentCredentials credentials, String prompt) throws IOException {
		String runId = UUID.randomUUID().toString();
		String playerName = player.getName().getString();
		Path directory = FabricLoader.getInstance().getGameDir().resolve("mineagent-logs");
		Files.createDirectories(directory);
		String fileName = FILE_TIME.format(Instant.now()) + "-" + safeFilePart(playerName) + "-" + runId.substring(0, 8) + ".jsonl";
		Path path = directory.resolve(fileName);
		AgentRunLogger logger = new AgentRunLogger(runId, path, Files.newBufferedWriter(path, StandardCharsets.UTF_8));

		JsonObject data = new JsonObject();
		data.addProperty("player", playerName);
		data.addProperty("player_uuid", player.getUUID().toString());
		data.addProperty("provider", credentials.provider().id());
		data.addProperty("model", credentials.model());
		data.addProperty("prompt", prompt);
		data.addProperty("log_path", path.toAbsolutePath().toString());
		logger.event("run_start", data);
		return logger;
	}

	public String runId() {
		return runId;
	}

	public Path path() {
		return path;
	}

	public void event(String type) {
		event(type, new JsonObject());
	}

	public synchronized void event(String type, JsonObject data) {
		if (closed) {
			return;
		}

		JsonObject line = new JsonObject();
		line.addProperty("time", Instant.now().toString());
		line.addProperty("run_id", runId);
		line.addProperty("thread", Thread.currentThread().getName());
		line.addProperty("type", type);
		line.add("data", safeCopy(data));
		try {
			writer.write(GSON.toJson(line));
			writer.newLine();
			writer.flush();
		} catch (IOException exception) {
			MineAgent.LOGGER.warn("Failed to write MineAgent run log {}", path, exception);
		}
	}

	public void providerRequest(String provider, JsonObject body) {
		JsonObject data = new JsonObject();
		data.addProperty("provider", provider);
		data.add("body", body);
		event("provider_request", data);
	}

	public void providerHttpResponse(String provider, int statusCode, JsonObject body) {
		JsonObject data = new JsonObject();
		data.addProperty("provider", provider);
		data.addProperty("status_code", statusCode);
		data.add("body", body);
		event("provider_http_response", data);
	}

	public void finish(String status, String message) {
		JsonObject data = new JsonObject();
		data.addProperty("status", status);
		data.addProperty("message", message == null ? "" : message);
		event("run_finish", data);
	}

	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		try {
			writer.close();
		} catch (IOException exception) {
			MineAgent.LOGGER.warn("Failed to close MineAgent run log {}", path, exception);
		}
	}

	private static JsonElement safeCopy(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return com.google.gson.JsonNull.INSTANCE;
		}
		if (element.isJsonObject()) {
			JsonObject source = element.getAsJsonObject();
			JsonObject copy = new JsonObject();
			for (String key : source.keySet()) {
				copy.add(key, safeCopy(source.get(key)));
			}
			return copy;
		}
		if (element.isJsonArray()) {
			JsonArray source = element.getAsJsonArray();
			JsonArray copy = new JsonArray();
			for (JsonElement item : source) {
				copy.add(safeCopy(item));
			}
			return copy;
		}
		if (element.isJsonPrimitive()) {
			JsonPrimitive primitive = element.getAsJsonPrimitive();
			if (primitive.isString()) {
				String value = primitive.getAsString();
				if (value.length() > MAX_STRING_LENGTH) {
					value = value.substring(0, MAX_STRING_LENGTH) + "\n... truncated by MineAgent backend log ...";
				}
				return new JsonPrimitive(value);
			}
		}
		return element.deepCopy();
	}

	private static String safeFilePart(String value) {
		String safe = value.replaceAll("[^A-Za-z0-9._-]", "_");
		return safe.isBlank() ? "player" : safe;
	}
}
