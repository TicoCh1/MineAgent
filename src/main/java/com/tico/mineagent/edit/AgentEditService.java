package com.tico.mineagent.edit;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.tico.mineagent.clipboard.AgentClipboard;
import com.tico.mineagent.geometry.GeometryEditResult;
import com.tico.mineagent.history.AgentBlockChange;
import com.tico.mineagent.history.AgentBlockSnapshot;
import com.tico.mineagent.history.AgentEditRecord;
import com.tico.mineagent.mask.AgentMask;
import com.tico.mineagent.sandbox.SandboxSession;

public final class AgentEditService {
	private static final long MAX_CANDIDATE_BLOCKS = 500_000L;
	private static final Dynamic2CommandExceptionType TOO_LARGE = new Dynamic2CommandExceptionType(
			(candidates, limit) -> Component.literal("MineAgent edit operation is too large: " + candidates + " candidate blocks exceeds limit " + limit + "."));

	private AgentEditService() {
	}

	public static GeometryEditResult replace(ServerLevel level, SandboxSession sandbox, AgentMask sourceMask, BlockInput target, BlockPos first, BlockPos second, List<String> warnings) throws CommandSyntaxException {
		BlockPos min = min(first, second);
		BlockPos max = max(first, second);
		ensureWithinLimit(boxVolume(min, max));
		EditStats stats = new EditStats(level, sandbox, "replace", warnings);
		Set<Long> visited = new HashSet<>();

		forEachInBox(min, max, pos -> {
			if (!visited.add(pos.asLong())) {
				return;
			}
			stats.candidates++;
			if (!insideSandbox(sandbox, pos)) {
				stats.skippedOutsideSandbox++;
				return;
			}
			if (!insideWorld(level, pos)) {
				stats.skippedOutsideWorld++;
				return;
			}
			if (sourceMask != null && !sourceMask.test(level, pos)) {
				stats.skippedByMask++;
				return;
			}

			stats.placeBlockInput(pos, target);
		});

		return stats.result();
	}

	public static GeometryEditResult move(ServerLevel level, SandboxSession sandbox, AgentMask sourceMask, BlockPos first, BlockPos second, BlockPos fromReference, BlockPos toReference, boolean ignoreAir, List<String> warnings) throws CommandSyntaxException {
		BlockPos min = min(first, second);
		BlockPos max = max(first, second);
		ensureWithinLimit(boxVolume(min, max));
		BlockPos delta = toReference.subtract(fromReference);
		if (delta.equals(BlockPos.ZERO)) {
			throw new IllegalArgumentException("Move delta is zero; from_reference and to_reference must differ.");
		}

		EditStats stats = new EditStats(level, sandbox, "move", warnings);
		List<MoveEntry> snapshots = new ArrayList<>();
		Set<Long> visited = new HashSet<>();

		forEachInBox(min, max, pos -> {
			if (!visited.add(pos.asLong())) {
				return;
			}
			stats.candidates++;
			if (!insideSandbox(sandbox, pos)) {
				stats.skippedOutsideSandbox++;
				return;
			}
			if (!insideWorld(level, pos)) {
				stats.skippedOutsideWorld++;
				return;
			}
			BlockState state = level.getBlockState(pos);
			if (ignoreAir && state.isAir()) {
				stats.skippedByMask++;
				return;
			}
			if (sourceMask != null && !sourceMask.test(level, pos)) {
				stats.skippedByMask++;
				return;
			}

			BlockPos target = pos.offset(delta);
			if (!insideSandbox(sandbox, target)) {
				stats.skippedOutsideSandbox++;
				return;
			}
			if (!insideWorld(level, target)) {
				stats.skippedOutsideWorld++;
				return;
			}
			snapshots.add(new MoveEntry(pos.immutable(), target.immutable(), snapshot(level, pos, fromReference)));
		});

		for (MoveEntry entry : snapshots) {
			stats.setState(entry.source(), Blocks.AIR.defaultBlockState());
		}
		for (MoveEntry entry : snapshots) {
			stats.placeSnapshot(entry.target(), entry.snapshot());
		}

		return stats.result();
	}

