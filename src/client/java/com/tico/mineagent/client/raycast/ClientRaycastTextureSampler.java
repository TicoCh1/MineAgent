package com.tico.mineagent.client.raycast;

import com.mojang.blaze3d.platform.NativeImage;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import com.tico.mineagent.client.mixin.SpriteContentsAccessor;

final class ClientRaycastTextureSampler {
	private static final double EPSILON = 1.0E-6D;

	private ClientRaycastTextureSampler() {
	}

	static int sampleBlock(
			Minecraft client,
			ClientLevel level,
			BlockPos pos,
			BlockState state,
			Vec3 origin,
			Vec3 direction,
			Direction hitFace,
			int fallbackColor) {
		try {
			BlockStateModel model = client.getBlockRenderer().getBlockModel(state);
			List<BlockModelPart> parts = model.collectParts(RandomSource.create(state.getSeed(pos)));
			Vec3 localOrigin = origin.subtract(pos.getX(), pos.getY(), pos.getZ());
			TextureHit best = TextureHit.NONE;

			for (BlockModelPart part : parts) {
				best = bestCloser(best, sampleQuads(client, level, state, pos, quads(part, hitFace), localOrigin, direction));
				best = bestCloser(best, sampleQuads(client, level, state, pos, quads(part, null), localOrigin, direction));
				if (best.hit()) {
					continue;
				}
				for (Direction face : Direction.values()) {
					best = bestCloser(best, sampleQuads(client, level, state, pos, quads(part, face), localOrigin, direction));
				}
			}

			return best.hit() ? best.color() : fallbackColor;
		} catch (RuntimeException exception) {
			return fallbackColor;
		}
	}

	private static List<BakedQuad> quads(BlockModelPart part, Direction face) {
		try {
			return part.getQuads(face);
		} catch (RuntimeException exception) {
			return List.of();
		}
	}

	private static TextureHit sampleQuads(
			Minecraft client,
			ClientLevel level,
			BlockState state,
			BlockPos pos,
			List<BakedQuad> quads,
			Vec3 localOrigin,
			Vec3 direction) {
		TextureHit best = TextureHit.NONE;
		for (BakedQuad quad : quads) {
			TextureHit hit = intersectQuad(client, level, state, pos, quad, localOrigin, direction);
			best = bestCloser(best, hit);
		}
		return best;
	}

	private static TextureHit intersectQuad(
			Minecraft client,
			ClientLevel level,
			BlockState state,
			BlockPos pos,
			BakedQuad quad,
			Vec3 localOrigin,
			Vec3 direction) {
		int[] data = quad.vertices();
		if (data.length < 28) {
			return TextureHit.NONE;
		}

		int stride = data.length / 4;
		Vertex a = vertex(data, stride, 0);
		Vertex b = vertex(data, stride, 1);
		Vertex c = vertex(data, stride, 2);
		Vertex d = vertex(data, stride, 3);

		TextureHit first = intersectTriangle(client, level, state, pos, quad, localOrigin, direction, a, b, c);
		TextureHit second = intersectTriangle(client, level, state, pos, quad, localOrigin, direction, a, c, d);
		return bestCloser(first, second);
	}

