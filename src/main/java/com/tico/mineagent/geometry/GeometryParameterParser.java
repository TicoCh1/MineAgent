package com.tico.mineagent.geometry;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import com.tico.mineagent.sandbox.SandboxSession;

public final class GeometryParameterParser {
	private static final DynamicCommandExceptionType INVALID_COORDINATE = new DynamicCommandExceptionType(
			value -> Component.literal("Invalid MineAgent coordinate: " + value));
	private static final DynamicCommandExceptionType UNKNOWN_ANCHOR = new DynamicCommandExceptionType(
			value -> Component.literal("Unknown MineAgent coordinate anchor: " + value));
	private static final DynamicCommandExceptionType INVALID_NUMBER = new DynamicCommandExceptionType(
			value -> Component.literal("Invalid MineAgent numeric value: " + value));
	private static final DynamicCommandExceptionType PLAYER_RELATIVE_COORDINATE = new DynamicCommandExceptionType(
			value -> Component.literal("MineAgent does not allow player-relative '~' coordinates: " + value));

	private GeometryParameterParser() {
	}

	public static ParsedBlockPos parseBlockPos(CommandSourceStack source, SandboxSession session, String raw) throws CommandSyntaxException {
		List<String> warnings = new ArrayList<>();
		BlockPos base = source.getPlayerOrException().blockPosition();
		return parseBlockPos(base, session, raw, warnings);
	}

	public static ParsedBlockPos parseBlockPos(BlockPos base, SandboxSession session, String raw) throws CommandSyntaxException {
		return parseBlockPos(base, session, raw, new ArrayList<>());
	}

	private static ParsedBlockPos parseBlockPos(BlockPos base, SandboxSession session, String raw, List<String> warnings) throws CommandSyntaxException {
		String value = raw.trim();
		if (value.contains("~")) {
			throw PLAYER_RELATIVE_COORDINATE.create(raw);
		}
		if (value.startsWith("@")) {
			return parseAnchorRelative(session, value, warnings);
		}

		String[] parts = splitCoordinate(value);
		int x = parseCoordinatePart(parts[0], base.getX(), "x", raw, warnings);
		int y = parseCoordinatePart(parts[1], base.getY(), "y", raw, warnings);
		int z = parseCoordinatePart(parts[2], base.getZ(), "z", raw, warnings);
		return new ParsedBlockPos(new BlockPos(x, y, z), warnings);
	}

	public static ParsedInt parseSignedSize(String raw, String name) throws CommandSyntaxException {
		ParsedInt parsed = parseRoundedInt(raw, name, false);
		if (parsed.value() != 0) {
			return parsed;
		}

		List<String> warnings = new ArrayList<>(parsed.warnings());
		warnings.add(name + " was 0 and was corrected to 1 because box dimensions must cover at least one block.");
		return new ParsedInt(1, warnings);
	}

	public static ParsedInt parsePositiveHeight(String raw) throws CommandSyntaxException {
		ParsedInt parsed = parseRoundedInt(raw, "height", false);
		List<String> warnings = new ArrayList<>(parsed.warnings());
		int height = parsed.value();
		if (height < 0) {
			height = Math.abs(height);
			warnings.add("height was negative and was corrected to its absolute value.");
		}
		if (height < 1) {
			height = 1;
			warnings.add("height was below 1 and was corrected to 1.");
		}
		return new ParsedInt(height, warnings);
	}

	public static ParsedInt parsePositiveInteger(String raw, String name) throws CommandSyntaxException {
		ParsedInt parsed = parseRoundedInt(raw, name, false);
		if (parsed.value() < 1) {
			throw INVALID_NUMBER.create(name + " must be >= 1: " + raw);
		}
		return parsed;
	}

	public static ParsedDouble parseNonNegativeDouble(String raw, String name) throws CommandSyntaxException {
		double value = parseDouble(raw);
		List<String> warnings = new ArrayList<>();
		if (value < 0.0D) {
			value = 0.0D;
			warnings.add(name + " was negative and was corrected to 0.");
		}
		return new ParsedDouble(value, warnings);
	}

	public static ParsedDouble parseCylinderRadius(String raw) throws CommandSyntaxException {
		double value = parseDouble(raw);
		List<String> warnings = new ArrayList<>();
		if (value < 1.0D) {
			value = 1.0D;
			warnings.add("radius was below 1 and was corrected to 1, matching WorldEdit cylinder radius behavior.");
		}
		return new ParsedDouble(value, warnings);
	}

