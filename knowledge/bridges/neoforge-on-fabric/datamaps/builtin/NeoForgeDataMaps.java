package ${package}.datamaps.builtin;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import ${package}.datamaps.DataMapType;
import ${package}.datamaps.DataMaps;

/**
 * NeoForge's net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps (from 26.3) that the converted mods
 * use: their files are read as NeoForge reads them, and a mod reads them with getData. Difference: the game's parrots
 * keep their own imitation table on Fabric (it is private to the game), so PARROT_IMITATIONS changes no parrot.
 */
public final class NeoForgeDataMaps {

    private NeoForgeDataMaps() {}

    public static final DataMapType<EntityType<?>, ParrotImitation> PARROT_IMITATIONS = DataMapType.builder(
        id("parrot_imitations"), Registries.ENTITY_TYPE, ParrotImitation.CODEC
    ).synced(ParrotImitation.SOUND_CODEC, false).build();

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("neoforge", path);
    }

    /** Registers NeoForge's own types (DataMaps.start). */
    public static void register() {
        DataMaps.registerBuiltIn(PARROT_IMITATIONS);
    }

    /** Hooks the game's own tables once (none from 26.3: the game reads its fuels and compostables itself). */
    public static void wire() {}

    /** Applies the loaded maps to the game's own tables after each load (none from 26.3). */
    public static void apply(boolean client) {}
}
