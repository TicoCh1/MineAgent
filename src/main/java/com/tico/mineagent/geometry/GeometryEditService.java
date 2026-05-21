package com.tico.mineagent.geometry;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import com.tico.mineagent.history.AgentBlockChange;
import com.tico.mineagent.history.AgentBlockSnapshot;
import com.tico.mineagent.history.AgentEditRecord;
import com.tico.mineagent.sandbox.SandboxSession;

public final class GeometryEditService {
	private GeometryEditService() {
	}

	public static GeometryEditResult setBlock(ServerLevel level, SandboxSession sandbox, BlockInput block, BlockPos pos, List<String> warnings) throws CommandSyntaxException {
		EditRegionLimiter.ensureSandboxCanContain(level, sandbox, pos, pos, warnings, "set_block");
		EditRun run = new EditRun(level, block, sandbox, sandboxMask(sandbox), warnings, "set_block");
		run.place(pos);
		return run.result();
	}

	public static GeometryEditResult fillBox(ServerLevel level, SandboxSession sandbox, BlockInput block, BlockPos first, BlockPos second, List<String> warnings) throws CommandSyntaxException {
		return fillBox(level, sandbox, block, first, second, warnings, "box");
	}

	public static GeometryEditResult generateExpression(ServerLevel level, SandboxSession sandbox, BlockInput block, BlockPos first, BlockPos second, WorldEditExpression expression, List<String> warnings) throws CommandSyntaxException {
		BlockPos min = min(first, second);
		BlockPos max = max(first, second);
		WorldEditExpression.Bounds expressionBounds = WorldEditExpression.Bounds.of(min, max);
		BlockState defaultState = block.getState();
		WorldEditExpression.BlockTypeData defaultTypeData = typeData(defaultState);
		boolean dynamicOutputBlock = expression.effects().dynamicOutputBlock();
		WorldEditExpression.BlockSampler blockSampler = queryPos -> EditRegionLimiter.insideWorld(level, queryPos)
				? typeData(level.getBlockState(queryPos))
				: new WorldEditExpression.BlockTypeData(-1, -1);
		EditRegionLimiter.ClippedBox region = EditRegionLimiter.clipBox(level, sandbox, min, max, warnings, "gen");
		EditRun run = new EditRun(level, block, sandbox, sandboxMask(sandbox), warnings, "gen");
		run.addPreSkipped(region.skippedOutsideSandbox(), region.skippedOutsideWorld());
		boolean[] warnedNonFinite = {false};
		if (region.hasBlocks()) {
			forEachInBox(region.min(), region.max(), pos -> {
				WorldEditExpression.EvaluationResult evaluation = expression.evaluate(pos, expressionBounds, defaultTypeData, blockSampler);
				double value = evaluation.value();
				if (!Double.isFinite(value)) {
					run.skipByMask();
					if (!warnedNonFinite[0]) {
						warnings.add("gen expression returned NaN or infinity for at least one block; those positions were skipped.");
						warnedNonFinite[0] = true;
					}
					return;
				}
				if (value > 0.0D) {
					if (dynamicOutputBlock) {
						run.placeState(pos, stateFromTypeData(evaluation.typeData(), defaultState, warnings));
					} else {
						run.place(pos);
					}
				} else {
					run.skipByMask();
				}
			});
		}
		return run.result();
	}

	private static GeometryEditResult fillBox(ServerLevel level, SandboxSession sandbox, BlockInput block, BlockPos first, BlockPos second, List<String> warnings, String label) throws CommandSyntaxException {
		BlockPos min = min(first, second);
		BlockPos max = max(first, second);
		EditRegionLimiter.ClippedBox region = EditRegionLimiter.clipBox(level, sandbox, min, max, warnings, label);
		EditRun run = new EditRun(level, block, sandbox, sandboxMask(sandbox), warnings, label);
		run.addPreSkipped(region.skippedOutsideSandbox(), region.skippedOutsideWorld());
		if (region.hasBlocks()) {
			forEachInBox(region.min(), region.max(), run::place);
		}
		return run.result();
	}

