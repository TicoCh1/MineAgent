package com.tico.mineagent.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.tico.mineagent.MineAgent;

public record AgentUiStartPayload(String providerId, String model, String apiKey, String projectId, boolean continueProject, String prompt) implements CustomPacketPayload {
	private static final int MAX_TEXT_LENGTH = 8192;
	public static final ResourceLocation PAYLOAD_ID = ResourceLocation.fromNamespaceAndPath(MineAgent.MOD_ID, "agent_ui_start");
	public static final Type<AgentUiStartPayload> ID = new Type<>(PAYLOAD_ID);
	public static final StreamCodec<RegistryFriendlyByteBuf, AgentUiStartPayload> CODEC = StreamCodec.of(AgentUiStartPayload::write, AgentUiStartPayload::read);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}

	private static void write(RegistryFriendlyByteBuf buffer, AgentUiStartPayload payload) {
		buffer.writeUtf(payload.providerId(), MAX_TEXT_LENGTH);
		buffer.writeUtf(payload.model(), MAX_TEXT_LENGTH);
		buffer.writeUtf(payload.apiKey(), MAX_TEXT_LENGTH);
		buffer.writeUtf(payload.projectId(), MAX_TEXT_LENGTH);
		buffer.writeBoolean(payload.continueProject());
		buffer.writeUtf(payload.prompt(), MAX_TEXT_LENGTH);
	}

	private static AgentUiStartPayload read(RegistryFriendlyByteBuf buffer) {
		return new AgentUiStartPayload(
				buffer.readUtf(MAX_TEXT_LENGTH),
				buffer.readUtf(MAX_TEXT_LENGTH),
				buffer.readUtf(MAX_TEXT_LENGTH),
				buffer.readUtf(MAX_TEXT_LENGTH),
				buffer.readBoolean(),
				buffer.readUtf(MAX_TEXT_LENGTH));
	}
}
