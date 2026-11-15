package com.prismlab;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PrismLab implements ModInitializer {
	public static final String MOD_ID = "prismlab";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		PrismItems.initialize();
		PrismBlocks.initialize();
		PrismLabCommands.initialize();
		LOGGER.info("Prism Lab demo mod initialized");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}

