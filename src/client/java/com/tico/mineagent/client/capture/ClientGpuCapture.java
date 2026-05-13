package com.tico.mineagent.client.capture;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.tico.mineagent.MineAgent;
import com.tico.mineagent.network.GpuCaptureRequestPayload;

public final class ClientGpuCapture {
	public static final String TYPE_VIRTUAL_CAMERA = "virtual_camera";
	public static final String TYPE_SANDBOX_ISOMETRIC = "sandbox_isometric";
	private static final AtomicInteger NEXT_TEXTURE_ID = new AtomicInteger();
	private static final int MIN_WIDTH = 256;
	private static final int MIN_HEIGHT = 256;
	private static final int MAX_WIDTH = 1920;
	private static final int MAX_HEIGHT = 1080;

	private ClientGpuCapture() {
	}

	public static CompletableFuture<GpuCaptureImageSet> capture(GpuCaptureRequestPayload payload) {
		CompletableFuture<GpuCaptureImageSet> future = new CompletableFuture<>();
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> captureOnClient(client, payload, future));
		return future;
	}

	public static int clampWidth(int width) {
		return Mth.clamp(width <= 0 ? 512 : width, MIN_WIDTH, MAX_WIDTH);
	}

	public static int clampHeight(int height) {
		return Mth.clamp(height <= 0 ? 512 : height, MIN_HEIGHT, MAX_HEIGHT);
	}

	public static double clampFov(double fovDegrees) {
		return Mth.clamp(fovDegrees <= 0.0D ? 60.0D : fovDegrees, 30.0D, 90.0D);
	}

	private static void captureOnClient(Minecraft client, GpuCaptureRequestPayload payload, CompletableFuture<GpuCaptureImageSet> future) {
		if (client.level == null || client.player == null) {
			future.completeExceptionally(new IllegalStateException("Cannot capture a GPU screenshot without a loaded client world."));
			return;
		}

		int width = clampWidth(payload.width());
		int height = clampHeight(payload.height());
		float fov = (float) clampFov(payload.fovDegrees());
		long started = System.nanoTime();
		if (TYPE_SANDBOX_ISOMETRIC.equals(payload.captureType())) {
			captureIsometric(client, payload.sandboxMin(), payload.sandboxMax(), width, height, fov)
					.whenComplete((images, throwable) -> complete(future, payload.captureType(), width, height, fov, started, images, throwable));
			return;
		}

		ClientGpuCaptureContext.CameraSpec spec = new ClientGpuCaptureContext.CameraSpec(
				new Vec3(payload.cameraX(), payload.cameraY(), payload.cameraZ()),
				payload.yawDegrees(),
				payload.pitchDegrees(),
				fov);
		renderOne(client, TYPE_VIRTUAL_CAMERA, "camera", width, height, spec)
				.whenComplete((image, throwable) -> {
					List<GpuCaptureImageSet.Image> images = image == null ? List.of() : List.of(image);
					complete(future, TYPE_VIRTUAL_CAMERA, width, height, fov, started, images, throwable);
				});
	}

	private static CompletableFuture<List<GpuCaptureImageSet.Image>> captureIsometric(Minecraft client, BlockPos rawMin, BlockPos rawMax, int width, int height, float fov) {
		BlockPos min = min(rawMin, rawMax);
		BlockPos max = max(rawMin, rawMax);
		Vec3 center = new Vec3(
				(min.getX() + max.getX() + 1.0D) * 0.5D,
				(min.getY() + max.getY() + 1.0D) * 0.5D,
				(min.getZ() + max.getZ() + 1.0D) * 0.5D);
		double sizeX = max.getX() - min.getX() + 1.0D;
		double sizeY = max.getY() - min.getY() + 1.0D;
		double sizeZ = max.getZ() - min.getZ() + 1.0D;
		double radius = Math.max(1.0D, Math.sqrt(sizeX * sizeX + sizeY * sizeY + sizeZ * sizeZ) * 0.5D);
		double distance = radius + 24.0D;
		int[][] signs = new int[][] {
				{ 1, 1, 1 },
				{ 1, 1, -1 },
				{ 1, -1, 1 },
				{ 1, -1, -1 },
				{ -1, 1, 1 },
				{ -1, 1, -1 },
				{ -1, -1, 1 },
				{ -1, -1, -1 }
		};

		CompletableFuture<List<GpuCaptureImageSet.Image>> chain = CompletableFuture.completedFuture(new ArrayList<>());
		for (int[] sign : signs) {
			String label = label(sign);
			Vec3 direction = new Vec3(sign[0], sign[1], sign[2]).normalize();
			Vec3 cameraPosition = center.add(direction.scale(distance));
			ClientGpuCaptureContext.CameraSpec spec = orthographicLookAt(cameraPosition, center, fov, min, max, width, height);
			chain = chain.thenCompose(images -> renderOne(client, TYPE_SANDBOX_ISOMETRIC, label, width, height, spec)
					.thenApply(image -> {
						images.add(image);
						return images;
					}));
		}
		return chain.thenApply(List::copyOf);
	}

	private static CompletableFuture<GpuCaptureImageSet.Image> renderOne(Minecraft client, String type, String label, int width, int height, ClientGpuCaptureContext.CameraSpec spec) {
		CompletableFuture<GpuCaptureImageSet.Image> future = new CompletableFuture<>();
		RenderTarget target = null;
		try {
			target = new TextureTarget("MineAgent " + type + " " + label, width, height, true);
			RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), 0x00000000, target.getDepthTexture(), 1.0D);
			HitResult oldHitResult = client.hitResult;
			Entity oldCrosshairPickEntity = client.crosshairPickEntity;
			MobEffectInstance oldNightVision = client.player.getEffect(MobEffects.NIGHT_VISION);
			MobEffectInstance oldNightVisionCopy = oldNightVision == null ? null : new MobEffectInstance(oldNightVision);
			client.hitResult = null;
			client.crosshairPickEntity = null;
			applyCaptureNightVision(client);
			ClientGpuCaptureContext.begin(target, spec);
			try {
				client.gameRenderer.renderLevel(client.getDeltaTracker());
			} finally {
				ClientGpuCaptureContext.end();
				client.hitResult = oldHitResult;
				client.crosshairPickEntity = oldCrosshairPickEntity;
				restoreNightVision(client, oldNightVisionCopy);
			}

			RenderTarget completedTarget = target;
			Screenshot.takeScreenshot(completedTarget, image -> client.execute(() -> {
				try {
					String localFile = ClientCaptureLog.save(type, label, image);
					ResourceLocation texture = register(client, type + "/" + label + "_" + NEXT_TEXTURE_ID.incrementAndGet(), image);
					future.complete(new GpuCaptureImageSet.Image(label, texture, width, height, localFile));
				} catch (Exception exception) {
					image.close();
					future.completeExceptionally(exception);
				} finally {
					completedTarget.destroyBuffers();
				}
			}));
		} catch (Exception exception) {
			if (target != null) {
				target.destroyBuffers();
			}
			ClientGpuCaptureContext.end();
			future.completeExceptionally(exception);
		}
		return future;
	}

	private static void applyCaptureNightVision(Minecraft client) {
		if (client.player == null) {
			return;
		}
		client.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 600, 0, true, false, false));
	}

	private static void restoreNightVision(Minecraft client, MobEffectInstance previousNightVision) {
		if (client.player == null) {
			return;
		}
		client.player.removeEffect(MobEffects.NIGHT_VISION);
		if (previousNightVision != null) {
			client.player.addEffect(previousNightVision);
		}
	}

	private static void complete(CompletableFuture<GpuCaptureImageSet> future, String type, int width, int height, float fov, long started, List<GpuCaptureImageSet.Image> images, Throwable throwable) {
		if (throwable != null) {
			future.completeExceptionally(throwable);
			return;
		}
		long durationMillis = Math.max(1L, Math.round((System.nanoTime() - started) / 1_000_000.0D));
		future.complete(new GpuCaptureImageSet(type, width, height, fov, durationMillis, images));
	}

	private static ClientGpuCaptureContext.CameraSpec lookAt(Vec3 cameraPosition, Vec3 target, float fov) {
		Vec3 delta = target.subtract(cameraPosition);
		double horizontal = Math.sqrt(delta.x() * delta.x() + delta.z() * delta.z());
		float yaw = (float) (Math.atan2(delta.z(), delta.x()) * 180.0D / Math.PI - 90.0D);
		float pitch = (float) (-Math.atan2(delta.y(), horizontal) * 180.0D / Math.PI);
		return new ClientGpuCaptureContext.CameraSpec(cameraPosition, yaw, pitch, fov);
	}

	private static ClientGpuCaptureContext.CameraSpec orthographicLookAt(Vec3 cameraPosition, Vec3 target, float fov, BlockPos min, BlockPos max, int width, int height) {
		ClientGpuCaptureContext.CameraSpec perspectivePose = lookAt(cameraPosition, target, fov);
		Vec3 forward = target.subtract(cameraPosition).normalize();
		Vec3 worldUp = new Vec3(0.0D, 1.0D, 0.0D);
		Vec3 right = forward.cross(worldUp);
		if (right.lengthSqr() < 1.0E-8D) {
			right = new Vec3(1.0D, 0.0D, 0.0D);
		} else {
			right = right.normalize();
		}
		Vec3 up = right.cross(forward).normalize();

		double halfWidthNeeded = 1.0D;
		double halfHeightNeeded = 1.0D;
		for (int x = min.getX(); x <= max.getX() + 1; x += Math.max(1, max.getX() + 1 - min.getX())) {
			for (int y = min.getY(); y <= max.getY() + 1; y += Math.max(1, max.getY() + 1 - min.getY())) {
				for (int z = min.getZ(); z <= max.getZ() + 1; z += Math.max(1, max.getZ() + 1 - min.getZ())) {
					Vec3 corner = new Vec3(x, y, z).subtract(target);
					halfWidthNeeded = Math.max(halfWidthNeeded, Math.abs(corner.dot(right)));
					halfHeightNeeded = Math.max(halfHeightNeeded, Math.abs(corner.dot(up)));
				}
			}
		}

		double padding = Math.max(2.0D, Math.max(halfWidthNeeded, halfHeightNeeded) * 0.08D);
		halfWidthNeeded += padding;
		halfHeightNeeded += padding;
		double aspect = height <= 0 ? 1.0D : width / (double) height;
		double halfHeight = Math.max(halfHeightNeeded, halfWidthNeeded / aspect);
		double clipDepth = cameraPosition.distanceTo(target) + Math.sqrt(target.distanceToSqr(new Vec3(min.getX(), min.getY(), min.getZ()))) + Math.sqrt(target.distanceToSqr(new Vec3(max.getX() + 1.0D, max.getY() + 1.0D, max.getZ() + 1.0D))) + 64.0D;
		return new ClientGpuCaptureContext.CameraSpec(
				cameraPosition,
				perspectivePose.yaw(),
				perspectivePose.pitch(),
				fov,
				ClientGpuCaptureContext.ProjectionMode.ORTHOGRAPHIC,
				(float) Math.max(1.0D, halfHeight),
				(float) Math.max(256.0D, clipDepth));
	}

	private static String label(int[] sign) {
		return (sign[0] > 0 ? "+x" : "-x")
				+ (sign[1] > 0 ? "+y" : "-y")
				+ (sign[2] > 0 ? "+z" : "-z");
	}

	private static ResourceLocation register(Minecraft client, String path, NativeImage image) {
		String normalizedPath = path.toLowerCase(Locale.ROOT).replace("+", "p").replace("-", "n");
		ResourceLocation location = ResourceLocation.fromNamespaceAndPath(MineAgent.MOD_ID, "gpu_capture/" + normalizedPath);
		DynamicTexture texture = new DynamicTexture(() -> MineAgent.MOD_ID + "/gpu_capture/" + normalizedPath, image);
		client.getTextureManager().register(location, texture);
		return location;
	}

	private static BlockPos min(BlockPos first, BlockPos second) {
		return new BlockPos(
				Math.min(first.getX(), second.getX()),
				Math.min(first.getY(), second.getY()),
				Math.min(first.getZ(), second.getZ()));
	}

	private static BlockPos max(BlockPos first, BlockPos second) {
		return new BlockPos(
				Math.max(first.getX(), second.getX()),
				Math.max(first.getY(), second.getY()),
				Math.max(first.getZ(), second.getZ()));
	}
}
