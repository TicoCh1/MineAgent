package com.tico.mineagent.agent;

public record AgentPlanItem(String step, String status) {
	public static final String PENDING = "pending";
	public static final String IN_PROGRESS = "in_progress";
	public static final String COMPLETED = "completed";

	public AgentPlanItem {
		step = step == null ? "" : step.trim();
		status = normalizeStatus(status);
	}

	public static String normalizeStatus(String rawStatus) {
		String status = rawStatus == null ? "" : rawStatus.trim();
		return switch (status) {
			case PENDING, IN_PROGRESS, COMPLETED -> status;
			default -> throw new IllegalArgumentException("Unsupported plan status: " + rawStatus + ". Use pending, in_progress, or completed.");
		};
	}
}
