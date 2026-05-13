package com.tico.mineagent.agent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

public record AgentToolResult(String callId, String toolName, boolean ok, JsonObject content, List<AgentImageAttachment> images) {
	private static final Gson GSON = new Gson();

	public AgentToolResult {
		images = List.copyOf(images);
	}

	public static AgentToolResult fromOutput(AgentToolCall call, AgentToolOutput output) {
		return fromOutput(call, output, List.of());
	}

	public static AgentToolResult fromOutput(AgentToolCall call, AgentToolOutput output, List<AgentImageAttachment> images) {
		return new AgentToolResult(call.id(), call.name(), output.ok(), output.content(), images);
	}

	public static AgentToolResult error(AgentToolCall call, String message) {
		JsonObject content = new JsonObject();
		content.addProperty("error", message);
		return new AgentToolResult(call.id(), call.name(), false, content, List.of());
	}

	public String outputJson() {
		JsonObject output = new JsonObject();
		output.addProperty("ok", ok);
		output.addProperty("tool", toolName);
		output.add("content", content);
		if (!images.isEmpty()) {
			JsonArray array = new JsonArray();
			for (AgentImageAttachment image : images) {
				JsonObject item = new JsonObject();
				item.addProperty("label", image.label());
				item.addProperty("media_type", image.mediaType());
				array.add(item);
			}
			output.add("attached_images", array);
		}
		return GSON.toJson(output);
	}
}
