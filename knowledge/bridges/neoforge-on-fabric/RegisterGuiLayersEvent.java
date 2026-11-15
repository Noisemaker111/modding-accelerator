package ${package};

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * NeoForge's net.neoforged.neoforge.client.event.RegisterGuiLayersEvent, posted once when the client entrypoint ends
 * (ClientBridge.client). NeoForge keeps the HUD as one ordered list of named layers; on Fabric it is Fabric API's
 * HudElementRegistry, whose VanillaHudElements stand for NeoForge's VanillaGuiLayers (several NeoForge layers split the
 * game's differently: HOTBAR is Fabric's spectator menu and hotbar, AFTER_CAMERA_DECORATIONS and CONTEXTUAL_INFO_BAR
 * have no element and are placed between their neighbours). A layer registered here is a Fabric element placed where
 * NeoForge would place it, drawn between RenderGuiLayerEvent.Pre and Post; wrapLayer and replaceLayer change what a
 * game layer or a mod's layer draws, inside its events.
 */
public class RegisterGuiLayersEvent extends Event implements IModBusEvent {

    /** NeoForge's name of each game layer, and the Fabric API elements that draw it. */
    static final Map<Identifier, List<Identifier>> FABRIC = new LinkedHashMap<>();
    /** The wrappers mods put around a game layer, by NeoForge's name. */
    static final Map<Identifier, UnaryOperator<GuiLayer>> WRAPPERS = new HashMap<>();
    /** The mods' own layers, by name. */
    static final Map<Identifier, GuiLayer> MOD_LAYERS = new HashMap<>();
    static final Identifier PRE = Identifier.fromNamespaceAndPath("${modid}", "neoforge_render_gui_pre");
    static final Identifier POST = Identifier.fromNamespaceAndPath("${modid}", "neoforge_render_gui_post");
    /** Whether this frame's RenderGuiEvent.Pre was cancelled. */
    static boolean canceled;

    static {
        FABRIC.put(VanillaGuiLayers.CAMERA_OVERLAYS, List.of(VanillaHudElements.MISC_OVERLAYS));
        FABRIC.put(VanillaGuiLayers.CROSSHAIR, List.of(VanillaHudElements.CROSSHAIR));
        FABRIC.put(VanillaGuiLayers.HOTBAR, List.of(VanillaHudElements.SPECTATOR_MENU, VanillaHudElements.HOTBAR));
        FABRIC.put(VanillaGuiLayers.PLAYER_HEALTH, List.of(VanillaHudElements.HEALTH_BAR));
        FABRIC.put(VanillaGuiLayers.ARMOR_LEVEL, List.of(VanillaHudElements.ARMOR_BAR));
        FABRIC.put(VanillaGuiLayers.FOOD_LEVEL, List.of(VanillaHudElements.FOOD_BAR));
        FABRIC.put(VanillaGuiLayers.VEHICLE_HEALTH, List.of(VanillaHudElements.MOUNT_HEALTH));
        FABRIC.put(VanillaGuiLayers.AIR_LEVEL, List.of(VanillaHudElements.AIR_BAR));
        FABRIC.put(VanillaGuiLayers.CONTEXTUAL_INFO_BAR_BACKGROUND, List.of(VanillaHudElements.INFO_BAR));
        FABRIC.put(VanillaGuiLayers.EXPERIENCE_LEVEL, List.of(VanillaHudElements.EXPERIENCE_LEVEL));
        FABRIC.put(VanillaGuiLayers.SELECTED_ITEM_NAME, List.of(VanillaHudElements.HELD_ITEM_TOOLTIP));
        FABRIC.put(VanillaGuiLayers.SPECTATOR_TOOLTIP, List.of(VanillaHudElements.SPECTATOR_TOOLTIP));
        FABRIC.put(VanillaGuiLayers.EFFECTS, List.of(VanillaHudElements.MOB_EFFECTS));
        FABRIC.put(VanillaGuiLayers.BOSS_OVERLAY, List.of(VanillaHudElements.BOSS_BAR));
        FABRIC.put(VanillaGuiLayers.SLEEP_OVERLAY, List.of(VanillaHudElements.SLEEP));
        FABRIC.put(VanillaGuiLayers.DEMO_OVERLAY, List.of(VanillaHudElements.DEMO_TIMER));
        FABRIC.put(VanillaGuiLayers.SCOREBOARD_SIDEBAR, List.of(VanillaHudElements.SCOREBOARD));
        FABRIC.put(VanillaGuiLayers.OVERLAY_MESSAGE, List.of(VanillaHudElements.OVERLAY_MESSAGE));
        FABRIC.put(VanillaGuiLayers.TITLE, List.of(VanillaHudElements.TITLE_AND_SUBTITLE));
        FABRIC.put(VanillaGuiLayers.CHAT, List.of(VanillaHudElements.CHAT));
        FABRIC.put(VanillaGuiLayers.TAB_LIST, List.of(VanillaHudElements.PLAYER_LIST));
        FABRIC.put(VanillaGuiLayers.SUBTITLE_OVERLAY, List.of(VanillaHudElements.SUBTITLES));
    }

    RegisterGuiLayersEvent() {}

