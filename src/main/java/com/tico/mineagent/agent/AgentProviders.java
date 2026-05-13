package com.tico.mineagent.agent;

public final class AgentProviders {
	private AgentProviders() {
	}

	public static AgentModelProvider create(AgentProviderType provider) {
		return switch (provider) {
			case OPENAI -> new OpenAiResponsesProvider();
			case CLAUDE -> new ClaudeMessagesProvider();
		};
	}
}
