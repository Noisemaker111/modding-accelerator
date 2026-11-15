package ${package}.fabric.api.client.rendering.v1.hud;

import net.minecraft.resources.Identifier;

/**
 * Fabric API's net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements: Fabric's ids for the game's HUD
 * parts. HudElementRegistry orders against them as the NeoForge layers that draw the same parts (VanillaGuiLayers:
 * misc_overlays is camera_overlays, health_bar is player_health ...). NeoForge draws the spectator menu in its hotbar
 * layer, so SPECTATOR_MENU orders as the hotbar.
 */
public final class VanillaHudElements {

    public static final Identifier MISC_OVERLAYS = Identifier.withDefaultNamespace("misc_overlays");
    public static final Identifier CROSSHAIR = Identifier.withDefaultNamespace("crosshair");
    public static final Identifier SPECTATOR_MENU = Identifier.withDefaultNamespace("spectator_menu");
    public static final Identifier HOTBAR = Identifier.withDefaultNamespace("hotbar");
    public static final Identifier ARMOR_BAR = Identifier.withDefaultNamespace("armor_bar");
    public static final Identifier HEALTH_BAR = Identifier.withDefaultNamespace("health_bar");
    public static final Identifier FOOD_BAR = Identifier.withDefaultNamespace("food_bar");
    public static final Identifier AIR_BAR = Identifier.withDefaultNamespace("air_bar");
    public static final Identifier MOUNT_HEALTH = Identifier.withDefaultNamespace("mount_health");
    public static final Identifier INFO_BAR = Identifier.withDefaultNamespace("info_bar");
    public static final Identifier EXPERIENCE_LEVEL = Identifier.withDefaultNamespace("experience_level");
    public static final Identifier HELD_ITEM_TOOLTIP = Identifier.withDefaultNamespace("held_item_tooltip");
    public static final Identifier SPECTATOR_TOOLTIP = Identifier.withDefaultNamespace("spectator_tooltip");
    public static final Identifier MOB_EFFECTS = Identifier.withDefaultNamespace("mob_effects");
    public static final Identifier BOSS_BAR = Identifier.withDefaultNamespace("boss_bar");
    public static final Identifier SLEEP = Identifier.withDefaultNamespace("sleep");
    public static final Identifier DEMO_TIMER = Identifier.withDefaultNamespace("demo_timer");
    public static final Identifier SCOREBOARD = Identifier.withDefaultNamespace("scoreboard");
    public static final Identifier OVERLAY_MESSAGE = Identifier.withDefaultNamespace("overlay_message");
    public static final Identifier TITLE_AND_SUBTITLE = Identifier.withDefaultNamespace("title_and_subtitle");
    public static final Identifier CHAT = Identifier.withDefaultNamespace("chat");
    public static final Identifier PLAYER_LIST = Identifier.withDefaultNamespace("player_list");
    public static final Identifier SUBTITLES = Identifier.withDefaultNamespace("subtitles");

    private VanillaHudElements() {}
}
