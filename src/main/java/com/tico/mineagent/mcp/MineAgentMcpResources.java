package com.tico.mineagent.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import com.tico.mineagent.clipboard.AgentClipboard;
import com.tico.mineagent.history.AgentEditRecord;
import com.tico.mineagent.mask.AgentMaskDefinition;
import com.tico.mineagent.palette.BlockPaletteIndex;
import com.tico.mineagent.sandbox.SandboxSession;

public final class MineAgentMcpResources {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final int CAPTURE_INDEX_LIMIT = 24;
	private static final int HISTORY_LIMIT = 12;

	private MineAgentMcpResources() {
	}

	public static List<MineAgentMcpResource> create() {
		List<MineAgentMcpResource> resources = new ArrayList<>();
		resources.add(resource(
				"sandbox://current",
				"sandbox_current",
				"Current MineAgent sandbox",
				"Current player sandbox bounds, dimension, player position, clipboard summary, and safety boundary.",
				MineAgentMcpResources::sandbox));
		resources.add(resource(
				"anchors://list",
				"anchors_list",
				"MineAgent anchors",
				"Current named coordinate anchors for this player session.",
				MineAgentMcpResources::anchors));
		resources.add(resource(
				"masks://list",
				"masks_list",
				"MineAgent masks",
				"Current named reusable masks for this player session.",
				MineAgentMcpResources::masks));
		resources.add(resource(
				"history://recent",
				"history_recent",
				"Recent MineAgent history",
				"Small recent undo/redo summary for this player session.",
				MineAgentMcpResources::history));
		resources.add(resource(
				"captures://index",
				"captures_index",
				"Recent MineAgent captures",
				"Small index of recent saved capture image metadata. Does not include image bytes.",
				MineAgentMcpResources::captures));
		resources.add(resource(
				"palette://metadata",
				"palette_metadata",
				"Block palette metadata",
				"Small metadata summary for the Minecraft block color/geometry palette. Use mineagent_block_palette_query for search; the full CSV is large and not recommended for routine model reads.",
				MineAgentMcpResources::paletteMetadata));
		return List.copyOf(resources);
	}

	private static MineAgentMcpResource resource(String uri, String name, String title, String description, MineAgentMcpResourceReader reader) {
		return new MineAgentMcpResource(uri, name, title, description, "application/json", reader);
	}

	private static JsonObject sandbox(MineAgentMcpContext context) {
		SandboxSession sandbox = context.sandbox();
		JsonObject object = new JsonObject();
		object.addProperty("resource", "sandbox://current");
		object.addProperty("player", context.player().getName().getString());
		object.addProperty("player_uuid", context.player().getUUID().toString());
		ResourceKey<Level> dimension = context.player().level().dimension();
		object.addProperty("dimension", dimension.location().toString());
		object.add("player_pos", pos(context.player().blockPosition()));
		object.addProperty("complete", sandbox.hasCompleteBounds());
		object.addProperty("selector", sandbox.selectorType().id());
		if (sandbox.hasCompleteBounds()) {
			BlockPos min = sandbox.min();
			BlockPos max = sandbox.max();
			object.add("min", pos(min));
			object.add("max", pos(max));
			object.addProperty("summary", sandbox.boundsSummary());
			object.addProperty("size", size(min, max));
			object.addProperty("volume_blocks", volume(min, max));
		}
		object.add("clipboard", clipboard(sandbox.clipboard()));
		JsonObject history = new JsonObject();
		history.addProperty("undo_records", sandbox.undoCount());
		history.addProperty("redo_records", sandbox.redoCount());
		object.add("history", history);
		return object;
	}

	private static JsonObject anchors(MineAgentMcpContext context) {
		JsonObject object = new JsonObject();
		object.addProperty("resource", "anchors://list");
		JsonArray items = new JsonArray();
		for (Map.Entry<String, BlockPos> entry : new TreeMap<>(context.sandbox().anchors()).entrySet()) {
			JsonObject item = new JsonObject();
			item.addProperty("name", entry.getKey());
			item.add("pos", pos(entry.getValue()));
			items.add(item);
		}
		object.addProperty("count", items.size());
		object.add("anchors", items);
		return object;
	}

	private static JsonObject masks(MineAgentMcpContext context) {
		JsonObject object = new JsonObject();
		object.addProperty("resource", "masks://list");
		JsonArray items = new JsonArray();
		for (Map.Entry<String, AgentMaskDefinition> entry : new TreeMap<>(context.sandbox().masks()).entrySet()) {
			items.add(mask(entry.getValue()));
		}
		object.addProperty("count", items.size());
		object.add("masks", items);
		return object;
	}

