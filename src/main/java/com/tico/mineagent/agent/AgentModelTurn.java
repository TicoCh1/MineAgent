package com.tico.mineagent.agent;

import java.util.List;

public record AgentModelTurn(String text, List<AgentToolCall> toolCalls) {
	public boolean hasToolCalls() {
		return !toolCalls.isEmpty();
	}
}
