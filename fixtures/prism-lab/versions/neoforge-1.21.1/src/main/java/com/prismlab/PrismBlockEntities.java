package com.prismlab;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.prismlab.block.entity.PrismNodeEntity;

public final class PrismBlockEntities {
	// 1.21.1 keeps the vanilla builder; Fabric's builder and direct Registry.register are gone.
	public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
			DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, PrismLab.MOD_ID);

	public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PrismNodeEntity>> PRISM_NODE =
			BLOCK_ENTITIES.register("prism_node",
					() -> BlockEntityType.Builder.of(PrismNodeEntity::new, PrismBlocks.PRISM_NODE.get()).build(null));

	private PrismBlockEntities() {
	}

	public static void initialize(IEventBus modBus) {
		BLOCK_ENTITIES.register(modBus);
	}
}
