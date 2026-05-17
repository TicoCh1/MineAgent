package com.tico.mineagent.mcp;

import java.util.List;

import net.minecraft.commands.CommandBuildContext;

import com.tico.mineagent.agent.AgentTool;
import com.tico.mineagent.agent.MineAgentToolRegistry;

public final class MineAgentMcpRegistry {
	private final List<AgentTool> tools;
	private final List<MineAgentMcpResource> resources;

	private MineAgentMcpRegistry(List<AgentTool> tools, List<MineAgentMcpResource> resources) {
		this.tools = List.copyOf(tools);
		this.resources = List.copyOf(resources);
	}

	public static MineAgentMcpRegistry create(CommandBuildContext registryAccess) {
		MineAgentToolRegistry toolRegistry = MineAgentToolRegistry.create(registryAccess);
		return new MineAgentMcpRegistry(toolRegistry.tools(), MineAgentMcpResources.create());
	}

	public List<AgentTool> tools() {
		return tools;
	}

	public List<MineAgentMcpResource> resources() {
		return resources;
	}
}
