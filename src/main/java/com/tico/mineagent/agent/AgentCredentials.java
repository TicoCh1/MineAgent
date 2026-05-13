package com.tico.mineagent.agent;

public record AgentCredentials(AgentProviderType provider, String model, String apiKey) {
	public String safeSummary() {
		return provider.id() + " / " + model;
	}
}
