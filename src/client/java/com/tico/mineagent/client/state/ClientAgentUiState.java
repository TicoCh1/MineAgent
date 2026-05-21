package com.tico.mineagent.client.state;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.Util;
import net.minecraft.resources.ResourceLocation;

import com.tico.mineagent.client.capture.ClientGpuCapture;
import com.tico.mineagent.client.capture.GpuCaptureImageSet;
import com.tico.mineagent.client.gui.MineAgentControlScreen;
import com.tico.mineagent.client.raycast.ClientRaycastCapture;
import com.tico.mineagent.client.raycast.RaycastImageSet;
import com.tico.mineagent.client.util.ClientDeferredTasks;
import com.tico.mineagent.agent.AgentPlanSnapshot;
import com.tico.mineagent.network.AgentClientSyncAckPayload;
import com.tico.mineagent.network.AgentClientSyncRequestPayload;
import com.tico.mineagent.network.AgentSandboxExpansionReplyPayload;
import com.tico.mineagent.network.AgentSandboxPermissionPayload;
import com.tico.mineagent.network.GpuCaptureRequestPayload;
import com.tico.mineagent.network.GpuCaptureResultPayload;
import com.tico.mineagent.network.AgentUiConfigurePayload;
import com.tico.mineagent.network.AgentUiContinuePayload;
import com.tico.mineagent.network.AgentUiLogPayload;
import com.tico.mineagent.network.AgentUiStartPayload;
import com.tico.mineagent.network.AgentUiStatePayload;
import com.tico.mineagent.network.AgentUiStopPayload;
import com.tico.mineagent.network.RaycastRequestPayload;
import com.tico.mineagent.network.RaycastResultPayload;
import com.tico.mineagent.network.WebUiOpenPayload;
import com.tico.mineagent.raycast.RaycastMode;

public final class ClientAgentUiState {
	private static final int MAX_LOG_ENTRIES = 300;
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final List<LogEntry> LOGS = new ArrayList<>();
	private static boolean configured;
	private static String providerId = "openai";
	private static String model = "";
	private static String status = "no running agent";
	private static String currentActivity = "idle";
	private static boolean awaitingApproval;
	private static int completedSteps;
	private static String activeProjectId = "default";
	private static String sandboxPermissionMode = "strict";
	private static boolean awaitingSandboxExpansion;
	private static String sandboxExpansionSummary = "";
	private static AgentPlanSnapshot plan = AgentPlanSnapshot.empty();
	private static int raycastSize = 224;
	private static double raycastFov = 70.0D;
	private static RaycastMode raycastMode = RaycastMode.SANDBOX;
	private static RaycastImageSet raycastResult;
	private static GpuCaptureImageSet gpuCaptureResult;

	private ClientAgentUiState() {
	}

	public static void registerReceivers() {
		ClientPlayNetworking.registerGlobalReceiver(AgentUiStatePayload.ID, (payload, context) -> context.client().execute(() -> {
			apply(payload);
			if (payload.openScreen()) {
				context.client().setScreen(new MineAgentControlScreen());
			}
		}));
		ClientPlayNetworking.registerGlobalReceiver(AgentUiLogPayload.ID, (payload, context) -> context.client().execute(() -> {
			status = payload.status();
			addLog(payload.level(), payload.message(), payload.detail());
		}));
		ClientPlayNetworking.registerGlobalReceiver(WebUiOpenPayload.ID, (payload, context) -> context.client().execute(() -> {
			Util.getPlatform().openUri(payload.url());
			addLog("ui", "MineAgent Web UI opened: " + payload.url());
		}));
		ClientPlayNetworking.registerGlobalReceiver(AgentClientSyncRequestPayload.ID, (payload, context) -> context.client().execute(() -> runClientSyncRequest(payload)));
		ClientPlayNetworking.registerGlobalReceiver(RaycastRequestPayload.ID, (payload, context) -> context.client().execute(() -> runRaycastRequest(payload)));
		ClientPlayNetworking.registerGlobalReceiver(GpuCaptureRequestPayload.ID, (payload, context) -> context.client().execute(() -> runGpuCaptureRequest(payload)));
	}

