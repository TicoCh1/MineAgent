package com.tico.mineagent.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public record AgentConversationRestore(
		boolean restoredProviderState,
		String provider,
		String model,
		String openAiPreviousResponseId,
		JsonArray claudeCompactedMessages,
		String note,
		String fallbackReason) {
	public static AgentConversationRestore empty() {
		return new AgentConversationRestore(false, "", "", "", new JsonArray(), "", "");
	}

	public static AgentConversationRestore fallback(String reason) {
		return new AgentConversationRestore(false, "", "", "", new JsonArray(), fallbackNote(reason), reason == null ? "" : reason);
	}

	public static AgentConversationRestore openAi(String provider, String model, String previousResponseId, String note) {
		return new AgentConversationRestore(true, provider, model, previousResponseId, new JsonArray(), note, "");
	}

	public static AgentConversationRestore claude(String provider, String model, JsonArray compactedMessages, String note) {
		return new AgentConversationRestore(true, provider, model, "", copyArray(compactedMessages), note, "");
	}

	public boolean hasOpenAiPreviousResponseId() {
		return openAiPreviousResponseId != null && !openAiPreviousResponseId.isBlank();
	}

	public boolean hasClaudeCompactedMessages() {
		return claudeCompactedMessages != null && !claudeCompactedMessages.isEmpty();
	}

	public JsonArray claudeCompactedMessagesCopy() {
		return copyArray(claudeCompactedMessages);
	}

	public JsonObject toLogJson() {
		JsonObject object = new JsonObject();
		object.addProperty("restored_provider_state", restoredProviderState);
		object.addProperty("provider", provider == null ? "" : provider);
		object.addProperty("model", model == null ? "" : model);
		object.addProperty("has_openai_previous_response_id", hasOpenAiPreviousResponseId());
		object.addProperty("claude_compacted_message_count", claudeCompactedMessages == null ? 0 : claudeCompactedMessages.size());
		object.addProperty("fallback_reason", fallbackReason == null ? "" : fallbackReason);
		object.addProperty("note", note == null ? "" : note);
		return object;
	}

	private static String fallbackNote(String reason) {
		String cleanReason = reason == null || reason.isBlank() ? "No compatible persisted provider conversation state is available." : reason;
		return cleanReason
				+ "\nMineAgent is starting a fresh provider conversation for the active project. Restore project continuity by reading design://agent.md, design://brief.md, and design://features before making design assumptions. Use the attached initial sandbox screenshots, and take new capture screenshots before substantial edits if current visual state matters.";
	}

	private static JsonArray copyArray(JsonArray source) {
		JsonArray copy = new JsonArray();
		if (source == null) {
			return copy;
		}
		for (JsonElement element : source) {
			copy.add(element.deepCopy());
		}
		return copy;
	}
}
