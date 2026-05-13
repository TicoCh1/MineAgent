package com.tico.mineagent.agent;

import com.google.gson.JsonObject;

public record AgentToolOutput(boolean ok, JsonObject content) {
	public static AgentToolOutput ok(JsonObject content) {
		return new AgentToolOutput(true, content);
	}

	public static AgentToolOutput error(String message) {
		JsonObject content = new JsonObject();
		content.addProperty("error", message);
		return new AgentToolOutput(false, content);
	}
}
