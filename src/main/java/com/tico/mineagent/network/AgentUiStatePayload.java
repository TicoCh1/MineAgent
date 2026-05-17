package com.tico.mineagent.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.tico.mineagent.MineAgent;

public record AgentUiStatePayload(
		boolean openScreen,
		boolean configured,
		String providerId,
		String model,
		String status,
		boolean awaitingApproval,
		int completedSteps,
		String planJson) implements CustomPacketPayload {
	private static final int MAX_TEXT_LENGTH = 2048;
	private static final int MAX_PLAN_LENGTH = 8192;
	public static final ResourceLocation PAYLOAD_ID = ResourceLocation.fromNamespaceAndPath(MineAgent.MOD_ID, "agent_ui_state");
	public static final Type<AgentUiStatePayload> ID = new Type<>(PAYLOAD_ID);
	public static final StreamCodec<RegistryFriendlyByteBuf, AgentUiStatePayload> CODEC = StreamCodec.of(AgentUiStatePayload::write, AgentUiStatePayload::read);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}

	private static void write(RegistryFriendlyByteBuf buffer, AgentUiStatePayload payload) {
		buffer.writeBoolean(payload.openScreen());
		buffer.writeBoolean(payload.configured());
		buffer.writeUtf(payload.providerId(), MAX_TEXT_LENGTH);
		buffer.writeUtf(payload.model(), MAX_TEXT_LENGTH);
		buffer.writeUtf(payload.status(), MAX_TEXT_LENGTH);
		buffer.writeBoolean(payload.awaitingApproval());
		buffer.writeInt(payload.completedSteps());
		buffer.writeUtf(payload.planJson(), MAX_PLAN_LENGTH);
	}

	private static AgentUiStatePayload read(RegistryFriendlyByteBuf buffer) {
		return new AgentUiStatePayload(
				buffer.readBoolean(),
				buffer.readBoolean(),
				buffer.readUtf(MAX_TEXT_LENGTH),
				buffer.readUtf(MAX_TEXT_LENGTH),
				buffer.readUtf(MAX_TEXT_LENGTH),
				buffer.readBoolean(),
				buffer.readInt(),
				buffer.readUtf(MAX_PLAN_LENGTH));
	}
}
