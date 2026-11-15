import net.minecraft.server.level.ServerPlayer;
// Flies the player on the integrated server along a fixed line, one step per 50 ms, and returns at once,
// so every launch maps the same terrain at the same speed: 16 blocks/s at y 140, looking north and down.
// vars "fly.segment": "east" (0, 0) -> (480, 0), the cold capture's; "south" (480, 0) -> (480, 480), the
// warm capture's; "path" (default) both, back to back, which prepare_world.py uses to generate the save.
// fly-east.java and fly-south.java set the segment and run this file. Each segment flies 30 s, then
// the player hovers at its end; "fly.done" turns true 20 s after the last segment ends.
double speed = 16.0;
String segment = vars.containsKey("fly.segment") ? vars.get("fly.segment").toString() : "path";
double x0 = segment.equals("south") ? 480 : 0;
double z0 = 0;
double dx = segment.equals("south") ? 0 : 1;
double dz = segment.equals("south") ? 1 : 0;
double length = segment.equals("path") ? 960 : 480;
Object previous = vars.get("fly.thread");
if (previous instanceof Thread t) {
    t.interrupt();
    t.join(2000);
}
var server = mc.getSingleplayerServer();
Thread flight = new Thread(() -> {
    long start = System.nanoTime();
    try {
        while (true) {
            double t = (System.nanoTime() - start) / 1e9;
            double travelled = Math.min(speed * t, length);
            // The whole path turns south at (480, 0).
            double x = segment.equals("path") ? Math.min(travelled, 480) : x0 + dx * travelled;
            double z = segment.equals("path") ? Math.max(0, travelled - 480) : z0 + dz * travelled;
            server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                p.getAbilities().flying = true;
                p.teleportTo(p.serverLevel(), x, 140, z, 0f, 30f);
            });
            if (t > length / speed + 20) break;
            Thread.sleep(50);
        }
    } catch (InterruptedException stopped) {
    }
    vars.put("fly.done", true);
}, "modaccel-fly");
flight.setDaemon(true);
vars.put("fly.done", false);
vars.put("fly.thread", flight);
flight.start();
return "flying";
