package com.tico.mineagent.history;

import net.minecraft.server.level.ServerLevel;

public record AgentBlockChange(AgentBlockSnapshot before, AgentBlockSnapshot after) {
	public boolean changed() {
		return !before.sameBlockData(after);
	}

	public boolean undo(ServerLevel level) {
		return before.place(level);
	}

	public boolean redo(ServerLevel level) {
		return after.place(level);
	}

	public boolean currentMatchesAfter(ServerLevel level) {
		return AgentBlockSnapshot.capture(level, after.pos()).sameBlockData(after);
	}

	public boolean currentMatchesBefore(ServerLevel level) {
		return AgentBlockSnapshot.capture(level, before.pos()).sameBlockData(before);
	}
}
