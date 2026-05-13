package com.tico.mineagent.agent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;

import com.tico.mineagent.MineAgent;
import com.tico.mineagent.sandbox.SandboxSession;
import com.tico.mineagent.sandbox.SandboxSessions;

public final class AgentRuntime {
	private static final AgentRuntime INSTANCE = new AgentRuntime();
	private static final int DEFAULT_MAX_STEPS = 12;
	private final ExecutorService executor = Executors.newCachedThreadPool(new AgentThreadFactory());
	private final ConcurrentMap<UUID, RunningAgent> runningAgents = new ConcurrentHashMap<>();

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
		return "running " + running.provider().id() + " / " + running.model();
	}

	public void stop(ServerPlayer player) {
		RunningAgent running = runningAgents.remove(player.getUUID());
		if (running != null) {
			running.cancel();
		}
	}

	public StartResult start(ServerPlayer player, AgentProviderType requestedProvider, String prompt, CommandBuildContext registryAccess) {
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

		Future<?> task = executor.submit(() -> runLoop(server, playerId, resolved, prompt, registryAccess, running));
		running.attachTask(task);
		return StartResult.started(resolved.safeSummary());
	}

	private void runLoop(MinecraftServer server, UUID playerId, AgentCredentials credentials, String prompt, CommandBuildContext registryAccess, RunningAgent running) {
		try {
			MineAgentToolRegistry registry = MineAgentToolRegistry.create(registryAccess);
			Map<String, AgentTool> toolsByName = index(registry.tools());
			AgentModelProvider provider = AgentProviders.create(credentials.provider());
			AgentConversation conversation = provider.start(credentials, prompt);
			List<AgentToolResult> pendingResults = List.of();
			int maxSteps = maxSteps();

			for (int step = 1; step <= maxSteps && !running.cancelled(); step++) {
				AgentModelTurn turn = provider.next(conversation, registry.tools(), pendingResults);
				if (!turn.text().isBlank()) {
					send(server, playerId, "MineAgent: " + turn.text().trim());
				}

				if (!turn.hasToolCalls()) {
					send(server, playerId, "MineAgent agent finished.");
					return;
				}

				send(server, playerId, "MineAgent executing " + turn.toolCalls().size() + " tool call(s), step " + step + "/" + maxSteps + ".");
				pendingResults = executeToolCalls(server, playerId, registryAccess, toolsByName, turn.toolCalls());
			}

			if (running.cancelled()) {
				send(server, playerId, "MineAgent agent stopped.");
			} else {
				send(server, playerId, "MineAgent agent stopped after reaching the tool-loop step limit.");
			}
		} catch (Exception exception) {
			if (running.cancelled()) {
				send(server, playerId, "MineAgent agent stopped.");
			} else {
				MineAgent.LOGGER.error("MineAgent agent loop failed", exception);
				send(server, playerId, "MineAgent agent failed: " + exception.getMessage());
			}
		} finally {
			running.finish();
			runningAgents.remove(playerId, running);
		}
	}

	private List<AgentToolResult> executeToolCalls(MinecraftServer server, UUID playerId, CommandBuildContext registryAccess, Map<String, AgentTool> toolsByName, List<AgentToolCall> calls) {
		return calls.stream()
				.map(call -> executeToolCall(server, playerId, registryAccess, toolsByName, call))
				.toList();
	}

	private AgentToolResult executeToolCall(MinecraftServer server, UUID playerId, CommandBuildContext registryAccess, Map<String, AgentTool> toolsByName, AgentToolCall call) {
		AgentTool tool = toolsByName.get(call.name());
		if (tool == null) {
			return AgentToolResult.error(call, "Unknown MineAgent tool: " + call.name());
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

	private static Map<String, AgentTool> index(List<AgentTool> tools) {
		Map<String, AgentTool> indexed = new HashMap<>();
		for (AgentTool tool : tools) {
			indexed.put(tool.name(), tool);
		}
		return Map.copyOf(indexed);
	}

	private static int maxSteps() {
		String raw = System.getenv("MINEAGENT_AGENT_MAX_STEPS");
		if (raw == null || raw.isBlank()) {
			return DEFAULT_MAX_STEPS;
		}
		try {
			return Math.max(1, Integer.parseInt(raw.trim()));
		} catch (NumberFormatException ignored) {
			return DEFAULT_MAX_STEPS;
		}
	}

	private static void send(MinecraftServer server, UUID playerId, String message) {
		server.execute(() -> {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player != null) {
				player.sendSystemMessage(Component.literal(message));
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

		private void attachTask(Future<?> task) {
			this.task = task;
		}

		private void cancel() {
			cancelled.set(true);
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
}
