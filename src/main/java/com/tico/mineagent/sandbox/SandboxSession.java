package com.tico.mineagent.sandbox;

import java.util.Collections;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import com.tico.mineagent.clipboard.AgentClipboard;
import com.tico.mineagent.history.AgentEditRecord;
import com.tico.mineagent.mask.AgentMaskDefinition;
import com.tico.mineagent.network.SandboxStatePayload;

public final class SandboxSession {
	private static final int MAX_HISTORY_RECORDS = 32;
	private boolean toolEnabled;
	private SandboxSelectorType selectorType = SandboxSelectorType.CUBOID;
	private BlockPos primary;
	private BlockPos secondary;
	private final Map<String, BlockPos> anchors = new HashMap<>();
	private final Map<String, AgentMaskDefinition> masks = new HashMap<>();
	private final Deque<AgentEditRecord> undoHistory = new ArrayDeque<>();
	private final Deque<AgentEditRecord> redoHistory = new ArrayDeque<>();
	private AgentClipboard clipboard;

	public boolean toolEnabled() {
		return toolEnabled;
	}

	public void setToolEnabled(boolean toolEnabled) {
		this.toolEnabled = toolEnabled;
	}

	public SandboxSelectorType selectorType() {
		return selectorType;
	}

	public void setSelectorType(SandboxSelectorType selectorType) {
		if (this.selectorType == selectorType) {
			return;
		}

		this.selectorType = selectorType;
		if (selectorType == SandboxSelectorType.EXTENDING_CUBOID && hasCompleteBounds()) {
			primary = min();
			secondary = max();
		}
	}

	public void selectPrimary(BlockPos pos) {
		if (selectorType == SandboxSelectorType.EXTENDING_CUBOID) {
			primary = pos;
			secondary = pos;
			return;
		}

		primary = pos;
	}

	public void selectSecondary(BlockPos pos) {
		if (selectorType == SandboxSelectorType.EXTENDING_CUBOID) {
			if (primary == null || secondary == null) {
				selectPrimary(pos);
				return;
			}

			BlockPos min = min();
			BlockPos max = max();
			if (contains(min, max, pos)) {
				return;
			}

			primary = new BlockPos(
					Math.min(min.getX(), pos.getX()),
					Math.min(min.getY(), pos.getY()),
					Math.min(min.getZ(), pos.getZ()));
			secondary = new BlockPos(
					Math.max(max.getX(), pos.getX()),
					Math.max(max.getY(), pos.getY()),
					Math.max(max.getZ(), pos.getZ()));
			return;
		}

		secondary = pos;
	}

	public boolean hasCompleteBounds() {
		return primary != null && secondary != null;
	}

	public void setAnchor(String name, BlockPos pos) {
		anchors.put(normalizeAnchorName(name), pos);
	}

	public BlockPos anchor(String name) {
		return anchors.get(normalizeAnchorName(name));
	}

	public Map<String, BlockPos> anchors() {
		return Collections.unmodifiableMap(anchors);
	}

	public void setMask(AgentMaskDefinition mask) {
		masks.put(mask.name(), mask);
	}

	public AgentMaskDefinition mask(String name) {
		return masks.get(AgentMaskDefinition.normalizeName(name));
	}

	public Map<String, AgentMaskDefinition> masks() {
		return Collections.unmodifiableMap(masks);
	}

	public boolean removeMask(String name) {
		return masks.remove(AgentMaskDefinition.normalizeName(name)) != null;
	}

	public void setClipboard(AgentClipboard clipboard) {
		this.clipboard = clipboard;
	}

	public AgentClipboard clipboard() {
		return clipboard;
	}

	public void recordEdit(AgentEditRecord record) {
		if (record == null || record.empty()) {
			return;
		}
		undoHistory.push(record);
		redoHistory.clear();
		while (undoHistory.size() > MAX_HISTORY_RECORDS) {
			undoHistory.removeLast();
		}
	}

	public AgentEditRecord popUndo() {
		return undoHistory.pollFirst();
	}

	public AgentEditRecord popRedo() {
		return redoHistory.pollFirst();
	}

	public void pushUndo(AgentEditRecord record) {
		if (record != null && !record.empty()) {
			undoHistory.push(record);
		}
	}

	public void pushRedo(AgentEditRecord record) {
		if (record != null && !record.empty()) {
			redoHistory.push(record);
		}
	}

	public int undoCount() {
		return undoHistory.size();
	}

	public int redoCount() {
		return redoHistory.size();
	}

	public BlockPos min() {
		if (!hasCompleteBounds()) {
			return BlockPos.ZERO;
		}

		return new BlockPos(
				Math.min(primary.getX(), secondary.getX()),
				Math.min(primary.getY(), secondary.getY()),
				Math.min(primary.getZ(), secondary.getZ()));
	}

	public BlockPos max() {
		if (!hasCompleteBounds()) {
			return BlockPos.ZERO;
		}

		return new BlockPos(
				Math.max(primary.getX(), secondary.getX()),
				Math.max(primary.getY(), secondary.getY()),
				Math.max(primary.getZ(), secondary.getZ()));
	}

	public void expandVert(int minY, int maxY) {
		BlockPos min = min();
		BlockPos max = max();
		setBounds(new BlockPos(min.getX(), minY, min.getZ()), new BlockPos(max.getX(), maxY, max.getZ()));
	}

	public void expand(List<Direction> directions, int amount, int reverseAmount) {
		Bounds bounds = new Bounds(min(), max());
		for (Direction direction : directions) {
			bounds = expand(bounds, direction, amount);
			bounds = expand(bounds, direction.getOpposite(), reverseAmount);
		}

		setBounds(bounds.min(), bounds.max());
	}

