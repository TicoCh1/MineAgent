package com.tico.mineagent.mcp;

import com.google.gson.JsonObject;

@FunctionalInterface
public interface MineAgentMcpResourceReader {
	JsonObject read(MineAgentMcpContext context) throws Exception;
}