	public static AgentCopyResult copy(ServerLevel level, SandboxSession sandbox, AgentMask sourceMask, BlockPos first, BlockPos second, BlockPos reference, List<String> warnings) throws CommandSyntaxException {
		BlockPos min = min(first, second);
		BlockPos max = max(first, second);
		ensureWithinLimit(boxVolume(min, max));
		CopyStats stats = new CopyStats(warnings);
		List<AgentClipboard.Entry> entries = new ArrayList<>();
		Set<Long> visited = new HashSet<>();

		forEachInBox(min, max, pos -> {
			if (!visited.add(pos.asLong())) {
				return;
			}
			stats.candidates++;
			if (!insideSandbox(sandbox, pos)) {
				stats.skippedOutsideSandbox++;
				return;
			}
			if (!insideWorld(level, pos)) {
				stats.skippedOutsideWorld++;
				return;
			}
			if (sourceMask != null && !sourceMask.test(level, pos)) {
				stats.skippedByMask++;
				return;
			}

			entries.add(snapshot(level, pos, reference));
			stats.copied++;
		});

		return stats.result(new AgentClipboard(min, max, reference, entries));
	}

	public static GeometryEditResult paste(ServerLevel level, SandboxSession sandbox, AgentClipboard clipboard, AgentMask targetMask, BlockPos reference, boolean ignoreAir, List<String> warnings) {
		EditStats stats = new EditStats(level, sandbox, "paste", warnings);

		for (AgentClipboard.Entry entry : clipboard.entries()) {
			stats.candidates++;
			if (ignoreAir && entry.state().isAir()) {
				stats.skippedByMask++;
				continue;
			}
			BlockPos target = entry.target(reference);
			if (!insideSandbox(sandbox, target)) {
				stats.skippedOutsideSandbox++;
				continue;
			}
			if (!insideWorld(level, target)) {
				stats.skippedOutsideWorld++;
				continue;
			}
			if (targetMask != null && !targetMask.test(level, target)) {
				stats.skippedByMask++;
				continue;
			}

			stats.placeSnapshot(target, entry);
		}

		return stats.result();
	}

	public static GeometryEditResult stack(ServerLevel level, SandboxSession sandbox, AgentMask sourceMask, AgentMask targetMask, BlockPos first, BlockPos second, Direction direction, int count, List<String> warnings) throws CommandSyntaxException {
		if (count < 1) {
			throw new IllegalArgumentException("Stack count must be >= 1.");
		}
		BlockPos min = min(first, second);
		BlockPos max = max(first, second);
		long candidates = boxVolume(min, max) * (long) count;
		ensureWithinLimit(candidates);

		EditStats stats = new EditStats(level, sandbox, "stack", warnings);
		List<StackEntry> snapshots = new ArrayList<>();
		Set<Long> visited = new HashSet<>();

		forEachInBox(min, max, pos -> {
			if (!visited.add(pos.asLong())) {
				return;
			}
			stats.candidates += count;
			if (!insideSandbox(sandbox, pos)) {
				stats.skippedOutsideSandbox += count;
				return;
			}
			if (!insideWorld(level, pos)) {
				stats.skippedOutsideWorld += count;
				return;
			}
			if (sourceMask != null && !sourceMask.test(level, pos)) {
				stats.skippedByMask += count;
				return;
			}

			snapshots.add(new StackEntry(pos.immutable(), snapshot(level, pos, pos)));
		});

		BlockPos singleOffset = stackOffset(min, max, direction);
		for (int repetition = 1; repetition <= count; repetition++) {
			BlockPos offset = multiply(singleOffset, repetition);
			for (StackEntry entry : snapshots) {
				BlockPos target = entry.source().offset(offset);
				if (!insideSandbox(sandbox, target)) {
					stats.skippedOutsideSandbox++;
					continue;
				}
				if (!insideWorld(level, target)) {
					stats.skippedOutsideWorld++;
					continue;
				}
				if (targetMask != null && !targetMask.test(level, target)) {
					stats.skippedByMask++;
					continue;
				}

				stats.placeSnapshot(target, entry.snapshot());
			}
		}

		return stats.result();
	}

