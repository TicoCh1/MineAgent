package com.tico.mineagent.mcp;

public record MineAgentMcpResource(
		String uri,
		String name,
		String title,
		String description,
		String mimeType,
		MineAgentMcpResourceReader reader) {
}
