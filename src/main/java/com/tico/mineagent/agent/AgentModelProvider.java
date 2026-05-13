package com.tico.mineagent.agent;

import java.util.List;

public interface AgentModelProvider {
	AgentConversation start(AgentCredentials credentials, String prompt);

	AgentModelTurn next(AgentConversation conversation, List<AgentTool> tools, List<AgentToolResult> toolResults) throws Exception;
}
