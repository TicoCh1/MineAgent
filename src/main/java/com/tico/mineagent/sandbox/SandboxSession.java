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
import com.tico.mineagent.structure.StructureComponentTracker;

public final class SandboxSession {
	private static final int MAX_HISTORY_RECORDS = 32;
	public static final int MAX_PROTOTYPE_VOLUME = 4096;
	public static final int MAX_PROTOTYPE_DIMENSION = 64;
	public static final int MAX_PROTOTYPE_EDIT_RECORDS = 128;
	private boolean toolEnabled;
	private SandboxSelectorType selectorType = SandboxSelectorType.CUBOID;
	private SandboxPermissionMode permissionMode = SandboxPermissionMode.STRICT;
	private BlockPos primary;
	private BlockPos secondary;
	private BlockPos prototypeMin;
	private BlockPos prototypeMax;
	private int prototypeEditRecords;
	private String activeProjectId = "default";
	private final Map<String, BlockPos> anchors = new HashMap<>();
	private final Map<String, AgentMaskDefinition> masks = new HashMap<>();
	private final Deque<AgentEditRecord> undoHistory = new ArrayDeque<>();
	private final Deque<AgentEditRecord> redoHistory = new ArrayDeque<>();
	private final Map<String, StructureComponentTracker> structuresByProject = new HashMap<>();
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

