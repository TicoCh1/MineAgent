package com.tico.mineagent.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.tico.mineagent.MineAgent;
import com.tico.mineagent.agent.AgentHostMode;
import com.tico.mineagent.agent.AgentHostModes;
import com.tico.mineagent.agent.AgentImageAttachment;
import com.tico.mineagent.agent.AgentRuntime;
import com.tico.mineagent.agent.AgentTool;
import com.tico.mineagent.agent.AgentToolMetadata;
import com.tico.mineagent.agent.AgentToolResult;
import com.tico.mineagent.sandbox.SandboxSession;
import com.tico.mineagent.sandbox.SandboxSessions;

public final class MineAgentMcpServer {
	private static final MineAgentMcpServer INSTANCE = new MineAgentMcpServer();
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final int DEFAULT_PORT = 39321;
	private static final String ENDPOINT = "/mcp";
	private static final String DEFAULT_PROTOCOL_VERSION = "2025-06-18";

	private HttpServer httpServer;
	private ExecutorService httpExecutor;
	private ActiveSession activeSession;
	private int port = DEFAULT_PORT;

	private MineAgentMcpServer() {
	}

	public static MineAgentMcpServer instance() {
		return INSTANCE;
	}

	public synchronized StartResult start(ServerPlayer player, CommandBuildContext registryAccess, int requestedPort) {
		int resolvedPort = requestedPort <= 0 ? DEFAULT_PORT : requestedPort;
		MinecraftServer server = ((ServerLevel) player.level()).getServer();
		activeSession = new ActiveSession(server, player.getUUID(), registryAccess);
		AgentHostModes.set(player, AgentHostMode.EXTERNAL);

		if (httpServer != null && port == resolvedPort) {
			return new StartResult(true, "MineAgent MCP server already running for " + player.getName().getString() + " at " + url(resolvedPort) + ".", resolvedPort, url(resolvedPort));
		}
		if (httpServer != null) {
			stop();
			activeSession = new ActiveSession(server, player.getUUID(), registryAccess);
			AgentHostModes.set(player, AgentHostMode.EXTERNAL);
		}

		try {
			httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", resolvedPort), 0);
			httpServer.createContext(ENDPOINT, this::handle);
			httpExecutor = Executors.newCachedThreadPool(runnable -> {
				Thread thread = new Thread(runnable, "MineAgent MCP HTTP");
				thread.setDaemon(true);
				return thread;
			});
			httpServer.setExecutor(httpExecutor);
			httpServer.start();
			port = resolvedPort;
			MineAgent.LOGGER.info("MineAgent MCP server started at {}", url(resolvedPort));
			return new StartResult(true, "MineAgent MCP server started for " + player.getName().getString() + " at " + url(resolvedPort) + ".", resolvedPort, url(resolvedPort));
		} catch (IOException exception) {
			httpServer = null;
			if (httpExecutor != null) {
				httpExecutor.shutdownNow();
				httpExecutor = null;
			}
			return new StartResult(false, "MineAgent MCP server failed to start on 127.0.0.1:" + resolvedPort + ": " + exception.getMessage(), resolvedPort, url(resolvedPort));
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
			return "external MCP server stopped";
		}
		String target = "no active player";
		ActiveSession session = activeSession;
		if (session != null) {
			ServerPlayer player = session.server().getPlayerList().getPlayer(session.playerId());
			target = player == null ? session.playerId().toString() + " offline" : player.getName().getString();
		}
		return "external MCP server running at " + url(port) + " for " + target;
	}

