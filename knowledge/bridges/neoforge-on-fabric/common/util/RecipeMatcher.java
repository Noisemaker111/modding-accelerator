package ${package}.common.util;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * NeoForge's net.neoforged.neoforge.common.util.RecipeMatcher: matches each input to a different test (a shapeless
 * recipe's stacks to its ingredients), returning for each input the index of the test it takes, or null when no such
 * one-to-one matching exists. Found by augmenting paths (each input in turn takes a free test, or one whose holder can
 * move to another), so it finds a matching whenever there is one.
 */
public final class RecipeMatcher {

    private RecipeMatcher() {}

    public static <T> int[] findMatches(List<T> inputs, List<? extends Predicate<T>> tests) {
        int count = inputs.size();
        if (count != tests.size()) return null;
        boolean[][] fits = new boolean[count][count];
        for (int i = 0; i < count; i++) for (int t = 0; t < count; t++) fits[i][t] = tests.get(t).test(inputs.get(i));
        int[] holder = new int[count];
        Arrays.fill(holder, -1);
        for (int input = 0; input < count; input++) {
            if (!take(input, fits, holder, new boolean[count])) return null;
        }
        int[] out = new int[count];
        for (int test = 0; test < count; test++) out[holder[test]] = test;
        return out;
    }

    private static boolean take(int input, boolean[][] fits, int[] holder, boolean[] seen) {
        for (int test = 0; test < holder.length; test++) {
            if (!fits[input][test] || seen[test]) continue;
            seen[test] = true;
            if (holder[test] < 0 || take(holder[test], fits, holder, seen)) {
                holder[test] = input;
                return true;
            }
        }
        return false;
    }
}