	public static GeometryEditResult fillBoxFromOrigin(ServerLevel level, SandboxSession sandbox, BlockInput block, BlockPos origin, int sizeX, int sizeY, int sizeZ, List<String> warnings) throws CommandSyntaxException {
		BlockPos second = new BlockPos(
				endpoint(origin.getX(), sizeX, "size_x endpoint", warnings),
				endpoint(origin.getY(), sizeY, "size_y endpoint", warnings),
				endpoint(origin.getZ(), sizeZ, "size_z endpoint", warnings));
		return fillBox(level, sandbox, block, origin, second, warnings, "box_origin");
	}

	public static GeometryEditResult makeEllipsoid(ServerLevel level, SandboxSession sandbox, BlockInput block, double centerX, double centerY, double centerZ, double radiusX, double radiusY, double radiusZ, List<String> warnings) throws CommandSyntaxException {
		double effectiveRadiusX = radiusX + 0.5D;
		double effectiveRadiusY = radiusY + 0.5D;
		double effectiveRadiusZ = radiusZ + 0.5D;
		int minX = floorToBlockCoordinate(centerX - effectiveRadiusX, "ellipsoid min x", warnings);
		int maxX = ceilToBlockCoordinate(centerX + effectiveRadiusX, "ellipsoid max x", warnings);
		int minY = floorToBlockCoordinate(centerY - effectiveRadiusY, "ellipsoid min y", warnings);
		int maxY = ceilToBlockCoordinate(centerY + effectiveRadiusY, "ellipsoid max y", warnings);
		int minZ = floorToBlockCoordinate(centerZ - effectiveRadiusZ, "ellipsoid min z", warnings);
		int maxZ = ceilToBlockCoordinate(centerZ + effectiveRadiusZ, "ellipsoid max z", warnings);
		EditRegionLimiter.ClippedBox region = EditRegionLimiter.clipBox(level, sandbox, new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), warnings, "ellipsoid");

