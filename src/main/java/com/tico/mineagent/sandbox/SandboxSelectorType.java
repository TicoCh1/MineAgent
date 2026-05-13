package com.tico.mineagent.sandbox;

import java.util.Locale;

public enum SandboxSelectorType {
	CUBOID("cuboid"),
	EXTENDING_CUBOID("extend");

	private final String id;

	SandboxSelectorType(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public static SandboxSelectorType byId(String id) {
		String normalized = id.toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "cuboid", "cuboidregion" -> CUBOID;
			case "extend", "extending", "extending_cuboid", "extendingcuboid" -> EXTENDING_CUBOID;
			default -> null;
		};
	}

	public static String[] suggestions() {
		return new String[] { "cuboid", "extend" };
	}
}
