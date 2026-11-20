package ${package};

import com.mojang.serialization.MapCodec;
import java.lang.reflect.Field;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;

/**
 * NeoForge's net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent (26.x), posted on the mod bus with the
 * client's registration events (ClientGameEvents.registration), before the game loads item models: each renderer type
 * goes into the game's own table (SpecialModelRenderers' id mapper, which its CODEC reads an item model's "special"
 * model with), as NeoForge passes that table to the event. Fabric API has no registry for these.
 */
public class RegisterSpecialModelRendererEvent extends Event implements IModBusEvent {

    public RegisterSpecialModelRendererEvent() {}

    public void register(Identifier location, MapCodec<? extends SpecialModelRenderer.Unbaked<?>> source) {
        idMapper().put(location, source);
    }

    /** The game's table (26.x keeps the game's own names at run time). */
    @SuppressWarnings("unchecked")
    static ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends SpecialModelRenderer.Unbaked<?>>> idMapper() {
        try {
            Field field = SpecialModelRenderers.class.getDeclaredField("ID_MAPPER");
            field.setAccessible(true);
            return (ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends SpecialModelRenderer.Unbaked<?>>>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("No SpecialModelRenderers.ID_MAPPER in this game", e);
        }
    }
}
