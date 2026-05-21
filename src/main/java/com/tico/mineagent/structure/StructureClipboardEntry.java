package com.tico.mineagent.structure;

import net.minecraft.core.BlockPos;

public record StructureClipboardEntry(
		String sourceId,
		String sourceName,
		String patternId,
		BlockPos sourceMin,
		BlockPos sourceMax,
		BlockPos relativeMin,
		BlockPos relativeMax,
		String sourceParentId) {
	public StructureClipboardEntry {
		sourceMin = sourceMin.immutable();
		sourceMax = sourceMax.immutable();
		relativeMin = relativeMin.immutable();
		relativeMax = relativeMax.immutable();
	}
}