	public static void sendConfigure(String providerId, String model, String apiKey) {
		if (!ClientPlayNetworking.canSend(AgentUiConfigurePayload.ID)) {
			addLog("error", "Server is not ready to receive MineAgent UI configuration.");
			return;
		}
		ClientPlayNetworking.send(new AgentUiConfigurePayload(providerId, model, apiKey));
	}

	public static void sendStart(String providerId, String model, String apiKey, String projectId, boolean continueProject, String prompt) {
		if (!ClientPlayNetworking.canSend(AgentUiStartPayload.ID)) {
			addLog("error", "Server is not ready to receive MineAgent agent start requests.");
			return;
		}
		ClientPlayNetworking.send(new AgentUiStartPayload(providerId, model, apiKey, projectId, continueProject, prompt));
	}

	public static void sendStop() {
		if (!ClientPlayNetworking.canSend(AgentUiStopPayload.ID)) {
			addLog("error", "Server is not ready to receive MineAgent stop requests.");
			return;
		}
		ClientPlayNetworking.send(new AgentUiStopPayload());
	}

	public static void sendContinue() {
		if (!ClientPlayNetworking.canSend(AgentUiContinuePayload.ID)) {
			addLog("error", "Server is not ready to receive MineAgent continuation approvals.");
			return;
		}
		ClientPlayNetworking.send(new AgentUiContinuePayload());
	}

	public static void sendSandboxPermissionMode(String mode) {
		if (!ClientPlayNetworking.canSend(AgentSandboxPermissionPayload.ID)) {
			addLog("error", "Server is not ready to receive MineAgent sandbox permission changes.");
			return;
		}
		ClientPlayNetworking.send(new AgentSandboxPermissionPayload(mode));
	}

	public static void sendSandboxExpansionReply(boolean approve) {
		if (!ClientPlayNetworking.canSend(AgentSandboxExpansionReplyPayload.ID)) {
			addLog("error", "Server is not ready to receive MineAgent sandbox expansion approvals.");
			return;
		}
		ClientPlayNetworking.send(new AgentSandboxExpansionReplyPayload(approve));
	}

	public static RaycastImageSet runRaycast(int requestedSize, double requestedFov) {
		return runRaycast(requestedSize, requestedFov, raycastMode);
	}

	public static RaycastImageSet runRaycast(int requestedSize, double requestedFov, RaycastMode requestedMode) {
		raycastSize = ClientRaycastCapture.clampSize(requestedSize);
		raycastFov = Math.max(1.0D, Math.min(170.0D, requestedFov));
		raycastMode = requestedMode == null ? RaycastMode.SANDBOX : requestedMode;
		try {
			RaycastImageSet result = ClientRaycastCapture.capture(raycastSize, raycastFov, raycastMode);
			setRaycastResult(result);
			addLog("ui", "%s raycast captured %dx%d at %.1f deg in %d ms (%d hit, %d air, %d out of sandbox pixels).".formatted(
					result.mode(),
					result.size(),
					result.size(),
					result.fovDegrees(),
					result.durationMillis(),
					result.hitPixels(),
					result.airPixels(),
					result.outOfSandboxPixels())
					+ " " + savedFilesSummary(result.localFiles()));
			return result;
		} catch (Exception exception) {
			addLog("error", "MineAgent raycast failed: " + exception.getMessage());
			return null;
		}
	}

	private static void runRaycastRequest(RaycastRequestPayload payload) {
		RaycastMode mode = RaycastMode.byId(payload.mode());
		int requestedSize = payload.resolution() <= 0 ? raycastSize : payload.resolution();
		RaycastImageSet result = runRaycast(requestedSize, payload.fovDegrees(), mode);
		if (payload.requestId() <= 0 || !ClientPlayNetworking.canSend(RaycastResultPayload.ID)) {
			return;
		}

		if (result == null) {
			ClientPlayNetworking.send(new RaycastResultPayload(
					payload.requestId(),
					false,
					mode == null ? RaycastMode.SANDBOX.id() : mode.id(),
					ClientRaycastCapture.clampSize(requestedSize),
					Math.max(1.0D, Math.min(170.0D, payload.fovDegrees())),
					0L,
					0,
					0,
					0,
					0.0D,
					0.0D,
					0.0D,
					"Client raycast failed. See MineAgent UI log for details.",
					""));
			return;
		}

		ClientPlayNetworking.send(new RaycastResultPayload(
				payload.requestId(),
				true,
				result.mode(),
				result.size(),
				result.fovDegrees(),
				result.durationMillis(),
				result.hitPixels(),
				result.airPixels(),
				result.outOfSandboxPixels(),
				result.cameraX(),
				result.cameraY(),
				result.cameraZ(),
				"Raycast captured and displayed in the MineAgent UI. " + savedFilesSummary(result.localFiles()) + " Image pixels are attached to the next model request when this capture was requested by the agent.",
				String.join("\n", result.localFiles())));
	}

