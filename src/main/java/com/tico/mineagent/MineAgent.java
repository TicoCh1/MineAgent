package com.tico.mineagent;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tico.mineagent.command.MineAgentCommands;
import com.tico.mineagent.mcp.MineAgentMcpServer;
import com.tico.mineagent.network.MineAgentNetworking;
import com.tico.mineagent.tool.SandboxToolEvents;
import com.tico.mineagent.web.MineAgentWebHost;

public class MineAgent implements ModInitializer {
	public static final String MOD_ID = "mineagent";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		MineAgentNetworking.registerPayloads();
		MineAgentNetworking.registerServerReceivers();
		MineAgentCommands.register();
		SandboxToolEvents.register();
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			MineAgentWebHost.instance().stop();
			MineAgentMcpServer.instance().stop();
		});

		LOGGER.info("MineAgent initialized.");
	}
}
