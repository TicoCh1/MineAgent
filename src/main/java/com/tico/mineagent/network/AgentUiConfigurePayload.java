package com.tico.mineagent.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.tico.mineagent.MineAgent;

public record AgentUiConfigurePayload(String providerId, String model, String apiKey) implements CustomPacketPayload {
	private static final int MAX_TEXT_LENGTH = 8192;
	public static final ResourceLocation PAYLOAD_ID = ResourceLocation.fromNamespaceAndPath(MineAgent.MOD_ID, "agent_ui_configure");
	public static final Type<AgentUiConfigurePayload> ID = new Type<>(PAYLOAD_ID);
	public static final StreamCodec<RegistryFriendlyByteBuf, AgentUiConfigurePayload> CODEC = StreamCodec.of(AgentUiConfigurePayload::write, AgentUiConfigurePayload::read);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}

	private static void write(RegistryFriendlyByteBuf buffer, AgentUiConfigurePayload payload) {
		buffer.writeUtf(payload.providerId(), MAX_TEXT_LENGTH);
		buffer.writeUtf(payload.model(), MAX_TEXT_LENGTH);
		buffer.writeUtf(payload.apiKey(), MAX_TEXT_LENGTH);
	}

	private static AgentUiConfigurePayload read(RegistryFriendlyByteBuf buffer) {
		return new AgentUiConfigurePayload(
				buffer.readUtf(MAX_TEXT_LENGTH),
				buffer.readUtf(MAX_TEXT_LENGTH),
				buffer.readUtf(MAX_TEXT_LENGTH));
	}
}
