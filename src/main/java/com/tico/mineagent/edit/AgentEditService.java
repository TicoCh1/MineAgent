package com.tico.mineagent.edit;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.tico.mineagent.clipboard.AgentClipboard;
import com.tico.mineagent.geometry.EditRegionLimiter;
import com.tico.mineagent.geometry.GeometryEditResult;
import com.tico.mineagent.history.AgentBlockChange;
import com.tico.mineagent.history.AgentBlockSnapshot;
import com.tico.mineagent.history.AgentEditRecord;
import com.tico.mineagent.mask.AgentMask;
import com.tico.mineagent.sandbox.SandboxSession;

public final class AgentEditService {
	private AgentEditService() {
	}

	public static GeometryEditResult replace(ServerLevel level, SandboxSession sandbox, AgentMask sourceMask, BlockInput target, BlockPos first, BlockPos second, List<String> warnings) throws CommandSyntaxException {
		BlockPos min = min(first, second);
		BlockPos max = max(first, second);
		EditRegionLimiter.ClippedBox region = EditRegionLimiter.clipBox(level, sandbox, min, max, warnings, "replace");
		EditStats stats = new EditStats(level, sandbox, "replace", warnings);
		stats.addPreSkipped(region.skippedOutsideSandbox(), region.skippedOutsideWorld());
		if (region.hasBlocks()) {
			forEachInBox(region.min(), region.max(), pos -> {
				stats.addCandidate();
				if (sourceMask != null && !sourceMask.test(level, pos)) {
					stats.addSkippedByMask(1L);
					return;
				}

				stats.placeBlockInput(pos, target);
			});
		}

		return stats.result();
	}

	public static GeometryEditResult move(ServerLevel level, SandboxSession sandbox, AgentMask sourceMask, BlockPos first, BlockPos second, BlockPos fromReference, BlockPos toReference, boolean ignoreAir, List<String> warnings) throws CommandSyntaxException {
		BlockPos min = min(first, second);
		BlockPos max = max(first, second);
		long deltaX = (long) toReference.getX() - fromReference.getX();
		long deltaY = (long) toReference.getY() - fromReference.getY();
		long deltaZ = (long) toReference.getZ() - fromReference.getZ();
		if (deltaX == 0L && deltaY == 0L && deltaZ == 0L) {
			throw new IllegalArgumentException("Move delta is zero; from_reference and to_reference must differ.");
		}
		EditRegionLimiter.Box targetBounds = offsetBox(min, max, deltaX, deltaY, deltaZ);
		EditRegionLimiter.Box operationBounds = unionBox(new EditRegionLimiter.Box(min, max), targetBounds);
		EditRegionLimiter.ensureSandboxCanContain(level, sandbox, operationBounds.min(), operationBounds.max(), warnings, "move");
		EditRegionLimiter.ClippedBox region = EditRegionLimiter.clipBox(level, sandbox, min, max, warnings, "move");

		EditStats stats = new EditStats(level, sandbox, "move", warnings);
		stats.addPreSkipped(region.skippedOutsideSandbox(), region.skippedOutsideWorld());
		List<MoveEntry> snapshots = new ArrayList<>();

		if (region.hasBlocks()) {
			forEachInBox(region.min(), region.max(), pos -> {
				stats.addCandidate();
				BlockState state = level.getBlockState(pos);
				if (ignoreAir && state.isAir()) {
					stats.addSkippedByMask(1L);
					return;
				}
				if (sourceMask != null && !sourceMask.test(level, pos)) {
					stats.addSkippedByMask(1L);
					return;
				}

				long targetX = (long) pos.getX() + deltaX;
				long targetY = (long) pos.getY() + deltaY;
				long targetZ = (long) pos.getZ() + deltaZ;
				if (!insideSandbox(sandbox, targetX, targetY, targetZ)) {
					stats.addSkippedOutsideSandbox(1L);
					return;
				}
				if (!insideWorld(level, targetX, targetY, targetZ)) {
					stats.addSkippedOutsideWorld(1L);
					return;
				}
				BlockPos target = blockPos(targetX, targetY, targetZ);
				snapshots.add(new MoveEntry(pos.immutable(), target.immutable(), snapshot(level, pos, fromReference)));
			});
		}

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
		EditRegionLimiter.ClippedBox region = EditRegionLimiter.clipBox(level, sandbox, min, max, warnings, "copy");
		CopyStats stats = new CopyStats(warnings);
		stats.addPreSkipped(region.skippedOutsideSandbox(), region.skippedOutsideWorld());
		List<AgentClipboard.Entry> entries = new ArrayList<>();

		if (region.hasBlocks()) {
			forEachInBox(region.min(), region.max(), pos -> {
				stats.addCandidate();
				if (sourceMask != null && !sourceMask.test(level, pos)) {
					stats.addSkippedByMask(1L);
					return;
				}

				entries.add(snapshot(level, pos, reference));
				stats.copied++;
			});
		}

		return stats.result(new AgentClipboard(min, max, reference, entries));
	}

