package com.prismlab;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;

/**
 * Server-side commands used by the multiplayer tier: a client cannot reach into a dedicated
 * server's world, so it asks the server to place a block and to report what the server holds.
 * The gap between {@code labcharge} and the client's own value is exactly what a player sees.
 */
public final class PrismLabCommands {
	private PrismLabCommands() {
	}

	public static void initialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
			dispatcher.register(Commands.literal("labplace")
					.requires(source -> source.getEntity() instanceof ServerPlayer)
					.then(Commands.argument("id", StringArgumentType.greedyString())
							.executes(context -> {
								ServerPlayer player = context.getSource().getPlayerOrException();
								String id = StringArgumentType.getString(context, "id");
								Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
								if (block == null) {
									context.getSource().sendFailure(Component.literal("unknown block " + id));
									return 0;
								}
								ServerLevel level = player.level();
								BlockPos pos = player.blockPosition().offset(1, 2, 1);
								level.setBlockAndUpdate(pos, block.defaultBlockState());
								context.getSource().sendSuccess(() -> Component.literal("placed " + id + " at " + pos.toShortString()), false);
								return 1;
							})));
			dispatcher.register(Commands.literal("labcharge")
					.requires(source -> source.getEntity() instanceof ServerPlayer)
					.executes(context -> {
						ServerPlayer player = context.getSource().getPlayerOrException();
						BlockPos pos = player.blockPosition().offset(1, 2, 1);
						var entity = player.level().getBlockEntity(pos);
						String charge = entity instanceof com.prismlab.block.entity.PrismNodeEntity node
								? String.valueOf(node.charge()) : "none";
						context.getSource().sendSuccess(() -> Component.literal("server charge=" + charge + " at " + pos.toShortString()), false);
						return 1;
					}));
		});
	}
}
