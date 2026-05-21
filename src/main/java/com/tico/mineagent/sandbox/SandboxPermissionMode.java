package com.tico.mineagent.sandbox;

import java.util.Locale;

public enum SandboxPermissionMode {
	STRICT("strict", "Sandbox only"),
	MANUAL_EXPAND("manual_expand", "Ask to expand"),
	AUTO_EXPAND_AIR("auto_expand_air", "Auto-expand through air");

	private final String id;
	private final String label;

	SandboxPermissionMode(String id, String label) {
		this.id = id;
		this.label = label;
	}

	public String id() {
		return id;
	}

	public String label() {
		return label;
	}

	public static SandboxPermissionMode byId(String raw) {
		if (raw == null) {
			return null;
		}
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		for (SandboxPermissionMode mode : values()) {
			if (mode.id.equals(normalized)) {
				return mode;
			}
		}
		return null;
	}
}
