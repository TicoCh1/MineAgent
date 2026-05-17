package com.tico.mineagent.agent;

import com.google.gson.JsonObject;

public record AgentTool(
		String name,
		String description,
		JsonObject inputSchema,
		boolean readOnly,
		AgentToolHandler handler) {
	public AgentToolMetadata metadata() {
		return AgentToolMetadata.forName(name, readOnly);
	}
}
