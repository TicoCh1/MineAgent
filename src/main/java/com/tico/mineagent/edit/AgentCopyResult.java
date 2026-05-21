package com.tico.mineagent.edit;

import java.util.List;

import com.tico.mineagent.clipboard.AgentClipboard;

public record AgentCopyResult(
		AgentClipboard clipboard,
		long candidates,
		int copied,
		long skippedOutsideSandbox,
		long skippedOutsideWorld,
		long skippedByMask,
		List<String> warnings) {
	public AgentCopyResult {
		warnings = List.copyOf(warnings);
	}

	public boolean clipped() {
		return skippedOutsideSandbox > 0 || skippedOutsideWorld > 0;
	}
}
