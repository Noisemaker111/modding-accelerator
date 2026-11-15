package ${package};

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

/**
 * NeoForge's net.neoforged.neoforge.common.UsernameCache: the last name each player logged in with, kept in
 * usernamecache.json in the game folder (the same file NeoForge keeps). Loaded when the bridge hooks the game's events
 * and updated when a player logs in (Fabric's ServerPlayConnectionEvents.JOIN; NeoForge updates it from its own
 * PlayerLoggedInEvent listener). The same members as NeoForge's.
 */
public final class UsernameCache {

    private static Map<UUID, String> map = new HashMap<>();
    private static final Path saveFile = FabricLoader.getInstance().getGameDir().resolve("usernamecache.json");
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LogUtils.getLogger();

    private UsernameCache() {}

    protected static void setUsername(UUID uuid, String username) {
        Objects.requireNonNull(uuid);
        Objects.requireNonNull(username);
        if (username.equals(map.get(uuid))) return;
        map.put(uuid, username);
        save();
    }

    protected static boolean removeUsername(UUID uuid) {
        Objects.requireNonNull(uuid);
        if (map.remove(uuid) != null) {
            save();
            return true;
        }
        return false;
    }

    public static String getLastKnownUsername(UUID uuid) {
        Objects.requireNonNull(uuid);
        return map.get(uuid);
    }

    public static boolean containsUUID(UUID uuid) {
        Objects.requireNonNull(uuid);
        return map.containsKey(uuid);
    }

    public static Map<UUID, String> getMap() {
        return ImmutableMap.copyOf(map);
    }

    protected static void save() {
        new SaveThread(gson.toJson(map)).start();
    }

    protected static void load() {
        if (!Files.exists(saveFile)) return;
        try (BufferedReader reader = Files.newBufferedReader(saveFile, StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<UUID, String>>() {}.getType();
            map = gson.fromJson(reader, type);
        } catch (JsonSyntaxException | IOException e) {
            LOGGER.error("Could not parse username cache file as valid json, deleting file {}", saveFile, e);
            try {
                Files.delete(saveFile);
            } catch (IOException e1) {
                LOGGER.error("Could not delete file {}", saveFile);
            }
        } finally {
            if (map == null) {
                map = new HashMap<>();
            }
        }
    }

    private static class SaveThread extends Thread {

        private final String data;

        SaveThread(String data) {
            this.data = data;
        }

        @Override
        public void run() {
            try {
                synchronized (saveFile) {
                    Files.write(saveFile, data.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                LOGGER.error("Failed to save username cache to file!", e);
            }
        }
    }
}
