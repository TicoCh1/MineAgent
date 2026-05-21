package com.tico.mineagent.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.tico.mineagent.network.MineAgentNetworking;
import com.tico.mineagent.network.GpuCaptureRequestPayload;
import com.tico.mineagent.agent.AgentHostMode;
import com.tico.mineagent.agent.AgentHostModes;
import com.tico.mineagent.history.AgentEditRecord;
import com.tico.mineagent.geometry.GeometryEditResult;
import com.tico.mineagent.mcp.MineAgentMcpServer;
import com.tico.mineagent.raycast.RaycastMode;
import com.tico.mineagent.sandbox.SandboxPermissionMode;
import com.tico.mineagent.sandbox.SandboxSelectorType;
import com.tico.mineagent.sandbox.SandboxSession;
import com.tico.mineagent.sandbox.SandboxSessions;
import com.tico.mineagent.web.MineAgentWebHost;

public final class MineAgentCommands {
	private static final SimpleCommandExceptionType INCOMPLETE_SANDBOX = new SimpleCommandExceptionType(
			Component.literal("MineAgent sandbox is incomplete. Use //mineagent tool and select two blocks first."));
	private static final DynamicCommandExceptionType UNKNOWN_DIRECTION = new DynamicCommandExceptionType(
			direction -> Component.literal("Unknown MineAgent direction: " + direction));
	private static final DynamicCommandExceptionType INVALID_DIRECTION_TAIL = new DynamicCommandExceptionType(
			tail -> Component.literal("Expected MineAgent direction syntax: <direction>, <reverse_amount>, or <reverse_amount> <direction>; got: " + tail));
	private static final double WORLD_EDIT_VERTICAL_PITCH_THRESHOLD = 67.5D;
	private static final Map<String, Direction> NAME_TO_DIRECTION_MAP = createDirectionNameMap();
	private static final String[] DIRECTION_SUGGESTIONS = new String[] {
			"me", "forward", "back", "left", "right", "up", "down", "north", "south", "east", "west"
	};
	private static final String[] SANDBOX_PERMISSION_SUGGESTIONS = new String[] {
			"strict", "manual_expand", "auto_expand_air"
	};

