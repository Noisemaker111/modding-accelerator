package ${package};

import com.mojang.authlib.GameProfile;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/**
 * NeoForge's net.neoforged.neoforge.common.util.FakePlayerFactory: one FakePlayer per level and profile, dropped when
 * the level unloads (Fabric's ServerLevelEvents.UNLOAD, where NeoForge drops them). The same members as NeoForge's;
 * getMinecraft's profile is NeoForge's (and Fabric API's) "[Minecraft]".
 */
public class FakePlayerFactory {

    private static final GameProfile MINECRAFT = new GameProfile(UUID.fromString("41C82C87-7AfB-4024-BA57-13D2C99CAE77"), "[Minecraft]");
    private static final Map<FakePlayerKey, FakePlayer> fakePlayers = new HashMap<>();

    private record FakePlayerKey(ServerLevel level, GameProfile username) {}

    public static FakePlayer getMinecraft(ServerLevel level) {
        return get(level, MINECRAFT);
    }

    public static FakePlayer get(ServerLevel level, GameProfile username) {
        FakePlayerKey key = new FakePlayerKey(level, username);
        return fakePlayers.computeIfAbsent(key, k -> new FakePlayer(k.level(), k.username()));
    }

    public static void unloadLevel(ServerLevel level) {
        fakePlayers.entrySet().removeIf(entry -> entry.getValue().level() == level);
    }
}
