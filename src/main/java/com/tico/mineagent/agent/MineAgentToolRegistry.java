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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import com.tico.mineagent.clipboard.AgentClipboard;
import com.tico.mineagent.design.DesignDocumentStore;
import com.tico.mineagent.edit.AgentCopyResult;
import com.tico.mineagent.edit.AgentEditService;
import com.tico.mineagent.geometry.GeometryAxisDirection;
import com.tico.mineagent.geometry.EditRegionLimiter;
import com.tico.mineagent.geometry.GeometryEditResult;
import com.tico.mineagent.geometry.GeometryEditService;
import com.tico.mineagent.geometry.GeometryParameterParser;
import com.tico.mineagent.geometry.GeometryParameterParser.ParsedBlockPos;
import com.tico.mineagent.geometry.GeometryParameterParser.ParsedDouble;
import com.tico.mineagent.geometry.GeometryParameterParser.ParsedInt;
import com.tico.mineagent.geometry.WorldEditExpression;
import com.tico.mineagent.history.AgentEditRecord;
import com.tico.mineagent.mask.AgentMask;
import com.tico.mineagent.mask.AgentMaskDefinition;
import com.tico.mineagent.mask.AgentMaskResolver;
import com.tico.mineagent.palette.BlockPaletteIndex;
import com.tico.mineagent.project.MineAgentProjectStore;
import com.tico.mineagent.sandbox.SandboxSession;
import com.tico.mineagent.structure.BlockVolumeSimilarity;
import com.tico.mineagent.structure.StructureClipboardEntry;
import com.tico.mineagent.structure.StructureComponentStore;
import com.tico.mineagent.structure.StructureComponentTracker;
import com.tico.mineagent.structure.StructureSequenceOptimizer;

public final class MineAgentToolRegistry {
	public static final String CLIENT_RAYCAST_TOOL = "mineagent_raycast_capture";
	public static final String CLIENT_VIRTUAL_CAMERA_TOOL = "mineagent_virtual_camera_capture";
	public static final String CLIENT_SANDBOX_ISOMETRIC_TOOL = "mineagent_sandbox_isometric_capture";
	public static final String STRUCTURE_COMPARE_BBOXES_TOOL = "mineagent_structure_compare_bboxes";
	public static final String BLOCK_FIND_TOOL = "mineagent_block_find";
	public static final String BLOCK_DEBUG_3X3_TOOL = "mineagent_block_debug_3x3";
	public static final String GEN_TOOL = "mineagent_gen";
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
	private static final int MAX_COMPARE_BBOX_VOLUME = 4096;
	private static final int MAX_COMPARE_BBOX_AXIS = 64;
	private static final int MAX_BLOCK_FIND_RESULTS = 512;
	private final List<AgentTool> tools;

	private MineAgentToolRegistry(List<AgentTool> tools) {
		this.tools = tools;
	}

