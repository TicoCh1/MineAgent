package com.tico.mineagent.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public final class StructureComponentTracker {
	public static final String MINIMUM_SIZE_RULE = "at least 2x2x1 blocks in any orientation; two dimensions must be >=2 and the third must be >=1";
	private final Map<String, Component> components = new LinkedHashMap<>();
	private int nextComponentNumber = 1;
	private int nextPatternNumber = 1;
	private long nextOrder = 1L;

	public synchronized boolean empty() {
		return components.isEmpty();
	}

	public synchronized int componentCount() {
		return components.size();
	}

	public synchronized Registration define(String name, BlockPos first, BlockPos second, String similarTo, String notes, List<String> warnings) {
		BlockPos min = min(first, second);
		BlockPos max = max(first, second);
		requireMinimumSize(min, max);
		requireNoDuplicate(min, max);

		String patternId = null;
		String sourceId = "";
		if (similarTo != null && !similarTo.isBlank()) {
			Component similar = resolveComponent(similarTo);
			if (similar == null) {
				warnings.add("Structure component similar_to='" + similarTo + "' did not match an existing component; MineAgent created a new pattern id.");
			} else if (sameOrRotatedSize(similar.min, similar.max, min, max)) {
				patternId = similar.patternId;
				sourceId = similar.id;
				if (!sameSize(similar.min, similar.max, min, max)) {
					warnings.add("Structure component " + sourceId + " has the same sorted dimensions but a different orientation; MineAgent shares its pattern id for rotation/mirror-style similarity.");
				}
			} else {
				warnings.add("Structure component similar_to='" + similarTo + "' has incompatible dimensions; MineAgent created a new pattern id.");
			}
		}

		if (patternId == null) {
			patternId = nextPatternId();
		}
		return register(name, min, max, patternId, sourceId, patternId, "manual_define", 0, 0, 0, "", 0, notes, false, warnings);
	}

	public synchronized List<StructureClipboardEntry> copyEntries(BlockPos first, BlockPos second, BlockPos reference) {
		BlockPos min = min(first, second);
		BlockPos max = max(first, second);
		List<Component> contained = containedComponents(min, max);
		contained.sort(StructureComponentTracker::parentBeforeChild);
		List<StructureClipboardEntry> entries = new ArrayList<>();
		for (Component component : contained) {
			entries.add(new StructureClipboardEntry(
					component.id,
					component.name,
					component.patternId,
					component.min,
					component.max,
					offset(component.min, reference),
					offset(component.max, reference),
					component.parentId));
		}
		return List.copyOf(entries);
	}

	public synchronized List<Registration> pasteEntries(List<StructureClipboardEntry> entries, BlockPos reference, List<String> warnings) {
		List<Registration> registered = new ArrayList<>();
		for (StructureClipboardEntry entry : sortedClipboardEntries(entries)) {
			BlockPos targetMin = reference.offset(entry.relativeMin());
			BlockPos targetMax = reference.offset(entry.relativeMax());
			if (!hasMinimumSize(targetMin, targetMax)) {
				warnings.add("Skipped traced pasted component from " + entry.sourceId() + " because its target bounds are below the minimum size rule.");
				continue;
			}
			Registration registration = register(
					entry.sourceName(),
					targetMin,
					targetMax,
					entry.patternId(),
					entry.sourceId(),
					entry.patternId(),
					"paste",
					targetMin.getX() - entry.sourceMin().getX(),
					targetMin.getY() - entry.sourceMin().getY(),
					targetMin.getZ() - entry.sourceMin().getZ(),
					"",
					0,
					"",
					true,
					warnings);
			if (registration != null) {
				registered.add(registration);
			}
		}
		return List.copyOf(registered);
	}

	public synchronized List<Registration> stack(BlockPos first, BlockPos second, Direction direction, int count, List<String> warnings) {
		BlockPos min = min(first, second);
		BlockPos max = max(first, second);
		List<Component> contained = containedComponents(min, max);
		if (contained.isEmpty()) {
			return List.of();
		}
		contained.sort(StructureComponentTracker::parentBeforeChild);

		BlockPos singleOffset = stackOffset(min, max, direction);
		List<Registration> registered = new ArrayList<>();
		for (int repetition = 1; repetition <= count; repetition++) {
			int dx = singleOffset.getX() * repetition;
			int dy = singleOffset.getY() * repetition;
			int dz = singleOffset.getZ() * repetition;
			for (Component component : contained) {
				BlockPos targetMin = component.min.offset(dx, dy, dz);
				BlockPos targetMax = component.max.offset(dx, dy, dz);
				Registration registration = register(
						component.name,
						targetMin,
						targetMax,
						component.patternId,
						component.id,
						component.patternId,
						"stack",
						dx,
						dy,
						dz,
						axisId(direction),
						repetition,
						"",
						true,
						warnings);
				if (registration != null) {
					registered.add(registration);
				}
			}
		}
		return List.copyOf(registered);
	}

	public synchronized List<Registration> rotate(BlockPos first, BlockPos second, BlockPos reference, int degrees, List<String> warnings) {
		int normalizedDegrees = normalizeDegrees(degrees);
		BlockPos min = min(first, second);
		BlockPos max = max(first, second);
		List<Component> contained = containedComponents(min, max);
		if (contained.isEmpty()) {
			return List.of();
		}

		List<Registration> transformed = new ArrayList<>();
		for (Component component : contained) {
			Bounds bounds = transformedBounds(component.min, component.max, pos -> rotateY(pos, reference, normalizedDegrees));
			component.min = bounds.min();
			component.max = bounds.max();
			component.copiedFromComponentId = "";
			component.copiedFromPatternId = "";
			component.operation = "rotate";
			component.offsetX = 0;
			component.offsetY = 0;
			component.offsetZ = 0;
			component.axis = "";
			component.repetition = 0;
			component.rotationDegrees = normalizedDegrees;
			component.mirrorPlane = "";
			transformed.add(new Registration(component.id, component.name, component.patternId, component.min, component.max, component.parentId));
		}
		rebuildHierarchy();
		return List.copyOf(transformed);
	}

	public synchronized List<Registration> flip(BlockPos first, BlockPos second, BlockPos reference, String plane, List<String> warnings) {
		String normalizedPlane = normalizePlane(plane);
		BlockPos min = min(first, second);
		BlockPos max = max(first, second);
		List<Component> contained = containedComponents(min, max);
		if (contained.isEmpty()) {
			return List.of();
		}

		List<Registration> transformed = new ArrayList<>();
		for (Component component : contained) {
			Bounds bounds = transformedBounds(component.min, component.max, pos -> mirror(pos, reference, normalizedPlane));
			component.min = bounds.min();
			component.max = bounds.max();
			component.copiedFromComponentId = "";
			component.copiedFromPatternId = "";
			component.operation = "flip";
			component.offsetX = 0;
			component.offsetY = 0;
			component.offsetZ = 0;
			component.axis = "";
			component.repetition = 0;
			component.rotationDegrees = 0;
			component.mirrorPlane = normalizedPlane;
			transformed.add(new Registration(component.id, component.name, component.patternId, component.min, component.max, component.parentId));
		}
		rebuildHierarchy();
		return List.copyOf(transformed);
	}

	public synchronized JsonObject toJson() {
		JsonObject object = new JsonObject();
		object.addProperty("schema_version", 1);
		object.addProperty("component_count", components.size());
		object.addProperty("pattern_count", patternIds().size());
		object.addProperty("minimum_size_rule", MINIMUM_SIZE_RULE);
		object.add("patterns", patternsJson());

		JsonArray roots = new JsonArray();
		for (Component component : components.values()) {
			if (component.parentId.isBlank()) {
				roots.add(componentJson(component));
			}
		}
		object.add("components", roots);
		return object;
	}

	public synchronized JsonObject componentJson(String id) {
		Component component = components.get(id);
		if (component == null) {
			throw new IllegalArgumentException("Unknown structure component id: " + id);
		}
		return componentJson(component);
	}

	public synchronized JsonArray registrationJson(List<Registration> registrations) {
		JsonArray array = new JsonArray();
		for (Registration registration : registrations) {
			if (components.containsKey(registration.id())) {
				array.add(componentJson(registration.id()));
			}
		}
		return array;
	}

	public synchronized ImpactResult traceEditImpact(BlockPos first, BlockPos second, String operation, boolean deleteContained) {
		BlockPos min = min(first, second);
		BlockPos max = max(first, second);
		if (components.isEmpty()) {
			return new ImpactResult(operation, min, max, deleteContained, List.of(), List.of());
		}

		Set<String> deleteRootIds = new LinkedHashSet<>();
		if (deleteContained) {
			List<Component> contained = containedComponents(min, max);
			contained.sort(StructureComponentTracker::parentBeforeChild);
			for (Component component : contained) {
				if (!isDescendantOfAny(component.id, deleteRootIds)) {
					deleteRootIds.add(component.id);
				}
			}
		}

		Set<String> deleteIds = new LinkedHashSet<>();
		for (String rootId : deleteRootIds) {
			collectSubtree(rootId, deleteIds);
		}

		List<ImpactItem> deleted = new ArrayList<>();
		for (Component component : components.values()) {
			if (deleteIds.contains(component.id)) {
				deleted.add(impactItem(component));
			}
		}
		if (!deleteIds.isEmpty()) {
			for (String id : deleteIds) {
				components.remove(id);
			}
			rebuildHierarchy();
		}

		List<ImpactItem> possiblyStale = new ArrayList<>();
		for (Component component : components.values()) {
			if (intersects(min, max, component.min, component.max)) {
				possiblyStale.add(impactItem(component));
			}
		}

		return new ImpactResult(operation, min, max, deleteContained, deleted, possiblyStale);
	}

	public synchronized List<Registration> move(BlockPos first, BlockPos second, BlockPos fromReference, BlockPos toReference, List<String> warnings) {
		BlockPos min = min(first, second);
		BlockPos max = max(first, second);
		long dx = (long) toReference.getX() - fromReference.getX();
		long dy = (long) toReference.getY() - fromReference.getY();
		long dz = (long) toReference.getZ() - fromReference.getZ();
		List<Component> contained = containedComponents(min, max);
		if (contained.isEmpty()) {
			return List.of();
		}
		contained.sort(StructureComponentTracker::parentBeforeChild);

		Set<String> movedIds = new LinkedHashSet<>();
		for (Component component : contained) {
			movedIds.add(component.id);
		}

		List<Registration> moved = new ArrayList<>();
		for (Component component : contained) {
			BlockPos targetMin = offset(component.min, dx, dy, dz);
			BlockPos targetMax = offset(component.max, dx, dy, dz);
			Component duplicate = componentWithSameBounds(targetMin, targetMax, movedIds);
			if (duplicate != null) {
				warnings.add("Moved traced structure component " + component.id + " now shares bounds with existing component " + duplicate.id + "; inspect the structure trace before relying on either component.");
			}
			component.min = targetMin;
			component.max = targetMax;
			component.copiedFromComponentId = "";
			component.copiedFromPatternId = "";
			component.operation = "move";
			component.offsetX = blockCoordinate(dx);
			component.offsetY = blockCoordinate(dy);
			component.offsetZ = blockCoordinate(dz);
			component.axis = "";
			component.repetition = 0;
			component.rotationDegrees = 0;
			component.mirrorPlane = "";
			moved.add(new Registration(component.id, component.name, component.patternId, component.min, component.max, component.parentId));
		}
		rebuildHierarchy();
		return List.copyOf(moved);
	}

	private Registration register(
			String rawName,
			BlockPos rawMin,
			BlockPos rawMax,
			String patternId,
			String copiedFromComponentId,
			String copiedFromPatternId,
			String operation,
			int offsetX,
			int offsetY,
			int offsetZ,
			String axis,
			int repetition,
			String notes,
			boolean skipDuplicate,
			List<String> warnings) {
		BlockPos min = min(rawMin, rawMax);
		BlockPos max = max(rawMin, rawMax);
		if (!hasMinimumSize(min, max)) {
			throw new IllegalArgumentException("Structure component bbox is too small. Minimum rule: " + MINIMUM_SIZE_RULE + ".");
		}

		Component duplicate = componentWithSameBounds(min, max);
		if (duplicate != null) {
			if (skipDuplicate) {
				warnings.add("Skipped duplicate traced structure component at " + boundsSummary(min, max) + "; existing component is " + duplicate.id + ".");
				return null;
			}
			throw new IllegalArgumentException("A structure component already exists at " + boundsSummary(min, max) + ": " + duplicate.id + " (" + duplicate.name + ").");
		}

		List<String> partialOverlaps = partialOverlaps(min, max);
		if (!partialOverlaps.isEmpty()) {
			warnings.add("Structure component overlaps existing component(s) without containment: " + String.join(", ", partialOverlaps) + ". The tree keeps them as siblings; prefer a containing parent bbox when this is intentional.");
		}

		String parentId = smallestContainingParent(min, max);
		String id = nextComponentId();
		Component component = new Component(
				id,
				cleanName(rawName),
				patternId == null || patternId.isBlank() ? nextPatternId() : patternId,
				min,
				max,
				parentId,
				copiedFromComponentId == null ? "" : copiedFromComponentId,
				copiedFromPatternId == null ? "" : copiedFromPatternId,
				operation == null || operation.isBlank() ? "manual_define" : operation,
				offsetX,
				offsetY,
				offsetZ,
				axis == null ? "" : axis,
				repetition,
				0,
				"",
				notes == null ? "" : notes.trim(),
				nextOrder++);

		for (Component child : components.values()) {
			if (child.parentId.equals(parentId) && contains(min, max, child.min, child.max)) {
				child.parentId = id;
			}
		}
		components.put(id, component);
		return new Registration(component.id, component.name, component.patternId, component.min, component.max, component.parentId);
	}

	private List<Component> containedComponents(BlockPos min, BlockPos max) {
		List<Component> contained = new ArrayList<>();
		for (Component component : components.values()) {
			if (contains(min, max, component.min, component.max)) {
				contained.add(component);
			}
		}
		return contained;
	}

	private static int parentBeforeChild(Component first, Component second) {
		int volume = Long.compare(volume(second.min, second.max), volume(first.min, first.max));
		if (volume != 0) {
			return volume;
		}
		return Long.compare(first.order, second.order);
	}

	private List<StructureClipboardEntry> sortedClipboardEntries(List<StructureClipboardEntry> entries) {
		List<StructureClipboardEntry> sorted = new ArrayList<>(entries);
		sorted.sort((first, second) -> {
			int volume = Long.compare(volume(second.relativeMin(), second.relativeMax()), volume(first.relativeMin(), first.relativeMax()));
			if (volume != 0) {
				return volume;
			}
			return first.sourceId().compareTo(second.sourceId());
		});
		return sorted;
	}

	private String smallestContainingParent(BlockPos min, BlockPos max) {
		return smallestContainingParent("", min, max);
	}

	private String smallestContainingParent(String excludeId, BlockPos min, BlockPos max) {
		Component best = null;
		for (Component component : components.values()) {
			if (component.id.equals(excludeId)) {
				continue;
			}
			if (!contains(component.min, component.max, min, max)) {
				continue;
			}
			if (sameBounds(component.min, component.max, min, max)) {
				continue;
			}
			if (best == null || volume(component.min, component.max) < volume(best.min, best.max)) {
				best = component;
			}
		}
		return best == null ? "" : best.id;
	}

	private List<String> partialOverlaps(BlockPos min, BlockPos max) {
		List<String> overlaps = new ArrayList<>();
		for (Component component : components.values()) {
			if (!intersects(min, max, component.min, component.max)) {
				continue;
			}
			if (contains(min, max, component.min, component.max) || contains(component.min, component.max, min, max)) {
				continue;
			}
			overlaps.add(component.id);
		}
		return overlaps;
	}

	private Component componentWithSameBounds(BlockPos min, BlockPos max) {
		return componentWithSameBounds(min, max, Set.of());
	}

	private Component componentWithSameBounds(BlockPos min, BlockPos max, Set<String> excludedIds) {
		for (Component component : components.values()) {
			if (excludedIds.contains(component.id)) {
				continue;
			}
			if (sameBounds(component.min, component.max, min, max)) {
				return component;
			}
		}
		return null;
	}

	private void requireNoDuplicate(BlockPos min, BlockPos max) {
		Component duplicate = componentWithSameBounds(min, max);
		if (duplicate != null) {
			throw new IllegalArgumentException("A structure component already exists at " + boundsSummary(min, max) + ": " + duplicate.id + " (" + duplicate.name + ").");
		}
	}

	private Component resolveComponent(String raw) {
		String value = raw.trim();
		Component byId = components.get(value);
		if (byId != null) {
			return byId;
		}
		String normalized = value.toLowerCase(Locale.ROOT);
		Component match = null;
		for (Component component : components.values()) {
			if (component.name.toLowerCase(Locale.ROOT).equals(normalized)) {
				if (match != null) {
					return null;
				}
				match = component;
			}
		}
		return match;
	}

	private void rebuildHierarchy() {
		for (Component component : components.values()) {
			component.parentId = "";
		}
		for (Component component : components.values()) {
			component.parentId = smallestContainingParent(component.id, component.min, component.max);
		}
	}

	private boolean isDescendantOfAny(String id, Set<String> ancestorIds) {
		Component component = components.get(id);
		while (component != null && !component.parentId.isBlank()) {
			if (ancestorIds.contains(component.parentId)) {
				return true;
			}
			component = components.get(component.parentId);
		}
		return false;
	}

	private void collectSubtree(String id, Set<String> ids) {
		if (!ids.add(id)) {
			return;
		}
		for (Component component : components.values()) {
			if (component.parentId.equals(id)) {
				collectSubtree(component.id, ids);
			}
		}
	}

	private static ImpactItem impactItem(Component component) {
		return new ImpactItem(component.id, component.name, component.patternId, component.min, component.max, component.parentId);
	}

	private JsonObject componentJson(Component component) {
		JsonObject object = new JsonObject();
		object.addProperty("id", component.id);
		object.addProperty("name", component.name);
		object.addProperty("pattern_id", component.patternId);
		if (component.parentId.isBlank()) {
			object.add("parent_id", JsonNull.INSTANCE);
		} else {
			object.addProperty("parent_id", component.parentId);
		}
		object.add("bbox", bbox(component.min, component.max));
		object.add("copy", copyJson(component));
		object.add("similar_component_ids", similarIds(component));
		if (!component.notes.isBlank()) {
			object.addProperty("notes", component.notes);
		}

		JsonArray children = new JsonArray();
		for (Component child : components.values()) {
			if (child.parentId.equals(component.id)) {
				children.add(componentJson(child));
			}
		}
		object.add("children", children);
		return object;
	}

	private JsonObject copyJson(Component component) {
		JsonObject object = new JsonObject();
		object.addProperty("is_copy", !component.copiedFromComponentId.isBlank());
		object.addProperty("operation", component.operation);
		if (component.copiedFromComponentId.isBlank()) {
			object.add("copied_from_component_id", JsonNull.INSTANCE);
		} else {
			object.addProperty("copied_from_component_id", component.copiedFromComponentId);
		}
		if (component.copiedFromPatternId.isBlank()) {
			object.add("copied_from_pattern_id", JsonNull.INSTANCE);
		} else {
			object.addProperty("copied_from_pattern_id", component.copiedFromPatternId);
		}
		JsonObject transform = new JsonObject();
		transform.addProperty("kind", transformKind(component.operation));
		transform.addProperty("offset_x", component.offsetX);
		transform.addProperty("offset_y", component.offsetY);
		transform.addProperty("offset_z", component.offsetZ);
		if (!component.axis.isBlank()) {
			transform.addProperty("axis", component.axis);
			transform.addProperty("repetition", component.repetition);
		}
		if (component.rotationDegrees != 0) {
			transform.addProperty("rotation_degrees_y", component.rotationDegrees);
		}
		if (!component.mirrorPlane.isBlank()) {
			transform.addProperty("mirror_plane", component.mirrorPlane);
		}
		object.add("transform", transform);
		return object;
	}

	private static String transformKind(String operation) {
		return switch (operation) {
			case "paste", "stack", "move" -> "translation";
			case "rotate" -> "rotation";
			case "flip" -> "mirror";
			default -> "identity_or_manual_similarity";
		};
	}

	private JsonArray similarIds(Component component) {
		JsonArray array = new JsonArray();
		for (Component other : components.values()) {
			if (!other.id.equals(component.id) && other.patternId.equals(component.patternId)) {
				array.add(other.id);
			}
		}
		return array;
	}

	private JsonArray patternsJson() {
		JsonArray array = new JsonArray();
		for (String patternId : patternIds()) {
			JsonObject pattern = new JsonObject();
			pattern.addProperty("pattern_id", patternId);
			JsonArray componentIds = new JsonArray();
			for (Component component : components.values()) {
				if (component.patternId.equals(patternId)) {
					componentIds.add(component.id);
				}
			}
			pattern.add("component_ids", componentIds);
			array.add(pattern);
		}
		return array;
	}

	private Set<String> patternIds() {
		Set<String> patternIds = new LinkedHashSet<>();
		for (Component component : components.values()) {
			patternIds.add(component.patternId);
		}
		return patternIds;
	}

	private static JsonObject bbox(BlockPos min, BlockPos max) {
		JsonObject object = new JsonObject();
		object.add("min", pos(min));
		object.add("max", pos(max));
		JsonObject size = new JsonObject();
		size.addProperty("x", max.getX() - min.getX() + 1);
		size.addProperty("y", max.getY() - min.getY() + 1);
		size.addProperty("z", max.getZ() - min.getZ() + 1);
		size.addProperty("text", sizeSummary(min, max));
		object.add("size", size);
		object.addProperty("volume", volume(min, max));
		return object;
	}

	private static JsonObject pos(BlockPos pos) {
		JsonObject object = new JsonObject();
		object.addProperty("x", pos.getX());
		object.addProperty("y", pos.getY());
		object.addProperty("z", pos.getZ());
		object.addProperty("text", "%d,%d,%d".formatted(pos.getX(), pos.getY(), pos.getZ()));
		return object;
	}

	private String nextComponentId() {
		return "cmp_%04d".formatted(nextComponentNumber++);
	}

	private String nextPatternId() {
		return "pat_%04d".formatted(nextPatternNumber++);
	}

	private static String cleanName(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException("Structure component name must not be blank.");
		}
		String name = raw.trim();
		if (name.length() > 96) {
			return name.substring(0, 96);
		}
		return name;
	}

	private static void requireMinimumSize(BlockPos min, BlockPos max) {
		if (!hasMinimumSize(min, max)) {
			throw new IllegalArgumentException("Structure component bbox is too small. Minimum rule: " + MINIMUM_SIZE_RULE + ".");
		}
	}

	private static boolean hasMinimumSize(BlockPos min, BlockPos max) {
		int[] sizes = dimensions(min, max);
		Arrays.sort(sizes);
		return sizes[0] >= 1 && sizes[1] >= 2 && sizes[2] >= 2;
	}

	private static boolean sameSize(BlockPos firstMin, BlockPos firstMax, BlockPos secondMin, BlockPos secondMax) {
		return Arrays.equals(dimensions(firstMin, firstMax), dimensions(secondMin, secondMax));
	}

	private static boolean sameOrRotatedSize(BlockPos firstMin, BlockPos firstMax, BlockPos secondMin, BlockPos secondMax) {
		int[] first = dimensions(firstMin, firstMax);
		int[] second = dimensions(secondMin, secondMax);
		Arrays.sort(first);
		Arrays.sort(second);
		return Arrays.equals(first, second);
	}

	private static int[] dimensions(BlockPos min, BlockPos max) {
		return new int[] {
				max.getX() - min.getX() + 1,
				max.getY() - min.getY() + 1,
				max.getZ() - min.getZ() + 1
		};
	}

	private static BlockPos stackOffset(BlockPos min, BlockPos max, Direction direction) {
		int distance = switch (direction.getAxis()) {
			case X -> max.getX() - min.getX() + 1;
			case Y -> max.getY() - min.getY() + 1;
			case Z -> max.getZ() - min.getZ() + 1;
		};
		return new BlockPos(direction.getStepX() * distance, direction.getStepY() * distance, direction.getStepZ() * distance);
	}

	private static Bounds transformedBounds(BlockPos min, BlockPos max, ComponentTransform transform) {
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
		return new Bounds(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
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

	private static BlockPos mirror(BlockPos pos, BlockPos reference, String plane) {
		return switch (plane) {
			case "yz" -> new BlockPos(blockCoordinate(2L * reference.getX() - pos.getX()), pos.getY(), pos.getZ());
			case "xy" -> new BlockPos(pos.getX(), pos.getY(), blockCoordinate(2L * reference.getZ() - pos.getZ()));
			case "xz" -> new BlockPos(pos.getX(), blockCoordinate(2L * reference.getY() - pos.getY()), pos.getZ());
			default -> throw new IllegalArgumentException("Unsupported mirror plane: " + plane);
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

	private static String normalizePlane(String plane) {
		String normalized = plane == null ? "" : plane.trim().toLowerCase(Locale.ROOT);
		if (!normalized.equals("yz") && !normalized.equals("xy") && !normalized.equals("xz")) {
			throw new IllegalArgumentException("Mirror plane must be one of yz, xy, or xz.");
		}
		return normalized;
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

	private static BlockPos offset(BlockPos pos, BlockPos reference) {
		return new BlockPos(pos.getX() - reference.getX(), pos.getY() - reference.getY(), pos.getZ() - reference.getZ());
	}

	private static BlockPos offset(BlockPos pos, long dx, long dy, long dz) {
		return new BlockPos(
				blockCoordinate((long) pos.getX() + dx),
				blockCoordinate((long) pos.getY() + dy),
				blockCoordinate((long) pos.getZ() + dz));
	}

	private static String axisId(Direction direction) {
		return switch (direction) {
			case EAST -> "+x";
			case WEST -> "-x";
			case UP -> "+y";
			case DOWN -> "-y";
			case SOUTH -> "+z";
			case NORTH -> "-z";
		};
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

	private static boolean contains(BlockPos outerMin, BlockPos outerMax, BlockPos innerMin, BlockPos innerMax) {
		return innerMin.getX() >= outerMin.getX() && innerMax.getX() <= outerMax.getX()
				&& innerMin.getY() >= outerMin.getY() && innerMax.getY() <= outerMax.getY()
				&& innerMin.getZ() >= outerMin.getZ() && innerMax.getZ() <= outerMax.getZ();
	}

	private static boolean intersects(BlockPos firstMin, BlockPos firstMax, BlockPos secondMin, BlockPos secondMax) {
		return firstMin.getX() <= secondMax.getX() && firstMax.getX() >= secondMin.getX()
				&& firstMin.getY() <= secondMax.getY() && firstMax.getY() >= secondMin.getY()
				&& firstMin.getZ() <= secondMax.getZ() && firstMax.getZ() >= secondMin.getZ();
	}

	private static boolean sameBounds(BlockPos firstMin, BlockPos firstMax, BlockPos secondMin, BlockPos secondMax) {
		return firstMin.equals(secondMin) && firstMax.equals(secondMax);
	}

	private static long volume(BlockPos min, BlockPos max) {
		return (long) (max.getX() - min.getX() + 1)
				* (max.getY() - min.getY() + 1L)
				* (max.getZ() - min.getZ() + 1L);
	}

	private static String boundsSummary(BlockPos min, BlockPos max) {
		return "%d,%d,%d -> %d,%d,%d".formatted(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
	}

	private static String sizeSummary(BlockPos min, BlockPos max) {
		return "%dx%dx%d".formatted(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
	}

	public record Registration(String id, String name, String patternId, BlockPos min, BlockPos max, String parentId) {
		public Registration {
			min = min.immutable();
			max = max.immutable();
			parentId = parentId == null ? "" : parentId;
		}
	}

	public record ImpactResult(
			String operation,
			BlockPos affectedMin,
			BlockPos affectedMax,
			boolean deleteContained,
			List<ImpactItem> deletedComponents,
			List<ImpactItem> possiblyStaleComponents) {
		public ImpactResult {
			operation = operation == null || operation.isBlank() ? "edit" : operation;
			affectedMin = affectedMin.immutable();
			affectedMax = affectedMax.immutable();
			deletedComponents = List.copyOf(deletedComponents);
			possiblyStaleComponents = List.copyOf(possiblyStaleComponents);
		}

		public boolean changedTree() {
			return !deletedComponents.isEmpty();
		}

		public boolean affected() {
			return changedTree() || !possiblyStaleComponents.isEmpty();
		}

		public JsonObject toJson() {
			JsonObject object = new JsonObject();
			object.addProperty("operation", operation);
			object.add("affected_bounds", bbox(affectedMin, affectedMax));
			object.addProperty("delete_contained_components", deleteContained);
			object.addProperty("deleted_count", deletedComponents.size());
			object.addProperty("possibly_stale_count", possiblyStaleComponents.size());
			object.add("deleted_components", impactItemsJson(deletedComponents));
			object.add("possibly_stale_components", impactItemsJson(possiblyStaleComponents));
			if (changedTree()) {
				object.addProperty("message", "MineAgent deleted traced structure component metadata whose bbox was fully covered by this edit; define replacement components if the design changed into a new reusable structure.");
			} else if (affected()) {
				object.addProperty("message", "This edit intersected traced structure components. Their bbox metadata remains, but inspect or redefine them if the visual structure changed.");
			}
			return object;
		}

		private static JsonArray impactItemsJson(List<ImpactItem> items) {
			JsonArray array = new JsonArray();
			for (ImpactItem item : items) {
				array.add(item.toJson());
			}
			return array;
		}
	}

	public record ImpactItem(String id, String name, String patternId, BlockPos min, BlockPos max, String parentId) {
		public ImpactItem {
			min = min.immutable();
			max = max.immutable();
			parentId = parentId == null ? "" : parentId;
		}

		private JsonObject toJson() {
			JsonObject object = new JsonObject();
			object.addProperty("id", id);
			object.addProperty("name", name);
			object.addProperty("pattern_id", patternId);
			if (parentId.isBlank()) {
				object.add("parent_id", JsonNull.INSTANCE);
			} else {
				object.addProperty("parent_id", parentId);
			}
			object.add("bbox", bbox(min, max));
			return object;
		}
	}

	private interface ComponentTransform {
		BlockPos apply(BlockPos pos);
	}

	private record Bounds(BlockPos min, BlockPos max) {
	}

	private static final class Component {
		private final String id;
		private final String name;
		private final String patternId;
		private BlockPos min;
		private BlockPos max;
		private String parentId;
		private String copiedFromComponentId;
		private String copiedFromPatternId;
		private String operation;
		private int offsetX;
		private int offsetY;
		private int offsetZ;
		private String axis;
		private int repetition;
		private int rotationDegrees;
		private String mirrorPlane;
		private final String notes;
		private final long order;

		private Component(
				String id,
				String name,
				String patternId,
				BlockPos min,
				BlockPos max,
				String parentId,
				String copiedFromComponentId,
				String copiedFromPatternId,
				String operation,
				int offsetX,
				int offsetY,
				int offsetZ,
				String axis,
				int repetition,
				int rotationDegrees,
				String mirrorPlane,
				String notes,
				long order) {
			this.id = id;
			this.name = name;
			this.patternId = patternId;
			this.min = min.immutable();
			this.max = max.immutable();
			this.parentId = parentId == null ? "" : parentId;
			this.copiedFromComponentId = copiedFromComponentId;
			this.copiedFromPatternId = copiedFromPatternId;
			this.operation = operation;
			this.offsetX = offsetX;
			this.offsetY = offsetY;
			this.offsetZ = offsetZ;
			this.axis = axis;
			this.repetition = repetition;
			this.rotationDegrees = rotationDegrees;
			this.mirrorPlane = mirrorPlane;
			this.notes = notes;
			this.order = order;
		}
	}
}
