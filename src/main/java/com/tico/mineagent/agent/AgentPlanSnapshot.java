package com.tico.mineagent.agent;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public record AgentPlanSnapshot(String explanation, List<AgentPlanItem> items) {
	private static final Gson GSON = new Gson();
	private static final AgentPlanSnapshot EMPTY = new AgentPlanSnapshot("", List.of());

	public AgentPlanSnapshot {
		explanation = explanation == null ? "" : explanation.trim();
		items = List.copyOf(items == null ? List.of() : items);
	}

	public static AgentPlanSnapshot empty() {
		return EMPTY;
	}

	public boolean isEmpty() {
		return explanation.isBlank() && items.isEmpty();
	}

	public JsonObject toJsonObject() {
		JsonObject object = new JsonObject();
		object.addProperty("explanation", explanation);
		JsonArray array = new JsonArray();
		for (AgentPlanItem item : items) {
			JsonObject itemObject = new JsonObject();
			itemObject.addProperty("step", item.step());
			itemObject.addProperty("status", item.status());
			array.add(itemObject);
		}
		object.add("plan", array);
		return object;
	}

	public String toJsonString() {
		return GSON.toJson(toJsonObject());
	}

	public static AgentPlanSnapshot fromJsonString(String json) {
		if (json == null || json.isBlank()) {
			return empty();
		}
		try {
			JsonObject object = JsonParser.parseString(json).getAsJsonObject();
			String explanation = object.has("explanation") && !object.get("explanation").isJsonNull()
					? object.get("explanation").getAsString()
					: "";
			JsonArray array = object.getAsJsonArray("plan");
			if (array == null) {
				return new AgentPlanSnapshot(explanation, List.of());
			}
			List<AgentPlanItem> items = new ArrayList<>();
			for (int i = 0; i < array.size(); i++) {
				if (!array.get(i).isJsonObject()) {
					continue;
				}
				JsonObject item = array.get(i).getAsJsonObject();
				String step = item.has("step") && !item.get("step").isJsonNull() ? item.get("step").getAsString() : "";
				String status = item.has("status") && !item.get("status").isJsonNull() ? item.get("status").getAsString() : AgentPlanItem.PENDING;
				items.add(new AgentPlanItem(step, status));
			}
			return new AgentPlanSnapshot(explanation, items);
		} catch (RuntimeException exception) {
			return empty();
		}
	}
}
