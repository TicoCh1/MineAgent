package com.tico.mineagent.client.capture;

import org.joml.Matrix4f;

import com.mojang.blaze3d.pipeline.RenderTarget;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;

import com.tico.mineagent.client.mixin.CameraAccessor;

public final class ClientGpuCaptureContext {
	private static RenderTarget renderTarget;
	private static CameraSpec cameraSpec;

	private ClientGpuCaptureContext() {
	}

	public static void begin(RenderTarget target, CameraSpec spec) {
		renderTarget = target;
		cameraSpec = spec;
	}

	public static void end() {
		renderTarget = null;
		cameraSpec = null;
	}

	public static boolean active() {
		return renderTarget != null && cameraSpec != null;
	}

	public static RenderTarget renderTarget() {
		return renderTarget;
	}

	public static float fovDegrees() {
		return cameraSpec == null ? 70.0F : cameraSpec.fovDegrees();
	}

	public static void apply(Camera camera) {
		CameraSpec spec = cameraSpec;
		if (spec == null) {
			return;
		}
		CameraAccessor accessor = (CameraAccessor) camera;
		accessor.mineagent$setPosition(spec.position());
		accessor.mineagent$setRotation(spec.yaw(), spec.pitch());
	}

	public static Matrix4f projection(GameRenderer renderer) {
		CameraSpec spec = cameraSpec;
		RenderTarget target = renderTarget;
		if (spec == null || target == null) {
			return renderer.getProjectionMatrix(70.0F);
		}
		float aspect = target.height <= 0 ? 1.0F : target.width / (float) target.height;
		if (spec.projection() == ProjectionMode.ORTHOGRAPHIC) {
			float halfHeight = Math.max(1.0F, spec.orthographicHalfHeight());
			float halfWidth = halfHeight * aspect;
			float depth = Math.max(renderer.getDepthFar(), spec.clipDepth());
			return new Matrix4f().setOrtho(-halfWidth, halfWidth, -halfHeight, halfHeight, -depth, depth);
		}
		return new Matrix4f().perspective((float) Math.toRadians(spec.fovDegrees()), aspect, 0.05F, renderer.getDepthFar());
	}

	public record CameraSpec(
			Vec3 position,
			float yaw,
			float pitch,
			float fovDegrees,
			ProjectionMode projection,
			float orthographicHalfHeight,
			float clipDepth) {
		public CameraSpec(Vec3 position, float yaw, float pitch, float fovDegrees) {
			this(position, yaw, pitch, fovDegrees, ProjectionMode.PERSPECTIVE, 1.0F, 1024.0F);
		}
	}

	public enum ProjectionMode {
		PERSPECTIVE,
		ORTHOGRAPHIC
	}
}
