package com.tico.mineagent.agent;

import com.google.gson.JsonArray;

import java.util.List;

public final class AgentConversation {
	private final AgentCredentials credentials;
	private final String prompt;
	private final String initialContext;
	private final List<AgentImageAttachment> initialImages;
	private final String restoreNote;
	private final JsonArray claudeMessages = new JsonArray();
	private String previousOpenAiResponseId;
	private boolean initialInputSent;

	public AgentConversation(AgentCredentials credentials, String prompt, String initialContext, List<AgentImageAttachment> initialImages, String restoreNote) {
		this.credentials = credentials;
		this.prompt = prompt;
		this.initialContext = initialContext;
		this.initialImages = List.copyOf(initialImages);
		this.restoreNote = restoreNote == null ? "" : restoreNote;
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

	public String restoreNote() {
		return restoreNote;
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

	public boolean initialInputSent() {
		return initialInputSent;
	}

	public void markInitialInputSent() {
		this.initialInputSent = true;
	}
}
