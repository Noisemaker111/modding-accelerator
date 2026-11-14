import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapViewport;
import dev.diorama.render.Radar;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointOverlay;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

if (mc.level == null || mc.player == null) throw new IllegalStateException("Enter a disposable world first");
var view = new MapViewport.View(0, 0, 640, 360, Projection.u(mc.player.getX(), mc.player.getZ()),
    Projection.v(mc.player.getX(), mc.player.getY(), mc.player.getZ()), 2, TileKey.SURFACE, false, true);
List<Object> radar = new ArrayList<>(), waypoints = new ArrayList<>();
List<int[]> cases = new ArrayList<>();
for (int count : new int[] {0, 64, 256, 1024}) for (int stack : new int[] {1, 64}) cases.add(new int[] {count, stack});
Collections.shuffle(cases, new Random(20261114));
for (int[] scenario : cases) {
    int count = scenario[0], stack = scenario[1];
    List<Integer> ids = new ArrayList<>();
    int baseline = Radar.collect(view, 1, true).size();
    try {
        for (int i = 0; i < count; i++) {
            int id = -2_000_000 - i;
            if (mc.level.getEntity(id) != null) throw new IllegalStateException("Fixture entity ID already exists");
            ItemEntity e = new ItemEntity(mc.level, mc.player.getX() + (i % 16) - 8, mc.player.getY(),
                mc.player.getZ() + ((i / 16) % 16) - 8, new ItemStack(Items.COBBLESTONE, stack));
            e.setId(id);
            mc.level.addEntity(e);
            ids.add(id);
        }
        int seen = Radar.collect(view, 1, true).size();
        if (seen != baseline + count) throw new AssertionError("Radar did not include all fixture entities: " + seen + "/" + (baseline + count));
        long checksum = 0;
        long warmUntil = System.nanoTime() + 1_000_000_000L;
        do { checksum += Radar.collect(view, 1, true).size(); } while (System.nanoTime() < warmUntil);
        List<Double> samples = new ArrayList<>();
        for (int round = 0; round < 12; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < 200; i++) checksum += Radar.collect(view, 1, true).size();
            samples.add((System.nanoTime() - start) / 200e6);
        }
        radar.add(Map.of("entities", count, "itemsPerStack", stack, "existingBlips", baseline, "observedBlips", seen,
            "millisecondsPerCollect", samples, "checksum", checksum));
    } finally {
        for (int id : ids) mc.level.removeEntity(id, Entity.RemovalReason.DISCARDED);
    }
}
for (int count : new int[] {0, 120, 1000, 5000}) {
    List<Waypoint> pins = new ArrayList<>();
    for (int i = 0; i < count; i++) {
        pins.add(new Waypoint("Study " + i, (int) mc.player.getX() + (i % 100) * 16, 64,
            (int) mc.player.getZ() + (i / 100) * 16, "minecraft:overworld", 0xff00ff00));
    }
    int seen = WaypointOverlay.layout(view, pins, true).size();
    if (seen != count) throw new AssertionError("Expected every synthetic waypoint in layout: " + seen + "/" + count);
    long checksum = 0;
    long warmUntil = System.nanoTime() + 500_000_000L;
    do { checksum += WaypointOverlay.layout(view, pins, true).size(); } while (System.nanoTime() < warmUntil);
    List<Double> samples = new ArrayList<>();
    for (int round = 0; round < 12; round++) {
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) checksum += WaypointOverlay.layout(view, pins, true).size();
        samples.add((System.nanoTime() - start) / 100e6);
    }
    waypoints.add(Map.of("waypoints", count, "millisecondsPerLayout", samples, "checksum", checksum));
}
return new com.google.gson.Gson().toJson(Map.of("schema", 1, "kind", "diorama-density", "radar", radar, "waypoints", waypoints,
    "facing", Projection.facing().name(), "position", List.of(mc.player.getX(), mc.player.getY(), mc.player.getZ()),
    "limit", "Client-only item entities, removed in finally; waypoint lists never saved. Measures collection/layout CPU, excludes drawing, networking and entity ticking."));
