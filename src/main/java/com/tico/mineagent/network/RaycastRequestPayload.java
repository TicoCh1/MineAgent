package com.tico.mineagent.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.tico.mineagent.MineAgent;
import com.tico.mineagent.raycast.RaycastMode;

public record RaycastRequestPayload(int requestId, int resolution, double fovDegrees, String mode) implements CustomPacketPayload {
	public static final ResourceLocation PAYLOAD_ID = ResourceLocation.fromNamespaceAndPath(MineAgent.MOD_ID, "raycast_request");
	public static final Type<RaycastRequestPayload> ID = new Type<>(PAYLOAD_ID);
	public static final StreamCodec<RegistryFriendlyByteBuf, RaycastRequestPayload> CODEC = StreamCodec.of(RaycastRequestPayload::write, RaycastRequestPayload::read);

	public RaycastRequestPayload(int requestId, int resolution, double fovDegrees, RaycastMode mode) {
		this(requestId, resolution, fovDegrees, mode.id());
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}

	private static void write(RegistryFriendlyByteBuf buffer, RaycastRequestPayload payload) {
		buffer.writeInt(payload.requestId());
		buffer.writeInt(payload.resolution());
		buffer.writeDouble(payload.fovDegrees());
		buffer.writeUtf(payload.mode(), 16);
	}

	private static RaycastRequestPayload read(RegistryFriendlyByteBuf buffer) {
		return new RaycastRequestPayload(buffer.readInt(), buffer.readInt(), buffer.readDouble(), buffer.readUtf(16));
	}
}
