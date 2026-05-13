package com.tico.mineagent.tool;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import com.tico.mineagent.sandbox.SandboxSession;
import com.tico.mineagent.sandbox.SandboxSessions;

public final class SandboxToolEvents {
	private SandboxToolEvents() {
	}

	public static void register() {
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (!isSandboxTool(player.getItemInHand(hand), hand) || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}

			SandboxSession session = SandboxSessions.get(serverPlayer);
			if (!canUseSandboxTool(serverPlayer)) {
				return InteractionResult.PASS;
			}

			session.selectPrimary(pos.immutable());
			SandboxSessions.sync(serverPlayer);
			serverPlayer.sendSystemMessage(Component.literal("MineAgent sandbox point 1 set to " + format(pos) + "."));
			return InteractionResult.SUCCESS;
		});

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (!isSandboxTool(player.getItemInHand(hand), hand)) {
				return InteractionResult.PASS;
			}

			if (world instanceof Level level && level.isClientSide()) {
				return InteractionResult.PASS;
			}

			if (!(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}

			SandboxSession session = SandboxSessions.get(serverPlayer);
			if (!canUseSandboxTool(serverPlayer)) {
				return InteractionResult.PASS;
			}

			session.selectSecondary(hitResult.getBlockPos().immutable());
			SandboxSessions.sync(serverPlayer);
			serverPlayer.sendSystemMessage(Component.literal("MineAgent sandbox point 2 set to " + format(hitResult.getBlockPos()) + ". Bounds: " + session.boundsSummary()));
			return InteractionResult.SUCCESS;
		});
	}

	private static boolean isSandboxTool(net.minecraft.world.item.ItemStack stack, InteractionHand hand) {
		return hand == InteractionHand.MAIN_HAND && stack.is(Items.NETHERITE_HOE);
	}

	private static boolean canUseSandboxTool(ServerPlayer player) {
		return player.createCommandSourceStack().hasPermission(2) && SandboxSessions.effectiveToolEnabled(player);
	}

	private static String format(net.minecraft.core.BlockPos pos) {
		return "(%d, %d, %d)".formatted(pos.getX(), pos.getY(), pos.getZ());
	}
}
