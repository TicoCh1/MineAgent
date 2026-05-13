package com.tico.mineagent.client.state;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import com.tico.mineagent.network.AgentEditBoundsPayload;

public final class ClientAgentEditBoundsState {
	private static final List<EditBounds> ENTRIES = new ArrayList<>();

	private ClientAgentEditBoundsState() {
	}

	public static void registerReceiver() {
		ClientPlayNetworking.registerGlobalReceiver(AgentEditBoundsPayload.ID, (payload, context) -> context.client().execute(() -> {
			if (!payload.visible()) {
				ENTRIES.clear();
				return;
			}
			ENTRIES.add(new EditBounds(payload.min(), payload.max(), payload.label()));
		}));
	}

	public static boolean visible() {
		return !ENTRIES.isEmpty();
	}

	public static List<EditBounds> entries() {
		return List.copyOf(ENTRIES);
	}

	public record EditBounds(BlockPos min, BlockPos max, String label) {
		public AABB bounds() {
			return new AABB(
					min.getX(),
					min.getY(),
					min.getZ(),
					max.getX() + 1.0,
					max.getY() + 1.0,
					max.getZ() + 1.0);
		}
	}
}
