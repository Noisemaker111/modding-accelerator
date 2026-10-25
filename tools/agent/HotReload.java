import java.io.*;
import java.lang.instrument.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Swaps recompiled classes into the running game. At startup it records a hash of every file in
 * the project's own class and resource folders on the game's classpath; a reload rescans them,
 * redefines the changed classes the game has already loaded and reloads resource packs when a
 * resource changed. Classes the game hasn't loaded yet need nothing: the loader reads the new
 * file when they're first used. What the JVM can't swap in place (a changed class shape on a
 * stock JVM, a mixin, the mod's metadata) is reported as needing a restart instead.
 */
final class HotReload {
    private static Instrumentation instrumentation;
    private static final List<Path> folders = new ArrayList<Path>();
    private static final Map<Path, String> known = new HashMap<Path, String>();

    static void install(Instrumentation value) { instrumentation = value; }

    /** Records what the game started with. Runs once, on the first frame. */
    static void baseline() {
        String root = System.getProperty("modaccel.root", "");
        if (root.isEmpty()) return;
        Path project = Paths.get(root).toAbsolutePath().normalize();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (entry.isEmpty()) continue;
            Path path = Paths.get(entry).toAbsolutePath().normalize();
            if (Files.isDirectory(path) && path.startsWith(project)) folders.add(path);
        }
        for (Path folder : folders) known.putAll(scan(folder));
    }

    static Map<String, Object> reload(ClassLoader game) throws Exception {
        if (instrumentation == null) throw new IllegalStateException("The agent has no Instrumentation; reload needs the portable agent from premain");
        long start = System.nanoTime();
        Map<Path, String> now = new HashMap<Path, String>();
        for (Path folder : folders) now.putAll(scan(folder));
        List<Path> changedClasses = new ArrayList<Path>(), changedResources = new ArrayList<Path>(), added = new ArrayList<Path>();
        for (Map.Entry<Path, String> file : now.entrySet()) {
            String before = known.get(file.getKey());
            if (before == null) added.add(file.getKey());
            else if (!before.equals(file.getValue())) (file.getKey().toString().endsWith(".class") ? changedClasses : changedResources).add(file.getKey());
        }
        List<String> restart = new ArrayList<String>();
        for (Path gone : known.keySet()) if (!now.containsKey(gone) && gone.toString().endsWith(".class")) restart.add("removed " + relative(gone));
        for (Path file : added) if (!file.toString().endsWith(".class")) changedResources.add(file);

        Map<String, Class<?>> loaded = new HashMap<String, Class<?>>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (type.isArray() || type.getClassLoader() == null) continue;
            if (sees(game, type.getClassLoader())) loaded.put(type.getName(), type);
        }
        List<ClassDefinition> definitions = new ArrayList<ClassDefinition>();
        List<String> redefined = new ArrayList<String>(), notLoaded = new ArrayList<String>();
        List<Path> applied = new ArrayList<Path>();
        for (Path file : changedClasses) {
            String name = className(file);
            byte[] bytes = Files.readAllBytes(file);
            if (contains(bytes, "Lorg/spongepowered/asm/mixin/Mixin;")) { restart.add("mixin " + name); continue; }
            Class<?> type = loaded.get(name);
            if (type == null) { notLoaded.add(name); applied.add(file); continue; }
            definitions.add(new ClassDefinition(type, bytes)); redefined.add(name); applied.add(file);
        }
        String failure = null;
        if (!definitions.isEmpty()) {
            try { instrumentation.redefineClasses(definitions.toArray(new ClassDefinition[0])); }
            catch (UnsupportedOperationException | ClassFormatError | VerifyError | ClassNotFoundException | UnmodifiableClassException error) {
                // All or nothing: the JVM applies none of the batch when one class can't change.
                failure = error.toString();
                restart.add("class shape changed (" + error.getMessage() + "); a stock JVM swaps method bodies only");
                applied.removeIf(file -> redefined.contains(className(file)));
                redefined.clear();
            }
        }
        for (Path file : added) if (file.toString().endsWith(".class")) { notLoaded.add(className(file)); applied.add(file); }

        boolean metadata = false;
        for (Path file : changedResources) {
            String name = file.getFileName().toString();
            if (name.equals("fabric.mod.json") || name.endsWith(".mixins.json") || name.equals("neoforge.mods.toml") || name.endsWith(".accesswidener")) {
                restart.add("mod metadata " + relative(file)); metadata = true;
            }
        }
        boolean resources = false;
        if (!changedResources.isEmpty() && !metadata) {
            resources = reloadResources(game);
            applied.addAll(changedResources);
        }
        for (Path file : applied) known.put(file, now.get(file));

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("redefined", redefined);
        result.put("loadOnFirstUse", notLoaded);
        result.put("resourcesReloaded", resources);
        result.put("changedResources", changedResources.size());
        result.put("needsRestart", restart);
        if (failure != null) result.put("redefineError", failure);
        result.put("enhancedRedefinition", enhanced());
        result.put("seconds", (System.nanoTime() - start) / 1e9);
        return result;
    }

    private static boolean reloadResources(ClassLoader game) {
        try {
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, game);
            Object client = minecraft.getMethod("getInstance").invoke(null);
            Method reload = minecraft.getMethod("reloadResourcePacks");
            reload.invoke(client);
            return true;
        } catch (Throwable error) {
            System.err.println("MODACCEL_RELOAD_RESOURCES_ERROR " + error);
            return false;
        }
    }

    private static boolean enhanced() {
        for (String argument : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments())
            if (argument.equals("-XX:+AllowEnhancedClassRedefinition")) return true;
        return false;
    }

    private static boolean sees(ClassLoader game, ClassLoader loader) {
        for (ClassLoader current = loader; current != null; current = current.getParent()) if (current == game) return true;
        return false;
    }

    private static String className(Path file) {
        for (Path folder : folders) if (file.startsWith(folder)) {
            String name = folder.relativize(file).toString().replace(File.separatorChar, '.');
            return name.substring(0, name.length() - ".class".length());
        }
        return file.getFileName().toString();
    }

    private static String relative(Path file) {
        for (Path folder : folders) if (file.startsWith(folder)) return folder.relativize(file).toString().replace(File.separatorChar, '/');
        return file.toString();
    }

    private static boolean contains(byte[] bytes, String text) {
        byte[] wanted = text.getBytes(StandardCharsets.UTF_8);
        outer: for (int i = 0; i + wanted.length <= bytes.length; i++) {
            for (int j = 0; j < wanted.length; j++) if (bytes[i + j] != wanted[j]) continue outer;
            return true;
        }
        return false;
    }

    private static Map<Path, String> scan(Path folder) {
        final Map<Path, String> files = new HashMap<Path, String>();
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-1");
            Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                    digest.reset();
                    byte[] hash = digest.digest(Files.readAllBytes(file));
                    StringBuilder text = new StringBuilder();
                    for (byte b : hash) text.append(String.format("%02x", b));
                    files.put(file.toAbsolutePath().normalize(), text.toString());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception error) { System.err.println("MODACCEL_RELOAD_SCAN_ERROR " + folder + " " + error); }
        return files;
    }
}
