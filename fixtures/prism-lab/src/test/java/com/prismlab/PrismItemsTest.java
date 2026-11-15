package com.prismlab;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tier 1b: the real registry from the real Minecraft jar, bootstrapped in-process.
 * No game launch, no window.
 *
 * <p>Bootstrap costs about 13 seconds once per test JVM, so this class carries it and
 * the pure logic tests ride along for free. Worth knowing: since Minecraft 26.x an
 * item's components (stack size and the rest) bind only when a datapack loads, so a
 * bare-bootstrapped JVM cannot build a real {@code ItemStack}. Anything stack-shaped
 * belongs in the game test tier.
 */
class PrismItemsTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void itemIsRegisteredUnderItsId() {
		Assertions.assertSame(PrismItems.PRISM_SHARD, BuiltInRegistries.ITEM.getValue(PrismLab.id("prism_shard")));
	}

	@Test
	void itemIdMatchesTheModNamespace() {
		Assertions.assertEquals("prismlab", BuiltInRegistries.ITEM.getKey(PrismItems.PRISM_SHARD).getNamespace());
		Assertions.assertEquals("prism_shard", BuiltInRegistries.ITEM.getKey(PrismItems.PRISM_SHARD).getPath());
	}

	@Test
	void keyRoundTripsThroughTheRegistry() {
		ResourceKey<?> key = BuiltInRegistries.ITEM.getResourceKey(PrismItems.PRISM_SHARD).orElseThrow();
		Assertions.assertEquals(Registries.ITEM, key.registryKey());
		Assertions.assertEquals(PrismItems.PRISM_SHARD_KEY, key);
	}
}
