package com.prismlab;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;

import com.prismlab.block.PrismNode;

public final class PrismBlocks {
	public static final ResourceKey<Block> PRISM_NODE_KEY = ResourceKey.create(Registries.BLOCK, PrismLab.id("prism_node"));
	public static final ResourceKey<Item> PRISM_NODE_ITEM_KEY = ResourceKey.create(Registries.ITEM, PrismLab.id("prism_node"));

	public static final Block PRISM_NODE = Registry.register(
			BuiltInRegistries.BLOCK,
			PRISM_NODE_KEY,
			new PrismNode(BlockBehaviour.Properties.of()
					.setId(PRISM_NODE_KEY)
					.strength(1.5f)
					.sound(SoundType.AMETHYST)
					.lightLevel(state -> 7)));

	public static final Item PRISM_NODE_ITEM = PrismItems.register(
			PRISM_NODE_ITEM_KEY,
			properties -> new BlockItem(PRISM_NODE, properties),
			new Item.Properties().useBlockDescriptionPrefix());

	private PrismBlocks() {
	}

	public static void initialize() {
		ItemGroupEvents.modifyEntriesEvent(PrismItems.tab("building_blocks")).register(entries -> entries.accept(PRISM_NODE_ITEM));
		PrismBlockEntities.initialize();
	}
}
