package com.tico.mineagent.agent;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.tico.mineagent.network.MineAgentNetworking;

public final class AgentHostToolRegistry {
	public static final String UPDATE_PLAN_TOOL = "mineagent_update_plan";
	private static final int MAX_PLAN_ITEMS = 20;
	private static final int MAX_STEP_CHARS = 180;
	private static final int MAX_EXPLANATION_CHARS = 512;

	private AgentHostToolRegistry() {
	}

	public static List<AgentTool> tools() {
		return List.of(new AgentTool(
				UPDATE_PLAN_TOOL,
				"Update the visible MineAgent build plan shown to the player, matching Codex-style plan tracking. Use this for non-trivial, multi-phase building tasks, when the approach changes, and after completing meaningful subtasks. The plan argument is the full current plan, not a patch. Each step must be concise, verifiable, and marked pending, in_progress, or completed. Keep at most one in_progress step. Do not use this for trivial one-step tasks.",
				planSchema(),
				false,
				AgentHostToolRegistry::updatePlan));
	}

	private static AgentToolOutput updatePlan(AgentToolContext context, JsonObject arguments) {
		String explanation = clipped(optionalString(arguments, "explanation"), MAX_EXPLANATION_CHARS);
		List<AgentPlanItem> items = parsePlanItems(arguments);
		AgentPlanSnapshot snapshot = AgentPlanStates.update(context.player(), explanation, items);
		MineAgentNetworking.sendAgentState(context.player());
		JsonObject content = snapshot.toJsonObject();
		content.addProperty("visible_in_ui", true);
		content.addProperty("tool_scope", "built_in_host_only");
		return AgentToolOutput.ok(content);
	}

	private static List<AgentPlanItem> parsePlanItems(JsonObject arguments) {
		if (!arguments.has("plan") || arguments.get("plan").isJsonNull() || !arguments.get("plan").isJsonArray()) {
			throw new IllegalArgumentException("mineagent_update_plan requires a plan array.");
		}
		JsonArray array = arguments.getAsJsonArray("plan");
		if (array.size() > MAX_PLAN_ITEMS) {
			throw new IllegalArgumentException("MineAgent visible plan may contain at most " + MAX_PLAN_ITEMS + " items.");
		}

		List<AgentPlanItem> items = new ArrayList<>();
		int inProgress = 0;
		for (JsonElement element : array) {
			if (!element.isJsonObject()) {
				throw new IllegalArgumentException("Each MineAgent plan item must be an object.");
			}
			JsonObject object = element.getAsJsonObject();
			String step = clipped(requireString(object, "step"), MAX_STEP_CHARS);
			if (step.isBlank()) {
				throw new IllegalArgumentException("MineAgent plan steps must not be blank.");
			}
			String status = AgentPlanItem.normalizeStatus(requireString(object, "status"));
			if (AgentPlanItem.IN_PROGRESS.equals(status)) {
				inProgress++;
			}
			items.add(new AgentPlanItem(step, status));
		}
		if (inProgress > 1) {
			throw new IllegalArgumentException("MineAgent visible plan accepts at most one in_progress item.");
		}
		return List.copyOf(items);
	}

	private static JsonObject planSchema() {
		JsonObject item = new JsonObject();
		item.addProperty("type", "object");
		item.add("properties", properties(
				property("step", string("Concise, verifiable build subtask.")),
				property("status", enumString("Current subtask state.", AgentPlanItem.PENDING, AgentPlanItem.IN_PROGRESS, AgentPlanItem.COMPLETED))));
		item.add("required", requiredArray("step", "status"));
		item.addProperty("additionalProperties", false);

		JsonObject plan = new JsonObject();
		plan.addProperty("type", "array");
		plan.addProperty("description", "Full current visible plan. Use [] only to intentionally clear the plan.");
		plan.add("items", item);

		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties(
				property("explanation", string("Optional short reason for the plan update.")),
				property("plan", plan)));
		schema.add("required", requiredArray("plan"));
		schema.addProperty("additionalProperties", false);
		return schema;
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

	private static JsonArray requiredArray(String... names) {
		JsonArray required = new JsonArray();
		for (String name : names) {
			required.add(name);
		}
		return required;
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

	private static String requireString(JsonObject object, String name) {
		if (!object.has(name) || object.get(name).isJsonNull()) {
			throw new IllegalArgumentException("Missing required argument: " + name);
		}
		String value = object.get(name).getAsString();
		if (value.isBlank()) {
			throw new IllegalArgumentException("Argument must not be blank: " + name);
		}
		return value.trim();
	}

	private static String optionalString(JsonObject object, String name) {
		if (!object.has(name) || object.get(name).isJsonNull()) {
			return "";
		}
		return object.get(name).getAsString().trim();
	}

	private static String clipped(String value, int maxChars) {
		String text = value == null ? "" : value.trim();
		return text.length() <= maxChars ? text : text.substring(0, maxChars);
	}

	private record NamedProperty(String name, JsonObject schema) {
	}
}
