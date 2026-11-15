import net.minecraft.server.MinecraftServer;
// Night of day 25, a barrier pen, the player on a dirt pillar, and 224 persistent zombies in five rings.
long deadline = System.currentTimeMillis() + 20000;
while (mc.player == null || !mc.player.isAlive()) {
    if (mc.player != null) mc.execute(() -> { if (!mc.player.isAlive()) mc.player.respawn(); });
    if (System.currentTimeMillis() > deadline) throw new IllegalStateException("player did not respawn");
    Thread.sleep(500);
}
mc.execute(() -> mc.setScreen(null));
Thread.sleep(1000);
MinecraftServer server = mc.getSingleplayerServer();
java.util.List<String> commands = new java.util.ArrayList<>(java.util.List.of(
    "forceload add -48 -48 48 48",
    "kill @e[type=!minecraft:player]",
    "gamerule doDaylightCycle false",
    "gamerule doWeatherCycle false",
    "gamerule doMobSpawning false",
    "weather clear",
    "time set 618000",
    "fill -21 -61 -21 21 -52 21 minecraft:barrier hollow",
    "fill -20 -60 -20 20 -53 20 minecraft:air",
    "fill 0 -60 0 0 -57 0 minecraft:dirt",
    "tp Dev 0.5 -56 0.5",
    "gamemode survival Dev",
    "effect give Dev minecraft:resistance infinite 4 true",
    "effect give Dev minecraft:saturation infinite 4 true",
    "effect give Dev minecraft:regeneration infinite 4 true"));
for (int r = 7; r <= 19; r += 3) {
    int count = (int) (2 * Math.PI * r / 1.8);
    for (int i = 0; i < count; i++) {
        double a = 2 * Math.PI * i / count;
        commands.add(String.format(java.util.Locale.ROOT, "summon minecraft:zombie %.1f -60 %.1f {Tags:[\"perf\"],PersistenceRequired:1b}",
            Math.round(r * Math.cos(a) * 10) / 10.0, Math.round(r * Math.sin(a) * 10) / 10.0));
    }
}
StringBuilder out = new StringBuilder();
server.submit(() -> {
    for (String c : commands) {
        try {
            server.getCommands().getDispatcher().execute(c, server.createCommandSourceStack().withSuppressedOutput().withPermission(4));
        } catch (Exception e) { out.append("FAIL ").append(c).append(": ").append(e.getMessage()).append("; "); }
    }
}).join();
long zombies = server.overworld().getEntities(net.minecraft.world.entity.EntityType.ZOMBIE, z -> true).size();
return "zombies=" + zombies + (out.length() == 0 ? "" : " " + out);