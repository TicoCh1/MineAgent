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
	public AgentConversation start(AgentCredentials credentials, String prompt, List<AgentImageAttachment> initialImages) throws Exception {
		AgentConversation conversation = new AgentConversation(credentials, prompt, initialImages);
		JsonObject user = new JsonObject();
		user.addProperty("role", "user");
		user.add("content", userContent(
				prompt + "\n\nInitial MineAgent sandbox context: attached images are the eight +/-X +/-Y +/-Z sandbox isometric screenshots captured before planning.",
				initialImages));
		conversation.claudeMessages().add(user);
		return conversation;
	}

	@Override
	public AgentModelTurn next(AgentConversation conversation, List<AgentTool> tools, List<AgentToolResult> toolResults, AgentRunLogger runLog) throws Exception {
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
			List<AgentImageAttachment> resultImages = resultImages(toolResults);
			if (!resultImages.isEmpty()) {
				content.add(textBlock("MineAgent visual capture outputs from the tool results above. Use these images for the required self-review before planning the next edit batch." + imageLabelSummary(resultImages)));
				for (AgentImageAttachment image : resultImages) {
					content.add(imageBlock(image));
				}
			}
			user.add("content", content);
			conversation.claudeMessages().add(user);
		}

		JsonObject body = new JsonObject();
		body.addProperty("model", conversation.credentials().model());
		body.addProperty("max_tokens", 4096);
		body.addProperty("system", AgentSystemPrompt.TEXT);
		body.add("messages", conversation.claudeMessages());
		body.add("tools", tools(tools));

		runLog.providerRequest("claude", redactedForLog(body));
		JsonObject response = send(conversation.credentials().apiKey(), body, runLog);
		JsonArray content = response.getAsJsonArray("content");
		JsonObject assistant = new JsonObject();
		assistant.addProperty("role", "assistant");
		assistant.add("content", content == null ? new JsonArray() : content.deepCopy());
		conversation.claudeMessages().add(assistant);
		return parseTurn(response);
	}

	private JsonObject send(String apiKey, JsonObject body, AgentRunLogger runLog) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(MESSAGES_URI)
				.timeout(Duration.ofSeconds(90))
				.header("x-api-key", apiKey)
				.header("anthropic-version", "2023-06-01")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> response;
		try {
			response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException exception) {
			throw new IOException("Claude request failed before receiving a response. Check local internet, DNS, proxy, firewall, or offline single-player environment. Details: " + exception.getMessage(), exception);
		}
		JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
		runLog.providerHttpResponse("claude", response.statusCode(), json);
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IOException(describeHttpFailure("Claude Messages API", response.statusCode(), json));
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
					if ("web_search".equals(optionalString(block, "name"))) {
						continue;
					}
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
		JsonObject webSearch = new JsonObject();
		webSearch.addProperty("type", "web_search_20250305");
		webSearch.addProperty("name", "web_search");
		webSearch.addProperty("max_uses", 5);
		array.add(webSearch);
		for (AgentTool tool : tools) {
			JsonObject object = new JsonObject();
			object.addProperty("name", tool.name());
			object.addProperty("description", tool.description());
			object.add("input_schema", tool.inputSchema());
			array.add(object);
		}
		return array;
	}

	private static JsonArray userContent(String text, List<AgentImageAttachment> images) throws IOException {
		JsonArray content = new JsonArray();
		content.add(textBlock(text + imageLabelSummary(images)));
		for (AgentImageAttachment image : images) {
			content.add(imageBlock(image));
		}
		return content;
	}

	private static JsonObject textBlock(String text) {
		JsonObject block = new JsonObject();
		block.addProperty("type", "text");
		block.addProperty("text", text);
		return block;
	}

	private static JsonObject imageBlock(AgentImageAttachment image) throws IOException {
		JsonObject block = new JsonObject();
		block.addProperty("type", "image");
		JsonObject source = new JsonObject();
		source.addProperty("type", "base64");
		source.addProperty("media_type", image.mediaType());
		source.addProperty("data", image.base64Data());
		block.add("source", source);
		return block;
	}

	private static String imageLabelSummary(List<AgentImageAttachment> images) {
		if (images.isEmpty()) {
			return "";
		}
		List<String> labels = new ArrayList<>();
		for (AgentImageAttachment image : images) {
			labels.add(image.label());
		}
		return "\n\nAttached image labels: " + String.join(", ", labels);
	}

	private static List<AgentImageAttachment> resultImages(List<AgentToolResult> results) {
		List<AgentImageAttachment> images = new ArrayList<>();
		for (AgentToolResult result : results) {
			images.addAll(result.images());
		}
		return List.copyOf(images);
	}

	private static String describeHttpFailure(String provider, int statusCode, JsonObject response) {
		String message = errorMessage(response);
		String hint = switch (statusCode) {
			case 400 -> "request was rejected, often because the model id, tool configuration, or request payload is invalid";
			case 401, 403 -> "API key was rejected or lacks permission";
			case 402 -> "billing, credits, or usage access may be insufficient";
			case 404 -> "model or endpoint was not found";
			case 408 -> "request timed out";
			case 409 -> "request conflicted with provider state";
			case 413 -> "request is too large";
			case 429 -> "rate limit or quota was exceeded";
			default -> statusCode >= 500 ? "provider service error; try again later" : "provider returned an error";
		};
		return provider + " returned HTTP " + statusCode + " (" + hint + "): " + message;
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

	private static JsonObject redactedForLog(JsonObject body) {
		JsonObject copy = body.deepCopy();
		redactImages(copy);
		return copy;
	}

	private static void redactImages(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return;
		}
		if (element.isJsonArray()) {
			for (JsonElement item : element.getAsJsonArray()) {
				redactImages(item);
			}
			return;
		}
		if (!element.isJsonObject()) {
			return;
		}
		JsonObject object = element.getAsJsonObject();
		for (String key : new ArrayList<>(object.keySet())) {
			JsonElement child = object.get(key);
			if ("data".equals(key) && child != null && child.isJsonPrimitive() && child.getAsString().length() > 128) {
				object.addProperty(key, "<base64 image redacted by MineAgent run log>");
			} else {
				redactImages(child);
			}
		}
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
