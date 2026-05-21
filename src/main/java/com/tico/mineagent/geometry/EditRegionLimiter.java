package com.tico.mineagent.geometry;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import com.tico.mineagent.sandbox.SandboxExpansionGuards;
import com.tico.mineagent.sandbox.SandboxSession;

public final class EditRegionLimiter {
	public static final long MAX_TRAVERSAL_BLOCKS = 262_144L;
	public static final int MIN_WORLD_XZ = -29_999_984;
	public static final int MAX_WORLD_XZ = 29_999_984;

	private EditRegionLimiter() {
	}

	public static ClippedBox clipBox(ServerLevel level, SandboxSession sandbox, BlockPos first, BlockPos second, List<String> warnings, String operation) {
		Box raw = Box.of(first, second);
		ensureSandboxCanContain(level, sandbox, raw.min(), raw.max(), warnings, operation);
		long rawVolume = raw.volume();
		Box sandboxed = raw.intersect(Box.of(sandbox.editMin(), sandbox.editMax()));
		long sandboxVolume = sandboxed == null ? 0L : sandboxed.volume();
		Box clipped = sandboxed == null ? null : sandboxed.clipWorld(level);
		long clippedVolume = clipped == null ? 0L : clipped.volume();
		long skippedOutsideSandbox = saturatedSubtract(rawVolume, sandboxVolume);
		long skippedOutsideWorld = saturatedSubtract(sandboxVolume, clippedVolume);

		if (skippedOutsideSandbox > 0L || skippedOutsideWorld > 0L) {
			warnings.add(operation + " region was clipped before traversal: skipped " + formatCount(skippedOutsideSandbox)
					+ " block position(s) outside the active " + sandbox.editBoundaryName() + " and " + formatCount(skippedOutsideWorld)
					+ " outside Minecraft world bounds.");
		}
		requireBudget(operation, clippedVolume);
		return new ClippedBox(clipped, clippedVolume, skippedOutsideSandbox, skippedOutsideWorld);
	}

	public static void ensureSandboxCanContain(ServerLevel level, SandboxSession sandbox, BlockPos first, BlockPos second, List<String> warnings, String operation) {
		Box requested = Box.of(first, second).clipWorld(level);
		if (requested == null) {
			return;
		}

		Box current = Box.of(sandbox.editMin(), sandbox.editMax());
		if (current.contains(requested)) {
			return;
		}
		if (sandbox.hasPrototypeSandbox()) {
			return;
		}

		BlockPos requestedMin = new BlockPos(
				Math.min(current.min().getX(), requested.min().getX()),
				Math.min(current.min().getY(), requested.min().getY()),
				Math.min(current.min().getZ(), requested.min().getZ()));
		BlockPos requestedMax = new BlockPos(
				Math.max(current.max().getX(), requested.max().getX()),
				Math.max(current.max().getY(), requested.max().getY()),
				Math.max(current.max().getZ(), requested.max().getZ()));
		SandboxExpansionGuards.handle(level, sandbox, requestedMin, requestedMax, operation, warnings);
	}

	public static void requireBudget(String operation, long count) {
		if (count > MAX_TRAVERSAL_BLOCKS) {
			throw new IllegalArgumentException(operation + " would traverse " + formatCount(count)
					+ " block position(s) after boundary clipping, exceeding MineAgent's synchronous traversal limit of "
					+ MAX_TRAVERSAL_BLOCKS + ". Split the operation into smaller regions.");
		}
	}

	public static long saturatedAdd(long a, long b) {
		if (a == Long.MAX_VALUE || b == Long.MAX_VALUE || Long.MAX_VALUE - a < b) {
			return Long.MAX_VALUE;
		}
		return a + b;
	}

	public static long saturatedMultiply(long a, long b) {
		if (a <= 0L || b <= 0L) {
			return 0L;
		}
		if (a > Long.MAX_VALUE / b) {
			return Long.MAX_VALUE;
		}
		return a * b;
	}

	public static int clampToBlockCoordinate(long value, String name, List<String> warnings) {
		if (value > Integer.MAX_VALUE) {
			warnings.add(name + " exceeded integer block-coordinate range and was clamped to " + Integer.MAX_VALUE + ".");
			return Integer.MAX_VALUE;
		}
		if (value < Integer.MIN_VALUE) {
			warnings.add(name + " exceeded integer block-coordinate range and was clamped to " + Integer.MIN_VALUE + ".");
			return Integer.MIN_VALUE;
		}
		return (int) value;
	}

