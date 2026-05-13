package com.tico.mineagent.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.tico.mineagent.MineAgent;

public record AgentClientSyncRequestPayload(int requestId, int clientTicks) implements CustomPacketPayload {
	public static final ResourceLocation PAYLOAD_ID = ResourceLocation.fromNamespaceAndPath(MineAgent.MOD_ID, "agent_client_sync_request");
	public static final Type<AgentClientSyncRequestPayload> ID = new Type<>(PAYLOAD_ID);
	public static final StreamCodec<RegistryFriendlyByteBuf, AgentClientSyncRequestPayload> CODEC = StreamCodec.of(AgentClientSyncRequestPayload::write, AgentClientSyncRequestPayload::read);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}

	private static void write(RegistryFriendlyByteBuf buffer, AgentClientSyncRequestPayload payload) {
		buffer.writeInt(payload.requestId());
		buffer.writeInt(payload.clientTicks());
	}

	private static AgentClientSyncRequestPayload read(RegistryFriendlyByteBuf buffer) {
		return new AgentClientSyncRequestPayload(buffer.readInt(), buffer.readInt());
	}
}
