package com.tico.mineagent.mask;

import com.mojang.brigadier.StringReader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import com.tico.mineagent.sandbox.SandboxSession;

public final class AgentMaskResolver {
	private AgentMaskResolver() {
	}

	public static AgentMask resolveNamed(SandboxSession session, CommandBuildContext registryAccess, String name) throws Exception {
		return resolveNamed(session, registryAccess, AgentMaskDefinition.normalizeName(name), new HashSet<>());
	}

	public static AgentMask blockMask(CommandBuildContext registryAccess, List<String> blocks) throws Exception {
		List<BlockInput> inputs = parseBlocks(registryAccess, blocks);
		if (inputs.isEmpty()) {
			throw new IllegalArgumentException("Block mask requires at least one block.");
		}
		return (level, pos) -> matchesAny(inputs, level, pos);
	}

	public static AgentMask resolveDefinition(SandboxSession session, CommandBuildContext registryAccess, AgentMaskDefinition definition) throws Exception {
		return resolveDefinition(session, registryAccess, definition, new HashSet<>());
	}

	private static AgentMask resolveNamed(SandboxSession session, CommandBuildContext registryAccess, String name, Set<String> resolving) throws Exception {
		AgentMaskDefinition definition = session.mask(name);
		if (definition == null) {
			throw new IllegalArgumentException("Unknown MineAgent mask: " + name);
		}
		return resolveDefinition(session, registryAccess, definition, resolving);
	}

	private static AgentMask resolveDefinition(SandboxSession session, CommandBuildContext registryAccess, AgentMaskDefinition definition, Set<String> resolving) throws Exception {
		String name = definition.name();
		if (!resolving.add(name)) {
			throw new IllegalArgumentException("MineAgent mask cycle detected at mask: " + name);
		}
		try {
			AgentMask base = switch (definition.mode()) {
				case ALL -> (level, pos) -> true;
				case NONE -> (level, pos) -> false;
				case EXISTING -> (level, pos) -> !level.getBlockState(pos).isAir();
				case AIR -> (level, pos) -> level.getBlockState(pos).isAir();
				case SOLID -> (level, pos) -> level.getBlockState(pos).isSolid();
				case BLOCKS -> blockMask(registryAccess, definition.blocks());
				case NOT_BLOCKS -> {
					AgentMask blockMask = blockMask(registryAccess, definition.blocks());
					yield (level, pos) -> !blockMask.test(level, pos);
				}
				case ANY_OF -> {
					List<AgentMask> masks = resolveChildren(session, registryAccess, definition, resolving);
					yield (level, pos) -> {
						for (AgentMask mask : masks) {
							if (mask.test(level, pos)) {
								return true;
							}
						}
						return false;
					};
				}
				case ALL_OF -> {
					List<AgentMask> masks = resolveChildren(session, registryAccess, definition, resolving);
					yield (level, pos) -> {
						for (AgentMask mask : masks) {
							if (!mask.test(level, pos)) {
								return false;
							}
						}
						return true;
					};
				}
				case NOT -> {
					List<AgentMask> masks = resolveChildren(session, registryAccess, definition, resolving);
					if (masks.size() != 1) {
						throw new IllegalArgumentException("Mode not requires exactly one child mask.");
					}
					AgentMask child = masks.get(0);
					yield (level, pos) -> !child.test(level, pos);
				}
			};
			return definition.invert() ? (level, pos) -> !base.test(level, pos) : base;
		} finally {
			resolving.remove(name);
		}
	}

	private static List<AgentMask> resolveChildren(SandboxSession session, CommandBuildContext registryAccess, AgentMaskDefinition definition, Set<String> resolving) throws Exception {
		if (definition.masks().isEmpty()) {
			throw new IllegalArgumentException("Mode " + definition.mode().id() + " requires at least one child mask.");
		}
		List<AgentMask> masks = new ArrayList<>();
		for (String name : definition.masks()) {
			masks.add(resolveNamed(session, registryAccess, name, resolving));
		}
		return masks;
	}

	private static List<BlockInput> parseBlocks(CommandBuildContext registryAccess, List<String> blocks) throws Exception {
		List<BlockInput> inputs = new ArrayList<>();
		for (String rawBlock : blocks) {
			String block = rawBlock.trim();
			if (!block.isBlank()) {
				inputs.add(BlockStateArgument.block(registryAccess).parse(new StringReader(block)));
			}
		}
		return inputs;
	}

	private static boolean matchesAny(List<BlockInput> inputs, ServerLevel level, BlockPos pos) {
		for (BlockInput input : inputs) {
			if (input.test(level, pos)) {
				return true;
			}
		}
		return false;
	}
}
