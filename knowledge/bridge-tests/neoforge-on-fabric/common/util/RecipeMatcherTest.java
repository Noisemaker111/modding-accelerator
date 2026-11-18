package ${package}.common.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** Each input takes a different test, moving an earlier input to another test when that frees one; none when impossible. */
class RecipeMatcherTest {

    @Test
    void inputsTakeDifferentTests() {
        Predicate<String> anyFruit = s -> s.equals("apple") || s.equals("pear");
        Predicate<String> apple = "apple"::equals;
        assertArrayEquals(new int[] { 0, 1 }, RecipeMatcher.findMatches(List.of("pear", "apple"), List.of(anyFruit, apple)));
        // apple first takes anyFruit; pear needs it, so apple moves to the apple test.
        assertArrayEquals(new int[] { 1, 0 }, RecipeMatcher.findMatches(List.of("apple", "pear"), List.of(anyFruit, apple)));
        assertNull(RecipeMatcher.findMatches(List.of("pear", "pear"), List.of(anyFruit, apple)));
        assertNull(RecipeMatcher.findMatches(List.of("pear"), List.of(anyFruit, apple)));
    }
}
