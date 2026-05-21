package com.tico.mineagent.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.tico.mineagent.MineAgent;

public record WebUiOpenPayload(String url) implements CustomPacketPayload {
	private static final int MAX_URL_LENGTH = 2048;
	public static final ResourceLocation PAYLOAD_ID = ResourceLocation.fromNamespaceAndPath(MineAgent.MOD_ID, "web_ui_open");
	public static final Type<WebUiOpenPayload> ID = new Type<>(PAYLOAD_ID);
	public static final StreamCodec<RegistryFriendlyByteBuf, WebUiOpenPayload> CODEC = StreamCodec.of(WebUiOpenPayload::write, WebUiOpenPayload::read);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}

	private static void write(RegistryFriendlyByteBuf buffer, WebUiOpenPayload payload) {
		buffer.writeUtf(payload.url(), MAX_URL_LENGTH);
	}

	private static WebUiOpenPayload read(RegistryFriendlyByteBuf buffer) {
		return new WebUiOpenPayload(buffer.readUtf(MAX_URL_LENGTH));
	}
}
