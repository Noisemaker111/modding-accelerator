package ${package};

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import java.lang.reflect.Proxy;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A mod's special model renderer type is read from an item model by the game's own codec, as on NeoForge. */
class RegisterSpecialModelRendererEventTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void theGamesCodecReadsTheModsType() {
        SpecialModelRenderer.Unbaked<?> glow = (SpecialModelRenderer.Unbaked<?>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { SpecialModelRenderer.Unbaked.class }, (p, m, a) -> null);
        new RegisterSpecialModelRendererEvent().register(Identifier.fromNamespaceAndPath("demo", "glow"), MapCodec.unit(glow));

        var read = SpecialModelRenderers.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"type\": \"demo:glow\"}"));
        assertTrue(read.isSuccess(), () -> read.error().get().message());
        assertSame(glow, read.getOrThrow());
        assertTrue(SpecialModelRenderers.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"type\": \"demo:other\"}")).isError());
    }
}
