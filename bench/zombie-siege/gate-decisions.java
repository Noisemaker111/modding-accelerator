import com.rique.zombieapocalypse.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
// Exact decision trace of the changed paths on frozen, synthetic inputs: every entity type's
// classification, AI eligibility per zombie type, and the break/place/tower decisions (with
// every block the mod asks permission for) over 100 consecutive game times. Permissions deny,
// so the world is never edited by the mod; synthetic entities are never added to the level.
var server = mc.getSingleplayerServer();
var level = server.overworld();
String result = server.submit(() -> {
    StringBuilder trace = new StringBuilder();
    for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
        trace.append(EntityType.getKey(type)).append(ZombieClassMobs.isZombieClass(type) ? '1' : '0');
        if (type.create(level) instanceof Zombie zombie) trace.append(ZombieCompatibility.shouldUseAddonAi(zombie) ? 'a' : 'n');
        trace.append(';');
    }
    // Arena outside the pen: zombies west of a plank wall, a villager target east of it.
    for (int x = 34; x <= 46; x++) for (int z = -6; z <= 6; z++) for (int y = -60; y <= -56; y++) {
        level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
    }
    for (int z = -6; z <= 6; z++) for (int y = -60; y <= -58; y++) {
        level.setBlockAndUpdate(new BlockPos(40, y, z), Blocks.OAK_PLANKS.defaultBlockState());
    }
    Villager target = new Villager(EntityType.VILLAGER, level);
    target.setPos(44.5, -60, 0.5);
    var data = (net.minecraft.world.level.storage.ServerLevelData) level.getLevelData();
    long saved = level.getGameTime();
    int asked = 0, acted = 0;
    try {
        for (int t = 0; t < 100; t++) {
            data.setGameTime(618000L + t);
            for (int i = 0; i < 24; i++) {
                Zombie zombie = new Zombie(EntityType.ZOMBIE, level);
                zombie.setId(500000 + i);
                zombie.setPos(39.5 - (i % 2) * 0.4, -60, -5.75 + i * 0.5);
                zombie.setYRot(i * 30f);
                zombie.setTarget(i % 4 == 3 ? null : target);
                StringBuilder calls = new StringBuilder();
                boolean broke = ZombieBlockBreaker.tick(zombie, (l, pos, z, griefing) -> { calls.append('b').append(pos.toShortString()).append(griefing); return false; });
                boolean placed = ZombieBlockPlacer.tick(zombie, (l, pos, z, griefing, direction) -> { calls.append('p').append(pos.toShortString()).append(direction); return false; });
                boolean towered = ZombieTowering.tick(zombie);
                asked += calls.length() > 0 ? 1 : 0;
                acted += (broke || placed || towered) ? 1 : 0;
                trace.append(t).append(':').append(i).append(broke ? 'B' : '-').append(placed ? 'P' : '-').append(towered ? 'T' : '-').append(calls).append('|');
            }
        }
    } finally {
        data.setGameTime(saved);
    }
    return trace + "#" + asked + " " + acted;
}).join();
String[] parts = result.split("#");
String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(parts[0].getBytes(java.nio.charset.StandardCharsets.UTF_8)));
return digest + " permissionRequests/actions=" + parts[1] + " traceChars=" + parts[0].length();
