package com.tico.mineagent.mcp;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.server.level.ServerPlayer;

import com.tico.mineagent.sandbox.SandboxSession;

public record MineAgentMcpContext(ServerPlayer player, SandboxSession sandbox, CommandBuildContext registryAccess) {
}
