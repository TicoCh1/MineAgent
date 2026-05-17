package com.tico.mineagent.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;

import com.tico.mineagent.MineAgent;
import com.tico.mineagent.network.GpuCaptureRequestPayload;
import com.tico.mineagent.network.GpuCaptureResultPayload;
import com.tico.mineagent.network.MineAgentNetworking;
import com.tico.mineagent.network.RaycastResultPayload;
import com.tico.mineagent.mcp.MineAgentMcpRegistry;
import com.tico.mineagent.mcp.MineAgentMcpResource;
import com.tico.mineagent.raycast.RaycastMode;
import com.tico.mineagent.sandbox.SandboxSession;
import com.tico.mineagent.sandbox.SandboxSessions;

public final class AgentRuntime {
	private static final AgentRuntime INSTANCE = new AgentRuntime();
	private static final int APPROVAL_SEGMENT_STEPS = 64;
	private static final int MAX_BATCH_OPERATIONS = 16;
	private static final int MAX_BATCH_QUERY_CALLS = 16;
	private static final int MAX_OPERATIONS_PER_CAPTURE = 4;
	private static final int CLIENT_WORLD_SETTLE_TICKS_AFTER_EDIT = 6;
	private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
	private final ExecutorService executor = Executors.newCachedThreadPool(new AgentThreadFactory());
	private final ConcurrentMap<UUID, RunningAgent> runningAgents = new ConcurrentHashMap<>();
	private final ConcurrentMap<UUID, Boolean> externalWorldMayNeedSettlement = new ConcurrentHashMap<>();

	private AgentRuntime() {
	}

	public static AgentRuntime instance() {
		return INSTANCE;
	}

	public boolean isRunning(ServerPlayer player) {
		RunningAgent running = runningAgents.get(player.getUUID());
		return running != null && !running.finished();
	}

	public String status(ServerPlayer player) {
		RunningAgent running = runningAgents.get(player.getUUID());
		if (running == null || running.finished()) {
			return "no running agent";
		}
		if (running.awaitingApproval()) {
			return "waiting for approval after " + running.completedSteps() + " steps";
		}
		return "running " + running.provider().id() + " / " + running.model();
	}

	public boolean isAwaitingApproval(ServerPlayer player) {
		RunningAgent running = runningAgents.get(player.getUUID());
		return running != null && running.awaitingApproval();
	}

	public int completedSteps(ServerPlayer player) {
		RunningAgent running = runningAgents.get(player.getUUID());
		return running == null ? 0 : running.completedSteps();
	}

	public boolean approveContinuation(ServerPlayer player) {
		RunningAgent running = runningAgents.get(player.getUUID());
		return running != null && running.approveContinuation();
	}

	public void stop(ServerPlayer player) {
		RunningAgent running = runningAgents.remove(player.getUUID());
		if (running != null) {
			running.cancel();
			MineAgentNetworking.clearAgentEditBounds(player);
		}
		externalWorldMayNeedSettlement.remove(player.getUUID());
	}

	public StartResult start(ServerPlayer player, AgentProviderType requestedProvider, String prompt, CommandBuildContext registryAccess) {
		if (AgentHostModes.get(player) == AgentHostMode.EXTERNAL) {
			return StartResult.failed("MineAgent host mode is external. Use the MCP endpoint from //mineagent agent mcp status, or switch back with //mineagent agent host internal.");
		}

		Optional<AgentCredentials> credentials = AgentConfigStore.resolve(player, requestedProvider);
		if (credentials.isEmpty()) {
			return StartResult.missingConfig();
		}

		SandboxSession sandbox = SandboxSessions.get(player);
		if (!sandbox.hasCompleteBounds()) {
			return StartResult.failed("MineAgent sandbox is incomplete. Use //mineagent tool and select two blocks before starting the agent.");
		}

		UUID playerId = player.getUUID();
		if (runningAgents.containsKey(playerId)) {
			return StartResult.failed("MineAgent agent is already running for you. Use //mineagent agent stop first.");
		}

		MinecraftServer server = ((ServerLevel) player.level()).getServer();
		AgentCredentials resolved = credentials.get();
		RunningAgent running = new RunningAgent(playerId, resolved.provider(), resolved.model());
		RunningAgent existing = runningAgents.putIfAbsent(playerId, running);
		if (existing != null) {
			return StartResult.failed("MineAgent agent is already running for you. Use //mineagent agent stop first.");
		}

		AgentRunLogger runLog;
		try {
			runLog = AgentRunLogger.create(player, resolved, prompt);
			MineAgent.LOGGER.info("MineAgent run {} logging to {}", runLog.runId(), runLog.path().toAbsolutePath());
		} catch (Exception exception) {
			runningAgents.remove(playerId, running);
			return StartResult.failed("MineAgent could not create backend run log: " + exception.getMessage());
		}

		MineAgentNetworking.clearAgentEditBounds(player);
		AgentPlanStates.clear(player);
		Future<?> task = executor.submit(() -> runLoop(server, playerId, resolved, prompt, registryAccess, running, runLog));
		running.attachTask(task);
		return StartResult.started(resolved.safeSummary());
	}

