package com.tico.mineagent.client.render;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.shaders.UniformType;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.tico.mineagent.MineAgent;
import com.tico.mineagent.client.capture.ClientGpuCaptureContext;
import com.tico.mineagent.client.state.ClientAgentEditBoundsState;
import com.tico.mineagent.client.state.ClientSandboxState;

public final class SandboxBoundaryRenderer {
	private static final int GRID_STEP = 8;
	private static final Vector4f COLOR_MODULATOR = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
	private static final RenderPipeline THROUGH_WALL_LINES = RenderPipeline.builder()
			.withLocation(ResourceLocation.fromNamespaceAndPath(MineAgent.MOD_ID, "pipeline/sandbox_lines_through_walls"))
			.withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
			.withUniform("Projection", UniformType.UNIFORM_BUFFER)
			.withUniform("Fog", UniformType.UNIFORM_BUFFER)
			.withUniform("Globals", UniformType.UNIFORM_BUFFER)
			.withVertexShader("core/rendertype_lines")
			.withFragmentShader("core/rendertype_lines")
			.withBlend(BlendFunction.TRANSLUCENT)
			.withCull(false)
			.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
			.withDepthWrite(false)
			.withVertexFormat(DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES)
			.build();
	private static final ByteBufferBuilder THROUGH_WALL_ALLOCATOR = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
	private static MappableRingBuffer throughWallVertexBuffer;

	private SandboxBoundaryRenderer() {
	}

