package com.tico.mineagent.agent;

import java.util.List;

public interface AgentModelProvider {
	AgentConversation start(AgentCredentials credentials, String prompt, String initialContext, List<AgentImageAttachment> initialImages, AgentConversationRestore restore) throws Exception;

	AgentModelTurn next(AgentConversation conversation, List<AgentTool> tools, List<AgentToolResult> toolResults, AgentRunLogger runLog) throws Exception;
}
