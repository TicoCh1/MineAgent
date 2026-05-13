package com.tico.mineagent.clipboard;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

public record AgentClipboard(
		BlockPos sourceMin,
		BlockPos sourceMax,
		BlockPos reference,
		List<Entry> entries) {
	public AgentClipboard {
		entries = List.copyOf(entries);
	}

	public boolean empty() {
		return entries.isEmpty();
	}

	public int blockCount() {
		return entries.size();
	}

	public record Entry(int dx, int dy, int dz, BlockState state, CompoundTag blockEntityTag) {
		public Entry {
			blockEntityTag = blockEntityTag == null ? null : blockEntityTag.copy();
		}

		public BlockPos target(BlockPos pasteReference) {
			return pasteReference.offset(dx, dy, dz);
		}
	}
}
