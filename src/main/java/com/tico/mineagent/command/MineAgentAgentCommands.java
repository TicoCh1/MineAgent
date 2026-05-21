package com.tico.mineagent.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import com.tico.mineagent.agent.AgentConfigStore;
import com.tico.mineagent.agent.AgentCredentials;
import com.tico.mineagent.agent.AgentHostMode;
import com.tico.mineagent.agent.AgentHostModes;
import com.tico.mineagent.agent.AgentProviderType;
import com.tico.mineagent.agent.AgentRuntime;
import com.tico.mineagent.mcp.MineAgentMcpServer;
import com.tico.mineagent.web.MineAgentWebHost;

public final class MineAgentAgentCommands {
	private static final DynamicCommandExceptionType UNKNOWN_PROVIDER = new DynamicCommandExceptionType(
			provider -> Component.literal("Unknown MineAgent provider: " + provider + ". Use openai or claude."));
	private static final String[] PROVIDERS = new String[] { "openai", "claude" };

	private MineAgentAgentCommands() {
	}

	public static LiteralArgumentBuilder<CommandSourceStack> configure() {
		return Commands.literal("config")
				.then(Commands.argument("provider", StringArgumentType.word())
						.suggests(MineAgentAgentCommands::suggestProviders)
						.then(Commands.argument("model", StringArgumentType.word())
								.then(Commands.argument("api_key", StringArgumentType.greedyString())
										.executes(MineAgentAgentCommands::configure))));
	}

	public static LiteralArgumentBuilder<CommandSourceStack> agent(CommandBuildContext registryAccess) {
		return Commands.literal("agent")
				.executes(MineAgentAgentCommands::showSetupHint)
				.then(Commands.literal("start")
						.then(Commands.argument("prompt", StringArgumentType.greedyString())
								.executes(context -> start(context, null, registryAccess))))
				.then(Commands.literal("openai")
						.then(Commands.argument("prompt", StringArgumentType.greedyString())
								.executes(context -> start(context, AgentProviderType.OPENAI, registryAccess))))
				.then(Commands.literal("claude")
						.then(Commands.argument("prompt", StringArgumentType.greedyString())
								.executes(context -> start(context, AgentProviderType.CLAUDE, registryAccess))))
				.then(host())
				.then(mcp(registryAccess))
				.then(Commands.literal("status")
						.executes(MineAgentAgentCommands::status))
				.then(Commands.literal("stop")
						.executes(MineAgentAgentCommands::stop));
	}

	public static int showSetupHint(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		player.sendSystemMessage(Component.literal("MineAgent agent bridge is ready."));
		player.sendSystemMessage(Component.literal("1) Select a sandbox: //mineagent tool, then left/right click two blocks."));
		player.sendSystemMessage(Component.literal("2) Configure one in-memory API session: //mineagent config <openai|claude> <model> <api_key>"));
		player.sendSystemMessage(Component.literal("   Environment alternative: OPENAI_API_KEY + MINEAGENT_OPENAI_MODEL, or ANTHROPIC_API_KEY + MINEAGENT_CLAUDE_MODEL."));
		player.sendSystemMessage(Component.literal("   Warning: commands may be visible in server logs. Prefer env vars outside private single-player tests."));
		player.sendSystemMessage(Component.literal("3) Preferred host UI: //mineagent web start, then use the local browser UI."));
		player.sendSystemMessage(Component.literal("   Command-only start: //mineagent agent start <building task>"));
		player.sendSystemMessage(Component.literal("   External host option: //mineagent agent mcp start, then connect Codex/Claude to the printed MCP URL."));
		Optional<AgentCredentials> configured = AgentConfigStore.configured(player);
		configured.ifPresent(credentials -> player.sendSystemMessage(Component.literal("Current in-memory config: " + credentials.safeSummary())));
		return 1;
	}

