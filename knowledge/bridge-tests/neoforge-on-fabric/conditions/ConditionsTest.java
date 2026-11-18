package ${package}.conditions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceCondition;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** NeoForge's conditions read and write NeoForge's JSON, and each of NeoForge's own types becomes the Fabric condition that means the same. */
class ConditionsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    @Test
    void neoForgesFormRoundTrips() {
        JsonElement written = json("{\"type\":\"neoforge:not\",\"value\":{\"type\":\"neoforge:mod_loaded\",\"modid\":\"create\"}}");
        ICondition read = ICondition.CODEC.parse(JsonOps.INSTANCE, written).getOrThrow();
        assertEquals(NeoForgeConditions.not(new ModLoadedCondition("create")), read);
        assertEquals(written, ICondition.CODEC.encodeStart(JsonOps.INSTANCE, read).getOrThrow());
    }

    @Test
    void neoForgesTypesBecomeFabricsConditions() {
        ResourceCondition fabric = Conditions.fabric(NeoForgeConditions.and(new ModLoadedCondition("create"), NeoForgeConditions.not(NeoForgeConditions.modLoaded("jei"))));
        assertEquals(
            json("{\"condition\":\"fabric:and\",\"values\":[{\"condition\":\"fabric:all_mods_loaded\",\"values\":[\"create\"]}," +
                "{\"condition\":\"fabric:not\",\"value\":{\"condition\":\"fabric:all_mods_loaded\",\"values\":[\"jei\"]}}]}"),
            ResourceCondition.CODEC.encodeStart(JsonOps.INSTANCE, fabric).getOrThrow()
        );
    }

    @Test
    void aModsOwnConditionHasNoFabricForm() {
        ICondition own = new ICondition() {
            @Override
            public boolean test(IContext context) {
                return true;
            }

            @Override
            public com.mojang.serialization.MapCodec<? extends ICondition> codec() {
                return com.mojang.serialization.MapCodec.unit(this);
            }
        };
        assertNull(Conditions.fabric(own));
        assertNull(Conditions.fabric(NeoForgeConditions.not(own)));
    }
}
