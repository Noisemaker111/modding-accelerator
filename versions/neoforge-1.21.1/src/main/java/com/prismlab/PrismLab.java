package com.prismlab;

import net.minecraft.resources.ResourceLocation;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge 1.21.1 entry point. The parent class implements Fabric's
 * {@code ModInitializer}; here {@code @Mod} replaces it and the mod event bus replaces
 * the loader's callbacks.
 */
@Mod(PrismLab.MOD_ID)
public class PrismLab {
	public static final String MOD_ID = "prismlab";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public PrismLab(IEventBus modBus, ModContainer container) {
		PrismItems.initialize(modBus);
		PrismBlocks.initialize(modBus);
		PrismLabCommands.initialize();
		LOGGER.info("Prism Lab demo mod initialized (NeoForge 1.21.1)");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
