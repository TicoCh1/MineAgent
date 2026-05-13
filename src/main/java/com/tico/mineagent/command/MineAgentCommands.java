package com.tico.mineagent.command;

import com.mojang.brigadier.CommandDispatcher;
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
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.tico.mineagent.sandbox.SandboxSelectorType;
import com.tico.mineagent.sandbox.SandboxSession;
import com.tico.mineagent.sandbox.SandboxSessions;

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

	private MineAgentCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			registerRoot(dispatcher, registryAccess, "/mineagent");
			registerRoot(dispatcher, registryAccess, "mineagent");
		});
	}

	private static void registerRoot(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess, String literal) {
		dispatcher.register(Commands.literal(literal)
				.requires(source -> source.hasPermission(2))
				.executes(MineAgentCommands::startPlaceholder)
				.then(MineAgentAgentCommands.configure())
				.then(MineAgentAgentCommands.agent(registryAccess))
				.then(Commands.literal("tool")
						.executes(MineAgentCommands::bindTool))
				.then(Commands.literal("sel")
						.executes(MineAgentCommands::showSelector)
						.then(Commands.argument("selector", StringArgumentType.word())
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(SandboxSelectorType.suggestions(), builder))
								.executes(MineAgentCommands::setSelector)))
				.then(expandLike("expand", MineAgentCommands::expand, true))
				.then(expandLike("contract", MineAgentCommands::contract, false))
				.then(shift())
				.then(outsetInset("outset", false))
				.then(outsetInset("inset", true))
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

	private static int startPlaceholder(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return MineAgentAgentCommands.showSetupHint(context);
	}

	private static int bindTool(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		SandboxSession session = SandboxSessions.get(player);
		session.setToolEnabled(true);
		ensureTool(player);
		SandboxSessions.sync(player);
		player.sendSystemMessage(Component.literal("MineAgent sandbox tool bound to netherite hoe. Left click sets point 1, right click sets point 2."));
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
		player.sendSystemMessage(Component.literal(action + ": " + session.boundsSummary()));
	}

	@FunctionalInterface
	private interface RegionDirectionOperation {
		int run(CommandContext<CommandSourceStack> context, int amount, int reverseAmount, List<Direction> directions) throws CommandSyntaxException;
	}

	private record RegionDirectionTail(int reverseAmount, List<Direction> directions) {
	}
}