    /** The game's layers wrapped in their events, and RenderGuiEvent around them all; from ClientGameEvents.hook(). */
    static void hook() {
        HudElementRegistry.addFirst(PRE, (graphics, deltaTracker) -> canceled = Bus.INSTANCE.post(new RenderGuiEvent.Pre(graphics, deltaTracker)).isCanceled());
        HudElementRegistry.addLast(POST, (graphics, deltaTracker) -> {
            if (!canceled) Bus.INSTANCE.post(new RenderGuiEvent.Post(graphics, deltaTracker));
            canceled = false;
        });
        for (var layer : FABRIC.entrySet()) {
            for (Identifier element : layer.getValue()) {
                HudElementRegistry.replaceElement(element, vanilla -> (graphics, deltaTracker) -> draw(graphics, deltaTracker, layer.getKey(), WRAPPERS.getOrDefault(layer.getKey(), UnaryOperator.identity()).apply(vanilla::extractRenderState)));
            }
        }
    }

    /** One layer between its RenderGuiLayerEvent.Pre and Post, unless this frame's RenderGuiEvent.Pre was cancelled. */
    static void draw(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Identifier name, GuiLayer layer) {
        if (canceled) return;
        if (Bus.INSTANCE.post(new RenderGuiLayerEvent.Pre(graphics, deltaTracker, name, layer)).isCanceled()) return;
        layer.render(graphics, deltaTracker);
        Bus.INSTANCE.post(new RenderGuiLayerEvent.Post(graphics, deltaTracker, name, layer));
    }

    public void registerBelowAll(Identifier id, GuiLayer layer) {
        HudElementRegistry.attachElementAfter(PRE, id, add(id, layer));
    }

    public void registerBelow(Identifier other, Identifier id, GuiLayer layer) {
        known(other);
        HudElement element = add(id, layer);
        if (other.equals(VanillaGuiLayers.AFTER_CAMERA_DECORATIONS)) HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, id, element);
        else if (other.equals(VanillaGuiLayers.CONTEXTUAL_INFO_BAR)) HudElementRegistry.attachElementAfter(VanillaHudElements.EXPERIENCE_LEVEL, id, element);
        else HudElementRegistry.attachElementBefore(FABRIC.containsKey(other) ? FABRIC.get(other).get(0) : other, id, element);
    }

    public void registerAbove(Identifier other, Identifier id, GuiLayer layer) {
        known(other);
        HudElement element = add(id, layer);
        if (other.equals(VanillaGuiLayers.AFTER_CAMERA_DECORATIONS)) HudElementRegistry.attachElementBefore(VanillaHudElements.SPECTATOR_MENU, id, element);
        else if (other.equals(VanillaGuiLayers.CONTEXTUAL_INFO_BAR)) HudElementRegistry.attachElementBefore(VanillaHudElements.HELD_ITEM_TOOLTIP, id, element);
        else HudElementRegistry.attachElementAfter(FABRIC.containsKey(other) ? FABRIC.get(other).get(FABRIC.get(other).size() - 1) : other, id, element);
    }

    public void registerAboveAll(Identifier id, GuiLayer layer) {
        HudElementRegistry.attachElementBefore(POST, id, add(id, layer));
    }

    public void replaceLayer(Identifier id, GuiLayer replacement) {
        wrapLayer(id, old -> replacement);
    }

    public void wrapLayer(Identifier id, UnaryOperator<GuiLayer> wrapper) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(wrapper);
        if (FABRIC.containsKey(id)) {
            UnaryOperator<GuiLayer> before = WRAPPERS.getOrDefault(id, UnaryOperator.identity());
            WRAPPERS.put(id, layer -> Objects.requireNonNull(wrapper.apply(before.apply(layer)), "wrapping layer must not be null"));
        } else if (MOD_LAYERS.containsKey(id)) {
            MOD_LAYERS.put(id, Objects.requireNonNull(wrapper.apply(MOD_LAYERS.get(id)), "wrapping layer must not be null"));
        } else {
            throw new IllegalArgumentException("Attempted to wrap layer with id '" + id + "', which does not exist!");
        }
    }

    HudElement add(Identifier id, GuiLayer layer) {
        Objects.requireNonNull(id);
        if (FABRIC.containsKey(id) || MOD_LAYERS.containsKey(id) || id.equals(VanillaGuiLayers.AFTER_CAMERA_DECORATIONS) || id.equals(VanillaGuiLayers.CONTEXTUAL_INFO_BAR)) {
            throw new IllegalArgumentException("Layer already registered: " + id);
        }
        MOD_LAYERS.put(id, Objects.requireNonNull(layer));
        return (graphics, deltaTracker) -> draw(graphics, deltaTracker, id, MOD_LAYERS.get(id));
    }

    static void known(Identifier other) {
        boolean vanilla = FABRIC.containsKey(other) || other.equals(VanillaGuiLayers.AFTER_CAMERA_DECORATIONS) || other.equals(VanillaGuiLayers.CONTEXTUAL_INFO_BAR);
        if (!vanilla && !MOD_LAYERS.containsKey(other)) {
            throw new IllegalArgumentException("Attempted to order against an unregistered layer " + other + ". Only order against vanilla's and your own.");
        }
    }
}
