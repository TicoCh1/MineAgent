package com.tico.mineagent.mask;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record AgentMaskDefinition(
		String name,
		Mode mode,
		List<String> blocks,
		List<String> masks,
		boolean invert) {
	public AgentMaskDefinition {
		name = normalizeName(name);
		mode = Objects.requireNonNull(mode, "mode");
		blocks = blocks == null ? List.of() : List.copyOf(blocks);
		masks = masks == null ? List.of() : masks.stream()
				.map(AgentMaskDefinition::normalizeName)
				.toList();
	}

	public static String normalizeName(String name) {
		String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
		if (!normalized.matches("[a-z0-9_]+")) {
			throw new IllegalArgumentException("Mask name must use lowercase letters, digits, or underscore: " + name);
		}
		return normalized;
	}

	public enum Mode {
		ALL("all"),
		NONE("none"),
		EXISTING("existing"),
		AIR("air"),
		SOLID("solid"),
		BLOCKS("blocks"),
		NOT_BLOCKS("not_blocks"),
		ANY_OF("any_of"),
		ALL_OF("all_of"),
		NOT("not");

		private final String id;

		Mode(String id) {
			this.id = id;
		}

		public String id() {
			return id;
		}

		public static Mode byId(String id) {
			String normalized = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
			for (Mode mode : values()) {
				if (mode.id.equals(normalized)) {
					return mode;
				}
			}
			return null;
		}
	}
}