	private static LiteralArgumentBuilder<CommandSourceStack> host() {
		return Commands.literal("host")
				.executes(MineAgentAgentCommands::hostStatus)
				.then(Commands.literal("internal")
						.executes(context -> setHostMode(context, AgentHostMode.INTERNAL)))
				.then(Commands.literal("external")
						.executes(context -> setHostMode(context, AgentHostMode.EXTERNAL)));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> mcp(CommandBuildContext registryAccess) {
		return Commands.literal("mcp")
				.executes(MineAgentAgentCommands::mcpStatus)
				.then(Commands.literal("start")
						.executes(context -> startMcp(context, registryAccess, MineAgentMcpServer.defaultPort()))
						.then(Commands.argument("port", IntegerArgumentType.integer(1024, 65535))
								.executes(context -> startMcp(context, registryAccess, IntegerArgumentType.getInteger(context, "port")))))
				.then(Commands.literal("stop")
						.executes(MineAgentAgentCommands::stopMcp))
				.then(Commands.literal("status")
						.executes(MineAgentAgentCommands::mcpStatus));
	}

	private static int hostStatus(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		player.sendSystemMessage(Component.literal("MineAgent host mode: " + AgentHostModes.get(player).id()));
		player.sendSystemMessage(Component.literal("MineAgent external MCP: " + MineAgentMcpServer.instance().status()));
		return 1;
	}

	private static int setHostMode(CommandContext<CommandSourceStack> context, AgentHostMode mode) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		AgentHostModes.set(player, mode);
		player.sendSystemMessage(Component.literal("MineAgent host mode set to " + mode.id() + "."));
		if (mode == AgentHostMode.EXTERNAL) {
			player.sendSystemMessage(Component.literal("Start or reuse the external MCP endpoint with //mineagent agent mcp start."));
		}
		return 1;
	}

	private static int startMcp(CommandContext<CommandSourceStack> context, CommandBuildContext registryAccess, int port) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		MineAgentMcpServer.StartResult result = MineAgentMcpServer.instance().start(player, registryAccess, port);
		player.sendSystemMessage(Component.literal(result.message()));
		if (result.ok()) {
			player.sendSystemMessage(Component.literal("Configure an external host as a Streamable HTTP MCP server using URL: " + result.url()));
		}
		return result.ok() ? 1 : 0;
	}

	private static int stopMcp(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		MineAgentMcpServer.instance().stop();
		AgentHostModes.set(player, AgentHostMode.INTERNAL);
		player.sendSystemMessage(Component.literal("MineAgent external MCP server stopped. Host mode set to internal."));
		return 1;
	}

	private static int mcpStatus(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		player.sendSystemMessage(Component.literal("MineAgent host mode: " + AgentHostModes.get(player).id()));
		player.sendSystemMessage(Component.literal("MineAgent external MCP: " + MineAgentMcpServer.instance().status()));
		return 1;
	}

	private static int configure(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		AgentProviderType provider = parseProvider(StringArgumentType.getString(context, "provider"));
		String model = StringArgumentType.getString(context, "model").trim();
		String apiKey = StringArgumentType.getString(context, "api_key").trim();
		if (model.isBlank() || apiKey.isBlank()) {
			player.sendSystemMessage(Component.literal("MineAgent config rejected: model and api_key must both be present."));
			return 0;
		}

		AgentConfigStore.configure(player, new AgentCredentials(provider, model, apiKey));
		player.sendSystemMessage(Component.literal("MineAgent configured for this game session: " + provider.id() + " / " + model + ". API key is stored in memory only."));
		return 1;
	}

	private static int start(CommandContext<CommandSourceStack> context, AgentProviderType provider, CommandBuildContext registryAccess) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		String prompt = StringArgumentType.getString(context, "prompt").trim();
		if (prompt.isBlank()) {
			player.sendSystemMessage(Component.literal("MineAgent agent prompt must not be blank."));
			return 0;
		}

		AgentRuntime.StartResult result = AgentRuntime.instance().start(player, provider, prompt, registryAccess, false);
		if (result.status() == AgentRuntime.StartStatus.MISSING_CONFIG) {
			player.sendSystemMessage(Component.literal(result.message()));
			showSetupHint(context);
			return 0;
		}
		player.sendSystemMessage(Component.literal(result.message()));
		return result.status() == AgentRuntime.StartStatus.STARTED ? 1 : 0;
	}

	private static int status(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		player.sendSystemMessage(Component.literal("MineAgent host mode: " + AgentHostModes.get(player).id()));
		player.sendSystemMessage(Component.literal("MineAgent agent status: " + AgentRuntime.instance().status(player)));
		player.sendSystemMessage(Component.literal("MineAgent Web UI: " + MineAgentWebHost.instance().status()));
		player.sendSystemMessage(Component.literal("MineAgent external MCP: " + MineAgentMcpServer.instance().status()));
		return 1;
	}

	private static int stop(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		AgentRuntime.instance().stop(player);
		player.sendSystemMessage(Component.literal("MineAgent agent stop requested."));
		return 1;
	}

	private static AgentProviderType parseProvider(String raw) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		AgentProviderType provider = AgentProviderType.byId(raw);
		if (provider == null) {
			throw UNKNOWN_PROVIDER.create(raw);
		}
		return provider;
	}

	private static CompletableFuture<Suggestions> suggestProviders(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(PROVIDERS, builder);
	}
}
