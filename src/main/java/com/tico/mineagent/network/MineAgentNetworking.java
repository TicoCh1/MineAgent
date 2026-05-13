package com.tico.mineagent.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class MineAgentNetworking {
	private MineAgentNetworking() {
	}

	public static void registerPayloads() {
		PayloadTypeRegistry.playS2C().register(SandboxStatePayload.ID, SandboxStatePayload.CODEC);
	}
}
