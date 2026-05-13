package com.tico.mineagent.agent;

import java.util.List;

public interface AgentModelProvider {
	AgentConversation start(AgentCredentials credentials, String prompt, List<AgentImageAttachment> initialImages) throws Exception;

	AgentModelTurn next(AgentConversation conversation, List<AgentTool> tools, List<AgentToolResult> toolResults, AgentRunLogger runLog) throws Exception;
}