		clearPrototypeSandbox();
		this.selectorType = selectorType;
		if (selectorType == SandboxSelectorType.EXTENDING_CUBOID && hasCompleteBounds()) {
			primary = min();
			secondary = max();
		}
	}

	public SandboxPermissionMode permissionMode() {
		return permissionMode;
	}

	public void setPermissionMode(SandboxPermissionMode permissionMode) {
		this.permissionMode = permissionMode == null ? SandboxPermissionMode.STRICT : permissionMode;
	}

	public void selectPrimary(BlockPos pos) {
		clearPrototypeSandbox();
		if (selectorType == SandboxSelectorType.EXTENDING_CUBOID) {
			primary = pos;
			secondary = pos;
			return;
		}

		primary = pos;
	}

	public void selectSecondary(BlockPos pos) {
		clearPrototypeSandbox();
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

	public PrototypeSandbox createPrototypeSandbox(BlockPos first, BlockPos second) {
		if (!hasCompleteBounds()) {
			throw new IllegalStateException("MineAgent sandbox is incomplete. Select the main sandbox before creating a prototype sandbox.");
		}
		BlockPos min = new BlockPos(
				Math.min(first.getX(), second.getX()),
				Math.min(first.getY(), second.getY()),
				Math.min(first.getZ(), second.getZ()));
		BlockPos max = new BlockPos(
				Math.max(first.getX(), second.getX()),
				Math.max(first.getY(), second.getY()),
				Math.max(first.getZ(), second.getZ()));
		int sizeX = max.getX() - min.getX() + 1;
		int sizeY = max.getY() - min.getY() + 1;
		int sizeZ = max.getZ() - min.getZ() + 1;
		long volume = (long) sizeX * sizeY * sizeZ;
		if (sizeX > MAX_PROTOTYPE_DIMENSION || sizeY > MAX_PROTOTYPE_DIMENSION || sizeZ > MAX_PROTOTYPE_DIMENSION) {
			throw new IllegalArgumentException("Prototype sandbox dimensions must each be <= " + MAX_PROTOTYPE_DIMENSION + " blocks. Requested " + sizeX + "x" + sizeY + "x" + sizeZ + ".");
		}
		if (volume > MAX_PROTOTYPE_VOLUME) {
			throw new IllegalArgumentException("Prototype sandbox volume must be <= " + MAX_PROTOTYPE_VOLUME + " blocks. Requested " + volume + ".");
		}
		if (!contains(min(), max(), min) || !contains(min(), max(), max)) {
			throw new IllegalArgumentException("Prototype sandbox must be fully inside the main MineAgent sandbox.");
		}

		prototypeMin = min;
		prototypeMax = max;
		prototypeEditRecords = 0;
		return prototypeSandbox();
	}

	public boolean clearPrototypeSandbox() {
		boolean hadPrototype = hasPrototypeSandbox();
		prototypeMin = null;
		prototypeMax = null;
		prototypeEditRecords = 0;
		return hadPrototype;
	}

	public boolean hasPrototypeSandbox() {
		return prototypeMin != null && prototypeMax != null;
	}

	public PrototypeSandbox prototypeSandbox() {
		if (!hasPrototypeSandbox()) {
			return null;
		}
		return new PrototypeSandbox(prototypeMin, prototypeMax, prototypeEditRecords, MAX_PROTOTYPE_EDIT_RECORDS);
	}

	public BlockPos editMin() {
		return hasPrototypeSandbox() ? prototypeMin : min();
	}

	public BlockPos editMax() {
		return hasPrototypeSandbox() ? prototypeMax : max();
	}

	public String editBoundaryName() {
		return hasPrototypeSandbox() ? "prototype sandbox" : "sandbox";
	}

	public String editBoundsSummary() {
		BlockPos min = editMin();
		BlockPos max = editMax();
		return "(%d, %d, %d) -> (%d, %d, %d)".formatted(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
	}

	public void requirePrototypeEditSlot(String operation) {
		if (hasPrototypeSandbox() && prototypeEditRecords >= MAX_PROTOTYPE_EDIT_RECORDS) {
			throw new IllegalStateException(operation + " cannot run in the active prototype sandbox because its "
					+ MAX_PROTOTYPE_EDIT_RECORDS + "-edit-record budget is exhausted. Clear or recreate the prototype sandbox.");
		}
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

	public String activeProjectId() {
		return activeProjectId;
	}

	public void setActiveProjectId(String activeProjectId) {
		String next = activeProjectId == null || activeProjectId.isBlank() ? "default" : activeProjectId;
		if (this.activeProjectId.equals(next)) {
			return;
		}
		this.activeProjectId = next;
		this.clipboard = null;
		clearPrototypeSandbox();
	}

	public StructureComponentTracker structures() {
		return structuresByProject.computeIfAbsent(activeProjectId, ignored -> new StructureComponentTracker());
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
		requirePrototypeEditSlot(record.label());
		undoHistory.push(record);
		redoHistory.clear();
		while (undoHistory.size() > MAX_HISTORY_RECORDS) {
			undoHistory.removeLast();
		}
		if (hasPrototypeSandbox()) {
			prototypeEditRecords++;
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

	public List<AgentEditRecord> undoHistorySnapshot() {
		return List.copyOf(undoHistory);
	}

	public List<AgentEditRecord> redoHistorySnapshot() {
		return List.copyOf(redoHistory);
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

	public void expandToInclude(BlockPos requestedMin, BlockPos requestedMax) {
		BlockPos min = min();
		BlockPos max = max();
		setBounds(
				new BlockPos(
						Math.min(min.getX(), requestedMin.getX()),
						Math.min(min.getY(), requestedMin.getY()),
						Math.min(min.getZ(), requestedMin.getZ())),
				new BlockPos(
						Math.max(max.getX(), requestedMax.getX()),
						Math.max(max.getY(), requestedMax.getY()),
						Math.max(max.getZ(), requestedMax.getZ())));
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

	public void setBounds(BlockPos first, BlockPos second) {
		BlockPos min = new BlockPos(
				Math.min(first.getX(), second.getX()),
				Math.min(first.getY(), second.getY()),
				Math.min(first.getZ(), second.getZ()));
		BlockPos max = new BlockPos(
				Math.max(first.getX(), second.getX()),
				Math.max(first.getY(), second.getY()),
				Math.max(first.getZ(), second.getZ()));
		primary = min;
		secondary = max;
		if (hasPrototypeSandbox() && (!contains(min, max, prototypeMin) || !contains(min, max, prototypeMax))) {
			clearPrototypeSandbox();
		}
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

	public record PrototypeSandbox(BlockPos min, BlockPos max, int usedEditRecords, int maxEditRecords) {
		public PrototypeSandbox {
			min = min.immutable();
			max = max.immutable();
		}

		public int remainingEditRecords() {
			return Math.max(0, maxEditRecords - usedEditRecords);
		}

		public String size() {
			return "%dx%dx%d".formatted(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
		}

		public long volume() {
			return (long) (max.getX() - min.getX() + 1)
					* (max.getY() - min.getY() + 1L)
					* (max.getZ() - min.getZ() + 1L);
		}
	}
}
