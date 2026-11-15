package ${package}.fabric.api.client.rendering.v1.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.GuiLayer;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Fabric API's net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry on NeoForge's GUI layers: each call is
 * kept, in order, and made in RegisterGuiLayersEvent (posted while Minecraft starts, after the client entrypoints):
 * addFirst and addLast are registerBelowAll and registerAboveAll, attachElementBefore and attachElementAfter are
 * registerBelow and registerAbove (Fabric's ids for the game's parts, VanillaHudElements, become NeoForge's), and
 * replaceElement wraps the layer. NeoForge cannot remove a layer, so removeElement replaces it with one that draws
 * nothing. A call after that event (Fabric takes them at any time) throws, as NeoForge's layers are fixed by then.
 */
public interface HudElementRegistry {

    static void addFirst(Identifier id, HudElement element) {
        Objects.requireNonNull(id, "identifier");
        Objects.requireNonNull(element, "hudElement");
        Layers.add(event -> event.registerBelowAll(id, Layers.layer(element)));
    }

    static void addLast(Identifier id, HudElement element) {
        Objects.requireNonNull(id, "identifier");
        Objects.requireNonNull(element, "hudElement");
        Layers.add(event -> event.registerAboveAll(id, Layers.layer(element)));
    }

    static void attachElementBefore(Identifier beforeThis, Identifier identifier, HudElement element) {
        Objects.requireNonNull(beforeThis, "beforeThis");
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(element, "hudElement");
        Layers.add(event -> event.registerBelow(Layers.neoforge(beforeThis), identifier, Layers.layer(element)));
    }

    static void attachElementAfter(Identifier afterThis, Identifier identifier, HudElement element) {
        Objects.requireNonNull(afterThis, "afterThis");
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(element, "hudElement");
        Layers.add(event -> event.registerAbove(Layers.neoforge(afterThis), identifier, Layers.layer(element)));
    }

    static void removeElement(Identifier identifier) {
        Objects.requireNonNull(identifier, "identifier");
        Layers.add(event -> event.replaceLayer(Layers.neoforge(identifier), (graphics, deltaTracker) -> {}));
    }

    static void replaceElement(Identifier identifier, Function<HudElement, HudElement> replacer) {
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(replacer, "replacer");
        Layers.add(event -> event.wrapLayer(Layers.neoforge(identifier), old -> Layers.layer(replacer.apply(old::render))));
    }

    /** The calls, kept until NeoForge's GUI layer event (the bridge's ClientHooks listens for it). */
    final class Layers {
        private static final List<Consumer<RegisterGuiLayersEvent>> PENDING = new ArrayList<>();
        private static boolean registered;

        /** Fabric's ids for the game's HUD parts, as NeoForge names the same layers. */
        private static final Map<Identifier, Identifier> VANILLA = Map.ofEntries(
            Map.entry(VanillaHudElements.MISC_OVERLAYS, VanillaGuiLayers.CAMERA_OVERLAYS),
            Map.entry(VanillaHudElements.CROSSHAIR, VanillaGuiLayers.CROSSHAIR),
            Map.entry(VanillaHudElements.SPECTATOR_MENU, VanillaGuiLayers.HOTBAR),
            Map.entry(VanillaHudElements.HOTBAR, VanillaGuiLayers.HOTBAR),
            Map.entry(VanillaHudElements.ARMOR_BAR, VanillaGuiLayers.ARMOR_LEVEL),
            Map.entry(VanillaHudElements.HEALTH_BAR, VanillaGuiLayers.PLAYER_HEALTH),
            Map.entry(VanillaHudElements.FOOD_BAR, VanillaGuiLayers.FOOD_LEVEL),
            Map.entry(VanillaHudElements.AIR_BAR, VanillaGuiLayers.AIR_LEVEL),
            Map.entry(VanillaHudElements.MOUNT_HEALTH, VanillaGuiLayers.VEHICLE_HEALTH),
            Map.entry(VanillaHudElements.INFO_BAR, VanillaGuiLayers.CONTEXTUAL_INFO_BAR),
            Map.entry(VanillaHudElements.EXPERIENCE_LEVEL, VanillaGuiLayers.EXPERIENCE_LEVEL),
            Map.entry(VanillaHudElements.HELD_ITEM_TOOLTIP, VanillaGuiLayers.SELECTED_ITEM_NAME),
            Map.entry(VanillaHudElements.SPECTATOR_TOOLTIP, VanillaGuiLayers.SPECTATOR_TOOLTIP),
            Map.entry(VanillaHudElements.MOB_EFFECTS, VanillaGuiLayers.EFFECTS),
            Map.entry(VanillaHudElements.BOSS_BAR, VanillaGuiLayers.BOSS_OVERLAY),
            Map.entry(VanillaHudElements.SLEEP, VanillaGuiLayers.SLEEP_OVERLAY),
            Map.entry(VanillaHudElements.DEMO_TIMER, VanillaGuiLayers.DEMO_OVERLAY),
            Map.entry(VanillaHudElements.SCOREBOARD, VanillaGuiLayers.SCOREBOARD_SIDEBAR),
            Map.entry(VanillaHudElements.OVERLAY_MESSAGE, VanillaGuiLayers.OVERLAY_MESSAGE),
            Map.entry(VanillaHudElements.TITLE_AND_SUBTITLE, VanillaGuiLayers.TITLE),
            Map.entry(VanillaHudElements.CHAT, VanillaGuiLayers.CHAT),
            Map.entry(VanillaHudElements.PLAYER_LIST, VanillaGuiLayers.TAB_LIST),
            Map.entry(VanillaHudElements.SUBTITLES, VanillaGuiLayers.SUBTITLE_OVERLAY)
        );

        private Layers() {}

        static synchronized void add(Consumer<RegisterGuiLayersEvent> call) {
            if (registered) throw new IllegalStateException("NeoForge fixed its GUI layers while Minecraft started; register HUD elements in the client entrypoint");
            PENDING.add(call);
        }

        static Identifier neoforge(Identifier fabric) {
            return VANILLA.getOrDefault(fabric, fabric);
        }

        static GuiLayer layer(HudElement element) {
            return element::extractRenderState;
        }

        /** Makes every kept call in NeoForge's event (the bridge's ClientHooks calls this). */
        public static synchronized void register(RegisterGuiLayersEvent event) {
            registered = true;
            for (Consumer<RegisterGuiLayersEvent> call : PENDING) call.accept(event);
            PENDING.clear();
        }
    }
}
