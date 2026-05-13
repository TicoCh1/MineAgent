package com.tico.mineagent.client.raycast;

import java.util.List;

import net.minecraft.resources.ResourceLocation;

public record RaycastImageSet(
		int size,
		String mode,
		double fovDegrees,
		double maxDistance,
		long durationMillis,
		int hitPixels,
		int airPixels,
		int outOfSandboxPixels,
		double cameraX,
		double cameraY,
		double cameraZ,
		ResourceLocation colorTexture,
		ResourceLocation depthTexture,
		ResourceLocation blockIdTexture,
		ResourceLocation positionTexture,
		List<String> localFiles) {
	public RaycastImageSet {
		localFiles = List.copyOf(localFiles);
	}
}
