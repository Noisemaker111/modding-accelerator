package com.prismlab.gametest;

import java.lang.reflect.Method;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;

import com.prismlab.PrismBlocks;
import com.prismlab.block.entity.PrismNodeEntity;

/**
 * Tier 2 for the block: a real server world ticks the block entity until it charges.
 * This is the shape of test an AE2-style block needs.
 */
public class PrismBlockGameTest implements CustomTestMethodInvoker {
	@GameTest
	public void nodeChargesInALiveWorld(GameTestHelper context) {
		context.setBlock(0, 1, 0, PrismBlocks.PRISM_NODE.defaultBlockState());
		BlockPos pos = context.absolutePos(new BlockPos(0, 1, 0));
		BlockEntity entity = context.getLevel().getBlockEntity(pos);
		if (!(entity instanceof PrismNodeEntity node)) {
			context.fail("no prism node block entity at " + pos);
			return;
		}
		if (context.getLevel().getBlockState(pos).getBlock() != PrismBlocks.PRISM_NODE) {
			context.fail("placed block is not the prism node");
			return;
		}
		for (int i = 0; i < 20; i++) {
			PrismNodeEntity.tick(context.getLevel(), pos, context.getLevel().getBlockState(pos), node);
		}
		if (node.charge() <= 0) {
			context.fail("node did not charge after a second of ticks");
			return;
		}
		context.succeed();
	}

	@Override
	public void invokeTestMethod(GameTestHelper context, Method method) throws ReflectiveOperationException {
		method.invoke(this, context);
	}
}

