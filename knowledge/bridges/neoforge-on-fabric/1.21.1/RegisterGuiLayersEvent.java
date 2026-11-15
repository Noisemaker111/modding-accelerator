package ${package};

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;

/**
 * NeoForge 21.1's net.neoforged.neoforge.client.event.RegisterGuiLayersEvent, posted once when the client entrypoint ends
 * (ClientBridge.client). Fabric API for 1.21.1 has no HUD layers, only HudRenderCallback after the game drew its HUD, so on
 * this version the mods' layers keep NeoForge's order among themselves (a layer ordered against one of the game's goes
 * first or last, as below or above it) and all draw after the game's HUD, each between RenderGuiLayerEvent.Pre and Post;
 * wrapLayer and replaceLayer work on the mods' layers, and on the game's are refused (IllegalArgumentException).
 */
public class RegisterGuiLayersEvent extends Event implements IModBusEvent {

    record Named(ResourceLocation name, LayeredDraw.Layer layer) {}

    /** The mods' layers, in drawing order. */
    static final List<Named> LAYERS = new ArrayList<>();

    RegisterGuiLayersEvent() {}

    /** RenderGuiEvent and the mods' layers, from Fabric API's HudRenderCallback; from ClientGameEvents.hook(). */
    static void hook() {
        HudRenderCallback.EVENT.register((graphics, deltaTracker) -> {
            if (Bus.INSTANCE.post(new RenderGuiEvent.Pre(graphics, deltaTracker)).isCanceled()) return;
            for (Named named : List.copyOf(LAYERS)) {
                if (Bus.INSTANCE.post(new RenderGuiLayerEvent.Pre(graphics, deltaTracker, named.name(), named.layer())).isCanceled()) continue;
                named.layer().render(graphics, deltaTracker);
                Bus.INSTANCE.post(new RenderGuiLayerEvent.Post(graphics, deltaTracker, named.name(), named.layer()));
            }
            Bus.INSTANCE.post(new RenderGuiEvent.Post(graphics, deltaTracker));
        });
    }

    public void registerBelowAll(ResourceLocation id, LayeredDraw.Layer layer) {
        add(0, id, layer);
    }

    public void registerBelow(ResourceLocation other, ResourceLocation id, LayeredDraw.Layer layer) {
        int at = index(other);
        add(at < 0 ? 0 : at, id, layer);
    }

    public void registerAbove(ResourceLocation other, ResourceLocation id, LayeredDraw.Layer layer) {
        int at = index(other);
        add(at < 0 ? LAYERS.size() : at + 1, id, layer);
    }

    public void registerAboveAll(ResourceLocation id, LayeredDraw.Layer layer) {
        add(LAYERS.size(), id, layer);
    }

    public void replaceLayer(ResourceLocation id, LayeredDraw.Layer replacement) {
        wrapLayer(id, old -> replacement);
    }

    public void wrapLayer(ResourceLocation id, UnaryOperator<LayeredDraw.Layer> wrapper) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(wrapper);
        for (int i = 0; i < LAYERS.size(); i++) {
            if (LAYERS.get(i).name().equals(id)) {
                LAYERS.set(i, new Named(id, Objects.requireNonNull(wrapper.apply(LAYERS.get(i).layer()), "wrapping layer must not be null")));
                return;
            }
        }
        throw new IllegalArgumentException("Attempted to wrap layer with id '" + id + "', which does not exist among the mods' layers (the game's cannot be wrapped on Fabric for 1.21.1)!");
    }

    /** The index of a mod's layer, -1 for a name of the game's; an unknown name throws, as on NeoForge. */
    static int index(ResourceLocation other) {
        for (int i = 0; i < LAYERS.size(); i++) if (LAYERS.get(i).name().equals(other)) return i;
        if (other.getNamespace().equals("minecraft")) return -1;
        throw new IllegalArgumentException("Attempted to order against an unregistered layer " + other + ". Only order against vanilla's and your own.");
    }

    static void add(int at, ResourceLocation id, LayeredDraw.Layer layer) {
        Objects.requireNonNull(id);
        for (Named named : LAYERS) if (named.name().equals(id)) throw new IllegalArgumentException("Layer already registered: " + id);
        LAYERS.add(at, new Named(id, Objects.requireNonNull(layer)));
    }
}
