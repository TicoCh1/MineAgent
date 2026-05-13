package com.tico.mineagent.client.state;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import com.tico.mineagent.network.SandboxStatePayload;

public final class ClientSandboxState {
	private static boolean toolEnabled;
	private static String selectorId = "cuboid";
	private static boolean complete;
	private static BlockPos min = BlockPos.ZERO;
	private static BlockPos max = BlockPos.ZERO;

	private ClientSandboxState() {
	}

	public static void registerReceiver() {
		ClientPlayNetworking.registerGlobalReceiver(SandboxStatePayload.ID, (payload, context) -> context.client().execute(() -> {
			toolEnabled = payload.toolEnabled();
			selectorId = payload.selectorId();
			complete = payload.complete();
			min = payload.min();
			max = payload.max();
		}));
	}

	public static boolean toolEnabled() {
		return toolEnabled;
	}

	public static String selectorId() {
		return selectorId;
	}

	public static boolean complete() {
		return complete;
	}

	public static BlockPos min() {
		return min;
	}

	public static BlockPos max() {
		return max;
	}

	public static AABB bounds() {
		return new AABB(
				min.getX(),
				min.getY(),
				min.getZ(),
				max.getX() + 1.0,
				max.getY() + 1.0,
				max.getZ() + 1.0);
	}

	public static String summary() {
		if (!complete) {
			return "sandbox incomplete";
		}
		return "%d,%d,%d -> %d,%d,%d".formatted(
				min.getX(),
				min.getY(),
				min.getZ(),
				max.getX(),
				max.getY(),
				max.getZ());
	}
}
