package com.tico.mineagent.agent;

import com.google.gson.JsonArray;

import java.util.List;

public final class AgentConversation {
	private final AgentCredentials credentials;
	private final String prompt;
	private final String initialContext;
	private final List<AgentImageAttachment> initialImages;
	private final JsonArray claudeMessages = new JsonArray();
	private String previousOpenAiResponseId;

	public AgentConversation(AgentCredentials credentials, String prompt, String initialContext, List<AgentImageAttachment> initialImages) {
		this.credentials = credentials;
		this.prompt = prompt;
		this.initialContext = initialContext;
		this.initialImages = List.copyOf(initialImages);
	}

	public AgentCredentials credentials() {
		return credentials;
	}

	public String prompt() {
		return prompt;
	}

	public String initialContext() {
		return initialContext;
	}

	public List<AgentImageAttachment> initialImages() {
		return initialImages;
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