	private void runLoop(MinecraftServer server, UUID playerId, AgentCredentials credentials, String prompt, CommandBuildContext registryAccess, RunningAgent running, AgentRunLogger runLog) {
		String finishStatus = "unknown";
		String finishMessage = "";
		try {
			MineAgentMcpRegistry registry = MineAgentMcpRegistry.create(registryAccess);
			List<AgentTool> modelTools = modelTools(registry.tools());
			Map<String, AgentTool> toolsByName = index(modelTools);
			AgentModelProvider provider = AgentProviders.create(credentials.provider());
			List<AgentImageAttachment> initialImages = captureInitialSandboxIsometric(server, playerId, runLog);
			String initialContext = collectInitialPromptContext(server, playerId, credentials, modelTools, registry.resources(), initialImages);
			runLog.event("prompt_context", promptContextData(initialContext));
			AgentConversation conversation = provider.start(credentials, prompt, initialContext, initialImages);
			List<AgentToolResult> pendingResults = List.of();
			runLog.event("tool_registry", toolRegistryData(modelTools));

			for (int step = 1; !running.cancelled(); step++) {
				if (step > 1 && (step - 1) % APPROVAL_SEGMENT_STEPS == 0 && !waitForContinuation(server, playerId, running, step - 1)) {
					finishStatus = "stopped";
					finishMessage = "Stopped before continuing past " + (step - 1) + " tool-loop steps.";
					send(server, playerId, "MineAgent agent stopped before continuing past " + (step - 1) + " tool-loop steps.");
					return;
				}

				runLog.event("step_start", stepData(step, pendingResults.size()));
				send(server, playerId, "ui", "MineAgent thinking, step " + step + ".");
				AgentModelTurn turn = provider.next(conversation, modelTools, pendingResults, runLog);
				runLog.event("model_turn", modelTurnData(step, turn));
				if (!turn.text().isBlank()) {
					sendDetail(server, playerId, "model", "Model visible reasoning, step " + step + ".", turn.text().trim());
				}

				if (!turn.hasToolCalls()) {
					finishStatus = "finished";
					finishMessage = "Model returned no more tool calls.";
					send(server, playerId, "MineAgent agent finished.");
					return;
				}

				running.setCompletedSteps(step);
				sendDetail(server, playerId, "tool", "MineAgent executing " + turn.toolCalls().size() + " tool call(s), step " + step + ".", toolCallList(turn.toolCalls()));
				pendingResults = executeToolCalls(server, playerId, registryAccess, toolsByName, turn.toolCalls(), runLog);
			}

			if (running.cancelled()) {
				finishStatus = "stopped";
				finishMessage = "Agent cancellation requested.";
				send(server, playerId, "MineAgent agent stopped.");
			} else {
				finishStatus = "stopped";
				finishMessage = "Agent loop exited.";
				send(server, playerId, "MineAgent agent stopped.");
			}
		} catch (Exception exception) {
			if (running.cancelled()) {
				finishStatus = "stopped";
				finishMessage = "Agent stopped while handling exception: " + exception.getMessage();
				send(server, playerId, "MineAgent agent stopped.");
			} else {
				finishStatus = "failed";
				finishMessage = exception.getMessage();
				MineAgent.LOGGER.error("MineAgent agent loop failed", exception);
				send(server, playerId, "MineAgent agent failed: " + exception.getMessage());
			}
		} finally {
			runLog.finish(finishStatus, finishMessage);
			runLog.close();
			clearEditBounds(server, playerId);
			running.finish();
			runningAgents.remove(playerId, running);
		}
	}

	private boolean waitForContinuation(MinecraftServer server, UUID playerId, RunningAgent running, int completedSteps) {
		CompletableFuture<Boolean> approval = running.beginApproval(completedSteps);
		send(server, playerId, "MineAgent paused after " + completedSteps + " tool-loop steps. Approve continuation in the MineAgent UI to allow the next " + APPROVAL_SEGMENT_STEPS + " steps.");
		sendState(server, playerId);
		try {
			while (!running.cancelled()) {
				try {
					return approval.get(1, TimeUnit.SECONDS);
				} catch (TimeoutException ignored) {
					// Keep the agent thread parked without blocking the server thread.
				}
			}
			return false;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return false;
		} catch (Exception exception) {
			return false;
		} finally {
			running.clearApproval();
			sendState(server, playerId);
		}
	}

	private List<AgentImageAttachment> captureInitialSandboxIsometric(MinecraftServer server, UUID playerId, AgentRunLogger runLog) throws Exception {
		CompletableFuture<GpuCaptureResultPayload> future = new CompletableFuture<>();
		server.execute(() -> {
			try {
				ServerPlayer player = server.getPlayerList().getPlayer(playerId);
				if (player == null) {
					future.completeExceptionally(new IllegalStateException("Player is no longer online."));
					return;
				}
				SandboxSession sandbox = SandboxSessions.get(player);
				if (!sandbox.hasCompleteBounds()) {
					future.completeExceptionally(new IllegalStateException("Initial sandbox isometric capture requires a complete MineAgent sandbox."));
					return;
				}
				GpuCaptureRequestPayload request = new GpuCaptureRequestPayload(
						0,
						"sandbox_isometric",
						512,
						512,
						60.0D,
						0.0D,
						0.0D,
						0.0D,
						0.0F,
						0.0F,
						sandbox.min(),
						sandbox.max());
				MineAgentNetworking.requestClientGpuCaptureForAgent(player, request)
						.whenComplete((payload, throwable) -> {
							if (throwable != null) {
								future.completeExceptionally(throwable);
							} else {
								future.complete(payload);
							}
						});
			} catch (Exception exception) {
				future.completeExceptionally(exception);
			}
		});

		GpuCaptureResultPayload payload;
		try {
			payload = future.get(150, TimeUnit.SECONDS);
		} catch (TimeoutException exception) {
			throw new IllegalStateException("Initial sandbox isometric capture timed out after 150 seconds.", exception);
		}
		if (!payload.ok()) {
			throw new IllegalStateException("Initial sandbox isometric capture failed: " + payload.message());
		}

		List<AgentImageAttachment> images = imageAttachments(payload, "initial_sandbox_isometric");
		JsonObject data = gpuCaptureResult(payload, List.of());
		data.addProperty("attached_image_count", images.size());
		data.add("attached_images", attachedImageData(images));
		runLog.event("initial_sandbox_isometric_capture", data);
		if (images.isEmpty()) {
			throw new IllegalStateException("Initial sandbox isometric capture produced no server-readable local image files.");
		}
		sendDetail(server, playerId, "ui", "MineAgent attached initial sandbox isometric context to the first model request.", "Attached " + images.size() + " image(s) from " + payload.localFiles());
		return images;
	}

	private String collectInitialPromptContext(MinecraftServer server, UUID playerId, AgentCredentials credentials, List<AgentTool> tools, List<MineAgentMcpResource> resources, List<AgentImageAttachment> initialImages) throws Exception {
		CompletableFuture<String> future = new CompletableFuture<>();
		server.execute(() -> {
			try {
				ServerPlayer player = server.getPlayerList().getPlayer(playerId);
				if (player == null) {
					future.completeExceptionally(new IllegalStateException("Player is no longer online."));
					return;
				}
				SandboxSession sandbox = SandboxSessions.get(player);
				future.complete(AgentPromptContext.initial(credentials, player, sandbox, tools, resources, initialImages));
			} catch (Exception exception) {
				future.completeExceptionally(exception);
			}
		});
		try {
			return future.get(12, TimeUnit.SECONDS);
		} catch (TimeoutException exception) {
			throw new IllegalStateException("Timed out while collecting initial MineAgent prompt context.", exception);
		}
	}

