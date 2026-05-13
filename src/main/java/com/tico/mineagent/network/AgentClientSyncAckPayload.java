package com.tico.mineagent.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.tico.mineagent.MineAgent;

public record AgentClientSyncAckPayload(int requestId) implements CustomPacketPayload {
	public static final ResourceLocation PAYLOAD_ID = ResourceLocation.fromNamespaceAndPath(MineAgent.MOD_ID, "agent_client_sync_ack");
	public static final Type<AgentClientSyncAckPayload> ID = new Type<>(PAYLOAD_ID);
	public static final StreamCodec<RegistryFriendlyByteBuf, AgentClientSyncAckPayload> CODEC = StreamCodec.of(AgentClientSyncAckPayload::write, AgentClientSyncAckPayload::read);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}

	private static void write(RegistryFriendlyByteBuf buffer, AgentClientSyncAckPayload payload) {
		buffer.writeInt(payload.requestId());
	}

	private static AgentClientSyncAckPayload read(RegistryFriendlyByteBuf buffer) {
		return new AgentClientSyncAckPayload(buffer.readInt());
	}
}
