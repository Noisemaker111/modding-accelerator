/** DIAG: N threads; each is busy for busyMs then sleeps sleepMs (sleepMs=0: always busy). Pure Java arithmetic, no clock in the hot loop. */
public class Spin {
    static volatile long sink;
    public static void main(String[] a) throws Exception {
        int threads = Integer.parseInt(a[0]);
        long busyMs = Long.parseLong(a[1]), sleepMs = Long.parseLong(a[2]), seconds = Long.parseLong(a[3]);
        boolean clockLoop = Boolean.getBoolean("clock");
        long stop = System.nanoTime() + seconds * 1_000_000_000L;
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            ts[t] = new Thread(() -> {
                try {
                    while (System.nanoTime() < stop) {
                        long end = System.nanoTime() + busyMs * 1_000_000L;
                        if (clockLoop) { while (System.nanoTime() < end) sink++; }
                        else { while (System.nanoTime() < end) for (int i = 0; i < 1000; i++) sink += i ^ (sink >>> 3); }
                        if (sleepMs > 0) Thread.sleep(sleepMs);
                    }
                } catch (InterruptedException e) { }
            }, "spin-" + t);
            ts[t].start();
        }
        for (Thread t : ts) t.join();
    }
}
