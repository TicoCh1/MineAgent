package com.tico.mineagent.sandbox;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import com.tico.mineagent.project.MineAgentProjectStore;

public final class SandboxSessions {
	private static final Map<UUID, SandboxSession> SESSIONS = new ConcurrentHashMap<>();

	private SandboxSessions() {
	}

	public static SandboxSession get(ServerPlayer player) {
		return SESSIONS.computeIfAbsent(player.getUUID(), ignored -> {
			SandboxSession session = new SandboxSession();
			try {
				session.setActiveProjectId(MineAgentProjectStore.readActiveProjectId(player));
				MineAgentProjectStore.ensureActiveProject(player, session);
			} catch (Exception ignoredException) {
				session.setActiveProjectId(MineAgentProjectStore.DEFAULT_PROJECT_ID);
			}
			return session;
		});
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
