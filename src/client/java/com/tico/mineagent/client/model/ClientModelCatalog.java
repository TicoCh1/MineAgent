package com.tico.mineagent.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class ClientModelCatalog {
	private static final URI OPENAI_MODELS_URI = URI.create("https://api.openai.com/v1/models");
	private static final URI ANTHROPIC_MODELS_URI = URI.create("https://api.anthropic.com/v1/models");
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	private ClientModelCatalog() {
	}

	public static List<String> defaults(String providerId) {
		if ("claude".equals(normalizeProvider(providerId))) {
			return List.of(
					"claude-opus-4-1-20250805",
					"claude-opus-4-20250514",
					"claude-sonnet-4-20250514",
					"claude-3-7-sonnet-20250219",
					"claude-3-5-sonnet-latest",
					"claude-3-5-haiku-latest");
		}
		return List.of(
				"gpt-5.5",
				"gpt-5.4",
				"gpt-5.2",
				"gpt-5.1",
				"gpt-5",
				"gpt-5-mini",
				"gpt-5-nano",
				"gpt-4.1");
	}

	public static CompletableFuture<Result> refresh(String providerId, String apiKey) {
		String provider = normalizeProvider(providerId);
		if (apiKey == null || apiKey.isBlank()) {
			return CompletableFuture.completedFuture(new Result(provider, defaults(provider), "fallback", "Enter an API key before refreshing the online model list."));
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				return "claude".equals(provider) ? fetchClaude(apiKey.trim()) : fetchOpenAi(apiKey.trim());
			} catch (Exception exception) {
				return new Result(provider, defaults(provider), "fallback", "Could not refresh model list: " + exception.getMessage());
			}
		});
	}

	private static Result fetchOpenAi(String apiKey) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(OPENAI_MODELS_URI)
				.timeout(Duration.ofSeconds(30))
				.header("Authorization", "Bearer " + apiKey)
				.GET()
				.build();
		HttpResponse<String> response;
		try {
			response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException exception) {
			throw new IOException("OpenAI model list request failed before receiving a response. Check local internet, DNS, proxy, firewall, or offline single-player environment. Details: " + exception.getMessage(), exception);
		}
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IOException(describeHttpFailure("OpenAI models API", response.statusCode(), response.body()));
		}

		JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
		JsonArray data = root.getAsJsonArray("data");
		List<ModelEntry> entries = new ArrayList<>();
		if (data != null) {
			for (JsonElement element : data) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject object = element.getAsJsonObject();
				String id = string(object, "id");
				if (id.isBlank() || !isOpenAiTextModel(id)) {
					continue;
				}
				entries.add(new ModelEntry(id, longValue(object, "created")));
			}
		}
		entries.sort(Comparator.comparingLong(ModelEntry::created).reversed().thenComparing(ModelEntry::id));
		List<String> models = unique(entries.stream().map(ModelEntry::id).toList(), defaults("openai"));
		return new Result("openai", models, "online", "Loaded " + models.size() + " OpenAI model ids.");
	}

	private static Result fetchClaude(String apiKey) throws IOException, InterruptedException {
		List<String> ids = new ArrayList<>();
		URI uri = ANTHROPIC_MODELS_URI;
		for (int page = 0; page < 5; page++) {
			HttpRequest request = HttpRequest.newBuilder(uri)
					.timeout(Duration.ofSeconds(30))
					.header("x-api-key", apiKey)
					.header("anthropic-version", "2023-06-01")
					.GET()
					.build();
			HttpResponse<String> response;
			try {
				response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			} catch (IOException exception) {
				throw new IOException("Anthropic model list request failed before receiving a response. Check local internet, DNS, proxy, firewall, or offline single-player environment. Details: " + exception.getMessage(), exception);
			}
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new IOException(describeHttpFailure("Anthropic models API", response.statusCode(), response.body()));
			}
			JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
			JsonArray data = root.getAsJsonArray("data");
			if (data != null) {
				for (JsonElement element : data) {
					if (!element.isJsonObject()) {
						continue;
					}
					String id = string(element.getAsJsonObject(), "id");
					if (!id.isBlank() && id.toLowerCase(Locale.ROOT).startsWith("claude")) {
						ids.add(id);
					}
				}
			}
			if (!bool(root, "has_more")) {
				break;
			}
			String lastId = string(root, "last_id");
			if (lastId.isBlank()) {
				break;
			}
			uri = URI.create(ANTHROPIC_MODELS_URI + "?after_id=" + URLEncoder.encode(lastId, StandardCharsets.UTF_8));
		}
		List<String> models = unique(ids, defaults("claude"));
		return new Result("claude", models, "online", "Loaded " + models.size() + " Claude model ids.");
	}

	private static boolean isOpenAiTextModel(String id) {
		String lower = id.toLowerCase(Locale.ROOT);
		if (lower.contains("embedding") || lower.contains("moderation") || lower.contains("transcribe")
				|| lower.contains("tts") || lower.contains("realtime") || lower.contains("audio")
				|| lower.contains("image") || lower.contains("dall") || lower.contains("whisper")
				|| lower.contains("sora")) {
			return false;
		}
		return lower.startsWith("gpt-") || lower.matches("o\\d.*");
	}

	private static List<String> unique(List<String> primary, List<String> fallback) {
		Set<String> seen = new LinkedHashSet<>();
		seen.addAll(primary);
		if (seen.isEmpty()) {
			seen.addAll(fallback);
		}
		return List.copyOf(seen);
	}

	private static String normalizeProvider(String providerId) {
		return "claude".equals(providerId == null ? "" : providerId.toLowerCase(Locale.ROOT).trim()) ? "claude" : "openai";
	}

	private static String describeHttpFailure(String provider, int statusCode, String body) {
		String hint = switch (statusCode) {
			case 400 -> "request was rejected, likely because the request payload is invalid";
			case 401, 403 -> "API key was rejected or lacks permission";
			case 402 -> "billing, credits, or usage access may be insufficient";
			case 404 -> "models endpoint was not found";
			case 408 -> "request timed out";
			case 413 -> "request is too large";
			case 429 -> "rate limit or quota was exceeded";
			default -> statusCode >= 500 ? "provider service error; try again later" : "provider returned an error";
		};
		return provider + " returned HTTP " + statusCode + " (" + hint + "): " + providerErrorMessage(body);
	}

	private static String providerErrorMessage(String body) {
		if (body == null || body.isBlank()) {
			return "empty response body";
		}
		try {
			JsonElement element = JsonParser.parseString(body);
			if (element.isJsonObject()) {
				JsonObject object = element.getAsJsonObject();
				if (object.has("error") && object.get("error").isJsonObject()) {
					JsonObject error = object.getAsJsonObject("error");
					String message = string(error, "message");
					String type = string(error, "type");
					String code = string(error, "code");
					return message + (type.isBlank() && code.isBlank() ? "" : " [type=" + type + ", code=" + code + "]");
				}
			}
		} catch (RuntimeException ignored) {
			// Fall through to truncated raw body.
		}
		return body.length() > 300 ? body.substring(0, 300) + "..." : body;
	}

	private static String string(JsonObject object, String name) {
		if (!object.has(name) || object.get(name).isJsonNull()) {
			return "";
		}
		return object.get(name).getAsString();
	}

	private static long longValue(JsonObject object, String name) {
		if (!object.has(name) || object.get(name).isJsonNull()) {
			return 0L;
		}
		try {
			return object.get(name).getAsLong();
		} catch (RuntimeException ignored) {
			return 0L;
		}
	}

	private static boolean bool(JsonObject object, String name) {
		if (!object.has(name) || object.get(name).isJsonNull()) {
			return false;
		}
		return object.get(name).getAsBoolean();
	}

	private record ModelEntry(String id, long created) {
	}

	public record Result(String providerId, List<String> models, String source, String message) {
	}
}
