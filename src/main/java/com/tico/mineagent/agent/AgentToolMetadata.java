package com.tico.mineagent.agent;

public record AgentToolMetadata(
		String title,
		String category,
		String status,
		boolean readOnly,
		boolean destructive,
		boolean countedOperation,
		boolean captureTool,
		boolean setupTool,
		boolean sandboxRequired,
		boolean worldChanging) {
	public boolean queryTool() {
		return readOnly && !captureTool;
	}

	public static AgentToolMetadata forName(String name, boolean fallbackReadOnly) {
		return switch (name) {
			case "mineagent_get_sandbox" -> metadata("Get Sandbox", "Context", true, false, false, false, false, false, false);
			case AgentHostToolRegistry.UPDATE_PLAN_TOOL -> metadata("Update Plan", "Host State", false, false, false, false, false, false, false);
			case "mineagent_block_palette_query" -> metadata("Block Palette Query", "Palette", true, false, false, false, false, false, false);
			case MineAgentToolRegistry.CLIENT_RAYCAST_TOOL -> metadata("Raycast Capture", "Perception", true, false, false, true, false, true, false);
			case MineAgentToolRegistry.CLIENT_VIRTUAL_CAMERA_TOOL -> metadata("Virtual Camera Capture", "Perception", true, false, false, true, false, true, false);
			case MineAgentToolRegistry.CLIENT_SANDBOX_ISOMETRIC_TOOL -> metadata("Sandbox Isometric Capture", "Perception", true, false, false, true, false, true, false);
			case "mineagent_mask_define" -> metadata("Define Mask", "Setup", false, false, false, false, true, false, false);
			case "mineagent_mask_list" -> metadata("List Masks", "Context", true, false, false, false, false, false, false);
			case "mineagent_mask_delete" -> metadata("Delete Mask", "Setup", false, false, false, false, true, false, false);
			case "mineagent_set_anchor" -> metadata("Set Anchor", "Setup", false, false, false, false, true, true, false);
			case "mineagent_set_block" -> edit("Set Block");
			case "mineagent_box_corners" -> edit("Box From Corners");
			case "mineagent_box_origin" -> edit("Box From Origin");
			case "mineagent_ellipsoid_center" -> edit("Ellipsoid From Center");
			case "mineagent_ellipsoid_box" -> edit("Ellipsoid From Box");
			case "mineagent_cylinder" -> edit("Cylinder");
			case "mineagent_line" -> edit("Line");
			case "mineagent_curve" -> edit("Curve");
			case "mineagent_replace" -> edit("Replace");
			case "mineagent_move" -> edit("Move");
			case "mineagent_copy" -> metadata("Copy", "Clipboard/Transform", false, false, true, false, false, true, false);
			case "mineagent_paste" -> metadata("Paste", "Clipboard/Transform", false, true, true, false, false, true, true);
			case "mineagent_stack" -> metadata("Stack", "Clipboard/Transform", false, true, true, false, false, true, true);
			case "mineagent_undo" -> metadata("Undo", "History", false, true, true, false, false, true, true);
			case "mineagent_redo" -> metadata("Redo", "History", false, true, true, false, false, true, true);
			default -> metadata(name, "Uncategorized", fallbackReadOnly, false, false, false, false, false, false);
		};
	}

	public static AgentToolMetadata forName(String name) {
		return forName(name, false);
	}

	private static AgentToolMetadata edit(String title) {
		return metadata(title, "Geometry Edit", false, true, true, false, false, true, true);
	}

	private static AgentToolMetadata metadata(
			String title,
			String category,
			boolean readOnly,
			boolean destructive,
			boolean countedOperation,
			boolean captureTool,
			boolean setupTool,
			boolean sandboxRequired,
			boolean worldChanging) {
		return new AgentToolMetadata(
				title,
				category,
				"stable",
				readOnly,
				destructive,
				countedOperation,
				captureTool,
				setupTool,
				sandboxRequired,
				worldChanging);
	}
}
