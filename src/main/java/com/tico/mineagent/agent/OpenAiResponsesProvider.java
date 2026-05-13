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

public final class OpenAiResponsesProvider implements AgentModelProvider {
	private static final URI RESPONSES_URI = URI.create("https://api.openai.com/v1/responses");
	private static final Gson GSON = new Gson();
	private final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(20))
			.build();

	@Override
	public AgentConversation start(AgentCredentials credentials, String prompt) {
		return new AgentConversation(credentials, prompt);
	}

	@Override
	public AgentModelTurn next(AgentConversation conversation, List<AgentTool> tools, List<AgentToolResult> toolResults) throws Exception {
		JsonObject body = new JsonObject();
		body.addProperty("model", conversation.credentials().model());
		body.addProperty("instructions", AgentSystemPrompt.TEXT);
		body.addProperty("parallel_tool_calls", false);
		body.addProperty("max_output_tokens", 2048);
		body.add("tools", tools(tools));

		JsonArray input = new JsonArray();
		if (conversation.previousOpenAiResponseId() == null) {
			JsonObject user = new JsonObject();
			user.addProperty("role", "user");
			user.addProperty("content", conversation.prompt());
			input.add(user);
		} else {
			body.addProperty("previous_response_id", conversation.previousOpenAiResponseId());
			for (AgentToolResult result : toolResults) {
				JsonObject output = new JsonObject();
				output.addProperty("type", "function_call_output");
				output.addProperty("call_id", result.callId());
				output.addProperty("output", result.outputJson());
				input.add(output);
			}
		}
		body.add("input", input);

		JsonObject response = send(conversation.credentials().apiKey(), body);
		conversation.setPreviousOpenAiResponseId(requiredString(response, "id"));
		return parseTurn(response);
	}

	private JsonObject send(String apiKey, JsonObject body) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(RESPONSES_URI)
				.timeout(Duration.ofSeconds(90))
				.header("Authorization", "Bearer " + apiKey)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IOException("OpenAI Responses API returned HTTP " + response.statusCode() + ": " + errorMessage(json));
		}
		return json;
	}

	private static AgentModelTurn parseTurn(JsonObject response) {
		String text = collectOutputText(response);
		List<AgentToolCall> toolCalls = new ArrayList<>();
		JsonArray output = response.getAsJsonArray("output");
		if (output != null) {
			for (JsonElement element : output) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject item = element.getAsJsonObject();
				if (!"function_call".equals(optionalString(item, "type"))) {
					continue;
				}
				String id = optionalString(item, "call_id");
				if (id == null) {
					id = optionalString(item, "id");
				}
				toolCalls.add(new AgentToolCall(id, requiredString(item, "name"), parseArguments(optionalString(item, "arguments"))));
			}
		}
		return new AgentModelTurn(text, List.copyOf(toolCalls));
	}

	private static JsonArray tools(List<AgentTool> tools) {
		JsonArray array = new JsonArray();
		for (AgentTool tool : tools) {
			JsonObject object = new JsonObject();
			object.addProperty("type", "function");
			object.addProperty("name", tool.name());
			object.addProperty("description", tool.description());
			object.add("parameters", tool.inputSchema());
			array.add(object);
		}
		return array;
	}

	private static String collectOutputText(JsonObject response) {
		if (response.has("output_text") && response.get("output_text").isJsonPrimitive()) {
			return response.get("output_text").getAsString();
		}
		StringBuilder text = new StringBuilder();
		JsonArray output = response.getAsJsonArray("output");
		if (output == null) {
			return "";
		}
		for (JsonElement element : output) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject item = element.getAsJsonObject();
			if (!"message".equals(optionalString(item, "type"))) {
				continue;
			}
			JsonArray content = item.getAsJsonArray("content");
			if (content == null) {
				continue;
			}
			for (JsonElement contentElement : content) {
				if (contentElement.isJsonObject()) {
					JsonObject block = contentElement.getAsJsonObject();
					if ("output_text".equals(optionalString(block, "type")) && block.has("text")) {
						text.append(block.get("text").getAsString());
					}
				}
			}
		}
		return text.toString();
	}

	private static JsonObject parseArguments(String raw) {
		if (raw == null || raw.isBlank()) {
			return new JsonObject();
		}
		try {
			JsonElement parsed = JsonParser.parseString(raw);
			if (parsed.isJsonObject()) {
				return parsed.getAsJsonObject();
			}
			JsonObject fallback = new JsonObject();
			fallback.add("value", parsed);
			return fallback;
		} catch (RuntimeException exception) {
			JsonObject fallback = new JsonObject();
			fallback.addProperty("_argument_parse_error", exception.getMessage());
			fallback.addProperty("_raw_arguments", raw);
			return fallback;
		}
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
