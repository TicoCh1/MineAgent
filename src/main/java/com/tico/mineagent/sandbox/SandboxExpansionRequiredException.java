package com.tico.mineagent.sandbox;

import java.util.List;

import net.minecraft.core.BlockPos;

public final class SandboxExpansionRequiredException extends RuntimeException {
	private final String operation;
	private final BlockPos currentMin;
	private final BlockPos currentMax;
	private final BlockPos requestedMin;
	private final BlockPos requestedMax;
	private final long addedVolume;
	private final List<BlockPos> blockingPositions;
	private final boolean autoScanSkipped;

	public SandboxExpansionRequiredException(
			String operation,
			BlockPos currentMin,
			BlockPos currentMax,
			BlockPos requestedMin,
			BlockPos requestedMax,
			long addedVolume,
			List<BlockPos> blockingPositions,
			boolean autoScanSkipped) {
		super(message(operation, requestedMin, requestedMax, addedVolume, blockingPositions, autoScanSkipped));
		this.operation = operation;
		this.currentMin = currentMin;
		this.currentMax = currentMax;
		this.requestedMin = requestedMin;
		this.requestedMax = requestedMax;
		this.addedVolume = addedVolume;
		this.blockingPositions = List.copyOf(blockingPositions);
		this.autoScanSkipped = autoScanSkipped;
	}

	public String operation() {
		return operation;
	}

	public BlockPos currentMin() {
		return currentMin;
	}

	public BlockPos currentMax() {
		return currentMax;
	}

	public BlockPos requestedMin() {
		return requestedMin;
	}

	public BlockPos requestedMax() {
		return requestedMax;
	}

	public long addedVolume() {
		return addedVolume;
	}

	public List<BlockPos> blockingPositions() {
		return blockingPositions;
	}

	public boolean autoScanSkipped() {
		return autoScanSkipped;
	}

	private static String message(String operation, BlockPos requestedMin, BlockPos requestedMax, long addedVolume, List<BlockPos> blockingPositions, boolean autoScanSkipped) {
		String suffix = blockingPositions.isEmpty()
				? ""
				: " Auto-expand found " + blockingPositions.size() + " sampled non-air block(s).";
		if (autoScanSkipped) {
			suffix += " Auto air-scan was skipped because the added area is too large.";
		}
		return "Sandbox expansion approval required for " + operation + " to "
				+ requestedMin.getX() + "," + requestedMin.getY() + "," + requestedMin.getZ()
				+ " -> "
				+ requestedMax.getX() + "," + requestedMax.getY() + "," + requestedMax.getZ()
				+ " (adds " + addedVolume + " block position(s))." + suffix;
	}
}