	public static boolean insideWorld(ServerLevel level, BlockPos pos) {
		return insideWorld(level, pos.getX(), pos.getY(), pos.getZ());
	}

	public static boolean insideWorld(ServerLevel level, long x, long y, long z) {
		return x >= MIN_WORLD_XZ && x <= MAX_WORLD_XZ
				&& y >= level.getMinY() && y < level.getMaxY()
				&& z >= MIN_WORLD_XZ && z <= MAX_WORLD_XZ;
	}

	private static long saturatedSubtract(long a, long b) {
		if (a == Long.MAX_VALUE) {
			return b == Long.MAX_VALUE ? 0L : Long.MAX_VALUE;
		}
		return Math.max(0L, a - b);
	}

	private static String formatCount(long count) {
		return count == Long.MAX_VALUE ? "at least " + Long.MAX_VALUE : Long.toString(count);
	}

	public record ClippedBox(Box box, long volume, long skippedOutsideSandbox, long skippedOutsideWorld) {
		public boolean hasBlocks() {
			return box != null && volume > 0L;
		}

		public BlockPos min() {
			return box.min();
		}

		public BlockPos max() {
			return box.max();
		}
	}

	public record Box(BlockPos min, BlockPos max) {
		public static Box of(BlockPos first, BlockPos second) {
			return new Box(
					new BlockPos(
							Math.min(first.getX(), second.getX()),
							Math.min(first.getY(), second.getY()),
							Math.min(first.getZ(), second.getZ())),
					new BlockPos(
							Math.max(first.getX(), second.getX()),
							Math.max(first.getY(), second.getY()),
							Math.max(first.getZ(), second.getZ())));
		}

		public Box intersect(Box other) {
			int minX = Math.max(min.getX(), other.min.getX());
			int minY = Math.max(min.getY(), other.min.getY());
			int minZ = Math.max(min.getZ(), other.min.getZ());
			int maxX = Math.min(max.getX(), other.max.getX());
			int maxY = Math.min(max.getY(), other.max.getY());
			int maxZ = Math.min(max.getZ(), other.max.getZ());
			if (minX > maxX || minY > maxY || minZ > maxZ) {
				return null;
			}
			return new Box(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
		}

		public Box clipY(int minY, int maxY) {
			int clippedMinY = Math.max(min.getY(), minY);
			int clippedMaxY = Math.min(max.getY(), maxY);
			if (clippedMinY > clippedMaxY) {
				return null;
			}
			return new Box(
					new BlockPos(min.getX(), clippedMinY, min.getZ()),
					new BlockPos(max.getX(), clippedMaxY, max.getZ()));
		}

		public Box clipWorld(ServerLevel level) {
			int clippedMinX = Math.max(min.getX(), MIN_WORLD_XZ);
			int clippedMinY = Math.max(min.getY(), level.getMinY());
			int clippedMinZ = Math.max(min.getZ(), MIN_WORLD_XZ);
			int clippedMaxX = Math.min(max.getX(), MAX_WORLD_XZ);
			int clippedMaxY = Math.min(max.getY(), level.getMaxY() - 1);
			int clippedMaxZ = Math.min(max.getZ(), MAX_WORLD_XZ);
			if (clippedMinX > clippedMaxX || clippedMinY > clippedMaxY || clippedMinZ > clippedMaxZ) {
				return null;
			}
			return new Box(
					new BlockPos(clippedMinX, clippedMinY, clippedMinZ),
					new BlockPos(clippedMaxX, clippedMaxY, clippedMaxZ));
		}

		public long volume() {
			long x = (long) max.getX() - min.getX() + 1L;
			long y = (long) max.getY() - min.getY() + 1L;
			long z = (long) max.getZ() - min.getZ() + 1L;
			return saturatedMultiply(saturatedMultiply(x, y), z);
		}

		public boolean contains(Box other) {
			return other.min.getX() >= min.getX() && other.max.getX() <= max.getX()
					&& other.min.getY() >= min.getY() && other.max.getY() <= max.getY()
					&& other.min.getZ() >= min.getZ() && other.max.getZ() <= max.getZ();
		}
	}
}
