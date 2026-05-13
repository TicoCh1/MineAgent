package com.tico.mineagent.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.tico.mineagent.MineAgent;

public record GpuCaptureRequestPayload(
		int requestId,
		String captureType,
		int width,
		int height,
		double fovDegrees,
		double cameraX,
		double cameraY,
		double cameraZ,
		float yawDegrees,
		float pitchDegrees,
		BlockPos sandboxMin,
		BlockPos sandboxMax) implements CustomPacketPayload {
	private static final int MAX_TEXT_LENGTH = 32;
	public static final ResourceLocation PAYLOAD_ID = ResourceLocation.fromNamespaceAndPath(MineAgent.MOD_ID, "gpu_capture_request");
	public static final Type<GpuCaptureRequestPayload> ID = new Type<>(PAYLOAD_ID);
	public static final StreamCodec<RegistryFriendlyByteBuf, GpuCaptureRequestPayload> CODEC = StreamCodec.of(GpuCaptureRequestPayload::write, GpuCaptureRequestPayload::read);

	public GpuCaptureRequestPayload {
		captureType = limit(captureType);
		sandboxMin = sandboxMin == null ? BlockPos.ZERO : sandboxMin.immutable();
		sandboxMax = sandboxMax == null ? BlockPos.ZERO : sandboxMax.immutable();
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}

	private static void write(RegistryFriendlyByteBuf buffer, GpuCaptureRequestPayload payload) {
		buffer.writeInt(payload.requestId());
		buffer.writeUtf(payload.captureType(), MAX_TEXT_LENGTH);
		buffer.writeInt(payload.width());
		buffer.writeInt(payload.height());
		buffer.writeDouble(payload.fovDegrees());
		buffer.writeDouble(payload.cameraX());
		buffer.writeDouble(payload.cameraY());
		buffer.writeDouble(payload.cameraZ());
		buffer.writeFloat(payload.yawDegrees());
		buffer.writeFloat(payload.pitchDegrees());
		buffer.writeBlockPos(payload.sandboxMin());
		buffer.writeBlockPos(payload.sandboxMax());
	}

	private static GpuCaptureRequestPayload read(RegistryFriendlyByteBuf buffer) {
		return new GpuCaptureRequestPayload(
				buffer.readInt(),
				buffer.readUtf(MAX_TEXT_LENGTH),
				buffer.readInt(),
				buffer.readInt(),
				buffer.readDouble(),
				buffer.readDouble(),
				buffer.readDouble(),
				buffer.readDouble(),
				buffer.readFloat(),
				buffer.readFloat(),
				buffer.readBlockPos(),
				buffer.readBlockPos());
	}

	private static String limit(String text) {
		if (text == null) {
			return "";
		}
		return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH);
	}
}
