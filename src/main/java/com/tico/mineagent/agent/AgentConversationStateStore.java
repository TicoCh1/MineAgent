package com.tico.mineagent.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerPlayer;

import com.tico.mineagent.project.MineAgentProjectStore;
import com.tico.mineagent.sandbox.SandboxSession;

public final class AgentConversationStateStore {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
	private static final String FILE_NAME = "conversation_state.json";
	private static final int SCHEMA_VERSION = 1;
	private static final Duration OPENAI_RESPONSE_ID_REUSE_WINDOW = Duration.ofDays(29);
	private static final int MAX_COMPACT_HISTORY_CHARS = 24_000;
	private static final int MAX_MESSAGE_EXCERPT_CHARS = 1_600;
	private static final int MAX_COMPACT_MESSAGES_SCANNED = 32;

	private AgentConversationStateStore() {
	}

	public static AgentConversationRestore load(ServerPlayer player, SandboxSession sandbox, AgentCredentials credentials) throws IOException {
		Path path = path(player, sandbox);
		if (!Files.isRegularFile(path)) {
			return AgentConversationRestore.empty();
		}

		JsonObject state;
		try {
			state = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (RuntimeException exception) {
			return AgentConversationRestore.fallback("MineAgent could not parse the active project's persisted conversation state: " + exception.getMessage());
		}

		String playerId = optionalString(state, "player_uuid");
		if (!playerId.isBlank() && !playerId.equals(player.getUUID().toString())) {
			return AgentConversationRestore.fallback("Persisted conversation state belongs to a different player UUID and was ignored.");
		}

		String projectId = optionalString(state, "project_id");
		if (!projectId.isBlank() && !projectId.equals(sandbox.activeProjectId())) {
			return AgentConversationRestore.fallback("Persisted conversation state belongs to project '" + projectId + "', while the active project is '" + sandbox.activeProjectId() + "'.");
		}

		String provider = optionalString(state, "provider");
		String model = optionalString(state, "model");
		if (!provider.equals(credentials.provider().id())) {
			return AgentConversationRestore.fallback("Persisted conversation state uses provider '" + provider + "', while the current run uses '" + credentials.provider().id() + "'.");
		}
		if (!model.equals(credentials.model())) {
			return AgentConversationRestore.fallback("Persisted conversation state uses model '" + model + "', while the current run uses '" + credentials.model() + "'.");
		}

		if (credentials.provider() == AgentProviderType.OPENAI) {
			String responseId = optionalString(nestedObject(state, "openai"), "previous_response_id");
			if (responseId.isBlank()) {
				return AgentConversationRestore.fallback("Persisted OpenAI conversation state does not contain a previous_response_id.");
			}
			if (isOpenAiResponseIdTooOld(state)) {
				return AgentConversationRestore.fallback("Persisted OpenAI previous_response_id is older than MineAgent's conservative 29-day reuse window.");
			}
			return AgentConversationRestore.openAi(provider, model, responseId, "MineAgent restored the active project's OpenAI previous_response_id. Continue from that provider-side state, but treat design://agent.md, design://brief.md, and design://features as the durable project source of truth if anything conflicts.");
		}

		if (credentials.provider() == AgentProviderType.CLAUDE) {
			JsonArray compacted = optionalArray(nestedObject(state, "claude"), "compacted_messages");
			if (compacted.isEmpty()) {
				return AgentConversationRestore.fallback("Persisted Claude conversation state does not contain compacted messages.");
			}
			return AgentConversationRestore.claude(provider, model, compacted, "MineAgent restored compacted Claude messages for the active project. Treat design://agent.md, design://brief.md, and design://features as the durable project source of truth if anything conflicts.");
		}

		return AgentConversationRestore.fallback("Persisted conversation state has an unsupported provider: " + provider);
	}

	public static JsonObject save(ServerPlayer player, SandboxSession sandbox, AgentConversation conversation) throws IOException {
		Path projectRoot = MineAgentProjectStore.activeProjectRoot(player, sandbox);
		Files.createDirectories(projectRoot);

		JsonObject object = new JsonObject();
		String now = Instant.now().toString();
		object.addProperty("schema_version", SCHEMA_VERSION);
		object.addProperty("updated_at", now);
		object.addProperty("player", player.getName().getString());
		object.addProperty("player_uuid", player.getUUID().toString());
		object.addProperty("project_id", sandbox.activeProjectId());
		object.addProperty("provider", conversation.credentials().provider().id());
		object.addProperty("model", conversation.credentials().model());
		object.addProperty("last_user_prompt", truncate(conversation.prompt(), 4_000));

		JsonObject openAi = new JsonObject();
		if (conversation.previousOpenAiResponseId() != null && !conversation.previousOpenAiResponseId().isBlank()) {
			openAi.addProperty("previous_response_id", conversation.previousOpenAiResponseId());
			openAi.addProperty("saved_at", now);
		}
		object.add("openai", openAi);

		JsonObject claude = new JsonObject();
		claude.addProperty("source_message_count", conversation.claudeMessages().size());
		claude.add("compacted_messages", compactedClaudeMessages(conversation));
		object.add("claude", claude);

		JsonObject fallback = new JsonObject();
		fallback.addProperty("strategy", "If provider state cannot be resumed, start a fresh provider conversation, read design://agent.md, design://brief.md, design://features, use attached initial sandbox screenshots, and take new captures before substantial edits.");
		fallback.addProperty("durable_project_memory", "design docs and structure metadata under the active per-player project folder");
		object.add("fallback", fallback);

		Path path = projectRoot.resolve(FILE_NAME);
		Files.writeString(path, GSON.toJson(object), StandardCharsets.UTF_8);

		JsonObject result = new JsonObject();
		result.addProperty("saved", true);
		result.addProperty("path", path.toAbsolutePath().normalize().toString());
		result.addProperty("project_id", sandbox.activeProjectId());
		result.addProperty("provider", conversation.credentials().provider().id());
		result.addProperty("model", conversation.credentials().model());
		result.addProperty("has_openai_previous_response_id", conversation.previousOpenAiResponseId() != null && !conversation.previousOpenAiResponseId().isBlank());
		result.addProperty("claude_source_message_count", conversation.claudeMessages().size());
		return result;
	}

	public static Path path(ServerPlayer player, SandboxSession sandbox) throws IOException {
		return MineAgentProjectStore.activeProjectRoot(player, sandbox).resolve(FILE_NAME);
	}

	private static JsonArray compactedClaudeMessages(AgentConversation conversation) {
		JsonArray array = new JsonArray();
		String history = compactClaudeHistory(conversation);
		if (history.isBlank()) {
			return array;
		}
		JsonObject message = new JsonObject();
		message.addProperty("role", "user");
		JsonArray content = new JsonArray();
		JsonObject text = new JsonObject();
		text.addProperty("type", "text");
		text.addProperty("text", history);
		content.add(text);
		message.add("content", content);
		array.add(message);
		return array;
	}

	private static String compactClaudeHistory(AgentConversation conversation) {
		List<String> lines = new ArrayList<>();
		lines.add("<mineagent_persisted_conversation_summary>");
		lines.add("This is a compact MineAgent-local summary of previous Claude messages for the active project. It intentionally omits image data and large raw tool outputs.");
		lines.add("Provider: " + conversation.credentials().provider().id());
		lines.add("Model: " + conversation.credentials().model());
		lines.add("Last user prompt: " + truncate(oneLine(conversation.prompt()), 1_000));
		lines.add("On resume, prefer durable project files design://agent.md, design://brief.md, design://features, and fresh captures when current geometry matters.");

		JsonArray messages = conversation.claudeMessages();
		int start = Math.max(0, messages.size() - MAX_COMPACT_MESSAGES_SCANNED);
		for (int index = start; index < messages.size(); index++) {
			JsonElement element = messages.get(index);
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject message = element.getAsJsonObject();
			String role = optionalString(message, "role");
			String excerpt = compactMessageContent(message.get("content"));
			if (excerpt.isBlank()) {
				continue;
			}
			lines.add(role + ": " + excerpt);
		}
		lines.add("</mineagent_persisted_conversation_summary>");

		String joined = String.join("\n", lines);
		if (joined.length() <= MAX_COMPACT_HISTORY_CHARS) {
			return joined;
		}
		return joined.substring(0, MAX_COMPACT_HISTORY_CHARS) + "\n... truncated by MineAgent conversation compaction ...\n</mineagent_persisted_conversation_summary>";
	}

	private static String compactMessageContent(JsonElement content) {
		if (content == null || content.isJsonNull()) {
			return "";
		}
		if (content.isJsonPrimitive()) {
			return truncate(oneLine(content.getAsString()), MAX_MESSAGE_EXCERPT_CHARS);
		}
		if (!content.isJsonArray()) {
			return truncate(oneLine(content.toString()), MAX_MESSAGE_EXCERPT_CHARS);
		}

		List<String> blocks = new ArrayList<>();
		for (JsonElement item : content.getAsJsonArray()) {
			if (!item.isJsonObject()) {
				continue;
			}
			JsonObject block = item.getAsJsonObject();
			String type = optionalString(block, "type");
			switch (type) {
				case "text" -> blocks.add(truncate(oneLine(optionalString(block, "text")), MAX_MESSAGE_EXCERPT_CHARS));
				case "tool_use" -> blocks.add("tool_use " + optionalString(block, "name") + " input=" + truncate(oneLine(block.has("input") ? block.get("input").toString() : "{}"), 800));
				case "tool_result" -> blocks.add("tool_result " + optionalString(block, "tool_use_id") + " content=" + truncate(oneLine(optionalString(block, "content")), 800));
				case "image" -> blocks.add("[image omitted]");
				default -> blocks.add(type.isBlank() ? "[unknown block]" : "[" + type + " omitted]");
			}
		}
		return truncate(String.join(" | ", blocks), MAX_MESSAGE_EXCERPT_CHARS);
	}

	private static boolean isOpenAiResponseIdTooOld(JsonObject state) {
		String timestamp = optionalString(nestedObject(state, "openai"), "saved_at");
		if (timestamp.isBlank()) {
			timestamp = optionalString(state, "updated_at");
		}
		if (timestamp.isBlank()) {
			return false;
		}
		try {
			Instant saved = Instant.parse(timestamp);
			return saved.plus(OPENAI_RESPONSE_ID_REUSE_WINDOW).isBefore(Instant.now());
		} catch (DateTimeParseException exception) {
			return false;
		}
	}

	private static JsonObject nestedObject(JsonObject object, String name) {
		if (object == null || !object.has(name) || !object.get(name).isJsonObject()) {
			return new JsonObject();
		}
		return object.getAsJsonObject(name);
	}

	private static JsonArray optionalArray(JsonObject object, String name) {
		JsonArray copy = new JsonArray();
		if (object == null || !object.has(name) || !object.get(name).isJsonArray()) {
			return copy;
		}
		for (JsonElement element : object.getAsJsonArray(name)) {
			copy.add(element.deepCopy());
		}
		return copy;
	}

	private static String optionalString(JsonObject object, String name) {
		if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
			return "";
		}
		return object.get(name).getAsString();
	}

	private static String truncate(String value, int maxChars) {
		String text = value == null ? "" : value;
		if (text.length() <= maxChars) {
			return text;
		}
		return text.substring(0, maxChars) + "...";
	}

	private static String oneLine(String value) {
		return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
	}
}
