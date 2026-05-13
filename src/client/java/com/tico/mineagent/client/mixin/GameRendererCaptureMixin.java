package com.tico.mineagent.client.mixin;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;

import com.tico.mineagent.client.capture.ClientGpuCaptureContext;

@Mixin(GameRenderer.class)
public abstract class GameRendererCaptureMixin {
	@Inject(method = "getFov", at = @At("HEAD"), cancellable = true)
	private void mineagent$overrideGpuCaptureFov(Camera camera, float partialTick, boolean changingFov, CallbackInfoReturnable<Float> cir) {
		if (ClientGpuCaptureContext.active()) {
			cir.setReturnValue(ClientGpuCaptureContext.fovDegrees());
		}
	}

	@Inject(method = "getProjectionMatrix", at = @At("HEAD"), cancellable = true)
	private void mineagent$overrideGpuCaptureProjection(float fov, CallbackInfoReturnable<Matrix4f> cir) {
		if (ClientGpuCaptureContext.active()) {
			cir.setReturnValue(ClientGpuCaptureContext.projection((GameRenderer) (Object) this));
		}
	}

	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void mineagent$skipGpuCaptureHand(float partialTick, boolean sleeping, Matrix4f cameraRotation, CallbackInfo ci) {
		if (ClientGpuCaptureContext.active()) {
			ci.cancel();
		}
	}
}
