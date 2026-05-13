package com.tico.mineagent.geometry;

import java.util.List;

public record GeometryEditResult(
		long candidates,
		int changed,
		int unchanged,
		int skippedOutsideSandbox,
		int skippedOutsideWorld,
		List<String> warnings) {
	public boolean clipped() {
		return skippedOutsideSandbox > 0 || skippedOutsideWorld > 0;
	}
}
