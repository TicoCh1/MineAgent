package com.tico.mineagent.agent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class ClaudeMessagesProvider implements AgentModelProvider {
	private static final URI MESSAGES_URI = URI.create("https://api.anthropic.com/v1/messages");
	private static final Gson GSON = new Gson();
	private final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(20))
			.build();

	@Override
	public AgentConversation start(AgentCredentials credentials, String prompt) {
		AgentConversation conversation = new AgentConversation(credentials, prompt);
		JsonObject user = new JsonObject();
		user.addProperty("role", "user");
		user.addProperty("content", prompt);
		conversation.claudeMessages().add(user);
		return conversation;
	}

	@Override
	public AgentModelTurn next(AgentConversation conversation, List<AgentTool> tools, List<AgentToolResult> toolResults) throws Exception {
		if (!toolResults.isEmpty()) {
			JsonObject user = new JsonObject();
			user.addProperty("role", "user");
			JsonArray content = new JsonArray();
			for (AgentToolResult result : toolResults) {
				JsonObject block = new JsonObject();
				block.addProperty("type", "tool_result");
				block.addProperty("tool_use_id", result.callId());
				block.addProperty("content", result.outputJson());
				if (!result.ok()) {
					block.addProperty("is_error", true);
				}
				content.add(block);
			}
			user.add("content", content);
			conversation.claudeMessages().add(user);
		}

		JsonObject body = new JsonObject();
		body.addProperty("model", conversation.credentials().model());
		body.addProperty("max_tokens", 2048);
		body.addProperty("system", AgentSystemPrompt.TEXT);
		body.add("messages", conversation.claudeMessages());
		body.add("tools", tools(tools));

		JsonObject response = send(conversation.credentials().apiKey(), body);
		JsonArray content = response.getAsJsonArray("content");
		JsonObject assistant = new JsonObject();
		assistant.addProperty("role", "assistant");
		assistant.add("content", content == null ? new JsonArray() : content.deepCopy());
		conversation.claudeMessages().add(assistant);
		return parseTurn(response);
	}

	private JsonObject send(String apiKey, JsonObject body) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(MESSAGES_URI)
				.timeout(Duration.ofSeconds(90))
				.header("x-api-key", apiKey)
				.header("anthropic-version", "2023-06-01")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IOException("Claude Messages API returned HTTP " + response.statusCode() + ": " + errorMessage(json));
		}
		return json;
	}

	private static AgentModelTurn parseTurn(JsonObject response) {
		StringBuilder text = new StringBuilder();
		List<AgentToolCall> toolCalls = new ArrayList<>();
		JsonArray content = response.getAsJsonArray("content");
		if (content != null) {
			for (JsonElement element : content) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject block = element.getAsJsonObject();
				String type = optionalString(block, "type");
				if ("text".equals(type) && block.has("text")) {
					text.append(block.get("text").getAsString());
				} else if ("tool_use".equals(type)) {
					JsonObject input = block.has("input") && block.get("input").isJsonObject()
							? block.getAsJsonObject("input")
							: new JsonObject();
					toolCalls.add(new AgentToolCall(requiredString(block, "id"), requiredString(block, "name"), input));
				}
			}
		}
		return new AgentModelTurn(text.toString(), List.copyOf(toolCalls));
	}

	private static JsonArray tools(List<AgentTool> tools) {
		JsonArray array = new JsonArray();
		for (AgentTool tool : tools) {
			JsonObject object = new JsonObject();
			object.addProperty("name", tool.name());
			object.addProperty("description", tool.description());
			object.add("input_schema", tool.inputSchema());
			array.add(object);
		}
		return array;
	}

	private static String errorMessage(JsonObject response) {
		if (response.has("error") && response.get("error").isJsonObject()) {
			JsonObject error = response.getAsJsonObject("error");
			if (error.has("message")) {
				return error.get("message").getAsString();
			}
		}
		return response.toString();
	}

	private static String optionalString(JsonObject object, String name) {
		if (!object.has(name) || object.get(name).isJsonNull()) {
			return null;
		}
		return object.get(name).getAsString();
	}

	private static String requiredString(JsonObject object, String name) {
		String value = optionalString(object, name);
		if (value == null) {
			throw new IllegalStateException("Provider response missing field: " + name);
		}
		return value;
	}
}
