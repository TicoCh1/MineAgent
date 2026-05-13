package com.tico.mineagent.client.raycast;

/*
 * Color compositing helpers adapted from Camera Obscura's ColorHelper.
 * Camera Obscura is Copyright (c) 2024 tomalbrc and licensed under LGPL-3.0-or-later.
 */

final class RaycastColorMath {
	private RaycastColorMath() {
	}

	static int packColor(double[] color) {
		int alpha = (int) Math.max(0, Math.min(255, color[0] * 255));
		int red = (int) Math.max(0, Math.min(255, color[1] * 255));
		int green = (int) Math.max(0, Math.min(255, color[2] * 255));
		int blue = (int) Math.max(0, Math.min(255, color[3] * 255));
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}

	static double[] unpackColor(int color) {
		int alpha = (color >> 24) & 0xFF;
		int red = (color >> 16) & 0xFF;
		int green = (color >> 8) & 0xFF;
		int blue = color & 0xFF;
		return new double[] { alpha / 255.0D, red / 255.0D, green / 255.0D, blue / 255.0D };
	}

	static int alphaComposite(int color1, int color2) {
		return packColor(alphaComposite(unpackColor(color1), unpackColor(color2)));
	}

	static double[] alphaComposite(double[] color1, double[] color2) {
		double alpha1 = color1[0];
		double alpha2 = color2[0];
		double alphaResult = alpha1 + alpha2 * (1.0D - alpha1);
		double[] result = new double[4];
		if (alphaResult == 0.0D) {
			return result;
		}
		result[0] = alphaResult;
		result[1] = (color1[1] * alpha1 + color2[1] * alpha2 * (1.0D - alpha1)) / alphaResult;
		result[2] = (color1[2] * alpha1 + color2[2] * alpha2 * (1.0D - alpha1)) / alphaResult;
		result[3] = (color1[3] * alpha1 + color2[3] * alpha2 * (1.0D - alpha1)) / alphaResult;
		return result;
	}
}