	public static void register() {
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> {
			if (ClientGpuCaptureContext.active()) {
				return;
			}
			boolean showSandbox = ClientSandboxState.complete();
			boolean showAgentEditBounds = ClientAgentEditBoundsState.visible();
			if (!showSandbox && !showAgentEditBounds) {
				return;
			}

			Minecraft client = Minecraft.getInstance();
			if (client.gameRenderer == null) {
				return;
			}

			Vec3 camera = client.gameRenderer.getMainCamera().getPosition();
			AABB sandboxBox = showSandbox ? ClientSandboxState.bounds() : null;
			List<ClientAgentEditBoundsState.EditBounds> agentEditBounds = showAgentEditBounds ? ClientAgentEditBoundsState.entries() : List.of();
			renderThroughWalls(client, context, sandboxBox, agentEditBounds, camera);
			renderVisible(context, sandboxBox, agentEditBounds, camera);
		});
	}

	private static void renderVisible(WorldRenderContext context, AABB sandboxBox, List<ClientAgentEditBoundsState.EditBounds> agentEditBounds, Vec3 camera) {
		PoseStack.Pose pose = context.matrices().last();
		VertexConsumer lines = context.consumers().getBuffer(RenderType.lines());
		if (sandboxBox != null) {
			drawGrid(lines, pose, sandboxBox, camera, 26, 190, 205, 115);
			drawFrame(lines, pose, sandboxBox.inflate(0.003), camera, 26, 242, 255, 255);
			drawFrame(lines, pose, sandboxBox.inflate(0.018), camera, 26, 242, 255, 230);
			drawFrame(lines, pose, sandboxBox.inflate(0.032), camera, 26, 242, 255, 200);
		}
		for (ClientAgentEditBoundsState.EditBounds bounds : agentEditBounds) {
			AABB agentEditBox = bounds.bounds();
			drawFrame(lines, pose, agentEditBox.inflate(0.009), camera, 255, 220, 32, 255);
			drawFrame(lines, pose, agentEditBox.inflate(0.024), camera, 255, 220, 32, 230);
			drawFrame(lines, pose, agentEditBox.inflate(0.039), camera, 255, 220, 32, 200);
		}
	}

	private static void renderThroughWalls(Minecraft client, WorldRenderContext context, AABB sandboxBox, List<ClientAgentEditBoundsState.EditBounds> agentEditBounds, Vec3 camera) {
		BufferBuilder buffer = new BufferBuilder(THROUGH_WALL_ALLOCATOR, THROUGH_WALL_LINES.getVertexFormatMode(), THROUGH_WALL_LINES.getVertexFormat());
		PoseStack.Pose pose = context.matrices().last();
		if (sandboxBox != null) {
			drawGrid(buffer, pose, sandboxBox, camera, 30, 178, 196, 38);
			drawFrame(buffer, pose, sandboxBox.inflate(0.006), camera, 26, 242, 255, 78);
			drawFrame(buffer, pose, sandboxBox.inflate(0.034), camera, 26, 242, 255, 42);
		}
		for (ClientAgentEditBoundsState.EditBounds bounds : agentEditBounds) {
			AABB agentEditBox = bounds.bounds();
			drawFrame(buffer, pose, agentEditBox.inflate(0.012), camera, 255, 205, 32, 86);
			drawFrame(buffer, pose, agentEditBox.inflate(0.040), camera, 255, 205, 32, 48);
		}

		MeshData mesh = buffer.buildOrThrow();
		try {
			drawImmediate(client, THROUGH_WALL_LINES, mesh);
		} finally {
			mesh.close();
		}
	}

	private static void drawImmediate(Minecraft client, RenderPipeline pipeline, MeshData mesh) {
		MeshData.DrawState drawState = mesh.drawState();
		VertexFormat format = drawState.format();
		GpuBuffer vertices = uploadThroughWallVertices(drawState, format, mesh);
		GpuBuffer indices;
		VertexFormat.IndexType indexType;

		if (pipeline.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
			indices = format.uploadImmediateIndexBuffer(mesh.indexBuffer());
			indexType = drawState.indexType();
		} else {
			RenderSystem.AutoStorageIndexBuffer sequentialBuffer = RenderSystem.getSequentialBuffer(pipeline.getVertexFormatMode());
			indices = sequentialBuffer.getBuffer(drawState.indexCount());
			indexType = sequentialBuffer.type();
		}

		GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
				.writeTransform(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, new Vector3f(), RenderSystem.getTextureMatrix(), 1.0F);
		try (RenderPass renderPass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(() -> MineAgent.MOD_ID + " sandbox through-wall lines", client.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(), client.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
			renderPass.setPipeline(pipeline);
			RenderSystem.bindDefaultUniforms(renderPass);
			renderPass.setUniform("DynamicTransforms", dynamicTransforms);
			renderPass.setVertexBuffer(0, vertices);
			renderPass.setIndexBuffer(indices, indexType);
			renderPass.drawIndexed(0, 0, drawState.indexCount(), 1);
		}

		throughWallVertexBuffer.rotate();
	}

	private static GpuBuffer uploadThroughWallVertices(MeshData.DrawState drawState, VertexFormat format, MeshData mesh) {
		int vertexBufferSize = drawState.vertexCount() * format.getVertexSize();
		if (throughWallVertexBuffer == null || throughWallVertexBuffer.size() < vertexBufferSize) {
			if (throughWallVertexBuffer != null) {
				throughWallVertexBuffer.close();
			}

			throughWallVertexBuffer = new MappableRingBuffer(() -> MineAgent.MOD_ID + " sandbox through-wall lines", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, vertexBufferSize);
		}

		CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
		try (GpuBuffer.MappedView mappedView = commandEncoder.mapBuffer(throughWallVertexBuffer.currentBuffer().slice(0, mesh.vertexBuffer().remaining()), false, true)) {
			MemoryUtil.memCopy(mesh.vertexBuffer(), mappedView.data());
		}

		return throughWallVertexBuffer.currentBuffer();
	}

	private static void drawFrame(VertexConsumer lines, PoseStack.Pose pose, AABB box, Vec3 camera, int red, int green, int blue, int alpha) {
		double x1 = box.minX;
		double y1 = box.minY;
		double z1 = box.minZ;
		double x2 = box.maxX;
		double y2 = box.maxY;
		double z2 = box.maxZ;

		line(lines, pose, x1, y1, z1, x2, y1, z1, camera, red, green, blue, alpha);
		line(lines, pose, x1, y1, z2, x2, y1, z2, camera, red, green, blue, alpha);
		line(lines, pose, x1, y2, z1, x2, y2, z1, camera, red, green, blue, alpha);
		line(lines, pose, x1, y2, z2, x2, y2, z2, camera, red, green, blue, alpha);

		line(lines, pose, x1, y1, z1, x1, y1, z2, camera, red, green, blue, alpha);
		line(lines, pose, x2, y1, z1, x2, y1, z2, camera, red, green, blue, alpha);
		line(lines, pose, x1, y2, z1, x1, y2, z2, camera, red, green, blue, alpha);
		line(lines, pose, x2, y2, z1, x2, y2, z2, camera, red, green, blue, alpha);

		line(lines, pose, x1, y1, z1, x1, y2, z1, camera, red, green, blue, alpha);
		line(lines, pose, x2, y1, z1, x2, y2, z1, camera, red, green, blue, alpha);
		line(lines, pose, x1, y1, z2, x1, y2, z2, camera, red, green, blue, alpha);
		line(lines, pose, x2, y1, z2, x2, y2, z2, camera, red, green, blue, alpha);
	}

	private static void drawGrid(VertexConsumer lines, PoseStack.Pose pose, AABB box, Vec3 camera, int red, int green, int blue, int alpha) {
		for (double x = firstInteriorGridLine(box.minX); x < box.maxX - 0.001; x += GRID_STEP) {
			line(lines, pose, x, box.minY, box.minZ, x, box.minY, box.maxZ, camera, red, green, blue, alpha);
			line(lines, pose, x, box.maxY, box.minZ, x, box.maxY, box.maxZ, camera, red, green, blue, alpha);
			line(lines, pose, x, box.minY, box.minZ, x, box.maxY, box.minZ, camera, red, green, blue, alpha);
			line(lines, pose, x, box.minY, box.maxZ, x, box.maxY, box.maxZ, camera, red, green, blue, alpha);
		}

		for (double z = firstInteriorGridLine(box.minZ); z < box.maxZ - 0.001; z += GRID_STEP) {
			line(lines, pose, box.minX, box.minY, z, box.maxX, box.minY, z, camera, red, green, blue, alpha);
			line(lines, pose, box.minX, box.maxY, z, box.maxX, box.maxY, z, camera, red, green, blue, alpha);
			line(lines, pose, box.minX, box.minY, z, box.minX, box.maxY, z, camera, red, green, blue, alpha);
			line(lines, pose, box.maxX, box.minY, z, box.maxX, box.maxY, z, camera, red, green, blue, alpha);
		}

		for (double y = firstInteriorGridLine(box.minY); y < box.maxY - 0.001; y += GRID_STEP) {
			line(lines, pose, box.minX, y, box.minZ, box.maxX, y, box.minZ, camera, red, green, blue, alpha);
			line(lines, pose, box.minX, y, box.maxZ, box.maxX, y, box.maxZ, camera, red, green, blue, alpha);
			line(lines, pose, box.minX, y, box.minZ, box.minX, y, box.maxZ, camera, red, green, blue, alpha);
			line(lines, pose, box.maxX, y, box.minZ, box.maxX, y, box.maxZ, camera, red, green, blue, alpha);
		}
	}

	private static double firstInteriorGridLine(double min) {
		double line = Math.floor(min / GRID_STEP) * GRID_STEP;
		while (line <= min + 0.001) {
			line += GRID_STEP;
		}

		return line;
	}

	private static void line(VertexConsumer consumer, PoseStack.Pose pose, double x1, double y1, double z1, double x2, double y2, double z2, Vec3 camera, int red, int green, int blue, int alpha) {
		float nx = (float) (x2 - x1);
		float ny = (float) (y2 - y1);
		float nz = (float) (z2 - z1);
		float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (length <= 0.0001F) {
			return;
		}

		nx /= length;
		ny /= length;
		nz /= length;
		consumer.addVertex(pose, (float) (x1 - camera.x), (float) (y1 - camera.y), (float) (z1 - camera.z)).setColor(red, green, blue, alpha).setNormal(pose, nx, ny, nz);
		consumer.addVertex(pose, (float) (x2 - camera.x), (float) (y2 - camera.y), (float) (z2 - camera.z)).setColor(red, green, blue, alpha).setNormal(pose, nx, ny, nz);
	}
}