	private static AgentClipboard.Entry snapshot(ServerLevel level, BlockPos pos, BlockPos reference) {
		BlockState state = level.getBlockState(pos);
		BlockEntity blockEntity = level.getBlockEntity(pos);
		CompoundTag tag = blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess());
		return new AgentClipboard.Entry(pos.getX() - reference.getX(), pos.getY() - reference.getY(), pos.getZ() - reference.getZ(), state, tag);
	}

	private static boolean placeSnapshot(ServerLevel level, BlockPos target, AgentClipboard.Entry entry) {
		boolean changed = level.setBlock(target, entry.state(), Block.UPDATE_ALL);
		if (entry.blockEntityTag() != null) {
			BlockEntity blockEntity = BlockEntity.loadStatic(target, entry.state(), entry.blockEntityTag().copy(), level.registryAccess());
			if (blockEntity != null) {
				level.setBlockEntity(blockEntity);
				blockEntity.setChanged();
				changed = true;
			}
		}
		return changed;
	}

	private static boolean insideSandbox(SandboxSession sandbox, BlockPos pos) {
		BlockPos min = sandbox.min();
		BlockPos max = sandbox.max();
		return pos.getX() >= min.getX() && pos.getX() <= max.getX()
				&& pos.getY() >= min.getY() && pos.getY() <= max.getY()
				&& pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
	}

	private static boolean insideWorld(ServerLevel level, BlockPos pos) {
		return pos.getY() >= level.getMinY() && pos.getY() < level.getMaxY();
	}

	private static BlockPos stackOffset(BlockPos min, BlockPos max, Direction direction) {
		int distance = switch (direction.getAxis()) {
			case X -> max.getX() - min.getX() + 1;
			case Y -> max.getY() - min.getY() + 1;
			case Z -> max.getZ() - min.getZ() + 1;
		};
		return new BlockPos(direction.getStepX() * distance, direction.getStepY() * distance, direction.getStepZ() * distance);
	}

	private static BlockPos multiply(BlockPos pos, int multiplier) {
		return new BlockPos(pos.getX() * multiplier, pos.getY() * multiplier, pos.getZ() * multiplier);
	}

	private static void forEachInBox(BlockPos min, BlockPos max, BlockConsumer consumer) {
		for (int y = min.getY(); y <= max.getY(); y++) {
			for (int z = min.getZ(); z <= max.getZ(); z++) {
				for (int x = min.getX(); x <= max.getX(); x++) {
					consumer.accept(new BlockPos(x, y, z));
				}
			}
		}
	}

	private static void ensureWithinLimit(long candidates) throws CommandSyntaxException {
		if (candidates > MAX_CANDIDATE_BLOCKS) {
			throw TOO_LARGE.create(candidates, MAX_CANDIDATE_BLOCKS);
		}
	}

	private static long boxVolume(BlockPos min, BlockPos max) {
		long x = (long) max.getX() - min.getX() + 1L;
		long y = (long) max.getY() - min.getY() + 1L;
		long z = (long) max.getZ() - min.getZ() + 1L;
		return Math.max(0L, x) * Math.max(0L, y) * Math.max(0L, z);
	}

	private static BlockPos min(BlockPos first, BlockPos second) {
		return new BlockPos(
				Math.min(first.getX(), second.getX()),
				Math.min(first.getY(), second.getY()),
				Math.min(first.getZ(), second.getZ()));
	}

	private static BlockPos max(BlockPos first, BlockPos second) {
		return new BlockPos(
				Math.max(first.getX(), second.getX()),
				Math.max(first.getY(), second.getY()),
				Math.max(first.getZ(), second.getZ()));
	}

	private interface BlockConsumer {
		void accept(BlockPos pos);
	}

	private record MoveEntry(BlockPos source, BlockPos target, AgentClipboard.Entry snapshot) {
	}

	private record StackEntry(BlockPos source, AgentClipboard.Entry snapshot) {
	}

	private static final class EditStats {
		private final ServerLevel level;
		private final SandboxSession sandbox;
		private final String label;
		private final List<String> warnings;
		private final List<AgentBlockChange> changes = new ArrayList<>();
		private long candidates;
		private int changed;
		private int unchanged;
		private int skippedOutsideSandbox;
		private int skippedOutsideWorld;
		private int skippedByMask;
		private boolean hasAffectedBounds;
		private int minX;
		private int minY;
		private int minZ;
		private int maxX;
		private int maxY;
		private int maxZ;

		private EditStats(ServerLevel level, SandboxSession sandbox, String label, List<String> warnings) {
			this.level = level;
			this.sandbox = sandbox;
			this.label = label;
			this.warnings = warnings;
		}

		private GeometryEditResult result() {
			BlockPos affectedMin = hasAffectedBounds ? new BlockPos(minX, minY, minZ) : BlockPos.ZERO;
			BlockPos affectedMax = hasAffectedBounds ? new BlockPos(maxX, maxY, maxZ) : BlockPos.ZERO;
			if (!changes.isEmpty()) {
				sandbox.recordEdit(new AgentEditRecord(label, changes, affectedMin, affectedMax));
			}
			return new GeometryEditResult(candidates, changed, unchanged, skippedOutsideSandbox, skippedOutsideWorld, skippedByMask, hasAffectedBounds, affectedMin, affectedMax, List.copyOf(warnings));
		}

		private void placeBlockInput(BlockPos pos, BlockInput block) {
			include(pos);
			AgentBlockSnapshot before = AgentBlockSnapshot.capture(level, pos);
			if (block.place(level, pos, Block.UPDATE_ALL)) {
				changed++;
			} else {
				unchanged++;
			}
			recordChange(before, AgentBlockSnapshot.capture(level, pos));
		}

		private void setState(BlockPos pos, BlockState state) {
			include(pos);
			AgentBlockSnapshot before = AgentBlockSnapshot.capture(level, pos);
			if (level.setBlock(pos, state, Block.UPDATE_ALL)) {
				changed++;
			} else {
				unchanged++;
			}
			recordChange(before, AgentBlockSnapshot.capture(level, pos));
		}

		private void placeSnapshot(BlockPos pos, AgentClipboard.Entry entry) {
			include(pos);
			AgentBlockSnapshot before = AgentBlockSnapshot.capture(level, pos);
			if (AgentEditService.placeSnapshot(level, pos, entry)) {
				changed++;
			} else {
				unchanged++;
			}
			recordChange(before, AgentBlockSnapshot.capture(level, pos));
		}

		private void recordChange(AgentBlockSnapshot before, AgentBlockSnapshot after) {
			AgentBlockChange change = new AgentBlockChange(before, after);
			if (change.changed()) {
				changes.add(change);
			}
		}

		private void include(BlockPos pos) {
			if (!hasAffectedBounds) {
				hasAffectedBounds = true;
				minX = maxX = pos.getX();
				minY = maxY = pos.getY();
				minZ = maxZ = pos.getZ();
				return;
			}

			minX = Math.min(minX, pos.getX());
			minY = Math.min(minY, pos.getY());
			minZ = Math.min(minZ, pos.getZ());
			maxX = Math.max(maxX, pos.getX());
			maxY = Math.max(maxY, pos.getY());
			maxZ = Math.max(maxZ, pos.getZ());
		}
	}

	private static final class CopyStats {
		private final List<String> warnings;
		private long candidates;
		private int copied;
		private int skippedOutsideSandbox;
		private int skippedOutsideWorld;
		private int skippedByMask;

		private CopyStats(List<String> warnings) {
			this.warnings = warnings;
		}

		private AgentCopyResult result(AgentClipboard clipboard) {
			return new AgentCopyResult(clipboard, candidates, copied, skippedOutsideSandbox, skippedOutsideWorld, skippedByMask, List.copyOf(warnings));
		}
	}
}
