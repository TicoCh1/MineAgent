package com.tico.mineagent.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import com.tico.mineagent.geometry.GeometryAxisDirection;
import com.tico.mineagent.geometry.GeometryEditResult;
import com.tico.mineagent.geometry.GeometryEditService;
import com.tico.mineagent.geometry.GeometryParameterParser;
import com.tico.mineagent.geometry.GeometryParameterParser.ParsedBlockPos;
import com.tico.mineagent.geometry.GeometryParameterParser.ParsedDouble;
import com.tico.mineagent.geometry.GeometryParameterParser.ParsedInt;
import com.tico.mineagent.sandbox.SandboxSession;

public final class MineAgentToolRegistry {
	private final List<AgentTool> tools;

	private MineAgentToolRegistry(List<AgentTool> tools) {
		this.tools = tools;
	}

	public static MineAgentToolRegistry create(CommandBuildContext registryAccess) {
		List<AgentTool> tools = new ArrayList<>();
		tools.add(new AgentTool(
				"mineagent_get_sandbox",
				"Inspect the current player's MineAgent sandbox boundary, anchors, dimension, and player block position. Use this before any edit. This tool does not modify the world.",
				schema(properties(), required()),
				true,
				MineAgentToolRegistry::getSandbox));
		tools.add(new AgentTool(
				"mineagent_set_anchor",
				"Create or replace a named coordinate anchor inside the current sandbox session. Later coordinate parameters may use @name or @name+dx,dy,dz. The position is parsed with MineAgent coordinate syntax and rounded to an integer block coordinate if needed.",
				schema(properties(
						property("name", string("Anchor name. Use a short lowercase identifier such as base, center, north_wall.")),
						property("pos", string("Coordinate in x,y,z, ~ relative, or @anchor+dx,dy,dz form."))),
						required("name", "pos")),
				false,
				MineAgentToolRegistry::setAnchor));
		tools.add(new AgentTool(
				"mineagent_box_corners",
				"Fill a rectangular box between two inclusive corner coordinates using one Minecraft block state. The operation is clipped to the sandbox and reports skipped blocks.",
				schema(properties(
						property("pos1", string("First inclusive corner coordinate.")),
						property("pos2", string("Second inclusive corner coordinate, different from pos1 for a non-degenerate box.")),
						property("block", string("Minecraft block-state syntax, for example minecraft:stone or minecraft:oak_stairs[facing=east]."))),
						required("pos1", "pos2", "block")),
				false,
				MineAgentToolRegistry::boxCorners));
		tools.add(new AgentTool(
				"mineagent_box_origin",
				"Fill a rectangular box from an origin coordinate and three signed integer dimensions. Positive and negative dimensions choose the expansion direction; zero is corrected to one block.",
				schema(properties(
						property("origin", string("Origin coordinate.")),
						property("size_x", string("Signed integer X dimension. Decimal values are rounded.")),
						property("size_y", string("Signed integer Y dimension. Decimal values are rounded.")),
						property("size_z", string("Signed integer Z dimension. Decimal values are rounded.")),
						property("block", string("Minecraft block-state syntax."))),
						required("origin", "size_x", "size_y", "size_z", "block")),
				false,
				MineAgentToolRegistry::boxOrigin));
		tools.add(new AgentTool(
				"mineagent_ellipsoid_center",
				"Generate a voxel sphere or ellipsoid from a center coordinate and X/Y/Z radii, matching the MineAgent/WorldEdit-style radius plus half-block voxel test. Radii may be decimals and are clipped to the sandbox.",
				schema(properties(
						property("center", string("Center coordinate.")),
						property("radius_x", string("Non-negative X radius. May be decimal.")),
						property("radius_y", string("Non-negative Y radius. May be decimal.")),
						property("radius_z", string("Non-negative Z radius. May be decimal.")),
						property("block", string("Minecraft block-state syntax."))),
						required("center", "radius_x", "radius_y", "radius_z", "block")),
				false,
				MineAgentToolRegistry::ellipsoidCenter));
		tools.add(new AgentTool(
				"mineagent_ellipsoid_box",
				"Generate a voxel sphere or ellipsoid inscribed in the inclusive box between two coordinates. This is useful when the desired shape should touch all six faces of a bounding box.",
				schema(properties(
						property("pos1", string("First inclusive bounding-box corner.")),
						property("pos2", string("Second inclusive bounding-box corner.")),
						property("block", string("Minecraft block-state syntax."))),
						required("pos1", "pos2", "block")),
				false,
				MineAgentToolRegistry::ellipsoidBox));
		tools.add(new AgentTool(
				"mineagent_cylinder",
				"Generate a voxel cylinder from a center coordinate, an axis direction, an integer height, and a radius. Axis may be +x, -x, +y, -y, +z, or -z. Height is corrected to at least one; radius is corrected to at least one.",
				schema(properties(
						property("center", string("Center of the first cylinder slice.")),
						property("axis", enumString("Cylinder height direction.", "+x", "-x", "+y", "-y", "+z", "-z")),
						property("height", string("Integer height in blocks. Decimal values are rounded.")),
						property("radius", string("Radius. May be decimal; values below 1 are corrected to 1.")),
						property("block", string("Minecraft block-state syntax."))),
						required("center", "axis", "height", "radius", "block")),
				false,
				MineAgentToolRegistry::cylinder));
		return new MineAgentToolRegistry(List.copyOf(tools));
	}

