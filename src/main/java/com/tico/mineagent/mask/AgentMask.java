package com.tico.mineagent.mask;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

@FunctionalInterface
public interface AgentMask {
	boolean test(ServerLevel level, BlockPos pos);
}
