package com.tico.mineagent.history;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import com.tico.mineagent.geometry.GeometryEditResult;

public record AgentEditRecord(String label, List<AgentBlockChange> changes, BlockPos affectedMin, BlockPos affectedMax) {
	public AgentEditRecord {
		changes = List.copyOf(changes);
		affectedMin = affectedMin.immutable();
		affectedMax = affectedMax.immutable();
	}

	public boolean empty() {
		return changes.isEmpty();
	}

	public GeometryEditResult undo(ServerLevel level) {
		List<String> warnings = new ArrayList<>();
		int changed = 0;
		int unchanged = 0;
		int conflicts = 0;
		for (int i = changes.size() - 1; i >= 0; i--) {
			AgentBlockChange change = changes.get(i);
			if (!change.currentMatchesAfter(level)) {
				conflicts++;
			}
			if (change.undo(level)) {
				changed++;
			} else {
				unchanged++;
			}
		}
		if (conflicts > 0) {
			warnings.add("Undo detected " + conflicts + " block(s) changed after the original MineAgent edit and overwrote them with the recorded previous state.");
		}
		return result(changed, unchanged, warnings);
	}

	public GeometryEditResult redo(ServerLevel level) {
		List<String> warnings = new ArrayList<>();
		int changed = 0;
		int unchanged = 0;
		int conflicts = 0;
		for (AgentBlockChange change : changes) {
			if (!change.currentMatchesBefore(level)) {
				conflicts++;
			}
			if (change.redo(level)) {
				changed++;
			} else {
				unchanged++;
			}
		}
		if (conflicts > 0) {
			warnings.add("Redo detected " + conflicts + " block(s) changed since undo and overwrote them with the recorded edited state.");
		}
		return result(changed, unchanged, warnings);
	}

	private GeometryEditResult result(int changed, int unchanged, List<String> warnings) {
		return new GeometryEditResult(
				changes.size(),
				changed,
				unchanged,
				0,
				0,
				0,
				true,
				affectedMin,
				affectedMax,
				warnings);
	}
}
