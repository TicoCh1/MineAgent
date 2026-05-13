package com.tico.mineagent.geometry;

import net.minecraft.core.BlockPos;

@FunctionalInterface
public interface GeometryMask {
	boolean test(BlockPos pos);
}