	private void handle(HttpExchange exchange) throws IOException {
		Headers headers = exchange.getResponseHeaders();
		headers.add("Access-Control-Allow-Origin", "http://localhost");
		headers.add("MCP-Protocol-Version", DEFAULT_PROTOCOL_VERSION);
		if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
			headers.add("Access-Control-Allow-Methods", "POST, OPTIONS");
			headers.add("Access-Control-Allow-Headers", "content-type, mcp-protocol-version");
			exchange.sendResponseHeaders(204, -1);
			return;
		}
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			writePlain(exchange, 405, "MineAgent MCP endpoint accepts JSON-RPC POST requests at " + ENDPOINT + ".");
			return;
		}

		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		JsonElement parsed;
		try {
			parsed = JsonParser.parseString(body);
		} catch (RuntimeException exception) {
			writeJson(exchange, 200, error(null, -32700, "Parse error: " + exception.getMessage()));
			return;
		}

		JsonElement response = parsed.isJsonArray() ? handleBatch(parsed.getAsJsonArray()) : handleOne(parsed);
		if (response == null || response.isJsonNull()) {
			exchange.sendResponseHeaders(202, -1);
			return;
		}
		writeJson(exchange, 200, response);
	}

	private JsonElement handleBatch(JsonArray requests) {
		JsonArray responses = new JsonArray();
		for (JsonElement item : requests) {
			JsonElement response = handleOne(item);
			if (response != null && !response.isJsonNull()) {
				responses.add(response);
			}
		}
		return responses.isEmpty() ? JsonNull.INSTANCE : responses;
	}

	private JsonElement handleOne(JsonElement requestElement) {
		if (requestElement == null || !requestElement.isJsonObject()) {
			return error(null, -32600, "Invalid request.");
		}
		JsonObject request = requestElement.getAsJsonObject();
		JsonElement id = request.has("id") ? request.get("id") : null;
		String method = request.has("method") && request.get("method").isJsonPrimitive() ? request.get("method").getAsString() : "";
		JsonObject params = request.has("params") && request.get("params").isJsonObject() ? request.getAsJsonObject("params") : new JsonObject();
		boolean notification = id == null || id.isJsonNull();
		try {
			JsonObject result = switch (method) {
				case "initialize" -> initialize(params);
				case "ping" -> new JsonObject();
				case "tools/list" -> toolsList();
				case "tools/call" -> toolsCall(params);
				case "resources/list" -> resourcesList();
				case "resources/templates/list" -> resourceTemplatesList();
				case "resources/read" -> resourcesRead(params);
				case "notifications/initialized", "notifications/cancelled" -> null;
				default -> throw new JsonRpcException(-32601, "Method not found: " + method);
			};
			if (notification) {
				return JsonNull.INSTANCE;
			}
			return success(id, result == null ? new JsonObject() : result);
		} catch (JsonRpcException exception) {
			if (notification) {
				return JsonNull.INSTANCE;
			}
			return error(id, exception.code(), exception.getMessage());
		} catch (Exception exception) {
			MineAgent.LOGGER.warn("MineAgent MCP request failed: {}", method, exception);
			if (notification) {
				return JsonNull.INSTANCE;
			}
			return error(id, -32603, exception.getMessage());
		}
	}

	private JsonObject initialize(JsonObject params) {
		JsonObject result = new JsonObject();
		String protocolVersion = params.has("protocolVersion") && params.get("protocolVersion").isJsonPrimitive()
				? params.get("protocolVersion").getAsString()
				: DEFAULT_PROTOCOL_VERSION;
		result.addProperty("protocolVersion", protocolVersion);
		JsonObject capabilities = new JsonObject();
		JsonObject tools = new JsonObject();
		tools.addProperty("listChanged", false);
		capabilities.add("tools", tools);
		JsonObject resources = new JsonObject();
		resources.addProperty("subscribe", false);
		resources.addProperty("listChanged", false);
		capabilities.add("resources", resources);
		result.add("capabilities", capabilities);
		JsonObject serverInfo = new JsonObject();
		serverInfo.addProperty("name", "mineagent");
		serverInfo.addProperty("version", "1.0.0");
		result.add("serverInfo", serverInfo);
		result.addProperty("instructions", "MineAgent exposes Minecraft sandboxed building tools plus small read-only resources. Project/design resources are scoped to the active target player's UUID; hosts should read projects://index and select an active project before design work. Use mineagent_block_palette_query for palette search. The built-in MineAgent host enforces screenshot cadence; external MCP hosts can call capture tools but cadence is not enforced yet.");
		return result;
	}

	private JsonObject toolsList() {
		MineAgentMcpRegistry registry = registry();
		JsonObject result = new JsonObject();
		JsonArray tools = new JsonArray();
		for (AgentTool tool : registry.tools()) {
			AgentToolMetadata metadata = tool.metadata();
			JsonObject item = new JsonObject();
			item.addProperty("name", tool.name());
			item.addProperty("title", metadata.title());
			item.addProperty("description", tool.description());
			item.add("inputSchema", tool.inputSchema());
			JsonObject annotations = new JsonObject();
			annotations.addProperty("title", metadata.title());
			annotations.addProperty("readOnlyHint", metadata.readOnly());
			annotations.addProperty("destructiveHint", metadata.destructive());
			annotations.addProperty("idempotentHint", false);
			annotations.addProperty("openWorldHint", false);
			item.add("annotations", annotations);
			tools.add(item);
		}
		result.add("tools", tools);
		return result;
	}

	private JsonObject toolsCall(JsonObject params) {
		ActiveSession session = requireSession();
		String name = requireString(params, "name");
		JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
				? params.getAsJsonObject("arguments")
				: new JsonObject();
		AgentToolResult result = AgentRuntime.instance().executeExternalMcpToolCall(
				session.server(),
				session.playerId(),
				session.registryAccess(),
				name,
				arguments);

		JsonObject response = new JsonObject();
		response.addProperty("isError", !result.ok());
		JsonArray content = new JsonArray();
		JsonObject text = new JsonObject();
		text.addProperty("type", "text");
		text.addProperty("text", result.outputJson());
		content.add(text);
		for (AgentImageAttachment image : result.images()) {
			JsonObject imageContent = imageContent(image);
			if (imageContent != null) {
				content.add(imageContent);
			}
		}
		response.add("content", content);
		return response;
	}

	private JsonObject resourcesList() {
		MineAgentMcpRegistry registry = registry();
		JsonObject result = new JsonObject();
		JsonArray resources = new JsonArray();
		for (MineAgentMcpResource resource : registry.resources()) {
			JsonObject item = new JsonObject();
			item.addProperty("uri", resource.uri());
			item.addProperty("name", resource.name());
			item.addProperty("title", resource.title());
			item.addProperty("description", resource.description());
			item.addProperty("mimeType", resource.mimeType());
			resources.add(item);
		}
		result.add("resources", resources);
		return result;
	}

	private JsonObject resourceTemplatesList() {
		JsonObject result = new JsonObject();
		JsonArray templates = new JsonArray();
		templates.add(resourceTemplate(
				"design://features/{feature_id}.md",
				"design_feature_file",
				"MineAgent feature markdown file",
				"Read one safe MineAgent feature markdown file from the current player's active-project design/features directory."));
		templates.add(resourceTemplate(
				"design://images/{image_name}.png",
				"design_image_file",
				"MineAgent design image file",
				"Read one safe MineAgent design image from the current player's active-project design/images directory as JSON metadata plus base64."));
		result.add("resourceTemplates", templates);
		return result;
	}

	private JsonObject resourcesRead(JsonObject params) throws Exception {
		String uri = requireString(params, "uri");
		MineAgentMcpResource resource = resourceByUri(uri);
		if (resource == null) {
			throw new JsonRpcException(-32602, "Unknown MineAgent resource URI: " + uri);
		}
		JsonObject data = readResourceOnServerThread(resource);
		JsonObject result = new JsonObject();
		JsonArray contents = new JsonArray();
		JsonObject content = new JsonObject();
		content.addProperty("uri", resource.uri());
		content.addProperty("mimeType", resource.mimeType());
		content.addProperty("text", GSON.toJson(data));
		contents.add(content);
		result.add("contents", contents);
		return result;
	}

	private JsonObject readResourceOnServerThread(MineAgentMcpResource resource) throws Exception {
		ActiveSession session = requireSession();
		CompletableFuture<JsonObject> future = new CompletableFuture<>();
		session.server().execute(() -> {
			try {
				ServerPlayer player = session.server().getPlayerList().getPlayer(session.playerId());
				if (player == null) {
					future.completeExceptionally(new IllegalStateException("MineAgent MCP target player is no longer online."));
					return;
				}
				SandboxSession sandbox = SandboxSessions.get(player);
				future.complete(resource.reader().read(new MineAgentMcpContext(player, sandbox, session.registryAccess())));
			} catch (Exception exception) {
				future.completeExceptionally(exception);
			}
		});
		try {
			return future.get(12, TimeUnit.SECONDS);
		} catch (TimeoutException exception) {
			throw new IllegalStateException("Timed out while reading MineAgent MCP resource " + resource.uri() + ".", exception);
		}
	}

	private MineAgentMcpRegistry registry() {
		ActiveSession session = requireSession();
		return MineAgentMcpRegistry.create(session.registryAccess());
	}

	private MineAgentMcpResource resourceByUri(String uri) {
		for (MineAgentMcpResource resource : registry().resources()) {
			if (resource.uri().equals(uri)) {
				return resource;
			}
		}
		return MineAgentMcpResources.dynamic(uri);
	}

	private static JsonObject resourceTemplate(String uriTemplate, String name, String title, String description) {
		JsonObject object = new JsonObject();
		object.addProperty("uriTemplate", uriTemplate);
		object.addProperty("name", name);
		object.addProperty("title", title);
		object.addProperty("description", description);
		object.addProperty("mimeType", "application/json");
		return object;
	}

	private synchronized ActiveSession requireSession() {
		if (activeSession == null) {
			throw new JsonRpcException(-32002, "MineAgent MCP server has no active player session. Run //mineagent agent mcp start in game first.");
		}
		return activeSession;
	}

	private static JsonObject imageContent(AgentImageAttachment image) {
		try {
			if (!Files.isRegularFile(image.path())) {
				return null;
			}
			JsonObject object = new JsonObject();
			object.addProperty("type", "image");
			object.addProperty("mimeType", image.mediaType());
			object.addProperty("data", Base64.getEncoder().encodeToString(Files.readAllBytes(image.path())));
			return object;
		} catch (IOException exception) {
			MineAgent.LOGGER.debug("Skipping unreadable MineAgent MCP image {}", image.path(), exception);
			return null;
		}
	}

	private static String requireString(JsonObject params, String name) {
		if (!params.has(name) || params.get(name).isJsonNull()) {
			throw new JsonRpcException(-32602, "Missing required parameter: " + name);
		}
		String value = params.get(name).getAsString().trim();
		if (value.isBlank()) {
			throw new JsonRpcException(-32602, "Parameter must not be blank: " + name);
		}
		return value;
	}

	private static JsonObject success(JsonElement id, JsonObject result) {
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id == null ? JsonNull.INSTANCE : id);
		response.add("result", result);
		return response;
	}

	private static JsonObject error(JsonElement id, int code, String message) {
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id == null ? JsonNull.INSTANCE : id);
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message == null ? "MineAgent MCP request failed." : message);
		response.add("error", error);
		return response;
	}

	private static void writeJson(HttpExchange exchange, int statusCode, JsonElement body) throws IOException {
		byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
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

	private static String url(int port) {
		return "http://127.0.0.1:" + port + ENDPOINT;
	}

	public static int defaultPort() {
		return DEFAULT_PORT;
	}

	public record StartResult(boolean ok, String message, int port, String url) {
	}

	private record ActiveSession(MinecraftServer server, UUID playerId, CommandBuildContext registryAccess) {
	}

	private static final class JsonRpcException extends RuntimeException {
		private final int code;

		private JsonRpcException(int code, String message) {
			super(message);
			this.code = code;
		}

		private int code() {
			return code;
		}
	}
}
