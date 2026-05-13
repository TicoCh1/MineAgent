package com.tico.mineagent.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.tico.mineagent.MineAgent;

public record AgentUiContinuePayload() implements CustomPacketPayload {
	public static final ResourceLocation PAYLOAD_ID = ResourceLocation.fromNamespaceAndPath(MineAgent.MOD_ID, "agent_ui_continue");
	public static final Type<AgentUiContinuePayload> ID = new Type<>(PAYLOAD_ID);
	public static final StreamCodec<RegistryFriendlyByteBuf, AgentUiContinuePayload> CODEC = StreamCodec.of(AgentUiContinuePayload::write, AgentUiContinuePayload::read);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}

	private static void write(RegistryFriendlyByteBuf buffer, AgentUiContinuePayload payload) {
	}

	private static AgentUiContinuePayload read(RegistryFriendlyByteBuf buffer) {
		return new AgentUiContinuePayload();
	}
}
