// Builds the ring the player looks around, once, into the save: 24 targets 4 blocks from the player
// at (0.5, -60, 0.5), at eye height, each a thing Jade describes (block entities with server data,
// crops, plain blocks, still mobs and armor stands). Mobs have NoAI and are silent, so every launch
// sees the same ring.
var server = mc.getSingleplayerServer();
String[] blocks = {
    "minecraft:furnace", "minecraft:chest", "minecraft:brewing_stand", "minecraft:beehive", "minecraft:barrel",
    "minecraft:spawner", "minecraft:jukebox", "minecraft:hopper", "minecraft:enchanting_table", "minecraft:lectern",
    "minecraft:oak_sign", "minecraft:cauldron", "minecraft:composter", "minecraft:smoker", "minecraft:note_block",
    "minecraft:redstone_wire"
};
String[] mobs = {"minecraft:villager", "minecraft:armor_stand", "minecraft:cow", "minecraft:horse", "minecraft:sheep",
    "minecraft:iron_golem", "minecraft:fox", "minecraft:armor_stand"};
String result = server.submit(() -> {
    var source = server.createCommandSourceStack().withSuppressedOutput();
    var commands = server.getCommands();
    commands.performPrefixedCommand(source, "forceload add -16 -16 16 16");
    commands.performPrefixedCommand(source, "fill -8 -60 -8 8 -55 8 minecraft:air");
    int placed = 0;
    for (int i = 0; i < 24; i++) {
        double yaw = Math.toRadians(i * 15.0);
        double dx = -Math.sin(yaw) * 4;
        double dz = Math.cos(yaw) * 4;
        int bx = (int) Math.floor(0.5 + dx);
        int bz = (int) Math.floor(0.5 + dz);
        if (i % 3 == 2) {
            String mob = mobs[(i / 3) % mobs.length];
            String nbt = mob.endsWith("armor_stand") ? "{NoGravity:1b,Rotation:[" + (i * 15 + 180) + "f,0f]}"
                : "{NoAI:1b,Silent:1b,PersistenceRequired:1b,Rotation:[" + (i * 15 + 180) + "f,0f]}";
            commands.performPrefixedCommand(source, String.format(java.util.Locale.ROOT, "summon %s %.2f -60 %.2f %s", mob, 0.5 + dx, 0.5 + dz, nbt));
        } else {
            String block = blocks[placed % blocks.length];
            placed++;
            commands.performPrefixedCommand(source, "setblock " + bx + " -59 " + bz + " minecraft:glass");
            commands.performPrefixedCommand(source, "setblock " + bx + " -59 " + bz + " " + block);
            if (block.endsWith("chest") || block.endsWith("barrel") || block.endsWith("hopper")) {
                commands.performPrefixedCommand(source, "item replace block " + bx + " -59 " + bz + " container.0 with minecraft:diamond 7");
                commands.performPrefixedCommand(source, "item replace block " + bx + " -59 " + bz + " container.1 with minecraft:oak_log 32");
            }
            if (block.endsWith("furnace") || block.endsWith("smoker")) {
                commands.performPrefixedCommand(source, "item replace block " + bx + " -59 " + bz + " container.0 with minecraft:beef 16");
            }
        }
    }
    commands.performPrefixedCommand(source, "tp @a 0.5 -60 0.5 0 0");
    commands.performPrefixedCommand(source, "gamemode creative @a");
    return "scene";
}).join();
return result;