	private static JsonObject history(MineAgentMcpContext context) {
		SandboxSession sandbox = context.sandbox();
		JsonObject object = new JsonObject();
		object.addProperty("resource", "history://recent");
		object.addProperty("undo_records", sandbox.undoCount());
		object.addProperty("redo_records", sandbox.redoCount());
		object.add("recent_undo", historyRecords(sandbox.undoHistorySnapshot()));
		object.add("recent_redo", historyRecords(sandbox.redoHistorySnapshot()));
		return object;
	}

	private static JsonObject captures(MineAgentMcpContext context) {
		JsonObject object = new JsonObject();
		object.addProperty("resource", "captures://index");
		Path indexPath = FabricLoader.getInstance().getGameDir()
				.resolve("mineagent-logs")
				.resolve("captures")
				.resolve("captures.jsonl");
		object.addProperty("index_path", indexPath.toAbsolutePath().toString());
		JsonArray captures = new JsonArray();
		if (Files.isRegularFile(indexPath)) {
			try {
				List<String> lines = Files.readAllLines(indexPath, StandardCharsets.UTF_8);
				int start = Math.max(0, lines.size() - CAPTURE_INDEX_LIMIT);
				for (int i = start; i < lines.size(); i++) {
					String line = lines.get(i).trim();
					if (line.isBlank()) {
						continue;
					}
					try {
						JsonElement element = GSON.fromJson(line, JsonElement.class);
						if (element == null || element.isJsonNull()) {
							captures.add(line);
						} else {
							captures.add(element);
						}
					} catch (RuntimeException exception) {
						captures.add(line);
					}
				}
			} catch (IOException exception) {
				object.addProperty("read_error", exception.getMessage());
			}
		}
		object.addProperty("returned", captures.size());
		object.addProperty("limit", CAPTURE_INDEX_LIMIT);
		object.add("captures", captures);
		return object;
	}

	private static JsonObject paletteMetadata(MineAgentMcpContext context) throws IOException {
		BlockPaletteIndex palette = BlockPaletteIndex.load();
		JsonObject object = new JsonObject();
		object.addProperty("resource", "palette://metadata");
		object.addProperty("minecraft_version", "1.21.10");
		object.addProperty("csv_resource_path", BlockPaletteIndex.resourcePath());
		object.addProperty("row_count", palette.entryCount());
		object.addProperty("query_tool", "mineagent_block_palette_query");
		object.add("columns", strings(palette.headers()));
		JsonObject recommended = new JsonObject();
		recommended.addProperty("method", "tool_call");
		recommended.addProperty("tool", "mineagent_block_palette_query");
		recommended.addProperty("reason", "Search, filter, and rank a small candidate set instead of reading the full color table.");
		object.add("recommended_access", recommended);
		JsonObject fullTable = new JsonObject();
		fullTable.addProperty("uri", "palette://all.csv");
		fullTable.addProperty("large", true);
		fullTable.addProperty("exposed", false);
		fullTable.addProperty("recommendation", "Do not read the full CSV during routine building. Use the query tool or specific future paged resources.");
		object.add("full_table", fullTable);
		return object;
	}

	private static JsonArray historyRecords(List<AgentEditRecord> records) {
		JsonArray array = new JsonArray();
		int limit = Math.min(HISTORY_LIMIT, records.size());
		for (int i = 0; i < limit; i++) {
			AgentEditRecord record = records.get(i);
			JsonObject item = new JsonObject();
			item.addProperty("label", record.label());
			item.addProperty("change_count", record.changes().size());
			item.add("affected_min", pos(record.affectedMin()));
			item.add("affected_max", pos(record.affectedMax()));
			array.add(item);
		}
		return array;
	}

	private static JsonObject clipboard(AgentClipboard clipboard) {
		JsonObject object = new JsonObject();
		object.addProperty("present", clipboard != null && !clipboard.empty());
		if (clipboard != null && !clipboard.empty()) {
			object.addProperty("block_count", clipboard.blockCount());
			object.add("source_min", pos(clipboard.sourceMin()));
			object.add("source_max", pos(clipboard.sourceMax()));
			object.add("reference", pos(clipboard.reference()));
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

	private static JsonObject pos(BlockPos pos) {
		JsonObject object = new JsonObject();
		object.addProperty("x", pos.getX());
		object.addProperty("y", pos.getY());
		object.addProperty("z", pos.getZ());
		object.addProperty("text", "%d,%d,%d".formatted(pos.getX(), pos.getY(), pos.getZ()));
		return object;
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

	private static JsonArray strings(List<String> values) {
		JsonArray array = new JsonArray();
		for (String value : values) {
			array.add(value);
		}
		return array;
	}
}
