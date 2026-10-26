"""Port rules learned from Stonecutter switches (coverage/stonecutter), written into knowledge/fixes.json.

Each rule here is a change that two or more real mods made the same way on the same update, read from
their `//? if` switches. The rule keeps their shape: same call, same arguments, nothing dropped. A rule
is attached to the single update its switches confirm, and gets a backport twin where the older side
can be restored without guessing. Every rule carries:

  examples  the switches it came from (mod, file, line), checked against coverage/stonecutter/switches
  test      one compiler error it answers, and the source before and after the rewrite

    python bench/stonecutter_rules.py          # rewrite the source=stonecutter rules in knowledge/fixes.json

PortableTests (modaccel self-test) re-checks every example, match, rewrite and jump in Java, with the
same code Port uses.
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIXES = ROOT / "knowledge" / "fixes.json"
SWITCHES = ROOT / "coverage" / "stonecutter" / "switches"

MC = r"(Minecraft\.getInstance\(\)|\b(?:this\.)?(?:minecraft|mc|client))"
LOC_MC = r"\s+location: (?:variable \w+ of type|class) (?:net\.minecraft\.client\.)?Minecraft\b"
LOC_PROFILE = r"\s+location: (?:variable \w+ of type|class) (?:com\.mojang\.authlib\.)?GameProfile\b"
ARG = r"(?:[^;(),]|\((?:[^;()]|\([^;()]*\))*\))+?"
ARGS = r"((?:[^;()]|\((?:[^;()]|\([^;()]*\))*\))*?)"


def err(file, line, msg, src, symbol=None, location=None):
    text = f"{file}:{line}: error: {msg}\n        {src}\n        ^\n"
    if symbol:
        text += f"  symbol:   {symbol}\n"
    if location:
        text += f"  location: {location}\n"
    return text


RULES = [
    # ---- 1.21.10 -> 1.21.11: permission levels became permission sets --------------------------------
    dict(id="sc-permissions-source-level", jump="1.21.10 -> 1.21.11 (mojmap)",
         match=r"symbol:\s+method hasPermission\(int\)",
         find=r"\.hasPermission\((\d+)\)",
         replace=".permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId($1)))",
         imports=["net.minecraft.server.permissions.Permission", "net.minecraft.server.permissions.PermissionLevel"],
         note="1.21.11 removed CommandSourceStack.hasPermission(int); the level is now a check against permissions(). The number keeps its meaning through PermissionLevel.byId, as Neruina writes it.",
         examples=[("NikitaCartes/EasyAuth", "EasyAuthPermissions.java", 63), ("TeamMidnightDust/MidnightLib", "AutoCommand.java", 48), ("Bawnorton/Neruina", "PermissionWrapper.java", 12)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "return source.hasPermission(2);", "method hasPermission(int)", "variable source of type CommandSourceStack"),
                   before="package a;\nclass C { boolean f(CommandSourceStack source) { return source.hasPermission(2); } }\n")),
    dict(id="sc-permissions-player-level", jump="1.21.10 -> 1.21.11 (mojmap)",
         match=r"symbol:\s+method hasPermissions\(int\)",
         find=r"\.hasPermissions\((\d+)\)",
         replace=".permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId($1)))",
         imports=["net.minecraft.server.permissions.Permission", "net.minecraft.server.permissions.PermissionLevel"],
         note="1.21.11 removed Player.hasPermissions(int); players answer the same check through permissions().",
         examples=[("SunkenInTime/diorama", "WaypointListScreen.java", 228), ("Bawnorton/Neruina", "PermissionWrapper.java", 12)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "if (player.hasPermissions(2)) {", "method hasPermissions(int)", "variable player of type ServerPlayer"),
                   before="package a;\nclass C { void f(ServerPlayer player) { if (player.hasPermissions(2)) { } } }\n")),

    # ---- 1.21.8 -> 1.21.9: authlib's GameProfile became a record ----------------------------------------
    dict(id="sc-gameprofile-name", jump="1.21.8 -> 1.21.9 (mojmap)",
         match=r"symbol:\s+method getName\(\)" + LOC_PROFILE,
         find=r"(\b\w*[Pp]rofile(?:\(\))?)\.getName\(\)", replace="$1.name()",
         note="1.21.9 ships authlib with GameProfile as a record: getName() is name().",
         examples=[("Skidamek/AutoModpack", "GameHelpers.java", 55), ("NikitaCartes/EasyAuth", "StoneCutterUtils.java", 127), ("mrbuilder1961/ChatPatches", "Config.java", 295)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "log(player.getGameProfile().getName());", "method getName()", "class GameProfile"),
                   before="class C { void f(ServerPlayer player, GameProfile profile) { log(player.getGameProfile().getName()); log(profile.getName()); } }\n")),
    dict(id="sc-gameprofile-id", jump="1.21.8 -> 1.21.9 (mojmap)",
         match=r"symbol:\s+method getId\(\)" + LOC_PROFILE,
         find=r"(\b\w*[Pp]rofile(?:\(\))?)\.getId\(\)", replace="$1.id()",
         note="1.21.9 ships authlib with GameProfile as a record: getId() is id().",
         examples=[("Skidamek/AutoModpack", "GameHelpers.java", 63), ("NikitaCartes/EasyAuth", "StoneCutterUtils.java", 145), ("Bawnorton/Neruina", "NeruinaCommandHandler.java", 331)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "UUID id = profile.getId();", "method getId()", "variable profile of type GameProfile"),
                   before="class C { UUID f(GameProfile profile) { return profile.getId(); } }\n")),
    dict(id="sc-gameprofile-name-back", jump="1.21.9 -> 1.21.8 (mojmap)",
         match=r"symbol:\s+method name\(\)" + LOC_PROFILE,
         find=r"(\b\w*[Pp]rofile(?:\(\))?)\.name\(\)", replace="$1.getName()",
         note="Backport of sc-gameprofile-name: before 1.21.9 GameProfile is a class with getName().",
         examples=[("Skidamek/AutoModpack", "GameHelpers.java", 55), ("NikitaCartes/EasyAuth", "StoneCutterUtils.java", 127)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "return profile.name();", "method name()", "variable profile of type GameProfile"),
                   before="class C { String f(GameProfile profile) { return profile.name(); } }\n")),
    dict(id="sc-gameprofile-id-back", jump="1.21.9 -> 1.21.8 (mojmap)",
         match=r"symbol:\s+method id\(\)" + LOC_PROFILE,
         find=r"(\b\w*[Pp]rofile(?:\(\))?)\.id\(\)", replace="$1.getId()",
         note="Backport of sc-gameprofile-id: before 1.21.9 GameProfile is a class with getId().",
         examples=[("Skidamek/AutoModpack", "GameHelpers.java", 63), ("NikitaCartes/EasyAuth", "StoneCutterUtils.java", 145)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "return profile.id();", "method id()", "variable profile of type GameProfile"),
                   before="class C { UUID f(GameProfile profile) { return profile.id(); } }\n")),

    # ---- 26.1.2 -> 26.2: Minecraft hands screens, overlays and toasts to gui ----------------------------
    dict(id="sc-gui-set-screen", jump="26.1.2 -> 26.2 (mojmap)",
         match=r"symbol:\s+method setScreen\([^)]*\)" + LOC_MC,
         find=MC + r"\.setScreen\(", replace="$1.gui.setScreen(",
         note="26.2 moved screen handling from Minecraft to its gui field: minecraft.setScreen(s) is minecraft.gui.setScreen(s).",
         examples=[("isXander/Controlify", "MinecraftUtil.java", 26), ("isXander/YetAnotherConfigLib", "GuiUtils.java", 60), ("NikitaCartes/EasyAuth", "ConfigScreen.java", 55), ("Skidamek/AutoModpack", "ScreenImpl.java", 259)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "Minecraft.getInstance().setScreen(screen);", "method setScreen(Screen)", "class Minecraft"),
                   before="class C { void f(Screen screen) { Minecraft.getInstance().setScreen(screen); this.minecraft.setScreen(null); } }\n")),
    dict(id="sc-gui-screen", jump="26.1.2 -> 26.2 (mojmap)",
         match=r"symbol:\s+variable screen" + LOC_MC,
         find=MC + r"\.screen\b(?!\s*=[^=])", replace="$1.gui.screen()",
         note="26.2 reads the open screen through gui.screen(). Assignments to the old field are left for a human: the mods that wrote it went through an accessor of their own.",
         examples=[("isXander/YetAnotherConfigLib", "GuiUtils.java", 73), ("Leawind/Third-Person", "Bridge.java", 44), ("Mirsario/Minecraft-CameraOverhaul", "ConfigScreen.java", 24), ("Skidamek/AutoModpack", "ScreenImpl.java", 226)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "return minecraft.screen != null;", "variable screen", "variable minecraft of type Minecraft"),
                   before="class C { boolean f() { return minecraft.screen != null; } void g(Screen s) { minecraft.screen = s; } }\n")),
    dict(id="sc-gui-overlay", jump="26.1.2 -> 26.2 (mojmap)",
         match=r"symbol:\s+method getOverlay\(\)" + LOC_MC,
         find=MC + r"\.getOverlay\(\)", replace="$1.gui.overlay()",
         note="26.2: the loading overlay belongs to gui.",
         examples=[("SunkenInTime/diorama", "GameShim.java", 52), ("isXander/Controlify", "MinecraftUtil.java", 52), ("Skidamek/AutoModpack", "AutoTestBridge.java", 387)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "boolean loaded = minecraft.getOverlay() == null;", "method getOverlay()", "variable minecraft of type Minecraft"),
                   before="class C { boolean f() { return minecraft.getOverlay() == null; } }\n")),
    dict(id="sc-gui-toast-manager", jump="26.1.2 -> 26.2 (mojmap)",
         match=r"symbol:\s+method getToastManager\(\)" + LOC_MC,
         find=MC + r"\.getToastManager\(\)", replace="$1.gui.toastManager()",
         note="26.2: toasts belong to gui.",
         examples=[("isXander/Controlify", "CTestUtil.java", 155), ("The-Fireplace-Minecraft-Mods/In-Game-Account-Switcher", "IASMinecraft.java", 284), ("mrbuilder1961/ChatPatches", "ChatPatches.java", 209)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "minecraft.getToastManager().clear();", "method getToastManager()", "variable minecraft of type Minecraft"),
                   before="class C { void f() { minecraft.getToastManager().clear(); } }\n")),
    dict(id="sc-main-render-target", jump="26.1.2 -> 26.2 (mojmap)",
         match=r"symbol:\s+method getMainRenderTarget\(\)" + LOC_MC,
         find=MC + r"\.getMainRenderTarget\(\)", replace="$1.gameRenderer.mainRenderTarget()",
         note="26.2: the main render target belongs to the game renderer.",
         examples=[("SunkenInTime/diorama", "ShimContext.java", 154), ("isXander/Controlify", "InGameInputHandler.java", 242), ("Skidamek/AutoModpack", "AutoTestBridge.java", 327)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "RenderTarget target = minecraft.getMainRenderTarget();", "method getMainRenderTarget()", "variable minecraft of type Minecraft"),
                   before="class C { RenderTarget f() { return minecraft.getMainRenderTarget(); } }\n")),
    dict(id="sc-gui-set-screen-back", jump="26.2 -> 26.1.2 (mojmap)",
         match=r"symbol:\s+method setScreen\([^)]*\)\s+location: variable gui of type Gui\b",
         find=r"\.gui\.setScreen\(", replace=".setScreen(",
         note="Backport of sc-gui-set-screen: before 26.2 gui is the HUD and Minecraft opens screens itself.",
         examples=[("isXander/Controlify", "MinecraftUtil.java", 26), ("isXander/YetAnotherConfigLib", "GuiUtils.java", 60)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "minecraft.gui.setScreen(screen);", "method setScreen(Screen)", "variable gui of type Gui"),
                   before="class C { void f(Screen screen) { minecraft.gui.setScreen(screen); } }\n")),
    dict(id="sc-gui-screen-back", jump="26.2 -> 26.1.2 (mojmap)",
         match=r"symbol:\s+method screen\(\)\s+location: variable gui of type Gui\b",
         find=r"\.gui\.screen\(\)", replace=".screen",
         note="Backport of sc-gui-screen.",
         examples=[("isXander/YetAnotherConfigLib", "GuiUtils.java", 73), ("Leawind/Third-Person", "Bridge.java", 44)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "return minecraft.gui.screen() != null;", "method screen()", "variable gui of type Gui"),
                   before="class C { boolean f() { return minecraft.gui.screen() != null; } }\n")),
    dict(id="sc-gui-overlay-back", jump="26.2 -> 26.1.2 (mojmap)",
         match=r"symbol:\s+method overlay\(\)\s+location: variable gui of type Gui\b",
         find=r"\.gui\.overlay\(\)", replace=".getOverlay()",
         note="Backport of sc-gui-overlay.",
         examples=[("SunkenInTime/diorama", "GameShim.java", 52), ("isXander/Controlify", "MinecraftUtil.java", 52)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "return minecraft.gui.overlay();", "method overlay()", "variable gui of type Gui"),
                   before="class C { Overlay f() { return minecraft.gui.overlay(); } }\n")),
    dict(id="sc-gui-toast-manager-back", jump="26.2 -> 26.1.2 (mojmap)",
         match=r"symbol:\s+method toastManager\(\)\s+location: variable gui of type Gui\b",
         find=r"\.gui\.toastManager\(\)", replace=".getToastManager()",
         note="Backport of sc-gui-toast-manager.",
         examples=[("isXander/Controlify", "CTestUtil.java", 155), ("The-Fireplace-Minecraft-Mods/In-Game-Account-Switcher", "IASMinecraft.java", 284)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "minecraft.gui.toastManager().clear();", "method toastManager()", "variable gui of type Gui"),
                   before="class C { void f() { minecraft.gui.toastManager().clear(); } }\n")),
    dict(id="sc-main-render-target-back", jump="26.2 -> 26.1.2 (mojmap)",
         match=r"symbol:\s+method mainRenderTarget\(\)\s+location: variable gameRenderer of type GameRenderer\b",
         find=r"\.gameRenderer\.mainRenderTarget\(\)", replace=".getMainRenderTarget()",
         note="Backport of sc-main-render-target.",
         examples=[("SunkenInTime/diorama", "ShimContext.java", 154), ("isXander/Controlify", "InGameInputHandler.java", 242)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "return minecraft.gameRenderer.mainRenderTarget();", "method mainRenderTarget()", "variable gameRenderer of type GameRenderer"),
                   before="class C { RenderTarget f() { return minecraft.gameRenderer.mainRenderTarget(); } }\n")),

    # ---- 26.1.2 -> 26.2: entity type constants moved to EntityTypes ------------------------------------
    dict(id="sc-entity-types-holder", jump="26.1.2 -> 26.2 (mojmap)",
         match=r"symbol:\s+variable [A-Z][A-Z0-9_]*\s+location: (?:class|interface) (?:net\.minecraft\.world\.entity\.)?EntityType\b",
         find=r"\bEntityType\.([A-Z][A-Z0-9_]*)\b", replace="EntityTypes.$1",
         imports=["net.minecraft.world.entity.EntityTypes"],
         note="26.2 moved the vanilla entity type constants from EntityType to a new EntityTypes holder; the type itself stays EntityType. Only all-capitals names move, so EntityType.Builder is left alone.",
         examples=[("Faboslav/friends-and-foes", "VersionedEntityType.java", 24), ("SunkenInTime/diorama", "MobFaces.java", 147)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "Zombie zombie = EntityType.ZOMBIE.create(level);", "variable ZOMBIE", "class EntityType"),
                   before="package a;\nclass C { EntityType<?> t = EntityType.ZOMBIE; EntityType.Builder<?> b; boolean f(EntityType<?> x) { return x == EntityType.ZOMBIE_HORSE; } }\n")),
    dict(id="sc-entity-types-holder-back", jump="26.2 -> 26.1.2 (mojmap)",
         match=r"symbol:\s+class EntityTypes\b|cannot find symbol\s+import net\.minecraft\.world\.entity\.EntityTypes;",
         find=r"\bEntityTypes\b", replace="EntityType",
         note="Backport of sc-entity-types-holder: before 26.2 the constants live on EntityType (the import line becomes a harmless duplicate).",
         examples=[("Faboslav/friends-and-foes", "VersionedEntityType.java", 24), ("SunkenInTime/diorama", "MobFaces.java", 147)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "EntityType<?> t = EntityTypes.ZOMBIE;", "class EntityTypes", "class C"),
                   before="class C { EntityType<?> t = EntityTypes.ZOMBIE; }\n")),

    # ---- 1.21.11 -> 26.1 --------------------------------------------------------------------------------
    dict(id="sc-chat-client-system-message", jump="1.21.11 -> 26.1 (mojmap)",
         match=r"symbol:\s+method addMessage\(Component\)",
         find=r"(\.getChat\(\)|\.chat)\.addMessage\(", replace="$1.addClientSystemMessage(",
         note="26.1 removed ChatComponent.addMessage(Component); a message the client shows itself is addClientSystemMessage.",
         examples=[("mrbuilder1961/ChatPatches", "Config.java", 373), ("Polyfrost/OverflowAnimationsV2", "PresetsScreen.kt", 70)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "mc.gui.getChat().addMessage(text);", "method addMessage(Component)", "class ChatComponent"),
                   before="class C { void f(Component text) { mc.gui.getChat().addMessage(text); } }\n")),
    dict(id="sc-chat-client-system-message-back", jump="26.1 -> 1.21.11 (mojmap)",
         match=r"symbol:\s+method addClientSystemMessage\(Component\)",
         find=r"(\.getChat\(\)|\.chat)\.addClientSystemMessage\(", replace="$1.addMessage(",
         note="Backport of sc-chat-client-system-message.",
         examples=[("mrbuilder1961/ChatPatches", "Config.java", 373), ("Polyfrost/OverflowAnimationsV2", "PresetsScreen.kt", 70)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "mc.gui.getChat().addClientSystemMessage(text);", "method addClientSystemMessage(Component)", "class ChatComponent"),
                   before="class C { void f(Component text) { mc.gui.getChat().addClientSystemMessage(text); } }\n")),
    dict(id="sc-player-system-message", jump="1.21.11 -> 26.1 (mojmap)",
         match=r"symbol:\s+method displayClientMessage\(Component,boolean\)",
         find=r"\.displayClientMessage\(" + ARGS + r"\s*,\s*false\s*\)", replace=".sendSystemMessage($1)",
         note="26.1 removed Player.displayClientMessage. A chat-line message (overlay false) is sendSystemMessage(message); action-bar calls (overlay true) are left for a human because the switches show no shared answer.",
         examples=[("Bawnorton/Neruina", "MessageHandler.java", 71), ("Skidamek/AutoModpack", "GameHelpers.java", 79)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "player.displayClientMessage(pad ? Texter.pad(message) : message, false);", "method displayClientMessage(Component,boolean)", "variable player of type ServerPlayer"),
                   before="class C { void f() { player.displayClientMessage(pad ? Texter.pad(message) : message, false); player.displayClientMessage(bar, true); } }\n")),
    dict(id="sc-graphics-draw-string-text", jump="1.21.11 -> 26.1 (mojmap)",
         match=r"symbol:\s+method drawString\(Font,",
         find=r"\.drawString\(", replace=".text(", when=r"\bGuiGraphics",
         note="26.1 renamed GuiGraphics.drawString to text, with the same five- and six-argument forms.",
         examples=[("Skidamek/AutoModpack", "VersionedScreen.java", 254), ("NikitaCartes/EasyAuth", "ConfigScreen.java", 194), ("Polyfrost/OverflowAnimationsV2", "MixinPlayerTabOverlay_TabListStyle.java", 175), ("North-West-Wind/AutoFish", "SuperFilterScreen.java", 181)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "graphics.drawString(font, text, x, y, color);", "method drawString(Font,String,int,int,int)", "variable graphics of type GuiGraphicsExtractor"),
                   before="class C { void f(GuiGraphicsExtractor graphics) { graphics.drawString(font, text, x, y, color); graphics.drawString(font, text, x, y, color, true); } }\n")),
    dict(id="sc-widget-extract-render-state", jump="1.21.11 -> 26.1 (mojmap)",
         match=r"method does not override or implement a method from a supertype|symbol:\s+method render\(GuiGraphics(?:Extractor)?,int,int,float\)",
         find=r"\b(public|protected) void render\(GuiGraphics(?:Extractor)? (\w+), int (\w+), int (\w+), float (\w+)\)",
         replace="$1 void extractRenderState(GuiGraphicsExtractor $2, int $3, int $4, float $5)",
         when=r"void render\(GuiGraphics(?:Extractor)? \w+, int \w+, int \w+, float \w+\)",
         imports=["net.minecraft.client.gui.GuiGraphicsExtractor"],
         note="26.1 renamed Renderable.render(graphics, mouseX, mouseY, partialTick) to extractRenderState with a GuiGraphicsExtractor; an override keeps its body and parameter names.",
         examples=[("North-West-Wind/AutoFish", "FilterSelectionScreen.java", 166), ("Skidamek/AutoModpack", "VersionedScreen.java", 122), ("Polyfrost/OverflowAnimationsV2", "PresetsScreen.kt", 145)],
         test=dict(error=err("C.java", 3, "method does not override or implement a method from a supertype", "@Override"),
                   before="package a;\nclass C extends Screen {\n    @Override\n    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {\n        super.render(graphics, mouseX, mouseY, partialTicks);\n    }\n}\n")),
    dict(id="sc-widget-super-extract-render-state", jump="1.21.11 -> 26.1 (mojmap)",
         match=r"symbol:\s+method render\(GuiGraphics(?:Extractor)?,int,int,float\)",
         find=r"\bsuper\.render\((\w+), (\w+), (\w+), (\w+)\)", replace="super.extractRenderState($1, $2, $3, $4)",
         note="The super call inside an override renamed by sc-widget-extract-render-state.",
         examples=[("North-West-Wind/AutoFish", "FilterSelectionScreen.java", 166), ("Polyfrost/OverflowAnimationsV2", "PresetsScreen.kt", 145), ("Skidamek/AutoModpack", "VersionedScreen.java", 122)],
         test=dict(error=err("C.java", 5, "cannot find symbol", "super.render(graphics, mouseX, mouseY, partialTicks);", "method render(GuiGraphicsExtractor,int,int,float)", "class Screen"),
                   before="class C extends Screen { public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) { super.render(graphics, mouseX, mouseY, partialTicks); } }\n")),
    dict(id="sc-fabric-key-mapping-helper", jump="1.21.11 -> 26.1 (fabric-api)",
         match=r"package net\.fabricmc\.fabric\.api\.client\.keybinding\.v1 does not exist|symbol:\s+variable KeyBindingHelper",
         find=r"\bKeyBindingHelper\.registerKeyBinding\(", replace="KeyMappingHelper.registerKeyMapping(",
         imports=["net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper"],
         note="Fabric API for 26.1 renamed KeyBindingHelper.registerKeyBinding to KeyMappingHelper.registerKeyMapping (package keymapping.v1). Same call, same argument; any names.",
         examples=[("North-West-Wind/AutoFish", "AutoFishFabric.java", 16), ("Zolo101/RenderScale", "FabricClientEntrypoint.java", 12), ("Polyfrost/OverflowAnimationsV2", "OverflowAnimationsKeybinds.kt", 30)],
         test=dict(error=err("C.java", 1, "package net.fabricmc.fabric.api.client.keybinding.v1 does not exist", "import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;"),
                   before="package a;\nimport net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;\nclass C { void f() { KeyBindingHelper.registerKeyBinding(KEY); } }\n")),
    dict(id="sc-fabric-key-mapping-import", jump="1.21.11 -> 26.1 (fabric-api)",
         match=r"package net\.fabricmc\.fabric\.api\.client\.keybinding\.v1 does not exist",
         find=r"(?m)^import net\.fabricmc\.fabric\.api\.client\.keybinding\.v1\.KeyBindingHelper;\r?\n", replace="",
         note="Drops the old import line once sc-fabric-key-mapping-helper has added the new one.",
         examples=[("Zolo101/RenderScale", "FabricClientEntrypoint.java", 12), ("Polyfrost/OverflowAnimationsV2", "OverflowAnimationsKeybinds.kt", 30)],
         test=dict(error=err("C.java", 1, "package net.fabricmc.fabric.api.client.keybinding.v1 does not exist", "import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;"),
                   before="package a;\nimport net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;\nimport net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;\nclass C { }\n")),
    dict(id="sc-fabric-key-mapping-helper-back", jump="26.1 -> 1.21.11 (fabric-api)",
         match=r"package net\.fabricmc\.fabric\.api\.client\.keymapping\.v1 does not exist|symbol:\s+variable KeyMappingHelper",
         find=r"\bKeyMappingHelper\.registerKeyMapping\(", replace="KeyBindingHelper.registerKeyBinding(",
         imports=["net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper"],
         note="Backport of sc-fabric-key-mapping-helper.",
         examples=[("North-West-Wind/AutoFish", "AutoFishFabric.java", 16), ("Zolo101/RenderScale", "FabricClientEntrypoint.java", 12)],
         test=dict(error=err("C.java", 1, "package net.fabricmc.fabric.api.client.keymapping.v1 does not exist", "import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;"),
                   before="package a;\nimport net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;\nclass C { void f() { KeyMappingHelper.registerKeyMapping(KEY); } }\n")),
    dict(id="sc-fabric-key-mapping-import-back", jump="26.1 -> 1.21.11 (fabric-api)",
         match=r"package net\.fabricmc\.fabric\.api\.client\.keymapping\.v1 does not exist",
         find=r"(?m)^import net\.fabricmc\.fabric\.api\.client\.keymapping\.v1\.KeyMappingHelper;\r?\n", replace="",
         note="Backport of sc-fabric-key-mapping-import.",
         examples=[("Zolo101/RenderScale", "FabricClientEntrypoint.java", 12), ("Polyfrost/OverflowAnimationsV2", "OverflowAnimationsKeybinds.kt", 30)],
         test=dict(error=err("C.java", 1, "package net.fabricmc.fabric.api.client.keymapping.v1 does not exist", "import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;"),
                   before="package a;\nimport net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;\nimport net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;\nclass C { }\n")),

    # ---- 26.2 -> 26.3: PoseStack rotations -------------------------------------------------------------
    dict(id="sc-posestack-rotate-degrees", jump="26.2 -> 26.3 (mojmap)",
         match=r"symbol:\s+method mulPose\(",
         find=r"\.mulPose\(\s*([\w.]+)\.rotationDegrees\(" + ARGS + r"\)\s*\)", replace=".rotateDegrees($1, $2)",
         note="26.3 dropped PoseStack.mulPose; a rotation by degrees about an axis is rotateDegrees(axis, degrees).",
         examples=[("FemaleGenderMod/FemaleGenderMod", "GenderLayer.java", 201), ("Polyfrost/OverflowAnimationsV2", "FirstPersonUtil.kt", 39)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "matrixStack.mulPose(Axis.YP.rotationDegrees(side.forSide(l, r)));", "method mulPose(Quaternionf)", "variable matrixStack of type PoseStack"),
                   before="class C { void f() { matrixStack.mulPose(Axis.YP.rotationDegrees(side.forSide(l, r))); } }\n")),
    dict(id="sc-posestack-rotate-degrees-back", jump="26.3 -> 26.2 (mojmap)",
         match=r"symbol:\s+method rotateDegrees\(",
         find=r"\.rotateDegrees\(\s*([\w.]+)\s*,\s*" + ARGS + r"\)(?=\s*;)", replace=".mulPose($1.rotationDegrees($2))",
         note="Backport of sc-posestack-rotate-degrees.",
         examples=[("FemaleGenderMod/FemaleGenderMod", "GenderLayer.java", 201), ("Polyfrost/OverflowAnimationsV2", "FirstPersonUtil.kt", 39)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "matrixStack.rotateDegrees(Axis.YP, side.forSide(l, r));", "method rotateDegrees(Axis,float)", "variable matrixStack of type PoseStack"),
                   before="class C { void f() { matrixStack.rotateDegrees(Axis.YP, side.forSide(l, r)); } }\n")),

    # ---- 1.21.1 -> 1.21.2 and 1.21.5 -> 1.21.6: the GUI texture pipeline --------------------------------
    dict(id="sc-blit-render-type", jump="1.21.1 -> 1.21.2 (mojmap)",
         match=r"no suitable method found for blit\((?:ResourceLocation|Identifier),int,int,(?:int|float),(?:int|float),int,int,int,int\)",
         find=r"\.blit\(\s*(?!RenderType|RenderPipelines|net\.minecraft)(" + ARG + r")\s*," + (r"\s*(" + ARG + r")\s*,") * 7 + r"\s*(" + ARG + r")\s*\)",
         replace=".blit(RenderType::guiTextured, $1, $2, $3, $4, $5, $6, $7, $8, $9)",
         imports=["net.minecraft.client.renderer.RenderType"],
         note="1.21.2 gave GuiGraphics.blit a render type first: the nine-argument texture call (texture, x, y, u, v, width, height, texture width, texture height) gains RenderType::guiTextured and keeps every argument.",
         examples=[("SunkenInTime/diorama", "SettingsScreen.java", 337), ("SunkenInTime/diorama", "ScreenArt.java", 44), ("Skidamek/AutoModpack", "VersionedScreen.java", 704)],
         test=dict(error=err("C.java", 3, "no suitable method found for blit(ResourceLocation,int,int,int,int,int,int,int,int)", "graphics.blit(LIST_BACKGROUND, 0, TAB_BAR_HEIGHT, 0, 0, this.side, this.listBottom() - TAB_BAR_HEIGHT, 32, 32);"),
                   before="package a;\nclass C { void f() { graphics.blit(LIST_BACKGROUND, 0, TAB_BAR_HEIGHT, 0, 0, this.side, this.listBottom() - TAB_BAR_HEIGHT, 32, 32); graphics.blit(TEX, x, y, 0, 0, w, h); } }\n")),
    dict(id="sc-blit-render-type-back", jump="1.21.2 -> 1.21.1 (mojmap)",
         match=r"guiTextured",
         find=r"\.(blit|blitSprite)\(\s*(?:net\.minecraft\.client\.renderer\.)?RenderType::guiTextured\s*,\s*", replace=".$1(",
         note="Backport of sc-blit-render-type: before 1.21.2 blit takes the texture first.",
         examples=[("SunkenInTime/diorama", "MapScreen.java", 784), ("Skidamek/AutoModpack", "VersionedScreen.java", 704)],
         test=dict(error=err("C.java", 3, "invalid method reference", "graphics.blitSprite(RenderType::guiTextured, sprite, x, y, w, h);", "method guiTextured(ResourceLocation)", "class RenderType"),
                   before="class C { void f() { graphics.blitSprite(RenderType::guiTextured, sprite, x, y, w, h); graphics.blit(net.minecraft.client.renderer.RenderType::guiTextured, TEX, x, y, 0, 0, w, h, 32, 32); } }\n")),
    dict(id="sc-gui-textured-pipeline", jump="1.21.5 -> 1.21.6 (mojmap)",
         match=r"guiTextured",
         find=r"(?:net\.minecraft\.client\.renderer\.)?RenderType::guiTextured\b", replace="RenderPipelines.GUI_TEXTURED",
         imports=["net.minecraft.client.renderer.RenderPipelines"],
         note="1.21.6 draws GUI textures with a render pipeline: RenderType::guiTextured is RenderPipelines.GUI_TEXTURED in blit and blitSprite, every other argument unchanged.",
         examples=[("SunkenInTime/diorama", "MapScreen.java", 784), ("Skidamek/AutoModpack", "VersionedScreen.java", 704), ("TeamMidnightDust/MidnightLib", "MidnightConfigListWidget.java", 36), ("DeDiamondPro/Resourcify", "ImageButton.kt", 67)],
         test=dict(error=err("C.java", 3, "invalid method reference", "graphics.blitSprite(RenderType::guiTextured, sprite, x, y, w, h);", "method guiTextured(ResourceLocation)", "class RenderType"),
                   before="package a;\nclass C { void f() { graphics.blitSprite(RenderType::guiTextured, sprite, x, y, w, h); graphics.blit(net.minecraft.client.renderer.RenderType::guiTextured, TEX, x, y, 0, 0, w, h, 32, 32); } }\n")),
    dict(id="sc-gui-textured-pipeline-back", jump="1.21.6 -> 1.21.5 (mojmap)",
         match=r"no suitable method found for blit(?:Sprite)?\(RenderPipeline,",
         find=r"\bRenderPipelines\.GUI_TEXTURED\b", replace="RenderType::guiTextured",
         imports=["net.minecraft.client.renderer.RenderType"],
         note="Backport of sc-gui-textured-pipeline.",
         examples=[("SunkenInTime/diorama", "MapScreen.java", 784), ("TeamMidnightDust/MidnightLib", "MidnightConfigListWidget.java", 36)],
         test=dict(error=err("C.java", 3, "no suitable method found for blitSprite(RenderPipeline,ResourceLocation,int,int,int,int)", "graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, w, h);"),
                   before="package a;\nclass C { void f() { graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, w, h); } }\n")),

    # ---- 1.21.1 -> 1.21.2: registry lookups --------------------------------------------------------------
    dict(id="sc-registry-lookup-or-throw", jump="1.21.1 -> 1.21.2 (mojmap)",
         match=r"symbol:\s+method registryOrThrow\(",
         find=r"\.registryOrThrow\(", replace=".lookupOrThrow(",
         note="1.21.2 renamed RegistryAccess.registryOrThrow(key) to lookupOrThrow(key); same key, same throwing behaviour.",
         examples=[("Faboslav/friends-and-foes", "FriendsAndFoesStructurePoolElements.java", 18), ("Zolo101/RenderScale", "RenderScaleAutoTest.java", 247)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "var pools = server.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL);", "method registryOrThrow(ResourceKey<Registry<StructureTemplatePool>>)", "interface RegistryAccess"),
                   before="class C { void f() { var pools = server.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL); } }\n")),
    dict(id="sc-registry-lookup-or-throw-back", jump="1.21.2 -> 1.21.1 (mojmap)",
         match=r"symbol:\s+method lookupOrThrow\(",
         find=r"\.lookupOrThrow\(", replace=".registryOrThrow(", when=r"registryAccess\(\)",
         note="Backport of sc-registry-lookup-or-throw.",
         examples=[("Faboslav/friends-and-foes", "FriendsAndFoesStructurePoolElements.java", 18), ("Zolo101/RenderScale", "RenderScaleAutoTest.java", 247)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "var pools = server.registryAccess().lookupOrThrow(Registries.TEMPLATE_POOL);", "method lookupOrThrow(ResourceKey<Registry<StructureTemplatePool>>)", "interface RegistryAccess"),
                   before="class C { void f() { var pools = server.registryAccess().lookupOrThrow(Registries.TEMPLATE_POOL); } }\n")),

    # ---- 1.21.3 -> 1.21.4: scroll areas ------------------------------------------------------------------
    dict(id="sc-scroll-amount", jump="1.21.3 -> 1.21.4 (mojmap)",
         match=r"symbol:\s+method getScrollAmount\(\)",
         find=r"\bgetScrollAmount\(\)", replace="scrollAmount()",
         note="1.21.4 moved selection lists onto AbstractScrollArea, whose getter is scrollAmount().",
         examples=[("Skidamek/AutoModpack", "ChangeBrowserWidget.java", 61), ("TeamMidnightDust/MidnightLib", "MidnightConfigScreen.java", 89)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "double s = list.getScrollAmount();", "method getScrollAmount()", "variable list of type ConfigList"),
                   before="class C { double f() { return list.getScrollAmount() + this.getScrollAmount(); } }\n")),
    dict(id="sc-scroll-amount-back", jump="1.21.4 -> 1.21.3 (mojmap)",
         match=r"symbol:\s+method scrollAmount\(\)",
         find=r"\bscrollAmount\(\)", replace="getScrollAmount()",
         note="Backport of sc-scroll-amount.",
         examples=[("Skidamek/AutoModpack", "ChangeBrowserWidget.java", 61), ("TeamMidnightDust/MidnightLib", "MidnightConfigScreen.java", 89)],
         test=dict(error=err("C.java", 3, "cannot find symbol", "double s = list.scrollAmount();", "method scrollAmount()", "variable list of type ConfigList"),
                   before="class C { double f() { return list.scrollAmount(); } }\n")),

]


def java_replace(pattern, replacement, text):
    """Java Matcher.replaceAll semantics for the patterns used here ($1, ${name})."""
    py = re.sub(r"\$\{(\w+)\}", r"\\g<\1>", replacement)
    py = re.sub(r"\$(\d)", r"\\g<\1>", py)
    return re.sub(pattern, py, text)


def rewrite(rule, text):
    """The same steps as Port.rewrite: when, find/replace, then ensureImports after the package line."""
    if rule.get("when") and not re.search(rule["when"], text):
        return text
    ap = rule["apply"]
    out = java_replace(ap["find"], ap.get("replace", ""), text)
    if out != text:
        for name in ap.get("ensureImports", []):
            if f"import {name};" in out:
                continue
            m = re.search(r"(?m)^package [^;]+;", out)
            out = out[:m.end()] + f"\nimport {name};" + out[m.end():] if m else f"import {name};\n" + out
    return out


def example_row(mod, file, line):
    data = json.loads((SWITCHES / f"{mod.replace('/', '__')}.json").read_text(encoding="utf-8"))
    for row in data["rows"]:
        if row["file"].endswith("/" + file) or row["file"] == file:
            if row["line"] == line:
                return data, row
    raise SystemExit(f"example {mod} {file}:{line} is not in coverage/stonecutter/switches")


def build():
    out = []
    for r in RULES:
        rule = {"id": r["id"], "source": "stonecutter", "jump": r["jump"], "match": r["match"],
                "apply": {"find": r["find"], "replace": r["replace"], **({"ensureImports": r["imports"]} if r.get("imports") else {})},
                "scope": r.get("scope", "log"), "when": r.get("when", ""), "note": r["note"], "verified": None}
        examples, mods = [], set()
        for mod, file, line in r["examples"]:
            data, row = example_row(mod, file, line)
            mods.add(mod)
            examples.append({"mod": mod, "commit": data["commit"][:12], "file": row["file"], "line": row["line"], "condition": row["condition"],
                             "older": row.get("older", "")[:300], "newer": row.get("newer", "")[:300]})
        if len(mods) < 2:
            raise SystemExit(f"{r['id']}: a learned rule needs the same change in two or more mods")
        rule["examples"] = examples
        t = r["test"]
        if not re.search(r["match"], t["error"]):
            raise SystemExit(f"{r['id']}: match does not find its test error")
        after = rewrite(rule, t["before"])
        if after == t["before"]:
            raise SystemExit(f"{r['id']}: the test source is not rewritten")
        rule["test"] = {"error": t["error"], "before": t["before"], "after": after}
        out.append(rule)
    return out


def main():
    fixes = json.loads(FIXES.read_text(encoding="utf-8"))
    keep = [r for r in fixes["rules"] if r.get("source") != "stonecutter"]
    learned = build()
    old = {r["id"]: r for r in fixes["rules"] if r.get("source") == "stonecutter"}
    for r in learned:  # a measured receipt survives regeneration
        if old.get(r["id"], {}).get("verified"):
            r["verified"] = old[r["id"]]["verified"]
    fixes["rules"] = keep + learned
    FIXES.write_text(json.dumps(fixes, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {len(learned)} learned rules ({sum(1 for r in learned if r['id'].endswith('-back'))} backports) into {FIXES}")
    for r in learned:
        print(f"  {r['id']:40} {r['jump']:32} {len({e['mod'] for e in r['examples']})} mods")


if __name__ == "__main__":
    main()
