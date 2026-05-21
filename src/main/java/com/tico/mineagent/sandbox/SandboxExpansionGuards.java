package com.tico.mineagent.sandbox;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public final class SandboxExpansionGuards {
	private static final ThreadLocal<RequestHandler> CURRENT = new ThreadLocal<>();

	private SandboxExpansionGuards() {
	}

	public static Scope install(RequestHandler handler) {
		RequestHandler previous = CURRENT.get();
		CURRENT.set(handler);
		return () -> {
			if (previous == null) {
				CURRENT.remove();
			} else {
				CURRENT.set(previous);
			}
		};
	}

	public static void handle(ServerLevel level, SandboxSession sandbox, BlockPos requestedMin, BlockPos requestedMax, String operation, List<String> warnings) {
		RequestHandler handler = CURRENT.get();
		if (handler != null) {
			handler.handle(level, sandbox, requestedMin, requestedMax, operation, warnings);
		}
	}

	@FunctionalInterface
	public interface RequestHandler {
		void handle(ServerLevel level, SandboxSession sandbox, BlockPos requestedMin, BlockPos requestedMax, String operation, List<String> warnings);
	}

	@FunctionalInterface
	public interface Scope extends AutoCloseable {
		@Override
		void close();
	}
}
