package com.tico.mineagent.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tico.mineagent.client.capture.ClientGpuCaptureContext;

@Mixin(Camera.class)
public abstract class CameraCaptureMixin {
	@Inject(method = "setup", at = @At("RETURN"))
	private void mineagent$applyGpuCaptureCamera(BlockGetter level, Entity entity, boolean detached, boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
		if (ClientGpuCaptureContext.active()) {
			ClientGpuCaptureContext.apply((Camera) (Object) this);
		}
	}
}
