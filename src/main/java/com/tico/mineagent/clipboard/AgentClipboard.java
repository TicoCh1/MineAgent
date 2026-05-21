package com.tico.mineagent.clipboard;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import com.tico.mineagent.structure.StructureClipboardEntry;

public record AgentClipboard(
		BlockPos sourceMin,
		BlockPos sourceMax,
		BlockPos reference,
		List<Entry> entries,
		List<StructureClipboardEntry> structureComponents) {
	public AgentClipboard(BlockPos sourceMin, BlockPos sourceMax, BlockPos reference, List<Entry> entries) {
		this(sourceMin, sourceMax, reference, entries, List.of());
	}

	public AgentClipboard {
		entries = List.copyOf(entries);
		structureComponents = structureComponents == null ? List.of() : List.copyOf(structureComponents);
	}

	public boolean empty() {
		return entries.isEmpty();
	}

	public int blockCount() {
		return entries.size();
	}

	public AgentClipboard withStructureComponents(List<StructureClipboardEntry> components) {
		return new AgentClipboard(sourceMin, sourceMax, reference, entries, components);
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
