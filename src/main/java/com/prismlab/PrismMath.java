package com.prismlab;

/**
 * Pure logic, no Minecraft classes. This is the kind of code the one-second lane can
 * check without ever starting a game or bootstrapping a registry.
 */
public final class PrismMath {
	/** Shards per stack. Kept here so pure tests never touch a Minecraft class. */
	public static final int STACK_SIZE = 16;

	private PrismMath() {
	}

	/** Light a stack of prism shards emits: 0 shards = 0, then +3 per shard, capped at 15. */
	public static int lightLevel(int shards) {
		if (shards <= 0) {
			return 0;
		}
		return Math.min(15, shards * 3);
	}

	/** How many shards fit into full stacks. */
	public static int fullStacks(int shards) {
		if (shards <= 0) {
			return 0;
		}
		return shards / STACK_SIZE;
	}

	/** Remaining shards after the given number of stacks is pulled out. */
	public static int remainder(int shards, int stacks) {
		int used = Math.max(0, stacks) * STACK_SIZE;
		return Math.max(0, shards - used);
	}
}
