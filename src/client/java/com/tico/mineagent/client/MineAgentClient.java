package com.tico.mineagent.client;

import net.fabricmc.api.ClientModInitializer;

import com.tico.mineagent.client.render.SandboxBoundaryRenderer;
import com.tico.mineagent.client.state.ClientAgentEditBoundsState;
import com.tico.mineagent.client.state.ClientAgentUiState;
import com.tico.mineagent.client.state.ClientSandboxState;

public class MineAgentClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientSandboxState.registerReceiver();
		ClientAgentEditBoundsState.registerReceiver();
		ClientAgentUiState.registerReceivers();
		SandboxBoundaryRenderer.register();
	}
}
