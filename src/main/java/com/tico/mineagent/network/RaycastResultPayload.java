package com.tico.mineagent.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.tico.mineagent.MineAgent;

public record RaycastResultPayload(
		int requestId,
		boolean ok,
		String mode,
		int resolution,
		double fovDegrees,
		long durationMillis,
		int hitPixels,
		int airPixels,
		int outOfSandboxPixels,
		double cameraX,
		double cameraY,
		double cameraZ,
		String message,
		String localFiles) implements CustomPacketPayload {
	private static final int MAX_TEXT_LENGTH = 1024;
	private static final int MAX_PATH_TEXT_LENGTH = 8192;
	public static final ResourceLocation PAYLOAD_ID = ResourceLocation.fromNamespaceAndPath(MineAgent.MOD_ID, "raycast_result");
	public static final Type<RaycastResultPayload> ID = new Type<>(PAYLOAD_ID);
	public static final StreamCodec<RegistryFriendlyByteBuf, RaycastResultPayload> CODEC = StreamCodec.of(RaycastResultPayload::write, RaycastResultPayload::read);

	public RaycastResultPayload {
		mode = limit(mode);
		message = limit(message);
		localFiles = limit(localFiles, MAX_PATH_TEXT_LENGTH);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}

	private static void write(RegistryFriendlyByteBuf buffer, RaycastResultPayload payload) {
		buffer.writeInt(payload.requestId());
		buffer.writeBoolean(payload.ok());
		buffer.writeUtf(payload.mode(), MAX_TEXT_LENGTH);
		buffer.writeInt(payload.resolution());
		buffer.writeDouble(payload.fovDegrees());
		buffer.writeLong(payload.durationMillis());
		buffer.writeInt(payload.hitPixels());
		buffer.writeInt(payload.airPixels());
		buffer.writeInt(payload.outOfSandboxPixels());
		buffer.writeDouble(payload.cameraX());
		buffer.writeDouble(payload.cameraY());
		buffer.writeDouble(payload.cameraZ());
		buffer.writeUtf(payload.message(), MAX_TEXT_LENGTH);
		buffer.writeUtf(payload.localFiles(), MAX_PATH_TEXT_LENGTH);
	}

	private static RaycastResultPayload read(RegistryFriendlyByteBuf buffer) {
		return new RaycastResultPayload(
				buffer.readInt(),
				buffer.readBoolean(),
				buffer.readUtf(MAX_TEXT_LENGTH),
				buffer.readInt(),
				buffer.readDouble(),
				buffer.readLong(),
				buffer.readInt(),
				buffer.readInt(),
				buffer.readInt(),
				buffer.readDouble(),
				buffer.readDouble(),
				buffer.readDouble(),
				buffer.readUtf(MAX_TEXT_LENGTH),
				buffer.readUtf(MAX_PATH_TEXT_LENGTH));
	}

	private static String limit(String text) {
		return limit(text, MAX_TEXT_LENGTH);
	}

	private static String limit(String text, int maxLength) {
		if (text == null) {
			return "";
		}
		return text.length() <= maxLength ? text : text.substring(0, maxLength - 16) + "...";
	}
}
