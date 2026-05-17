package com.tico.mineagent.geometry;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

import com.tico.mineagent.history.AgentBlockChange;
import com.tico.mineagent.history.AgentBlockSnapshot;
import com.tico.mineagent.history.AgentEditRecord;
import com.tico.mineagent.sandbox.SandboxSession;

public final class GeometryEditService {
	private GeometryEditService() {
	}

	public static GeometryEditResult setBlock(ServerLevel level, SandboxSession sandbox, BlockInput block, BlockPos pos, List<String> warnings) throws CommandSyntaxException {
		EditRun run = new EditRun(level, block, sandbox, sandboxMask(sandbox), warnings, "set_block");
		run.place(pos);
		return run.result();
	}

	public static GeometryEditResult fillBox(ServerLevel level, SandboxSession sandbox, BlockInput block, BlockPos first, BlockPos second, List<String> warnings) throws CommandSyntaxException {
		return fillBox(level, sandbox, block, first, second, warnings, "box");
	}

	private static GeometryEditResult fillBox(ServerLevel level, SandboxSession sandbox, BlockInput block, BlockPos first, BlockPos second, List<String> warnings, String label) throws CommandSyntaxException {
		BlockPos min = min(first, second);
		BlockPos max = max(first, second);
		EditRun run = new EditRun(level, block, sandbox, sandboxMask(sandbox), warnings, label);
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
		return fillBox(level, sandbox, block, origin, second, warnings, "box_origin");
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

		EditRun run = new EditRun(level, block, sandbox, sandboxMask(sandbox), warnings, "ellipsoid");
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

		Direction direction = axisDirection.direction();
		Direction.Axis axis = direction.getAxis();
		EditRun run = new EditRun(level, block, sandbox, sandboxMask(sandbox), warnings, "cylinder");
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

	public static GeometryEditResult makeLine(ServerLevel level, SandboxSession sandbox, BlockInput block, BlockPos first, BlockPos second, double thickness, List<String> warnings) throws CommandSyntaxException {
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
		int samples = Math.max(1, (int) Math.ceil(length * 10.0D));

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
		for (BlockPos pos : positions) {
			for (int x = pos.getX() - ceilRadius; x <= pos.getX() + ceilRadius; x++) {
				for (int y = pos.getY() - ceilRadius; y <= pos.getY() + ceilRadius; y++) {
					for (int z = pos.getZ() - ceilRadius; z <= pos.getZ() + ceilRadius; z++) {
						if (lengthSq(x - pos.getX(), y - pos.getY(), z - pos.getZ()) <= radiusSquare) {
							result.add(new BlockPos(x, y, z));
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
		int dx = b.getX() - a.getX();
		int dy = b.getY() - a.getY();
		int dz = b.getZ() - a.getZ();
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
		private int skippedOutsideSandbox;
		private int skippedOutsideWorld;
		private boolean hasAffectedBounds;
		private int minX;
		private int minY;
		private int minZ;
		private int maxX;
		private int maxY;
		private int maxZ;

		private EditRun(ServerLevel level, BlockInput block, SandboxSession sandbox, GeometryMask hardMask, List<String> warnings, String label) {
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

			candidates++;
			if (!hardMask.test(pos)) {
				skippedOutsideSandbox++;
				return;
			}
			if (pos.getY() < level.getMinY() || pos.getY() >= level.getMaxY()) {
				skippedOutsideWorld++;
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

		private GeometryEditResult result() {
			BlockPos affectedMin = hasAffectedBounds ? new BlockPos(minX, minY, minZ) : BlockPos.ZERO;
			BlockPos affectedMax = hasAffectedBounds ? new BlockPos(maxX, maxY, maxZ) : BlockPos.ZERO;
			if (!changes.isEmpty()) {
				sandbox.recordEdit(new AgentEditRecord(label, changes, affectedMin, affectedMax));
			}
			return new GeometryEditResult(candidates, changed, unchanged, skippedOutsideSandbox, skippedOutsideWorld, 0, hasAffectedBounds, affectedMin, affectedMax, List.copyOf(warnings));
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
