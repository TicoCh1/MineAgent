package com.tico.mineagent.agent;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import net.minecraft.server.level.ServerPlayer;

public final class AgentPlanStates {
	private static final ConcurrentMap<UUID, AgentPlanSnapshot> STATES = new ConcurrentHashMap<>();

	private AgentPlanStates() {
	}

	public static AgentPlanSnapshot snapshot(ServerPlayer player) {
		if (player == null) {
			return AgentPlanSnapshot.empty();
		}
		return STATES.getOrDefault(player.getUUID(), AgentPlanSnapshot.empty());
	}

	public static AgentPlanSnapshot snapshot(UUID playerId) {
		return STATES.getOrDefault(playerId, AgentPlanSnapshot.empty());
	}

	public static AgentPlanSnapshot update(ServerPlayer player, String explanation, List<AgentPlanItem> items) {
		AgentPlanSnapshot snapshot = new AgentPlanSnapshot(explanation, items);
		STATES.put(player.getUUID(), snapshot);
		return snapshot;
	}

	public static void clear(ServerPlayer player) {
		if (player != null) {
			STATES.remove(player.getUUID());
		}
	}

	public static void clear(UUID playerId) {
		if (playerId != null) {
			STATES.remove(playerId);
		}
	}
}