	public List<AgentTool> tools() {
		return tools;
	}

	private static AgentToolOutput getSandbox(AgentToolContext context, JsonObject arguments) {
		SandboxSession sandbox = context.sandbox();
		JsonObject content = new JsonObject();
		content.addProperty("complete", sandbox.hasCompleteBounds());
		content.addProperty("selector", sandbox.selectorType().id());
		content.add("player_pos", pos(context.player().blockPosition()));
		ResourceKey<Level> dimension = context.player().level().dimension();
		content.addProperty("dimension", dimension.location().toString());
		if (sandbox.hasCompleteBounds()) {
			content.add("min", pos(sandbox.min()));
			content.add("max", pos(sandbox.max()));
			content.addProperty("summary", sandbox.boundsSummary());
		}
		JsonObject anchors = new JsonObject();
		for (Map.Entry<String, BlockPos> anchor : sandbox.anchors().entrySet()) {
			anchors.add(anchor.getKey(), pos(anchor.getValue()));
		}
		content.add("anchors", anchors);
		return AgentToolOutput.ok(content);
	}

	private static AgentToolOutput setAnchor(AgentToolContext context, JsonObject arguments) throws Exception {
		ParsedBlockPos pos = parsePos(context, arguments, "pos");
		String name = requireString(arguments, "name");
		context.sandbox().setAnchor(name, pos.pos());
		JsonObject content = new JsonObject();
		content.addProperty("name", name);
		content.add("pos", pos(pos.pos()));
		content.add("warnings", warnings(pos.warnings()));
		return AgentToolOutput.ok(content);
	}

	private static AgentToolOutput boxCorners(AgentToolContext context, JsonObject arguments) throws Exception {
		requireCompleteSandbox(context.sandbox());
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos pos1 = parsePos(context, arguments, "pos1");
		ParsedBlockPos pos2 = parsePos(context, arguments, "pos2");
		warnings.addAll(pos1.warnings());
		warnings.addAll(pos2.warnings());
		GeometryEditResult result = GeometryEditService.fillBox(level(context), context.sandbox(), block(context, arguments), pos1.pos(), pos2.pos(), warnings);
		return AgentToolOutput.ok(editResult(result));
	}

	private static AgentToolOutput boxOrigin(AgentToolContext context, JsonObject arguments) throws Exception {
		requireCompleteSandbox(context.sandbox());
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos origin = parsePos(context, arguments, "origin");
		ParsedInt sizeX = GeometryParameterParser.parseSignedSize(requireString(arguments, "size_x"), "size_x");
		ParsedInt sizeY = GeometryParameterParser.parseSignedSize(requireString(arguments, "size_y"), "size_y");
		ParsedInt sizeZ = GeometryParameterParser.parseSignedSize(requireString(arguments, "size_z"), "size_z");
		warnings.addAll(origin.warnings());
		warnings.addAll(sizeX.warnings());
		warnings.addAll(sizeY.warnings());
		warnings.addAll(sizeZ.warnings());
		GeometryEditResult result = GeometryEditService.fillBoxFromOrigin(level(context), context.sandbox(), block(context, arguments), origin.pos(), sizeX.value(), sizeY.value(), sizeZ.value(), warnings);
		return AgentToolOutput.ok(editResult(result));
	}

	private static AgentToolOutput ellipsoidCenter(AgentToolContext context, JsonObject arguments) throws Exception {
		requireCompleteSandbox(context.sandbox());
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos center = parsePos(context, arguments, "center");
		ParsedDouble radiusX = GeometryParameterParser.parseNonNegativeDouble(requireString(arguments, "radius_x"), "radius_x");
		ParsedDouble radiusY = GeometryParameterParser.parseNonNegativeDouble(requireString(arguments, "radius_y"), "radius_y");
		ParsedDouble radiusZ = GeometryParameterParser.parseNonNegativeDouble(requireString(arguments, "radius_z"), "radius_z");
		warnings.addAll(center.warnings());
		warnings.addAll(radiusX.warnings());
		warnings.addAll(radiusY.warnings());
		warnings.addAll(radiusZ.warnings());
		GeometryEditResult result = GeometryEditService.makeEllipsoid(level(context), context.sandbox(), block(context, arguments), center.pos().getX(), center.pos().getY(), center.pos().getZ(), radiusX.value(), radiusY.value(), radiusZ.value(), warnings);
		return AgentToolOutput.ok(editResult(result));
	}

