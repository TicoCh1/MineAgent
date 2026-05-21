package com.tico.mineagent.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import com.tico.mineagent.clipboard.AgentClipboard;
import com.tico.mineagent.mask.AgentMaskDefinition;
import com.tico.mineagent.mcp.MineAgentMcpResource;
import com.tico.mineagent.sandbox.SandboxSession;

public final class AgentPromptContext {
	private AgentPromptContext() {
	}

	public static String initial(AgentCredentials credentials, ServerPlayer player, SandboxSession sandbox, List<AgentTool> tools, List<MineAgentMcpResource> resources, List<AgentImageAttachment> initialImages) {
		StringBuilder builder = new StringBuilder();
		builder.append("<mineagent_context version=\"1\">\n");
		builder.append("<context_contract>\n");
		builder.append("- This run-specific context is appended after the stable system instructions and stable tool schemas.\n");
		builder.append("- Treat these facts as the current runtime environment; if they conflict with tool results, trust the latest tool result.\n");
		builder.append("- MineAgent keeps all tools registered in a stable order. Unavailable or invalid operations fail through tool results instead of tools being hidden dynamically.\n");
		builder.append("</context_contract>\n");

		builder.append("<run_environment>\n");
		builder.append("provider: ").append(credentials.provider().id()).append('\n');
		builder.append("model: ").append(credentials.model()).append('\n');
		builder.append("minecraft: 1.21.10\n");
		builder.append("mod: Fabric MineAgent\n");
		builder.append("dimension: ").append(player.level().dimension().location()).append('\n');
		builder.append("player_uuid: ").append(player.getUUID()).append('\n');
		builder.append("player_block_pos: ").append(pos(player.blockPosition())).append('\n');
		builder.append("active_project_id: ").append(sandbox.activeProjectId()).append('\n');
		builder.append("project_boundary: design docs, feature images, and structure traces are scoped to this player's UUID and active project. Do not switch projects unless the player or host explicitly asks.\n");
		builder.append("</run_environment>\n");

		builder.append("<safety_boundary>\n");
		builder.append("sandbox_complete: ").append(sandbox.hasCompleteBounds()).append('\n');
		if (sandbox.hasCompleteBounds()) {
			BlockPos min = sandbox.min();
			BlockPos max = sandbox.max();
			builder.append("sandbox_min: ").append(pos(min)).append('\n');
			builder.append("sandbox_max: ").append(pos(max)).append('\n');
			builder.append("sandbox_size: ").append(size(min, max)).append('\n');
			builder.append("sandbox_volume_blocks: ").append(volume(min, max)).append('\n');
			builder.append("active_edit_scope: ").append(sandbox.hasPrototypeSandbox() ? "prototype_sandbox" : "main_sandbox").append('\n');
			builder.append("active_edit_min: ").append(pos(sandbox.editMin())).append('\n');
			builder.append("active_edit_max: ").append(pos(sandbox.editMax())).append('\n');
		}
		builder.append("prototype_sandbox: ").append(prototypeSummary(sandbox)).append('\n');
		builder.append("sandbox_permission_mode: ").append(sandbox.permissionMode().id()).append(" (").append(sandbox.permissionMode().label()).append(")\n");
		builder.append("write_scope: strict mode clips model-facing write tools to the current sandbox. In manual_expand or auto_expand_air mode, justified outside-sandbox operations may request or perform sandbox expansion before execution; tool results and approval outcomes are the source of truth.\n");
		builder.append("coordinate_contract: use absolute x,y,z or @anchor+dx,dy,dz only; never use Minecraft player-relative ~ syntax.\n");
		builder.append("execution_contract: provider adapters cannot edit the world directly; every read/write goes through MineAgent tools and server-thread execution.\n");
		builder.append("</safety_boundary>\n");

		builder.append("<session_state>\n");
		builder.append("selector_type: ").append(sandbox.selectorType().id()).append('\n');
		builder.append("anchors: ").append(anchorSummary(sandbox)).append('\n');
		builder.append("masks: ").append(maskSummary(sandbox)).append('\n');
		builder.append("clipboard: ").append(clipboardSummary(sandbox.clipboard())).append('\n');
		builder.append("structure_components: ").append(sandbox.structures().componentCount()).append('\n');
		builder.append("undo_records: ").append(sandbox.undoCount()).append('\n');
		builder.append("redo_records: ").append(sandbox.redoCount()).append('\n');
		builder.append("</session_state>\n");

		builder.append("<tool_registry_contract>\n");
		builder.append("tool_count: ").append(tools.size()).append('\n');
		builder.append("provider_hosted_tools: provider web search may also be available, but it is not a MineAgent world-editing tool.\n");
		builder.append("counted_operation_limit_per_batch: 16\n");
		builder.append("query_limit_per_batch: 16\n");
		builder.append("capture_cadence: after every group of 1-4 counted operations, append one capture tool before more counted operations or batch end.\n");
		builder.append("tool_order:\n");
		for (int i = 0; i < tools.size(); i++) {
			AgentTool tool = tools.get(i);
			AgentToolMetadata metadata = tool.metadata();
			builder.append("- ").append(i + 1).append(". ").append(tool.name())
					.append(" [category=").append(metadata.category())
					.append(", read_only=").append(metadata.readOnly())
					.append(", counted=").append(metadata.countedOperation())
					.append(", capture=").append(metadata.captureTool())
					.append(", setup=").append(metadata.setupTool())
					.append("]\n");
		}
		builder.append("</tool_registry_contract>\n");

		builder.append("<resource_registry_contract>\n");
		builder.append("resource_count: ").append(resources.size()).append('\n');
		builder.append("resource_policy: resources are small read-on-demand state windows; use tools for search, filtering, and edits.\n");
		builder.append("resources:\n");
		for (MineAgentMcpResource resource : resources) {
			builder.append("- ").append(resource.uri()).append(" [").append(resource.title()).append("]\n");
		}
		builder.append("palette_policy: use mineagent_block_palette_query for color/material search; palette://metadata is intentionally small and the full CSV is large.\n");
		builder.append("</resource_registry_contract>\n");

		builder.append("<visual_context>\n");
		builder.append("initial_capture: eight clean sandbox_isometric screenshots from +/-X +/-Y +/-Z diagonal directions are attached to this first request.\n");
		builder.append("initial_image_count: ").append(initialImages.size()).append('\n');
		builder.append("initial_image_labels: ").append(imageLabels(initialImages)).append('\n');
		builder.append("capture_result_contract: later capture tool results attach images to the next provider request; use those pixels for self-review before planning the next edit batch.\n");
		builder.append("</visual_context>\n");
		builder.append("</mineagent_context>");
		return builder.toString();
	}

