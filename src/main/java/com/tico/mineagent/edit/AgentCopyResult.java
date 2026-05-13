package com.tico.mineagent.edit;

import java.util.List;

import com.tico.mineagent.clipboard.AgentClipboard;

public record AgentCopyResult(
		AgentClipboard clipboard,
		long candidates,
		int copied,
		int skippedOutsideSandbox,
		int skippedOutsideWorld,
		int skippedByMask,
		List<String> warnings) {
	public AgentCopyResult {
		warnings = List.copyOf(warnings);
	}

	public boolean clipped() {
		return skippedOutsideSandbox > 0 || skippedOutsideWorld > 0;
	}
}
