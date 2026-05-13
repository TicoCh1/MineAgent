package com.tico.mineagent.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.tico.mineagent.MineAgent;

public record AgentUiLogPayload(String level, String message, String status, String detail) implements CustomPacketPayload {
	private static final int MAX_TEXT_LENGTH = 8192;
	private static final String TRUNCATION_SUFFIX = "\n... truncated by MineAgent UI ...";
	public static final ResourceLocation PAYLOAD_ID = ResourceLocation.fromNamespaceAndPath(MineAgent.MOD_ID, "agent_ui_log");
	public static final Type<AgentUiLogPayload> ID = new Type<>(PAYLOAD_ID);
	public static final StreamCodec<RegistryFriendlyByteBuf, AgentUiLogPayload> CODEC = StreamCodec.of(AgentUiLogPayload::write, AgentUiLogPayload::read);

	public AgentUiLogPayload {
		level = limit(level);
		message = limit(message);
		status = limit(status);
		detail = limit(detail);
	}

	public AgentUiLogPayload(String level, String message, String status) {
		this(level, message, status, "");
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}

	private static void write(RegistryFriendlyByteBuf buffer, AgentUiLogPayload payload) {
		buffer.writeUtf(payload.level(), MAX_TEXT_LENGTH);
		buffer.writeUtf(payload.message(), MAX_TEXT_LENGTH);
		buffer.writeUtf(payload.status(), MAX_TEXT_LENGTH);
		buffer.writeUtf(payload.detail(), MAX_TEXT_LENGTH);
	}

	private static AgentUiLogPayload read(RegistryFriendlyByteBuf buffer) {
		return new AgentUiLogPayload(
				buffer.readUtf(MAX_TEXT_LENGTH),
				buffer.readUtf(MAX_TEXT_LENGTH),
				buffer.readUtf(MAX_TEXT_LENGTH),
				buffer.readUtf(MAX_TEXT_LENGTH));
	}

	private static String limit(String text) {
		if (text == null) {
			return "";
		}
		if (text.length() <= MAX_TEXT_LENGTH) {
			return text;
		}
		int end = Math.max(0, MAX_TEXT_LENGTH - TRUNCATION_SUFFIX.length());
		return text.substring(0, end) + TRUNCATION_SUFFIX;
	}
}
