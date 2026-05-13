package com.tico.mineagent.client.raycast;

import com.mojang.blaze3d.platform.NativeImage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.Vec3;

import com.tico.mineagent.client.state.ClientSandboxState;
import com.tico.mineagent.client.capture.ClientCaptureLog;
import com.tico.mineagent.MineAgent;
import com.tico.mineagent.raycast.RaycastMode;

public final class ClientRaycastCapture {
	private static final AtomicInteger NEXT_TEXTURE_ID = new AtomicInteger();
	private static final double MAX_DISTANCE = 256.0D;
	private static final Vec3 FORWARD = new Vec3(1.0D, 0.0D, 0.0D);
	private static final int TILE_SIZE = 32;
	private static final int AIR_COLOR = 0xFF05070B;
	private static final int AIR_DEPTH_COLOR = 0xFF020408;
	private static final int AIR_BLOCK_ID_COLOR = 0xFF07111D;
	private static final int AIR_POSITION_COLOR = 0xFF030A12;
	private static final int OUT_OF_SANDBOX_COLOR = 0xFF2E183C;
	private static final int OUT_OF_SANDBOX_DEPTH_COLOR = 0xFF24122E;
	private static final int OUT_OF_SANDBOX_BLOCK_ID_COLOR = 0xFF7E3FA1;
	private static final int OUT_OF_SANDBOX_POSITION_COLOR = 0xFF4C245F;
	private static final byte PIXEL_AIR = 0;
	private static final byte PIXEL_HIT = 1;
	private static final byte PIXEL_OUT_OF_SANDBOX = 2;
	private static final ExecutorService RAYCAST_EXECUTOR = Executors.newFixedThreadPool(
			Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1)),
			runnable -> {
				Thread thread = new Thread(runnable, "MineAgent Raycast Worker");
				thread.setDaemon(true);
				return thread;
			});

	private ClientRaycastCapture() {
	}

	public static RaycastImageSet capture(int requestedSize, double requestedFovDegrees) {
		return capture(requestedSize, requestedFovDegrees, RaycastMode.SANDBOX);
	}

	public static RaycastImageSet capture(int requestedSize, double requestedFovDegrees, RaycastMode requestedMode) {
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = client.level;
		if (level == null) {
			throw new IllegalStateException("Cannot raycast without a loaded client world.");
		}

		int size = clampSize(requestedSize);
		double fovDegrees = Mth.clamp(requestedFovDegrees, 1.0D, 170.0D);
		RaycastMode mode = requestedMode == null ? RaycastMode.SANDBOX : requestedMode;
		AABB sandboxBounds = null;
		if (mode == RaycastMode.SANDBOX) {
			if (!ClientSandboxState.complete()) {
				throw new IllegalStateException("Sandbox raycast requires a complete MineAgent sandbox. Use //mineagent tool or switch raycast mode to free.");
			}
			sandboxBounds = ClientSandboxState.bounds();
		}

		Camera camera = client.gameRenderer.getMainCamera();
		Vec3 origin = camera.getPosition();
		Map<Long, LevelChunk> chunkCache = new ConcurrentHashMap<>();
		Map<Long, Boolean> emptySectionCache = new ConcurrentHashMap<>();
		if (sandboxBounds != null) {
			new ClientRaycastBlockIterator(level, chunkCache, emptySectionCache).preloadChunks(sandboxBounds);
		}

		int pixels = size * size;
		byte[] status = new byte[pixels];
		int[] color = new int[pixels];
		int[] blockId = new int[pixels];
		int[] xPos = new int[pixels];
		int[] yPos = new int[pixels];
		int[] zPos = new int[pixels];
		double[] depth = new double[pixels];
		Bounds bounds = new Bounds();
		AtomicInteger hitPixels = new AtomicInteger();
		AtomicInteger airPixels = new AtomicInteger();
		AtomicInteger outOfSandboxPixels = new AtomicInteger();

		long started = System.nanoTime();
		List<CompletableFuture<Void>> futures = new ArrayList<>();
		for (int tileY = 0; tileY < size; tileY += TILE_SIZE) {
			for (int tileX = 0; tileX < size; tileX += TILE_SIZE) {
				int startX = tileX;
				int startY = tileY;
				int endX = Math.min(size, startX + TILE_SIZE);
				int endY = Math.min(size, startY + TILE_SIZE);
				AABB tileSandboxBounds = sandboxBounds;
				futures.add(CompletableFuture.runAsync(() -> {
					ClientRaycastBlockIterator iterator = new ClientRaycastBlockIterator(level, chunkCache, emptySectionCache);
					for (int y = startY; y < endY; y++) {
						for (int x = startX; x < endX; x++) {
							Vec3 direction = rayAt(camera.getYRot(), camera.getXRot(), fovDegrees, size, size, x, y).normalize();
							HitSample sample = trace(client, level, iterator, origin, direction, mode, tileSandboxBounds);
							int index = y * size + x;
							status[index] = sample.status();
							if (sample.hit()) {
								color[index] = sample.color();
								blockId[index] = sample.blockIdColor();
								xPos[index] = sample.x();
								yPos[index] = sample.y();
								zPos[index] = sample.z();
								depth[index] = sample.depth();
								synchronized (bounds) {
									bounds.include(sample.x(), sample.y(), sample.z());
								}
								hitPixels.incrementAndGet();
							} else if (sample.status() == PIXEL_OUT_OF_SANDBOX) {
								outOfSandboxPixels.incrementAndGet();
							} else {
								airPixels.incrementAndGet();
							}
						}
					}
				}, RAYCAST_EXECUTOR));
			}
		}
		CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

		NativeImage colorImage = new NativeImage(NativeImage.Format.RGBA, size, size, false);
		NativeImage depthImage = new NativeImage(NativeImage.Format.RGBA, size, size, false);
		NativeImage blockIdImage = new NativeImage(NativeImage.Format.RGBA, size, size, false);
		NativeImage positionImage = new NativeImage(NativeImage.Format.RGBA, size, size, false);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int index = y * size + x;
				if (status[index] == PIXEL_HIT) {
					colorImage.setPixelABGR(x, y, argbToAbgr(color[index]));
					int gray = Mth.clamp((int) Math.round(255.0D * (1.0D - Math.min(depth[index], MAX_DISTANCE) / MAX_DISTANCE)), 0, 255);
					depthImage.setPixelABGR(x, y, argbToAbgr(opaque(rgb(gray, gray, gray))));
					blockIdImage.setPixelABGR(x, y, argbToAbgr(blockId[index]));
					positionImage.setPixelABGR(x, y, argbToAbgr(opaque(positionColor(bounds, xPos[index], yPos[index], zPos[index]))));
				} else if (status[index] == PIXEL_OUT_OF_SANDBOX) {
					colorImage.setPixelABGR(x, y, argbToAbgr(OUT_OF_SANDBOX_COLOR));
					depthImage.setPixelABGR(x, y, argbToAbgr(OUT_OF_SANDBOX_DEPTH_COLOR));
					blockIdImage.setPixelABGR(x, y, argbToAbgr(OUT_OF_SANDBOX_BLOCK_ID_COLOR));
					positionImage.setPixelABGR(x, y, argbToAbgr(OUT_OF_SANDBOX_POSITION_COLOR));
				} else {
					colorImage.setPixelABGR(x, y, argbToAbgr(AIR_COLOR));
					depthImage.setPixelABGR(x, y, argbToAbgr(AIR_DEPTH_COLOR));
					blockIdImage.setPixelABGR(x, y, argbToAbgr(AIR_BLOCK_ID_COLOR));
					positionImage.setPixelABGR(x, y, argbToAbgr(AIR_POSITION_COLOR));
				}
			}
		}

		long durationMillis = Math.max(1L, Math.round((System.nanoTime() - started) / 1_000_000.0D));
		List<String> localFiles = List.of(
						ClientCaptureLog.save("raycast_" + mode.id(), "color", colorImage),
						ClientCaptureLog.save("raycast_" + mode.id(), "depth", depthImage),
						ClientCaptureLog.save("raycast_" + mode.id(), "block_id", blockIdImage),
						ClientCaptureLog.save("raycast_" + mode.id(), "position", positionImage))
				.stream()
				.filter(path -> !path.isBlank())
				.toList();
		int textureId = NEXT_TEXTURE_ID.incrementAndGet();
		ResourceLocation colorTexture = register(client, "raycast/color_" + textureId, colorImage);
		ResourceLocation depthTexture = register(client, "raycast/depth_" + textureId, depthImage);
		ResourceLocation blockIdTexture = register(client, "raycast/block_id_" + textureId, blockIdImage);
		ResourceLocation positionTexture = register(client, "raycast/position_" + textureId, positionImage);
		return new RaycastImageSet(
				size,
				mode.id(),
				fovDegrees,
				MAX_DISTANCE,
				durationMillis,
				hitPixels.get(),
				airPixels.get(),
				outOfSandboxPixels.get(),
				origin.x(),
				origin.y(),
				origin.z(),
				colorTexture,
				depthTexture,
				blockIdTexture,
				positionTexture,
				localFiles);
	}

	public static int clampSize(int requestedSize) {
		int[] supported = new int[] { 128, 224, 256, 512, 768 };
		int closest = supported[0];
		int bestDistance = Math.abs(requestedSize - closest);
		for (int size : supported) {
			int distance = Math.abs(requestedSize - size);
			if (distance < bestDistance) {
				closest = size;
				bestDistance = distance;
			}
		}
		return closest;
	}

	private static HitSample trace(Minecraft client, ClientLevel level, ClientRaycastBlockIterator iterator, Vec3 origin, Vec3 direction, RaycastMode mode, AABB sandboxBounds) {
		Segment segment = raySegment(origin, direction, mode, sandboxBounds);
		if (segment == null) {
			return HitSample.outOfSandbox();
		}

		ClipContext context = new ClipContext(segment.from(), segment.to(), ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, CollisionContext.empty());
		var hits = iterator.raycast(context);
		int compositeColor = 0x00FFFFFF;
		boolean hasHitWater = false;
		HitSample nearestSemanticHit = HitSample.MISS;

		for (ClientRaycastBlockIterator.WorldHit worldHit : hits) {
			BlockState state = worldHit.blockState();
			boolean isWater = worldHit.isWaterOrWaterlogged();
			boolean transparentSoFar = ((compositeColor >>> 24) & 0xFF) == 0;
			if (hasHitWater && !transparentSoFar && state.is(Blocks.WATER)) {
				continue;
			}

			HitSample sample = hit(client, level, worldHit, origin, direction);
			if (sample.hit() && !nearestSemanticHit.hit()) {
				nearestSemanticHit = sample;
			}
			if (sample.hit()) {
				compositeColor = RaycastColorMath.alphaComposite(compositeColor, sample.color());
				hasHitWater |= isWater;
				if (((compositeColor >>> 24) & 0xFF) >= 255) {
					return sample.withColor(compositeColor);
				}
			}
		}

		if (!nearestSemanticHit.hit()) {
			return HitSample.air();
		}
		if (((compositeColor >>> 24) & 0xFF) == 0) {
			return HitSample.air();
		}
		return nearestSemanticHit.withColor(compositeColor | 0xFF000000);
	}

	private static Segment raySegment(Vec3 origin, Vec3 direction, RaycastMode mode, AABB sandboxBounds) {
		Vec3 target = direction.scale(MAX_DISTANCE).add(origin);
		if (mode != RaycastMode.SANDBOX || sandboxBounds == null) {
			return new Segment(origin, target);
		}

		double[] clipped = clip(origin, target, sandboxBounds);
		if (clipped == null) {
			return null;
		}

		double start = Mth.clamp(clipped[0] + 1.0E-6D, 0.0D, 1.0D);
		double end = Mth.clamp(clipped[1], 0.0D, 1.0D);
		if (end <= start) {
			return null;
		}
		return new Segment(origin.lerp(target, start), origin.lerp(target, end));
	}

	private static double[] clip(Vec3 from, Vec3 to, AABB bounds) {
		double tMin = 0.0D;
		double tMax = 1.0D;
		double[] start = new double[] { from.x(), from.y(), from.z() };
		double[] delta = new double[] { to.x() - from.x(), to.y() - from.y(), to.z() - from.z() };
		double[] min = new double[] { bounds.minX, bounds.minY, bounds.minZ };
		double[] max = new double[] { bounds.maxX, bounds.maxY, bounds.maxZ };

		for (int i = 0; i < 3; i++) {
			if (Math.abs(delta[i]) < 1.0E-9D) {
				if (start[i] < min[i] || start[i] > max[i]) {
					return null;
				}
			} else {
				double inverse = 1.0D / delta[i];
				double near = (min[i] - start[i]) * inverse;
				double far = (max[i] - start[i]) * inverse;
				if (near > far) {
					double swap = near;
					near = far;
					far = swap;
				}
				tMin = Math.max(tMin, near);
				tMax = Math.min(tMax, far);
				if (tMin > tMax) {
					return null;
				}
			}
		}

		return new double[] { tMin, tMax };
	}

	private static HitSample hit(Minecraft client, ClientLevel level, ClientRaycastBlockIterator.WorldHit worldHit, Vec3 origin, Vec3 direction) {
		BlockPos pos = worldHit.blockPos();
		BlockState state = worldHit.blockState();
		if (state.isAir() && (worldHit.fluidState() == null || worldHit.fluidState().isEmpty())) {
			return HitSample.MISS;
		}

		int mapColor = state.getMapColor(level, pos).col;
		if (worldHit.isWaterOrWaterlogged()) {
			mapColor = level.getBiome(pos).value().getWaterColor();
		}
		if (mapColor == 0) {
			mapColor = rgb(96, 104, 116);
		}
		int shaded = shade(mapColor, state.isSolidRender() ? Direction.UP : Direction.NORTH);
		int alpha = state.isSolidRender() && !worldHit.isWaterOrWaterlogged() ? 0xFF : worldHit.isWaterOrWaterlogged() ? 0x90 : 0xC0;
		BlockHitResult blockHit = worldHit.hitResult();
		Direction hitFace = blockHit == null ? Direction.UP : blockHit.getDirection();
		int fallbackColor = (alpha << 24) | shaded;
		int visualColor = worldHit.isWaterOrWaterlogged()
				? fallbackColor
				: ClientRaycastTextureSampler.sampleBlock(client, level, pos, state, origin, direction, hitFace, fallbackColor);
		visualColor = applySemanticAlpha(visualColor, alpha);
		ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		double depth = blockHit == null ? Math.sqrt(pos.distToCenterSqr(origin)) : blockHit.getLocation().distanceTo(origin);
		return new HitSample(
				PIXEL_HIT,
				true,
				pos.getX(),
				pos.getY(),
				pos.getZ(),
				depth,
				visualColor,
				blockIdColor(blockId.toString()));
	}

	private static int applySemanticAlpha(int color, int alpha) {
		int sampledAlpha = (color >>> 24) & 0xFF;
		int resolvedAlpha = Math.min(sampledAlpha, alpha);
		return (resolvedAlpha << 24) | (color & 0x00FFFFFF);
	}

	private static Vec3 rayAt(float yaw, float pitch, double fovDegrees, int width, int height, int x, int y) {
		double fovYawRad = Math.toRadians(Mth.clamp(fovDegrees, 1.0D, 170.0D) * 0.5D);
		double fovPitchRad = fovYawRad;
		double yawRad = (yaw + 90.0D) * Mth.DEG_TO_RAD;
		double pitchRad = -pitch * Mth.DEG_TO_RAD;

		Vec3 lowerLeft = doubleYawPitchRotation(FORWARD, -fovYawRad, -fovPitchRad, yawRad, pitchRad);
		Vec3 upperLeft = doubleYawPitchRotation(FORWARD, -fovYawRad, fovPitchRad, yawRad, pitchRad);
		Vec3 lowerRight = doubleYawPitchRotation(FORWARD, fovYawRad, -fovPitchRad, yawRad, pitchRad);
		Vec3 upperRight = doubleYawPitchRotation(FORWARD, fovYawRad, fovPitchRad, yawRad, pitchRad);

		double v = height == 1 ? 0.5D : y / (double) (height - 1);
		Vec3 leftEdge = upperLeft.lerp(lowerLeft, v);
		Vec3 rightEdge = upperRight.lerp(lowerRight, v);
		double u = width == 1 ? 0.5D : x / (double) (width - 1);
		return leftEdge.lerp(rightEdge, u);
	}

	private static Vec3 doubleYawPitchRotation(Vec3 base, double firstYaw, double firstPitch, double secondYaw, double secondPitch) {
		return yawPitchRotation(yawPitchRotation(base, firstYaw, firstPitch), secondYaw, secondPitch);
	}

	private static Vec3 yawPitchRotation(Vec3 base, double angleYaw, double anglePitch) {
		double oldX = base.x();
		double oldY = base.y();
		double oldZ = base.z();
		double sinOne = Math.sin(angleYaw);
		double sinTwo = Math.sin(anglePitch);
		double cosOne = Math.cos(angleYaw);
		double cosTwo = Math.cos(anglePitch);
		double newX = oldX * cosOne * cosTwo - oldY * cosOne * sinTwo - oldZ * sinOne;
		double newY = oldX * sinTwo + oldY * cosTwo;
		double newZ = oldX * sinOne * cosTwo - oldY * sinOne * sinTwo + oldZ * cosOne;
		return new Vec3(newX, newY, newZ);
	}

	private static ResourceLocation register(Minecraft client, String path, NativeImage image) {
		ResourceLocation location = ResourceLocation.fromNamespaceAndPath(MineAgent.MOD_ID, path);
		DynamicTexture texture = new DynamicTexture(() -> MineAgent.MOD_ID + "/" + path, image);
		client.getTextureManager().register(location, texture);
		return location;
	}

	private static int blockIdColor(String id) {
		int hash = id.hashCode();
		int r = 72 + ((hash >>> 16) & 0x7F);
		int g = 72 + ((hash >>> 8) & 0x7F);
		int b = 72 + (hash & 0x7F);
		return opaque(rgb(r, g, b));
	}

	private static int positionColor(Bounds bounds, int x, int y, int z) {
		return rgb(
				normalize(x, bounds.minX, bounds.maxX),
				normalize(y, bounds.minY, bounds.maxY),
				normalize(z, bounds.minZ, bounds.maxZ));
	}

	private static int normalize(int value, int min, int max) {
		if (max <= min) {
			return 127;
		}
		return Mth.clamp((int) Math.round(255.0D * (value - min) / (double) (max - min)), 0, 255);
	}

	private static int shade(int rgb, Direction face) {
		double factor = switch (face) {
			case UP -> 1.0D;
			case DOWN -> 0.48D;
			case NORTH, SOUTH -> 0.74D;
			case EAST, WEST -> 0.64D;
		};
		int r = Mth.clamp((int) Math.round(((rgb >> 16) & 0xFF) * factor), 0, 255);
		int g = Mth.clamp((int) Math.round(((rgb >> 8) & 0xFF) * factor), 0, 255);
		int b = Mth.clamp((int) Math.round((rgb & 0xFF) * factor), 0, 255);
		return rgb(r, g, b);
	}

	private static int rgb(int r, int g, int b) {
		return (r << 16) | (g << 8) | b;
	}

	private static int opaque(int rgb) {
		return 0xFF000000 | rgb;
	}

	private static int argbToAbgr(int argb) {
		return (argb & 0xFF000000) | ((argb & 0xFF) << 16) | (argb & 0xFF00) | ((argb >> 16) & 0xFF);
	}

	private static final class Bounds {
		private int minX = Integer.MAX_VALUE;
		private int minY = Integer.MAX_VALUE;
		private int minZ = Integer.MAX_VALUE;
		private int maxX = Integer.MIN_VALUE;
		private int maxY = Integer.MIN_VALUE;
		private int maxZ = Integer.MIN_VALUE;

		private void include(int x, int y, int z) {
			minX = Math.min(minX, x);
			minY = Math.min(minY, y);
			minZ = Math.min(minZ, z);
			maxX = Math.max(maxX, x);
			maxY = Math.max(maxY, y);
			maxZ = Math.max(maxZ, z);
		}
	}

	private record HitSample(
			byte status,
			boolean hit,
			int x,
			int y,
			int z,
			double depth,
			int color,
			int blockIdColor) {
		private static final HitSample MISS = new HitSample(PIXEL_AIR, false, 0, 0, 0, 0.0D, 0, 0);

		private static HitSample air() {
			return MISS;
		}

		private static HitSample outOfSandbox() {
			return new HitSample(PIXEL_OUT_OF_SANDBOX, false, 0, 0, 0, 0.0D, 0, 0);
		}

		private HitSample withColor(int color) {
			return new HitSample(status, hit, x, y, z, depth, color, blockIdColor);
		}
	}

	private record Segment(Vec3 from, Vec3 to) {
	}
}
