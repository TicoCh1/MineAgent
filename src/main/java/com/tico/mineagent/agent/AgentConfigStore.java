package com.tico.mineagent.agent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;

public final class AgentConfigStore {
	private static final Map<UUID, AgentCredentials> PLAYER_CREDENTIALS = new ConcurrentHashMap<>();

	private AgentConfigStore() {
	}

	public static void configure(ServerPlayer player, AgentCredentials credentials) {
		PLAYER_CREDENTIALS.put(player.getUUID(), credentials);
	}

	public static Optional<AgentCredentials> configured(ServerPlayer player) {
		return Optional.ofNullable(PLAYER_CREDENTIALS.get(player.getUUID()));
	}

	public static Optional<AgentCredentials> resolve(ServerPlayer player, AgentProviderType requestedProvider) {
		AgentCredentials configured = PLAYER_CREDENTIALS.get(player.getUUID());
		if (configured != null && (requestedProvider == null || configured.provider() == requestedProvider)) {
			return Optional.of(configured);
		}

		AgentProviderType provider = requestedProvider != null ? requestedProvider : configured != null ? configured.provider() : null;
		if (provider == null) {
			Optional<AgentCredentials> openAi = fromEnvironment(AgentProviderType.OPENAI);
			if (openAi.isPresent()) {
				return openAi;
			}
			return fromEnvironment(AgentProviderType.CLAUDE);
		}

		return fromEnvironment(provider);
	}

	private static Optional<AgentCredentials> fromEnvironment(AgentProviderType provider) {
		String keyName = provider == AgentProviderType.OPENAI ? "OPENAI_API_KEY" : "ANTHROPIC_API_KEY";
		String modelName = provider == AgentProviderType.OPENAI ? "MINEAGENT_OPENAI_MODEL" : "MINEAGENT_CLAUDE_MODEL";
		String apiKey = System.getenv(keyName);
		String model = System.getenv(modelName);
		if (isBlank(apiKey) || isBlank(model)) {
			return Optional.empty();
		}
		return Optional.of(new AgentCredentials(provider, model.trim(), apiKey.trim()));
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
