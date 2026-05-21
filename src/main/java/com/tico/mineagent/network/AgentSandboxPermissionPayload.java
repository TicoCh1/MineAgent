package com.tico.mineagent.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.tico.mineagent.MineAgent;

public record AgentSandboxPermissionPayload(String mode) implements CustomPacketPayload {
	public static final ResourceLocation PAYLOAD_ID = ResourceLocation.fromNamespaceAndPath(MineAgent.MOD_ID, "agent_sandbox_permission");
	public static final Type<AgentSandboxPermissionPayload> ID = new Type<>(PAYLOAD_ID);
	public static final StreamCodec<RegistryFriendlyByteBuf, AgentSandboxPermissionPayload> CODEC = StreamCodec.of(AgentSandboxPermissionPayload::write, AgentSandboxPermissionPayload::read);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}

	private static void write(RegistryFriendlyByteBuf buffer, AgentSandboxPermissionPayload payload) {
		buffer.writeUtf(payload.mode());
	}

	private static AgentSandboxPermissionPayload read(RegistryFriendlyByteBuf buffer) {
		return new AgentSandboxPermissionPayload(buffer.readUtf());
	}
}
