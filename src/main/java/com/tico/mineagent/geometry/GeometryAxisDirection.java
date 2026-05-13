package com.tico.mineagent.geometry;

import java.util.Locale;

import net.minecraft.core.Direction;

public enum GeometryAxisDirection {
	POSITIVE_X("+x", Direction.EAST),
	NEGATIVE_X("-x", Direction.WEST),
	POSITIVE_Y("+y", Direction.UP),
	NEGATIVE_Y("-y", Direction.DOWN),
	POSITIVE_Z("+z", Direction.SOUTH),
	NEGATIVE_Z("-z", Direction.NORTH);

	private final String id;
	private final Direction direction;

	GeometryAxisDirection(String id, Direction direction) {
		this.id = id;
		this.direction = direction;
	}

	public String id() {
		return id;
	}

	public Direction direction() {
		return direction;
	}

	public static GeometryAxisDirection byId(String id) {
		String normalized = id.toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "+x", "x", "east", "e" -> POSITIVE_X;
			case "-x", "west", "w" -> NEGATIVE_X;
			case "+y", "y", "up", "u" -> POSITIVE_Y;
			case "-y", "down", "d" -> NEGATIVE_Y;
			case "+z", "z", "south", "s" -> POSITIVE_Z;
			case "-z", "north", "n" -> NEGATIVE_Z;
			default -> null;
		};
	}

	public static String[] suggestions() {
		return new String[] { "+x", "-x", "+y", "-y", "+z", "-z" };
	}
}
