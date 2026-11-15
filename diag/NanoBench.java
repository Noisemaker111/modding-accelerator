public class NanoBench {
    static volatile long sink;
    public static void main(String[] a) {
        for (int round = 0; round < 3; round++) {
            long n = 0, t0 = System.nanoTime(), end = t0 + 500_000_000L;
            while (System.nanoTime() < end) { sink++; n++; }
            System.out.printf("NANOBENCH java=%s ns_per_nanoTime_iteration=%.1f%n", System.getProperty("java.version"), 5e8 / n);
        }
    }
}
