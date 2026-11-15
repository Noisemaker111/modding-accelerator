package ${package}.loader.api;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;
import ${package}.loader.api.entrypoint.EntrypointContainer;

/**
 * Fabric Loader's net.fabricmc.loader.api.FabricLoader on NeoForge's mod list (FancyModLoader): the mods NeoForge
 * loaded, found by their NeoForge id or by the Fabric id a converted mod keeps (modproperties fabricId); NeoForge's
 * config and game folders; its physical side as Dist (the side type convert writes for Fabric's EnvType); and the
 * entrypoints other mods read, which convert keeps in each converted mod's modproperties as fabric.entrypoints.KEY.
 * A Fabric entrypoint is a class made with its no-argument constructor, or Class::field or Class::method (static).
 */
public final class FabricLoader {

    private static final FabricLoader INSTANCE = new FabricLoader();
    private final Map<String, Object> made = new ConcurrentHashMap<>();

    private FabricLoader() {}

    public static FabricLoader getInstance() {
        return INSTANCE;
    }

    /** NeoForge's id for a Fabric id: the converted mod that keeps it as fabricId, else the id with - and . as _. */
    static String neoforgeId(String id) {
        ModList mods = ModList.get();
        if (mods == null || mods.isLoaded(id)) return id;
        for (IModInfo info : mods.getMods()) if (id.equals(info.getModProperties().get("fabricId"))) return info.getModId();
        String neo = id.replaceAll("[^a-z0-9_]", "_");
        return mods.isLoaded(neo) ? neo : id;
    }

    public boolean isModLoaded(String id) {
        return ModList.get() != null && ModList.get().isLoaded(neoforgeId(id));
    }

    public Optional<ModContainer> getModContainer(String id) {
        return ModList.get() == null ? Optional.empty() : ModList.get().getModContainerById(neoforgeId(id)).map(ModContainer::new);
    }

    public Collection<ModContainer> getAllMods() {
        List<ModContainer> out = new ArrayList<>();
        for (IModInfo info : ModList.get().getMods()) ModList.get().getModContainerById(info.getModId()).ifPresent(c -> out.add(new ModContainer(c)));
        return out;
    }

    public boolean isDevelopmentEnvironment() {
        return !FMLEnvironment.isProduction();
    }

    public Dist getEnvironmentType() {
        return FMLEnvironment.getDist();
    }

    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    public File getConfigDirectory() {
        return getConfigDir().toFile();
    }

    public Path getGameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    public File getGameDirectory() {
        return getGameDir().toFile();
    }

    public String getRawGameVersion() {
        return ModList.get().getModContainerById("minecraft").map(c -> c.getModInfo().getVersion().toString()).orElse("");
    }

    public <T> List<T> getEntrypoints(String key, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (EntrypointContainer<T> container : getEntrypointContainers(key, type)) out.add(container.getEntrypoint());
        return out;
    }

    public <T> void invokeEntrypoints(String key, Class<T> type, Consumer<? super T> invoker) {
        for (T entrypoint : getEntrypoints(key, type)) invoker.accept(entrypoint);
    }

    public <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) {
        List<EntrypointContainer<T>> out = new ArrayList<>();
        for (IModInfo info : ModList.get().getMods()) {
            Object value = info.getModProperties().get("fabric.entrypoints." + key);
            if (!(value instanceof List<?> list)) continue;
            ModContainer provider = getModContainer(info.getModId()).orElse(null);
            if (provider == null) continue;
            for (Object definition : list) {
                String name = String.valueOf(definition);
                Object entry = made.computeIfAbsent(info.getModId() + "/" + name, k -> make(name));
                if (!type.isInstance(entry)) throw new IllegalStateException("Entrypoint " + name + " of " + info.getModId() + " is not a " + type.getName());
                out.add(new EntrypointContainer<>(type.cast(entry), provider, name));
            }
        }
        return out;
    }

    /** One entrypoint as Fabric's default language adapter makes it. */
    static Object make(String definition) {
        try {
            String[] parts = definition.split("::", 2);
            Class<?> owner = Class.forName(parts[0], true, FabricLoader.class.getClassLoader());
            if (parts.length == 1) return owner.getDeclaredConstructor().newInstance();
            for (Field field : owner.getDeclaredFields()) if (field.getName().equals(parts[1]) && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                return field.get(null);
            }
            for (Method method : owner.getDeclaredMethods()) if (method.getName().equals(parts[1]) && Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0) {
                method.setAccessible(true);
                return method.invoke(null);
            }
            throw new IllegalStateException("No static field or method " + parts[1] + " in " + parts[0]);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not make the entrypoint " + definition, e);
        }
    }
}