	private static String anchorSummary(SandboxSession sandbox) {
		if (sandbox.anchors().isEmpty()) {
			return "none";
		}
		List<String> anchors = new ArrayList<>();
		for (Map.Entry<String, BlockPos> entry : new TreeMap<>(sandbox.anchors()).entrySet()) {
			anchors.add(entry.getKey() + "=" + pos(entry.getValue()));
		}
		return String.join(", ", anchors);
	}

	private static String maskSummary(SandboxSession sandbox) {
		if (sandbox.masks().isEmpty()) {
			return "none";
		}
		List<String> masks = new ArrayList<>();
		for (Map.Entry<String, AgentMaskDefinition> entry : new TreeMap<>(sandbox.masks()).entrySet()) {
			masks.add(entry.getKey() + ":" + entry.getValue().mode().id());
		}
		return String.join(", ", masks);
	}

	private static String clipboardSummary(AgentClipboard clipboard) {
		if (clipboard == null || clipboard.empty()) {
			return "empty";
		}
		return "blocks=" + clipboard.blockCount()
				+ ", source_min=" + pos(clipboard.sourceMin())
				+ ", source_max=" + pos(clipboard.sourceMax())
				+ ", reference=" + pos(clipboard.reference())
				+ ", traced_structure_components=" + clipboard.structureComponents().size();
	}

	private static String prototypeSummary(SandboxSession sandbox) {
		SandboxSession.PrototypeSandbox prototype = sandbox.prototypeSandbox();
		if (prototype == null) {
			return "inactive";
		}
		return "active, min=" + pos(prototype.min())
				+ ", max=" + pos(prototype.max())
				+ ", size=" + prototype.size()
				+ ", volume=" + prototype.volume()
				+ ", edit_records=" + prototype.usedEditRecords() + "/" + prototype.maxEditRecords()
				+ ", remaining=" + prototype.remainingEditRecords();
	}

	private static String imageLabels(List<AgentImageAttachment> images) {
		if (images.isEmpty()) {
			return "none";
		}
		return String.join(", ", images.stream().map(AgentImageAttachment::label).toList());
	}

	private static String pos(BlockPos pos) {
		return "%d,%d,%d".formatted(pos.getX(), pos.getY(), pos.getZ());
	}

	private static String size(BlockPos min, BlockPos max) {
		return "%dx%dx%d".formatted(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
	}

	private static long volume(BlockPos min, BlockPos max) {
		long x = max.getX() - min.getX() + 1L;
		long y = max.getY() - min.getY() + 1L;
		long z = max.getZ() - min.getZ() + 1L;
		return x * y * z;
	}
}
