package com.tico.mineagent;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tico.mineagent.command.MineAgentCommands;
import com.tico.mineagent.network.MineAgentNetworking;
import com.tico.mineagent.tool.SandboxToolEvents;

public class MineAgent implements ModInitializer {
	public static final String MOD_ID = "mineagent";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		MineAgentNetworking.registerPayloads();
		MineAgentNetworking.registerServerReceivers();
		MineAgentCommands.register();
		SandboxToolEvents.register();

		LOGGER.info("MineAgent initialized.");
	}
}
