package com.tico.mineagent.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.tico.mineagent.geometry.GeometryAxisDirection;
import com.tico.mineagent.geometry.GeometryEditResult;
import com.tico.mineagent.geometry.GeometryEditService;
import com.tico.mineagent.geometry.GeometryParameterParser;
import com.tico.mineagent.geometry.GeometryParameterParser.ParsedBlockPos;
import com.tico.mineagent.geometry.GeometryParameterParser.ParsedDouble;
import com.tico.mineagent.geometry.GeometryParameterParser.ParsedInt;
import com.tico.mineagent.network.MineAgentNetworking;
import com.tico.mineagent.sandbox.SandboxSession;
import com.tico.mineagent.sandbox.SandboxSessions;

public final class MineAgentGeometryCommands {
	private static final SimpleCommandExceptionType INCOMPLETE_SANDBOX = new SimpleCommandExceptionType(
			Component.literal("MineAgent sandbox is incomplete. Use //mineagent tool and select two blocks first."));
	private static final SimpleCommandExceptionType SAME_BOX_CORNERS = new SimpleCommandExceptionType(
			Component.literal("MineAgent geometry requires two non-identical corner coordinates."));
	private static final DynamicCommandExceptionType INVALID_AXIS = new DynamicCommandExceptionType(
			axis -> Component.literal("Invalid MineAgent cylinder axis: " + axis + ". Use +x, -x, +y, -y, +z, or -z."));
	private static final DynamicCommandExceptionType INVALID_ANCHOR_NAME = new DynamicCommandExceptionType(
			name -> Component.literal("Invalid MineAgent anchor name: " + name + ". Use letters, digits, or underscore."));

	private MineAgentGeometryCommands() {
	}

	public static LiteralArgumentBuilder<CommandSourceStack> anchor() {
		return Commands.literal("anchor")
				.then(Commands.literal("set")
						.then(Commands.argument("name", StringArgumentType.word())
								.then(Commands.argument("pos", StringArgumentType.word())
										.suggests(MineAgentGeometryCommands::suggestAnchors)
										.executes(MineAgentGeometryCommands::setAnchor))))
				.then(Commands.literal("list")
						.executes(MineAgentGeometryCommands::listAnchors));
	}

