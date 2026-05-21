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
	public AgentConversation start(AgentCredentials credentials, String prompt, String initialContext, List<AgentImageAttachment> initialImages, AgentConversationRestore restore) {
		AgentConversation conversation = new AgentConversation(credentials, prompt, initialContext, initialImages, restore.note());
		if (restore.hasOpenAiPreviousResponseId()) {
			conversation.setPreviousOpenAiResponseId(restore.openAiPreviousResponseId());
		}
		return conversation;
	}

	@Override
	public AgentModelTurn next(AgentConversation conversation, List<AgentTool> tools, List<AgentToolResult> toolResults, AgentRunLogger runLog) throws Exception {
		JsonObject body = new JsonObject();
		body.addProperty("model", conversation.credentials().model());
		body.addProperty("instructions", AgentSystemPrompt.TEXT);
		body.addProperty("parallel_tool_calls", true);
		body.addProperty("tool_choice", "auto");
		body.addProperty("max_output_tokens", 4096);
		body.add("tools", tools(tools));
		JsonArray include = new JsonArray();
		include.add("web_search_call.action.sources");
		body.add("include", include);

		JsonArray input = new JsonArray();
		if (conversation.previousOpenAiResponseId() != null) {
			body.addProperty("previous_response_id", conversation.previousOpenAiResponseId());
		}
		if (!conversation.initialInputSent()) {
			JsonObject user = new JsonObject();
			user.addProperty("role", "user");
			user.add("content", userContent(
					initialUserInput(conversation),
					conversation.initialImages()));
			input.add(user);
		} else {
			for (AgentToolResult result : toolResults) {
				JsonObject output = new JsonObject();
				output.addProperty("type", "function_call_output");
				output.addProperty("call_id", result.callId());
				output.addProperty("output", result.outputJson());
				input.add(output);
			}
			List<AgentImageAttachment> resultImages = resultImages(toolResults);
			if (!resultImages.isEmpty()) {
				JsonObject user = new JsonObject();
				user.addProperty("role", "user");
				user.add("content", userContent("MineAgent visual capture outputs from the tool results above. Use these images for the required self-review before planning the next edit batch.", resultImages));
				input.add(user);
			}
		}
		body.add("input", input);

		runLog.providerRequest("openai", redactedForLog(body));
		JsonObject response = send(conversation.credentials().apiKey(), body, runLog);
		conversation.setPreviousOpenAiResponseId(requiredString(response, "id"));
		conversation.markInitialInputSent();
		return parseTurn(response);
	}

	private JsonObject send(String apiKey, JsonObject body, AgentRunLogger runLog) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(RESPONSES_URI)
				.timeout(Duration.ofSeconds(90))
				.header("Authorization", "Bearer " + apiKey)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> response;
		try {
			response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException exception) {
			throw new IOException("OpenAI request failed before receiving a response. Check local internet, DNS, proxy, firewall, or offline single-player environment. Details: " + exception.getMessage(), exception);
		}
		JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
		runLog.providerHttpResponse("openai", response.statusCode(), json);
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IOException(describeHttpFailure("OpenAI Responses API", response.statusCode(), json));
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
		JsonObject webSearch = new JsonObject();
		webSearch.addProperty("type", "web_search");
		webSearch.addProperty("external_web_access", true);
		array.add(webSearch);
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

	private static String initialUserInput(AgentConversation conversation) {
		return conversation.initialContext()
				+ restoreNote(conversation)
				+ "\n\n<user_request>\n"
				+ conversation.prompt()
				+ "\n</user_request>";
	}

	private static String restoreNote(AgentConversation conversation) {
		if (conversation.restoreNote().isBlank()) {
			return "";
		}
		return "\n\n<conversation_restore>\n" + conversation.restoreNote() + "\n</conversation_restore>";
	}

	private static JsonArray userContent(String text, List<AgentImageAttachment> images) throws IOException {
		JsonArray content = new JsonArray();
		JsonObject textItem = new JsonObject();
		textItem.addProperty("type", "input_text");
		textItem.addProperty("text", text + imageLabelSummary(images));
		content.add(textItem);
		for (AgentImageAttachment image : images) {
			JsonObject imageItem = new JsonObject();
			imageItem.addProperty("type", "input_image");
			imageItem.addProperty("detail", "auto");
			imageItem.addProperty("image_url", image.dataUrl());
			content.add(imageItem);
		}
		return content;
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
			if ("image_url".equals(key) && child != null && child.isJsonPrimitive() && child.getAsString().startsWith("data:image/")) {
				object.addProperty(key, "data:image/*;base64,<redacted by MineAgent run log>");
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
