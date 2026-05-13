package com.tico.mineagent.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.tico.mineagent.client.capture.ClientGpuCaptureContext;

@Mixin(Minecraft.class)
public abstract class MinecraftCaptureMixin {
	@Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
	private void mineagent$useGpuCaptureRenderTarget(CallbackInfoReturnable<RenderTarget> cir) {
		if (ClientGpuCaptureContext.active()) {
			cir.setReturnValue(ClientGpuCaptureContext.renderTarget());
		}
	}
}