	private List<AgentToolResult> executeToolCalls(MinecraftServer server, UUID playerId, CommandBuildContext registryAccess, Map<String, AgentTool> toolsByName, List<AgentToolCall> calls, AgentRunLogger runLog) {
		if (hasCountedOperation(calls)) {
			clearEditBounds(server, playerId);
		}
		BatchValidation validation = validateBatch(toolsByName, calls);
		if (!validation.ok()) {
			runLog.event("batch_policy_rejection", batchRejectionData(validation));
			sendDetail(server, playerId, "error", "MineAgent rejected this tool batch before executing edits.", validation.message());
		}

		List<AgentToolResult> results = new ArrayList<>();
		String failedNonQueryTool = null;
		boolean clientWorldMayNeedSettlement = false;
		for (AgentToolCall call : calls) {
			runLog.event("tool_call", toolCallData(call));
			sendDetail(server, playerId, "tool", "Calling tool: " + call.name(), pretty(call.arguments()));

			AgentToolResult result;
			if (!validation.ok() && (!validation.allowQueries() || !isQueryTool(toolsByName, call))) {
				result = AgentToolResult.error(call, "Batch rejected by MineAgent policy before execution: " + validation.message());
			} else if (failedNonQueryTool != null && !isQueryTool(toolsByName, call) && !isHostStateTool(call.name())) {
				result = AgentToolResult.error(call, "Skipped because previous non-query operation failed in this batch: " + failedNonQueryTool + ". Query tools may still run, but edit/capture/session-changing calls after a failure are not executed.");
			} else if (clientWorldMayNeedSettlement && requiresSettledClientWorldBeforeCall(toolsByName, call)) {
				ClientSyncResult sync = waitForClientWorldSettlement(server, playerId, call, runLog);
				clientWorldMayNeedSettlement = !sync.ok();
				if (!sync.ok() && isCaptureTool(call.name())) {
					result = AgentToolResult.error(call, "Skipped capture because the client did not confirm that prior block edits were received/render-settled: " + sync.message());
				} else {
					result = executeToolCall(server, playerId, registryAccess, toolsByName, call);
				}
			} else {
				result = executeToolCall(server, playerId, registryAccess, toolsByName, call);
			}

			runLog.event("tool_result", toolResultData(result));
			if (result.ok() && isWorldChangingTool(call.name())) {
				clientWorldMayNeedSettlement = true;
			}
			if (!result.ok() && failedNonQueryTool == null && !isQueryTool(toolsByName, call) && !isHostStateTool(call.name())) {
				failedNonQueryTool = call.name();
			}
			sendDetail(
					server,
					playerId,
					result.ok() ? "ok" : "error",
					"Tool " + (result.ok() ? "completed: " : "failed: ") + call.name(),
					toolResultUiDetail(result));
			results.add(result);
		}
		return List.copyOf(results);
	}

	public AgentToolResult executeExternalMcpToolCall(MinecraftServer server, UUID playerId, CommandBuildContext registryAccess, String toolName, JsonObject arguments) {
		MineAgentMcpRegistry registry = MineAgentMcpRegistry.create(registryAccess);
		Map<String, AgentTool> toolsByName = index(registry.tools());
		JsonObject safeArguments = arguments == null ? new JsonObject() : arguments.deepCopy();
		AgentToolCall call = new AgentToolCall("mcp-" + UUID.randomUUID(), toolName, safeArguments);
		if (!toolsByName.containsKey(call.name())) {
			return AgentToolResult.error(call, "Unknown MineAgent tool: " + call.name());
		}
		if (isBatchOperationTool(call.name())) {
			clearEditBounds(server, playerId);
		}

		sendDetail(server, playerId, "tool", "External MCP calling tool: " + call.name(), pretty(call.arguments()));
		boolean needsSettlement = Boolean.TRUE.equals(externalWorldMayNeedSettlement.get(playerId));
		AgentToolResult result;
		if (needsSettlement && requiresSettledClientWorldBeforeCall(toolsByName, call)) {
			ClientSyncResult sync = waitForClientWorldSettlement(server, playerId, call, null);
			if (sync.ok()) {
				externalWorldMayNeedSettlement.remove(playerId);
				result = executeToolCall(server, playerId, registryAccess, toolsByName, call);
			} else if (isCaptureTool(call.name())) {
				result = AgentToolResult.error(call, "Skipped capture because the client did not confirm that prior block edits were received/render-settled: " + sync.message());
			} else {
				result = executeToolCall(server, playerId, registryAccess, toolsByName, call);
			}
		} else {
			result = executeToolCall(server, playerId, registryAccess, toolsByName, call);
		}

		if (result.ok() && isWorldChangingTool(call.name())) {
			externalWorldMayNeedSettlement.put(playerId, Boolean.TRUE);
		}
		sendDetail(
				server,
				playerId,
				result.ok() ? "ok" : "error",
				"External MCP tool " + (result.ok() ? "completed: " : "failed: ") + call.name(),
				toolResultUiDetail(result));
		return result;
	}

	private static boolean hasCountedOperation(List<AgentToolCall> calls) {
		for (AgentToolCall call : calls) {
			if (isBatchOperationTool(call.name())) {
				return true;
			}
		}
		return false;
	}

