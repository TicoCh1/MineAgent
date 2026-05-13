package com.tico.mineagent.sandbox;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

public final class SandboxSessions {
	private static final Map<UUID, SandboxSession> SESSIONS = new ConcurrentHashMap<>();

	private SandboxSessions() {
	}

	public static SandboxSession get(ServerPlayer player) {
		return SESSIONS.computeIfAbsent(player.getUUID(), ignored -> new SandboxSession());
	}

	public static boolean effectiveToolEnabled(ServerPlayer player) {
		SandboxSession session = get(player);
		return session.toolEnabled() || isCreativeMode(player);
	}

	public static void sync(ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, com.tico.mineagent.network.SandboxStatePayload.ID)) {
			ServerPlayNetworking.send(player, get(player).toPayload(effectiveToolEnabled(player)));
		}
	}

	private static boolean isCreativeMode(ServerPlayer player) {
		return player.gameMode.getGameModeForPlayer() == GameType.CREATIVE;
	}
}
