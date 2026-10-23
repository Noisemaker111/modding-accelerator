package com.prismlab;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 1.21.1 NeoForge freezes the vanilla registries before mods construct, so
 * {@code Registry.register(BuiltInRegistries.ITEM, …)} throws "Registry is already frozen".
 * Registration goes through a {@link DeferredRegister} bound to the mod event bus instead.
 */
public final class PrismItems {
	public static final int PRISM_SHARD_STACK = PrismMath.STACK_SIZE;

	public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(PrismLab.MOD_ID);

	public static final DeferredItem<Item> PRISM_SHARD = ITEMS.registerSimpleItem(
			"prism_shard",
			new Item.Properties().stacksTo(PRISM_SHARD_STACK));

	private PrismItems() {
	}

	public static void initialize(IEventBus modBus) {
		ITEMS.register(modBus);
		// 1.21.1 fills creative tabs after registration, through a mod-bus event.
		modBus.addListener(PrismItems::addToCreativeTabs);
	}

	public static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
		if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
			event.accept(PRISM_SHARD.get());
		}
	}
}
