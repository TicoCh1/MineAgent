package com.tico.mineagent.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.tico.mineagent.MineAgent;

public record AgentEditBoundsPayload(
		boolean visible,
		BlockPos min,
		BlockPos max,
		String label) implements CustomPacketPayload {
	public static final ResourceLocation PAYLOAD_ID = ResourceLocation.fromNamespaceAndPath(MineAgent.MOD_ID, "agent_edit_bounds");
	public static final Type<AgentEditBoundsPayload> ID = new Type<>(PAYLOAD_ID);
	public static final StreamCodec<RegistryFriendlyByteBuf, AgentEditBoundsPayload> CODEC = StreamCodec.of(AgentEditBoundsPayload::write, AgentEditBoundsPayload::read);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}

	private static void write(RegistryFriendlyByteBuf buffer, AgentEditBoundsPayload payload) {
		buffer.writeBoolean(payload.visible());
		buffer.writeBlockPos(payload.min());
		buffer.writeBlockPos(payload.max());
		buffer.writeUtf(payload.label());
	}

	private static AgentEditBoundsPayload read(RegistryFriendlyByteBuf buffer) {
		return new AgentEditBoundsPayload(
				buffer.readBoolean(),
				buffer.readBlockPos(),
				buffer.readBlockPos(),
				buffer.readUtf());
	}
}
