package com.tico.mineagent.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.tico.mineagent.MineAgent;

public record AgentSandboxExpansionReplyPayload(boolean approve) implements CustomPacketPayload {
	public static final ResourceLocation PAYLOAD_ID = ResourceLocation.fromNamespaceAndPath(MineAgent.MOD_ID, "agent_sandbox_expansion_reply");
	public static final Type<AgentSandboxExpansionReplyPayload> ID = new Type<>(PAYLOAD_ID);
	public static final StreamCodec<RegistryFriendlyByteBuf, AgentSandboxExpansionReplyPayload> CODEC = StreamCodec.of(AgentSandboxExpansionReplyPayload::write, AgentSandboxExpansionReplyPayload::read);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}

	private static void write(RegistryFriendlyByteBuf buffer, AgentSandboxExpansionReplyPayload payload) {
		buffer.writeBoolean(payload.approve());
	}

	private static AgentSandboxExpansionReplyPayload read(RegistryFriendlyByteBuf buffer) {
		return new AgentSandboxExpansionReplyPayload(buffer.readBoolean());
	}
}
