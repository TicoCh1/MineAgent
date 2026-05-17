package com.tico.mineagent.agent;

import java.util.Locale;

public enum AgentHostMode {
	INTERNAL("internal"),
	EXTERNAL("external");

	private final String id;

	AgentHostMode(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public static AgentHostMode byId(String id) {
		String normalized = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
		for (AgentHostMode mode : values()) {
			if (mode.id.equals(normalized)) {
				return mode;
			}
		}
		return null;
	}
}
