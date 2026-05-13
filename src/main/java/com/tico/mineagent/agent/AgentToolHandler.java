package com.tico.mineagent.agent;

import com.google.gson.JsonObject;

@FunctionalInterface
public interface AgentToolHandler {
	AgentToolOutput execute(AgentToolContext context, JsonObject arguments) throws Exception;
}
