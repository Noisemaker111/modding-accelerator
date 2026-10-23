package com.prismlab;

import java.util.function.Function;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;

public final class PrismItems {
	public static final int PRISM_SHARD_STACK = PrismMath.STACK_SIZE;

	public static final ResourceKey<Item> PRISM_SHARD_KEY = ResourceKey.create(Registries.ITEM, PrismLab.id("prism_shard"));

	public static final Item PRISM_SHARD = register(
			PRISM_SHARD_KEY,
			Item::new,
			new Item.Properties().stacksTo(PRISM_SHARD_STACK)
	);

	private PrismItems() {
	}

	public static void initialize() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.INGREDIENTS).register(tab -> tab.accept(PRISM_SHARD));
	}

	public static Item register(ResourceKey<Item> itemKey, Function<Item.Properties, Item> itemFactory, Item.Properties settings) {
		Item item = itemFactory.apply(settings.setId(itemKey));
		Registry.register(BuiltInRegistries.ITEM, itemKey, item);
		return item;
	}
}
