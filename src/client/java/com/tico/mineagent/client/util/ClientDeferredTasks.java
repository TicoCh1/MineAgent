package com.tico.mineagent.client.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

public final class ClientDeferredTasks {
	private static final List<DelayedTask> TASKS = new ArrayList<>();

	private ClientDeferredTasks() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> runDueTasks());
	}

	public static void afterClientTicks(int ticks, Runnable task) {
		if (ticks <= 0) {
			Minecraft.getInstance().execute(task);
			return;
		}
		synchronized (TASKS) {
			TASKS.add(new DelayedTask(ticks, task));
		}
	}

	private static void runDueTasks() {
		List<Runnable> due = new ArrayList<>();
		synchronized (TASKS) {
			Iterator<DelayedTask> iterator = TASKS.iterator();
			while (iterator.hasNext()) {
				DelayedTask delayed = iterator.next();
				delayed.ticksRemaining--;
				if (delayed.ticksRemaining <= 0) {
					due.add(delayed.task);
					iterator.remove();
				}
			}
		}
		for (Runnable task : due) {
			task.run();
		}
	}

	private static final class DelayedTask {
		private int ticksRemaining;
		private final Runnable task;

		private DelayedTask(int ticksRemaining, Runnable task) {
			this.ticksRemaining = ticksRemaining;
			this.task = task;
		}
	}
}