	private static void runClientSyncRequest(AgentClientSyncRequestPayload payload) {
		int ticks = Math.max(1, Math.min(40, payload.clientTicks()));
		ClientDeferredTasks.afterClientTicks(ticks, () -> {
			if (ClientPlayNetworking.canSend(AgentClientSyncAckPayload.ID)) {
				ClientPlayNetworking.send(new AgentClientSyncAckPayload(payload.requestId()));
			}
		});
	}

	private static void runGpuCaptureRequest(GpuCaptureRequestPayload payload) {
		ClientGpuCapture.capture(payload).whenComplete((result, throwable) -> {
			Runnable reply = () -> {
				if (throwable != null || result == null) {
					addLog("error", "MineAgent GPU capture failed: " + (throwable == null ? "unknown error" : throwable.getMessage()));
					if (payload.requestId() <= 0 || !ClientPlayNetworking.canSend(GpuCaptureResultPayload.ID)) {
						return;
					}
					ClientPlayNetworking.send(new GpuCaptureResultPayload(
							payload.requestId(),
							false,
							payload.captureType(),
							ClientGpuCapture.clampWidth(payload.width()),
							ClientGpuCapture.clampHeight(payload.height()),
							ClientGpuCapture.clampFov(payload.fovDegrees()),
							0L,
							0,
							"",
							"Client GPU capture failed: " + (throwable == null ? "unknown error" : throwable.getMessage()),
							""));
					return;
				}

				setGpuCaptureResult(result);
				addLog("ui", "%s GPU capture rendered %d image(s) at %dx%d / %.1f deg in %d ms.".formatted(
						result.type(),
						result.images().size(),
						result.width(),
						result.height(),
						result.fovDegrees(),
						result.durationMillis())
						+ " " + savedFilesSummary(gpuLocalFiles(result)));
				if (payload.requestId() <= 0 || !ClientPlayNetworking.canSend(GpuCaptureResultPayload.ID)) {
					return;
				}
				ClientPlayNetworking.send(new GpuCaptureResultPayload(
						payload.requestId(),
						true,
						result.type(),
						result.width(),
						result.height(),
						result.fovDegrees(),
						result.durationMillis(),
						result.images().size(),
						imageLabels(result),
						"Clean GPU capture rendered and displayed in the MineAgent UI with MineAgent overlays suppressed and temporary night-vision normalization. " + savedFilesSummary(gpuLocalFiles(result)) + " Image pixels are attached to the next model request when this capture was requested by the agent.",
						String.join("\n", gpuLocalFiles(result))));
			};
			Minecraft.getInstance().execute(reply);
		});
	}

	public static void addLocal(String message) {
		addLog("ui", message);
	}

	public static boolean configured() {
		return configured;
	}

	public static String providerId() {
		return providerId;
	}

	public static String model() {
		return model;
	}

	public static String status() {
		return status;
	}

	public static String currentActivity() {
		return currentActivity;
	}

	public static boolean awaitingApproval() {
		return awaitingApproval;
	}

	public static int completedSteps() {
		return completedSteps;
	}

	public static String activeProjectId() {
		return activeProjectId;
	}

	public static String sandboxPermissionMode() {
		return sandboxPermissionMode;
	}

	public static boolean awaitingSandboxExpansion() {
		return awaitingSandboxExpansion;
	}

	public static String sandboxExpansionSummary() {
		return sandboxExpansionSummary;
	}

	public static AgentPlanSnapshot plan() {
		return plan;
	}

	public static int raycastSize() {
		return raycastSize;
	}

	public static void setRaycastSize(int size) {
		raycastSize = ClientRaycastCapture.clampSize(size);
	}

	public static double raycastFov() {
		return raycastFov;
	}

	public static void setRaycastFov(double fov) {
		raycastFov = Math.max(1.0D, Math.min(170.0D, fov));
	}

