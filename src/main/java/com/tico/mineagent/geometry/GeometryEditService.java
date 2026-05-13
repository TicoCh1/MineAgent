package com.tico.mineagent.geometry;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

import com.tico.mineagent.sandbox.SandboxSession;

public final class GeometryEditService {
	private static final long MAX_CANDIDATE_BLOCKS = 500_000L;
	private static final Dynamic2CommandExceptionType TOO_LARGE = new Dynamic2CommandExceptionType(
			(candidates, limit) -> Component.literal("MineAgent geometry operation is too large: " + candidates + " candidate blocks exceeds limit " + limit + "."));

	private GeometryEditService() {
	}

	public static GeometryEditResult fillBox(ServerLevel level, SandboxSession sandbox, BlockInput block, BlockPos first, BlockPos second, List<String> warnings) throws CommandSyntaxException {
		BlockPos min = min(first, second);
		BlockPos max = max(first, second);
		ensureWithinLimit(boxVolume(min, max));
		EditRun run = new EditRun(level, block, sandboxMask(sandbox), warnings);
		for (int y = min.getY(); y <= max.getY(); y++) {
			for (int z = min.getZ(); z <= max.getZ(); z++) {
				for (int x = min.getX(); x <= max.getX(); x++) {
					run.place(new BlockPos(x, y, z));
				}
			}
		}
		return run.result();
	}

	public static GeometryEditResult fillBoxFromOrigin(ServerLevel level, SandboxSession sandbox, BlockInput block, BlockPos origin, int sizeX, int sizeY, int sizeZ, List<String> warnings) throws CommandSyntaxException {
		BlockPos second = new BlockPos(origin.getX() + endpointOffset(sizeX), origin.getY() + endpointOffset(sizeY), origin.getZ() + endpointOffset(sizeZ));
		return fillBox(level, sandbox, block, origin, second, warnings);
	}

	public static GeometryEditResult makeEllipsoid(ServerLevel level, SandboxSession sandbox, BlockInput block, double centerX, double centerY, double centerZ, double radiusX, double radiusY, double radiusZ, List<String> warnings) throws CommandSyntaxException {
		double effectiveRadiusX = radiusX + 0.5D;
		double effectiveRadiusY = radiusY + 0.5D;
		double effectiveRadiusZ = radiusZ + 0.5D;
		int minX = (int) Math.floor(centerX - effectiveRadiusX);
		int maxX = (int) Math.ceil(centerX + effectiveRadiusX);
		int minY = (int) Math.floor(centerY - effectiveRadiusY);
		int maxY = (int) Math.ceil(centerY + effectiveRadiusY);
		int minZ = (int) Math.floor(centerZ - effectiveRadiusZ);
		int maxZ = (int) Math.ceil(centerZ + effectiveRadiusZ);
		ensureWithinLimit(boxVolume(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ)));

		EditRun run = new EditRun(level, block, sandboxMask(sandbox), warnings);
		for (int y = minY; y <= maxY; y++) {
			double yn = (y - centerY) / effectiveRadiusY;
			for (int z = minZ; z <= maxZ; z++) {
				double zn = (z - centerZ) / effectiveRadiusZ;
				for (int x = minX; x <= maxX; x++) {
					double xn = (x - centerX) / effectiveRadiusX;
					if (lengthSq(xn, yn, zn) <= 1.0D) {
						run.place(new BlockPos(x, y, z));
					}
				}
			}
		}
		return run.result();
	}

	public static GeometryEditResult makeEllipsoidInBox(ServerLevel level, SandboxSession sandbox, BlockInput block, BlockPos first, BlockPos second, List<String> warnings) throws CommandSyntaxException {
		BlockPos min = min(first, second);
		BlockPos max = max(first, second);
		double centerX = (min.getX() + max.getX()) / 2.0D;
		double centerY = (min.getY() + max.getY()) / 2.0D;
		double centerZ = (min.getZ() + max.getZ()) / 2.0D;
		double radiusX = (max.getX() - min.getX()) / 2.0D;
		double radiusY = (max.getY() - min.getY()) / 2.0D;
		double radiusZ = (max.getZ() - min.getZ()) / 2.0D;
		return makeEllipsoid(level, sandbox, block, centerX, centerY, centerZ, radiusX, radiusY, radiusZ, warnings);
	}

	public static GeometryEditResult makeCylinder(ServerLevel level, SandboxSession sandbox, BlockInput block, BlockPos center, GeometryAxisDirection axisDirection, int height, double radius, List<String> warnings) throws CommandSyntaxException {
		double effectiveRadius = radius + 0.5D;
		int ceilRadius = (int) Math.ceil(effectiveRadius);
		long estimate = (long) height * ((long) ceilRadius * 2L + 1L) * ((long) ceilRadius * 2L + 1L);
		ensureWithinLimit(estimate);

		Direction direction = axisDirection.direction();
		Direction.Axis axis = direction.getAxis();
		EditRun run = new EditRun(level, block, sandboxMask(sandbox), warnings);
		for (int h = 0; h < height; h++) {
			BlockPos sliceCenter = center.relative(direction, h);
			for (int a = -ceilRadius; a <= ceilRadius; a++) {
				double an = a / effectiveRadius;
				for (int b = -ceilRadius; b <= ceilRadius; b++) {
					double bn = b / effectiveRadius;
					if (lengthSq(an, bn) <= 1.0D) {
						run.place(offsetPerpendicular(sliceCenter, axis, a, b));
					}
				}
			}
		}
		return run.result();
	}

	private static GeometryMask sandboxMask(SandboxSession sandbox) {
		BlockPos min = sandbox.min();
		BlockPos max = sandbox.max();
		return pos -> pos.getX() >= min.getX() && pos.getX() <= max.getX()
				&& pos.getY() >= min.getY() && pos.getY() <= max.getY()
				&& pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
	}

	private static BlockPos offsetPerpendicular(BlockPos center, Direction.Axis axis, int a, int b) {
		return switch (axis) {
			case X -> center.offset(0, a, b);
			case Y -> center.offset(a, 0, b);
			case Z -> center.offset(a, b, 0);
		};
	}

	private static int endpointOffset(int size) {
		return size > 0 ? size - 1 : size + 1;
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

	private static double lengthSq(double x, double y) {
		return x * x + y * y;
	}

	private static double lengthSq(double x, double y, double z) {
		return x * x + y * y + z * z;
	}

	private static final class EditRun {
		private final ServerLevel level;
		private final BlockInput block;
		private final GeometryMask hardMask;
		private final Set<Long> visited = new HashSet<>();
		private final List<String> warnings;
		private long candidates;
		private int changed;
		private int unchanged;
		private int skippedOutsideSandbox;
		private int skippedOutsideWorld;

		private EditRun(ServerLevel level, BlockInput block, GeometryMask hardMask, List<String> warnings) {
			this.level = level;
			this.block = block;
			this.hardMask = hardMask;
			this.warnings = warnings;
		}

		private void place(BlockPos pos) {
			if (!visited.add(pos.asLong())) {
				return;
			}

			candidates++;
			if (!hardMask.test(pos)) {
				skippedOutsideSandbox++;
				return;
			}
			if (pos.getY() < level.getMinY() || pos.getY() >= level.getMaxY()) {
				skippedOutsideWorld++;
				return;
			}

			if (block.place(level, pos, Block.UPDATE_ALL)) {
				changed++;
			} else {
				unchanged++;
			}
		}

		private GeometryEditResult result() {
			return new GeometryEditResult(candidates, changed, unchanged, skippedOutsideSandbox, skippedOutsideWorld, List.copyOf(warnings));
		}
	}
}