	private static BatchValidation validateBatch(Map<String, AgentTool> toolsByName, List<AgentToolCall> calls) {
		int queryCalls = 0;
		int operations = 0;
		for (AgentToolCall call : calls) {
			if (isQueryTool(toolsByName, call)) {
				queryCalls++;
			} else if (isBatchOperationTool(call.name())) {
				operations++;
			}
		}
		if (queryCalls > MAX_BATCH_QUERY_CALLS) {
			return BatchValidation.error(false, "A MineAgent batch may contain at most " + MAX_BATCH_QUERY_CALLS + " query calls. This batch contained " + queryCalls + ".");
		}
		if (operations > MAX_BATCH_OPERATIONS) {
			return BatchValidation.error(true, "A MineAgent batch may contain at most " + MAX_BATCH_OPERATIONS + " non-query operations. This batch contained " + operations + ".");
		}

		int operationsSinceCapture = 0;
		for (AgentToolCall call : calls) {
			if (isQueryTool(toolsByName, call)) {
				continue;
			}
			if (isCaptureTool(call.name())) {
				if (operationsSinceCapture > 0) {
					operationsSinceCapture = 0;
				}
				continue;
			}
			if (!isBatchOperationTool(call.name())) {
				continue;
			}

			operationsSinceCapture++;
			if (operationsSinceCapture > MAX_OPERATIONS_PER_CAPTURE) {
				return BatchValidation.error(true, "A MineAgent batch may contain at most " + MAX_OPERATIONS_PER_CAPTURE + " non-query operations before a required capture. Append one of " + captureToolNames() + " after each group of 1-4 operations.");
			}
		}

		if (operationsSinceCapture > 0) {
			return BatchValidation.error(true, "This MineAgent batch ends with " + operationsSinceCapture + " non-query operation(s) that are not followed by a required capture. Append one of " + captureToolNames() + " before ending the batch.");
		}
		return BatchValidation.success();
	}

	private static boolean isQueryTool(Map<String, AgentTool> toolsByName, AgentToolCall call) {
		AgentTool tool = toolsByName.get(call.name());
		return tool != null && tool.metadata().queryTool();
	}

	private static boolean isCaptureTool(String toolName) {
		return AgentToolMetadata.forName(toolName).captureTool();
	}

	private static boolean isBatchOperationTool(String toolName) {
		return AgentToolMetadata.forName(toolName).countedOperation();
	}

	private static boolean isHostStateTool(String toolName) {
		return AgentHostToolRegistry.UPDATE_PLAN_TOOL.equals(toolName);
	}

	private static boolean isWorldChangingTool(String toolName) {
		return AgentToolMetadata.forName(toolName).worldChanging();
	}

	private static boolean requiresSettledClientWorldBeforeCall(Map<String, AgentTool> toolsByName, AgentToolCall call) {
		return isCaptureTool(call.name()) || isQueryTool(toolsByName, call);
	}

	private static String captureToolNames() {
		return MineAgentToolRegistry.CLIENT_RAYCAST_TOOL + ", "
				+ MineAgentToolRegistry.CLIENT_VIRTUAL_CAMERA_TOOL + ", or "
				+ MineAgentToolRegistry.CLIENT_SANDBOX_ISOMETRIC_TOOL;
	}

	private ClientSyncResult waitForClientWorldSettlement(MinecraftServer server, UUID playerId, AgentToolCall call, AgentRunLogger runLog) {
		sendDetail(
				server,
				playerId,
				"ui",
				"Waiting for client world/render sync before: " + call.name(),
				"MineAgent is waiting for the client to process prior block updates and a short render-tick buffer before running this observation/query.");

		CompletableFuture<ClientSyncResult> future = new CompletableFuture<>();
		server.execute(() -> {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) {
				future.complete(new ClientSyncResult(false, "Player is no longer online."));
				return;
			}
			MineAgentNetworking.requestClientWorldSyncForAgent(player, CLIENT_WORLD_SETTLE_TICKS_AFTER_EDIT)
					.whenComplete((synced, throwable) -> {
						if (throwable != null) {
							future.complete(new ClientSyncResult(false, throwable.getMessage()));
							return;
						}
						if (!Boolean.TRUE.equals(synced)) {
							future.complete(new ClientSyncResult(false, "Client did not advertise the MineAgent sync barrier channel."));
							return;
						}
						future.complete(new ClientSyncResult(true, "Client acknowledged prior packets after " + CLIENT_WORLD_SETTLE_TICKS_AFTER_EDIT + " client tick(s)."));
					});
		});

		ClientSyncResult result;
		try {
			result = future.get(12, TimeUnit.SECONDS);
		} catch (TimeoutException exception) {
			result = new ClientSyncResult(false, "Timed out after 12 seconds while waiting for client sync.");
		} catch (Exception exception) {
			result = new ClientSyncResult(false, exception.getMessage());
		}

