package com.prismlab;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Server-side commands used by the multiplayer tier: a client cannot reach into a dedicated
 * server's world, so it asks the server to place a block and to report what the server holds.
 * The gap between {@code labcharge} and the client's own value is exactly what a player sees.
 *
 * <p>Fabric's {@code CommandRegistrationCallback} is replaced by NeoForge's
 * {@code RegisterCommandsEvent} on the game bus.
 */
public final class PrismLabCommands {
	private PrismLabCommands() {
	}

	public static void initialize() {
		NeoForge.EVENT_BUS.addListener(PrismLabCommands::register);
	}

	private static void register(RegisterCommandsEvent event) {
		var dispatcher = event.getDispatcher();
		dispatcher.register(Commands.literal("labplace")
				.requires(source -> source.getEntity() instanceof ServerPlayer)
				.then(Commands.argument("id", StringArgumentType.greedyString())
						.executes(context -> {
							ServerPlayer player = context.getSource().getPlayerOrException();
							String id = StringArgumentType.getString(context, "id");
							Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
							if (block == null) {
								context.getSource().sendFailure(Component.literal("unknown block " + id));
								return 0;
							}
							ServerLevel level = player.serverLevel();
							BlockPos pos = player.blockPosition().offset(1, 2, 1);
							level.setBlockAndUpdate(pos, block.defaultBlockState());
							context.getSource().sendSuccess(() -> Component.literal("placed " + id + " at " + pos.toShortString()), false);
							return 1;
						})));
		dispatcher.register(Commands.literal("labcharge")
				.requires(source -> source.getEntity() instanceof ServerPlayer)
				.executes(context -> {
					CommandSourceStack source = context.getSource();
					ServerPlayer player = source.getPlayerOrException();
					BlockPos pos = player.blockPosition().offset(1, 2, 1);
					var entity = player.serverLevel().getBlockEntity(pos);
					String charge = entity instanceof com.prismlab.block.entity.PrismNodeEntity node
							? String.valueOf(node.charge()) : "none";
					source.sendSuccess(() -> Component.literal("server charge=" + charge + " at " + pos.toShortString()), false);
					return 1;
				}));
	}
}