		EditRun run = new EditRun(level, block, sandbox, sandboxMask(sandbox), warnings, "ellipsoid");
		run.addPreSkipped(region.skippedOutsideSandbox(), region.skippedOutsideWorld());
		if (region.hasBlocks()) {
			for (long y = region.min().getY(); y <= region.max().getY(); y++) {
				double yn = (y - centerY) / effectiveRadiusY;
				for (long z = region.min().getZ(); z <= region.max().getZ(); z++) {
					double zn = (z - centerZ) / effectiveRadiusZ;
					for (long x = region.min().getX(); x <= region.max().getX(); x++) {
						double xn = (x - centerX) / effectiveRadiusX;
						if (lengthSq(xn, yn, zn) <= 1.0D) {
							run.place(new BlockPos((int) x, (int) y, (int) z));
						}
					}
				}
			}
		}
		return run.result();
	}

	public static GeometryEditResult makeEllipsoidInBox(ServerLevel level, SandboxSession sandbox, BlockInput block, BlockPos first, BlockPos second, List<String> warnings) throws CommandSyntaxException {
		BlockPos min = min(first, second);
		BlockPos max = max(first, second);
		double centerX = midpoint(min.getX(), max.getX());
		double centerY = midpoint(min.getY(), max.getY());
		double centerZ = midpoint(min.getZ(), max.getZ());
		double radiusX = ((long) max.getX() - min.getX()) / 2.0D;
		double radiusY = ((long) max.getY() - min.getY()) / 2.0D;
		double radiusZ = ((long) max.getZ() - min.getZ()) / 2.0D;
		return makeEllipsoid(level, sandbox, block, centerX, centerY, centerZ, radiusX, radiusY, radiusZ, warnings);
	}

	public static GeometryEditResult makeCylinder(ServerLevel level, SandboxSession sandbox, BlockInput block, BlockPos center, GeometryAxisDirection axisDirection, int height, double radius, List<String> warnings) throws CommandSyntaxException {
		double effectiveRadius = radius + 0.5D;
		int ceilRadius = ceilToBlockCoordinate(effectiveRadius, "cylinder radius", warnings);

		Direction direction = axisDirection.direction();
		Direction.Axis axis = direction.getAxis();
		EditRegionLimiter.Box rawBounds = cylinderBounds(center, direction, height, ceilRadius, warnings);
		EditRegionLimiter.ClippedBox region = EditRegionLimiter.clipBox(level, sandbox, rawBounds.min(), rawBounds.max(), warnings, "cylinder");
		EditRun run = new EditRun(level, block, sandbox, sandboxMask(sandbox), warnings, "cylinder");
		run.addPreSkipped(region.skippedOutsideSandbox(), region.skippedOutsideWorld());
		if (region.hasBlocks()) {
			for (long y = region.min().getY(); y <= region.max().getY(); y++) {
				for (long z = region.min().getZ(); z <= region.max().getZ(); z++) {
					for (long x = region.min().getX(); x <= region.max().getX(); x++) {
						if (insideCylinder(x, y, z, center, direction, axis, height, effectiveRadius)) {
							run.place(new BlockPos((int) x, (int) y, (int) z));
						}
					}
				}
			}
		}
		return run.result();
	}

	public static GeometryEditResult makeLine(ServerLevel level, SandboxSession sandbox, BlockInput block, BlockPos first, BlockPos second, double thickness, List<String> warnings) throws CommandSyntaxException {
		EditRegionLimiter.requireBudget("line rasterization", lineStepCount(first, second));
		Set<BlockPos> positions = linePositions(first, second);
		return placePositions(level, sandbox, block, ballooned(positions, thickness), warnings, "line");
	}

	public static GeometryEditResult makeCurve(ServerLevel level, SandboxSession sandbox, BlockInput block, List<BlockPos> points, double thickness, int degree, List<String> warnings) throws CommandSyntaxException {
		if (points.size() < 3) {
			throw new IllegalArgumentException("Curve requires at least 3 points.");
		}
		if (degree < 1) {
			throw new IllegalArgumentException("Curve degree must be >= 1.");
		}
		int effectiveDegree = Math.min(degree, points.size() - 1);
		if (effectiveDegree != degree) {
			warnings.add("curve degree " + degree + " exceeded available points and was reduced to " + effectiveDegree + ".");
		}
		double length = Math.max(1.0D, polylineLength(points));
		long sampleCount = Math.max(1L, (long) Math.ceil(length * 10.0D));
		EditRegionLimiter.requireBudget("curve sampling", sampleCount);
		int samples = (int) sampleCount;

		List<Vec3> nodes = centered(points);
		Set<BlockPos> positions = new HashSet<>();
		for (int i = 0; i <= samples; i++) {
			double u = (points.size() - 1) * (i / (double) samples);
			Vec3 point = effectiveDegree == 3 ? cubicPosition(nodes, u) : polynomialPosition(nodes, u, effectiveDegree);
			positions.add(toBlockPos(point));
		}
		return placePositions(level, sandbox, block, ballooned(positions, thickness), warnings, "curve");
	}

	private static GeometryEditResult placePositions(ServerLevel level, SandboxSession sandbox, BlockInput block, Set<BlockPos> positions, List<String> warnings, String label) throws CommandSyntaxException {
		EditRegionLimiter.Box bounds = boundsOf(positions);
		if (bounds != null) {
			EditRegionLimiter.ensureSandboxCanContain(level, sandbox, bounds.min(), bounds.max(), warnings, label);
		}
		EditRun run = new EditRun(level, block, sandbox, sandboxMask(sandbox), warnings, label);
		for (BlockPos pos : positions) {
			run.place(pos);
		}
		return run.result();
	}

	private static Set<BlockPos> linePositions(BlockPos first, BlockPos second) {
		Set<BlockPos> positions = new HashSet<>();
		int x1 = first.getX();
		int y1 = first.getY();
		int z1 = first.getZ();
		int x2 = second.getX();
		int y2 = second.getY();
		int z2 = second.getZ();
		int dx = Math.abs(x2 - x1);
		int dy = Math.abs(y2 - y1);
		int dz = Math.abs(z2 - z1);
		if (dx + dy + dz == 0) {
			positions.add(first);
			return positions;
		}

		int dMax = Math.max(Math.max(dx, dy), dz);
		if (dMax == dx) {
			for (int step = 0; step <= dx; step++) {
				int x = x1 + step * sign(x2 - x1);
				int y = (int) Math.round(y1 + step * (dy / (double) dx) * sign(y2 - y1));
				int z = (int) Math.round(z1 + step * (dz / (double) dx) * sign(z2 - z1));
				positions.add(new BlockPos(x, y, z));
			}
		} else if (dMax == dy) {
			for (int step = 0; step <= dy; step++) {
				int y = y1 + step * sign(y2 - y1);
				int x = (int) Math.round(x1 + step * (dx / (double) dy) * sign(x2 - x1));
				int z = (int) Math.round(z1 + step * (dz / (double) dy) * sign(z2 - z1));
				positions.add(new BlockPos(x, y, z));
			}
		} else {
			for (int step = 0; step <= dz; step++) {
				int z = z1 + step * sign(z2 - z1);
				int y = (int) Math.round(y1 + step * (dy / (double) dz) * sign(y2 - y1));
				int x = (int) Math.round(x1 + step * (dx / (double) dz) * sign(x2 - x1));
				positions.add(new BlockPos(x, y, z));
			}
		}
		return positions;
	}

	private static Set<BlockPos> ballooned(Set<BlockPos> positions, double radius) {
		Set<BlockPos> result = new HashSet<>();
		int ceilRadius = (int) Math.ceil(radius);
		double radiusSquare = radius * radius;
		long diameter = ceilRadius * 2L + 1L;
		long kernelVolume = EditRegionLimiter.saturatedMultiply(EditRegionLimiter.saturatedMultiply(diameter, diameter), diameter);
		EditRegionLimiter.requireBudget("thickness expansion", EditRegionLimiter.saturatedMultiply(positions.size(), kernelVolume));
		for (BlockPos pos : positions) {
			for (long x = (long) pos.getX() - ceilRadius; x <= (long) pos.getX() + ceilRadius; x++) {
				for (long y = (long) pos.getY() - ceilRadius; y <= (long) pos.getY() + ceilRadius; y++) {
					for (long z = (long) pos.getZ() - ceilRadius; z <= (long) pos.getZ() + ceilRadius; z++) {
						if (lengthSq(x - pos.getX(), y - pos.getY(), z - pos.getZ()) <= radiusSquare) {
							if (isBlockCoordinate(x) && isBlockCoordinate(y) && isBlockCoordinate(z)) {
								result.add(new BlockPos((int) x, (int) y, (int) z));
							}
						}
					}
				}
			}
		}
		return result;
	}

	private static int sign(int value) {
		return value > 0 ? 1 : value < 0 ? -1 : 0;
	}

	private static boolean isBlockCoordinate(long value) {
		return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
	}

	private static List<Vec3> centered(List<BlockPos> points) {
		List<Vec3> result = new ArrayList<>(points.size());
		for (BlockPos point : points) {
			result.add(new Vec3(point.getX() + 0.5D, point.getY() + 0.5D, point.getZ() + 0.5D));
		}
		return result;
	}

	private static double polylineLength(List<BlockPos> points) {
		double length = 0.0D;
		for (int i = 0; i < points.size() - 1; i++) {
			length += distance(points.get(i), points.get(i + 1));
		}
		return length;
	}

	private static double distance(BlockPos a, BlockPos b) {
		long dx = (long) b.getX() - a.getX();
		long dy = (long) b.getY() - a.getY();
		long dz = (long) b.getZ() - a.getZ();
		return Math.sqrt(lengthSq(dx, dy, dz));
	}

	private static Vec3 cubicPosition(List<Vec3> nodes, double u) {
		int last = nodes.size() - 1;
		if (u >= last) {
			return nodes.get(last);
		}
		int i = Math.max(0, Math.min((int) Math.floor(u), last - 1));
		double t = u - i;
		Vec3 p0 = nodes.get(Math.max(0, i - 1));
		Vec3 p1 = nodes.get(i);
		Vec3 p2 = nodes.get(Math.min(last, i + 1));
		Vec3 p3 = nodes.get(Math.min(last, i + 2));
		double t2 = t * t;
		double t3 = t2 * t;
		return new Vec3(
				0.5D * ((2.0D * p1.x()) + (-p0.x() + p2.x()) * t + (2.0D * p0.x() - 5.0D * p1.x() + 4.0D * p2.x() - p3.x()) * t2 + (-p0.x() + 3.0D * p1.x() - 3.0D * p2.x() + p3.x()) * t3),
				0.5D * ((2.0D * p1.y()) + (-p0.y() + p2.y()) * t + (2.0D * p0.y() - 5.0D * p1.y() + 4.0D * p2.y() - p3.y()) * t2 + (-p0.y() + 3.0D * p1.y() - 3.0D * p2.y() + p3.y()) * t3),
				0.5D * ((2.0D * p1.z()) + (-p0.z() + p2.z()) * t + (2.0D * p0.z() - 5.0D * p1.z() + 4.0D * p2.z() - p3.z()) * t2 + (-p0.z() + 3.0D * p1.z() - 3.0D * p2.z() + p3.z()) * t3));
	}

	private static Vec3 polynomialPosition(List<Vec3> nodes, double u, int degree) {
		int window = Math.min(degree + 1, nodes.size());
		int start = Math.max(0, Math.min((int) Math.floor(u) - degree / 2, nodes.size() - window));
		Vec3 result = new Vec3(0.0D, 0.0D, 0.0D);
		for (int i = start; i < start + window; i++) {
			double basis = 1.0D;
			for (int j = start; j < start + window; j++) {
				if (i != j) {
					basis *= (u - j) / (double) (i - j);
				}
			}
			Vec3 node = nodes.get(i);
			result = result.add(node.multiply(basis));
		}
		return result;
	}

	private static BlockPos toBlockPos(Vec3 point) {
		return new BlockPos((int) Math.floor(point.x()), (int) Math.floor(point.y()), (int) Math.floor(point.z()));
	}

	private static EditRegionLimiter.Box boundsOf(Set<BlockPos> positions) {
		if (positions.isEmpty()) {
			return null;
		}
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (BlockPos pos : positions) {
			minX = Math.min(minX, pos.getX());
			minY = Math.min(minY, pos.getY());
			minZ = Math.min(minZ, pos.getZ());
			maxX = Math.max(maxX, pos.getX());
			maxY = Math.max(maxY, pos.getY());
			maxZ = Math.max(maxZ, pos.getZ());
		}
		return new EditRegionLimiter.Box(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
	}

	private static GeometryMask sandboxMask(SandboxSession sandbox) {
		BlockPos min = sandbox.editMin();
		BlockPos max = sandbox.editMax();
		return pos -> pos.getX() >= min.getX() && pos.getX() <= max.getX()
				&& pos.getY() >= min.getY() && pos.getY() <= max.getY()
				&& pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
	}

	private static int endpointOffset(int size) {
		return size > 0 ? size - 1 : size + 1;
	}

	private static int endpoint(int origin, int size, String name, List<String> warnings) {
		return EditRegionLimiter.clampToBlockCoordinate((long) origin + endpointOffset(size), name, warnings);
	}

	private static int floorToBlockCoordinate(double value, String name, List<String> warnings) {
		return EditRegionLimiter.clampToBlockCoordinate((long) Math.floor(value), name, warnings);
	}

	private static int ceilToBlockCoordinate(double value, String name, List<String> warnings) {
		return EditRegionLimiter.clampToBlockCoordinate((long) Math.ceil(value), name, warnings);
	}

	private static double midpoint(int min, int max) {
		return min + (((long) max - min) / 2.0D);
	}

	private static long lineStepCount(BlockPos first, BlockPos second) {
		long dx = Math.abs((long) second.getX() - first.getX());
		long dy = Math.abs((long) second.getY() - first.getY());
		long dz = Math.abs((long) second.getZ() - first.getZ());
		return Math.max(Math.max(dx, dy), dz) + 1L;
	}

	private static EditRegionLimiter.Box cylinderBounds(BlockPos center, Direction direction, int height, int ceilRadius, List<String> warnings) {
		long endX = center.getX() + (long) direction.getStepX() * (height - 1L);
		long endY = center.getY() + (long) direction.getStepY() * (height - 1L);
		long endZ = center.getZ() + (long) direction.getStepZ() * (height - 1L);
		long minX = Math.min(center.getX(), endX);
		long minY = Math.min(center.getY(), endY);
		long minZ = Math.min(center.getZ(), endZ);
		long maxX = Math.max(center.getX(), endX);
		long maxY = Math.max(center.getY(), endY);
		long maxZ = Math.max(center.getZ(), endZ);
		if (direction.getAxis() != Direction.Axis.X) {
			minX -= ceilRadius;
			maxX += ceilRadius;
		}
		if (direction.getAxis() != Direction.Axis.Y) {
			minY -= ceilRadius;
			maxY += ceilRadius;
		}
		if (direction.getAxis() != Direction.Axis.Z) {
			minZ -= ceilRadius;
			maxZ += ceilRadius;
		}
		return new EditRegionLimiter.Box(
				new BlockPos(
						EditRegionLimiter.clampToBlockCoordinate(minX, "cylinder min x", warnings),
						EditRegionLimiter.clampToBlockCoordinate(minY, "cylinder min y", warnings),
						EditRegionLimiter.clampToBlockCoordinate(minZ, "cylinder min z", warnings)),
				new BlockPos(
						EditRegionLimiter.clampToBlockCoordinate(maxX, "cylinder max x", warnings),
						EditRegionLimiter.clampToBlockCoordinate(maxY, "cylinder max y", warnings),
						EditRegionLimiter.clampToBlockCoordinate(maxZ, "cylinder max z", warnings)));
	}

	private static boolean insideCylinder(long x, long y, long z, BlockPos center, Direction direction, Direction.Axis axis, int height, double effectiveRadius) {
		double axial = switch (axis) {
			case X -> (x - center.getX()) * direction.getStepX();
			case Y -> (y - center.getY()) * direction.getStepY();
			case Z -> (z - center.getZ()) * direction.getStepZ();
		};
		if (axial < 0.0D || axial >= height) {
			return false;
		}
		double a = switch (axis) {
			case X -> y - center.getY();
			case Y -> x - center.getX();
			case Z -> x - center.getX();
		};
		double b = switch (axis) {
			case X -> z - center.getZ();
			case Y -> z - center.getZ();
			case Z -> y - center.getY();
		};
		return lengthSq(a / effectiveRadius, b / effectiveRadius) <= 1.0D;
	}

	private static void forEachInBox(BlockPos min, BlockPos max, BlockConsumer consumer) throws CommandSyntaxException {
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

	private static double lengthSq(double x, double y) {
		return x * x + y * y;
	}

	private static double lengthSq(double x, double y, double z) {
		return x * x + y * y + z * z;
	}

	private static WorldEditExpression.BlockTypeData typeData(BlockState state) {
		Block block = state.getBlock();
		int type = BuiltInRegistries.BLOCK.getId(block);
		List<BlockState> states = block.getStateDefinition().getPossibleStates();
		int data = states.indexOf(state);
		return new WorldEditExpression.BlockTypeData(type, Math.max(0, data));
	}

	private static BlockState stateFromTypeData(WorldEditExpression.BlockTypeData typeData, BlockState fallback, List<String> warnings) {
		Block block = BuiltInRegistries.BLOCK.byId(typeData.type());
		if (block == null) {
			warnings.add("gen expression produced unknown type id " + typeData.type() + "; used the target block state instead.");
			return fallback;
		}
		List<BlockState> states = block.getStateDefinition().getPossibleStates();
		if (states.isEmpty()) {
			return block.defaultBlockState();
		}
		if (typeData.data() < 0 || typeData.data() >= states.size()) {
			warnings.add("gen expression produced state data " + typeData.data() + " outside block " + BuiltInRegistries.BLOCK.getKey(block) + " state range 0.." + (states.size() - 1) + "; used that block's default state.");
			return block.defaultBlockState();
		}
		return states.get(typeData.data());
	}

	private interface BlockConsumer {
		void accept(BlockPos pos) throws CommandSyntaxException;
	}

	private record Vec3(double x, double y, double z) {
		private Vec3 add(Vec3 other) {
			return new Vec3(x + other.x, y + other.y, z + other.z);
		}

		private Vec3 multiply(double scalar) {
			return new Vec3(x * scalar, y * scalar, z * scalar);
		}
	}

	private static final class EditRun {
		private final ServerLevel level;
		private final BlockInput block;
		private final SandboxSession sandbox;
		private final GeometryMask hardMask;
		private final Set<Long> visited = new HashSet<>();
		private final List<String> warnings;
		private final String label;
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

		private EditRun(ServerLevel level, BlockInput block, SandboxSession sandbox, GeometryMask hardMask, List<String> warnings, String label) {
			sandbox.requirePrototypeEditSlot(label);
			this.level = level;
			this.block = block;
			this.sandbox = sandbox;
			this.hardMask = hardMask;
			this.warnings = warnings;
			this.label = label;
		}

		private void place(BlockPos pos) {
			if (!visited.add(pos.asLong())) {
				return;
			}

			candidates = EditRegionLimiter.saturatedAdd(candidates, 1L);
			if (!hardMask.test(pos)) {
				skippedOutsideSandbox = EditRegionLimiter.saturatedAdd(skippedOutsideSandbox, 1L);
				return;
			}
			if (!EditRegionLimiter.insideWorld(level, pos)) {
				skippedOutsideWorld = EditRegionLimiter.saturatedAdd(skippedOutsideWorld, 1L);
				return;
			}

			includeAffected(pos);
			AgentBlockSnapshot before = AgentBlockSnapshot.capture(level, pos);
			if (block.place(level, pos, Block.UPDATE_ALL)) {
				changed++;
			} else {
				unchanged++;
			}
			AgentBlockSnapshot after = AgentBlockSnapshot.capture(level, pos);
			AgentBlockChange change = new AgentBlockChange(before, after);
			if (change.changed()) {
				changes.add(change);
			}
		}

		private void placeState(BlockPos pos, BlockState state) {
			if (!visited.add(pos.asLong())) {
				return;
			}

			candidates = EditRegionLimiter.saturatedAdd(candidates, 1L);
			if (!hardMask.test(pos)) {
				skippedOutsideSandbox = EditRegionLimiter.saturatedAdd(skippedOutsideSandbox, 1L);
				return;
			}
			if (!EditRegionLimiter.insideWorld(level, pos)) {
				skippedOutsideWorld = EditRegionLimiter.saturatedAdd(skippedOutsideWorld, 1L);
				return;
			}

			includeAffected(pos);
			AgentBlockSnapshot before = AgentBlockSnapshot.capture(level, pos);
			if (level.setBlock(pos, state, Block.UPDATE_ALL)) {
				changed++;
			} else {
				unchanged++;
			}
			AgentBlockSnapshot after = AgentBlockSnapshot.capture(level, pos);
			AgentBlockChange change = new AgentBlockChange(before, after);
			if (change.changed()) {
				changes.add(change);
			}
		}

		private void skipByMask() {
			candidates = EditRegionLimiter.saturatedAdd(candidates, 1L);
			skippedByMask = EditRegionLimiter.saturatedAdd(skippedByMask, 1L);
		}

		private void addPreSkipped(long outsideSandbox, long outsideWorld) {
			skippedOutsideSandbox = EditRegionLimiter.saturatedAdd(skippedOutsideSandbox, outsideSandbox);
			skippedOutsideWorld = EditRegionLimiter.saturatedAdd(skippedOutsideWorld, outsideWorld);
			candidates = EditRegionLimiter.saturatedAdd(candidates, EditRegionLimiter.saturatedAdd(outsideSandbox, outsideWorld));
		}

		private GeometryEditResult result() {
			BlockPos affectedMin = hasAffectedBounds ? new BlockPos(minX, minY, minZ) : BlockPos.ZERO;
			BlockPos affectedMax = hasAffectedBounds ? new BlockPos(maxX, maxY, maxZ) : BlockPos.ZERO;
			if (!changes.isEmpty()) {
				sandbox.recordEdit(new AgentEditRecord(label, changes, affectedMin, affectedMax));
			}
			return new GeometryEditResult(candidates, changed, unchanged, skippedOutsideSandbox, skippedOutsideWorld, skippedByMask, hasAffectedBounds, affectedMin, affectedMax, List.copyOf(warnings));
		}

		private void includeAffected(BlockPos pos) {
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
}
