package com.tico.mineagent.client.raycast;

/*
 * Adapted from Camera Obscura's AbstractWorldIterator and BlockIterator.
 * Camera Obscura is Copyright (c) 2024 tomalbrc and licensed under LGPL-3.0-or-later.
 *
 * MineAgent keeps this as a small client-side helper instead of depending on the
 * full Camera Obscura renderer, because MineAgent needs extra semantic buffers
 * and currently samples the local client camera.
 */

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;
import java.util.Map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

final class ClientRaycastBlockIterator {
	private final ClientLevel level;
	private final Map<Long, LevelChunk> cachedChunks;
	private final Map<Long, Boolean> emptySections;

	ClientRaycastBlockIterator(ClientLevel level, Map<Long, LevelChunk> cachedChunks, Map<Long, Boolean> emptySections) {
		this.level = level;
		this.cachedChunks = cachedChunks;
		this.emptySections = emptySections;
	}

	void preloadChunks(AABB bounds) {
		int minChunkX = SectionPos.blockToSectionCoord((int) Math.floor(bounds.minX));
		int maxChunkX = SectionPos.blockToSectionCoord((int) Math.floor(bounds.maxX - 1.0D));
		int minChunkZ = SectionPos.blockToSectionCoord((int) Math.floor(bounds.minZ));
		int maxChunkZ = SectionPos.blockToSectionCoord((int) Math.floor(bounds.maxZ - 1.0D));
		int chunkCount = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
		if (chunkCount > 512) {
			return;
		}

		for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
			for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
				getChunkAt(chunkX, chunkZ);
			}
		}
	}

	List<WorldHit> raycast(ClipContext clipContext) {
		List<WorldHit> hits = new ObjectArrayList<>();
		WorldHit hitResult = BlockGetter.traverseBlocks(clipContext.getFrom(), clipContext.getTo(), clipContext, (context, blockPos) -> {
			if (isEmptySection(blockPos)) {
				return null;
			}

			BlockState blockState = cachedBlockState(blockPos);
			FluidState fluidState = cachedFluidState(blockPos);
			FluidState fluidStateAbove = fluidState.isEmpty() ? null : cachedFluidState(blockPos.above());
			BlockHitResult hit = nearestHit(context, blockPos, blockState, fluidState);

			if (!blockState.isSolidRender() || blockState.isAir()) {
				if (!blockState.isAir() || !fluidState.isEmpty()) {
					hits.add(new WorldHit(new BlockPos(blockPos), blockState, fluidState, fluidStateAbove, hit));
				}
				return null;
			}

			return new WorldHit(new BlockPos(blockPos), blockState, fluidState, fluidStateAbove, hit);
		}, context -> null);

		if (hitResult != null) {
			hits.add(hitResult);
		} else {
			BlockState blockState = Blocks.AIR.defaultBlockState();
			hits.add(new WorldHit(BlockPos.containing(clipContext.getTo()), blockState, blockState.getFluidState(), blockState.getFluidState(), null));
		}
		return hits;
	}

	private BlockHitResult nearestHit(ClipContext context, BlockPos blockPos, BlockState blockState, FluidState fluidState) {
		BlockHitResult blockHit = clip(context.getBlockShape(blockState, level, blockPos), context, blockPos);
		BlockHitResult fluidHit = fluidState.isEmpty() ? null : clip(context.getFluidShape(fluidState, level, blockPos), context, blockPos);
		if (blockHit == null) {
			return fluidHit;
		}
		if (fluidHit == null) {
			return blockHit;
		}
		Vec3 from = context.getFrom();
		return blockHit.getLocation().distanceToSqr(from) <= fluidHit.getLocation().distanceToSqr(from) ? blockHit : fluidHit;
	}

	private BlockHitResult clip(VoxelShape shape, ClipContext context, BlockPos blockPos) {
		return shape.isEmpty() ? null : shape.clip(context.getFrom(), context.getTo(), blockPos);
	}

	private boolean isEmptySection(BlockPos blockPos) {
		if (level.isOutsideBuildHeight(blockPos)) {
			return true;
		}

		LevelChunk chunk = getChunkAt(blockPos);
		if (chunk.isEmpty()) {
			return true;
		}

		int sectionIndex = level.getSectionIndex(blockPos.getY());
		LevelChunkSection[] sections = chunk.getSections();
		if (sectionIndex < 0 || sectionIndex >= sections.length) {
			return true;
		}

		long key = sectionKey(ChunkPos.asLong(blockPos), sectionIndex);
		return emptySections.computeIfAbsent(key, ignored -> sections[sectionIndex].hasOnlyAir());
	}

	private FluidState cachedFluidState(BlockPos blockPos) {
		if (level.isOutsideBuildHeight(blockPos)) {
			return Fluids.EMPTY.defaultFluidState();
		}
		return getChunkAt(blockPos).getFluidState(blockPos);
	}

	private BlockState cachedBlockState(BlockPos blockPos) {
		if (level.isOutsideBuildHeight(blockPos)) {
			return Blocks.AIR.defaultBlockState();
		}
		return getChunkAt(blockPos).getBlockState(blockPos);
	}

	private LevelChunk getChunkAt(BlockPos blockPos) {
		return getChunkAt(SectionPos.blockToSectionCoord(blockPos.getX()), SectionPos.blockToSectionCoord(blockPos.getZ()));
	}

	private LevelChunk getChunkAt(int chunkX, int chunkZ) {
		long key = ChunkPos.asLong(chunkX, chunkZ);
		LevelChunk cached = cachedChunks.get(key);
		if (cached != null) {
			return cached;
		}
		return cachedChunks.computeIfAbsent(key, ignored -> level.getChunk(chunkX, chunkZ));
	}

	private long sectionKey(long chunkKey, int sectionIndex) {
		return (chunkKey << 6) ^ (sectionIndex & 0x3FL);
	}

	record WorldHit(BlockPos blockPos, BlockState blockState, FluidState fluidState, FluidState fluidStateAbove, BlockHitResult hitResult) {
		boolean isWaterOrWaterlogged() {
			return fluidState != null && !fluidState.isEmpty() && fluidState.is(FluidTags.WATER);
		}
	}
}
