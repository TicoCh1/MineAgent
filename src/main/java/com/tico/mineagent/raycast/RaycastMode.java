package com.tico.mineagent.raycast;

import java.util.Locale;

public enum RaycastMode {
	SANDBOX("sandbox"),
	FREE("free");

	private final String id;

	RaycastMode(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public static RaycastMode byId(String id) {
		String normalized = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
		for (RaycastMode mode : values()) {
			if (mode.id.equals(normalized)) {
				return mode;
			}
		}
		return null;
	}
}
