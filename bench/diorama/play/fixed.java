// For experiment-fixed.json: the same work in every launch, instead of a flight that maps whatever the
// launch's timing lets it. Starts a thread named as a baker (so the bakers' CPU metric counts it) that
// waits 3 s for the capture to start, runs the work toggle-setup.java left in vars "perf.work" (first
// meshes of the frozen save's tiles, their spawn spots and tints, nothing kept between rounds) 6 times,
// then stays alive past the window so its CPU time is read while it still exists. Returns at once.
Object work = vars.get("perf.work");
if (!(work instanceof java.util.concurrent.Callable)) throw new IllegalStateException("run toggle-setup.java first");
Object previous = vars.get("fixed.thread");
if (previous instanceof Thread t) {
    t.interrupt();
    t.join(5000);
}
Thread fixed = new Thread(() -> {
    try {
        Thread.sleep(3000);
        for (int round = 0; round < 6; round++) {
            ((java.util.concurrent.Callable<?>) work).call();
        }
        vars.put("fixed.done", true);
        Thread.sleep(120_000);
    } catch (InterruptedException stop) {
        // The next phase replaces this thread.
    } catch (Exception e) {
        vars.put("fixed.error", e.toString());
    }
}, "diorama-baker-fixed");
fixed.setDaemon(true);
vars.put("fixed.done", false);
vars.put("fixed.thread", fixed);
fixed.start();
return "fixed";
