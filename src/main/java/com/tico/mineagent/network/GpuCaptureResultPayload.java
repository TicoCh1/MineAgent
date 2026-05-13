package com.tico.mineagent.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.tico.mineagent.MineAgent;

public record GpuCaptureResultPayload(
		int requestId,
		boolean ok,
		String captureType,
		int width,
		int height,
		double fovDegrees,
		long durationMillis,
		int imageCount,
		String labels,
		String message,
		String localFiles) implements CustomPacketPayload {
	private static final int MAX_TEXT_LENGTH = 1024;
	private static final int MAX_PATH_TEXT_LENGTH = 16384;
	public static final ResourceLocation PAYLOAD_ID = ResourceLocation.fromNamespaceAndPath(MineAgent.MOD_ID, "gpu_capture_result");
	public static final Type<GpuCaptureResultPayload> ID = new Type<>(PAYLOAD_ID);
	public static final StreamCodec<RegistryFriendlyByteBuf, GpuCaptureResultPayload> CODEC = StreamCodec.of(GpuCaptureResultPayload::write, GpuCaptureResultPayload::read);

	public GpuCaptureResultPayload {
		captureType = limit(captureType);
		labels = limit(labels);
		message = limit(message);
		localFiles = limit(localFiles, MAX_PATH_TEXT_LENGTH);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}

	private static void write(RegistryFriendlyByteBuf buffer, GpuCaptureResultPayload payload) {
		buffer.writeInt(payload.requestId());
		buffer.writeBoolean(payload.ok());
		buffer.writeUtf(payload.captureType(), MAX_TEXT_LENGTH);
		buffer.writeInt(payload.width());
		buffer.writeInt(payload.height());
		buffer.writeDouble(payload.fovDegrees());
		buffer.writeLong(payload.durationMillis());
		buffer.writeInt(payload.imageCount());
		buffer.writeUtf(payload.labels(), MAX_TEXT_LENGTH);
		buffer.writeUtf(payload.message(), MAX_TEXT_LENGTH);
		buffer.writeUtf(payload.localFiles(), MAX_PATH_TEXT_LENGTH);
	}

	private static GpuCaptureResultPayload read(RegistryFriendlyByteBuf buffer) {
		return new GpuCaptureResultPayload(
				buffer.readInt(),
				buffer.readBoolean(),
				buffer.readUtf(MAX_TEXT_LENGTH),
				buffer.readInt(),
				buffer.readInt(),
				buffer.readDouble(),
				buffer.readLong(),
				buffer.readInt(),
				buffer.readUtf(MAX_TEXT_LENGTH),
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