	private MineAgentCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			MineAgentNetworking.setCommandBuildContext(registryAccess);
			registerRoot(dispatcher, registryAccess, "/mineagent");
			registerRoot(dispatcher, registryAccess, "mineagent");
		});
	}

	private static void registerRoot(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess, String literal) {
		dispatcher.register(Commands.literal(literal)
				.requires(source -> source.hasPermission(2))
				.executes(context -> startWeb(context, registryAccess, MineAgentWebHost.defaultPort()))
				.then(MineAgentAgentCommands.configure())
				.then(MineAgentAgentCommands.agent(registryAccess))
				.then(web(registryAccess))
				.then(codex(registryAccess))
				.then(Commands.literal("tool")
						.executes(MineAgentCommands::bindTool))
				.then(Commands.literal("sel")
						.executes(MineAgentCommands::showSelector)
						.then(Commands.argument("selector", StringArgumentType.word())
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(SandboxSelectorType.suggestions(), builder))
								.executes(MineAgentCommands::setSelector)))
				.then(Commands.literal("permission")
						.executes(MineAgentCommands::showSandboxPermission)
						.then(Commands.argument("mode", StringArgumentType.word())
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(SANDBOX_PERMISSION_SUGGESTIONS, builder))
								.executes(MineAgentCommands::setSandboxPermission)))
				.then(expandLike("expand", MineAgentCommands::expand, true))
				.then(expandLike("contract", MineAgentCommands::contract, false))
				.then(shift())
				.then(outsetInset("outset", false))
				.then(outsetInset("inset", true))
				.then(raycast())
				.then(gpuCapture())
				.then(history("undo", true))
				.then(history("redo", false))
				.then(MineAgentGeometryCommands.anchor())
				.then(MineAgentGeometryCommands.geometry(registryAccess)));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> expandLike(String name, RegionDirectionOperation operation, boolean supportsVert) {
		LiteralArgumentBuilder<CommandSourceStack> builder = Commands.literal(name);
		if (supportsVert) {
			builder.then(Commands.literal("vert")
					.executes(MineAgentCommands::expandVert));
		}

		return builder.then(Commands.argument("amount", IntegerArgumentType.integer(0))
				.executes(context -> operation.run(
						context,
						IntegerArgumentType.getInteger(context, "amount"),
						0,
						resolveDirections(context, null)))
				.then(Commands.argument("tail", StringArgumentType.greedyString())
						.suggests(MineAgentCommands::suggestDirections)
						.executes(context -> {
							RegionDirectionTail tail = parseRegionDirectionTail(context, StringArgumentType.getString(context, "tail"));
							return operation.run(
									context,
									IntegerArgumentType.getInteger(context, "amount"),
									tail.reverseAmount(),
									tail.directions());
						})));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> shift() {
		return Commands.literal("shift")
				.then(Commands.argument("amount", IntegerArgumentType.integer())
						.executes(context -> shift(
								context,
								IntegerArgumentType.getInteger(context, "amount"),
								resolveDirections(context, null)))
						.then(Commands.argument("direction", StringArgumentType.word())
								.suggests(MineAgentCommands::suggestDirections)
								.executes(context -> shift(
										context,
										IntegerArgumentType.getInteger(context, "amount"),
										resolveDirections(context, StringArgumentType.getString(context, "direction"))))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> outsetInset(String name, boolean inset) {
		return Commands.literal(name)
				.then(Commands.argument("amount", IntegerArgumentType.integer(0))
						.executes(context -> outsetInset(context, IntegerArgumentType.getInteger(context, "amount"), inset, false, false))
						.then(Commands.literal("-h")
								.executes(context -> outsetInset(context, IntegerArgumentType.getInteger(context, "amount"), inset, true, false)))
						.then(Commands.literal("-v")
								.executes(context -> outsetInset(context, IntegerArgumentType.getInteger(context, "amount"), inset, false, true))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> raycast() {
		return Commands.literal("raycast")
				.executes(context -> requestRaycast(context, 70.0D, RaycastMode.SANDBOX))
				.then(Commands.literal(RaycastMode.SANDBOX.id())
						.executes(context -> requestRaycast(context, 70.0D, RaycastMode.SANDBOX)))
				.then(Commands.literal(RaycastMode.FREE.id())
						.executes(context -> requestRaycast(context, 70.0D, RaycastMode.FREE)))
				.then(Commands.argument("fov_degrees", DoubleArgumentType.doubleArg(1.0D, 170.0D))
						.executes(context -> requestRaycast(context, DoubleArgumentType.getDouble(context, "fov_degrees"), RaycastMode.SANDBOX))
						.then(Commands.literal(RaycastMode.SANDBOX.id())
								.executes(context -> requestRaycast(context, DoubleArgumentType.getDouble(context, "fov_degrees"), RaycastMode.SANDBOX)))
						.then(Commands.literal(RaycastMode.FREE.id())
								.executes(context -> requestRaycast(context, DoubleArgumentType.getDouble(context, "fov_degrees"), RaycastMode.FREE))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> gpuCapture() {
		return Commands.literal("capture")
				.then(Commands.literal("isometric")
						.executes(context -> requestIsometricCapture(context, 512, 512, 60.0D))
						.then(Commands.argument("width", IntegerArgumentType.integer(256, 1920))
								.then(Commands.argument("height", IntegerArgumentType.integer(256, 1080))
										.then(Commands.argument("fov_degrees", DoubleArgumentType.doubleArg(30.0D, 90.0D))
												.executes(context -> requestIsometricCapture(
														context,
														IntegerArgumentType.getInteger(context, "width"),
														IntegerArgumentType.getInteger(context, "height"),
														DoubleArgumentType.getDouble(context, "fov_degrees")))))))
				.then(Commands.literal("camera")
						.then(Commands.argument("x", DoubleArgumentType.doubleArg())
								.then(Commands.argument("y", DoubleArgumentType.doubleArg())
										.then(Commands.argument("z", DoubleArgumentType.doubleArg())
												.then(Commands.argument("yaw_degrees", DoubleArgumentType.doubleArg())
														.then(Commands.argument("pitch_degrees", DoubleArgumentType.doubleArg(-90.0D, 90.0D))
																.executes(context -> requestVirtualCameraCapture(context, 512, 512, 60.0D))
																.then(Commands.argument("width", IntegerArgumentType.integer(256, 1920))
																		.then(Commands.argument("height", IntegerArgumentType.integer(256, 1080))
																				.then(Commands.argument("fov_degrees", DoubleArgumentType.doubleArg(30.0D, 90.0D))
																						.executes(context -> requestVirtualCameraCapture(
																								context,
																								IntegerArgumentType.getInteger(context, "width"),
																								IntegerArgumentType.getInteger(context, "height"),
																								DoubleArgumentType.getDouble(context, "fov_degrees"))))))))))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> history(String name, boolean undo) {
		return Commands.literal(name)
				.executes(context -> applyHistory(context, 1, undo))
				.then(Commands.argument("steps", IntegerArgumentType.integer(1))
						.executes(context -> applyHistory(context, IntegerArgumentType.getInteger(context, "steps"), undo)));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> web(CommandBuildContext registryAccess) {
		return Commands.literal("web")
				.executes(context -> startWeb(context, registryAccess, MineAgentWebHost.defaultPort()))
				.then(Commands.literal("start")
						.executes(context -> startWeb(context, registryAccess, MineAgentWebHost.defaultPort()))
						.then(Commands.argument("port", IntegerArgumentType.integer(1024, 65535))
								.executes(context -> startWeb(context, registryAccess, IntegerArgumentType.getInteger(context, "port")))))
				.then(Commands.literal("stop")
						.executes(MineAgentCommands::stopWeb))
				.then(Commands.literal("status")
						.executes(MineAgentCommands::webStatus));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> codex(CommandBuildContext registryAccess) {
		return Commands.literal("codex")
				.executes(context -> startCodexHost(context, registryAccess, MineAgentMcpServer.defaultPort()))
				.then(Commands.argument("port", IntegerArgumentType.integer(1024, 65535))
						.executes(context -> startCodexHost(context, registryAccess, IntegerArgumentType.getInteger(context, "port"))));
	}

	private static int startWeb(CommandContext<CommandSourceStack> context, CommandBuildContext registryAccess) throws CommandSyntaxException {
		return startWeb(context, registryAccess, MineAgentWebHost.defaultPort());
	}

	private static int startWeb(CommandContext<CommandSourceStack> context, CommandBuildContext registryAccess, int port) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		CommandBuildContext resolvedRegistry = registryAccess;
		if (resolvedRegistry == null) {
			player.sendSystemMessage(Component.literal("MineAgent Web UI cannot start yet because command registry context is not ready."));
			return 0;
		}
		MineAgentWebHost.StartResult result = MineAgentWebHost.instance().start(player, resolvedRegistry, port, false);
		MineAgentNetworking.sendAgentLog(player, result.ok() ? "ui" : "error", result.message());
		if (result.ok()) {
			if (result.newlyStarted()) {
				MineAgentNetworking.openWebUi(player, result.url());
				MineAgentNetworking.sendAgentLog(player, "ui", "Open MineAgent Web UI: " + result.url());
			} else {
				MineAgentNetworking.sendAgentLog(player, "warn", "MineAgent Web UI is already open at " + result.url() + ". Use the existing browser tab to avoid duplicate pages.");
			}
		}
		return result.ok() ? 1 : 0;
	}

	private static int startCodexHost(CommandContext<CommandSourceStack> context, CommandBuildContext registryAccess, int port) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		if (registryAccess == null) {
			MineAgentNetworking.sendAgentLog(player, "error", "MineAgent Codex host cannot start yet because command registry context is not ready.");
			return 0;
		}
		MineAgentMcpServer.StartResult result = MineAgentMcpServer.instance().start(player, registryAccess, port);
		MineAgentNetworking.sendAgentLog(player, result.ok() ? "ui" : "error", result.message());
		if (!result.ok()) {
			return 0;
		}
		AgentHostModes.set(player, AgentHostMode.EXTERNAL);
		MineAgentNetworking.sendAgentLog(player, "ok", "MineAgent host mode set to external for Codex MCP.");
		MineAgentNetworking.sendAgentLog(player, "ui", "Add this to Codex config.toml: [mcp_servers.mineagent] url = \"" + result.url() + "\"");
		MineAgentNetworking.sendAgentLog(player, "ui", "Recommended Codex MCP options: enabled = true, default_tools_approval_mode = \"prompt\", tool_timeout_sec = 120.");
		return 1;
	}

	private static int stopWeb(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		MineAgentWebHost.instance().stop();
		MineAgentNetworking.sendAgentLog(player, "ui", "MineAgent Web UI stopped.");
		return 1;
	}

	private static int webStatus(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		MineAgentNetworking.sendAgentLog(player, "ui", "MineAgent Web UI: " + MineAgentWebHost.instance().status());
		return 1;
	}

	private static int showSandboxPermission(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		SandboxPermissionMode mode = SandboxSessions.get(player).permissionMode();
		MineAgentNetworking.sendAgentLog(player, "ui", "MineAgent sandbox permission mode: " + mode.id() + " (" + mode.label() + ").");
		return 1;
	}

	private static int setSandboxPermission(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		String raw = StringArgumentType.getString(context, "mode");
		SandboxPermissionMode mode = SandboxPermissionMode.byId(raw);
		if (mode == null) {
			MineAgentNetworking.sendAgentLog(player, "error", "Unknown MineAgent sandbox permission mode: " + raw);
			return 0;
		}
		SandboxSessions.get(player).setPermissionMode(mode);
		MineAgentNetworking.sendAgentState(player);
		MineAgentNetworking.sendAgentLog(player, "ok", "MineAgent sandbox permission mode set to " + mode.id() + " (" + mode.label() + ").");
		return 1;
	}

	private static int requestRaycast(CommandContext<CommandSourceStack> context, double fovDegrees, RaycastMode mode) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		if (mode == RaycastMode.SANDBOX) {
			requireCompleteSandbox(player);
		}
		MineAgentNetworking.requestClientRaycast(player, fovDegrees, mode);
		return 1;
	}

	private static int requestIsometricCapture(CommandContext<CommandSourceStack> context, int width, int height, double fovDegrees) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		SandboxSession session = requireCompleteSandbox(player);
		MineAgentNetworking.requestClientGpuCapture(player, new GpuCaptureRequestPayload(
				0,
				"sandbox_isometric",
				width,
				height,
				fovDegrees,
				0.0D,
				0.0D,
				0.0D,
				0.0F,
				0.0F,
				session.min(),
				session.max()));
		return 1;
	}

	private static int requestVirtualCameraCapture(CommandContext<CommandSourceStack> context, int width, int height, double fovDegrees) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		MineAgentNetworking.requestClientGpuCapture(player, new GpuCaptureRequestPayload(
				0,
				"virtual_camera",
				width,
				height,
				fovDegrees,
				DoubleArgumentType.getDouble(context, "x"),
				DoubleArgumentType.getDouble(context, "y"),
				DoubleArgumentType.getDouble(context, "z"),
				(float) DoubleArgumentType.getDouble(context, "yaw_degrees"),
				(float) DoubleArgumentType.getDouble(context, "pitch_degrees"),
				BlockPos.ZERO,
				BlockPos.ZERO));
		return 1;
	}

	private static int applyHistory(CommandContext<CommandSourceStack> context, int steps, boolean undo) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		SandboxSession session = SandboxSessions.get(player);
		long candidates = 0L;
		int changed = 0;
		int unchanged = 0;
		int applied = 0;
		for (int i = 0; i < steps; i++) {
			AgentEditRecord record = undo ? session.popUndo() : session.popRedo();
			if (record == null) {
				break;
			}
			GeometryEditResult result = undo ? record.undo((net.minecraft.server.level.ServerLevel) player.level()) : record.redo((net.minecraft.server.level.ServerLevel) player.level());
			if (undo) {
				session.pushRedo(record);
			} else {
				session.pushUndo(record);
			}
			applied++;
			candidates += result.candidates();
			changed += result.changed();
			unchanged += result.unchanged();
			if (result.hasAffectedBounds()) {
				MineAgentNetworking.showAgentEditBounds(player, result.affectedMin(), result.affectedMax(), undo ? "undo" : "redo");
			}
			for (String warning : result.warnings()) {
				player.sendSystemMessage(Component.literal("MineAgent " + (undo ? "undo" : "redo") + " warning: " + warning));
			}
		}
		if (applied == 0) {
			player.sendSystemMessage(Component.literal(undo ? "No MineAgent undo history is available." : "No MineAgent redo history is available."));
			return 0;
		}
		SandboxSessions.sync(player);
		player.sendSystemMessage(Component.literal("MineAgent " + (undo ? "undo" : "redo") + " applied " + applied + " record(s): " + changed + " changed, " + unchanged + " unchanged, " + candidates + " candidates."));
		return applied;
	}

	private static int bindTool(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		SandboxSession session = SandboxSessions.get(player);
		session.setToolEnabled(true);
		ensureTool(player);
		SandboxSessions.sync(player);
		player.sendSystemMessage(Component.literal("MineAgent sandbox tool bound to netherite hoe. In creative mode, netherite hoe is the default MineAgent sandbox tool even without binding. Left click sets point 1, right click sets point 2."));
		return 1;
	}

	private static int showSelector(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		SandboxSession session = SandboxSessions.get(player);
		player.sendSystemMessage(Component.literal("MineAgent sandbox selector: " + session.selectorType().id()));
		return 1;
	}

	private static int setSelector(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		String selector = StringArgumentType.getString(context, "selector");
		SandboxSelectorType type = SandboxSelectorType.byId(selector);
		if (type == null) {
			player.sendSystemMessage(Component.literal("Unsupported MineAgent sandbox selector: " + selector + ". Supported now: cuboid, extend."));
			return 0;
		}

		SandboxSession session = SandboxSessions.get(player);
		session.setSelectorType(type);
		SandboxSessions.sync(player);
		player.sendSystemMessage(Component.literal("MineAgent sandbox selector set to " + type.id() + ". This controls the agent sandbox, not a WorldEdit selection."));
		return 1;
	}

	private static int expandVert(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		SandboxSession session = requireCompleteSandbox(player);
		session.expandVert(player.level().getMinY(), player.level().getMaxY() - 1);
		SandboxSessions.sync(player);
		sendSandboxSummary(player, "Expanded sandbox vertically");
		return 1;
	}

	private static int expand(CommandContext<CommandSourceStack> context, int amount, int reverseAmount, List<Direction> directions) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		SandboxSession session = requireCompleteSandbox(player);
		session.expand(directions, amount, reverseAmount);
		SandboxSessions.sync(player);
		sendSandboxSummary(player, "Expanded sandbox");
		return 1;
	}

	private static int contract(CommandContext<CommandSourceStack> context, int amount, int reverseAmount, List<Direction> directions) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		SandboxSession session = requireCompleteSandbox(player);
		if (!session.contract(directions, amount, reverseAmount)) {
			player.sendSystemMessage(Component.literal("MineAgent sandbox contract rejected because it would invert the sandbox bounds."));
			return 0;
		}
		SandboxSessions.sync(player);
		sendSandboxSummary(player, "Contracted sandbox");
		return 1;
	}

	private static int shift(CommandContext<CommandSourceStack> context, int amount, List<Direction> directions) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		SandboxSession session = requireCompleteSandbox(player);
		session.shift(directions, amount);
		SandboxSessions.sync(player);
		sendSandboxSummary(player, "Shifted sandbox");
		return 1;
	}

	private static int outsetInset(CommandContext<CommandSourceStack> context, int amount, boolean inset, boolean horizontalOnly, boolean verticalOnly) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		SandboxSession session = requireCompleteSandbox(player);

		if (inset) {
			if (!session.inset(amount, horizontalOnly, verticalOnly)) {
				player.sendSystemMessage(Component.literal("MineAgent sandbox inset rejected because it would invert the sandbox bounds."));
				return 0;
			}
		} else {
			session.outset(amount, horizontalOnly, verticalOnly);
		}

		SandboxSessions.sync(player);
		sendSandboxSummary(player, inset ? "Inset sandbox" : "Outset sandbox");
		return 1;
	}

	private static SandboxSession requireCompleteSandbox(ServerPlayer player) throws CommandSyntaxException {
		SandboxSession session = SandboxSessions.get(player);
		if (!session.hasCompleteBounds()) {
			throw INCOMPLETE_SANDBOX.create();
		}
		return session;
	}

	private static void ensureTool(ServerPlayer player) {
		if (player.getInventory().contains(new ItemStack(Items.NETHERITE_HOE))) {
			return;
		}

		player.getInventory().add(new ItemStack(Items.NETHERITE_HOE));
	}

	private static List<Direction> resolveDirections(CommandContext<CommandSourceStack> context, String rawDirection) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		String directionText = rawDirection == null ? "me" : rawDirection;
		String[] directionParts = directionText.split(",");
		List<Direction> directions = new ArrayList<>(directionParts.length);
		for (String directionPart : directionParts) {
			String normalized = directionPart.trim().toLowerCase(Locale.ROOT);
			if (normalized.isEmpty()) {
				throw UNKNOWN_DIRECTION.create(directionText);
			}

			directions.add(resolveDirection(player, normalized));
		}

		return directions;
	}

	private static RegionDirectionTail parseRegionDirectionTail(CommandContext<CommandSourceStack> context, String tail) throws CommandSyntaxException {
		String trimmed = tail.trim();
		if (trimmed.isEmpty()) {
			return new RegionDirectionTail(0, resolveDirections(context, null));
		}

		String[] parts = trimmed.split("\\s+");
		if (parts.length == 1) {
			Integer reverseAmount = tryParseNonNegativeInteger(parts[0]);
			if (reverseAmount != null) {
				return new RegionDirectionTail(reverseAmount, resolveDirections(context, null));
			}

			return new RegionDirectionTail(0, resolveDirections(context, parts[0]));
		}

		if (parts.length == 2) {
			Integer reverseAmount = tryParseNonNegativeInteger(parts[0]);
			if (reverseAmount == null) {
				throw INVALID_DIRECTION_TAIL.create(tail);
			}

			return new RegionDirectionTail(reverseAmount, resolveDirections(context, parts[1]));
		}

		throw INVALID_DIRECTION_TAIL.create(tail);
	}

	private static Integer tryParseNonNegativeInteger(String value) throws CommandSyntaxException {
		try {
			int parsed = Integer.parseInt(value);
			return parsed >= 0 ? parsed : null;
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private static Direction resolveDirection(ServerPlayer player, String directionText) throws CommandSyntaxException {
		Direction byName = NAME_TO_DIRECTION_MAP.get(directionText);
		if (byName != null) {
			return byName;
		}

		return switch (directionText) {
			case "m", "me", "f", "forward" -> getDirectionRelative(player, 0);
			case "b", "back", "backward" -> {
				Direction direction = getDirectionRelative(player, 180);
				if (direction == Direction.UP) {
					yield Direction.DOWN;
				}
				if (direction == Direction.DOWN) {
					yield Direction.UP;
				}
				yield direction;
			}
			case "l", "left" -> getDirectionRelative(player, -90);
			case "r", "right" -> getDirectionRelative(player, 90);
			default -> throw UNKNOWN_DIRECTION.create(directionText);
		};
	}

	private static CompletableFuture<Suggestions> suggestDirections(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(DIRECTION_SUGGESTIONS, builder);
	}

	private static Direction getDirectionRelative(ServerPlayer player, int yawOffset) {
		return getWorldEditCardinalDirection(player, yawOffset);
	}

	/*
	 * Direction names and vertical aim thresholds are adapted from WorldEdit's
	 * WorldEdit#getDirection/getPlayerDirection and AbstractPlayerActor#getCardinalDirection.
	 */
	private static Direction getWorldEditCardinalDirection(ServerPlayer player, int yawOffset) {
		float pitch = player.getXRot();
		if (pitch > WORLD_EDIT_VERTICAL_PITCH_THRESHOLD) {
			return Direction.DOWN;
		}
		if (pitch < -WORLD_EDIT_VERTICAL_PITCH_THRESHOLD) {
			return Direction.UP;
		}

		double rotation = (player.getYRot() + yawOffset) % 360.0D;
		if (rotation < 0.0D) {
			rotation += 360.0D;
		}

		return getHorizontalDirection(rotation);
	}

	private static Direction getHorizontalDirection(double rotation) {
		if (45.0D <= rotation && rotation < 135.0D) {
			return Direction.WEST;
		}
		if (135.0D <= rotation && rotation < 225.0D) {
			return Direction.NORTH;
		}
		if (225.0D <= rotation && rotation < 315.0D) {
			return Direction.EAST;
		}
		return Direction.SOUTH;
	}

	private static Map<String, Direction> createDirectionNameMap() {
		Map<String, Direction> directions = new HashMap<>();
		putDirectionPrefixes(directions, Direction.NORTH, "north");
		putDirectionPrefixes(directions, Direction.SOUTH, "south");
		putDirectionPrefixes(directions, Direction.EAST, "east");
		putDirectionPrefixes(directions, Direction.WEST, "west");
		putDirectionPrefixes(directions, Direction.UP, "up");
		putDirectionPrefixes(directions, Direction.DOWN, "down");
		return Map.copyOf(directions);
	}

	private static void putDirectionPrefixes(Map<String, Direction> directions, Direction direction, String name) {
		for (int length = 1; length <= name.length(); length++) {
			directions.put(name.substring(0, length), direction);
		}
	}

	private static void sendSandboxSummary(ServerPlayer player, String action) {
		SandboxSession session = SandboxSessions.get(player);
		MineAgentNetworking.sendAgentLog(player, "ui", action + ": " + session.boundsSummary());
	}

	@FunctionalInterface
	private interface RegionDirectionOperation {
		int run(CommandContext<CommandSourceStack> context, int amount, int reverseAmount, List<Direction> directions) throws CommandSyntaxException;
	}

	private record RegionDirectionTail(int reverseAmount, List<Direction> directions) {
	}
}
