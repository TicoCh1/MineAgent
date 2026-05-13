package com.tico.mineagent.history;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public record AgentBlockSnapshot(BlockPos pos, BlockState state, CompoundTag blockEntityTag) {
	public static AgentBlockSnapshot capture(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		BlockEntity blockEntity = level.getBlockEntity(pos);
		CompoundTag tag = blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess());
		return new AgentBlockSnapshot(pos.immutable(), state, tag == null ? null : tag.copy());
	}

	public boolean place(ServerLevel level) {
		boolean changed = level.setBlock(pos, state, Block.UPDATE_ALL);
		if (blockEntityTag != null) {
			BlockEntity blockEntity = BlockEntity.loadStatic(pos, state, blockEntityTag.copy(), level.registryAccess());
			if (blockEntity != null) {
				level.setBlockEntity(blockEntity);
				blockEntity.setChanged();
				changed = true;
			}
		} else if (level.getBlockEntity(pos) != null) {
			level.removeBlockEntity(pos);
			changed = true;
		}
		return changed;
	}

	public boolean sameBlockData(AgentBlockSnapshot other) {
		if (!state.equals(other.state)) {
			return false;
		}
		if (blockEntityTag == null || other.blockEntityTag == null) {
			return blockEntityTag == null && other.blockEntityTag == null;
		}
		return blockEntityTag.equals(other.blockEntityTag);
	}
}
