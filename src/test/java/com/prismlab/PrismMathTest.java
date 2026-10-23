package com.prismlab;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tier 1a: pure logic, no Minecraft bootstrap. Milliseconds. */
@org.junit.jupiter.api.Tag("pure")
class PrismMathTest {
	@ParameterizedTest
	@CsvSource({
			"0, 0",
			"1, 3",
			"4, 12",
			"5, 15",
			"64, 15",
			"-3, 0"
	})
	void lightLevelIsClamped(int shards, int expected) {
		Assertions.assertEquals(expected, PrismMath.lightLevel(shards));
	}

	@Test
	void stacksUseTheRealStackSize() {
		Assertions.assertEquals(1, PrismMath.fullStacks(17));
		Assertions.assertEquals(4, PrismMath.fullStacks(64));
	}

	@Test
	void remainderNeverGoesNegative() {
		Assertions.assertEquals(1, PrismMath.remainder(17, 1));
		Assertions.assertEquals(0, PrismMath.remainder(5, 9));
	}
}
