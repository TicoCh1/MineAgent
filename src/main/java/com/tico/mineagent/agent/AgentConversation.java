package com.tico.mineagent.agent;

import com.google.gson.JsonArray;

public final class AgentConversation {
	private final AgentCredentials credentials;
	private final String prompt;
	private final JsonArray claudeMessages = new JsonArray();
	private String previousOpenAiResponseId;

	public AgentConversation(AgentCredentials credentials, String prompt) {
		this.credentials = credentials;
		this.prompt = prompt;
	}

	public AgentCredentials credentials() {
		return credentials;
	}

	public String prompt() {
		return prompt;
	}

	public JsonArray claudeMessages() {
		return claudeMessages;
	}

	public String previousOpenAiResponseId() {
		return previousOpenAiResponseId;
	}

	public void setPreviousOpenAiResponseId(String previousOpenAiResponseId) {
		this.previousOpenAiResponseId = previousOpenAiResponseId;
	}
}