	public boolean contract(List<Direction> directions, int amount, int reverseAmount) {
		Bounds bounds = new Bounds(min(), max());
		for (Direction direction : directions) {
			bounds = contract(bounds, direction, amount);
			bounds = contract(bounds, direction.getOpposite(), reverseAmount);
		}

		// Sandbox bounds are a safety boundary, so unlike WorldEdit's CuboidRegion
		// endpoint crossover behavior, MineAgent rejects inverted bounds.
		if (inverts(bounds.min(), bounds.max())) {
			return false;
		}

		setBounds(bounds.min(), bounds.max());
		return true;
	}

	public void shift(List<Direction> directions, int amount) {
		int x = 0;
		int y = 0;
		int z = 0;
		for (Direction direction : directions) {
			x += direction.getStepX() * amount;
			y += direction.getStepY() * amount;
			z += direction.getStepZ() * amount;
		}

		BlockPos delta = new BlockPos(x, y, z);
		setBounds(min().offset(delta), max().offset(delta));
	}

	public void outset(int amount, boolean horizontalOnly, boolean verticalOnly) {
		Bounds bounds = new Bounds(min(), max());
		for (Direction direction : directionsForEachAxis(horizontalOnly, verticalOnly)) {
			bounds = expand(bounds, direction, amount);
		}

		setBounds(bounds.min(), bounds.max());
	}

	public boolean inset(int amount, boolean horizontalOnly, boolean verticalOnly) {
		Bounds bounds = new Bounds(min(), max());
		for (Direction direction : directionsForEachAxis(horizontalOnly, verticalOnly)) {
			bounds = contract(bounds, direction, amount);
		}

		// Keep inset from turning a sandbox into a flipped or unexpectedly expanded box.
		if (inverts(bounds.min(), bounds.max())) {
			return false;
		}

		setBounds(bounds.min(), bounds.max());
		return true;
	}

	public String boundsSummary() {
		if (!hasCompleteBounds()) {
			return "incomplete";
		}

		BlockPos min = min();
		BlockPos max = max();
		return "(%d, %d, %d) -> (%d, %d, %d)".formatted(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
	}

	public SandboxStatePayload toPayload() {
		return toPayload(toolEnabled);
	}

	public SandboxStatePayload toPayload(boolean effectiveToolEnabled) {
		boolean complete = hasCompleteBounds();
		return new SandboxStatePayload(
				effectiveToolEnabled,
				selectorType.id(),
				complete,
				complete ? min() : BlockPos.ZERO,
				complete ? max() : BlockPos.ZERO,
				primary != null,
				primary != null ? primary : BlockPos.ZERO,
				secondary != null,
				secondary != null ? secondary : BlockPos.ZERO);
	}

	private void setBounds(BlockPos min, BlockPos max) {
		primary = min;
		secondary = max;
	}

	private static Bounds expand(Bounds bounds, Direction direction, int amount) {
		return expand(bounds, direction.getStepX() * amount, direction.getStepY() * amount, direction.getStepZ() * amount);
	}

	private static Bounds contract(Bounds bounds, Direction direction, int amount) {
		return contract(bounds, direction.getStepX() * amount, direction.getStepY() * amount, direction.getStepZ() * amount);
	}

	private static Bounds expand(Bounds bounds, int x, int y, int z) {
		BlockPos min = bounds.min();
		BlockPos max = bounds.max();
		int minX = min.getX();
		int minY = min.getY();
		int minZ = min.getZ();
		int maxX = max.getX();
		int maxY = max.getY();
		int maxZ = max.getZ();

		if (x > 0) {
			maxX += x;
		} else if (x < 0) {
			minX += x;
		}

		if (y > 0) {
			maxY += y;
		} else if (y < 0) {
			minY += y;
		}

		if (z > 0) {
			maxZ += z;
		} else if (z < 0) {
			minZ += z;
		}

		return new Bounds(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
	}

	private static Bounds contract(Bounds bounds, int x, int y, int z) {
		BlockPos min = bounds.min();
		BlockPos max = bounds.max();
		int minX = min.getX();
		int minY = min.getY();
		int minZ = min.getZ();
		int maxX = max.getX();
		int maxY = max.getY();
		int maxZ = max.getZ();

		if (x > 0) {
			minX += x;
		} else if (x < 0) {
			maxX += x;
		}

		if (y > 0) {
			minY += y;
		} else if (y < 0) {
			maxY += y;
		}

		if (z > 0) {
			minZ += z;
		} else if (z < 0) {
			maxZ += z;
		}

		return new Bounds(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
	}

	private static Direction[] directionsForEachAxis(boolean horizontalOnly, boolean verticalOnly) {
		if (horizontalOnly) {
			return new Direction[] { Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH };
		}
		if (verticalOnly) {
			return new Direction[] { Direction.UP, Direction.DOWN };
		}
		return new Direction[] { Direction.UP, Direction.DOWN, Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH };
	}

	private static boolean inverts(BlockPos min, BlockPos max) {
		return min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ();
	}

	private static boolean contains(BlockPos min, BlockPos max, BlockPos pos) {
		return pos.getX() >= min.getX() && pos.getX() <= max.getX()
				&& pos.getY() >= min.getY() && pos.getY() <= max.getY()
				&& pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
	}

	private static String normalizeAnchorName(String name) {
		return name.toLowerCase(Locale.ROOT);
	}

	private record Bounds(BlockPos min, BlockPos max) {
	}
}
