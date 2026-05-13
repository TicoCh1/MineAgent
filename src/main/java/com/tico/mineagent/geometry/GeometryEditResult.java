package com.tico.mineagent.geometry;

import java.util.List;

import net.minecraft.core.BlockPos;

public record GeometryEditResult(
		long candidates,
		int changed,
		int unchanged,
		int skippedOutsideSandbox,
		int skippedOutsideWorld,
		int skippedByMask,
		boolean hasAffectedBounds,
		BlockPos affectedMin,
		BlockPos affectedMax,
		List<String> warnings) {
	public boolean clipped() {
		return skippedOutsideSandbox > 0 || skippedOutsideWorld > 0;
	}
}