	public static MineAgentToolRegistry create(CommandBuildContext registryAccess) {
		List<AgentTool> tools = new ArrayList<>();
		tools.add(tool(
				"mineagent_get_sandbox",
				"Inspect the current player's MineAgent sandbox boundary, anchors, dimension, and player block position. Use this before any edit. This tool does not modify the world.",
				schema(properties(), required()),
				true,
				MineAgentToolRegistry::getSandbox));
		tools.add(tool(
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
		tools.add(tool(
				BLOCK_FIND_TOOL,
				"Find concrete coordinates of blocks matching a requested Minecraft block or partial block state inside an inclusive bbox. This is a read-only world query and may read outside the sandbox, but the bbox must remain inside Minecraft world bounds. scan_order contains exactly three signed axes such as [\"+x\",\"-z\",\"+y\"]; the first axis changes fastest, so +x scans x from min to max before advancing the next axis. The tool stops after max_results matches and has the same synchronous traversal budget as edit tools.",
				schema(properties(
						property("pos1", string("First inclusive bbox corner coordinate. May be outside the sandbox but must be inside Minecraft world bounds.")),
						property("pos2", string("Second inclusive bbox corner coordinate. May be outside the sandbox but must be inside Minecraft world bounds.")),
						property("block", string("Minecraft block or partial block-state matcher, such as minecraft:stone, minecraft:oak_stairs[facing=east], or minecraft:chest[type=left]. Unspecified block-state properties are not constrained.")),
						property("scan_order", scanOrderArray("Exactly three signed axes controlling traversal order, fastest axis first. Examples: [\"+x\",\"-z\",\"+y\"] or [\"-y\",\"-x\",\"+z\"].")),
						property("max_results", integer("Maximum matching coordinates to return, 1-512. Use a small value unless many examples are needed."))),
						required("pos1", "pos2", "block", "scan_order", "max_results")),
				true,
				MineAgentToolRegistry::findBlocks));
		tools.add(tool(
				BLOCK_DEBUG_3X3_TOOL,
				"Read a complete 3x3x3 block sample centered on one coordinate for local debugging. This is read-only and may read outside the sandbox, but the entire 3x3x3 cube must remain inside Minecraft world bounds. The result uses shape=333 and returns every sampled coordinate with block id, block state, and air flag.",
				schema(properties(
						property("center", string("Center coordinate of the 3x3x3 sample."))),
						required("center")),
				true,
				MineAgentToolRegistry::debugBlocks3x3));
		tools.add(tool(
				CLIENT_RAYCAST_TOOL,
				"Capture the current client camera as MineAgent raycast perception. Prefer mode=sandbox unless the player explicitly asks for outside context. Resolution must be 128, 256, or 512. FOV must be 45, 60, 75, or 90 degrees. The result returns metadata, displays four aligned UI channels, saves PNGs locally, and attaches those images to the next provider request when image input is supported: textured color, depth, block_id, and xyz position.",
				schema(properties(
						property("mode", enumString("Raycast bounds mode. Use sandbox by default; use free only for deliberate out-of-sandbox context.", "sandbox", "free")),
						property("resolution", integerEnum("Capture resolution. Use 128 for fast checks, 256 for normal inspection, 512 for detailed material checks.", 128, 256, 512)),
						property("fov_degrees", numberEnum("Camera field of view in degrees.", 45, 60, 75, 90))),
						required("mode", "resolution", "fov_degrees")),
				true,
				MineAgentToolRegistry::clientRaycast));
		tools.add(tool(
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
		tools.add(tool(
				CLIENT_SANDBOX_ISOMETRIC_TOOL,
				"Render eight clean GPU-accelerated orthographic isometric overview screenshots aimed at the current sandbox from the +/-X +/-Y +/-Z diagonal directions. Use this after coherent edit batches to inspect silhouette, massing, rooflines, underside/overhangs, and hidden alignment without perspective distortion. MineAgent overlays and vanilla targeting outlines are suppressed during capture, and temporary night-vision normalization reduces time-of-day darkness before client state is restored. Default width/height is 512x512. Width is clamped to 256-1920, height to 256-1080, and FOV is accepted for API compatibility while orthographic framing is derived from the sandbox bounds. Images are displayed in the MineAgent UI, saved locally, and attached to the next provider request when image input is supported.",
				schema(properties(
						property("width", integer("Requested image width. Use 512 by default.")),
						property("height", integer("Requested image height. Use 512 by default.")),
						property("fov_degrees", number("Requested perspective FOV. Use 60 by default."))),
						required("width", "height", "fov_degrees")),
				true,
				MineAgentToolRegistry::clientSandboxIsometric));
		tools.add(tool(
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
		tools.add(tool(
				"mineagent_mask_list",
				"List currently defined reusable MineAgent masks for this player session.",
				schema(properties(), required()),
				true,
				MineAgentToolRegistry::listMasks));
		tools.add(tool(
				"mineagent_mask_delete",
				"Delete a named MineAgent mask from this player session.",
				schema(properties(
						property("name", string("Mask name to delete."))),
						required("name")),
				false,
				MineAgentToolRegistry::deleteMask));
		tools.add(tool(
				"mineagent_set_anchor",
				"Create or replace one or more named coordinate anchors inside the current sandbox session. Later coordinate parameters may use @name or @name+dx,dy,dz. Each position is parsed with MineAgent coordinate syntax and rounded to an integer block coordinate if needed.",
				schema(properties(
						property("anchors", anchorArray("Anchors to create or replace. Use this for one anchor too, so related points can be defined in a single tool call."))),
						required("anchors")),
				false,
				MineAgentToolRegistry::setAnchor));
		tools.add(tool(
				"mineagent_prototype_sandbox_create",
				"Create or replace a small prototype sandbox fully inside the main sandbox. While active, all world/block edit tools are clipped to this child sandbox, making it useful for fast detail prototypes. The prototype sandbox may contain at most 4096 blocks, no dimension may exceed 64 blocks, and it accepts at most 128 successful edit records before it must be cleared or recreated.",
				schema(properties(
						property("pos1", string("First inclusive prototype sandbox corner coordinate.")),
						property("pos2", string("Second inclusive prototype sandbox corner coordinate."))),
						required("pos1", "pos2")),
				false,
				MineAgentToolRegistry::createPrototypeSandbox));
		tools.add(tool(
				"mineagent_prototype_sandbox_clear",
				"Clear the active prototype sandbox so later edit tools use the full main MineAgent sandbox again.",
				schema(properties(), required()),
				false,
				MineAgentToolRegistry::clearPrototypeSandbox));
		tools.add(tool(
				"mineagent_project_list",
				"List MineAgent projects owned by the current player UUID in the current Minecraft save. This never lists or reads another player's project data.",
				schema(properties(), required()),
				true,
				MineAgentToolRegistry::listProjects));
		tools.add(tool(
				"mineagent_project_select",
				"Select or create the active MineAgent project for the current player UUID in the current Minecraft save. Project ids are single safe names, not paths; this tool cannot select another player's data. Use only when the player or host wants a different project context.",
				schema(properties(
						property("project_id", string("Safe project id such as eiffel_tower or ship_refit. Blank selects default.")),
						property("title", string("Optional human-readable project title. Use empty string when unchanged or unnecessary."))),
						required("project_id", "title")),
				false,
				MineAgentToolRegistry::selectProject));
		tools.add(tool(
				"mineagent_structure_define",
				"Define a named structure component by an inclusive bbox. This records only trace metadata: name, bbox, hierarchy, pattern id, and copy/similarity lineage; it never stores block data. The bbox must satisfy the minimum size rule: at least two dimensions are >=2 blocks and the third is >=1 block. Prefer defining repeated useful modules and larger composed modules instead of many tiny copies.",
				schema(properties(
						property("name", string("Model-chosen component name, such as gothic_tracery_cell, five_balusters_module, central_rose_window, or west_facade.")),
						property("pos1", string("First inclusive bbox corner coordinate.")),
						property("pos2", string("Second inclusive bbox corner coordinate.")),
						property("similar_to", string("Optional existing component id or exact name to share a pattern id with, when this component is a rotated/mirrored/otherwise equivalent copy. Use empty string when new.")),
						property("notes", string("Optional short design note explaining why this component is useful for repetition or hierarchy. Use empty string when unnecessary."))),
						required("name", "pos1", "pos2", "similar_to", "notes")),
				false,
				MineAgentToolRegistry::defineStructure));
		tools.add(tool(
				"mineagent_structure_list",
				"Read the current structure component trace tree. The tree stores named bboxes, parent/child hierarchy, pattern families, and copy/transform lineage, but never block data.",
				schema(properties(), required()),
				true,
				MineAgentToolRegistry::listStructures));
		tools.add(tool(
				STRUCTURE_COMPARE_BBOXES_TOOL,
				"Compare two inclusive bboxes for similar non-air block structure after you have first used mineagent_virtual_camera_capture to roughly locate the relevant areas. Do not use this from guesswork alone. Each bbox may contain at most 4096 blocks and each axis may be at most 64 blocks. The comparison allows an unknown integer translation plus optional Y-axis rotations and horizontal mirrors. The tool returns best transform/offset candidates, world-coordinate alignment, exact labeled-IoU score, occupancy score, material/blockstate score, and mismatch counts. Use this to determine whether two selected motifs are copies, rotated/mirrored copies, or approximate variants.",
				schema(properties(
						property("first_pos1", string("First corner of the reference bbox.")),
						property("first_pos2", string("Second corner of the reference bbox.")),
						property("second_pos1", string("First corner of the candidate bbox.")),
						property("second_pos2", string("Second corner of the candidate bbox.")),
						property("match_mode", enumString("Signature mode. block_state compares exact transformed block states; block_id ignores block-state properties.", "block_state", "block_id")),
						property("include_rotations", bool("When true, try Y-axis rotations 0, 90, 180, and 270 degrees.")),
						property("include_mirrors", bool("When true, try horizontal X/Z mirror combinations.")),
						property("max_offset", integer("Maximum absolute dx/dy/dz offset to consider after transforming the second bbox. Use a tight value when the virtual camera already localized the candidate; 64 is the maximum useful default.")),
						property("max_results", integer("Number of top transform/offset candidates to return. Use 5 normally; maximum is 20."))),
						required("first_pos1", "first_pos2", "second_pos1", "second_pos2", "match_mode", "include_rotations", "include_mirrors", "max_offset", "max_results")),
				true,
				MineAgentToolRegistry::compareStructureBboxes));
		tools.add(tool(
				"mineagent_structure_sequence_optimize",
				"Optimize integer block sizes for symbolic repeated structure sequences before building. Use this for scale planning such as final=[A*3,B*2], A=[B*2,C], with constraints like final<90, A<10, B in [2,3]. Prefer the structured sequences/constraints arrays for tool calls; shorthand fields exist only for compact model notes. Results are sorted by closeness to the root constraint target: upper bounds sort closest to the upper value, lower bounds closest to the lower value, and intervals closest to their midpoint. max_results defaults conceptually to 5 and is capped at 20.",
				schema(properties(
						property("root", string("Root sequence/component to optimize, usually final.")),
						property("sequences", sequenceArray("Structured sequence definitions. Example: [{name:\"final\",items:[{name:\"A\",count:3},{name:\"B\",count:2}]},{name:\"A\",items:[{name:\"B\",count:2},{name:\"C\",count:1}]}].")),
						property("definition_shorthand", stringArray("Optional shorthand definitions such as [\"final=[A*3,B*2]\", \"A=[B*2,C]\"]. Use [] when structured sequences are provided.")),
						property("constraints", sequenceConstraintArray("Structured constraints. Omit min/max when unused; allowed_values means one-of choices. Example: [{name:\"final\",max:90,max_inclusive:false},{name:\"A\",max:10,max_inclusive:false},{name:\"B\",allowed_values:[2,3]}].")),
						property("constraint_shorthand", stringArray("Optional shorthand constraints such as [\"final<90\", \"A<10\", \"2<=B<=3\"]. Use [] when structured constraints are provided.")),
						property("max_results", integer("Maximum candidate rows to return. Use 5 normally; may be raised up to 20 when the player/model explicitly wants more options.")),
						property("max_unbounded_value", integer("Upper search cap for unconstrained atomic components. Use 64 unless the planning problem needs a wider search."))),
						required("root", "sequences", "definition_shorthand", "constraints", "constraint_shorthand", "max_results", "max_unbounded_value")),
				true,
				MineAgentToolRegistry::optimizeStructureSequence));
		tools.add(tool(
				"mineagent_design_doc_write",
				"Write a MineAgent design support document inside the current player's active project in this Minecraft save only. Documents are limited to agent.md for high-priority project/session working rules and player preferences such as whether existing structures may be modified or how large the work area should be, brief.md for the current design brief/scale/reference analysis, and features/feature_<slug>.md for concrete reference/detail features. Images may be included as base64 data and are saved under the same active project, resized to max 2048x2048 while preserving aspect ratio.",
				schema(properties(
						property("document", enumString("Design document kind.", "agent", "brief", "feature")),
						property("feature_id", string("Required only for feature documents. Use a short slug; MineAgent stores it as feature_<slug>.md. Use empty string for agent/brief.")),
						property("content", string("Markdown content. Keep it focused; rewrite/compress existing feature docs instead of exceeding 64 feature files.")),
						property("images", imageArray("Optional images to attach to this document as base64 data. Use [] when not needed."))),
						required("document", "feature_id", "content", "images")),
				false,
				MineAgentToolRegistry::writeDesignDoc));
		tools.add(tool(
				"mineagent_design_doc_read",
				"Read MineAgent design support documents from the current player's active project. This tool cannot read arbitrary files or other players' projects; it can only read agent.md high-priority working rules/player preferences, brief.md current design analysis, feature_<slug>.md detail records, or the design-doc index.",
				schema(properties(
						property("document", enumString("Design document kind to read.", "agent", "brief", "feature", "index")),
						property("feature_id", string("Required only when document=feature. Use empty string otherwise."))),
						required("document", "feature_id")),
				true,
				MineAgentToolRegistry::readDesignDoc));
		tools.add(tool(
				"mineagent_design_image_delete",
				"Delete one or more images from the current player's active-project MineAgent design images folder. This cannot delete arbitrary files or other players' images and exists so the model can stay under the 128-image design-doc limit.",
				schema(properties(
						property("images", stringArray("Image file names to delete from the design images folder, such as westminster_window.png. Do not include paths."))),
						required("images")),
				false,
				MineAgentToolRegistry::deleteDesignImages));
		tools.add(tool(
				GEN_TOOL,
				"WorldEdit //generate / //gen style bounded shape generation using structured MCP arguments instead of one long Minecraft chat command string. MineAgent requires pos1 and pos2 as the inclusive bbox because there is no WorldEdit selection, and this MCP JSON expression string has no Minecraft chat 256-character limit. The expression is evaluated once per block in the requested bbox after sandbox/world clipping; when the numeric result is > 0, MineAgent places block. Coordinate variables follow WorldEdit's default expression transform: x, y, z are normalized around the bbox center, approximately -1 at the low face and +1 at the high face; sx/sy/sz are bbox dimensions; lx/ly/lz are integer offsets from bbox min; world_x/world_y/world_z and wx/wy/wz are absolute coordinates. Supported syntax: numbers, parentheses, variables, constants pi/e/true/false, semicolon-separated statements, { } blocks, if/else, return, assignment = += -= *= /= %= ^=, prefix/postfix ++/--, unary + - ! ~, postfix factorial !, arithmetic + - * / % ^ or ** for power, bit shifts << >>, comparisons < <= > >= == != ~=, logical && ||, and ternary condition ? a : b. Supported functions include abs, acos, asin, atan, atan2, cbrt, ceil, cos, cosh, exp, floor, ln/log/log10, max, min, rint, round, sin, sinh, sqrt, tan, tanh, pow, hypot, clamp(value,min,max), signum, rotate(varA,varB,angle), swap(varA,varB), random(), randint(max), perlin(seed,x,y,z,frequency,octaves,persistence), voronoi(seed,x,y,z,frequency), ridgedmulti(seed,x,y,z,frequency,octaves), query(x,y,z,type,data), queryAbs(x,y,z,type,data), and queryRel(dx,dy,dz,type,data). query/queryAbs/queryRel compare type/data where -1 is wildcard; if type or data is a variable, MineAgent assigns the queried block's numeric type/data to it after comparison. MineAgent uses modern 1.21.10 block registry numeric id plus blockstate ordinal as the legacy type/data compatibility layer; assigning type/data can rewrite the output block state. Loops, switch, break/continue, megabuf/gmegabuf, closest/gclosest are not supported yet. Optimization is effect-aware: pure coordinate math gets the safest fast classification; random, query, mutable variables, statements, and type/data rewrite disable optimizations that would change WorldEdit-like behavior.",
				schema(properties(
						property("pos1", string("First inclusive bbox corner. Required; MineAgent //gen does not use a WorldEdit selection.")),
						property("pos2", string("Second inclusive bbox corner. Required; the expression coordinate frame is derived from pos1/pos2 even if strict sandbox clipping trims traversal.")),
						property("block", string("Minecraft block-state syntax to place where expression > 0, for example minecraft:stone or minecraft:oak_stairs[facing=east].")),
						property("expression", string("WorldEdit-style expression. Example sphere: x*x + y*y + z*z <= 1. Example with type/data rewrite: if (x>0) { type=1; data=0; 1 } else 0. Example query: { type=-1; data=-1; queryRel(0,-1,0,type,data) && type != 0 }."))),
						required("pos1", "pos2", "block", "expression")),
				false,
				MineAgentToolRegistry::genExpression));
		tools.add(tool(
				"mineagent_box_corners",
				"Fill a rectangular box between two inclusive corner coordinates using one Minecraft block state. The operation is clipped to the sandbox and reports skipped blocks.",
				schema(properties(
						property("pos1", string("First inclusive corner coordinate.")),
						property("pos2", string("Second inclusive corner coordinate, different from pos1 for a non-degenerate box.")),
						property("block", string("Minecraft block-state syntax, for example minecraft:stone or minecraft:oak_stairs[facing=east]."))),
						required("pos1", "pos2", "block")),
				false,
				MineAgentToolRegistry::boxCorners));
		tools.add(tool(
				"mineagent_set_block",
				"Set one block at one explicit coordinate to one Minecraft block state. The operation is hard-clipped to the sandbox and reports whether it changed, was unchanged, or was skipped.",
				schema(properties(
						property("pos", string("Target coordinate.")),
						property("block", string("Minecraft block-state syntax."))),
						required("pos", "block")),
				false,
				MineAgentToolRegistry::setBlock));
		tools.add(tool(
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
		tools.add(tool(
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
		tools.add(tool(
				"mineagent_ellipsoid_box",
				"Generate a voxel sphere or ellipsoid inscribed in the inclusive box between two coordinates. This is useful when the desired shape should touch all six faces of a bounding box.",
				schema(properties(
						property("pos1", string("First inclusive bounding-box corner.")),
						property("pos2", string("Second inclusive bounding-box corner.")),
						property("block", string("Minecraft block-state syntax."))),
						required("pos1", "pos2", "block")),
				false,
				MineAgentToolRegistry::ellipsoidBox));
		tools.add(tool(
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
		tools.add(tool(
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
		tools.add(tool(
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
		tools.add(tool(
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
		tools.add(tool(
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
		tools.add(tool(
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
		tools.add(tool(
				"mineagent_paste",
				"Paste the current session clipboard at a reference point. The optional mask is a target mask: it controls which existing target positions may be overwritten. ignore_air skips air entries from the clipboard, equivalent to WorldEdit -a paste behavior.",
				schema(properties(
						property("reference", string("Target paste reference/origin coordinate.")),
						property("mask", string("Optional named target mask. Use empty string to allow overwriting any sandbox target position.")),
						property("ignore_air", bool("When true, air entries in the clipboard are not pasted."))),
						required("reference", "mask", "ignore_air")),
				false,
				MineAgentToolRegistry::paste));
		tools.add(tool(
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
		tools.add(tool(
				"mineagent_rotate",
				"Rotate an inclusive bbox around a reference point on the vertical Y axis by a multiple of 90 degrees, then update any traced structure components contained in that bbox. This is a direct region transform, not a clipboard transform.",
				schema(properties(
						property("pos1", string("First inclusive source bbox corner coordinate.")),
						property("pos2", string("Second inclusive source bbox corner coordinate.")),
						property("reference", string("Rotation origin/reference coordinate.")),
						property("degrees", string("Rotation angle in degrees. Use 90, 180, 270, or negative equivalents; positive 90 is clockwise when viewed from above."))),
						required("pos1", "pos2", "reference", "degrees")),
				false,
				MineAgentToolRegistry::rotate));
		tools.add(tool(
				"mineagent_flip",
				"Mirror an inclusive bbox across a plane through a reference point, then update any traced structure components contained in that bbox. Planes are yz (flip X), xy (flip Z), and xz (flip Y). This is a direct region transform, not a clipboard transform.",
				schema(properties(
						property("pos1", string("First inclusive source bbox corner coordinate.")),
						property("pos2", string("Second inclusive source bbox corner coordinate.")),
						property("reference", string("Mirror plane reference coordinate.")),
						property("plane", enumString("Mirror plane through reference: yz flips X, xy flips Z, xz flips Y.", "yz", "xy", "xz"))),
						required("pos1", "pos2", "reference", "plane")),
				false,
				MineAgentToolRegistry::flip));
		tools.add(tool(
				"mineagent_undo",
				"Undo exactly one previous MineAgent block-edit record for this player session, using WorldEdit-style before/after change history. Undo may overwrite blocks if another tool or player changed them after the original edit; warnings report detected conflicts. Batches are not undo units.",
				schema(properties(
						property("steps", string("Must be 1. Values above or below 1 are corrected to one record with a warning."))),
						required("steps")),
				false,
				MineAgentToolRegistry::undo));
		tools.add(tool(
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

	private static AgentTool tool(String name, String description, JsonObject inputSchema, boolean readOnly, AgentToolHandler handler) {
		AgentTool tool = new AgentTool(name, description, inputSchema, readOnly, handler);
		AgentToolMetadata metadata = tool.metadata();
		if ("Uncategorized".equals(metadata.category())) {
			throw new IllegalStateException("MineAgent tool metadata is missing for " + name + ".");
		}
		if (metadata.readOnly() != readOnly) {
			throw new IllegalStateException("MineAgent tool metadata readOnly mismatch for " + name + ".");
		}
		return tool;
	}

	private static AgentToolOutput getSandbox(AgentToolContext context, JsonObject arguments) {
		SandboxSession sandbox = context.sandbox();
		JsonObject content = new JsonObject();
		content.addProperty("player_uuid", context.player().getUUID().toString());
		content.addProperty("active_project_id", sandbox.activeProjectId());
		content.addProperty("projects_resource", "projects://index");
		content.addProperty("complete", sandbox.hasCompleteBounds());
		content.addProperty("selector", sandbox.selectorType().id());
		content.addProperty("permission_mode", sandbox.permissionMode().id());
		content.addProperty("permission_mode_label", sandbox.permissionMode().label());
		content.add("player_pos", pos(context.player().blockPosition()));
		ResourceKey<Level> dimension = context.player().level().dimension();
		content.addProperty("dimension", dimension.location().toString());
		if (sandbox.hasCompleteBounds()) {
			content.add("min", pos(sandbox.min()));
			content.add("max", pos(sandbox.max()));
			content.addProperty("summary", sandbox.boundsSummary());
			content.add("active_edit_bounds", activeEditBounds(sandbox));
		}
		content.add("prototype_sandbox", prototypeSandbox(sandbox));
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
			clipboardInfo.addProperty("traced_structure_components", clipboard.structureComponents().size());
		}
		content.add("clipboard", clipboardInfo);
		JsonObject structures = new JsonObject();
		structures.addProperty("component_count", sandbox.structures().componentCount());
		structures.addProperty("minimum_size_rule", StructureComponentTracker.MINIMUM_SIZE_RULE);
		content.add("structures", structures);
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

	private static AgentToolOutput findBlocks(AgentToolContext context, JsonObject arguments) throws Exception {
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos pos1 = parsePos(context, arguments, "pos1");
		ParsedBlockPos pos2 = parsePos(context, arguments, "pos2");
		warnings.addAll(pos1.warnings());
		warnings.addAll(pos2.warnings());
		ParsedBox box = ParsedBox.of(pos1.pos(), pos2.pos());
		ServerLevel level = level(context);
		requireInsideWorld(level, box.min(), box.max(), "block_find");
		EditRegionLimiter.requireBudget(BLOCK_FIND_TOOL, box.volume());

		BlockInput matcher = block(context, arguments);
		List<ScanAxis> scanOrder = scanOrder(arguments);
		int maxResults = Math.max(1, Math.min(MAX_BLOCK_FIND_RESULTS, optionalInt(arguments, "max_results", 32)));
		if (optionalInt(arguments, "max_results", 32) > MAX_BLOCK_FIND_RESULTS) {
			warnings.add("max_results was clamped to " + MAX_BLOCK_FIND_RESULTS + ".");
		}

		JsonArray matches = new JsonArray();
		long visited = 0L;
		ScanAxis fast = scanOrder.get(0);
		ScanAxis middle = scanOrder.get(1);
		ScanAxis slow = scanOrder.get(2);
		int[] coordinate = new int[3];
		ScanRange fastRange = scanRange(box, fast);
		ScanRange middleRange = scanRange(box, middle);
		ScanRange slowRange = scanRange(box, slow);
		outer:
		for (int slowValue = slowRange.start(); slowRange.contains(slowValue); slowValue += slowRange.step()) {
			coordinate[slow.coordinateIndex()] = slowValue;
			for (int middleValue = middleRange.start(); middleRange.contains(middleValue); middleValue += middleRange.step()) {
				coordinate[middle.coordinateIndex()] = middleValue;
				for (int fastValue = fastRange.start(); fastRange.contains(fastValue); fastValue += fastRange.step()) {
					coordinate[fast.coordinateIndex()] = fastValue;
					visited++;
					BlockPos pos = new BlockPos(coordinate[0], coordinate[1], coordinate[2]);
					if (matcher.test(level, pos)) {
						BlockState state = level.getBlockState(pos);
						JsonObject match = new JsonObject();
						match.add("pos", pos(pos));
						match.add("block", blockStateJson(state));
						matches.add(match);
						if (matches.size() >= maxResults) {
							break outer;
						}
					}
				}
			}
		}

		JsonObject content = new JsonObject();
		content.add("bbox", simpleBoxJson(box));
		content.addProperty("block_matcher", requireString(arguments, "block"));
		content.add("scan_order", scanOrderJson(scanOrder));
		content.addProperty("scan_order_meaning", "The first axis changes fastest; signed axes choose min-to-max or max-to-min direction.");
		content.addProperty("max_results", maxResults);
		content.addProperty("visited", visited);
		content.addProperty("returned", matches.size());
		content.addProperty("stopped_after_max_results", matches.size() >= maxResults && visited < box.volume());
		content.add("matches", matches);
		content.add("warnings", warnings(warnings));
		return AgentToolOutput.ok(content);
	}

	private static AgentToolOutput debugBlocks3x3(AgentToolContext context, JsonObject arguments) throws Exception {
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos center = parsePos(context, arguments, "center");
		warnings.addAll(center.warnings());
		ParsedBox box = ParsedBox.of(
				new BlockPos(center.pos().getX() - 1, center.pos().getY() - 1, center.pos().getZ() - 1),
				new BlockPos(center.pos().getX() + 1, center.pos().getY() + 1, center.pos().getZ() + 1));
		ServerLevel level = level(context);
		requireInsideWorld(level, box.min(), box.max(), "block_debug_3x3");

		JsonArray layers = new JsonArray();
		JsonArray flat = new JsonArray();
		for (int dy = -1; dy <= 1; dy++) {
			JsonObject layer = new JsonObject();
			layer.addProperty("dy", dy);
			layer.addProperty("y", center.pos().getY() + dy);
			JsonArray rows = new JsonArray();
			for (int dz = -1; dz <= 1; dz++) {
				JsonObject row = new JsonObject();
				row.addProperty("dz", dz);
				row.addProperty("z", center.pos().getZ() + dz);
				JsonArray cells = new JsonArray();
				for (int dx = -1; dx <= 1; dx++) {
					BlockPos sample = new BlockPos(center.pos().getX() + dx, center.pos().getY() + dy, center.pos().getZ() + dz);
					JsonObject cell = blockSampleJson(level, sample);
					cell.addProperty("dx", dx);
					cell.addProperty("dy", dy);
					cell.addProperty("dz", dz);
					cells.add(cell);
					flat.add(cell.deepCopy());
				}
				row.add("cells", cells);
				rows.add(row);
			}
			layer.add("rows", rows);
			layers.add(layer);
		}

		JsonObject content = new JsonObject();
		content.addProperty("shape", "333");
		content.add("center", pos(center.pos()));
		content.add("min", pos(box.min()));
		content.add("max", pos(box.max()));
		content.addProperty("layout", "layers are dy=-1..1, rows are dz=-1..1, cells are dx=-1..1.");
		content.add("layers", layers);
		content.add("flat", flat);
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

	private static AgentToolOutput createPrototypeSandbox(AgentToolContext context, JsonObject arguments) throws Exception {
		requireCompleteSandbox(context.sandbox());
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos pos1 = parsePos(context, arguments, "pos1");
		ParsedBlockPos pos2 = parsePos(context, arguments, "pos2");
		warnings.addAll(pos1.warnings());
		warnings.addAll(pos2.warnings());
		boolean replaced = context.sandbox().hasPrototypeSandbox();
		SandboxSession.PrototypeSandbox prototype = context.sandbox().createPrototypeSandbox(pos1.pos(), pos2.pos());
		JsonObject content = new JsonObject();
		content.addProperty("active", true);
		content.addProperty("replaced_previous", replaced);
		content.add("prototype_sandbox", prototypeSandbox(prototype));
		content.add("active_edit_bounds", activeEditBounds(context.sandbox()));
		content.add("warnings", warnings(warnings));
		return AgentToolOutput.ok(content);
	}

	private static AgentToolOutput clearPrototypeSandbox(AgentToolContext context, JsonObject arguments) {
		boolean cleared = context.sandbox().clearPrototypeSandbox();
		JsonObject content = new JsonObject();
		content.addProperty("cleared", cleared);
		content.add("prototype_sandbox", prototypeSandbox(context.sandbox()));
		if (context.sandbox().hasCompleteBounds()) {
			content.add("active_edit_bounds", activeEditBounds(context.sandbox()));
		}
		return AgentToolOutput.ok(content);
	}

	private static AgentToolOutput defineStructure(AgentToolContext context, JsonObject arguments) throws Exception {
		requireCompleteSandbox(context.sandbox());
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos pos1 = parsePos(context, arguments, "pos1");
		ParsedBlockPos pos2 = parsePos(context, arguments, "pos2");
		warnings.addAll(pos1.warnings());
		warnings.addAll(pos2.warnings());
		requireInsideSandbox(context.sandbox(), pos1.pos(), pos2.pos(), "structure component");

		StructureComponentTracker.Registration registration = context.sandbox().structures().define(
				requireString(arguments, "name"),
				pos1.pos(),
				pos2.pos(),
				optionalString(arguments, "similar_to"),
				optionalString(arguments, "notes"),
				warnings);
		JsonObject content = new JsonObject();
		content.addProperty("id", registration.id());
		content.addProperty("name", registration.name());
		content.addProperty("pattern_id", registration.patternId());
		content.add("component", context.sandbox().structures().componentJson(registration.id()));
		content.add("structure_file", structureFileSummary(StructureComponentStore.write(context.player(), context.sandbox())));
		content.add("warnings", warnings(warnings));
		return AgentToolOutput.ok(content);
	}

	private static AgentToolOutput listStructures(AgentToolContext context, JsonObject arguments) throws Exception {
		return AgentToolOutput.ok(StructureComponentStore.view(context.player(), context.sandbox()));
	}

	private static AgentToolOutput compareStructureBboxes(AgentToolContext context, JsonObject arguments) throws Exception {
		return finishStructureCompareBboxes(snapshotStructureCompareBboxes(context, arguments));
	}

	static StructureCompareSnapshot snapshotStructureCompareBboxes(AgentToolContext context, JsonObject arguments) throws Exception {
		requireCompleteSandbox(context.sandbox());
		List<String> warnings = new ArrayList<>();
		ParsedBox first = parseBox(context, arguments, "first", warnings);
		ParsedBox second = parseBox(context, arguments, "second", warnings);
		requireInsideSandbox(context.sandbox(), first.min(), first.max(), "first comparison");
		requireInsideSandbox(context.sandbox(), second.min(), second.max(), "second comparison");
		requireInsideWorld(level(context), first.min(), first.max(), "first comparison");
		requireInsideWorld(level(context), second.min(), second.max(), "second comparison");
		validateComparableBox(first, "first");
		validateComparableBox(second, "second");

		String matchMode = requireString(arguments, "match_mode").toLowerCase(Locale.ROOT);
		boolean blockStateMode = switch (matchMode) {
			case "block_state" -> true;
			case "block_id" -> false;
			default -> throw new IllegalArgumentException("match_mode must be block_state or block_id.");
		};
		boolean includeRotations = bool(arguments, "include_rotations", true);
		boolean includeMirrors = bool(arguments, "include_mirrors", true);
		int maxOffset = optionalInt(arguments, "max_offset", 64);
		int maxResults = optionalInt(arguments, "max_results", 5);
		List<BlockVolumeSimilarity.Transform> transforms = BlockVolumeSimilarity.Transform.transforms(includeRotations, includeMirrors);

		BlockVolumeSimilarity.Volume firstVolume = readCompareVolume(level(context), first, blockStateMode, transforms, false);
		BlockVolumeSimilarity.Volume secondVolume = readCompareVolume(level(context), second, blockStateMode, transforms, true);
		if (firstVolume.voxels().isEmpty() || secondVolume.voxels().isEmpty()) {
			warnings.add("One or both comparison bboxes contain no non-air blocks; similarity is not informative.");
		}
		return new StructureCompareSnapshot(
				matchMode,
				first,
				second,
				includeRotations,
				includeMirrors,
				maxOffset,
				maxResults,
				firstVolume,
				secondVolume,
				warnings);
	}

	static AgentToolOutput finishStructureCompareBboxes(StructureCompareSnapshot snapshot) {
		BlockVolumeSimilarity.Result result = BlockVolumeSimilarity.compare(
				snapshot.firstVolume(),
				snapshot.secondVolume(),
				new BlockVolumeSimilarity.Options(
						snapshot.includeRotations(),
						snapshot.includeMirrors(),
						snapshot.maxOffset(),
						snapshot.maxResults(),
						BlockVolumeSimilarity.DEFAULT_CANDIDATE_OFFSETS_PER_TRANSFORM));

		JsonObject content = new JsonObject();
		content.addProperty("match_mode", snapshot.matchMode());
		content.add("first_bbox", compareBoxJson(snapshot.first(), snapshot.firstVolume()));
		content.add("second_bbox", compareBoxJson(snapshot.second(), snapshot.secondVolume()));
		content.addProperty("include_rotations", snapshot.includeRotations());
		content.addProperty("include_mirrors", snapshot.includeMirrors());
		content.addProperty("max_offset", snapshot.maxOffset());
		content.addProperty("max_results", Math.max(1, Math.min(20, snapshot.maxResults())));
		content.addProperty("first_non_air_count", result.firstNonAirCount());
		content.addProperty("second_non_air_count", result.secondNonAirCount());
		content.addProperty("transforms_checked", result.transformsChecked());
		content.addProperty("offsets_evaluated", result.offsetsEvaluated());
		content.addProperty("vote_pairs", result.votePairs());
		content.addProperty("elapsed_ms", result.elapsedMillis());
		content.add("matches", similarityMatchesJson(result.matches(), snapshot.first(), snapshot.second()));
		BlockVolumeSimilarity.Match best = result.best();
		if (best != null) {
			content.addProperty("best_score", best.score());
			content.addProperty("exact_match", exactSimilarityMatch(best));
			content.add("best", similarityMatchJson(best, snapshot.first(), snapshot.second()));
			content.add("best_alignment", similarityAlignmentJson(snapshot.first(), snapshot.second(), best));
			if (exactSimilarityMatch(best)) {
				content.add("exact_alignment", similarityAlignmentJson(snapshot.first(), snapshot.second(), best));
			}
		} else {
			content.addProperty("exact_match", false);
		}
		content.add("warnings", warnings(snapshot.warnings()));
		return AgentToolOutput.ok(content);
	}

	private static AgentToolOutput optimizeStructureSequence(AgentToolContext context, JsonObject arguments) {
		List<String> definitions = new ArrayList<>(stringList(arguments, "definition_shorthand"));
		for (JsonElement element : optionalJsonArray(arguments, "sequences")) {
			if (!element.isJsonObject()) {
				throw new IllegalArgumentException("Each sequence must be an object with name and items.");
			}
			JsonObject sequence = element.getAsJsonObject();
			String name = requireString(sequence, "name");
			JsonArray items = optionalJsonArray(sequence, "items");
			if (items.isEmpty()) {
				throw new IllegalArgumentException("Sequence " + name + " must contain at least one item.");
			}
			List<String> terms = new ArrayList<>();
			for (JsonElement itemElement : items) {
				if (!itemElement.isJsonObject()) {
					throw new IllegalArgumentException("Each sequence item must be an object with name and count.");
				}
				JsonObject item = itemElement.getAsJsonObject();
				String itemName = requireString(item, "name");
				int count = optionalInt(item, "count", 1);
				if (count <= 0 || count > 4096) {
					throw new IllegalArgumentException("Sequence item count must be 1..4096: " + itemName + ".");
				}
				terms.add(count == 1 ? itemName : itemName + "*" + count);
			}
			definitions.add(name + "=[" + String.join(",", terms) + "]");
		}

		List<String> constraints = new ArrayList<>(stringList(arguments, "constraint_shorthand"));
		for (JsonElement element : optionalJsonArray(arguments, "constraints")) {
			if (!element.isJsonObject()) {
				throw new IllegalArgumentException("Each constraint must be an object with name plus min/max/allowed_values.");
			}
			JsonObject constraint = element.getAsJsonObject();
			String name = requireString(constraint, "name");
			List<Integer> allowedValues = integerList(constraint, "allowed_values");
			if (!allowedValues.isEmpty()) {
				constraints.add(name + " in [" + joinIntegers(allowedValues) + "]");
			}
			Integer min = optionalInteger(constraint, "min");
			Integer max = optionalInteger(constraint, "max");
			boolean minInclusive = bool(constraint, "min_inclusive", true);
			boolean maxInclusive = bool(constraint, "max_inclusive", true);
			if (min != null && max != null) {
				constraints.add(min + (minInclusive ? "<=" : "<") + name + (maxInclusive ? "<=" : "<") + max);
			} else if (min != null) {
				constraints.add(name + (minInclusive ? ">=" : ">") + min);
			} else if (max != null) {
				constraints.add(name + (maxInclusive ? "<=" : "<") + max);
			}
		}

		JsonObject content = StructureSequenceOptimizer.optimize(
				definitions,
				constraints,
				requireString(arguments, "root"),
				optionalInt(arguments, "max_results", 5),
				optionalInt(arguments, "max_unbounded_value", 64));
		content.addProperty("read_only", true);
		return AgentToolOutput.ok(content);
	}

	private static AgentToolOutput listProjects(AgentToolContext context, JsonObject arguments) throws Exception {
		return AgentToolOutput.ok(MineAgentProjectStore.index(context.player(), context.sandbox()));
	}

	private static AgentToolOutput selectProject(AgentToolContext context, JsonObject arguments) throws Exception {
		JsonObject content = MineAgentProjectStore.select(
				context.player(),
				context.sandbox(),
				requireString(arguments, "project_id"),
				optionalString(arguments, "title"));
		return AgentToolOutput.ok(content);
	}

	private static AgentToolOutput writeDesignDoc(AgentToolContext context, JsonObject arguments) throws Exception {
		JsonObject content = DesignDocumentStore.write(
				context.player(),
				context.sandbox(),
				requireString(arguments, "document"),
				optionalString(arguments, "feature_id"),
				requireString(arguments, "content"),
				optionalJsonArray(arguments, "images"));
		return AgentToolOutput.ok(content);
	}

	private static AgentToolOutput readDesignDoc(AgentToolContext context, JsonObject arguments) throws Exception {
		JsonObject content = DesignDocumentStore.read(
				context.player(),
				context.sandbox(),
				requireString(arguments, "document"),
				optionalString(arguments, "feature_id"));
		return AgentToolOutput.ok(content);
	}

	private static AgentToolOutput deleteDesignImages(AgentToolContext context, JsonObject arguments) throws Exception {
		return AgentToolOutput.ok(DesignDocumentStore.deleteImages(context.player(), context.sandbox(), arrayOfStrings(arguments, "images")));
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
		return editOutputWithStructureImpact(context, result, "set_block", false);
	}

	private static AgentToolOutput genExpression(AgentToolContext context, JsonObject arguments) throws Exception {
		requireCompleteSandbox(context.sandbox());
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos pos1 = parsePos(context, arguments, "pos1");
		ParsedBlockPos pos2 = parsePos(context, arguments, "pos2");
		warnings.addAll(pos1.warnings());
		warnings.addAll(pos2.warnings());
		String blockText = requireString(arguments, "block");
		WorldEditExpression expression = WorldEditExpression.compile(requireString(arguments, "expression"));
		GeometryEditResult result = GeometryEditService.generateExpression(level(context), context.sandbox(), block(context, blockText), pos1.pos(), pos2.pos(), expression, warnings);
		AgentToolOutput output = editOutputWithStructureImpact(context, result, "gen", isAirBlockText(blockText));
		output.content().addProperty("expression", expression.source());
		output.content().addProperty("expression_false_or_nonfinite", result.skippedByMask());
		output.content().add("expression_effects", expressionEffects(expression.effects()));
		output.content().add("enabled_optimizations", strings(expression.enabledOptimizations()));
		output.content().add("disabled_optimizations", strings(expression.disabledOptimizations()));
		return output;
	}

	private static AgentToolOutput boxCorners(AgentToolContext context, JsonObject arguments) throws Exception {
		requireCompleteSandbox(context.sandbox());
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos pos1 = parsePos(context, arguments, "pos1");
		ParsedBlockPos pos2 = parsePos(context, arguments, "pos2");
		warnings.addAll(pos1.warnings());
		warnings.addAll(pos2.warnings());
		GeometryEditResult result = GeometryEditService.fillBox(level(context), context.sandbox(), block(context, arguments), pos1.pos(), pos2.pos(), warnings);
		return editOutputWithStructureImpact(context, result, "box", true);
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
		return editOutputWithStructureImpact(context, result, "box_origin", true);
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
		return editOutputWithStructureImpact(context, result, "ellipsoid", false);
	}

	private static AgentToolOutput ellipsoidBox(AgentToolContext context, JsonObject arguments) throws Exception {
		requireCompleteSandbox(context.sandbox());
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos pos1 = parsePos(context, arguments, "pos1");
		ParsedBlockPos pos2 = parsePos(context, arguments, "pos2");
		warnings.addAll(pos1.warnings());
		warnings.addAll(pos2.warnings());
		GeometryEditResult result = GeometryEditService.makeEllipsoidInBox(level(context), context.sandbox(), block(context, arguments), pos1.pos(), pos2.pos(), warnings);
		return editOutputWithStructureImpact(context, result, "ellipsoid_box", false);
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
		return editOutputWithStructureImpact(context, result, "cylinder", false);
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
		return editOutputWithStructureImpact(context, result, "line", false);
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
		return editOutputWithStructureImpact(context, result, "curve", false);
	}

	private static AgentToolOutput replace(AgentToolContext context, JsonObject arguments) throws Exception {
		requireCompleteSandbox(context.sandbox());
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos pos1 = parsePos(context, arguments, "pos1");
		ParsedBlockPos pos2 = parsePos(context, arguments, "pos2");
		warnings.addAll(pos1.warnings());
		warnings.addAll(pos2.warnings());
		AgentMask sourceMask = sourceMaskFromArguments(context, arguments, warnings);
		String targetBlockText = requireString(arguments, "target_block");
		GeometryEditResult result = AgentEditService.replace(level(context), context.sandbox(), sourceMask, block(context, targetBlockText), pos1.pos(), pos2.pos(), warnings);
		return editOutputWithStructureImpact(context, result, "replace", isAirBlockText(targetBlockText));
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
		AgentMask sourceMask = optionalNamedMask(context, arguments, "mask");
		GeometryEditResult result = AgentEditService.move(level(context), context.sandbox(), sourceMask, pos1.pos(), pos2.pos(), fromReference.pos(), toReference.pos(), bool(arguments, "ignore_air", false), warnings);
		JsonObject content = editResult(result);
		if (result.changed() > 0 && result.hasAffectedBounds() && !context.sandbox().structures().empty()) {
			if (sourceMask == null) {
				List<String> structureWarnings = new ArrayList<>();
				List<StructureComponentTracker.Registration> registrations = context.sandbox().structures().move(pos1.pos(), pos2.pos(), fromReference.pos(), toReference.pos(), structureWarnings);
				if (!registrations.isEmpty() || !structureWarnings.isEmpty()) {
					addStructureTrace(content, context, registrations, structureWarnings);
				} else {
					addStructureImpact(content, context, result, "move", false);
				}
			} else {
				addStructureImpact(content, context, result, "move", false);
			}
		}
		return AgentToolOutput.ok(content);
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
		List<StructureClipboardEntry> tracedComponents = result.clipboard().empty()
				? List.of()
				: context.sandbox().structures().copyEntries(pos1.pos(), pos2.pos(), reference.pos());
		AgentClipboard clipboard = result.clipboard().withStructureComponents(tracedComponents);
		result = new AgentCopyResult(clipboard, result.candidates(), result.copied(), result.skippedOutsideSandbox(), result.skippedOutsideWorld(), result.skippedByMask(), result.warnings());
		context.sandbox().setClipboard(clipboard);
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
		List<String> structureWarnings = new ArrayList<>();
		List<StructureComponentTracker.Registration> registrations = result.hasAffectedBounds()
				? context.sandbox().structures().pasteEntries(clipboard.structureComponents(), reference.pos(), structureWarnings)
				: List.of();
		JsonObject content = editResult(result);
		addStructureTrace(content, context, registrations, structureWarnings);
		return AgentToolOutput.ok(content);
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
		List<String> structureWarnings = new ArrayList<>();
		List<StructureComponentTracker.Registration> registrations = result.hasAffectedBounds()
				? context.sandbox().structures().stack(pos1.pos(), pos2.pos(), axis.direction(), count.value(), structureWarnings)
				: List.of();
		JsonObject content = editResult(result);
		addStructureTrace(content, context, registrations, structureWarnings);
		return AgentToolOutput.ok(content);
	}

	private static AgentToolOutput rotate(AgentToolContext context, JsonObject arguments) throws Exception {
		requireCompleteSandbox(context.sandbox());
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos pos1 = parsePos(context, arguments, "pos1");
		ParsedBlockPos pos2 = parsePos(context, arguments, "pos2");
		ParsedBlockPos reference = parsePos(context, arguments, "reference");
		int degrees = parseRotationDegrees(requireString(arguments, "degrees"));
		warnings.addAll(pos1.warnings());
		warnings.addAll(pos2.warnings());
		warnings.addAll(reference.warnings());
		GeometryEditResult result = AgentEditService.rotate(level(context), context.sandbox(), pos1.pos(), pos2.pos(), reference.pos(), degrees, warnings);
		List<String> structureWarnings = new ArrayList<>();
		List<StructureComponentTracker.Registration> registrations = result.hasAffectedBounds()
				? context.sandbox().structures().rotate(pos1.pos(), pos2.pos(), reference.pos(), degrees, structureWarnings)
				: List.of();
		JsonObject content = editResult(result);
		addStructureTrace(content, context, registrations, structureWarnings);
		return AgentToolOutput.ok(content);
	}

	private static AgentToolOutput flip(AgentToolContext context, JsonObject arguments) throws Exception {
		requireCompleteSandbox(context.sandbox());
		List<String> warnings = new ArrayList<>();
		ParsedBlockPos pos1 = parsePos(context, arguments, "pos1");
		ParsedBlockPos pos2 = parsePos(context, arguments, "pos2");
		ParsedBlockPos reference = parsePos(context, arguments, "reference");
		String plane = requireString(arguments, "plane").toLowerCase(Locale.ROOT);
		warnings.addAll(pos1.warnings());
		warnings.addAll(pos2.warnings());
		warnings.addAll(reference.warnings());
		GeometryEditResult result = AgentEditService.flip(level(context), context.sandbox(), pos1.pos(), pos2.pos(), reference.pos(), mirrorPlane(plane), warnings);
		List<String> structureWarnings = new ArrayList<>();
		List<StructureComponentTracker.Registration> registrations = result.hasAffectedBounds()
				? context.sandbox().structures().flip(pos1.pos(), pos2.pos(), reference.pos(), plane, structureWarnings)
				: List.of();
		JsonObject content = editResult(result);
		addStructureTrace(content, context, registrations, structureWarnings);
		return AgentToolOutput.ok(content);
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

	private static void addStructureTrace(JsonObject content, AgentToolContext context, List<StructureComponentTracker.Registration> registrations, List<String> structureWarnings) throws Exception {
		JsonObject trace = new JsonObject();
		trace.addProperty("updated_count", registrations.size());
		trace.add("updated_components", context.sandbox().structures().registrationJson(registrations));
		if (!registrations.isEmpty()) {
			trace.add("structure_file", structureFileSummary(StructureComponentStore.write(context.player(), context.sandbox())));
		} else {
			trace.add("structure_file", structureFileSummary(StructureComponentStore.view(context.player(), context.sandbox())));
		}
		trace.add("warnings", warnings(structureWarnings));
		content.add("structure_trace", trace);
	}

	private static AgentToolOutput editOutputWithStructureImpact(AgentToolContext context, GeometryEditResult result, String operation, boolean deleteContained) throws Exception {
		JsonObject content = editResult(result);
		addStructureImpact(content, context, result, operation, deleteContained);
		return AgentToolOutput.ok(content);
	}

	private static void addStructureImpact(JsonObject content, AgentToolContext context, GeometryEditResult result, String operation, boolean deleteContained) throws Exception {
		if (result.changed() <= 0 || !result.hasAffectedBounds() || context.sandbox().structures().empty()) {
			return;
		}

		StructureComponentTracker.ImpactResult impact = context.sandbox().structures().traceEditImpact(
				result.affectedMin(),
				result.affectedMax(),
				operation,
				deleteContained);
		if (!impact.affected()) {
			return;
		}

		JsonObject trace = impact.toJson();
		if (impact.changedTree()) {
			trace.add("structure_file", structureFileSummary(StructureComponentStore.write(context.player(), context.sandbox())));
		} else {
			trace.add("structure_file", structureFileSummary(StructureComponentStore.view(context.player(), context.sandbox())));
		}
		content.add("structure_impact", trace);
	}

	private static JsonObject structureFileSummary(JsonObject full) {
		JsonObject summary = new JsonObject();
		if (full.has("file_path")) {
			summary.add("file_path", full.get("file_path"));
		}
		if (full.has("project_id")) {
			summary.add("project_id", full.get("project_id"));
		}
		if (full.has("file_exists")) {
			summary.add("file_exists", full.get("file_exists"));
		}
		if (full.has("component_count")) {
			summary.add("component_count", full.get("component_count"));
		}
		if (full.has("pattern_count")) {
			summary.add("pattern_count", full.get("pattern_count"));
		}
		return summary;
	}

	private static void requireInsideSandbox(SandboxSession sandbox, BlockPos first, BlockPos second, String label) {
		BlockPos min = new BlockPos(
				Math.min(first.getX(), second.getX()),
				Math.min(first.getY(), second.getY()),
				Math.min(first.getZ(), second.getZ()));
		BlockPos max = new BlockPos(
				Math.max(first.getX(), second.getX()),
				Math.max(first.getY(), second.getY()),
				Math.max(first.getZ(), second.getZ()));
		BlockPos sandboxMin = sandbox.min();
		BlockPos sandboxMax = sandbox.max();
		if (min.getX() < sandboxMin.getX() || max.getX() > sandboxMax.getX()
				|| min.getY() < sandboxMin.getY() || max.getY() > sandboxMax.getY()
				|| min.getZ() < sandboxMin.getZ() || max.getZ() > sandboxMax.getZ()) {
			throw new IllegalArgumentException("The " + label + " bbox must be inside the current sandbox.");
		}
	}

	private static ParsedBox parseBox(AgentToolContext context, JsonObject arguments, String prefix, List<String> warnings) throws Exception {
		ParsedBlockPos pos1 = parsePos(context, arguments, prefix + "_pos1");
		ParsedBlockPos pos2 = parsePos(context, arguments, prefix + "_pos2");
		warnings.addAll(pos1.warnings());
		warnings.addAll(pos2.warnings());
		return ParsedBox.of(pos1.pos(), pos2.pos());
	}

	private static void requireInsideWorld(ServerLevel level, BlockPos first, BlockPos second, String label) {
		ParsedBox box = ParsedBox.of(first, second);
		if (!EditRegionLimiter.insideWorld(level, box.min()) || !EditRegionLimiter.insideWorld(level, box.max())) {
			throw new IllegalArgumentException("The " + label + " bbox must be inside Minecraft world bounds: x/z "
					+ EditRegionLimiter.MIN_WORLD_XZ + ".." + EditRegionLimiter.MAX_WORLD_XZ
					+ ", y " + level.getMinY() + ".." + (level.getMaxY() - 1) + ".");
		}
	}

	private static List<ScanAxis> scanOrder(JsonObject arguments) {
		JsonArray array = optionalJsonArray(arguments, "scan_order");
		if (array.size() != 3) {
			throw new IllegalArgumentException("scan_order must contain exactly three signed axes, such as [\"+x\",\"-z\",\"+y\"].");
		}
		boolean[] seen = new boolean[3];
		List<ScanAxis> axes = new ArrayList<>();
		for (JsonElement element : array) {
			String token = element.getAsString().trim().toLowerCase(Locale.ROOT);
			ScanAxis axis = switch (token) {
				case "+x" -> new ScanAxis(0, 1, token);
				case "-x" -> new ScanAxis(0, -1, token);
				case "+y" -> new ScanAxis(1, 1, token);
				case "-y" -> new ScanAxis(1, -1, token);
				case "+z" -> new ScanAxis(2, 1, token);
				case "-z" -> new ScanAxis(2, -1, token);
				default -> throw new IllegalArgumentException("Unsupported scan_order axis: " + token + ". Use +x, -x, +y, -y, +z, or -z.");
			};
			if (seen[axis.coordinateIndex()]) {
				throw new IllegalArgumentException("scan_order must mention each coordinate axis exactly once.");
			}
			seen[axis.coordinateIndex()] = true;
			axes.add(axis);
		}
		return List.copyOf(axes);
	}

	private static ScanRange scanRange(ParsedBox box, ScanAxis axis) {
		int min = switch (axis.coordinateIndex()) {
			case 0 -> box.min().getX();
			case 1 -> box.min().getY();
			default -> box.min().getZ();
		};
		int max = switch (axis.coordinateIndex()) {
			case 0 -> box.max().getX();
			case 1 -> box.max().getY();
			default -> box.max().getZ();
		};
		return axis.direction() > 0 ? new ScanRange(min, max, 1) : new ScanRange(max, min, -1);
	}

	private static void validateComparableBox(ParsedBox box, String label) {
		if (box.sizeX() > MAX_COMPARE_BBOX_AXIS || box.sizeY() > MAX_COMPARE_BBOX_AXIS || box.sizeZ() > MAX_COMPARE_BBOX_AXIS) {
			throw new IllegalArgumentException("The " + label + " bbox exceeds MineAgent's comparison axis limit of " + MAX_COMPARE_BBOX_AXIS + " blocks.");
		}
		if (box.volume() > MAX_COMPARE_BBOX_VOLUME) {
			throw new IllegalArgumentException("The " + label + " bbox volume " + box.volume() + " exceeds MineAgent's comparison limit of " + MAX_COMPARE_BBOX_VOLUME + " blocks.");
		}
	}

	private static BlockVolumeSimilarity.Volume readCompareVolume(ServerLevel level, ParsedBox box, boolean blockStateMode, List<BlockVolumeSimilarity.Transform> transforms, boolean transformedSignatures) {
		List<BlockVolumeSimilarity.Voxel> voxels = new ArrayList<>();
		for (int y = box.min().getY(); y <= box.max().getY(); y++) {
			for (int z = box.min().getZ(); z <= box.max().getZ(); z++) {
				for (int x = box.min().getX(); x <= box.max().getX(); x++) {
					BlockState state = level.getBlockState(new BlockPos(x, y, z));
					if (state.isAir()) {
						continue;
					}
					String signature = blockSignature(state, blockStateMode);
					List<String> signatures = List.of(signature);
					if (transformedSignatures && blockStateMode) {
						List<String> perTransform = new ArrayList<>(transforms.size());
						for (BlockVolumeSimilarity.Transform transform : transforms) {
							perTransform.add(blockSignature(transformState(state, transform), true));
						}
						signatures = perTransform;
					}
					voxels.add(new BlockVolumeSimilarity.Voxel(
							x - box.min().getX(),
							y - box.min().getY(),
							z - box.min().getZ(),
							signature,
							signatures));
				}
			}
		}
		return new BlockVolumeSimilarity.Volume(box.sizeX(), box.sizeY(), box.sizeZ(), voxels);
	}

	private static BlockState transformState(BlockState state, BlockVolumeSimilarity.Transform transform) {
		BlockState transformed = state;
		if (transform.mirrorX()) {
			transformed = transformed.mirror(Mirror.FRONT_BACK);
		}
		if (transform.mirrorZ()) {
			transformed = transformed.mirror(Mirror.LEFT_RIGHT);
		}
		return transformed.rotate(stateRotationForDegrees(transform.rotationDegrees()));
	}

	private static Rotation stateRotationForDegrees(int degrees) {
		return switch (degrees) {
			case 90 -> Rotation.CLOCKWISE_90;
			case 180 -> Rotation.CLOCKWISE_180;
			case 270 -> Rotation.COUNTERCLOCKWISE_90;
			default -> Rotation.NONE;
		};
	}

	private static String blockSignature(BlockState state, boolean blockStateMode) {
		if (blockStateMode) {
			return state.toString();
		}
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	private static JsonObject compareBoxJson(ParsedBox box, BlockVolumeSimilarity.Volume volume) {
		JsonObject object = simpleBoxJson(box);
		object.addProperty("non_air_count", volume.voxels().size());
		return object;
	}

	private static JsonObject simpleBoxJson(ParsedBox box) {
		JsonObject object = new JsonObject();
		object.add("min", pos(box.min()));
		object.add("max", pos(box.max()));
		JsonObject size = new JsonObject();
		size.addProperty("x", box.sizeX());
		size.addProperty("y", box.sizeY());
		size.addProperty("z", box.sizeZ());
		object.add("size", size);
		object.addProperty("volume", box.volume());
		return object;
	}

	private static JsonArray scanOrderJson(List<ScanAxis> axes) {
		JsonArray array = new JsonArray();
		for (ScanAxis axis : axes) {
			array.add(axis.token());
		}
		return array;
	}

	private static JsonArray similarityMatchesJson(List<BlockVolumeSimilarity.Match> matches, ParsedBox first, ParsedBox second) {
		JsonArray array = new JsonArray();
		for (BlockVolumeSimilarity.Match match : matches) {
			array.add(similarityMatchJson(match, first, second));
		}
		return array;
	}

	private static JsonObject similarityMatchJson(BlockVolumeSimilarity.Match match, ParsedBox first, ParsedBox second) {
		JsonObject object = new JsonObject();
		object.add("transform", similarityTransformJson(match.transform()));
		object.add("offset", similarityOffsetJson(match.offset()));
		object.add("alignment", similarityAlignmentJson(first, second, match));
		object.addProperty("score", match.score());
		object.addProperty("occupancy_score", match.occupancyScore());
		object.addProperty("material_score", match.materialScore());
		object.addProperty("match", match.match());
		object.addProperty("mismatch", match.mismatch());
		object.addProperty("missing", match.missing());
		object.addProperty("extra", match.extra());
		object.addProperty("union", match.union());
		return object;
	}

	private static boolean exactSimilarityMatch(BlockVolumeSimilarity.Match match) {
		return match.mismatch() == 0 && match.missing() == 0 && match.extra() == 0;
	}

	private static JsonObject similarityAlignmentJson(ParsedBox first, ParsedBox second, BlockVolumeSimilarity.Match match) {
		JsonObject object = new JsonObject();
		object.add("transform", similarityTransformJson(match.transform()));
		object.add("local_offset_after_transform", similarityOffsetJson(match.offset()));
		object.add("first_bbox_origin_world", pos(first.min()));
		object.add("second_bbox_origin_world_before", pos(second.min()));
		object.add("second_bbox_origin_world_after_alignment", pos(new BlockPos(
				first.min().getX() + match.offset().dx(),
				first.min().getY() + match.offset().dy(),
				first.min().getZ() + match.offset().dz())));
		ParsedBox alignedBounds = transformedSecondBounds(first, second, match.transform(), match.offset());
		object.add("transformed_second_bbox_world_min", pos(alignedBounds.min()));
		object.add("transformed_second_bbox_world_max", pos(alignedBounds.max()));
		object.addProperty("world_mapping_formula", "For a block at second_world, compute second_local = second_world - second_bbox.min, transformed_local = transform(second_local), then aligned_world = first_bbox.min + transformed_local + offset.");
		return object;
	}

	private static ParsedBox transformedSecondBounds(ParsedBox first, ParsedBox second, BlockVolumeSimilarity.Transform transform, BlockVolumeSimilarity.Offset offset) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		int[] xs = { 0, second.sizeX() - 1 };
		int[] ys = { 0, second.sizeY() - 1 };
		int[] zs = { 0, second.sizeZ() - 1 };
		for (int x : xs) {
			for (int y : ys) {
				for (int z : zs) {
					BlockVolumeSimilarity.Pos transformed = transform.apply(x, y, z);
					int worldX = first.min().getX() + transformed.x() + offset.dx();
					int worldY = first.min().getY() + transformed.y() + offset.dy();
					int worldZ = first.min().getZ() + transformed.z() + offset.dz();
					minX = Math.min(minX, worldX);
					minY = Math.min(minY, worldY);
					minZ = Math.min(minZ, worldZ);
					maxX = Math.max(maxX, worldX);
					maxY = Math.max(maxY, worldY);
					maxZ = Math.max(maxZ, worldZ);
				}
			}
		}
		return new ParsedBox(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
	}

	private static JsonObject similarityTransformJson(BlockVolumeSimilarity.Transform transform) {
		JsonObject object = new JsonObject();
		object.addProperty("id", transform.id());
		object.addProperty("rotation_degrees_y", transform.rotationDegrees());
		object.addProperty("mirror_x", transform.mirrorX());
		object.addProperty("mirror_z", transform.mirrorZ());
		return object;
	}

	private static JsonObject similarityOffsetJson(BlockVolumeSimilarity.Offset offset) {
		JsonObject object = new JsonObject();
		object.addProperty("dx", offset.dx());
		object.addProperty("dy", offset.dy());
		object.addProperty("dz", offset.dz());
		object.addProperty("text", "%d,%d,%d".formatted(offset.dx(), offset.dy(), offset.dz()));
		object.addProperty("meaning", "Add this offset after transforming second bbox local coordinates to align them to first bbox local coordinates.");
		return object;
	}

	private static int parseRotationDegrees(String raw) {
		double value = Double.parseDouble(raw.trim());
		int degrees = (int) Math.round(value);
		if (Math.abs(value - degrees) > 0.001D) {
			throw new IllegalArgumentException("Rotate degrees must be a whole-number multiple of 90. Prefer 90-degree steps unless no other method can satisfy the design.");
		}
		if (degrees % 90 != 0) {
			throw new IllegalArgumentException("Rotate degrees must be a multiple of 90. Prefer 90-degree steps unless no other method can satisfy the design.");
		}
		return degrees;
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
		return block(context, requireString(arguments, name));
	}

	private static BlockInput block(AgentToolContext context, String rawBlock) throws Exception {
		return BlockStateArgument.block(context.registryAccess()).parse(new StringReader(rawBlock.trim()));
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

	private static AgentEditService.MirrorPlane mirrorPlane(String plane) {
		return switch (plane) {
			case "yz" -> AgentEditService.MirrorPlane.YZ;
			case "xy" -> AgentEditService.MirrorPlane.XY;
			case "xz" -> AgentEditService.MirrorPlane.XZ;
			default -> throw new IllegalArgumentException("Mirror plane must be one of yz, xy, or xz.");
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

	private static boolean isAirBlockText(String rawBlock) {
		String block = rawBlock.trim().toLowerCase(Locale.ROOT);
		int stateStart = block.indexOf('[');
		if (stateStart >= 0) {
			block = block.substring(0, stateStart);
		}
		return block.equals("air")
				|| block.equals("minecraft:air")
				|| block.equals("cave_air")
				|| block.equals("minecraft:cave_air")
				|| block.equals("void_air")
				|| block.equals("minecraft:void_air");
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

	private static int optionalInt(JsonObject arguments, String name, int fallback) {
		Integer value = optionalInteger(arguments, name);
		return value == null ? fallback : value;
	}

	private static Integer optionalInteger(JsonObject arguments, String name) {
		if (!arguments.has(name) || arguments.get(name).isJsonNull()) {
			return null;
		}
		return arguments.get(name).getAsInt();
	}

	private static JsonArray optionalJsonArray(JsonObject arguments, String name) {
		if (!arguments.has(name) || arguments.get(name).isJsonNull()) {
			return new JsonArray();
		}
		JsonElement element = arguments.get(name);
		if (!element.isJsonArray()) {
			throw new IllegalArgumentException("Argument must be an array: " + name);
		}
		return element.getAsJsonArray();
	}

	private static JsonArray arrayOfStrings(JsonObject arguments, String name) {
		JsonArray array = new JsonArray();
		for (String value : stringList(arguments, name)) {
			array.add(value);
		}
		return array;
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

	private static List<Integer> integerList(JsonObject arguments, String name) {
		if (!arguments.has(name) || arguments.get(name).isJsonNull()) {
			return List.of();
		}
		JsonElement element = arguments.get(name);
		if (!element.isJsonArray()) {
			throw new IllegalArgumentException("Argument must be an array of integers: " + name);
		}
		List<Integer> values = new ArrayList<>();
		for (JsonElement item : element.getAsJsonArray()) {
			if (!item.isJsonNull()) {
				values.add(item.getAsInt());
			}
		}
		return values;
	}

	private static String joinIntegers(List<Integer> values) {
		List<String> strings = new ArrayList<>();
		for (int value : values) {
			strings.add(Integer.toString(value));
		}
		return String.join(",", strings);
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

	private static JsonObject expressionEffects(WorldEditExpression.Effects effects) {
		JsonObject object = new JsonObject();
		object.addProperty("uses_statements", effects.usesStatements());
		object.addProperty("uses_mutable_state", effects.usesMutableState());
		object.addProperty("uses_random", effects.usesRandom());
		object.addProperty("uses_noise", effects.usesNoise());
		object.addProperty("uses_query", effects.usesQuery());
		object.addProperty("uses_type_data", effects.usesTypeData());
		object.addProperty("writes_type_data", effects.writesTypeData());
		object.addProperty("pure_coordinate_math", effects.pureCoordinateMath());
		object.addProperty("dynamic_output_block", effects.dynamicOutputBlock());
		return object;
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
		content.addProperty("traced_structure_components", result.clipboard().structureComponents().size());
		content.add("source_min", pos(result.clipboard().sourceMin()));
		content.add("source_max", pos(result.clipboard().sourceMax()));
		content.add("reference", pos(result.clipboard().reference()));
		content.add("warnings", warnings(result.warnings()));
		return content;
	}

	private static JsonObject activeEditBounds(SandboxSession sandbox) {
		JsonObject object = new JsonObject();
		object.addProperty("scope", sandbox.hasPrototypeSandbox() ? "prototype_sandbox" : "main_sandbox");
		object.add("min", pos(sandbox.editMin()));
		object.add("max", pos(sandbox.editMax()));
		object.addProperty("summary", sandbox.editBoundsSummary());
		return object;
	}

	private static JsonObject prototypeSandbox(SandboxSession sandbox) {
		return prototypeSandbox(sandbox.prototypeSandbox());
	}

	private static JsonObject prototypeSandbox(SandboxSession.PrototypeSandbox prototype) {
		JsonObject object = new JsonObject();
		object.addProperty("active", prototype != null);
		object.addProperty("max_volume_blocks", SandboxSession.MAX_PROTOTYPE_VOLUME);
		object.addProperty("max_dimension_blocks", SandboxSession.MAX_PROTOTYPE_DIMENSION);
		object.addProperty("max_edit_records", SandboxSession.MAX_PROTOTYPE_EDIT_RECORDS);
		if (prototype != null) {
			object.add("min", pos(prototype.min()));
			object.add("max", pos(prototype.max()));
			object.addProperty("size", prototype.size());
			object.addProperty("volume_blocks", prototype.volume());
			object.addProperty("used_edit_records", prototype.usedEditRecords());
			object.addProperty("remaining_edit_records", prototype.remainingEditRecords());
		}
		return object;
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

	private static JsonObject blockSampleJson(ServerLevel level, BlockPos pos) {
		JsonObject object = new JsonObject();
		object.add("pos", pos(pos));
		object.add("block", blockStateJson(level.getBlockState(pos)));
		return object;
	}

	private static JsonObject blockStateJson(BlockState state) {
		JsonObject object = new JsonObject();
		object.addProperty("block_id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		object.addProperty("block_state", state.toString());
		object.addProperty("is_air", state.isAir());
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

	private static JsonObject scanOrderArray(String description) {
		JsonObject property = new JsonObject();
		property.addProperty("type", "array");
		property.addProperty("description", description);
		property.addProperty("minItems", 3);
		property.addProperty("maxItems", 3);
		JsonObject items = enumString("Signed scan axis.", "+x", "-x", "+y", "-y", "+z", "-z");
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

	private static JsonObject integerArray(String description) {
		JsonObject property = new JsonObject();
		property.addProperty("type", "array");
		property.addProperty("description", description);
		JsonObject items = new JsonObject();
		items.addProperty("type", "integer");
		property.add("items", items);
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

	private static JsonObject sequenceArray(String description) {
		JsonObject property = new JsonObject();
		property.addProperty("type", "array");
		property.addProperty("description", description);

		JsonObject term = new JsonObject();
		term.addProperty("type", "object");
		term.add("properties", properties(
				property("name", string("Referenced sequence/component name.")),
				property("count", integer("Positive repetition count. Use 1 for a single occurrence."))));
		term.add("required", requiredArray("name", "count"));
		term.addProperty("additionalProperties", false);

		JsonObject terms = new JsonObject();
		terms.addProperty("type", "array");
		terms.addProperty("minItems", 1);
		terms.add("items", term);

		JsonObject sequence = new JsonObject();
		sequence.addProperty("type", "object");
		sequence.add("properties", properties(
				property("name", string("Defined sequence name, such as final, facade, window_row, or A.")),
				property("items", terms)));
		sequence.add("required", requiredArray("name", "items"));
		sequence.addProperty("additionalProperties", false);

		property.add("items", sequence);
		return property;
	}

	private static JsonObject sequenceConstraintArray(String description) {
		JsonObject property = new JsonObject();
		property.addProperty("type", "array");
		property.addProperty("description", description);

		JsonObject constraint = new JsonObject();
		constraint.addProperty("type", "object");
		constraint.add("properties", properties(
				property("name", string("Sequence/component name this constraint applies to.")),
				property("min", integer("Optional lower bound. Omit or null when unused.")),
				property("min_inclusive", bool("Whether min is inclusive. Defaults to true when omitted.")),
				property("max", integer("Optional upper bound. Omit or null when unused.")),
				property("max_inclusive", bool("Whether max is inclusive. Defaults to true when omitted.")),
				property("allowed_values", integerArray("Optional exact allowed integer values, equivalent to one-of choices. Use [] when unused."))));
		constraint.add("required", requiredArray("name"));
		constraint.addProperty("additionalProperties", false);

		property.add("items", constraint);
		return property;
	}

	private static JsonObject imageArray(String description) {
		JsonObject property = new JsonObject();
		property.addProperty("type", "array");
		property.addProperty("description", description);
		property.addProperty("maxItems", 8);

		JsonObject item = new JsonObject();
		item.addProperty("type", "object");
		item.add("properties", properties(
				property("name", string("Short image name used for the saved PNG filename and Markdown alt text.")),
				property("media_type", string("Image media type for documentation only, such as image/png or image/jpeg.")),
				property("data_base64", string("Base64 image bytes, optionally as a data URL. MineAgent decodes and writes only inside the current player's active-project design/images directory."))));
		item.add("required", requiredArray("name", "media_type", "data_base64"));
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

	record StructureCompareSnapshot(
			String matchMode,
			ParsedBox first,
			ParsedBox second,
			boolean includeRotations,
			boolean includeMirrors,
			int maxOffset,
			int maxResults,
			BlockVolumeSimilarity.Volume firstVolume,
			BlockVolumeSimilarity.Volume secondVolume,
			List<String> warnings) {
		StructureCompareSnapshot {
			warnings = List.copyOf(warnings);
		}
	}

	private record ScanAxis(int coordinateIndex, int direction, String token) {
	}

	private record ScanRange(int start, int end, int step) {
		private boolean contains(int value) {
			return step > 0 ? value <= end : value >= end;
		}
	}

	private record ParsedBox(BlockPos min, BlockPos max) {
		private static ParsedBox of(BlockPos first, BlockPos second) {
			return new ParsedBox(
					new BlockPos(
							Math.min(first.getX(), second.getX()),
							Math.min(first.getY(), second.getY()),
							Math.min(first.getZ(), second.getZ())),
					new BlockPos(
							Math.max(first.getX(), second.getX()),
							Math.max(first.getY(), second.getY()),
							Math.max(first.getZ(), second.getZ())));
		}

		private int sizeX() {
			return max.getX() - min.getX() + 1;
		}

		private int sizeY() {
			return max.getY() - min.getY() + 1;
		}

		private int sizeZ() {
			return max.getZ() - min.getZ() + 1;
		}

		private long volume() {
			return (long) sizeX() * sizeY() * sizeZ();
		}
	}

	private record NamedProperty(String name, JsonObject schema) {
	}
}
