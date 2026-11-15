package com.prismlab;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.prismlab.block.PrismNode;

public final class PrismBlocks {
	public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(PrismLab.MOD_ID);

	public static final DeferredBlock<PrismNode> PRISM_NODE = BLOCKS.registerBlock(
			"prism_node",
			PrismNode::new,
			BlockBehaviour.Properties.of()
					.strength(1.5f)
					.sound(SoundType.AMETHYST)
					.lightLevel(state -> 7));

	public static final DeferredItem<BlockItem> PRISM_NODE_ITEM = PrismItems.ITEMS.registerSimpleBlockItem("prism_node", PRISM_NODE);

	private PrismBlocks() {
	}

	public static void initialize(IEventBus modBus) {
		BLOCKS.register(modBus);
		modBus.addListener(PrismBlocks::addToCreativeTabs);
		PrismBlockEntities.initialize(modBus);
	}

	public static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
		if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
			event.accept(PRISM_NODE_ITEM.get());
		}
	}
}
