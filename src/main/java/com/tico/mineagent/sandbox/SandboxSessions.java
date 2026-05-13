package com.tico.mineagent.sandbox;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public final class SandboxSessions {
	private static final Map<UUID, SandboxSession> SESSIONS = new ConcurrentHashMap<>();

	private SandboxSessions() {
	}

	public static SandboxSession get(ServerPlayer player) {
		return SESSIONS.computeIfAbsent(player.getUUID(), ignored -> new SandboxSession());
	}

	public static void sync(ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, com.tico.mineagent.network.SandboxStatePayload.ID)) {
			ServerPlayNetworking.send(player, get(player).toPayload());
		}
	}
}
