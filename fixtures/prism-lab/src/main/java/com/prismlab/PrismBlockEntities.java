package com.prismlab;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;

import com.prismlab.block.entity.PrismNodeEntity;

public final class PrismBlockEntities {
	public static final BlockEntityType<PrismNodeEntity> PRISM_NODE = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,
			PrismLab.id("prism_node"),
			FabricBlockEntityTypeBuilder.create(PrismNodeEntity::new, PrismBlocks.PRISM_NODE).build());

	private PrismBlockEntities() {
	}

	public static void initialize() {
	}
}
