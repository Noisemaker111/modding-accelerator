import java.util.*;
import java.util.concurrent.locks.LockSupport;
/** DIAG: how long a requested 10 ms sleep really takes. */
public class SleepJitter {
    public static void main(String[] a) throws Exception {
        for (String kind : List.of("Thread.sleep(10)", "parkNanos(10ms)", "Thread.sleep(1)")) {
            long[] d = new long[150];
            for (int i = 0; i < d.length; i++) {
                long t = System.nanoTime();
                if (kind.startsWith("Thread.sleep(10")) Thread.sleep(10);
                else if (kind.startsWith("park")) LockSupport.parkNanos(10_000_000L);
                else Thread.sleep(1);
                d[i] = System.nanoTime() - t;
            }
            Arrays.sort(d);
            System.out.printf("JITTER %s java=%s %s p10=%.2fms p50=%.2fms p90=%.2fms max=%.2fms%n", a.length > 0 ? a[0] : "", System.getProperty("java.version"),
                kind, d[15] / 1e6, d[75] / 1e6, d[135] / 1e6, d[149] / 1e6);
        }
    }
}