	public static LiteralArgumentBuilder<CommandSourceStack> geometry(CommandBuildContext registryAccess) {
		return Commands.literal("gen")
				.then(box(registryAccess))
				.then(ellipsoid("sphere", registryAccess))
				.then(ellipsoid("ellipsoid", registryAccess))
				.then(cylinder("cylinder", registryAccess))
				.then(cylinder("cyl", registryAccess));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> box(CommandBuildContext registryAccess) {
		return Commands.literal("box")
				.then(Commands.literal("corners")
						.then(Commands.argument("pos1", StringArgumentType.word())
								.suggests(MineAgentGeometryCommands::suggestAnchors)
								.then(Commands.argument("pos2", StringArgumentType.word())
										.suggests(MineAgentGeometryCommands::suggestAnchors)
										.then(Commands.argument("block", BlockStateArgument.block(registryAccess))
												.executes(MineAgentGeometryCommands::boxCorners)))))
				.then(Commands.literal("origin")
						.then(Commands.argument("origin", StringArgumentType.word())
								.suggests(MineAgentGeometryCommands::suggestAnchors)
								.then(Commands.argument("sizeX", StringArgumentType.word())
										.then(Commands.argument("sizeY", StringArgumentType.word())
												.then(Commands.argument("sizeZ", StringArgumentType.word())
														.then(Commands.argument("block", BlockStateArgument.block(registryAccess))
																.executes(MineAgentGeometryCommands::boxOrigin)))))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> ellipsoid(String name, CommandBuildContext registryAccess) {
		return Commands.literal(name)
				.then(Commands.literal("center")
						.then(Commands.argument("center", StringArgumentType.word())
								.suggests(MineAgentGeometryCommands::suggestAnchors)
								.then(Commands.argument("radiusX", StringArgumentType.word())
										.then(Commands.argument("radiusY", StringArgumentType.word())
												.then(Commands.argument("radiusZ", StringArgumentType.word())
														.then(Commands.argument("block", BlockStateArgument.block(registryAccess))
																.executes(MineAgentGeometryCommands::ellipsoidCenter)))))))
				.then(Commands.literal("box")
						.then(Commands.argument("pos1", StringArgumentType.word())
								.suggests(MineAgentGeometryCommands::suggestAnchors)
								.then(Commands.argument("pos2", StringArgumentType.word())
										.suggests(MineAgentGeometryCommands::suggestAnchors)
										.then(Commands.argument("block", BlockStateArgument.block(registryAccess))
												.executes(MineAgentGeometryCommands::ellipsoidInBox)))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> cylinder(String name, CommandBuildContext registryAccess) {
		return Commands.literal(name)
				.then(Commands.argument("center", StringArgumentType.word())
						.suggests(MineAgentGeometryCommands::suggestAnchors)
						.then(Commands.argument("axis", StringArgumentType.word())
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(GeometryAxisDirection.suggestions(), builder))
								.then(Commands.argument("height", StringArgumentType.word())
										.then(Commands.argument("radius", StringArgumentType.word())
												.then(Commands.argument("block", BlockStateArgument.block(registryAccess))
														.executes(MineAgentGeometryCommands::cylinder))))));
	}

	private static int setAnchor(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		SandboxSession session = SandboxSessions.get(player);
		String name = StringArgumentType.getString(context, "name");
		if (!name.matches("[A-Za-z0-9_]+")) {
			throw INVALID_ANCHOR_NAME.create(name);
		}

		ParsedBlockPos pos = GeometryParameterParser.parseBlockPos(context.getSource(), session, StringArgumentType.getString(context, "pos"));
		session.setAnchor(name, pos.pos());
		sendWarnings(player, pos.warnings());
		MineAgentNetworking.sendAgentLog(player, "ui", "MineAgent anchor @" + name.toLowerCase() + " set to " + format(pos.pos()) + ".");
		return 1;
	}

	private static int listAnchors(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		SandboxSession session = SandboxSessions.get(player);
		if (session.anchors().isEmpty()) {
			MineAgentNetworking.sendAgentLog(player, "ui", "MineAgent has no coordinate anchors.");
			return 1;
		}

		for (Map.Entry<String, BlockPos> entry : session.anchors().entrySet()) {
			MineAgentNetworking.sendAgentLog(player, "ui", "@" + entry.getKey() + " = " + format(entry.getValue()));
		}
		return session.anchors().size();
	}

	private static int boxCorners(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		SandboxSession session = requireCompleteSandbox(player);
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos pos1 = parsePos(context, session, "pos1", warnings);
		ParsedBlockPos pos2 = parsePos(context, session, "pos2", warnings);
		if (pos1.pos().equals(pos2.pos())) {
			throw SAME_BOX_CORNERS.create();
		}

		GeometryEditResult result = GeometryEditService.fillBox(level(player), session, block(context), pos1.pos(), pos2.pos(), warnings);
		sendResult(player, "Generated box", result);
		return result.changed();
	}

	private static int boxOrigin(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		SandboxSession session = requireCompleteSandbox(player);
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos origin = parsePos(context, session, "origin", warnings);
		int sizeX = parseSize(context, "sizeX", warnings);
		int sizeY = parseSize(context, "sizeY", warnings);
		int sizeZ = parseSize(context, "sizeZ", warnings);

		GeometryEditResult result = GeometryEditService.fillBoxFromOrigin(level(player), session, block(context), origin.pos(), sizeX, sizeY, sizeZ, warnings);
		sendResult(player, "Generated box", result);
		return result.changed();
	}

	private static int ellipsoidCenter(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		SandboxSession session = requireCompleteSandbox(player);
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos center = parsePos(context, session, "center", warnings);
		double radiusX = parseNonNegativeDouble(context, "radiusX", warnings);
		double radiusY = parseNonNegativeDouble(context, "radiusY", warnings);
		double radiusZ = parseNonNegativeDouble(context, "radiusZ", warnings);

		GeometryEditResult result = GeometryEditService.makeEllipsoid(level(player), session, block(context), center.pos().getX(), center.pos().getY(), center.pos().getZ(), radiusX, radiusY, radiusZ, warnings);
		sendResult(player, "Generated ellipsoid", result);
		return result.changed();
	}

	private static int ellipsoidInBox(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		SandboxSession session = requireCompleteSandbox(player);
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos pos1 = parsePos(context, session, "pos1", warnings);
		ParsedBlockPos pos2 = parsePos(context, session, "pos2", warnings);
		if (pos1.pos().equals(pos2.pos())) {
			throw SAME_BOX_CORNERS.create();
		}

		GeometryEditResult result = GeometryEditService.makeEllipsoidInBox(level(player), session, block(context), pos1.pos(), pos2.pos(), warnings);
		sendResult(player, "Generated inscribed ellipsoid", result);
		return result.changed();
	}

	private static int cylinder(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		SandboxSession session = requireCompleteSandbox(player);
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos center = parsePos(context, session, "center", warnings);
		GeometryAxisDirection axis = GeometryAxisDirection.byId(StringArgumentType.getString(context, "axis"));
		if (axis == null) {
			throw INVALID_AXIS.create(StringArgumentType.getString(context, "axis"));
		}

		int height = parseHeight(context, warnings);
		double radius = parseCylinderRadius(context, warnings);
		GeometryEditResult result = GeometryEditService.makeCylinder(level(player), session, block(context), center.pos(), axis, height, radius, warnings);
		sendResult(player, "Generated cylinder", result);
		return result.changed();
	}

	private static SandboxSession requireCompleteSandbox(ServerPlayer player) throws CommandSyntaxException {
		SandboxSession session = SandboxSessions.get(player);
		if (!session.hasCompleteBounds()) {
			throw INCOMPLETE_SANDBOX.create();
		}
		return session;
	}

	private static ParsedBlockPos parsePos(CommandContext<CommandSourceStack> context, SandboxSession session, String name, List<String> warnings) throws CommandSyntaxException {
		ParsedBlockPos pos = GeometryParameterParser.parseBlockPos(context.getSource(), session, StringArgumentType.getString(context, name));
		warnings.addAll(pos.warnings());
		return pos;
	}

	private static int parseSize(CommandContext<CommandSourceStack> context, String name, List<String> warnings) throws CommandSyntaxException {
		ParsedInt parsed = GeometryParameterParser.parseSignedSize(StringArgumentType.getString(context, name), name);
		warnings.addAll(parsed.warnings());
		return parsed.value();
	}

	private static int parseHeight(CommandContext<CommandSourceStack> context, List<String> warnings) throws CommandSyntaxException {
		ParsedInt parsed = GeometryParameterParser.parsePositiveHeight(StringArgumentType.getString(context, "height"));
		warnings.addAll(parsed.warnings());
		return parsed.value();
	}

	private static double parseNonNegativeDouble(CommandContext<CommandSourceStack> context, String name, List<String> warnings) throws CommandSyntaxException {
		ParsedDouble parsed = GeometryParameterParser.parseNonNegativeDouble(StringArgumentType.getString(context, name), name);
		warnings.addAll(parsed.warnings());
		return parsed.value();
	}

	private static double parseCylinderRadius(CommandContext<CommandSourceStack> context, List<String> warnings) throws CommandSyntaxException {
		ParsedDouble parsed = GeometryParameterParser.parseCylinderRadius(StringArgumentType.getString(context, "radius"));
		warnings.addAll(parsed.warnings());
		return parsed.value();
	}

	private static BlockInput block(CommandContext<CommandSourceStack> context) {
		return BlockStateArgument.getBlock(context, "block");
	}

	private static ServerLevel level(ServerPlayer player) {
		return (ServerLevel) player.level();
	}

	private static void sendResult(ServerPlayer player, String action, GeometryEditResult result) {
		MineAgentNetworking.sendAgentLog(player, "ui", action + ": changed " + result.changed()
				+ " block(s), considered " + result.candidates()
				+ ", unchanged " + result.unchanged()
				+ ", clipped outside sandbox " + result.skippedOutsideSandbox()
				+ ", clipped outside world " + result.skippedOutsideWorld() + ".");
		sendWarnings(player, result.warnings());
	}

	private static void sendWarnings(ServerPlayer player, List<String> warnings) {
		for (String warning : warnings) {
			MineAgentNetworking.sendAgentLog(player, "warn", "MineAgent warning: " + warning);
		}
	}

	private static CompletableFuture<Suggestions> suggestAnchors(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		List<String> suggestions = SandboxSessions.get(player).anchors().keySet().stream()
				.map(name -> "@" + name)
				.toList();
		return SharedSuggestionProvider.suggest(suggestions, builder);
	}

	private static String format(BlockPos pos) {
		return "(%d, %d, %d)".formatted(pos.getX(), pos.getY(), pos.getZ());
	}
}
