package com.tico.mineagent.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.tico.mineagent.MineAgent;
import com.tico.mineagent.agent.AgentConfigStore;
import com.tico.mineagent.agent.AgentCredentials;
import com.tico.mineagent.agent.AgentHostMode;
import com.tico.mineagent.agent.AgentHostModes;
import com.tico.mineagent.agent.AgentLogBuffer;
import com.tico.mineagent.agent.AgentModelCatalog;
import com.tico.mineagent.agent.AgentPlanStates;
import com.tico.mineagent.agent.AgentProviderType;
import com.tico.mineagent.agent.AgentRuntime;
import com.tico.mineagent.network.MineAgentNetworking;
import com.tico.mineagent.project.MineAgentProjectStore;
import com.tico.mineagent.sandbox.SandboxConfigStore;
import com.tico.mineagent.sandbox.SandboxPermissionMode;
import com.tico.mineagent.sandbox.SandboxSession;
import com.tico.mineagent.sandbox.SandboxSessions;

public final class MineAgentWebHost {
	private static final MineAgentWebHost INSTANCE = new MineAgentWebHost();
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final int DEFAULT_PORT = 39320;
	private static final DateTimeFormatter PROJECT_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);

	private HttpServer httpServer;
	private ExecutorService httpExecutor;
	private ActiveSession activeSession;
	private int port = DEFAULT_PORT;

	private MineAgentWebHost() {
	}

	public static MineAgentWebHost instance() {
		return INSTANCE;
	}

	public synchronized StartResult start(ServerPlayer player, CommandBuildContext registryAccess, int requestedPort, boolean openBrowser) {
		int resolvedPort = requestedPort <= 0 ? DEFAULT_PORT : requestedPort;
		MinecraftServer server = ((ServerLevel) player.level()).getServer();
		activeSession = new ActiveSession(server, player.getUUID(), registryAccess);
		AgentHostModes.set(player, AgentHostMode.INTERNAL);

		if (httpServer != null && port == resolvedPort) {
			if (openBrowser) {
				openBrowser(url(resolvedPort));
			}
			return new StartResult(true, false, "MineAgent Web UI already running for " + player.getName().getString() + " at " + url(resolvedPort) + ".", resolvedPort, url(resolvedPort));
		}
		if (httpServer != null) {
			stop();
			activeSession = new ActiveSession(server, player.getUUID(), registryAccess);
			AgentHostModes.set(player, AgentHostMode.INTERNAL);
		}

		try {
			httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", resolvedPort), 0);
			httpServer.createContext("/", this::handle);
			httpExecutor = Executors.newCachedThreadPool(runnable -> {
				Thread thread = new Thread(runnable, "MineAgent Web UI HTTP");
				thread.setDaemon(true);
				return thread;
			});
			httpServer.setExecutor(httpExecutor);
			httpServer.start();
			port = resolvedPort;
			if (openBrowser) {
				openBrowser(url(resolvedPort));
			}
			MineAgent.LOGGER.info("MineAgent Web UI started at {}", url(resolvedPort));
			AgentLogBuffer.add(player, "ui", "MineAgent Web UI started at " + url(resolvedPort) + ".");
			return new StartResult(true, true, "MineAgent Web UI started for " + player.getName().getString() + " at " + url(resolvedPort) + ".", resolvedPort, url(resolvedPort));
		} catch (IOException exception) {
			httpServer = null;
			if (httpExecutor != null) {
				httpExecutor.shutdownNow();
				httpExecutor = null;
			}
			return new StartResult(false, false, "MineAgent Web UI failed to start on 127.0.0.1:" + resolvedPort + ": " + exception.getMessage(), resolvedPort, url(resolvedPort));
		}
	}

	public synchronized void stop() {
		if (httpServer != null) {
			httpServer.stop(0);
			httpServer = null;
		}
		if (httpExecutor != null) {
			httpExecutor.shutdownNow();
			httpExecutor = null;
		}
		activeSession = null;
	}

	public synchronized String status() {
		if (httpServer == null) {
			return "web UI stopped";
		}
		String target = "no active player";
		ActiveSession session = activeSession;
		if (session != null) {
			ServerPlayer player = session.server().getPlayerList().getPlayer(session.playerId());
			target = player == null ? session.playerId().toString() + " offline" : player.getName().getString();
		}
		return "web UI running at " + url(port) + " for " + target;
	}

	public static int defaultPort() {
		return DEFAULT_PORT;
	}

	private void handle(HttpExchange exchange) throws IOException {
		Headers headers = exchange.getResponseHeaders();
		headers.add("Access-Control-Allow-Origin", "http://127.0.0.1:" + port);
		headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
		headers.add("Access-Control-Allow-Headers", "content-type");
		if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
			exchange.sendResponseHeaders(204, -1);
			return;
		}

		String path = exchange.getRequestURI().getPath();
		try {
			if ("/".equals(path) || "/index.html".equals(path)) {
				writeHtml(exchange, 200, INDEX_HTML);
				return;
			}
			if (path.startsWith("/api/")) {
				handleApi(exchange, path);
				return;
			}
			writePlain(exchange, 404, "MineAgent Web UI route not found.");
		} catch (ApiException exception) {
			JsonObject body = new JsonObject();
			body.addProperty("ok", false);
			body.addProperty("error", exception.getMessage());
			writeJson(exchange, exception.statusCode(), body);
		} catch (Exception exception) {
			MineAgent.LOGGER.warn("MineAgent Web UI request failed: {}", path, exception);
			JsonObject body = new JsonObject();
			body.addProperty("ok", false);
			body.addProperty("error", exception.getMessage());
			writeJson(exchange, 500, body);
		}
	}

	private void handleApi(HttpExchange exchange, String path) throws Exception {
		String method = exchange.getRequestMethod();
		if ("GET".equalsIgnoreCase(method) && "/api/state".equals(path)) {
			writeJson(exchange, 200, ok(withPlayer((player, sandbox, session) -> state(player, sandbox))));
			return;
		}
		if (!"POST".equalsIgnoreCase(method)) {
			throw new ApiException(405, "MineAgent Web UI API endpoint requires POST.");
		}

		JsonObject body = requestBody(exchange);
		if ("/api/models".equals(path)) {
			writeJson(exchange, 200, ok(models(body)));
			return;
		}
		JsonObject result = switch (path) {
			case "/api/configure" -> withPlayer((player, sandbox, session) -> configure(player, body));
			case "/api/project" -> withPlayer((player, sandbox, session) -> project(player, sandbox, body));
			case "/api/permission" -> withPlayer((player, sandbox, session) -> permission(player, sandbox, body));
			case "/api/sandbox" -> withPlayer((player, sandbox, session) -> sandbox(player, sandbox, body));
			case "/api/start" -> withPlayer((player, sandbox, session) -> startAgent(player, sandbox, session, body));
			case "/api/stop" -> withPlayer((player, sandbox, session) -> stopAgent(player));
			case "/api/approve" -> withPlayer((player, sandbox, session) -> approveContinuation(player));
			case "/api/sandbox-expansion" -> withPlayer((player, sandbox, session) -> sandboxExpansion(player, body));
			default -> throw new ApiException(404, "Unknown MineAgent Web UI API endpoint: " + path);
		};
		writeJson(exchange, 200, ok(result));
	}

	private JsonObject state(ServerPlayer player, SandboxSession sandbox) throws IOException {
		JsonObject object = new JsonObject();
		object.addProperty("player", player.getName().getString());
		object.addProperty("player_uuid", player.getUUID().toString());
		object.addProperty("status", AgentRuntime.instance().status(player));
		object.addProperty("running", AgentRuntime.instance().isRunning(player));
		object.addProperty("awaiting_approval", AgentRuntime.instance().isAwaitingApproval(player));
		object.addProperty("completed_steps", AgentRuntime.instance().completedSteps(player));
		object.addProperty("awaiting_sandbox_expansion", AgentRuntime.instance().isAwaitingSandboxExpansion(player));
		object.addProperty("sandbox_expansion_summary", AgentRuntime.instance().sandboxExpansionSummary(player));
		object.addProperty("host_mode", AgentHostModes.get(player).id());
		AgentConfigStore.configured(player).ifPresentOrElse(credentials -> {
			JsonObject config = new JsonObject();
			config.addProperty("configured", true);
			config.addProperty("provider", credentials.provider().id());
			config.addProperty("model", credentials.model());
			object.add("config", config);
		}, () -> {
			JsonObject config = new JsonObject();
			config.addProperty("configured", false);
			config.addProperty("provider", "openai");
			config.addProperty("model", "");
			object.add("config", config);
		});
		object.add("projects", MineAgentProjectStore.index(player, sandbox));
		object.add("sandbox", SandboxConfigStore.summary(player, sandbox));
		object.add("plan", AgentPlanStates.snapshot(player).toJsonObject());
		object.add("logs", AgentLogBuffer.snapshot(player));
		return object;
	}

	private JsonObject models(JsonObject body) throws Exception {
		ModelRequest request = modelRequest(body);
		AgentModelCatalog.Result result = AgentModelCatalog.refresh(request.providerId(), request.apiKey()).get(45, TimeUnit.SECONDS);
		AgentLogBuffer.add(request.playerId(), "ui", result.message(), "");
		JsonObject object = new JsonObject();
		object.addProperty("provider", result.providerId());
		object.addProperty("source", result.source());
		object.addProperty("message", result.message());
		JsonArray models = new JsonArray();
		for (String model : result.models()) {
			models.add(model);
		}
		object.add("models", models);
		return object;
	}

	private ModelRequest modelRequest(JsonObject body) throws Exception {
		return withPlayer((player, sandbox, session) -> {
			AgentProviderType provider = requireProvider(body);
			String apiKey = optionalString(body, "api_key", "");
			if (apiKey.isBlank()) {
				Optional<AgentCredentials> configured = AgentConfigStore.configured(player);
				if (configured.isPresent() && configured.get().provider() == provider) {
					apiKey = configured.get().apiKey();
				}
			}
			return new ModelRequest(player.getUUID(), provider.id(), apiKey);
		});
	}

	private JsonObject configure(ServerPlayer player, JsonObject body) {
		AgentProviderType provider = requireProvider(body);
		String model = requireString(body, "model");
		String apiKey = optionalString(body, "api_key", "");
		if (apiKey.isBlank()) {
			Optional<AgentCredentials> configured = AgentConfigStore.configured(player);
			if (configured.isPresent() && configured.get().provider() == provider) {
				apiKey = configured.get().apiKey();
			}
		}
		if (apiKey.isBlank()) {
			throw new IllegalArgumentException("API key is required before saving an AI API configuration.");
		}
		AgentConfigStore.configure(player, new AgentCredentials(provider, model, apiKey));
		AgentLogBuffer.add(player, "ok", "MineAgent API config saved: " + provider.id() + " / " + model + ".");
		JsonObject object = new JsonObject();
		object.addProperty("configured", true);
		object.addProperty("provider", provider.id());
		object.addProperty("model", model);
		return object;
	}

	private JsonObject project(ServerPlayer player, SandboxSession sandbox, JsonObject body) throws IOException {
		String action = optionalString(body, "action", "select");
		String projectId = optionalString(body, "project_id", "");
		String title = optionalString(body, "title", "");
		if ("create".equals(action) && projectId.isBlank()) {
			projectId = uniqueProjectId(player, "");
			title = title.isBlank() ? "New MineAgent Project" : title;
		}
		if (projectId.isBlank()) {
			throw new IllegalArgumentException("Missing required field: project_id");
		}
		if ("create".equals(action) && MineAgentProjectStore.projectExists(player, projectId)) {
			throw new IllegalArgumentException("Project already exists: " + projectId);
		}
		if (("select".equals(action) || "rename".equals(action)) && !MineAgentProjectStore.projectExists(player, projectId)) {
			throw new IllegalArgumentException("Project does not exist: " + projectId);
		}

		JsonObject selected = MineAgentProjectStore.select(player, sandbox, projectId, title);
		JsonObject applied = SandboxConfigStore.applyProjectOrGlobal(player, sandbox);
		SandboxSessions.sync(player);
		AgentLogBuffer.add(player, "ui", switch (action) {
			case "create" -> "Created project " + projectId + ".";
			case "rename" -> "Renamed project " + projectId + ".";
			default -> "Selected project " + projectId + ".";
		});
		JsonObject object = new JsonObject();
		object.add("project", selected);
		object.add("sandbox_applied", applied);
		return object;
	}

	private JsonObject sandbox(ServerPlayer player, SandboxSession sandbox, JsonObject body) throws IOException {
		String scope = optionalString(body, "scope", "project");
		String source = optionalString(body, "source", "manual");
		boolean apply = optionalBoolean(body, "apply", true);
		if ("current".equals(source)) {
			JsonObject result = SandboxConfigStore.saveCurrent(player, sandbox, scope);
			if (apply) {
				SandboxConfigStore.applyProjectOrGlobal(player, sandbox);
			}
			SandboxSessions.sync(player);
			AgentLogBuffer.add(player, "ok", "Saved current " + scope + " sandbox.");
			return result;
		}

		BlockPos min = pos(body.getAsJsonObject("min"));
		BlockPos max = pos(body.getAsJsonObject("max"));
		SandboxPermissionMode permission = permission(body, sandbox.permissionMode());
		JsonObject result;
		if ("global".equals(scope)) {
			result = SandboxConfigStore.saveGlobal(player, sandbox, min, max, permission, apply);
		} else if ("project".equals(scope)) {
			result = SandboxConfigStore.saveProject(player, sandbox, min, max, permission, apply);
		} else {
			throw new IllegalArgumentException("Sandbox scope must be global or project.");
		}
		SandboxSessions.sync(player);
		AgentLogBuffer.add(player, "ok", "Saved " + scope + " sandbox with permission " + permission.label() + ".");
		return result;
	}

	private JsonObject permission(ServerPlayer player, SandboxSession sandbox, JsonObject body) throws IOException {
		SandboxPermissionMode mode = permission(body, sandbox.permissionMode());
		sandbox.setPermissionMode(mode);
		JsonObject saved = new JsonObject();
		if (sandbox.hasCompleteBounds()) {
			saved = SandboxConfigStore.saveProject(player, sandbox, sandbox.min(), sandbox.max(), mode, true);
		}
		SandboxSessions.sync(player);
		AgentLogBuffer.add(player, "ok", "Sandbox permission mode set to " + mode.label() + ".");
		JsonObject object = new JsonObject();
		object.addProperty("permission_mode", mode.id());
		object.addProperty("permission_label", mode.label());
		object.add("saved", saved);
		return object;
	}

	private JsonObject startAgent(ServerPlayer player, SandboxSession sandbox, ActiveSession session, JsonObject body) throws IOException {
		AgentProviderType provider = requireProvider(body);
		String model = optionalString(body, "model", "");
		String apiKey = optionalString(body, "api_key", "");
		if (!apiKey.isBlank()) {
			if (model.isBlank()) {
				throw new IllegalArgumentException("Model is required when setting an API key.");
			}
			AgentConfigStore.configure(player, new AgentCredentials(provider, model, apiKey));
		} else if (!model.isBlank()) {
			Optional<AgentCredentials> configured = AgentConfigStore.configured(player);
			if (configured.isPresent() && configured.get().provider() == provider && !model.equals(configured.get().model())) {
				AgentConfigStore.configure(player, new AgentCredentials(provider, model, configured.get().apiKey()));
			}
		}

		String prompt = requireString(body, "prompt");
		String projectId = optionalString(body, "project_id", "");
		String title = optionalString(body, "title", "");
		boolean createProject = optionalBoolean(body, "create_project", false);
		boolean continueProject = optionalBoolean(body, "continue_project", false);
		if (createProject && projectId.isBlank()) {
			projectId = uniqueProjectId(player, prompt);
			title = title.isBlank() ? provisionalTitle(prompt) : title;
		}
		if (!continueProject && !createProject && (title.isBlank() || "New MineAgent Project".equals(title))) {
			title = provisionalTitle(prompt);
		}
		if (projectId.isBlank()) {
			throw new IllegalArgumentException("Missing required field: project_id");
		}
		if (createProject && MineAgentProjectStore.projectExists(player, projectId)) {
			throw new IllegalArgumentException("Project already exists: " + projectId);
		}
		if (!createProject && !MineAgentProjectStore.projectExists(player, projectId)) {
			throw new IllegalArgumentException("Project does not exist: " + projectId);
		}
		MineAgentProjectStore.select(player, sandbox, projectId, title);
		SandboxConfigStore.applyProjectOrGlobal(player, sandbox);
		SandboxSessions.sync(player);

		AgentRuntime.StartResult result = AgentRuntime.instance().start(player, provider, prompt, session.registryAccess(), continueProject && !createProject);
		AgentLogBuffer.add(player, result.status() == AgentRuntime.StartStatus.STARTED ? "ok" : "error", result.message());
		JsonObject object = new JsonObject();
		object.addProperty("status", result.status().name());
		object.addProperty("message", result.message());
		object.addProperty("project_id", projectId);
		object.addProperty("project_title", title);
		object.addProperty("continue_project", continueProject && !createProject);
		return object;
	}

	private JsonObject stopAgent(ServerPlayer player) {
		AgentRuntime.instance().stop(player);
		AgentLogBuffer.add(player, "warn", "MineAgent agent stop requested.");
		JsonObject object = new JsonObject();
		object.addProperty("stopped", true);
		return object;
	}

	private JsonObject approveContinuation(ServerPlayer player) {
		boolean approved = AgentRuntime.instance().approveContinuation(player);
		AgentLogBuffer.add(player, approved ? "ok" : "warn", approved ? "MineAgent continuation approved for the next segment." : "MineAgent has no pending continuation request.");
		JsonObject object = new JsonObject();
		object.addProperty("approved", approved);
		return object;
	}

	private JsonObject sandboxExpansion(ServerPlayer player, JsonObject body) {
		boolean approve = optionalBoolean(body, "approve", false);
		boolean resolved = AgentRuntime.instance().resolveSandboxExpansion(player, approve);
		AgentLogBuffer.add(player, resolved ? (approve ? "ok" : "warn") : "warn", resolved ? (approve ? "Sandbox expansion approved." : "Sandbox expansion rejected.") : "MineAgent has no pending sandbox expansion request.");
		JsonObject object = new JsonObject();
		object.addProperty("resolved", resolved);
		return object;
	}

	private <T> T withPlayer(PlayerAction<T> action) throws Exception {
		ActiveSession session = requireSession();
		CompletableFuture<T> future = new CompletableFuture<>();
		session.server().execute(() -> {
			try {
				ServerPlayer player = session.server().getPlayerList().getPlayer(session.playerId());
				if (player == null) {
					future.completeExceptionally(new IllegalStateException("MineAgent Web UI target player is no longer online."));
					return;
				}
				if (!player.createCommandSourceStack().hasPermission(2)) {
					future.completeExceptionally(new IllegalStateException("MineAgent Web UI target player no longer has permission level 2."));
					return;
				}
				SandboxSession sandbox = SandboxSessions.get(player);
				future.complete(action.run(player, sandbox, session));
			} catch (Exception exception) {
				future.completeExceptionally(exception);
			}
		});
		try {
			return future.get(20, TimeUnit.SECONDS);
		} catch (TimeoutException exception) {
			throw new IllegalStateException("Timed out while serving MineAgent Web UI request.", exception);
		}
	}

	private synchronized ActiveSession requireSession() {
		if (activeSession == null) {
			throw new ApiException(409, "MineAgent Web UI has no active player session. Run //mineagent web start in game first.");
		}
		return activeSession;
	}

	private static JsonObject requestBody(HttpExchange exchange) throws IOException {
		String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		if (raw.isBlank()) {
			return new JsonObject();
		}
		JsonElement parsed = JsonParser.parseString(raw);
		if (!parsed.isJsonObject()) {
			throw new ApiException(400, "Request body must be a JSON object.");
		}
		return parsed.getAsJsonObject();
	}

	private static JsonObject ok(JsonObject result) {
		JsonObject object = new JsonObject();
		object.addProperty("ok", true);
		object.add("result", result);
		return object;
	}

	private static AgentProviderType requireProvider(JsonObject body) {
		String raw = optionalString(body, "provider", "openai");
		AgentProviderType provider = AgentProviderType.byId(raw);
		if (provider == null) {
			throw new IllegalArgumentException("Provider must be openai or claude.");
		}
		return provider;
	}

	private static SandboxPermissionMode permission(JsonObject body, SandboxPermissionMode fallback) {
		String raw = optionalString(body, "permission_mode", fallback.id());
		SandboxPermissionMode mode = SandboxPermissionMode.byId(raw);
		if (mode == null) {
			throw new IllegalArgumentException("Unknown sandbox permission mode: " + raw);
		}
		return mode;
	}

	private static String uniqueProjectId(ServerPlayer player, String prompt) {
		String prefix = slug(prompt, "project");
		String timestamp = PROJECT_TIME.format(Instant.now());
		String base = MineAgentProjectStore.normalizeProjectId(prefix + "_" + timestamp);
		String id = base;
		int suffix = 2;
		while (MineAgentProjectStore.projectExists(player, id)) {
			id = MineAgentProjectStore.normalizeProjectId(base + "_" + suffix);
			suffix++;
		}
		return id;
	}

	private static String provisionalTitle(String prompt) {
		String text = prompt == null ? "" : prompt.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
		if (text.isBlank()) {
			return "New MineAgent Project";
		}
		if (text.length() > 80) {
			text = text.substring(0, 80).trim();
		}
		return text;
	}

	private static String slug(String raw, String fallback) {
		String value = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
		value = value.replaceAll("[^a-z0-9]+", "_").replaceAll("_+", "_");
		while (value.startsWith("_")) {
			value = value.substring(1);
		}
		while (value.endsWith("_")) {
			value = value.substring(0, value.length() - 1);
		}
		if (value.isBlank()) {
			value = fallback;
		}
		if (value.length() > 28) {
			value = value.substring(0, 28);
			while (value.endsWith("_")) {
				value = value.substring(0, value.length() - 1);
			}
		}
		return value.isBlank() ? fallback : value;
	}

	private static BlockPos pos(JsonObject object) {
		if (object == null) {
			throw new IllegalArgumentException("Missing coordinate object.");
		}
		return new BlockPos(
				object.get("x").getAsInt(),
				object.get("y").getAsInt(),
				object.get("z").getAsInt());
	}

	private static String requireString(JsonObject body, String name) {
		String value = optionalString(body, name, "");
		if (value.isBlank()) {
			throw new IllegalArgumentException("Missing required field: " + name);
		}
		return value;
	}

	private static String optionalString(JsonObject body, String name, String fallback) {
		if (!body.has(name) || body.get(name).isJsonNull()) {
			return fallback;
		}
		return body.get(name).getAsString().trim();
	}

	private static boolean optionalBoolean(JsonObject body, String name, boolean fallback) {
		if (!body.has(name) || body.get(name).isJsonNull()) {
			return fallback;
		}
		return body.get(name).getAsBoolean();
	}

	private static void writeJson(HttpExchange exchange, int statusCode, JsonObject body) throws IOException {
		byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(statusCode, bytes.length);
		try (OutputStream output = exchange.getResponseBody()) {
			output.write(bytes);
		}
	}

	private static void writeHtml(HttpExchange exchange, int statusCode, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		exchange.sendResponseHeaders(statusCode, bytes.length);
		try (OutputStream output = exchange.getResponseBody()) {
			output.write(bytes);
		}
	}

	private static void writePlain(HttpExchange exchange, int statusCode, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
		exchange.sendResponseHeaders(statusCode, bytes.length);
		try (OutputStream output = exchange.getResponseBody()) {
			output.write(bytes);
		}
	}

	private static void openBrowser(String url) {
		try {
			if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()) {
				Desktop.getDesktop().browse(URI.create(url));
			}
		} catch (Exception exception) {
			MineAgent.LOGGER.debug("Could not open MineAgent Web UI browser automatically.", exception);
		}
	}

	private static String url(int port) {
		return "http://127.0.0.1:" + port + "/";
	}

	public record StartResult(boolean ok, boolean newlyStarted, String message, int port, String url) {
	}

	private record ActiveSession(MinecraftServer server, UUID playerId, CommandBuildContext registryAccess) {
	}

	private record ModelRequest(UUID playerId, String providerId, String apiKey) {
	}

	@FunctionalInterface
	private interface PlayerAction<T> {
		T run(ServerPlayer player, SandboxSession sandbox, ActiveSession session) throws Exception;
	}

	private static final class ApiException extends RuntimeException {
		private final int statusCode;

		private ApiException(int statusCode, String message) {
			super(message);
			this.statusCode = statusCode;
		}

		private int statusCode() {
			return statusCode;
		}
	}

	private static final String INDEX_HTML = """
			<!doctype html>
			<html lang="en">
			<head>
			  <meta charset="utf-8" />
			  <meta name="viewport" content="width=device-width, initial-scale=1" />
			  <title>MineAgent</title>
			  <style>
			    :root{color-scheme:dark;--bg:#131313;--rail:#1b1b1b;--panel:#202020;--panel2:#2b2b2b;--line:#303030;--text:#f2f2f2;--muted:#9a9a9a;--soft:#c9c9c9;--accent:#d8d8d8;--ok:#4ade80;--warn:#fbbf24;--bad:#fb7185}
			    *{box-sizing:border-box} body{margin:0;background:var(--bg);color:var(--text);font:14px/1.45 Inter,ui-sans-serif,system-ui,Segoe UI,Arial,sans-serif}
			    button,input,select,textarea{font:inherit} button{border:1px solid #4a4a4a;background:#2d2d2d;color:var(--text);border-radius:8px;padding:8px 12px;cursor:pointer} button:hover{background:#383838} button.primary{background:#e8e8e8;border-color:#e8e8e8;color:#111} button.danger{background:#3a2024;border-color:#74323a;color:#ffd8de} button.icon{width:34px;height:34px;padding:0;border-radius:10px} button.compact{padding:6px 10px}
			    input,select,textarea{width:100%;background:#171717;color:var(--text);border:1px solid #3a3a3a;border-radius:8px;padding:9px 10px;outline:none} input:focus,select:focus,textarea:focus{border-color:#777} textarea{resize:none;min-height:96px;max-height:210px}
			    label{display:block;color:var(--muted);font-size:12px;margin:12px 0 5px}.app{display:grid;grid-template-columns:248px minmax(480px,1fr) 300px;min-height:100vh}.sidebar{background:var(--rail);border-right:1px solid var(--line);padding:14px;display:flex;flex-direction:column;gap:12px}.main{display:flex;flex-direction:column;min-width:0}.right{background:#181818;border-left:1px solid var(--line);padding:16px}
			    .brand{display:flex;align-items:center;justify-content:space-between}.brand h1{font-size:16px;margin:0}.top{height:56px;display:flex;align-items:center;justify-content:flex-end;border-bottom:1px solid #242424;padding:0 16px;gap:8px}.projects{overflow:auto;min-height:0}.project{padding:9px 10px;border-radius:8px;color:var(--soft);cursor:pointer}.project:hover{background:#242424}.project.active{background:#303030;color:var(--text)}.project b{display:block;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.project small{display:block;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.newbox{border-top:1px solid var(--line);padding-top:12px}.muted{color:var(--muted)}.pill{border:1px solid var(--line);border-radius:999px;padding:4px 8px;color:var(--muted);background:#191919;font-size:12px}
			    .chat{flex:1;display:flex;align-items:center;justify-content:center;padding:24px}.composerWrap{width:min(760px,92vw)}.promptTitle{text-align:center;font-size:28px;margin:0 0 26px}.composer{background:#2b2b2b;border:1px solid #383838;border-radius:16px;box-shadow:0 18px 50px #0005;overflow:hidden}.composer textarea{border:0;background:transparent;border-radius:0;padding:16px 16px 8px}.composerbar{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:8px 10px 10px}.projectChip{display:flex;align-items:center;gap:8px;color:var(--muted);font-size:13px;min-width:0}.projectChip span{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.miniSelect{width:auto;min-width:180px;background:#202020;padding:6px 28px 6px 8px}.actions{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.sectionTitle{font-size:12px;color:var(--muted);text-transform:uppercase;letter-spacing:.05em;margin:4px 0 10px}.status{display:grid;gap:8px;color:var(--muted)}.status b{color:var(--text)}.log,.plan{white-space:pre-wrap;background:#121212;border:1px solid var(--line);border-radius:10px;padding:10px;overflow:auto}.log{height:210px}.plan{max-height:300px}.rightBlock{margin-bottom:18px}.row{display:grid;grid-template-columns:1fr 1fr;gap:8px}.row3{display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px}
			    .drawer{position:fixed;inset:0;display:none;background:#0008;z-index:5}.drawer.open{display:block}.sheet{position:absolute;right:0;top:0;height:100%;width:min(430px,96vw);background:#1c1c1c;border-left:1px solid var(--line);padding:18px;overflow:auto;box-shadow:-24px 0 60px #0008}.sheetHead{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px}.sheet h2{margin:18px 0 8px;font-size:14px}.ok{color:var(--ok)}.warn{color:var(--warn)}.bad{color:var(--bad)}
			    @media(max-width:1120px){.app{grid-template-columns:240px minmax(420px,1fr)}.right{display:none}}@media(max-width:760px){.app{grid-template-columns:1fr}.sidebar{display:none}.chat{padding:16px}.promptTitle{font-size:22px}}
			  </style>
			</head>
			<body>
			  <div class="app">
			    <aside class="sidebar">
			      <div class="brand"><h1>MineAgent</h1><button class="secondary compact" onclick="openSettings()" title="Model and settings">Model</button></div>
			      <div class="muted" id="player">No state</div>
			      <div class="sectionTitle">Projects</div>
			      <div class="projects" id="projects"></div>
			      <div class="newbox">
			        <button class="primary" style="width:100%" onclick="selectNewProject()">New project</button>
			      </div>
			    </aside>
			    <main class="main">
			      <div class="top">
			        <span class="pill" id="agentStatus">loading</span>
			      </div>
			      <div class="chat">
			        <div class="composerWrap">
			          <h1 class="promptTitle">What should MineAgent build?</h1>
			          <div class="composer">
			            <textarea id="prompt" placeholder="Message MineAgent..."></textarea>
			            <div class="composerbar">
			              <div class="projectChip"><span id="projectTitle">Project</span><select id="permissionQuick" class="miniSelect" title="Sandbox permission"><option value="strict">Strict sandbox</option><option value="manual_expand">Manual expand</option><option value="auto_expand_air">Auto expand air</option></select></div>
			              <div class="actions">
			                <button class="secondary" onclick="stopRun()">Stop</button>
			                <button class="primary" onclick="startRun()">Send</button>
			              </div>
			            </div>
			          </div>
			        </div>
			      </div>
			    </main>
			    <aside class="right">
			      <div class="rightBlock"><div class="sectionTitle">Status</div><div class="status" id="runStatus"></div></div>
			      <div class="rightBlock"><div class="sectionTitle">Progress</div><div class="plan" id="plan"></div></div>
			      <div class="rightBlock"><div class="sectionTitle">Output</div><div class="log" id="log"></div></div>
			      <div class="actions">
			        <button class="secondary" onclick="approve()">Approve continuation</button>
			        <button class="secondary" onclick="sandboxExpansion(true)">Approve expansion</button>
			        <button class="danger" onclick="sandboxExpansion(false)">Reject</button>
			      </div>
			    </aside>
			  </div>
			  <div class="drawer" id="settingsDrawer" onclick="drawerBackdrop(event)">
			    <div class="sheet">
			      <div class="sheetHead"><h1>Settings</h1><button class="icon" onclick="closeSettings()">x</button></div>
			      <h2>Project</h2>
			      <div class="status" id="projectSettings"></div>
			      <label>Title</label><input id="projectTitleEdit" placeholder="Project title">
			      <div class="actions"><button class="secondary" onclick="saveProjectTitle()">Save title</button></div>
			      <h2>AI API</h2>
			      <div class="row">
			        <div><label>Provider</label><select id="provider"><option value="openai">OpenAI</option><option value="claude">Claude</option></select></div>
			        <div><label>Model</label><select id="model"></select></div>
			      </div>
			      <label>API key</label><input id="apiKey" type="password" placeholder="session memory only">
			      <div class="actions"><button class="secondary" onclick="refreshModels()">Refresh models</button><button class="primary" onclick="saveApiConfig()">Save API config</button></div>
			      <div class="status" id="modelStatus"></div>
			      <h2>Sandbox</h2>
			      <div class="status" id="sandboxStatus"></div>
			      <label>Permission</label>
			      <select id="permissionSettings">
			        <option value="strict">Strict sandbox</option>
			        <option value="manual_expand">Manual expand</option>
			        <option value="auto_expand_air">Auto expand air</option>
			      </select>
			      <div class="row3">
			        <div><label>Min X</label><input id="minX" type="number"></div>
			        <div><label>Min Y</label><input id="minY" type="number"></div>
			        <div><label>Min Z</label><input id="minZ" type="number"></div>
			      </div>
			      <div class="row3">
			        <div><label>Max X</label><input id="maxX" type="number"></div>
			        <div><label>Max Y</label><input id="maxY" type="number"></div>
			        <div><label>Max Z</label><input id="maxZ" type="number"></div>
			      </div>
			      <div class="actions">
			        <button class="secondary" onclick="loadCurrentBounds()">Use active bounds</button>
			        <button onclick="saveSandbox('project')">Save project sandbox</button>
			        <button class="secondary" onclick="saveSandbox('global')">Save global sandbox</button>
			      </div>
			    </div>
			  </div>
			  <script>
			    let state=null, selectedProject=null, newProject=false, titleTouched=false, permissionDirty=false, boundsTouched=false, modelOptions=[], localLogs=[];
			    const $=id=>document.getElementById(id);
			    $('projectTitleEdit').addEventListener('input',()=>titleTouched=true);
			    ['minX','minY','minZ','maxX','maxY','maxZ'].forEach(id=>$(id).addEventListener('input',()=>boundsTouched=true));
			    $('provider').addEventListener('change',()=>refreshModels());
			    $('permissionQuick').addEventListener('change',()=>savePermission($('permissionQuick').value));
			    $('permissionSettings').addEventListener('change',()=>savePermission($('permissionSettings').value));
			    $('prompt').addEventListener('keydown',e=>{ if(e.key==='Enter'&&(e.ctrlKey||e.metaKey)){ startRun(); }});
			    function openSettings(){ $('settingsDrawer').classList.add('open'); }
			    function closeSettings(){ $('settingsDrawer').classList.remove('open'); }
			    function drawerBackdrop(e){ if(e.target.id==='settingsDrawer') closeSettings(); }
			    function esc(s){ return String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
			    function log(msg){ localLogs.push({time:new Date().toISOString(),level:'local',message:msg,detail:''}); if(localLogs.length>40) localLogs.shift(); renderLogs(); }
			    async function api(path, body){ const r=await fetch(path,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(body||{})}); const j=await r.json(); if(!j.ok) throw new Error(j.error||'request failed'); return j.result; }
			    async function refresh(){ try{ const r=await fetch('/api/state'); const j=await r.json(); if(!j.ok) throw new Error(j.error); state=j.result; render(); }catch(e){ $('agentStatus').textContent=e.message; } }
			    function render(){
			      $('player').textContent=`${state.player}`;
			      $('agentStatus').textContent=state.status;
			      $('provider').value=state.config.provider||'openai';
			      ensureModelOptions(state.config.provider||'openai',state.config.model||'');
			      const active=state.projects.active_project_id;
			      if(!selectedProject) selectedProject=active;
			      const list=state.projects.projects||[];
			      $('projects').innerHTML=list.map(p=>`<div class="project ${p.id===selectedProject?'active':''}" onclick="selectProject('${esc(p.id)}')"><b>${esc(p.title||p.id)}</b><small>${esc(p.id)} - ${p.conversation_state_exists?'saved context':'fresh'} - ${p.sandbox_exists?'sandbox':'global'}</small></div>`).join('');
			      const current=list.find(p=>p.id===selectedProject);
			      $('projectTitle').textContent=current?.title||selectedProject||active||'New project';
			      const s=state.sandbox.active_session||{};
			      $('runStatus').innerHTML=`<div>Project: <b>${esc(s.active_project_id||active)}</b></div><div>Bounds: <b>${s.complete?esc(s.bounds):'incomplete'}</b></div><div>Permission: <b>${esc(s.permission_mode||'strict')}</b></div><div>Provider: <b>${esc(state.config.provider||'openai')} ${esc(state.config.model||'')}</b></div><div>Steps: <b>${state.completed_steps||0}</b></div>`;
			      $('projectSettings').innerHTML=current?`<div>Active project: <b>${esc(current.id)}</b></div><div>Mode: <b>${newProject?'fresh new run':'continue saved project'}</b></div>`:'<div>No active project.</div>';
			      if(!titleTouched) $('projectTitleEdit').value=current?.title||'';
			      $('sandboxStatus').innerHTML=`<div>Active bounds: <b>${s.complete?esc(s.bounds):'incomplete'}</b></div><div>Global sandbox: <b>${state.sandbox.global.exists?'saved':'none'}</b></div><div>Project sandbox: <b>${state.sandbox.project.exists?'saved':'none'}</b></div>`;
			      if(!permissionDirty) syncPermissionSelects(s.permission_mode||'strict');
			      if(s.complete && !boundsTouched){ fillBounds(s.min,s.max); }
			      $('plan').textContent=formatPlan(state.plan||{});
			      renderLogs();
			    }
			    function ensureModelOptions(provider,current){ if(!modelOptions.length){ setModels(current?[current]:[],current); $('modelStatus').innerHTML='<div>Refresh models with your API key before saving.</div>'; } }
			    function setModels(models,selected){ modelOptions=[...new Set((models||[]).filter(Boolean))]; if(selected && !modelOptions.includes(selected)) modelOptions.unshift(selected); $('model').innerHTML=modelOptions.map(m=>`<option value="${esc(m)}">${esc(m)}</option>`).join(''); if(modelOptions.length) $('model').value=selected&&modelOptions.includes(selected)?selected:modelOptions[0]; }
			    function syncPermissionSelects(mode){ $('permissionQuick').value=mode; $('permissionSettings').value=mode; }
			    function currentPermission(){ return $('permissionQuick').value||$('permissionSettings').value||'strict'; }
			    function renderLogs(){ if(!state) return; const entries=[...(state.logs||[]),...localLogs]; $('log').textContent=entries.map(e=>{ const t=e.time?new Date(e.time).toLocaleTimeString():''; const d=e.detail?`\\n${e.detail}`:''; return `[${t}] ${e.message||''}${d}`; }).join('\\n'); $('log').scrollTop=$('log').scrollHeight; }
			    function formatPlan(plan){ const items=plan.plan||[]; if(!items.length) return 'No active plan.'; return items.map(i=>`${i.status||'pending'}  ${i.step||''}`).join('\\n'); }
			    async function selectProject(id){ selectedProject=id; newProject=false; titleTouched=false; render(); try{ await api('/api/project',{action:'select',project_id:id}); await refresh(); }catch(e){ log(e.message); } }
			    async function selectNewProject(){ try{ const result=await api('/api/project',{action:'create',project_id:'',title:''}); selectedProject=result.project.active_project.id; newProject=true; titleTouched=false; await refresh(); }catch(e){ log(e.message); } }
			    function fillBounds(min,max){ $('minX').value=min.x; $('minY').value=min.y; $('minZ').value=min.z; $('maxX').value=max.x; $('maxY').value=max.y; $('maxZ').value=max.z; boundsTouched=false; }
			    function bounds(){ return {min:{x:+$('minX').value,y:+$('minY').value,z:+$('minZ').value},max:{x:+$('maxX').value,y:+$('maxY').value,z:+$('maxZ').value}}; }
			    function loadCurrentBounds(){ if(state?.sandbox?.active_session?.complete) fillBounds(state.sandbox.active_session.min,state.sandbox.active_session.max); }
			    async function refreshModels(){ try{ $('modelStatus').innerHTML='<div>Refreshing provider model list...</div>'; const r=await api('/api/models',{provider:$('provider').value,api_key:$('apiKey').value.trim()}); setModels(r.models,state?.config?.model||''); $('modelStatus').innerHTML=`<div>${esc(r.message)}</div>`; }catch(e){ $('modelStatus').innerHTML=`<div class="bad">${esc(e.message)}</div>`; } }
			    async function saveApiConfig(){ try{ await api('/api/configure',{provider:$('provider').value,model:$('model').value,api_key:$('apiKey').value.trim()}); $('apiKey').value=''; await refresh(); }catch(e){ log(e.message); } }
			    async function savePermission(mode){ try{ permissionDirty=true; syncPermissionSelects(mode); await api('/api/permission',{permission_mode:mode}); permissionDirty=false; await refresh(); }catch(e){ permissionDirty=false; log(e.message); } }
			    async function saveProjectTitle(){ try{ if(!selectedProject){ throw new Error('No project selected.'); } const keepFresh=newProject; const title=$('projectTitleEdit').value.trim(); await api('/api/project',{action:'rename',project_id:selectedProject,title}); newProject=keepFresh; titleTouched=false; await refresh(); }catch(e){ log(e.message); } }
			    async function saveSandbox(scope){ try{ const b=bounds(); await api('/api/sandbox',{scope,source:'manual',...b,permission_mode:currentPermission(),apply:true}); boundsTouched=false; await refresh(); }catch(e){ log(e.message); } }
			    async function startRun(){ try{ const projectId=(selectedProject||state.projects.active_project_id||'').trim(); const editedTitle=$('projectTitleEdit').value.trim(); const title=newProject&&editedTitle&&editedTitle!=='New MineAgent Project'?editedTitle:''; const result=await api('/api/start',{provider:$('provider').value,model:$('model').value,api_key:$('apiKey').value.trim(),project_id:projectId,title,create_project:false,continue_project:!newProject,prompt:$('prompt').value.trim()}); $('apiKey').value=''; $('prompt').value=''; selectedProject=result.project_id||projectId; newProject=false; titleTouched=false; await refresh(); }catch(e){ log(e.message); } }
			    async function stopRun(){ try{ await api('/api/stop',{}); await refresh(); }catch(e){ log(e.message); } }
			    async function approve(){ try{ await api('/api/approve',{}); await refresh(); }catch(e){ log(e.message); } }
			    async function sandboxExpansion(approve){ try{ await api('/api/sandbox-expansion',{approve}); await refresh(); }catch(e){ log(e.message); } }
			    refresh(); setInterval(refresh,2000);
			  </script>
			</body>
			</html>
			""";
}
