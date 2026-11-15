// Turns the player's view around the ring at 45 degrees a second, level with the targets, one step
// per 50 ms on the client, and returns at once: what a player looking around does, so Jade raycasts,
// collects a tooltip and requests server data for a new target every few ticks. Each call restarts
// the turn from yaw 0, so every capture sees the same sequence of targets.
Object previous = vars.get("look.thread");
if (previous instanceof Thread t) {
    t.interrupt();
    t.join(2000);
}
Thread look = new Thread(() -> {
    long start = System.nanoTime();
    try {
        while (true) {
            double t = (System.nanoTime() - start) / 1e9;
            float yaw = (float) ((t * 45.0) % 360.0);
            mc.execute(() -> {
                if (mc.player == null) return;
                mc.player.setYRot(yaw);
                mc.player.setYHeadRot(yaw);
                mc.player.yRotO = yaw;
                mc.player.setXRot(0f);
                mc.player.xRotO = 0f;
            });
            Thread.sleep(50);
        }
    } catch (InterruptedException stopped) {
    }
}, "mmt-look");
look.setDaemon(true);
vars.put("look.thread", look);
look.start();
return "looking";