	private static ParsedInt parseRoundedInt(String raw, String name, boolean nonNegative) throws CommandSyntaxException {
		double value = parseDouble(raw);
		List<String> warnings = new ArrayList<>();
		int rounded = roundToInt(value, name, warnings);
		if (nonNegative && rounded < 0) {
			rounded = 0;
			warnings.add(name + " was negative and was corrected to 0.");
		}
		return new ParsedInt(rounded, warnings);
	}

	private static ParsedBlockPos parseAnchorRelative(SandboxSession session, String raw, List<String> warnings) throws CommandSyntaxException {
		int offsetStart = firstOffsetStart(raw);
		String anchorName = (offsetStart < 0 ? raw.substring(1) : raw.substring(1, offsetStart)).toLowerCase(Locale.ROOT);
		if (anchorName.isBlank()) {
			throw INVALID_COORDINATE.create(raw);
		}

		BlockPos anchor = session.anchor(anchorName);
		if (anchor == null) {
			throw UNKNOWN_ANCHOR.create(anchorName);
		}

		if (offsetStart < 0) {
			return new ParsedBlockPos(anchor, warnings);
		}

		String offsetText = raw.substring(offsetStart);
		String[] parts = splitCoordinate(offsetText);
		int x = EditRegionLimiter.clampToBlockCoordinate((long) anchor.getX() + parseOffsetPart(parts[0], "x offset in " + raw, warnings), "x coordinate in " + raw, warnings);
		int y = EditRegionLimiter.clampToBlockCoordinate((long) anchor.getY() + parseOffsetPart(parts[1], "y offset in " + raw, warnings), "y coordinate in " + raw, warnings);
		int z = EditRegionLimiter.clampToBlockCoordinate((long) anchor.getZ() + parseOffsetPart(parts[2], "z offset in " + raw, warnings), "z coordinate in " + raw, warnings);
		return new ParsedBlockPos(new BlockPos(x, y, z), warnings);
	}

	private static int firstOffsetStart(String raw) {
		for (int i = 1; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == '+' || c == '-') {
				return i;
			}
		}
		return -1;
	}

	private static String[] splitCoordinate(String raw) throws CommandSyntaxException {
		String[] parts = raw.split(",", -1);
		if (parts.length != 3) {
			throw INVALID_COORDINATE.create(raw);
		}
		return parts;
	}

	private static int parseCoordinatePart(String raw, int base, String axis, String fullValue, List<String> warnings) throws CommandSyntaxException {
		String part = raw.trim();
		if (part.isEmpty()) {
			throw INVALID_COORDINATE.create(fullValue);
		}

		return roundToInt(parseDouble(part), axis + " coordinate in " + fullValue, warnings);
	}

	private static int parseOffsetPart(String raw, String name, List<String> warnings) throws CommandSyntaxException {
		String part = raw.trim();
		if (part.isEmpty()) {
			throw INVALID_COORDINATE.create(raw);
		}
		if (part.charAt(0) == '+') {
			return roundToInt(parseDouble(part.substring(1)), name, warnings);
		}
		return roundToInt(parseDouble(part), name, warnings);
	}

	private static double parseDouble(String raw) throws CommandSyntaxException {
		try {
			double value = Double.parseDouble(raw);
			if (!Double.isFinite(value)) {
				throw INVALID_NUMBER.create(raw);
			}
			return value;
		} catch (NumberFormatException ignored) {
			throw INVALID_NUMBER.create(raw);
		}
	}

	private static int roundToInt(double value, String name, List<String> warnings) {
		long rounded = Math.round(value);
		if (Double.compare(value, rounded) != 0) {
			warnings.add(name + " was not an integer and was rounded to " + rounded + ".");
		}
		if (rounded > Integer.MAX_VALUE) {
			warnings.add(name + " exceeded integer range and was clamped to " + Integer.MAX_VALUE + ".");
			return Integer.MAX_VALUE;
		}
		if (rounded < Integer.MIN_VALUE) {
			warnings.add(name + " exceeded integer range and was clamped to " + Integer.MIN_VALUE + ".");
			return Integer.MIN_VALUE;
		}
		return (int) rounded;
	}

	public record ParsedBlockPos(BlockPos pos, List<String> warnings) {
	}

	public record ParsedInt(int value, List<String> warnings) {
	}

	public record ParsedDouble(double value, List<String> warnings) {
	}
}
