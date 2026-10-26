var server = mc.getServer();
var uuid = mc.player.getUuid();
server.execute(() -> {
    var sp = server.getPlayerManager().getPlayer(uuid);
    var source = server.getCommandSource().withSilent();
    server.getCommandManager().executeWithPrefix(source, "difficulty easy");
    server.getCommandManager().executeWithPrefix(source, "time set day");
    server.getCommandManager().executeWithPrefix(source, "gamerule doDaylightCycle false");
    server.getCommandManager().executeWithPrefix(source, "gamerule doMobSpawning false");
    server.getCommandManager().executeWithPrefix(source, "kill @e[type=!player]");
    server.getCommandManager().executeWithPrefix(source, "effect clear @a");
    sp.setHealth(2.0F);
    var hunger = sp.getHungerManager();
    hunger.setFoodLevel(17);
    hunger.setSaturationLevel(0.0F);
    try {
        var f = net.minecraft.entity.player.HungerManager.class.getDeclaredField("exhaustion");
        f.setAccessible(true);
        f.setFloat(hunger, 0.0F);
    } catch (Exception e) { throw new RuntimeException(e); }
});
return "server player set to health 2, food 17, saturation 0, exhaustion 0";
