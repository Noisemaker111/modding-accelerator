// A vanilla zombie summoned by a command, with a pig three blocks away, at night, the player in creative far off.
// What Zombie Apocalypse Addon does to every zombie that joins a level (EventHandler.onEntityJoinLevel) is checked
// after this by check.java: its own AI goals, its scaling tag, and that the zombie hunts the pig.
var server = mc.getSingleplayerServer();
var uuid = mc.player.getUUID();
// For eval --until: whether the summoned zombie has picked a target yet.
vars.put("hunting", (java.util.function.BooleanSupplier) () -> server.submit(() -> {
    for (var e : server.overworld().getAllEntities())
        if (e.getTags().contains("modaccel_za") && e instanceof net.minecraft.world.entity.monster.Zombie z && z.getTarget() != null) return true;
    return false;
}).join());
return server.submit(() -> {
    var sp = server.getPlayerList().getPlayer(uuid);
    var source = server.createCommandSourceStack().withSuppressedOutput().withPermission(4);
    var commands = server.getCommands();
    double x = Math.floor(sp.getX()) + 0.5, z = Math.floor(sp.getZ()) + 24.5;
    int y = sp.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
    for (String c : new String[] {
            "difficulty easy", "gamemode creative @a", "time set midnight", "gamerule doDaylightCycle false",
            "gamerule doMobSpawning false", "kill @e[type=!player]",
            // Animal hunting is off by default; the mod's own command turns it on, berserker skips its hunger and cooldown gates.
            "zhunt on", "zhunt berserker on",
            "summon minecraft:zombie " + x + " " + y + " " + z + " {Tags:[\"modaccel_za\"],PersistenceRequired:1b}",
            "summon minecraft:pig " + (x + 3) + " " + y + " " + z + " {Tags:[\"modaccel_pig\"],NoAI:1b,PersistenceRequired:1b,Health:1000f,attributes:[{id:\"minecraft:max_health\",base:1000d}]}" })
        commands.performPrefixedCommand(source, c);
    return "summoned a zombie and a pig at " + x + " " + y + " " + z;
}).join();