	public static RaycastMode raycastMode() {
		return raycastMode;
	}

	public static void toggleRaycastMode() {
		raycastMode = raycastMode == RaycastMode.SANDBOX ? RaycastMode.FREE : RaycastMode.SANDBOX;
	}

	public static RaycastImageSet raycastResult() {
		return raycastResult;
	}

	public static GpuCaptureImageSet gpuCaptureResult() {
		return gpuCaptureResult;
	}

	public static List<LogEntry> logs() {
		return List.copyOf(LOGS);
	}

	public static void toggleLogEntry(int index) {
		if (index < 0 || index >= LOGS.size()) {
			return;
		}
		LogEntry entry = LOGS.get(index);
		if (!entry.hasDetail()) {
			return;
		}
		LOGS.set(index, entry.withExpanded(!entry.expanded()));
	}

	public static String playerName() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return "player";
		}
		return client.player.getName().getString();
	}

	private static void apply(AgentUiStatePayload payload) {
		configured = payload.configured();
		providerId = payload.providerId().isBlank() ? "openai" : payload.providerId();
		model = payload.model();
		status = payload.status();
		awaitingApproval = payload.awaitingApproval();
		completedSteps = payload.completedSteps();
		activeProjectId = payload.activeProjectId().isBlank() ? "default" : payload.activeProjectId();
		sandboxPermissionMode = payload.sandboxPermissionMode().isBlank() ? "strict" : payload.sandboxPermissionMode();
		awaitingSandboxExpansion = payload.awaitingSandboxExpansion();
		sandboxExpansionSummary = payload.sandboxExpansionSummary();
		plan = AgentPlanSnapshot.fromJsonString(payload.planJson());
		if (payload.openScreen()) {
			addLog("ui", "MineAgent control panel connected.");
		}
	}

	private static void setRaycastResult(RaycastImageSet result) {
		release(raycastResult);
		raycastResult = result;
	}

	private static void setGpuCaptureResult(GpuCaptureImageSet result) {
		release(gpuCaptureResult);
		gpuCaptureResult = result;
	}

	private static void release(RaycastImageSet result) {
		if (result == null) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		release(client, result.colorTexture());
		release(client, result.depthTexture());
		release(client, result.blockIdTexture());
		release(client, result.positionTexture());
	}

	private static void release(GpuCaptureImageSet result) {
		if (result == null) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		for (GpuCaptureImageSet.Image image : result.images()) {
			release(client, image.texture());
		}
	}

	private static void release(Minecraft client, ResourceLocation location) {
		client.getTextureManager().release(location);
	}

	private static String imageLabels(GpuCaptureImageSet result) {
		List<String> labels = new ArrayList<>();
		for (GpuCaptureImageSet.Image image : result.images()) {
			labels.add(image.label());
		}
		return String.join(", ", labels);
	}

	private static List<String> gpuLocalFiles(GpuCaptureImageSet result) {
		List<String> files = new ArrayList<>();
		for (GpuCaptureImageSet.Image image : result.images()) {
			if (image.localFile() != null && !image.localFile().isBlank()) {
				files.add(image.localFile());
			}
		}
		return files;
	}

	private static String savedFilesSummary(List<String> files) {
		if (files.isEmpty()) {
			return "No local capture files were written.";
		}
		String first = files.get(0);
		int separator = Math.max(first.lastIndexOf('\\'), first.lastIndexOf('/'));
		String directory = separator >= 0 ? first.substring(0, separator) : first;
		return "Saved " + files.size() + " image file(s) under " + directory + ".";
	}

	private static void addLog(String level, String message) {
		addLog(level, message, "");
	}

	private static void addLog(String level, String message, String detail) {
		currentActivity = message;
		LOGS.add(new LogEntry(LocalTime.now().format(TIME_FORMAT), level, message, detail == null ? "" : detail, false));
		while (LOGS.size() > MAX_LOG_ENTRIES) {
			LOGS.remove(0);
		}
	}

	public record LogEntry(String time, String level, String message, String detail, boolean expanded) {
		public boolean hasDetail() {
			return !detail.isBlank();
		}

		private LogEntry withExpanded(boolean expanded) {
			return new LogEntry(time, level, message, detail, expanded);
		}
	}
}