	private static AgentToolOutput ellipsoidBox(AgentToolContext context, JsonObject arguments) throws Exception {
		requireCompleteSandbox(context.sandbox());
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos pos1 = parsePos(context, arguments, "pos1");
		ParsedBlockPos pos2 = parsePos(context, arguments, "pos2");
		warnings.addAll(pos1.warnings());
		warnings.addAll(pos2.warnings());
		GeometryEditResult result = GeometryEditService.makeEllipsoidInBox(level(context), context.sandbox(), block(context, arguments), pos1.pos(), pos2.pos(), warnings);
		return AgentToolOutput.ok(editResult(result));
	}

	private static AgentToolOutput cylinder(AgentToolContext context, JsonObject arguments) throws Exception {
		requireCompleteSandbox(context.sandbox());
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos center = parsePos(context, arguments, "center");
		GeometryAxisDirection axis = GeometryAxisDirection.byId(requireString(arguments, "axis"));
		if (axis == null) {
			throw new IllegalArgumentException("Unsupported cylinder axis. Use one of +x, -x, +y, -y, +z, -z.");
		}
		ParsedInt height = GeometryParameterParser.parsePositiveHeight(requireString(arguments, "height"));
		ParsedDouble radius = GeometryParameterParser.parseCylinderRadius(requireString(arguments, "radius"));
		warnings.addAll(center.warnings());
		warnings.addAll(height.warnings());
		warnings.addAll(radius.warnings());
		GeometryEditResult result = GeometryEditService.makeCylinder(level(context), context.sandbox(), block(context, arguments), center.pos(), axis, height.value(), radius.value(), warnings);
		return AgentToolOutput.ok(editResult(result));
	}

	private static ParsedBlockPos parsePos(AgentToolContext context, JsonObject arguments, String name) throws Exception {
		return GeometryParameterParser.parseBlockPos(context.player().blockPosition(), context.sandbox(), requireString(arguments, name));
	}

	private static BlockInput block(AgentToolContext context, JsonObject arguments) throws Exception {
		return BlockStateArgument.block(context.registryAccess()).parse(new StringReader(requireString(arguments, "block")));
	}

	private static ServerLevel level(AgentToolContext context) {
		return (ServerLevel) context.player().level();
	}

	private static void requireCompleteSandbox(SandboxSession sandbox) {
		if (!sandbox.hasCompleteBounds()) {
			throw new IllegalStateException("MineAgent sandbox is incomplete. Use //mineagent tool and select two blocks before asking the agent to edit.");
		}
	}

	private static String requireString(JsonObject arguments, String name) {
		if (!arguments.has(name) || arguments.get(name).isJsonNull()) {
			throw new IllegalArgumentException("Missing required argument: " + name);
		}
		String value = arguments.get(name).getAsString();
		if (value.isBlank()) {
			throw new IllegalArgumentException("Argument must not be blank: " + name);
		}
		return value.trim();
	}

	private static JsonObject editResult(GeometryEditResult result) {
		JsonObject content = new JsonObject();
		content.addProperty("candidates", result.candidates());
		content.addProperty("changed", result.changed());
		content.addProperty("unchanged", result.unchanged());
		content.addProperty("skipped_outside_sandbox", result.skippedOutsideSandbox());
		content.addProperty("skipped_outside_world", result.skippedOutsideWorld());
		content.addProperty("clipped", result.clipped());
		content.add("warnings", warnings(result.warnings()));
		return content;
	}

	private static JsonObject pos(BlockPos pos) {
		JsonObject object = new JsonObject();
		object.addProperty("x", pos.getX());
		object.addProperty("y", pos.getY());
		object.addProperty("z", pos.getZ());
		object.addProperty("text", "%d,%d,%d".formatted(pos.getX(), pos.getY(), pos.getZ()));
		return object;
	}

	private static JsonArray warnings(List<String> warnings) {
		JsonArray array = new JsonArray();
		for (String warning : warnings) {
			array.add(warning);
		}
		return array;
	}

	private static JsonObject string(String description) {
		JsonObject property = new JsonObject();
		property.addProperty("type", "string");
		property.addProperty("description", description);
		return property;
	}

	private static JsonObject enumString(String description, String... values) {
		JsonObject property = string(description);
		JsonArray enums = new JsonArray();
		for (String value : values) {
			enums.add(value);
		}
		property.add("enum", enums);
		return property;
	}

	private static NamedProperty property(String name, JsonObject schema) {
		return new NamedProperty(name, schema);
	}

	private static JsonObject properties(NamedProperty... properties) {
		JsonObject object = new JsonObject();
		for (NamedProperty property : properties) {
			object.add(property.name(), property.schema());
		}
		return object;
	}

	private static String[] required(String... names) {
		return names;
	}

	private static JsonObject schema(JsonObject properties, String[] required) {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		JsonArray requiredArray = new JsonArray();
		for (String name : required) {
			requiredArray.add(name);
		}
		schema.add("required", requiredArray);
		schema.addProperty("additionalProperties", false);
		return schema;
	}

	private record NamedProperty(String name, JsonObject schema) {
	}
}
