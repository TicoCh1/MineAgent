package com.tico.mineagent.agent;

import com.google.gson.JsonObject;

public record AgentToolCall(String id, String name, JsonObject arguments) {
}
