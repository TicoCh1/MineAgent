package com.tico.mineagent.network;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import com.tico.mineagent.agent.AgentConfigStore;
import com.tico.mineagent.agent.AgentCredentials;
import com.tico.mineagent.agent.AgentProviderType;
import com.tico.mineagent.agent.AgentRuntime;
import com.tico.mineagent.raycast.RaycastMode;

public final class MineAgentNetworking {
	private static final AtomicInteger NEXT_RAYCAST_REQUEST_ID = new AtomicInteger();
	private static final AtomicInteger NEXT_GPU_CAPTURE_REQUEST_ID = new AtomicInteger();
	private static final AtomicInteger NEXT_CLIENT_SYNC_REQUEST_ID = new AtomicInteger();
	private static final ConcurrentMap<Integer, CompletableFuture<RaycastResultPayload>> PENDING_RAYCASTS = new ConcurrentHashMap<>();
	private static final ConcurrentMap<Integer, CompletableFuture<GpuCaptureResultPayload>> PENDING_GPU_CAPTURES = new ConcurrentHashMap<>();
	private static final ConcurrentMap<Integer, CompletableFuture<Boolean>> PENDING_CLIENT_SYNCS = new ConcurrentHashMap<>();
	private static CommandBuildContext commandBuildContext;

	private MineAgentNetworking() {
	}

