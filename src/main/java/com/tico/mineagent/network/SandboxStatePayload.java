package com.tico.mineagent.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.tico.mineagent.MineAgent;

public record SandboxStatePayload(
		boolean toolEnabled,
		String selectorId,
		boolean complete,
		BlockPos min,
		BlockPos max,
		boolean hasPrimary,
		BlockPos primary,
		boolean hasSecondary,
		BlockPos secondary) implements CustomPacketPayload {
	public static final ResourceLocation PAYLOAD_ID = ResourceLocation.fromNamespaceAndPath(MineAgent.MOD_ID, "sandbox_state");
	public static final Type<SandboxStatePayload> ID = new Type<>(PAYLOAD_ID);
	public static final StreamCodec<RegistryFriendlyByteBuf, SandboxStatePayload> CODEC = StreamCodec.of(SandboxStatePayload::write, SandboxStatePayload::read);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}

	private static void write(RegistryFriendlyByteBuf buffer, SandboxStatePayload payload) {
		buffer.writeBoolean(payload.toolEnabled());
		buffer.writeUtf(payload.selectorId());
		buffer.writeBoolean(payload.complete());
		buffer.writeBlockPos(payload.min());
		buffer.writeBlockPos(payload.max());
		buffer.writeBoolean(payload.hasPrimary());
		buffer.writeBlockPos(payload.primary());
		buffer.writeBoolean(payload.hasSecondary());
		buffer.writeBlockPos(payload.secondary());
	}

	private static SandboxStatePayload read(RegistryFriendlyByteBuf buffer) {
		return new SandboxStatePayload(
				buffer.readBoolean(),
				buffer.readUtf(),
				buffer.readBoolean(),
				buffer.readBlockPos(),
				buffer.readBlockPos(),
				buffer.readBoolean(),
				buffer.readBlockPos(),
				buffer.readBoolean(),
				buffer.readBlockPos());
	}
}
