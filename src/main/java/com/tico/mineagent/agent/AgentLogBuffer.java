package com.tico.mineagent.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;

public final class AgentLogBuffer {
	private static final int MAX_ENTRIES = 240;
	private static final Map<UUID, Deque<Entry>> LOGS = new ConcurrentHashMap<>();

	private AgentLogBuffer() {
	}

	public static void add(ServerPlayer player, String level, String message) {
		add(player.getUUID(), level, message, "");
	}

	public static void add(ServerPlayer player, String level, String message, String detail) {
		add(player.getUUID(), level, message, detail);
	}

	public static void add(UUID playerId, String level, String message, String detail) {
		if (playerId == null || message == null || message.isBlank()) {
			return;
		}
		Deque<Entry> entries = LOGS.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
		synchronized (entries) {
			entries.addLast(new Entry(Instant.now().toString(), safe(level, "info"), message, detail == null ? "" : detail));
			while (entries.size() > MAX_ENTRIES) {
				entries.removeFirst();
			}
		}
	}

	public static JsonArray snapshot(ServerPlayer player) {
		Deque<Entry> entries = LOGS.computeIfAbsent(player.getUUID(), ignored -> new ArrayDeque<>());
		JsonArray array = new JsonArray();
		synchronized (entries) {
			for (Entry entry : entries) {
				JsonObject object = new JsonObject();
				object.addProperty("time", entry.time());
				object.addProperty("level", entry.level());
				object.addProperty("message", entry.message());
				object.addProperty("detail", entry.detail());
				array.add(object);
			}
		}
		return array;
	}

	private static String safe(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private record Entry(String time, String level, String message, String detail) {
	}
}