	private static TextureHit intersectTriangle(
			Minecraft client,
			ClientLevel level,
			BlockState state,
			BlockPos pos,
			BakedQuad quad,
			Vec3 localOrigin,
			Vec3 direction,
			Vertex a,
			Vertex b,
			Vertex c) {
		Vec3 edgeOne = b.pos().subtract(a.pos());
		Vec3 edgeTwo = c.pos().subtract(a.pos());
		Vec3 p = direction.cross(edgeTwo);
		double determinant = edgeOne.dot(p);
		if (Math.abs(determinant) < EPSILON) {
			return TextureHit.NONE;
		}

		double inverseDeterminant = 1.0D / determinant;
		Vec3 t = localOrigin.subtract(a.pos());
		double u = t.dot(p) * inverseDeterminant;
		if (u < -EPSILON || u > 1.0D + EPSILON) {
			return TextureHit.NONE;
		}

		Vec3 q = t.cross(edgeOne);
		double v = direction.dot(q) * inverseDeterminant;
		if (v < -EPSILON || u + v > 1.0D + EPSILON) {
			return TextureHit.NONE;
		}

		double rayT = edgeTwo.dot(q) * inverseDeterminant;
		if (rayT < EPSILON) {
			return TextureHit.NONE;
		}

		float spriteU = (float) (a.u() + u * (b.u() - a.u()) + v * (c.u() - a.u()));
		float spriteV = (float) (a.v() + u * (b.v() - a.v()) + v * (c.v() - a.v()));
		int color = sampleSprite(quad.sprite(), spriteU, spriteV);
		if (quad.isTinted()) {
			color = multiplyTint(client.getBlockColors(), level, state, pos, quad.tintIndex(), color);
		}
		if (quad.shade()) {
			color = shade(color, quad.direction());
		}
		return new TextureHit(true, rayT, color);
	}

	private static Vertex vertex(int[] data, int stride, int index) {
		int base = index * stride;
		return new Vertex(
				new Vec3(
						Float.intBitsToFloat(data[base]),
						Float.intBitsToFloat(data[base + 1]),
						Float.intBitsToFloat(data[base + 2])),
				Float.intBitsToFloat(data[base + 4]),
				Float.intBitsToFloat(data[base + 5]));
	}

	private static int sampleSprite(TextureAtlasSprite sprite, float atlasU, float atlasV) {
		SpriteContents contents = sprite.contents();
		NativeImage image = ((SpriteContentsAccessor) contents).mineagent$originalImage();
		float localU = Mth.clamp(sprite.getUOffset(atlasU) / 16.0F, 0.0F, 0.9999F);
		float localV = Mth.clamp(sprite.getVOffset(atlasV) / 16.0F, 0.0F, 0.9999F);
		int x = Mth.clamp((int) (localU * contents.width()), 0, contents.width() - 1);
		int y = Mth.clamp((int) (localV * contents.height()), 0, contents.height() - 1);
		return nativeAbgrToArgb(image.getPixel(x, y));
	}

	private static int multiplyTint(BlockColors blockColors, ClientLevel level, BlockState state, BlockPos pos, int tintIndex, int color) {
		int tint = blockColors.getColor(state, level, pos, tintIndex);
		if (tint == -1) {
			return color;
		}

		int alpha = color >>> 24;
		int red = ((color >>> 16) & 0xFF) * ((tint >>> 16) & 0xFF) / 255;
		int green = ((color >>> 8) & 0xFF) * ((tint >>> 8) & 0xFF) / 255;
		int blue = (color & 0xFF) * (tint & 0xFF) / 255;
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}

	private static int shade(int argb, Direction face) {
		double factor = switch (face) {
			case UP -> 1.0D;
			case DOWN -> 0.48D;
			case NORTH, SOUTH -> 0.74D;
			case EAST, WEST -> 0.64D;
		};
		int alpha = argb >>> 24;
		int red = Mth.clamp((int) Math.round(((argb >> 16) & 0xFF) * factor), 0, 255);
		int green = Mth.clamp((int) Math.round(((argb >> 8) & 0xFF) * factor), 0, 255);
		int blue = Mth.clamp((int) Math.round((argb & 0xFF) * factor), 0, 255);
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}

	private static int nativeAbgrToArgb(int abgr) {
		int alpha = (abgr >>> 24) & 0xFF;
		int blue = (abgr >>> 16) & 0xFF;
		int green = (abgr >>> 8) & 0xFF;
		int red = abgr & 0xFF;
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}

	private static TextureHit bestCloser(TextureHit current, TextureHit candidate) {
		if (!candidate.hit()) {
			return current;
		}
		if (!current.hit() || candidate.t() < current.t()) {
			return candidate;
		}
		return current;
	}

	private record Vertex(Vec3 pos, float u, float v) {
	}

	private record TextureHit(boolean hit, double t, int color) {
		private static final TextureHit NONE = new TextureHit(false, 0.0D, 0);
	}
}
