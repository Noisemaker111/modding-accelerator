var server = mc.getSingleplayerServer();
var uuid = mc.player.getUUID();
return server.submit(() -> {
    var sp = server.getPlayerList().getPlayer(uuid);
    var source = server.createCommandSourceStack().withSuppressedOutput();
    for (String c : new String[] { "difficulty easy", "time set day", "kill @e[type=!player]", "effect clear @a" })
        server.getCommands().performPrefixedCommand(source, c);
    var rules = server.getGameRules();
    rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server);
    rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server);
    if (!rules.get(net.minecraft.world.level.gamerules.GameRules.NATURAL_HEALTH_REGENERATION))
        throw new IllegalStateException("Natural regeneration is off in this world, so nothing would heal.");
    sp.setHealth(2.0F);
    var food = sp.getFoodData();
    food.setFoodLevel(17);
    food.setSaturation(0.0F);
    try {
        var f = net.minecraft.world.food.FoodData.class.getDeclaredField("exhaustionLevel");
        f.setAccessible(true);
        f.setFloat(food, 0.0F);
    } catch (Exception e) { throw new RuntimeException(e); }
    return "server player set to health 2, food 17, saturation 0, exhaustion 0";
}).join();
