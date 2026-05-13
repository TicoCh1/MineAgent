package com.tico.mineagent.agent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public record AgentToolResult(String callId, String toolName, boolean ok, JsonObject content) {
	private static final Gson GSON = new Gson();

	public static AgentToolResult fromOutput(AgentToolCall call, AgentToolOutput output) {
		return new AgentToolResult(call.id(), call.name(), output.ok(), output.content());
	}

	public static AgentToolResult error(AgentToolCall call, String message) {
		JsonObject content = new JsonObject();
		content.addProperty("error", message);
		return new AgentToolResult(call.id(), call.name(), false, content);
	}

	public String outputJson() {
		JsonObject output = new JsonObject();
		output.addProperty("ok", ok);
		output.addProperty("tool", toolName);
		output.add("content", content);
		return GSON.toJson(output);
	}
}
