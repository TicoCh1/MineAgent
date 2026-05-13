package com.tico.mineagent.client.capture;

import java.util.List;

import net.minecraft.resources.ResourceLocation;

public record GpuCaptureImageSet(
		String type,
		int width,
		int height,
		double fovDegrees,
		long durationMillis,
		List<Image> images) {
	public GpuCaptureImageSet {
		images = List.copyOf(images);
	}

	public record Image(String label, ResourceLocation texture, int width, int height, String localFile) {
	}
}
