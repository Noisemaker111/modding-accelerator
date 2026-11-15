// A fixed, entity-free view for the framebuffer gate: untouched grass outside the pen, camera straight down,
// time frozen, then long enough for chat lines and hurt effects to fade. Effects stay so nothing can hurt the player.
long deadline = System.currentTimeMillis() + 20000;
while (mc.player == null || !mc.player.isAlive()) {
    if (mc.player != null) mc.execute(() -> { if (!mc.player.isAlive()) mc.player.respawn(); });
    if (System.currentTimeMillis() > deadline) throw new IllegalStateException("player did not respawn");
    Thread.sleep(500);
}
var server = mc.getSingleplayerServer();
server.submit(() -> {
    var source = server.createCommandSourceStack().withSuppressedOutput().withPermission(4);
    for (String c : new String[]{"kill @e[type=!minecraft:player]", "clear Dev", "effect clear Dev minecraft:regeneration", "tp Dev 0.5 -60 60.5 0 90", "kill @e[type=minecraft:item]"}) {
        server.getCommands().performPrefixedCommand(source, c);
    }
}).join();
Thread.sleep(12000);
server.submit(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput().withPermission(4), "kill @e[type=minecraft:item]")).join();
Thread.sleep(1500);
mc.execute(() -> mc.setScreen(null));
Thread.sleep(1500);
return "framed";