		JsonObject data = new JsonObject();
		data.addProperty("before_tool", call.name());
		data.addProperty("ok", result.ok());
		data.addProperty("message", result.message());
		data.addProperty("client_ticks", CLIENT_WORLD_SETTLE_TICKS_AFTER_EDIT);
		if (runLog != null) {
			runLog.event("client_world_sync", data);
		}
		return result;
	}

	private AgentToolResult executeToolCall(MinecraftServer server, UUID playerId, CommandBuildContext registryAccess, Map<String, AgentTool> toolsByName, AgentToolCall call) {
		AgentTool tool = toolsByName.get(call.name());
		if (tool == null) {
			return AgentToolResult.error(call, "Unknown MineAgent tool: " + call.name());
		}
		if (MineAgentToolRegistry.CLIENT_RAYCAST_TOOL.equals(call.name())) {
			return executeClientRaycastTool(server, playerId, call);
		}
		if (MineAgentToolRegistry.CLIENT_VIRTUAL_CAMERA_TOOL.equals(call.name())
				|| MineAgentToolRegistry.CLIENT_SANDBOX_ISOMETRIC_TOOL.equals(call.name())) {
			return executeClientGpuCaptureTool(server, playerId, call);
		}

		CompletableFuture<AgentToolResult> future = new CompletableFuture<>();
		server.execute(() -> {
			try {
				ServerPlayer player = server.getPlayerList().getPlayer(playerId);
				if (player == null) {
					future.complete(AgentToolResult.error(call, "Player is no longer online."));
					return;
				}

				SandboxSession sandbox = SandboxSessions.get(player);
				AgentToolContext context = new AgentToolContext(player, sandbox, registryAccess);
				AgentToolOutput output = tool.handler().execute(context, call.arguments());
				if (output.ok()) {
					addEditBoundsFromOutput(player, call.name(), output);
				}
				SandboxSessions.sync(player);
				future.complete(AgentToolResult.fromOutput(call, output));
			} catch (Exception exception) {
				future.complete(AgentToolResult.error(call, exception.getMessage()));
			}
		});

		try {
			return future.get(120, TimeUnit.SECONDS);
		} catch (TimeoutException exception) {
			return AgentToolResult.error(call, "Tool timed out after 120 seconds.");
		} catch (Exception exception) {
			return AgentToolResult.error(call, exception.getMessage());
		}
	}

	private AgentToolResult executeClientRaycastTool(MinecraftServer server, UUID playerId, AgentToolCall call) {
		List<String> warnings = new ArrayList<>();
		RaycastMode mode = parseRaycastMode(call.arguments(), warnings);
		int resolution = parseAllowedInt(call.arguments(), "resolution", 256, new int[] { 128, 256, 512 }, warnings);
		double fovDegrees = parseAllowedInt(call.arguments(), "fov_degrees", 75, new int[] { 45, 60, 75, 90 }, warnings);

		CompletableFuture<AgentToolResult> future = new CompletableFuture<>();
		server.execute(() -> {
			try {
				ServerPlayer player = server.getPlayerList().getPlayer(playerId);
				if (player == null) {
					future.complete(AgentToolResult.error(call, "Player is no longer online."));
					return;
				}
				SandboxSession sandbox = SandboxSessions.get(player);
				if (mode == RaycastMode.SANDBOX && !sandbox.hasCompleteBounds()) {
					future.complete(AgentToolResult.error(call, "Sandbox raycast requires a complete MineAgent sandbox."));
					return;
				}

				CompletableFuture<RaycastResultPayload> capture = MineAgentNetworking.requestClientRaycastForAgent(player, resolution, fovDegrees, mode);
				capture.whenComplete((payload, throwable) -> {
					if (throwable != null) {
						future.complete(AgentToolResult.error(call, throwable.getMessage()));
						return;
					}
					if (!payload.ok()) {
						future.complete(AgentToolResult.error(call, payload.message()));
						return;
					}
					future.complete(AgentToolResult.fromOutput(call, AgentToolOutput.ok(raycastResult(payload, warnings)), imageAttachments(payload, "raycast_" + payload.mode())));
				});
			} catch (Exception exception) {
				future.complete(AgentToolResult.error(call, exception.getMessage()));
			}
		});

		try {
			return future.get(120, TimeUnit.SECONDS);
		} catch (TimeoutException exception) {
			return AgentToolResult.error(call, "Client raycast timed out after 120 seconds.");
		} catch (Exception exception) {
			return AgentToolResult.error(call, exception.getMessage());
		}
	}

	private AgentToolResult executeClientGpuCaptureTool(MinecraftServer server, UUID playerId, AgentToolCall call) {
		List<String> warnings = new ArrayList<>();
		int width = parseClampedInt(call.arguments(), "width", 512, 256, 1920, warnings);
		int height = parseClampedInt(call.arguments(), "height", 512, 256, 1080, warnings);
		double fovDegrees = parseClampedDouble(call.arguments(), "fov_degrees", 60.0D, 30.0D, 90.0D, warnings);
		boolean isometric = MineAgentToolRegistry.CLIENT_SANDBOX_ISOMETRIC_TOOL.equals(call.name());
		double cameraX = isometric ? 0.0D : parseDouble(call.arguments(), "x", 0.0D, warnings);
		double cameraY = isometric ? 0.0D : parseDouble(call.arguments(), "y", 0.0D, warnings);
		double cameraZ = isometric ? 0.0D : parseDouble(call.arguments(), "z", 0.0D, warnings);
		float yaw = isometric ? 0.0F : (float) parseDouble(call.arguments(), "yaw_degrees", 0.0D, warnings);
		float pitch = isometric ? 0.0F : (float) parseClampedDouble(call.arguments(), "pitch_degrees", 0.0D, -90.0D, 90.0D, warnings);

		CompletableFuture<AgentToolResult> future = new CompletableFuture<>();
		server.execute(() -> {
			try {
				ServerPlayer player = server.getPlayerList().getPlayer(playerId);
				if (player == null) {
					future.complete(AgentToolResult.error(call, "Player is no longer online."));
					return;
				}
				SandboxSession sandbox = SandboxSessions.get(player);
				if (isometric && !sandbox.hasCompleteBounds()) {
					future.complete(AgentToolResult.error(call, "Sandbox isometric capture requires a complete MineAgent sandbox."));
					return;
				}

				GpuCaptureRequestPayload request = new GpuCaptureRequestPayload(
						0,
						isometric ? "sandbox_isometric" : "virtual_camera",
						width,
						height,
						fovDegrees,
						cameraX,
						cameraY,
						cameraZ,
						yaw,
						pitch,
						isometric ? sandbox.min() : BlockPos.ZERO,
						isometric ? sandbox.max() : BlockPos.ZERO);
				CompletableFuture<GpuCaptureResultPayload> capture = MineAgentNetworking.requestClientGpuCaptureForAgent(player, request);
				capture.whenComplete((payload, throwable) -> {
					if (throwable != null) {
						future.complete(AgentToolResult.error(call, throwable.getMessage()));
						return;
					}
					if (!payload.ok()) {
						future.complete(AgentToolResult.error(call, payload.message()));
						return;
					}
					future.complete(AgentToolResult.fromOutput(call, AgentToolOutput.ok(gpuCaptureResult(payload, warnings)), imageAttachments(payload, payload.captureType())));
				});
			} catch (Exception exception) {
				future.complete(AgentToolResult.error(call, exception.getMessage()));
			}
		});

		try {
			return future.get(150, TimeUnit.SECONDS);
		} catch (TimeoutException exception) {
			return AgentToolResult.error(call, "Client GPU capture timed out after 150 seconds.");
		} catch (Exception exception) {
			return AgentToolResult.error(call, exception.getMessage());
		}
	}

	private static Map<String, AgentTool> index(List<AgentTool> tools) {
		Map<String, AgentTool> indexed = new HashMap<>();
		for (AgentTool tool : tools) {
			indexed.put(tool.name(), tool);
		}
		return Map.copyOf(indexed);
	}

	private static List<AgentTool> modelTools(List<AgentTool> mcpTools) {
		List<AgentTool> tools = new ArrayList<>(AgentHostToolRegistry.tools());
		tools.addAll(mcpTools);
		return List.copyOf(tools);
	}

	private static JsonObject toolRegistryData(List<AgentTool> tools) {
		JsonObject data = new JsonObject();
		data.addProperty("count", tools.size());
		JsonArray array = new JsonArray();
		for (AgentTool tool : tools) {
			AgentToolMetadata metadata = tool.metadata();
			JsonObject item = new JsonObject();
			item.addProperty("name", tool.name());
			item.addProperty("title", metadata.title());
			item.addProperty("category", metadata.category());
			item.addProperty("status", metadata.status());
			item.addProperty("read_only", tool.readOnly());
			item.addProperty("destructive", metadata.destructive());
			item.addProperty("counted_operation", metadata.countedOperation());
			item.addProperty("capture_tool", metadata.captureTool());
			item.addProperty("setup_tool", metadata.setupTool());
			item.addProperty("sandbox_required", metadata.sandboxRequired());
			item.addProperty("world_changing", metadata.worldChanging());
			array.add(item);
		}
		data.add("tools", array);
		return data;
	}

	private static JsonObject promptContextData(String initialContext) {
		JsonObject data = new JsonObject();
		data.addProperty("kind", "initial_run_context");
		data.addProperty("chars", initialContext.length());
		data.addProperty("text", initialContext);
		return data;
	}

	private static JsonObject stepData(int step, int pendingResultCount) {
		JsonObject data = new JsonObject();
		data.addProperty("step", step);
		data.addProperty("pending_tool_results", pendingResultCount);
		return data;
	}

	private static JsonObject modelTurnData(int step, AgentModelTurn turn) {
		JsonObject data = new JsonObject();
		data.addProperty("step", step);
		data.addProperty("visible_text", turn.text());
		data.addProperty("tool_call_count", turn.toolCalls().size());
		JsonArray calls = new JsonArray();
		for (AgentToolCall call : turn.toolCalls()) {
			calls.add(toolCallData(call));
		}
		data.add("tool_calls", calls);
		return data;
	}

	private static JsonObject toolCallData(AgentToolCall call) {
		JsonObject data = new JsonObject();
		data.addProperty("call_id", call.id());
		data.addProperty("tool", call.name());
		data.add("arguments", call.arguments());
		return data;
	}

	private static JsonObject toolResultData(AgentToolResult result) {
		JsonObject data = new JsonObject();
		data.addProperty("call_id", result.callId());
		data.addProperty("tool", result.toolName());
		data.addProperty("ok", result.ok());
		data.addProperty("attached_image_count", result.images().size());
		data.add("attached_images", attachedImageData(result.images()));
		data.add("content", result.content());
		return data;
	}

	private static JsonObject batchRejectionData(BatchValidation validation) {
		JsonObject data = new JsonObject();
		data.addProperty("message", validation.message());
		data.addProperty("allow_queries", validation.allowQueries());
		return data;
	}

	private static JsonArray attachedImageData(List<AgentImageAttachment> images) {
		JsonArray array = new JsonArray();
		for (AgentImageAttachment image : images) {
			JsonObject item = new JsonObject();
			item.addProperty("label", image.label());
			item.addProperty("media_type", image.mediaType());
			item.addProperty("local_path", image.path().toString());
			array.add(item);
		}
		return array;
	}

	private static String toolCallList(List<AgentToolCall> calls) {
		StringBuilder builder = new StringBuilder();
		for (AgentToolCall call : calls) {
			if (!builder.isEmpty()) {
				builder.append('\n');
			}
			builder.append("- ").append(call.name()).append(" call_id=").append(call.id());
		}
		return builder.toString();
	}

	private static String toolResultUiDetail(AgentToolResult result) {
		if (AgentHostToolRegistry.UPDATE_PLAN_TOOL.equals(result.toolName()) && result.ok()) {
			JsonArray plan = result.content().getAsJsonArray("plan");
			int items = plan == null ? 0 : plan.size();
			return "Visible build plan updated with " + items + " item(s).";
		}
		if (!"mineagent_block_palette_query".equals(result.toolName()) || !result.ok()) {
			return result.outputJson();
		}

		JsonObject content = result.content();
		String query = content.has("query") ? content.get("query").getAsString() : "";
		int returned = content.has("returned") ? content.get("returned").getAsInt() : 0;
		List<String> labels = new ArrayList<>();
		JsonArray matches = content.getAsJsonArray("matches");
		if (matches != null) {
			for (int i = 0; i < matches.size(); i++) {
				if (!matches.get(i).isJsonObject()) {
					continue;
				}
				JsonObject match = matches.get(i).getAsJsonObject();
				if (match.has("series_label")) {
					labels.add(match.get("series_label").getAsString());
				} else if (match.has("block_id")) {
					labels.add(match.get("block_id").getAsString());
				}
			}
		}
		return "Palette query \"" + query + "\" returned " + returned + " package(s): " + String.join(", ", labels);
	}

	private static String pretty(com.google.gson.JsonElement element) {
		return PRETTY_GSON.toJson(element);
	}

	private static RaycastMode parseRaycastMode(JsonObject arguments, List<String> warnings) {
		String raw = optionalString(arguments, "mode", RaycastMode.SANDBOX.id());
		RaycastMode mode = RaycastMode.byId(raw);
		if (mode == null) {
			warnings.add("Unsupported raycast mode '" + raw + "'; using sandbox.");
			return RaycastMode.SANDBOX;
		}
		return mode;
	}

	private static int parseAllowedInt(JsonObject arguments, String name, int fallback, int[] allowed, List<String> warnings) {
		int value = fallback;
		if (arguments.has(name) && !arguments.get(name).isJsonNull()) {
			try {
				value = (int) Math.round(arguments.get(name).getAsDouble());
			} catch (RuntimeException exception) {
				warnings.add("Invalid " + name + "; using " + fallback + ".");
				value = fallback;
			}
		} else {
			warnings.add("Missing " + name + "; using " + fallback + ".");
		}

		int closest = allowed[0];
		int bestDistance = Math.abs(value - closest);
		for (int candidate : allowed) {
			int distance = Math.abs(value - candidate);
			if (distance < bestDistance) {
				closest = candidate;
				bestDistance = distance;
			}
		}
		if (closest != value) {
			warnings.add("Adjusted " + name + " from " + value + " to allowed value " + closest + ".");
		}
		return closest;
	}

	private static int parseClampedInt(JsonObject arguments, String name, int fallback, int min, int max, List<String> warnings) {
		int value = fallback;
		if (arguments.has(name) && !arguments.get(name).isJsonNull()) {
			try {
				value = (int) Math.round(arguments.get(name).getAsDouble());
			} catch (RuntimeException exception) {
				warnings.add("Invalid " + name + "; using " + fallback + ".");
				value = fallback;
			}
		} else {
			warnings.add("Missing " + name + "; using " + fallback + ".");
		}
		int clamped = Math.max(min, Math.min(max, value));
		if (clamped != value) {
			warnings.add("Adjusted " + name + " from " + value + " to allowed range " + min + "-" + max + ".");
		}
		return clamped;
	}

	private static double parseClampedDouble(JsonObject arguments, String name, double fallback, double min, double max, List<String> warnings) {
		double value = fallback;
		if (arguments.has(name) && !arguments.get(name).isJsonNull()) {
			try {
				value = arguments.get(name).getAsDouble();
			} catch (RuntimeException exception) {
				warnings.add("Invalid " + name + "; using " + fallback + ".");
				value = fallback;
			}
		} else {
			warnings.add("Missing " + name + "; using " + fallback + ".");
		}
		double clamped = Math.max(min, Math.min(max, value));
		if (Double.compare(clamped, value) != 0) {
			warnings.add("Adjusted " + name + " from " + value + " to allowed range " + min + "-" + max + ".");
		}
		return clamped;
	}

	private static double parseDouble(JsonObject arguments, String name, double fallback, List<String> warnings) {
		if (!arguments.has(name) || arguments.get(name).isJsonNull()) {
			warnings.add("Missing " + name + "; using " + fallback + ".");
			return fallback;
		}
		try {
			return arguments.get(name).getAsDouble();
		} catch (RuntimeException exception) {
			warnings.add("Invalid " + name + "; using " + fallback + ".");
			return fallback;
		}
	}

	private static String optionalString(JsonObject arguments, String name, String fallback) {
		if (!arguments.has(name) || arguments.get(name).isJsonNull()) {
			return fallback;
		}
		try {
			String value = arguments.get(name).getAsString().trim();
			return value.isBlank() ? fallback : value;
		} catch (RuntimeException exception) {
			return fallback;
		}
	}

	private static List<AgentImageAttachment> imageAttachments(RaycastResultPayload payload, String labelPrefix) {
		return imageAttachments(payload.localFiles(), "", labelPrefix);
	}

	private static List<AgentImageAttachment> imageAttachments(GpuCaptureResultPayload payload, String labelPrefix) {
		return imageAttachments(payload.localFiles(), payload.labels(), labelPrefix);
	}

	private static List<AgentImageAttachment> imageAttachments(String localFiles, String labels, String labelPrefix) {
		List<String> paths = splitLines(localFiles);
		List<String> labelParts = splitLabels(labels);
		List<AgentImageAttachment> images = new ArrayList<>();
		for (int i = 0; i < paths.size(); i++) {
			Path path = Path.of(paths.get(i)).toAbsolutePath().normalize();
			if (!Files.isRegularFile(path)) {
				continue;
			}
			String label = i < labelParts.size() ? labelParts.get(i) : "image_" + (i + 1);
			images.add(new AgentImageAttachment(labelPrefix + "_" + label, "image/png", path));
		}
		return List.copyOf(images);
	}

	private static List<String> splitLines(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		return text.lines()
				.map(String::trim)
				.filter(line -> !line.isBlank())
				.toList();
	}

	private static List<String> splitLabels(String labels) {
		if (labels == null || labels.isBlank()) {
			return List.of();
		}
		List<String> parsed = new ArrayList<>();
		for (String label : labels.split(",")) {
			String trimmed = label.trim();
			if (!trimmed.isBlank()) {
				parsed.add(trimmed);
			}
		}
		return List.copyOf(parsed);
	}

	private static JsonArray strings(List<String> values) {
		JsonArray array = new JsonArray();
		for (String value : values) {
			array.add(value);
		}
		return array;
	}

	private static JsonObject raycastResult(RaycastResultPayload payload, List<String> warnings) {
		JsonObject content = new JsonObject();
		content.addProperty("mode", payload.mode());
		content.addProperty("resolution", payload.resolution());
		content.addProperty("fov_degrees", payload.fovDegrees());
		content.addProperty("duration_ms", payload.durationMillis());
		content.addProperty("model_image_pixels_available", true);
		content.addProperty("images_available_in_ui", true);
		content.addProperty("message", payload.message());
		content.addProperty("saved_image_count", splitLines(payload.localFiles()).size());

		JsonObject camera = new JsonObject();
		camera.addProperty("x", payload.cameraX());
		camera.addProperty("y", payload.cameraY());
		camera.addProperty("z", payload.cameraZ());
		content.add("camera", camera);

		JsonObject pixels = new JsonObject();
		pixels.addProperty("hit", payload.hitPixels());
		pixels.addProperty("air", payload.airPixels());
		pixels.addProperty("out_of_sandbox", payload.outOfSandboxPixels());
		content.add("pixels", pixels);

		JsonObject channels = new JsonObject();
		channels.addProperty("color", "textured raycast preview shown in UI only");
		channels.addProperty("depth", "depth preview shown in UI only");
		channels.addProperty("block_id", "block-id color map shown in UI only");
		channels.addProperty("position", "xyz position map shown in UI only");
		content.add("channels", channels);

		JsonArray warningArray = new JsonArray();
		for (String warning : warnings) {
			warningArray.add(warning);
		}
		if (payload.mode().equals(RaycastMode.FREE.id())) {
			warningArray.add("Free raycast is outside sandbox clipping. Prefer sandbox mode for normal agent perception.");
		}
		content.add("warnings", warningArray);
		JsonArray notes = new JsonArray();
		notes.add("Raycast images are attached to the next model request as PNG image inputs when the provider accepts image content.");
		content.add("notes", notes);
		return content;
	}

	private static JsonObject gpuCaptureResult(GpuCaptureResultPayload payload, List<String> warnings) {
		JsonObject content = new JsonObject();
		content.addProperty("type", payload.captureType());
		content.addProperty("width", payload.width());
		content.addProperty("height", payload.height());
		content.addProperty("fov_degrees", payload.fovDegrees());
		content.addProperty("duration_ms", payload.durationMillis());
		content.addProperty("image_count", payload.imageCount());
		content.addProperty("image_labels", payload.labels());
		content.addProperty("model_image_pixels_available", true);
		content.addProperty("images_available_in_ui", true);
		content.addProperty("message", payload.message());
		content.addProperty("saved_image_count", splitLines(payload.localFiles()).size());
		JsonArray warningArray = new JsonArray();
		for (String warning : warnings) {
			warningArray.add(warning);
		}
		content.add("warnings", warningArray);
		JsonArray notes = new JsonArray();
		notes.add("GPU capture images are ordinary rendered screenshots, not raycast multi-channel data.");
		notes.add("GPU capture suppresses MineAgent sandbox/edit-bound overlays and vanilla targeting outline, then applies temporary night-vision normalization before restoring client state.");
		notes.add("GPU capture images are attached to the next model request as PNG image inputs when the provider accepts image content.");
		content.add("notes", notes);
		return content;
	}

	private static void addEditBoundsFromOutput(ServerPlayer player, String toolName, AgentToolOutput output) {
		JsonObject content = output.content();
		if (!content.has("has_affected_bounds") || content.get("has_affected_bounds").isJsonNull()) {
			return;
		}
		if (!content.get("has_affected_bounds").getAsBoolean()) {
			return;
		}

		try {
			JsonObject bounds = content.getAsJsonObject("affected_bounds");
			BlockPos min = readPos(bounds.getAsJsonObject("min"));
			BlockPos max = readPos(bounds.getAsJsonObject("max"));
			MineAgentNetworking.showAgentEditBounds(player, min, max, toolName);
		} catch (RuntimeException exception) {
			MineAgent.LOGGER.debug("MineAgent tool result did not contain a valid affected_bounds object for {}", toolName, exception);
		}
	}

	private static BlockPos readPos(JsonObject object) {
		return new BlockPos(
				object.get("x").getAsInt(),
				object.get("y").getAsInt(),
				object.get("z").getAsInt());
	}

	private static void send(MinecraftServer server, UUID playerId, String message) {
		String level = message.toLowerCase(java.util.Locale.ROOT).contains("failed") ? "error" : "info";
		send(server, playerId, level, message);
	}

	private static void send(MinecraftServer server, UUID playerId, String level, String message) {
		sendDetail(server, playerId, level, message, "");
	}

	private static void sendDetail(MinecraftServer server, UUID playerId, String level, String message, String detail) {
		server.execute(() -> {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player != null) {
				MineAgentNetworking.sendAgentLog(player, level, message, detail);
			}
		});
	}

	private static void sendState(MinecraftServer server, UUID playerId) {
		server.execute(() -> {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player != null) {
				MineAgentNetworking.sendAgentState(player);
			}
		});
	}

	private static void clearEditBounds(MinecraftServer server, UUID playerId) {
		server.execute(() -> {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player != null) {
				MineAgentNetworking.clearAgentEditBounds(player);
			}
		});
	}

	public enum StartStatus {
		STARTED,
		MISSING_CONFIG,
		FAILED
	}

	public record StartResult(StartStatus status, String message) {
		private static StartResult started(String providerSummary) {
			return new StartResult(StartStatus.STARTED, "MineAgent agent started with " + providerSummary + ".");
		}

		private static StartResult missingConfig() {
			return new StartResult(StartStatus.MISSING_CONFIG, "MineAgent API config is missing.");
		}

		private static StartResult failed(String message) {
			return new StartResult(StartStatus.FAILED, message);
		}
	}

	private static final class RunningAgent {
		private final UUID playerId;
		private final AgentProviderType provider;
		private final String model;
		private final AtomicBoolean cancelled = new AtomicBoolean();
		private final AtomicBoolean finished = new AtomicBoolean();
		private final AtomicBoolean awaitingApproval = new AtomicBoolean();
		private volatile CompletableFuture<Boolean> approval;
		private volatile int completedSteps;
		private Future<?> task;

		private RunningAgent(UUID playerId, AgentProviderType provider, String model) {
			this.playerId = playerId;
			this.provider = provider;
			this.model = model;
		}

		private AgentProviderType provider() {
			return provider;
		}

		private String model() {
			return model;
		}

		private boolean cancelled() {
			return cancelled.get();
		}

		private boolean finished() {
			return finished.get();
		}

		private boolean awaitingApproval() {
			return awaitingApproval.get();
		}

		private int completedSteps() {
			return completedSteps;
		}

		private void setCompletedSteps(int completedSteps) {
			this.completedSteps = completedSteps;
		}

		private CompletableFuture<Boolean> beginApproval(int completedSteps) {
			this.completedSteps = completedSteps;
			CompletableFuture<Boolean> nextApproval = new CompletableFuture<>();
			approval = nextApproval;
			awaitingApproval.set(true);
			return nextApproval;
		}

		private boolean approveContinuation() {
			CompletableFuture<Boolean> currentApproval = approval;
			if (currentApproval == null || !awaitingApproval.get()) {
				return false;
			}
			return currentApproval.complete(true);
		}

		private void clearApproval() {
			awaitingApproval.set(false);
			approval = null;
		}

		private void attachTask(Future<?> task) {
			this.task = task;
		}

		private void cancel() {
			cancelled.set(true);
			CompletableFuture<Boolean> currentApproval = approval;
			if (currentApproval != null) {
				currentApproval.complete(false);
			}
			if (task != null) {
				task.cancel(true);
			}
		}

		private void finish() {
			finished.set(true);
		}
	}

	private static final class AgentThreadFactory implements ThreadFactory {
		private int nextId = 1;

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "MineAgent-Agent-" + nextId++);
			thread.setDaemon(true);
			return thread;
		}
	}

	private record BatchValidation(boolean ok, boolean allowQueries, String message) {
		private static BatchValidation success() {
			return new BatchValidation(true, true, "");
		}

		private static BatchValidation error(boolean allowQueries, String message) {
			return new BatchValidation(false, allowQueries, message);
		}
	}

	private record ClientSyncResult(boolean ok, String message) {
	}
}
