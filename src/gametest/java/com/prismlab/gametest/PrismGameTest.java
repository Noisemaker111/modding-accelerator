package com.prismlab.gametest;

import java.lang.reflect.Method;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * Tier 2: a real server world, ticked in-process, no client and no window.
 * Runs with ./gradlew runGametest (and as part of build).
 */
public class PrismGameTest implements CustomTestMethodInvoker {
	@GameTest
	public void modItemExistsInTheLiveServerRegistry(GameTestHelper context) {
		if (BuiltInRegistries.ITEM.getValue(com.prismlab.PrismLab.id("prism_shard")) == null) {
			context.fail("prismlab:prism_shard is missing from the live server registry");
		}
		context.succeed();
	}

	@GameTest
	public void everyRegisteredBlockAndItemHasItsAssets(GameTestHelper context) {
		// The ids the live registry holds and the files this mod ships: a wrong id fails here and
		// the message names the path that is missing.
		java.util.List<String> problems = PrismAssets.problems();
		if (!problems.isEmpty()) {
			context.fail("world tier: " + String.join("; ", problems));
		}
		context.succeed();
	}

	@GameTest
	public void worldTicksAndBlocksChange(GameTestHelper context) {
		context.assertBlockPresent(Blocks.AIR, 0, 0, 0);
		context.setBlock(0, 0, 0, Blocks.GLOWSTONE);
		context.assertBlockPresent(Blocks.GLOWSTONE, 0, 0, 0);
		context.succeed();
	}

	@GameTest
	public void itemStackHasTheRealStackLimitInAWorld(GameTestHelper context) {
		ItemEntity entity = context.spawnItem(com.prismlab.PrismItems.PRISM_SHARD, 1.0f, 1.0f, 1.0f);
		int max = entity.getItem().getMaxStackSize();
		if (max != com.prismlab.PrismItems.PRISM_SHARD_STACK) {
			context.fail("expected max stack " + com.prismlab.PrismItems.PRISM_SHARD_STACK + " but the live stack says " + max);
		}
		context.succeed();
	}

	@Override
	public void invokeTestMethod(GameTestHelper context, Method method) throws ReflectiveOperationException {
		method.invoke(this, context);
	}
}
