package com.tico.mineagent.agent;

import java.util.Locale;

public enum AgentProviderType {
	OPENAI("openai"),
	CLAUDE("claude");

	private final String id;

	AgentProviderType(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public static AgentProviderType byId(String id) {
		String normalized = id.toLowerCase(Locale.ROOT);
		for (AgentProviderType provider : values()) {
			if (provider.id.equals(normalized)) {
				return provider;
			}
		}
		return null;
	}
}
