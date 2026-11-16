package ${package};

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;

/**
 * NeoForge's net.neoforged.neoforgespi.language.IModInfo, as far as mods read it from ModContainer.getModInfo(): the
 * mod's id, name, description, namespace, version, properties and file, read from Fabric Loader's metadata of that mod.
 * The version is Fabric Loader's (NeoForge's is Maven's ArtifactVersion; MavenVersionTranslator reads both as text), and
 * the properties are fabric.mod.json's custom values (NeoForge's [modproperties.ID]), objects as maps and arrays as lists.
 */
public interface IModInfo {

    String getModId();

    String getDisplayName();

    String getDescription();

    default String getNamespace() {
        return getModId();
    }

    /** The mod's version as Fabric Loader read it, or null when Fabric Loader does not know the mod. */
    default Version getVersion() {
        return FabricLoader.getInstance().getModContainer(getModId()).map(c -> c.getMetadata().getVersion()).orElse(null);
    }

    /** fabric.mod.json's custom values of the mod, as plain Java values. */
    default Map<String, Object> getModProperties() {
        Map<String, Object> out = new LinkedHashMap<>();
        FabricLoader.getInstance().getModContainer(getModId()).ifPresent(c -> {
            for (var e : c.getMetadata().getCustomValues().entrySet()) out.put(e.getKey(), plain(e.getValue()));
        });
        return out;
    }

    /** The file the mod came in (one per mod on Fabric, its root paths), or null when Fabric Loader does not know the mod. */
    default IModFileInfo getOwningFile() {
        return IModFileInfo.of(getModId());
    }

    /** A custom value as NeoForge's properties hold it: a map, a list, a string, a number, a boolean or null. */
    static Object plain(CustomValue value) {
        return switch (value.getType()) {
            case OBJECT -> {
                Map<String, Object> map = new LinkedHashMap<>();
                for (var e : value.getAsObject()) map.put(e.getKey(), plain(e.getValue()));
                yield map;
            }
            case ARRAY -> {
                List<Object> list = new ArrayList<>();
                for (CustomValue item : value.getAsArray()) list.add(plain(item));
                yield list;
            }
            case STRING -> value.getAsString();
            case NUMBER -> value.getAsNumber();
            case BOOLEAN -> value.getAsBoolean();
            case NULL -> null;
        };
    }

    /** The mod's metadata as Fabric Loader read it (the mod's id alone when Fabric Loader does not know it). */
    static IModInfo of(String modid) {
        ModMetadata meta = FabricLoader.getInstance().getModContainer(modid).map(c -> c.getMetadata()).orElse(null);
        return new IModInfo() {
            @Override
            public String getModId() {
                return modid;
            }

            @Override
            public String getDisplayName() {
                return meta == null ? modid : meta.getName();
            }

            @Override
            public String getDescription() {
                return meta == null ? "" : meta.getDescription();
            }
        };
    }
}