	public static GeometryEditResult paste(ServerLevel level, SandboxSession sandbox, AgentClipboard clipboard, AgentMask targetMask, BlockPos reference, boolean ignoreAir, List<String> warnings) {
		EditRegionLimiter.requireBudget("paste", clipboard.entries().size());
		EditRegionLimiter.Box targetBounds = pasteBounds(clipboard, reference, ignoreAir);
		if (targetBounds != null) {
			EditRegionLimiter.ensureSandboxCanContain(level, sandbox, targetBounds.min(), targetBounds.max(), warnings, "paste target");
		}
		EditStats stats = new EditStats(level, sandbox, "paste", warnings);

		for (AgentClipboard.Entry entry : clipboard.entries()) {
			stats.addCandidate();
			if (ignoreAir && entry.state().isAir()) {
				stats.addSkippedByMask(1L);
				continue;
			}
			long targetX = (long) reference.getX() + entry.dx();
			long targetY = (long) reference.getY() + entry.dy();
			long targetZ = (long) reference.getZ() + entry.dz();
			if (!insideSandbox(sandbox, targetX, targetY, targetZ)) {
				stats.addSkippedOutsideSandbox(1L);
				continue;
			}
			if (!insideWorld(level, targetX, targetY, targetZ)) {
				stats.addSkippedOutsideWorld(1L);
				continue;
			}
			BlockPos target = blockPos(targetX, targetY, targetZ);
			if (targetMask != null && !targetMask.test(level, target)) {
				stats.addSkippedByMask(1L);
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
		EditRegionLimiter.Box targetBounds = stackTargetBounds(min, max, direction, count);
		EditRegionLimiter.Box operationBounds = unionBox(new EditRegionLimiter.Box(min, max), targetBounds);
		EditRegionLimiter.ensureSandboxCanContain(level, sandbox, operationBounds.min(), operationBounds.max(), warnings, "stack");
		EditRegionLimiter.ClippedBox region = EditRegionLimiter.clipBox(level, sandbox, min, max, warnings, "stack source");
		long candidateCount = EditRegionLimiter.saturatedMultiply(region.volume(), count);
		EditRegionLimiter.requireBudget("stack", candidateCount);

		EditStats stats = new EditStats(level, sandbox, "stack", warnings);
		stats.addPreSkipped(
				EditRegionLimiter.saturatedMultiply(region.skippedOutsideSandbox(), count),
				EditRegionLimiter.saturatedMultiply(region.skippedOutsideWorld(), count));
		List<StackEntry> snapshots = new ArrayList<>();

		if (region.hasBlocks()) {
			forEachInBox(region.min(), region.max(), pos -> {
				stats.addCandidates(count);
				if (sourceMask != null && !sourceMask.test(level, pos)) {
					stats.addSkippedByMask(count);
					return;
				}

				snapshots.add(new StackEntry(pos.immutable(), snapshot(level, pos, pos)));
			});
		}

		if (snapshots.isEmpty()) {
			return stats.result();
		}

		LongOffset singleOffset = stackOffset(min, max, direction);
		for (int repetition = 1; repetition <= count; repetition++) {
			long offsetX = singleOffset.x() * repetition;
			long offsetY = singleOffset.y() * repetition;
			long offsetZ = singleOffset.z() * repetition;
			for (StackEntry entry : snapshots) {
				long targetX = (long) entry.source().getX() + offsetX;
				long targetY = (long) entry.source().getY() + offsetY;
				long targetZ = (long) entry.source().getZ() + offsetZ;
				if (!insideSandbox(sandbox, targetX, targetY, targetZ)) {
					stats.addSkippedOutsideSandbox(1L);
					continue;
				}
				if (!insideWorld(level, targetX, targetY, targetZ)) {
					stats.addSkippedOutsideWorld(1L);
					continue;
				}
				BlockPos target = blockPos(targetX, targetY, targetZ);
				if (targetMask != null && !targetMask.test(level, target)) {
					stats.addSkippedByMask(1L);
					continue;
				}

				stats.placeSnapshot(target, entry.snapshot());
			}
		}

		return stats.result();
	}

	public static GeometryEditResult rotate(ServerLevel level, SandboxSession sandbox, BlockPos first, BlockPos second, BlockPos reference, int degrees, List<String> warnings) {
		Rotation rotation = rotationForDegrees(degrees);
		int normalizedDegrees = normalizeDegrees(degrees);
		BlockPos min = min(first, second);
		BlockPos max = max(first, second);
		EditRegionLimiter.Box targetBounds = transformedBounds(min, max, pos -> rotateY(pos, reference, normalizedDegrees));
		EditRegionLimiter.Box operationBounds = unionBox(new EditRegionLimiter.Box(min, max), targetBounds);
		EditRegionLimiter.ensureSandboxCanContain(level, sandbox, operationBounds.min(), operationBounds.max(), warnings, "rotate");
		EditRegionLimiter.ClippedBox region = EditRegionLimiter.clipBox(level, sandbox, min, max, warnings, "rotate source");

		EditStats stats = new EditStats(level, sandbox, "rotate", warnings);
		stats.addPreSkipped(region.skippedOutsideSandbox(), region.skippedOutsideWorld());
		List<TransformEntry> snapshots = new ArrayList<>();

		if (region.hasBlocks()) {
			forEachInBox(region.min(), region.max(), pos -> {
				stats.addCandidate();
				BlockPos target = rotateY(pos, reference, normalizedDegrees);
				if (!insideSandbox(sandbox, target.getX(), target.getY(), target.getZ())) {
					stats.addSkippedOutsideSandbox(1L);
					return;
				}
				if (!insideWorld(level, target.getX(), target.getY(), target.getZ())) {
					stats.addSkippedOutsideWorld(1L);
					return;
				}

				AgentClipboard.Entry snapshot = snapshot(level, pos, pos);
				snapshots.add(new TransformEntry(pos.immutable(), target.immutable(), transformSnapshot(snapshot, rotation)));
			});
		}

		applyTransformSnapshots(stats, snapshots);
		return stats.result();
	}

	public static GeometryEditResult flip(ServerLevel level, SandboxSession sandbox, BlockPos first, BlockPos second, BlockPos reference, MirrorPlane plane, List<String> warnings) {
		BlockPos min = min(first, second);
		BlockPos max = max(first, second);
		EditRegionLimiter.Box targetBounds = transformedBounds(min, max, pos -> mirror(pos, reference, plane));
		EditRegionLimiter.Box operationBounds = unionBox(new EditRegionLimiter.Box(min, max), targetBounds);
		EditRegionLimiter.ensureSandboxCanContain(level, sandbox, operationBounds.min(), operationBounds.max(), warnings, "flip");
		EditRegionLimiter.ClippedBox region = EditRegionLimiter.clipBox(level, sandbox, min, max, warnings, "flip source");
		if (plane == MirrorPlane.XZ) {
			warnings.add("Vertical xz-plane flip mirrors block positions across Y; vanilla block-state mirror metadata only covers horizontal X/Z facing, so block orientation metadata was left unchanged.");
		}

		EditStats stats = new EditStats(level, sandbox, "flip", warnings);
		stats.addPreSkipped(region.skippedOutsideSandbox(), region.skippedOutsideWorld());
		List<TransformEntry> snapshots = new ArrayList<>();
		Mirror blockMirror = blockMirrorForPlane(plane);

		if (region.hasBlocks()) {
			forEachInBox(region.min(), region.max(), pos -> {
				stats.addCandidate();
				BlockPos target = mirror(pos, reference, plane);
				if (!insideSandbox(sandbox, target.getX(), target.getY(), target.getZ())) {
					stats.addSkippedOutsideSandbox(1L);
					return;
				}
				if (!insideWorld(level, target.getX(), target.getY(), target.getZ())) {
					stats.addSkippedOutsideWorld(1L);
					return;
				}

				AgentClipboard.Entry snapshot = snapshot(level, pos, pos);
				snapshots.add(new TransformEntry(pos.immutable(), target.immutable(), transformSnapshot(snapshot, blockMirror)));
			});
		}

		applyTransformSnapshots(stats, snapshots);
		return stats.result();
	}

	private static AgentClipboard.Entry snapshot(ServerLevel level, BlockPos pos, BlockPos reference) {
		BlockState state = level.getBlockState(pos);
		BlockEntity blockEntity = level.getBlockEntity(pos);
		CompoundTag tag = blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess());
		return new AgentClipboard.Entry(
				clipboardOffset(pos.getX(), reference.getX(), "x"),
				clipboardOffset(pos.getY(), reference.getY(), "y"),
				clipboardOffset(pos.getZ(), reference.getZ(), "z"),
				state,
				tag);
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

	private static void applyTransformSnapshots(EditStats stats, List<TransformEntry> snapshots) {
		for (TransformEntry entry : snapshots) {
			stats.setState(entry.source(), Blocks.AIR.defaultBlockState());
		}
		for (TransformEntry entry : snapshots) {
			stats.placeSnapshot(entry.target(), entry.snapshot());
		}
	}

	private static AgentClipboard.Entry transformSnapshot(AgentClipboard.Entry entry, Rotation rotation) {
		return new AgentClipboard.Entry(entry.dx(), entry.dy(), entry.dz(), entry.state().rotate(rotation), entry.blockEntityTag());
	}

	private static AgentClipboard.Entry transformSnapshot(AgentClipboard.Entry entry, Mirror mirror) {
		BlockState state = mirror == Mirror.NONE ? entry.state() : entry.state().mirror(mirror);
		return new AgentClipboard.Entry(entry.dx(), entry.dy(), entry.dz(), state, entry.blockEntityTag());
	}

	private static boolean insideSandbox(SandboxSession sandbox, long x, long y, long z) {
		BlockPos min = sandbox.editMin();
		BlockPos max = sandbox.editMax();
		return x >= min.getX() && x <= max.getX()
				&& y >= min.getY() && y <= max.getY()
				&& z >= min.getZ() && z <= max.getZ();
	}

	private static boolean insideWorld(ServerLevel level, long x, long y, long z) {
		return EditRegionLimiter.insideWorld(level, x, y, z);
	}

	private static LongOffset stackOffset(BlockPos min, BlockPos max, Direction direction) {
		long distance = switch (direction.getAxis()) {
			case X -> (long) max.getX() - min.getX() + 1L;
			case Y -> (long) max.getY() - min.getY() + 1L;
			case Z -> (long) max.getZ() - min.getZ() + 1L;
		};
		return new LongOffset(direction.getStepX() * distance, direction.getStepY() * distance, direction.getStepZ() * distance);
	}

	private static EditRegionLimiter.Box offsetBox(BlockPos min, BlockPos max, long deltaX, long deltaY, long deltaZ) {
		return new EditRegionLimiter.Box(
				new BlockPos(blockCoordinate((long) min.getX() + deltaX), blockCoordinate((long) min.getY() + deltaY), blockCoordinate((long) min.getZ() + deltaZ)),
				new BlockPos(blockCoordinate((long) max.getX() + deltaX), blockCoordinate((long) max.getY() + deltaY), blockCoordinate((long) max.getZ() + deltaZ)));
	}

	private static EditRegionLimiter.Box unionBox(EditRegionLimiter.Box first, EditRegionLimiter.Box second) {
		return new EditRegionLimiter.Box(
				new BlockPos(
						Math.min(first.min().getX(), second.min().getX()),
						Math.min(first.min().getY(), second.min().getY()),
						Math.min(first.min().getZ(), second.min().getZ())),
				new BlockPos(
						Math.max(first.max().getX(), second.max().getX()),
						Math.max(first.max().getY(), second.max().getY()),
						Math.max(first.max().getZ(), second.max().getZ())));
	}

	private static EditRegionLimiter.Box pasteBounds(AgentClipboard clipboard, BlockPos reference, boolean ignoreAir) {
		if (clipboard.entries().isEmpty()) {
			return null;
		}
		long minX = Long.MAX_VALUE;
		long minY = Long.MAX_VALUE;
		long minZ = Long.MAX_VALUE;
		long maxX = Long.MIN_VALUE;
		long maxY = Long.MIN_VALUE;
		long maxZ = Long.MIN_VALUE;
		for (AgentClipboard.Entry entry : clipboard.entries()) {
			if (ignoreAir && entry.state().isAir()) {
				continue;
			}
			long x = (long) reference.getX() + entry.dx();
			long y = (long) reference.getY() + entry.dy();
			long z = (long) reference.getZ() + entry.dz();
			minX = Math.min(minX, x);
			minY = Math.min(minY, y);
			minZ = Math.min(minZ, z);
			maxX = Math.max(maxX, x);
			maxY = Math.max(maxY, y);
			maxZ = Math.max(maxZ, z);
		}
		if (minX == Long.MAX_VALUE) {
			return null;
		}
		return new EditRegionLimiter.Box(
				new BlockPos(blockCoordinate(minX), blockCoordinate(minY), blockCoordinate(minZ)),
				new BlockPos(blockCoordinate(maxX), blockCoordinate(maxY), blockCoordinate(maxZ)));
	}

	private static EditRegionLimiter.Box stackTargetBounds(BlockPos min, BlockPos max, Direction direction, int count) {
		LongOffset singleOffset = stackOffset(min, max, direction);
		long lastOffsetX = singleOffset.x() * count;
		long lastOffsetY = singleOffset.y() * count;
		long lastOffsetZ = singleOffset.z() * count;
		return new EditRegionLimiter.Box(
				new BlockPos(
						blockCoordinate(Math.min((long) min.getX() + singleOffset.x(), (long) min.getX() + lastOffsetX)),
						blockCoordinate(Math.min((long) min.getY() + singleOffset.y(), (long) min.getY() + lastOffsetY)),
						blockCoordinate(Math.min((long) min.getZ() + singleOffset.z(), (long) min.getZ() + lastOffsetZ))),
				new BlockPos(
						blockCoordinate(Math.max((long) max.getX() + singleOffset.x(), (long) max.getX() + lastOffsetX)),
						blockCoordinate(Math.max((long) max.getY() + singleOffset.y(), (long) max.getY() + lastOffsetY)),
						blockCoordinate(Math.max((long) max.getZ() + singleOffset.z(), (long) max.getZ() + lastOffsetZ))));
	}

	private static EditRegionLimiter.Box transformedBounds(BlockPos min, BlockPos max, BlockTransform transform) {
		BlockPos[] corners = new BlockPos[] {
				new BlockPos(min.getX(), min.getY(), min.getZ()),
				new BlockPos(min.getX(), min.getY(), max.getZ()),
				new BlockPos(min.getX(), max.getY(), min.getZ()),
				new BlockPos(min.getX(), max.getY(), max.getZ()),
				new BlockPos(max.getX(), min.getY(), min.getZ()),
				new BlockPos(max.getX(), min.getY(), max.getZ()),
				new BlockPos(max.getX(), max.getY(), min.getZ()),
				new BlockPos(max.getX(), max.getY(), max.getZ())
		};
		BlockPos transformed = transform.apply(corners[0]);
		int minX = transformed.getX();
		int minY = transformed.getY();
		int minZ = transformed.getZ();
		int maxX = transformed.getX();
		int maxY = transformed.getY();
		int maxZ = transformed.getZ();
		for (int i = 1; i < corners.length; i++) {
			transformed = transform.apply(corners[i]);
			minX = Math.min(minX, transformed.getX());
			minY = Math.min(minY, transformed.getY());
			minZ = Math.min(minZ, transformed.getZ());
			maxX = Math.max(maxX, transformed.getX());
			maxY = Math.max(maxY, transformed.getY());
			maxZ = Math.max(maxZ, transformed.getZ());
		}
		return new EditRegionLimiter.Box(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
	}

	private static BlockPos rotateY(BlockPos pos, BlockPos reference, int degrees) {
		long dx = (long) pos.getX() - reference.getX();
		long dz = (long) pos.getZ() - reference.getZ();
		return switch (degrees) {
			case 90 -> new BlockPos(blockCoordinate((long) reference.getX() - dz), pos.getY(), blockCoordinate((long) reference.getZ() + dx));
			case 180 -> new BlockPos(blockCoordinate((long) reference.getX() - dx), pos.getY(), blockCoordinate((long) reference.getZ() - dz));
			case 270 -> new BlockPos(blockCoordinate((long) reference.getX() + dz), pos.getY(), blockCoordinate((long) reference.getZ() - dx));
			default -> pos.immutable();
		};
	}

	private static BlockPos mirror(BlockPos pos, BlockPos reference, MirrorPlane plane) {
		return switch (plane) {
			case YZ -> new BlockPos(blockCoordinate(2L * reference.getX() - pos.getX()), pos.getY(), pos.getZ());
			case XY -> new BlockPos(pos.getX(), pos.getY(), blockCoordinate(2L * reference.getZ() - pos.getZ()));
			case XZ -> new BlockPos(pos.getX(), blockCoordinate(2L * reference.getY() - pos.getY()), pos.getZ());
		};
	}

	private static Rotation rotationForDegrees(int degrees) {
		return switch (normalizeDegrees(degrees)) {
			case 90 -> Rotation.CLOCKWISE_90;
			case 180 -> Rotation.CLOCKWISE_180;
			case 270 -> Rotation.COUNTERCLOCKWISE_90;
			default -> Rotation.NONE;
		};
	}

	private static int normalizeDegrees(int degrees) {
		int normalized = degrees % 360;
		if (normalized < 0) {
			normalized += 360;
		}
		if (normalized % 90 != 0) {
			throw new IllegalArgumentException("Rotate degrees must be a multiple of 90.");
		}
		return normalized;
	}

	private static Mirror blockMirrorForPlane(MirrorPlane plane) {
		return switch (plane) {
			case YZ -> Mirror.FRONT_BACK;
			case XY -> Mirror.LEFT_RIGHT;
			case XZ -> Mirror.NONE;
		};
	}

	private static int clipboardOffset(int coordinate, int reference, String axis) {
		long offset = (long) coordinate - reference;
		if (offset < Integer.MIN_VALUE || offset > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Clipboard " + axis + " offset exceeds integer range.");
		}
		return (int) offset;
	}

	private static BlockPos blockPos(long x, long y, long z) {
		return new BlockPos((int) x, (int) y, (int) z);
	}

	private static int blockCoordinate(long value) {
		if (value > Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		if (value < Integer.MIN_VALUE) {
			return Integer.MIN_VALUE;
		}
		return (int) value;
	}

	private static void forEachInBox(BlockPos min, BlockPos max, BlockConsumer consumer) {
		for (long y = min.getY(); y <= max.getY(); y++) {
			for (long z = min.getZ(); z <= max.getZ(); z++) {
				for (long x = min.getX(); x <= max.getX(); x++) {
					consumer.accept(new BlockPos((int) x, (int) y, (int) z));
				}
			}
		}
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

	private interface BlockTransform {
		BlockPos apply(BlockPos pos);
	}

	private record MoveEntry(BlockPos source, BlockPos target, AgentClipboard.Entry snapshot) {
	}

	private record StackEntry(BlockPos source, AgentClipboard.Entry snapshot) {
	}

	private record TransformEntry(BlockPos source, BlockPos target, AgentClipboard.Entry snapshot) {
	}

	private record LongOffset(long x, long y, long z) {
	}

	public enum MirrorPlane {
		YZ,
		XY,
		XZ
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
		private long skippedOutsideSandbox;
		private long skippedOutsideWorld;
		private long skippedByMask;
		private boolean hasAffectedBounds;
		private int minX;
		private int minY;
		private int minZ;
		private int maxX;
		private int maxY;
		private int maxZ;

		private EditStats(ServerLevel level, SandboxSession sandbox, String label, List<String> warnings) {
			sandbox.requirePrototypeEditSlot(label);
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

		private void addCandidate() {
			addCandidates(1L);
		}

		private void addCandidates(long count) {
			candidates = EditRegionLimiter.saturatedAdd(candidates, count);
		}

		private void addPreSkipped(long outsideSandbox, long outsideWorld) {
			addSkippedOutsideSandbox(outsideSandbox);
			addSkippedOutsideWorld(outsideWorld);
			addCandidates(EditRegionLimiter.saturatedAdd(outsideSandbox, outsideWorld));
		}

		private void addSkippedOutsideSandbox(long count) {
			skippedOutsideSandbox = EditRegionLimiter.saturatedAdd(skippedOutsideSandbox, count);
		}

		private void addSkippedOutsideWorld(long count) {
			skippedOutsideWorld = EditRegionLimiter.saturatedAdd(skippedOutsideWorld, count);
		}

		private void addSkippedByMask(long count) {
			skippedByMask = EditRegionLimiter.saturatedAdd(skippedByMask, count);
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
		private long skippedOutsideSandbox;
		private long skippedOutsideWorld;
		private long skippedByMask;

		private CopyStats(List<String> warnings) {
			this.warnings = warnings;
		}

		private AgentCopyResult result(AgentClipboard clipboard) {
			return new AgentCopyResult(clipboard, candidates, copied, skippedOutsideSandbox, skippedOutsideWorld, skippedByMask, List.copyOf(warnings));
		}

		private void addCandidate() {
			addCandidates(1L);
		}

		private void addCandidates(long count) {
			candidates = EditRegionLimiter.saturatedAdd(candidates, count);
		}

		private void addPreSkipped(long outsideSandbox, long outsideWorld) {
			skippedOutsideSandbox = EditRegionLimiter.saturatedAdd(skippedOutsideSandbox, outsideSandbox);
			skippedOutsideWorld = EditRegionLimiter.saturatedAdd(skippedOutsideWorld, outsideWorld);
			addCandidates(EditRegionLimiter.saturatedAdd(outsideSandbox, outsideWorld));
		}

		private void addSkippedByMask(long count) {
			skippedByMask = EditRegionLimiter.saturatedAdd(skippedByMask, count);
		}
	}
}
