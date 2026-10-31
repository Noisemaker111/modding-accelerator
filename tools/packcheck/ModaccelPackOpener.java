package modaccel.packprobe;

import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DatapackLoadFailureScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.LoadingErrorScreen;
import net.minecraftforge.common.MinecraftForge;

/**
 * pack-check's client half: opens the named save from the title screen the way the world list does, so the
 * check needs no menu clicks and no quick-play support. Forge's loading screen of warnings is recorded and
 * passed the way a player clicks "Proceed"; one with errors is recorded and the client closes, since a player
 * cannot get past it either. When the game is back at the title screen after the world, the client closes.
 * Property: modaccel.probe.world (the save's folder name).
 */
final class ModaccelPackOpener {
    static boolean opened;

    static void register() { MinecraftForge.EVENT_BUS.addListener(ModaccelPackOpener::screen); }

    static void screen(ScreenEvent.Opening event) {
        String world = System.getProperty("modaccel.probe.world", "");
        // Every screen is named in the log, so a run that stops on an unexpected screen says which one.
        System.out.println("MODACCEL-PROBE-SCREEN " + (event.getNewScreen() == null ? "none" : event.getNewScreen().getClass().getName()));
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getNewScreen() instanceof LoadingErrorScreen loading) {
            List<?> errors = list(loading, "modLoadErrors"), warnings = list(loading, "modLoadWarnings");
            for (Object e : errors) System.out.println("MODACCEL-PROBE-LOAD-ERROR " + text(e));
            for (Object w : warnings) System.out.println("MODACCEL-PROBE-LOAD-WARNING " + text(w));
            if (!errors.isEmpty()) { System.out.println("MODACCEL-PROBE-CLIENT-DONE"); minecraft.tell(minecraft::stop); }
            else event.setNewScreen(new TitleScreen(false));
            if (errors.isEmpty() && !world.isEmpty()) open(minecraft, (TitleScreen) event.getNewScreen(), world);
            return;
        }
        if (event.getNewScreen() instanceof DatapackLoadFailureScreen) {
            // The world's saved settings were rejected: the game offers only Safe Mode (vanilla data) or back.
            System.out.println("MODACCEL-PROBE-WORLD-FAILED datapack load failure screen");
            System.out.println("MODACCEL-PROBE-CLIENT-DONE");
            minecraft.tell(minecraft::stop);
            return;
        }
        if (world.isEmpty() || !(event.getNewScreen() instanceof TitleScreen title)) return;
        if (!opened) open(minecraft, title, world);
        else {
            // Back at the title screen after the world: the result is written, or the world failed to open.
            System.out.println("MODACCEL-PROBE-CLIENT-DONE");
            minecraft.tell(minecraft::stop);
        }
    }

    /** The probe has written its result: close the game, which saves the world and stops its built-in server. */
    static void finished() {
        Minecraft minecraft = Minecraft.getInstance();
        System.out.println("MODACCEL-PROBE-CLIENT-DONE");
        minecraft.execute(minecraft::stop);
    }

    static void open(Minecraft minecraft, TitleScreen title, String world) {
        if (opened) return;
        opened = true;
        System.out.println("MODACCEL-PROBE-OPENING " + world);
        minecraft.tell(() -> minecraft.createWorldOpenFlows().loadLevel(title, world));
    }

    static List<?> list(LoadingErrorScreen screen, String name) {
        try { Field field = LoadingErrorScreen.class.getDeclaredField(name); field.setAccessible(true); return (List<?>) field.get(screen); }
        catch (ReflectiveOperationException | RuntimeException missing) { return List.of("(could not read " + name + ": " + missing + ")"); }
    }

    static String text(Object problem) {
        try { return String.valueOf(problem.getClass().getMethod("formatToString").invoke(problem)).replace('\n', ' '); }
        catch (ReflectiveOperationException | RuntimeException none) { return String.valueOf(problem).replace('\n', ' '); }
    }
}
