package com.tico.mineagent.agent;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.server.level.ServerPlayer;

import com.tico.mineagent.sandbox.SandboxSession;

public record AgentToolContext(ServerPlayer player, SandboxSession sandbox, CommandBuildContext registryAccess) {
}
