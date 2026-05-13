package com.tico.mineagent.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import com.tico.mineagent.clipboard.AgentClipboard;
import com.tico.mineagent.edit.AgentCopyResult;
import com.tico.mineagent.edit.AgentEditService;
import com.tico.mineagent.geometry.GeometryAxisDirection;
import com.tico.mineagent.geometry.GeometryEditResult;
import com.tico.mineagent.geometry.GeometryEditService;
import com.tico.mineagent.geometry.GeometryParameterParser;
import com.tico.mineagent.geometry.GeometryParameterParser.ParsedBlockPos;
import com.tico.mineagent.geometry.GeometryParameterParser.ParsedDouble;
import com.tico.mineagent.geometry.GeometryParameterParser.ParsedInt;
import com.tico.mineagent.history.AgentEditRecord;
import com.tico.mineagent.mask.AgentMask;
import com.tico.mineagent.mask.AgentMaskDefinition;
import com.tico.mineagent.mask.AgentMaskResolver;
import com.tico.mineagent.palette.BlockPaletteIndex;
import com.tico.mineagent.sandbox.SandboxSession;

public final class MineAgentToolRegistry {
	public static final String CLIENT_RAYCAST_TOOL = "mineagent_raycast_capture";
	public static final String CLIENT_VIRTUAL_CAMERA_TOOL = "mineagent_virtual_camera_capture";
	public static final String CLIENT_SANDBOX_ISOMETRIC_TOOL = "mineagent_sandbox_isometric_capture";
	private static final List<String> DEFAULT_PALETTE_COLUMNS = List.of(
			"block_id",
			"shape_description",
			"geometry_details",
			"average_color_hex",
			"average_color_r",
			"average_color_g",
			"average_color_b",
			"face_color_summary",
			"different_faces");
	private static final List<String> EXTRA_PALETTE_COLUMNS = List.of(
			"model_category",
			"shape_family",
			"support_class",
			"average_alpha_coverage",
			"tint_policy",
			"material_tags",
			"model_count",
			"blockstate_model_ref_count",
			"rotated_by_blockstate",
			"model_sources",
			"parent_models",
			"texture_sources",
			"confidence",
			"notes");
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
				"mineagent_block_palette_query",
				"Look up Minecraft block series packages by visual color, block id, geometry/model columns, or full text in MineAgent's block palette table. Results are grouped into series such as oak planks, oak log, smooth sandstone, or deepslate tile so one material family occupies one result slot. For color search, block-entity style blocks are ignored, and packages are ranked by color plus construction usefulness: full cubes with same faces, full cubes with different faces, slabs/stairs, self-supporting irregulars, then dependent irregulars. Default results only include block ids, shape_description, geometry_details, average color, face_color_summary, and different_faces. Use extra_columns only when a specific field is needed; available extras are model_category, shape_family, support_class, average_alpha_coverage, tint_policy, material_tags, model_count, blockstate_model_ref_count, rotated_by_blockstate, model_sources, parent_models, texture_sources, confidence, and notes. Extra fields are usually less useful and should be requested deliberately.",
				schema(properties(
						property("mode", enumString("Query mode.", "block_id", "color", "column", "text")),
						property("query", string("Search value. For color use #rrggbb, r,g,b, or a color phrase such as warm gray, dark green, tan, beige, orange, or blue. For block_id use minecraft:block_id. For text/column use search text.")),
						property("column", string("Column name for mode=column, such as shape_family, support_class, material_tags, model_category, or confidence. Use empty string for other modes.")),
						property("limit", string("Maximum returned series packages, 1-16. Use 8 by default.")),
						property("extra_columns", stringArray("Optional explicit extra CSV columns to return for each package. Use [] by default. Available: model_category, shape_family, support_class, average_alpha_coverage, tint_policy, material_tags, model_count, blockstate_model_ref_count, rotated_by_blockstate, model_sources, parent_models, texture_sources, confidence, notes."))),
						required("mode", "query", "column", "limit", "extra_columns")),
				true,
				MineAgentToolRegistry::blockPaletteQuery));
		tools.add(new AgentTool(
				CLIENT_RAYCAST_TOOL,
				"Capture the current client camera as MineAgent raycast perception. Prefer mode=sandbox unless the player explicitly asks for outside context. Resolution must be 128, 256, or 512. FOV must be 45, 60, 75, or 90 degrees. The result returns metadata, displays four aligned UI channels, saves PNGs locally, and attaches those images to the next provider request when image input is supported: textured color, depth, block_id, and xyz position.",
				schema(properties(
						property("mode", enumString("Raycast bounds mode. Use sandbox by default; use free only for deliberate out-of-sandbox context.", "sandbox", "free")),
						property("resolution", integerEnum("Capture resolution. Use 128 for fast checks, 256 for normal inspection, 512 for detailed material checks.", 128, 256, 512)),
						property("fov_degrees", numberEnum("Camera field of view in degrees.", 45, 60, 75, 90))),
						required("mode", "resolution", "fov_degrees")),
				true,
				MineAgentToolRegistry::clientRaycast));
		tools.add(new AgentTool(
				CLIENT_VIRTUAL_CAMERA_TOOL,
				"Render one clean GPU-accelerated client screenshot from an arbitrary virtual camera position and rotation. The camera does not move the player. MineAgent overlays and vanilla targeting outlines are suppressed during capture, and temporary night-vision normalization reduces time-of-day darkness before client state is restored. Default width/height is 512x512. Width is clamped to 256-1920, height to 256-1080, and FOV to 30-90 degrees. The image is displayed in the MineAgent UI, saved locally, and attached to the next provider request when image input is supported.",
				schema(properties(
						property("x", number("Camera X position in world coordinates.")),
						property("y", number("Camera Y position in world coordinates.")),
						property("z", number("Camera Z position in world coordinates.")),
						property("yaw_degrees", number("Minecraft camera yaw in degrees.")),
						property("pitch_degrees", number("Minecraft camera pitch in degrees.")),
						property("width", integer("Requested image width. Use 512 by default.")),
						property("height", integer("Requested image height. Use 512 by default.")),
						property("fov_degrees", number("Requested perspective FOV. Use 60 by default."))),
						required("x", "y", "z", "yaw_degrees", "pitch_degrees", "width", "height", "fov_degrees")),
				true,
				MineAgentToolRegistry::clientVirtualCamera));
		tools.add(new AgentTool(
				CLIENT_SANDBOX_ISOMETRIC_TOOL,
				"Render eight clean GPU-accelerated orthographic isometric overview screenshots aimed at the current sandbox from the +/-X +/-Y +/-Z diagonal directions. Use this after coherent edit batches to inspect silhouette, massing, rooflines, underside/overhangs, and hidden alignment without perspective distortion. MineAgent overlays and vanilla targeting outlines are suppressed during capture, and temporary night-vision normalization reduces time-of-day darkness before client state is restored. Default width/height is 512x512. Width is clamped to 256-1920, height to 256-1080, and FOV is accepted for API compatibility while orthographic framing is derived from the sandbox bounds. Images are displayed in the MineAgent UI, saved locally, and attached to the next provider request when image input is supported.",
				schema(properties(
						property("width", integer("Requested image width. Use 512 by default.")),
						property("height", integer("Requested image height. Use 512 by default.")),
						property("fov_degrees", number("Requested perspective FOV. Use 60 by default."))),
						required("width", "height", "fov_degrees")),
				true,
				MineAgentToolRegistry::clientSandboxIsometric));
		tools.add(new AgentTool(
				"mineagent_mask_define",
				"Define or replace a named MineAgent mask for later edits. Masks are reusable filters inspired by WorldEdit masks. Use not_blocks to protect adjacent blocks by excluding specific block states, blocks to affect only specific states, existing to ignore air, air to affect only air, and any_of/all_of/not to combine named masks.",
				schema(properties(
						property("name", string("Short lowercase mask name, using letters, digits, or underscore.")),
						property("mode", enumString("Mask mode.", "all", "none", "existing", "air", "solid", "blocks", "not_blocks", "any_of", "all_of", "not")),
						property("blocks", stringArray("Block-state list for blocks or not_blocks modes, for example [\"minecraft:air\", \"minecraft:water\"]. Use [] when unused.")),
						property("masks", stringArray("Named child masks for any_of/all_of/not modes. Use [] when unused.")),
						property("invert", bool("Invert the final mask after mode evaluation."))),
						required("name", "mode", "blocks", "masks", "invert")),
				false,
				MineAgentToolRegistry::defineMask));
		tools.add(new AgentTool(
				"mineagent_mask_list",
				"List currently defined reusable MineAgent masks for this player session.",
				schema(properties(), required()),
				true,
				MineAgentToolRegistry::listMasks));
		tools.add(new AgentTool(
				"mineagent_mask_delete",
				"Delete a named MineAgent mask from this player session.",
				schema(properties(
						property("name", string("Mask name to delete."))),
						required("name")),
				false,
				MineAgentToolRegistry::deleteMask));
		tools.add(new AgentTool(
				"mineagent_set_anchor",
				"Create or replace one or more named coordinate anchors inside the current sandbox session. Later coordinate parameters may use @name or @name+dx,dy,dz. Each position is parsed with MineAgent coordinate syntax and rounded to an integer block coordinate if needed.",
				schema(properties(
						property("anchors", anchorArray("Anchors to create or replace. Use this for one anchor too, so related points can be defined in a single tool call."))),
						required("anchors")),
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
				"mineagent_set_block",
				"Set one block at one explicit coordinate to one Minecraft block state. The operation is hard-clipped to the sandbox and reports whether it changed, was unchanged, or was skipped.",
				schema(properties(
						property("pos", string("Target coordinate.")),
						property("block", string("Minecraft block-state syntax."))),
						required("pos", "block")),
				false,
				MineAgentToolRegistry::setBlock));
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
		tools.add(new AgentTool(
				"mineagent_line",
				"Draw a filled voxel line segment between two explicit coordinates, adapted from WorldEdit's line rasterization but without using a selection. Thickness is a non-negative radius; 0 draws a one-block-thick line.",
				schema(properties(
						property("pos1", string("First endpoint coordinate.")),
						property("pos2", string("Second endpoint coordinate.")),
						property("thickness", string("Non-negative line thickness radius. Use 0 for a one-block line.")),
						property("block", string("Minecraft block-state syntax."))),
						required("pos1", "pos2", "thickness", "block")),
				false,
				MineAgentToolRegistry::line));
		tools.add(new AgentTool(
				"mineagent_curve",
				"Draw a filled voxel curve through at least 3 explicit coordinates, adapted from WorldEdit's spline drawing but without using a selection. Default degree is 3. Higher degree uses local polynomial interpolation when requested. Thickness is a non-negative radius; 0 draws a one-block-thick curve.",
				schema(properties(
						property("points", stringArray("Ordered coordinate list with at least 3 points.")),
						property("thickness", string("Non-negative curve thickness radius. Use 0 for a one-block curve.")),
						property("degree", string("Optional interpolation degree. Default 3. Higher values require enough points and may overshoot.")),
						property("block", string("Minecraft block-state syntax."))),
						required("points", "thickness", "block")),
				false,
				MineAgentToolRegistry::curve));
		tools.add(new AgentTool(
				"mineagent_replace",
				"Replace blocks inside the inclusive box between pos1 and pos2, clipped to the sandbox. Provide either mask with a named mask or from_blocks with block-state syntax. If both are empty, MineAgent uses existing blocks only, matching WorldEdit's safe default of not replacing air.",
				schema(properties(
						property("pos1", string("First inclusive source corner coordinate.")),
						property("pos2", string("Second inclusive source corner coordinate.")),
						property("from_blocks", stringArray("Block states to replace. Use [] when using mask.")),
						property("mask", string("Named mask controlling source positions to replace. Use empty string when using from_blocks.")),
						property("target_block", string("Replacement block-state syntax."))),
						required("pos1", "pos2", "from_blocks", "mask", "target_block")),
				false,
				MineAgentToolRegistry::replace));
		tools.add(new AgentTool(
				"mineagent_move",
				"Move an inclusive source box by the vector from from_reference to to_reference, clipped to the sandbox. Source blocks are snapshotted first, then source positions are cleared, then target positions are overwritten. ignore_air is the WorldEdit -a behavior.",
				schema(properties(
						property("pos1", string("First inclusive source corner coordinate.")),
						property("pos2", string("Second inclusive source corner coordinate.")),
						property("from_reference", string("Reference coordinate before the move.")),
						property("to_reference", string("Reference coordinate after the move.")),
						property("mask", string("Optional named source mask. Use empty string for no source mask.")),
						property("ignore_air", bool("When true, air in the source is not moved, equivalent to WorldEdit -a."))),
						required("pos1", "pos2", "from_reference", "to_reference", "mask", "ignore_air")),
				false,
				MineAgentToolRegistry::move));
		tools.add(new AgentTool(
				"mineagent_copy",
				"Copy an inclusive source box into the session clipboard using a reference point. The optional mask controls which source blocks are copied and skipped blocks are absent from the clipboard.",
				schema(properties(
						property("pos1", string("First inclusive source corner coordinate.")),
						property("pos2", string("Second inclusive source corner coordinate.")),
						property("reference", string("Clipboard reference/origin coordinate.")),
						property("mask", string("Optional named source mask. Use empty string for no mask."))),
						required("pos1", "pos2", "reference", "mask")),
				false,
				MineAgentToolRegistry::copy));
		tools.add(new AgentTool(
				"mineagent_paste",
				"Paste the current session clipboard at a reference point. The optional mask is a target mask: it controls which existing target positions may be overwritten. ignore_air skips air entries from the clipboard, equivalent to WorldEdit -a paste behavior.",
				schema(properties(
						property("reference", string("Target paste reference/origin coordinate.")),
						property("mask", string("Optional named target mask. Use empty string to allow overwriting any sandbox target position.")),
						property("ignore_air", bool("When true, air entries in the clipboard are not pasted."))),
						required("reference", "mask", "ignore_air")),
				false,
				MineAgentToolRegistry::paste));
		tools.add(new AgentTool(
				"mineagent_stack",
				"Repeat an inclusive source box along one strict axis direction (+x, -x, +y, -y, +z, or -z), matching WorldEdit-style stack spacing by the source box size. source_mask filters which blocks inside the source box are copied. target_mask filters which existing target positions outside the source box may be overwritten. The sandbox remains the hard write boundary.",
				schema(properties(
						property("pos1", string("First inclusive source corner coordinate.")),
						property("pos2", string("Second inclusive source corner coordinate.")),
						property("axis", enumString("Strict stack axis direction.", "+x", "-x", "+y", "-y", "+z", "-z")),
						property("count", string("Number of copies to create. Must be an integer >= 1; decimals are rounded with a warning.")),
						property("source_mask", string("Optional named source mask controlling which source blocks are copied. Use empty string to copy all source blocks, including air.")),
						property("target_mask", string("Optional named target mask controlling which existing target positions may be overwritten. Use empty string to allow overwriting any sandbox target position."))),
						required("pos1", "pos2", "axis", "count", "source_mask", "target_mask")),
				false,
				MineAgentToolRegistry::stack));
		tools.add(new AgentTool(
				"mineagent_undo",
				"Undo exactly one previous MineAgent block-edit record for this player session, using WorldEdit-style before/after change history. Undo may overwrite blocks if another tool or player changed them after the original edit; warnings report detected conflicts. Batches are not undo units.",
				schema(properties(
						property("steps", string("Must be 1. Values above or below 1 are corrected to one record with a warning."))),
						required("steps")),
				false,
				MineAgentToolRegistry::undo));
		tools.add(new AgentTool(
				"mineagent_redo",
				"Redo exactly one MineAgent block-edit record that was undone in this player session. Redo may overwrite blocks if another tool or player changed them after undo; warnings report detected conflicts. Batches are not redo units.",
				schema(properties(
						property("steps", string("Must be 1. Values above or below 1 are corrected to one record with a warning."))),
						required("steps")),
				false,
				MineAgentToolRegistry::redo));
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
		JsonObject masks = new JsonObject();
		for (Map.Entry<String, AgentMaskDefinition> mask : sandbox.masks().entrySet()) {
			masks.add(mask.getKey(), mask(mask.getValue()));
		}
		content.add("masks", masks);
		AgentClipboard clipboard = sandbox.clipboard();
		JsonObject clipboardInfo = new JsonObject();
		clipboardInfo.addProperty("present", clipboard != null);
		if (clipboard != null) {
			clipboardInfo.addProperty("block_count", clipboard.blockCount());
			clipboardInfo.add("source_min", pos(clipboard.sourceMin()));
			clipboardInfo.add("source_max", pos(clipboard.sourceMax()));
			clipboardInfo.add("reference", pos(clipboard.reference()));
		}
		content.add("clipboard", clipboardInfo);
		JsonObject history = new JsonObject();
		history.addProperty("undo_records", sandbox.undoCount());
		history.addProperty("redo_records", sandbox.redoCount());
		content.add("history", history);
		return AgentToolOutput.ok(content);
	}

	private static AgentToolOutput blockPaletteQuery(AgentToolContext context, JsonObject arguments) throws Exception {
		String mode = requireString(arguments, "mode");
		String query = requireString(arguments, "query");
		String column = optionalString(arguments, "column");
		ParsedInt parsedLimit = GeometryParameterParser.parsePositiveInteger(requireString(arguments, "limit"), "limit");
		BlockPaletteIndex.QueryResult result = BlockPaletteIndex.load().query(mode, query, column == null ? "" : column, parsedLimit.value());
		List<String> warnings = new ArrayList<>(parsedLimit.warnings());
		List<String> extraColumns = paletteExtraColumns(arguments, result.headers(), warnings);

		JsonObject content = new JsonObject();
		content.addProperty("mode", result.mode());
		content.addProperty("query", result.query());
		content.addProperty("limit", result.limit());
		content.addProperty("total_rows", result.totalRows());
		content.addProperty("returned", result.matches().size());
		content.add("default_columns", strings(DEFAULT_PALETTE_COLUMNS));
		content.add("available_extra_columns", strings(EXTRA_PALETTE_COLUMNS));
		content.add("extra_columns_returned", strings(extraColumns));
		JsonArray matches = new JsonArray();
		for (BlockPaletteIndex.SeriesMatch match : result.matches()) {
			matches.add(paletteMatch(match, extraColumns));
		}
		content.add("matches", matches);
		warnings.addAll(result.warnings());
		content.add("warnings", warnings(warnings));
		return AgentToolOutput.ok(content);
	}

	private static AgentToolOutput setAnchor(AgentToolContext context, JsonObject arguments) throws Exception {
		List<AnchorInput> anchors = anchorInputs(arguments);
		if (anchors.isEmpty()) {
			throw new IllegalArgumentException("mineagent_set_anchor requires at least one anchor.");
		}

		List<String> warnings = new ArrayList<>();
		JsonObject anchorMap = new JsonObject();
		JsonArray items = new JsonArray();
		for (AnchorInput anchor : anchors) {
			String name = normalizeAnchorName(anchor.name());
			ParsedBlockPos parsed = GeometryParameterParser.parseBlockPos(context.player().blockPosition(), context.sandbox(), anchor.pos());
			warnings.addAll(parsed.warnings());
			if (anchorMap.has(name)) {
				warnings.add("Anchor @" + name + " was supplied more than once; the later value replaced the earlier value in this tool call.");
			}

			context.sandbox().setAnchor(name, parsed.pos());
			JsonObject item = new JsonObject();
			item.addProperty("name", name);
			item.add("pos", pos(parsed.pos()));
			items.add(item);
			anchorMap.add(name, pos(parsed.pos()));
		}

		JsonObject content = new JsonObject();
		content.addProperty("count", anchors.size());
		content.add("items", items);
		content.add("anchors", anchorMap);
		content.add("warnings", warnings(warnings));
		return AgentToolOutput.ok(content);
	}

	private static AgentToolOutput clientRaycast(AgentToolContext context, JsonObject arguments) {
		throw new IllegalStateException("mineagent_raycast_capture is a client perception tool handled asynchronously by AgentRuntime.");
	}

	private static AgentToolOutput clientVirtualCamera(AgentToolContext context, JsonObject arguments) {
		throw new IllegalStateException("mineagent_virtual_camera_capture is a client perception tool handled asynchronously by AgentRuntime.");
	}

	private static AgentToolOutput clientSandboxIsometric(AgentToolContext context, JsonObject arguments) {
		throw new IllegalStateException("mineagent_sandbox_isometric_capture is a client perception tool handled asynchronously by AgentRuntime.");
	}

	private static AgentToolOutput defineMask(AgentToolContext context, JsonObject arguments) throws Exception {
		String name = requireString(arguments, "name");
		AgentMaskDefinition.Mode mode = AgentMaskDefinition.Mode.byId(requireString(arguments, "mode"));
		if (mode == null) {
			throw new IllegalArgumentException("Unsupported mask mode.");
		}
		AgentMaskDefinition definition = new AgentMaskDefinition(name, mode, stringList(arguments, "blocks"), stringList(arguments, "masks"), bool(arguments, "invert", false));
		AgentMaskResolver.resolveDefinition(context.sandbox(), context.registryAccess(), definition);
		context.sandbox().setMask(definition);
		JsonObject content = new JsonObject();
		content.addProperty("name", definition.name());
		content.add("mask", mask(definition));
		return AgentToolOutput.ok(content);
	}

	private static AgentToolOutput listMasks(AgentToolContext context, JsonObject arguments) {
		JsonObject content = new JsonObject();
		JsonObject masks = new JsonObject();
		for (Map.Entry<String, AgentMaskDefinition> entry : context.sandbox().masks().entrySet()) {
			masks.add(entry.getKey(), mask(entry.getValue()));
		}
		content.add("masks", masks);
		return AgentToolOutput.ok(content);
	}

	private static AgentToolOutput deleteMask(AgentToolContext context, JsonObject arguments) {
		String name = AgentMaskDefinition.normalizeName(requireString(arguments, "name"));
		JsonObject content = new JsonObject();
		content.addProperty("name", name);
		content.addProperty("deleted", context.sandbox().removeMask(name));
		return AgentToolOutput.ok(content);
	}

	private static AgentToolOutput setBlock(AgentToolContext context, JsonObject arguments) throws Exception {
		requireCompleteSandbox(context.sandbox());
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos pos = parsePos(context, arguments, "pos");
		warnings.addAll(pos.warnings());
		GeometryEditResult result = GeometryEditService.setBlock(level(context), context.sandbox(), block(context, arguments), pos.pos(), warnings);
		return AgentToolOutput.ok(editResult(result));
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

	private static AgentToolOutput line(AgentToolContext context, JsonObject arguments) throws Exception {
		requireCompleteSandbox(context.sandbox());
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos pos1 = parsePos(context, arguments, "pos1");
		ParsedBlockPos pos2 = parsePos(context, arguments, "pos2");
		ParsedDouble thickness = GeometryParameterParser.parseNonNegativeDouble(requireString(arguments, "thickness"), "thickness");
		warnings.addAll(pos1.warnings());
		warnings.addAll(pos2.warnings());
		warnings.addAll(thickness.warnings());
		GeometryEditResult result = GeometryEditService.makeLine(level(context), context.sandbox(), block(context, arguments), pos1.pos(), pos2.pos(), thickness.value(), warnings);
		return AgentToolOutput.ok(editResult(result));
	}

	private static AgentToolOutput curve(AgentToolContext context, JsonObject arguments) throws Exception {
		requireCompleteSandbox(context.sandbox());
		List<String> warnings = new ArrayList<>();
		List<ParsedBlockPos> parsedPoints = parsePosList(context, arguments, "points");
		if (parsedPoints.size() < 3) {
			throw new IllegalArgumentException("mineagent_curve requires at least 3 points.");
		}
		List<BlockPos> points = new ArrayList<>();
		for (ParsedBlockPos parsedPoint : parsedPoints) {
			points.add(parsedPoint.pos());
			warnings.addAll(parsedPoint.warnings());
		}
		ParsedDouble thickness = GeometryParameterParser.parseNonNegativeDouble(requireString(arguments, "thickness"), "thickness");
		warnings.addAll(thickness.warnings());
		int degree = 3;
		String rawDegree = optionalString(arguments, "degree");
		if (rawDegree != null && !rawDegree.isBlank()) {
			ParsedInt parsedDegree = GeometryParameterParser.parsePositiveInteger(rawDegree, "degree");
			degree = parsedDegree.value();
			warnings.addAll(parsedDegree.warnings());
		}
		GeometryEditResult result = GeometryEditService.makeCurve(level(context), context.sandbox(), block(context, arguments), points, thickness.value(), degree, warnings);
		return AgentToolOutput.ok(editResult(result));
	}

	private static AgentToolOutput replace(AgentToolContext context, JsonObject arguments) throws Exception {
		requireCompleteSandbox(context.sandbox());
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos pos1 = parsePos(context, arguments, "pos1");
		ParsedBlockPos pos2 = parsePos(context, arguments, "pos2");
		warnings.addAll(pos1.warnings());
		warnings.addAll(pos2.warnings());
		AgentMask sourceMask = sourceMaskFromArguments(context, arguments, warnings);
		GeometryEditResult result = AgentEditService.replace(level(context), context.sandbox(), sourceMask, block(context, arguments, "target_block"), pos1.pos(), pos2.pos(), warnings);
		return AgentToolOutput.ok(editResult(result));
	}

	private static AgentToolOutput move(AgentToolContext context, JsonObject arguments) throws Exception {
		requireCompleteSandbox(context.sandbox());
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos pos1 = parsePos(context, arguments, "pos1");
		ParsedBlockPos pos2 = parsePos(context, arguments, "pos2");
		ParsedBlockPos fromReference = parsePos(context, arguments, "from_reference");
		ParsedBlockPos toReference = parsePos(context, arguments, "to_reference");
		warnings.addAll(pos1.warnings());
		warnings.addAll(pos2.warnings());
		warnings.addAll(fromReference.warnings());
		warnings.addAll(toReference.warnings());
		GeometryEditResult result = AgentEditService.move(level(context), context.sandbox(), optionalNamedMask(context, arguments, "mask"), pos1.pos(), pos2.pos(), fromReference.pos(), toReference.pos(), bool(arguments, "ignore_air", false), warnings);
		return AgentToolOutput.ok(editResult(result));
	}

	private static AgentToolOutput copy(AgentToolContext context, JsonObject arguments) throws Exception {
		requireCompleteSandbox(context.sandbox());
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos pos1 = parsePos(context, arguments, "pos1");
		ParsedBlockPos pos2 = parsePos(context, arguments, "pos2");
		ParsedBlockPos reference = parsePos(context, arguments, "reference");
		warnings.addAll(pos1.warnings());
		warnings.addAll(pos2.warnings());
		warnings.addAll(reference.warnings());
		AgentCopyResult result = AgentEditService.copy(level(context), context.sandbox(), optionalNamedMask(context, arguments, "mask"), pos1.pos(), pos2.pos(), reference.pos(), warnings);
		context.sandbox().setClipboard(result.clipboard());
		return AgentToolOutput.ok(copyResult(result));
	}

	private static AgentToolOutput paste(AgentToolContext context, JsonObject arguments) throws Exception {
		requireCompleteSandbox(context.sandbox());
		AgentClipboard clipboard = context.sandbox().clipboard();
		if (clipboard == null) {
			throw new IllegalStateException("MineAgent clipboard is empty. Use mineagent_copy before mineagent_paste.");
		}
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos reference = parsePos(context, arguments, "reference");
		warnings.addAll(reference.warnings());
		GeometryEditResult result = AgentEditService.paste(level(context), context.sandbox(), clipboard, optionalNamedMask(context, arguments, "mask"), reference.pos(), bool(arguments, "ignore_air", false), warnings);
		return AgentToolOutput.ok(editResult(result));
	}

	private static AgentToolOutput stack(AgentToolContext context, JsonObject arguments) throws Exception {
		requireCompleteSandbox(context.sandbox());
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos pos1 = parsePos(context, arguments, "pos1");
		ParsedBlockPos pos2 = parsePos(context, arguments, "pos2");
		ParsedInt count = GeometryParameterParser.parsePositiveInteger(requireString(arguments, "count"), "count");
		GeometryAxisDirection axis = strictAxis(requireString(arguments, "axis"));
		warnings.addAll(pos1.warnings());
		warnings.addAll(pos2.warnings());
		warnings.addAll(count.warnings());
		GeometryEditResult result = AgentEditService.stack(level(context), context.sandbox(), optionalNamedMask(context, arguments, "source_mask"), optionalNamedMask(context, arguments, "target_mask"), pos1.pos(), pos2.pos(), axis.direction(), count.value(), warnings);
		return AgentToolOutput.ok(editResult(result));
	}

	private static AgentToolOutput undo(AgentToolContext context, JsonObject arguments) throws Exception {
		ParsedInt parsedSteps = GeometryParameterParser.parsePositiveInteger(requireString(arguments, "steps"), "steps");
		List<String> warnings = singleHistoryStepWarnings(parsedSteps);
		GeometryEditResult result = applyHistory(context, 1, true, warnings);
		return AgentToolOutput.ok(editResultWithHistory(context.sandbox(), result));
	}

	private static AgentToolOutput redo(AgentToolContext context, JsonObject arguments) throws Exception {
		ParsedInt parsedSteps = GeometryParameterParser.parsePositiveInteger(requireString(arguments, "steps"), "steps");
		List<String> warnings = singleHistoryStepWarnings(parsedSteps);
		GeometryEditResult result = applyHistory(context, 1, false, warnings);
		return AgentToolOutput.ok(editResultWithHistory(context.sandbox(), result));
	}

	private static List<String> singleHistoryStepWarnings(ParsedInt parsedSteps) {
		List<String> warnings = new ArrayList<>(parsedSteps.warnings());
		if (parsedSteps.value() != 1) {
			warnings.add("MineAgent undo/redo is one edit record at a time; requested steps=" + parsedSteps.value() + " was corrected to 1.");
		}
		return warnings;
	}

	private static GeometryEditResult applyHistory(AgentToolContext context, int steps, boolean undo, List<String> initialWarnings) {
		ServerLevel level = level(context);
		SandboxSession sandbox = context.sandbox();
		List<String> warnings = new ArrayList<>(initialWarnings);
		long candidates = 0L;
		int changed = 0;
		int unchanged = 0;
		boolean hasAffectedBounds = false;
		BlockPos min = BlockPos.ZERO;
		BlockPos max = BlockPos.ZERO;

		for (int i = 0; i < steps; i++) {
			AgentEditRecord record = undo ? sandbox.popUndo() : sandbox.popRedo();
			if (record == null) {
				if (i == 0) {
					throw new IllegalStateException(undo ? "No MineAgent undo history is available." : "No MineAgent redo history is available.");
				}
				warnings.add("Only " + i + " history record(s) were available.");
				break;
			}

			GeometryEditResult result = undo ? record.undo(level) : record.redo(level);
			if (undo) {
				sandbox.pushRedo(record);
				warnings.add("Undid edit record: " + record.label() + ".");
			} else {
				sandbox.pushUndo(record);
				warnings.add("Redid edit record: " + record.label() + ".");
			}
			candidates += result.candidates();
			changed += result.changed();
			unchanged += result.unchanged();
			warnings.addAll(result.warnings());
			if (result.hasAffectedBounds()) {
				if (!hasAffectedBounds) {
					min = result.affectedMin();
					max = result.affectedMax();
					hasAffectedBounds = true;
				} else {
					min = new BlockPos(
							Math.min(min.getX(), result.affectedMin().getX()),
							Math.min(min.getY(), result.affectedMin().getY()),
							Math.min(min.getZ(), result.affectedMin().getZ()));
					max = new BlockPos(
							Math.max(max.getX(), result.affectedMax().getX()),
							Math.max(max.getY(), result.affectedMax().getY()),
							Math.max(max.getZ(), result.affectedMax().getZ()));
				}
			}
		}

		return new GeometryEditResult(candidates, changed, unchanged, 0, 0, 0, hasAffectedBounds, min, max, warnings);
	}

	private static ParsedBlockPos parsePos(AgentToolContext context, JsonObject arguments, String name) throws Exception {
		return GeometryParameterParser.parseBlockPos(context.player().blockPosition(), context.sandbox(), requireString(arguments, name));
	}

	private static List<ParsedBlockPos> parsePosList(AgentToolContext context, JsonObject arguments, String name) throws Exception {
		List<String> rawPoints = stringList(arguments, name);
		List<ParsedBlockPos> points = new ArrayList<>(rawPoints.size());
		for (String rawPoint : rawPoints) {
			points.add(GeometryParameterParser.parseBlockPos(context.player().blockPosition(), context.sandbox(), rawPoint));
		}
		return points;
	}

	private static List<AnchorInput> anchorInputs(JsonObject arguments) {
		if (arguments.has("anchors") && !arguments.get("anchors").isJsonNull()) {
			JsonElement anchorsElement = arguments.get("anchors");
			if (!anchorsElement.isJsonArray()) {
				throw new IllegalArgumentException("Argument must be an array of anchor objects: anchors");
			}

			List<AnchorInput> anchors = new ArrayList<>();
			for (JsonElement element : anchorsElement.getAsJsonArray()) {
				if (!element.isJsonObject()) {
					throw new IllegalArgumentException("Each anchor must be an object with name and pos.");
				}
				JsonObject object = element.getAsJsonObject();
				anchors.add(new AnchorInput(requireString(object, "name"), requireString(object, "pos")));
			}
			return anchors;
		}

		if (arguments.has("name") || arguments.has("pos")) {
			return List.of(new AnchorInput(requireString(arguments, "name"), requireString(arguments, "pos")));
		}

		return List.of();
	}

	private static BlockInput block(AgentToolContext context, JsonObject arguments) throws Exception {
		return block(context, arguments, "block");
	}

	private static BlockInput block(AgentToolContext context, JsonObject arguments, String name) throws Exception {
		return BlockStateArgument.block(context.registryAccess()).parse(new StringReader(requireString(arguments, name)));
	}

	private static GeometryAxisDirection strictAxis(String raw) {
		return switch (raw) {
			case "+x" -> GeometryAxisDirection.POSITIVE_X;
			case "-x" -> GeometryAxisDirection.NEGATIVE_X;
			case "+y" -> GeometryAxisDirection.POSITIVE_Y;
			case "-y" -> GeometryAxisDirection.NEGATIVE_Y;
			case "+z" -> GeometryAxisDirection.POSITIVE_Z;
			case "-z" -> GeometryAxisDirection.NEGATIVE_Z;
			default -> throw new IllegalArgumentException("Unsupported stack axis. Use exactly one of +x, -x, +y, -y, +z, -z.");
		};
	}

	private static AgentMask optionalNamedMask(AgentToolContext context, JsonObject arguments, String name) throws Exception {
		String maskName = optionalString(arguments, name);
		if (maskName == null || maskName.isBlank()) {
			return null;
		}
		return AgentMaskResolver.resolveNamed(context.sandbox(), context.registryAccess(), maskName);
	}

	private static AgentMask sourceMaskFromArguments(AgentToolContext context, JsonObject arguments, List<String> warnings) throws Exception {
		AgentMask namedMask = optionalNamedMask(context, arguments, "mask");
		List<String> fromBlocks = stringList(arguments, "from_blocks");
		AgentMask blockMask = fromBlocks.isEmpty() ? null : AgentMaskResolver.blockMask(context.registryAccess(), fromBlocks);
		if (namedMask != null && blockMask != null) {
			warnings.add("Both mask and from_blocks were supplied; MineAgent combined them with logical AND.");
			return (level, pos) -> namedMask.test(level, pos) && blockMask.test(level, pos);
		}
		if (namedMask != null) {
			return namedMask;
		}
		if (blockMask != null) {
			return blockMask;
		}
		warnings.add("No replace source mask was supplied; MineAgent defaulted to existing non-air blocks.");
		return (level, pos) -> !level.getBlockState(pos).isAir();
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

	private static String optionalString(JsonObject arguments, String name) {
		if (!arguments.has(name) || arguments.get(name).isJsonNull()) {
			return null;
		}
		return arguments.get(name).getAsString().trim();
	}

	private static boolean bool(JsonObject arguments, String name, boolean fallback) {
		if (!arguments.has(name) || arguments.get(name).isJsonNull()) {
			return fallback;
		}
		return arguments.get(name).getAsBoolean();
	}

	private static List<String> stringList(JsonObject arguments, String name) {
		if (!arguments.has(name) || arguments.get(name).isJsonNull()) {
			return List.of();
		}
		JsonElement element = arguments.get(name);
		if (!element.isJsonArray()) {
			throw new IllegalArgumentException("Argument must be an array of strings: " + name);
		}
		List<String> values = new ArrayList<>();
		for (JsonElement item : element.getAsJsonArray()) {
			if (!item.isJsonNull()) {
				String value = item.getAsString().trim();
				if (!value.isBlank()) {
					values.add(value);
				}
			}
		}
		return values;
	}

	private static String normalizeAnchorName(String raw) {
		String name = raw.trim().toLowerCase(Locale.ROOT);
		if (!name.matches("[a-z0-9_]+")) {
			throw new IllegalArgumentException("Invalid MineAgent anchor name: " + raw + ". Use letters, digits, or underscore.");
		}
		return name;
	}

	private static JsonObject editResult(GeometryEditResult result) {
		JsonObject content = new JsonObject();
		content.addProperty("candidates", result.candidates());
		content.addProperty("changed", result.changed());
		content.addProperty("unchanged", result.unchanged());
		content.addProperty("skipped_outside_sandbox", result.skippedOutsideSandbox());
		content.addProperty("skipped_outside_world", result.skippedOutsideWorld());
		content.addProperty("skipped_by_mask", result.skippedByMask());
		content.addProperty("clipped", result.clipped());
		content.addProperty("has_affected_bounds", result.hasAffectedBounds());
		if (result.hasAffectedBounds()) {
			JsonObject bounds = new JsonObject();
			bounds.add("min", pos(result.affectedMin()));
			bounds.add("max", pos(result.affectedMax()));
			bounds.addProperty("summary", "%d,%d,%d -> %d,%d,%d".formatted(
					result.affectedMin().getX(),
					result.affectedMin().getY(),
					result.affectedMin().getZ(),
					result.affectedMax().getX(),
					result.affectedMax().getY(),
					result.affectedMax().getZ()));
			content.add("affected_bounds", bounds);
		}
		content.add("warnings", warnings(result.warnings()));
		return content;
	}

	private static JsonObject editResultWithHistory(SandboxSession sandbox, GeometryEditResult result) {
		JsonObject content = editResult(result);
		content.addProperty("undo_records", sandbox.undoCount());
		content.addProperty("redo_records", sandbox.redoCount());
		return content;
	}

	private static JsonObject copyResult(AgentCopyResult result) {
		JsonObject content = new JsonObject();
		content.addProperty("candidates", result.candidates());
		content.addProperty("copied", result.copied());
		content.addProperty("skipped_outside_sandbox", result.skippedOutsideSandbox());
		content.addProperty("skipped_outside_world", result.skippedOutsideWorld());
		content.addProperty("skipped_by_mask", result.skippedByMask());
		content.addProperty("clipped", result.clipped());
		content.addProperty("clipboard_block_count", result.clipboard().blockCount());
		content.add("source_min", pos(result.clipboard().sourceMin()));
		content.add("source_max", pos(result.clipboard().sourceMax()));
		content.add("reference", pos(result.clipboard().reference()));
		content.add("warnings", warnings(result.warnings()));
		return content;
	}

	private static JsonObject mask(AgentMaskDefinition definition) {
		JsonObject object = new JsonObject();
		object.addProperty("name", definition.name());
		object.addProperty("mode", definition.mode().id());
		object.add("blocks", strings(definition.blocks()));
		object.add("masks", strings(definition.masks()));
		object.addProperty("invert", definition.invert());
		return object;
	}

	private static List<String> paletteExtraColumns(JsonObject arguments, List<String> headers, List<String> warnings) {
		List<String> columns = new ArrayList<>();
		for (String rawColumn : stringList(arguments, "extra_columns")) {
			String column = rawColumn.trim().toLowerCase(Locale.ROOT);
			if (column.isBlank()) {
				continue;
			}
			if (DEFAULT_PALETTE_COLUMNS.contains(column)) {
				warnings.add("Palette extra column '" + column + "' is already included by default.");
				continue;
			}
			if (!headers.contains(column) || !EXTRA_PALETTE_COLUMNS.contains(column)) {
				warnings.add("Ignored unsupported palette extra column '" + rawColumn + "'.");
				continue;
			}
			if (!columns.contains(column)) {
				columns.add(column);
			}
		}
		return List.copyOf(columns);
	}

	private static JsonObject paletteMatch(BlockPaletteIndex.SeriesMatch match, List<String> extraColumns) {
		BlockPaletteIndex.Entry entry = match.representative();
		JsonObject object = new JsonObject();
		object.addProperty("score", match.score());
		object.addProperty("reason", match.reason());
		object.addProperty("series_id", match.seriesId());
		object.addProperty("series_label", match.seriesLabel());
		object.addProperty("series_member_count", match.members().size());
		object.add("series_block_ids", paletteSeriesBlockIds(match.members()));
		object.addProperty("block_id", entry.value("block_id"));
		object.addProperty("average_color_hex", entry.value("average_color_hex"));
		object.addProperty("average_color_r", entry.value("average_color_r"));
		object.addProperty("average_color_g", entry.value("average_color_g"));
		object.addProperty("average_color_b", entry.value("average_color_b"));
		object.addProperty("shape_description", entry.value("shape_description"));
		object.addProperty("geometry_details", entry.value("geometry_details"));
		object.addProperty("face_color_summary", entry.value("face_color_summary"));
		object.addProperty("different_faces", entry.value("different_faces"));

		JsonObject extra = new JsonObject();
		for (String column : extraColumns) {
			String value = entry.value(column);
			if (!value.isBlank()) {
				extra.addProperty(column, value);
			}
		}
		object.add("extra_fields", extra);
		return object;
	}

	private static JsonArray paletteSeriesBlockIds(List<BlockPaletteIndex.Entry> members) {
		JsonArray array = new JsonArray();
		for (BlockPaletteIndex.Entry member : members) {
			array.add(member.value("block_id"));
		}
		return array;
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

	private static JsonArray strings(List<String> values) {
		JsonArray array = new JsonArray();
		for (String value : values) {
			array.add(value);
		}
		return array;
	}

	private static JsonObject string(String description) {
		JsonObject property = new JsonObject();
		property.addProperty("type", "string");
		property.addProperty("description", description);
		return property;
	}

	private static JsonObject stringArray(String description) {
		JsonObject property = new JsonObject();
		property.addProperty("type", "array");
		property.addProperty("description", description);
		JsonObject items = new JsonObject();
		items.addProperty("type", "string");
		property.add("items", items);
		return property;
	}

	private static JsonObject bool(String description) {
		JsonObject property = new JsonObject();
		property.addProperty("type", "boolean");
		property.addProperty("description", description);
		return property;
	}

	private static JsonObject number(String description) {
		JsonObject property = new JsonObject();
		property.addProperty("type", "number");
		property.addProperty("description", description);
		return property;
	}

	private static JsonObject integer(String description) {
		JsonObject property = new JsonObject();
		property.addProperty("type", "integer");
		property.addProperty("description", description);
		return property;
	}

	private static JsonObject anchorArray(String description) {
		JsonObject property = new JsonObject();
		property.addProperty("type", "array");
		property.addProperty("description", description);
		property.addProperty("minItems", 1);

		JsonObject item = new JsonObject();
		item.addProperty("type", "object");
		item.add("properties", properties(
				property("name", string("Anchor name. Use a short lowercase identifier with letters, digits, or underscore, such as base, center, north_wall.")),
				property("pos", string("Coordinate in x,y,z or @anchor+dx,dy,dz form. Never use ~ player-relative coordinates."))));
		item.add("required", requiredArray("name", "pos"));
		item.addProperty("additionalProperties", false);
		property.add("items", item);
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

	private static JsonObject integerEnum(String description, int... values) {
		JsonObject property = new JsonObject();
		property.addProperty("type", "integer");
		property.addProperty("description", description);
		JsonArray enums = new JsonArray();
		for (int value : values) {
			enums.add(value);
		}
		property.add("enum", enums);
		return property;
	}

	private static JsonObject numberEnum(String description, int... values) {
		JsonObject property = new JsonObject();
		property.addProperty("type", "number");
		property.addProperty("description", description);
		JsonArray enums = new JsonArray();
		for (int value : values) {
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

	private static JsonArray requiredArray(String... names) {
		JsonArray requiredArray = new JsonArray();
		for (String name : names) {
			requiredArray.add(name);
		}
		return requiredArray;
	}

	private static JsonObject schema(JsonObject properties, String[] required) {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		schema.add("required", requiredArray(required));
		schema.addProperty("additionalProperties", false);
		return schema;
	}

	private record AnchorInput(String name, String pos) {
	}

	private record NamedProperty(String name, JsonObject schema) {
	}
}