	public static void registerPayloads() {
		PayloadTypeRegistry.playS2C().register(SandboxStatePayload.ID, SandboxStatePayload.CODEC);
		PayloadTypeRegistry.playS2C().register(AgentUiStatePayload.ID, AgentUiStatePayload.CODEC);
		PayloadTypeRegistry.playS2C().register(AgentUiLogPayload.ID, AgentUiLogPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(AgentEditBoundsPayload.ID, AgentEditBoundsPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(AgentClientSyncRequestPayload.ID, AgentClientSyncRequestPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(RaycastRequestPayload.ID, RaycastRequestPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(GpuCaptureRequestPayload.ID, GpuCaptureRequestPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(AgentClientSyncAckPayload.ID, AgentClientSyncAckPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(RaycastResultPayload.ID, RaycastResultPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(GpuCaptureResultPayload.ID, GpuCaptureResultPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(AgentUiConfigurePayload.ID, AgentUiConfigurePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(AgentUiStartPayload.ID, AgentUiStartPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(AgentUiStopPayload.ID, AgentUiStopPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(AgentUiContinuePayload.ID, AgentUiContinuePayload.CODEC);
	}

	public static void registerServerReceivers() {
		ServerPlayNetworking.registerGlobalReceiver(AgentUiConfigurePayload.ID, MineAgentNetworking::handleConfigure);
		ServerPlayNetworking.registerGlobalReceiver(AgentUiStartPayload.ID, MineAgentNetworking::handleStart);
		ServerPlayNetworking.registerGlobalReceiver(AgentUiStopPayload.ID, MineAgentNetworking::handleStop);
		ServerPlayNetworking.registerGlobalReceiver(AgentUiContinuePayload.ID, MineAgentNetworking::handleContinue);
		ServerPlayNetworking.registerGlobalReceiver(AgentClientSyncAckPayload.ID, MineAgentNetworking::handleClientSyncAck);
		ServerPlayNetworking.registerGlobalReceiver(RaycastResultPayload.ID, MineAgentNetworking::handleRaycastResult);
		ServerPlayNetworking.registerGlobalReceiver(GpuCaptureResultPayload.ID, MineAgentNetworking::handleGpuCaptureResult);
	}

	public static void setCommandBuildContext(CommandBuildContext registryAccess) {
		commandBuildContext = registryAccess;
	}

	public static void openAgentUi(ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, AgentUiStatePayload.ID)) {
			ServerPlayNetworking.send(player, snapshot(player, true));
			return;
		}

		player.sendSystemMessage(Component.literal("MineAgent UI is unavailable because this client did not register the UI channel."));
	}

	public static void sendAgentLog(ServerPlayer player, String level, String message) {
		sendAgentLog(player, level, message, "");
	}

	public static void sendAgentLog(ServerPlayer player, String level, String message, String detail) {
		player.sendSystemMessage(Component.literal(message));
		if (ServerPlayNetworking.canSend(player, AgentUiLogPayload.ID)) {
			ServerPlayNetworking.send(player, new AgentUiLogPayload(level, message, AgentRuntime.instance().status(player), detail));
		}
	}

	public static void sendAgentState(ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, AgentUiStatePayload.ID)) {
			ServerPlayNetworking.send(player, snapshot(player, false));
		}
	}

	public static void showAgentEditBounds(ServerPlayer player, BlockPos min, BlockPos max, String label) {
		if (ServerPlayNetworking.canSend(player, AgentEditBoundsPayload.ID)) {
			ServerPlayNetworking.send(player, new AgentEditBoundsPayload(true, min, max, label));
		}
	}

	public static void clearAgentEditBounds(ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, AgentEditBoundsPayload.ID)) {
			ServerPlayNetworking.send(player, new AgentEditBoundsPayload(false, BlockPos.ZERO, BlockPos.ZERO, ""));
		}
	}

	public static CompletableFuture<Boolean> requestClientWorldSyncForAgent(ServerPlayer player, int clientTicks) {
		if (!ServerPlayNetworking.canSend(player, AgentClientSyncRequestPayload.ID)) {
			return CompletableFuture.completedFuture(false);
		}

		int requestId = NEXT_CLIENT_SYNC_REQUEST_ID.incrementAndGet();
		CompletableFuture<Boolean> future = new CompletableFuture<>();
		PENDING_CLIENT_SYNCS.put(requestId, future);
		future.orTimeout(10, TimeUnit.SECONDS).whenComplete((result, throwable) -> PENDING_CLIENT_SYNCS.remove(requestId));
		ServerPlayNetworking.send(player, new AgentClientSyncRequestPayload(requestId, clientTicks));
		return future;
	}

	public static void requestClientRaycast(ServerPlayer player, double fovDegrees, RaycastMode mode) {
		if (ServerPlayNetworking.canSend(player, RaycastRequestPayload.ID)) {
			ServerPlayNetworking.send(player, new RaycastRequestPayload(0, 0, fovDegrees, mode));
			sendAgentLog(player, "ui", "MineAgent " + mode.id() + " raycast requested at " + fovDegrees + " degree FOV.");
			return;
		}

		player.sendSystemMessage(Component.literal("MineAgent raycast is unavailable because this client did not register the raycast channel."));
	}

	public static CompletableFuture<RaycastResultPayload> requestClientRaycastForAgent(ServerPlayer player, int resolution, double fovDegrees, RaycastMode mode) {
		if (!ServerPlayNetworking.canSend(player, RaycastRequestPayload.ID)) {
			return failedFuture("MineAgent raycast is unavailable because this client did not register the raycast channel.");
		}

		int requestId = NEXT_RAYCAST_REQUEST_ID.incrementAndGet();
		CompletableFuture<RaycastResultPayload> future = new CompletableFuture<>();
		PENDING_RAYCASTS.put(requestId, future);
		future.orTimeout(90, TimeUnit.SECONDS).whenComplete((result, throwable) -> PENDING_RAYCASTS.remove(requestId));
		ServerPlayNetworking.send(player, new RaycastRequestPayload(requestId, resolution, fovDegrees, mode));
		sendAgentLog(player, "ui", "MineAgent requested " + mode.id() + " raycast for agent at " + resolution + "x" + resolution + " / " + fovDegrees + " degree FOV.");
		return future;
	}

	public static CompletableFuture<GpuCaptureResultPayload> requestClientGpuCaptureForAgent(ServerPlayer player, GpuCaptureRequestPayload request) {
		if (!ServerPlayNetworking.canSend(player, GpuCaptureRequestPayload.ID)) {
			return failedGpuFuture("MineAgent GPU capture is unavailable because this client did not register the GPU capture channel.");
		}

		int requestId = NEXT_GPU_CAPTURE_REQUEST_ID.incrementAndGet();
		CompletableFuture<GpuCaptureResultPayload> future = new CompletableFuture<>();
		PENDING_GPU_CAPTURES.put(requestId, future);
		future.orTimeout(120, TimeUnit.SECONDS).whenComplete((result, throwable) -> PENDING_GPU_CAPTURES.remove(requestId));
		ServerPlayNetworking.send(player, new GpuCaptureRequestPayload(
				requestId,
				request.captureType(),
				request.width(),
				request.height(),
				request.fovDegrees(),
				request.cameraX(),
				request.cameraY(),
				request.cameraZ(),
				request.yawDegrees(),
				request.pitchDegrees(),
				request.sandboxMin(),
				request.sandboxMax()));
		sendAgentLog(player, "ui", "MineAgent requested " + request.captureType() + " GPU capture at " + request.width() + "x" + request.height() + " / " + request.fovDegrees() + " degree FOV.");
		return future;
	}

	public static void requestClientGpuCapture(ServerPlayer player, GpuCaptureRequestPayload request) {
		if (ServerPlayNetworking.canSend(player, GpuCaptureRequestPayload.ID)) {
			ServerPlayNetworking.send(player, new GpuCaptureRequestPayload(
					0,
					request.captureType(),
					request.width(),
					request.height(),
					request.fovDegrees(),
					request.cameraX(),
					request.cameraY(),
					request.cameraZ(),
					request.yawDegrees(),
					request.pitchDegrees(),
					request.sandboxMin(),
					request.sandboxMax()));
			sendAgentLog(player, "ui", "MineAgent " + request.captureType() + " GPU capture requested at " + request.width() + "x" + request.height() + " / " + request.fovDegrees() + " degree FOV.");
			return;
		}

		player.sendSystemMessage(Component.literal("MineAgent GPU capture is unavailable because this client did not register the GPU capture channel."));
	}

	private static void handleConfigure(AgentUiConfigurePayload payload, ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();
		if (!hasMineAgentPermission(player)) {
			sendAgentLog(player, "error", "MineAgent UI request rejected: permission level 2 is required.");
			return;
		}

		AgentProviderType provider = AgentProviderType.byId(payload.providerId().trim());
		String model = payload.model().trim();
		String apiKey = payload.apiKey().trim();
		if (provider == null) {
			sendAgentLog(player, "error", "MineAgent config rejected: provider must be openai or claude.");
			return;
		}
		if (model.isBlank() || apiKey.isBlank()) {
			sendAgentLog(player, "error", "MineAgent config rejected: model and API key are both required.");
			return;
		}

		AgentConfigStore.configure(player, new AgentCredentials(provider, model, apiKey));
		sendAgentLog(player, "ok", "MineAgent configured for this game session: " + provider.id() + " / " + model + ". API key is stored in memory only.");
		sendAgentState(player);
	}

	private static void handleStart(AgentUiStartPayload payload, ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();
		if (!hasMineAgentPermission(player)) {
			sendAgentLog(player, "error", "MineAgent UI request rejected: permission level 2 is required.");
			return;
		}

		CommandBuildContext registryAccess = commandBuildContext;
		if (registryAccess == null) {
			sendAgentLog(player, "error", "MineAgent cannot start yet because command registry context is not ready.");
			return;
		}

		AgentProviderType provider = AgentProviderType.byId(payload.providerId().trim());
		if (provider == null) {
			sendAgentLog(player, "error", "MineAgent start rejected: provider must be openai or claude.");
			return;
		}

		String model = payload.model().trim();
		String apiKey = payload.apiKey().trim();
		if (!apiKey.isBlank()) {
			if (model.isBlank()) {
				sendAgentLog(player, "error", "MineAgent start rejected: model is required when a new API key is supplied.");
				return;
			}
			AgentConfigStore.configure(player, new AgentCredentials(provider, model, apiKey));
		}

		String prompt = payload.prompt().trim();
		if (prompt.isBlank()) {
			sendAgentLog(player, "error", "MineAgent start rejected: prompt must not be blank.");
			return;
		}

		AgentRuntime.StartResult result = AgentRuntime.instance().start(player, provider, prompt, registryAccess);
		String level = result.status() == AgentRuntime.StartStatus.STARTED ? "ok" : "error";
		sendAgentLog(player, level, result.message());
		sendAgentState(player);
	}

	private static void handleStop(AgentUiStopPayload payload, ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();
		if (!hasMineAgentPermission(player)) {
			sendAgentLog(player, "error", "MineAgent UI request rejected: permission level 2 is required.");
			return;
		}

		AgentRuntime.instance().stop(player);
		sendAgentLog(player, "warn", "MineAgent agent stop requested.");
		sendAgentState(player);
	}

	private static void handleContinue(AgentUiContinuePayload payload, ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();
		if (!hasMineAgentPermission(player)) {
			sendAgentLog(player, "error", "MineAgent UI request rejected: permission level 2 is required.");
			return;
		}

		if (AgentRuntime.instance().approveContinuation(player)) {
			sendAgentLog(player, "ok", "MineAgent continuation approved for the next 64 tool-loop steps.");
		} else {
			sendAgentLog(player, "warn", "MineAgent has no pending continuation request.");
		}
		sendAgentState(player);
	}

	private static void handleClientSyncAck(AgentClientSyncAckPayload payload, ServerPlayNetworking.Context context) {
		CompletableFuture<Boolean> future = PENDING_CLIENT_SYNCS.remove(payload.requestId());
		if (future != null) {
			future.complete(true);
		}
	}

	private static void handleRaycastResult(RaycastResultPayload payload, ServerPlayNetworking.Context context) {
		CompletableFuture<RaycastResultPayload> future = PENDING_RAYCASTS.get(payload.requestId());
		if (future != null) {
			future.complete(payload);
		}
	}

	private static void handleGpuCaptureResult(GpuCaptureResultPayload payload, ServerPlayNetworking.Context context) {
		CompletableFuture<GpuCaptureResultPayload> future = PENDING_GPU_CAPTURES.get(payload.requestId());
		if (future != null) {
			future.complete(payload);
		}
	}

	private static boolean hasMineAgentPermission(ServerPlayer player) {
		return player.createCommandSourceStack().hasPermission(2);
	}

	private static AgentUiStatePayload snapshot(ServerPlayer player, boolean openScreen) {
		Optional<AgentCredentials> configured = AgentConfigStore.configured(player);
		String providerId = configured.map(credentials -> credentials.provider().id()).orElse("openai");
		String model = configured.map(AgentCredentials::model).orElse("");
		return new AgentUiStatePayload(
				openScreen,
				configured.isPresent(),
				providerId,
				model,
				AgentRuntime.instance().status(player),
				AgentRuntime.instance().isAwaitingApproval(player),
				AgentRuntime.instance().completedSteps(player));
	}

	private static CompletableFuture<RaycastResultPayload> failedFuture(String message) {
		CompletableFuture<RaycastResultPayload> future = new CompletableFuture<>();
		future.completeExceptionally(new IllegalStateException(message));
		return future;
	}

	private static CompletableFuture<GpuCaptureResultPayload> failedGpuFuture(String message) {
		CompletableFuture<GpuCaptureResultPayload> future = new CompletableFuture<>();
		future.completeExceptionally(new IllegalStateException(message));
		return future;
	}
}
