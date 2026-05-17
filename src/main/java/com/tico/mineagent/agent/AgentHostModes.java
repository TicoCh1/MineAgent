package com.tico.mineagent.agent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;

public final class AgentHostModes {
	private static final Map<UUID, AgentHostMode> HOST_MODES = new ConcurrentHashMap<>();

	private AgentHostModes() {
	}

	public static AgentHostMode get(ServerPlayer player) {
		return HOST_MODES.getOrDefault(player.getUUID(), AgentHostMode.INTERNAL);
	}

	public static void set(ServerPlayer player, AgentHostMode mode) {
		if (mode == AgentHostMode.INTERNAL) {
			HOST_MODES.remove(player.getUUID());
			return;
		}
		HOST_MODES.put(player.getUUID(), mode);
	}
}
