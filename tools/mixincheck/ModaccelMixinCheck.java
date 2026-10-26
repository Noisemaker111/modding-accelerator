import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * Lets Mixin itself decide whether a mod's mixins apply, without starting the game.
 *
 * modaccel starts this in place of the game's main class, inside the mod's own dev launch (same
 * Java, classpath, loader properties and agents). It sets the loader up the way the loader's own
 * JUnit support does, then, instead of running the game, loads every class the mod's mixin configs
 * target through the game's class loader. Loading a class is what makes Mixin apply its mixins, so
 * every failure is Mixin's own, in its own words. Classes are loaded, not initialised: no game code
 * runs, and nothing opens a window or a world.
 *
 * - Fabric: what fabric-loader-junit does, Knot.init (finds the mods, reads their mixin configs,
 *   installs Mixin), then Knot's class loader.
 * - NeoForge: what junit-fml does, BootstrapLauncher.unitTestingMain with the forgejunituserdev
 *   launch target (NeoForge's unit-test target: no early window), then the game layer's loader.
 *
 * Run with -Dmixin.debug.injectors=true so an injector that matches fewer places than it expects
 * (its {@code expect} count, 1 by default) is reported by Mixin instead of silently doing nothing.
 * The require counts come from Mixin's own injection object, so a result can tell whether the real
 * game would stop on it (fewer than {@code require}) or start with the injection missing.
 *
 * Java 8 syntax: this runs on whatever Java the mod's own game uses.
 * Output: one JSON object per line, appended to the file named by -Dmodaccel.mixincheck.out.
 */
public final class ModaccelMixinCheck {
    static final Set<String> OURS = new HashSet<String>();
    static final String HANDLER = "org.spongepowered.asm.mixin.extensibility.ModaccelMixinErrors";

    public static void main(String[] args) throws Throwable {
        long start = System.nanoTime();
        new FileOutputStream(System.getProperty("modaccel.mixincheck.out")).close();
        for (String name : System.getProperty("modaccel.mixincheck.configs", "").split(",")) if (!name.trim().isEmpty()) OURS.add(name.trim());
        String side = System.getProperty("modaccel.mixincheck.side", "client").toUpperCase(Locale.ROOT);
        String loader = System.getProperty("modaccel.mixincheck.loader", "fabric");
        ClassLoader game;
        try { game = loader.equals("neoforge") ? neoForge(args) : fabric(args, side); }
        catch (Throwable failure) {
            emit("error", "phase", "loader", "message", messages(unwrap(failure)), "exception", unwrap(failure).getClass().getName());
            emit("done", "seconds", seconds(start));
            Runtime.getRuntime().halt(3); return;
        }
        Thread.currentThread().setContextClassLoader(game);
        check(game, side, start);
        // The game is never started; nothing else should run on the way out.
        Runtime.getRuntime().halt(0);
    }

    static ClassLoader fabric(String[] args, String side) throws Throwable {
        Class<?> envType = Class.forName("net.fabricmc.api.EnvType");
        @SuppressWarnings({"unchecked", "rawtypes"}) Object env = Enum.valueOf((Class) envType, side);
        Class<?> knot = Class.forName("net.fabricmc.loader.impl.launch.knot.Knot");
        Object instance = knot.getConstructor(envType).newInstance(env);
        return (ClassLoader) knot.getMethod("init", String[].class).invoke(instance, (Object) args);
    }

    /** junit-fml's LaunchWrapper: the launcher returns once the game layer is built, with its loader as the context loader. */
    static ClassLoader neoForge(String[] args) throws Throwable {
        Class<?> launcher = Class.forName("cpw.mods.bootstraplauncher.BootstrapLauncher");
        launcher.getMethod("unitTestingMain", String[].class).invoke(null, (Object) args);
        ClassLoader game = Thread.currentThread().getContextClassLoader();
        if (game == null || game == ClassLoader.getSystemClassLoader()) throw new IllegalStateException("NeoForge's unit-test launch returned without a game class loader");
        return game;
    }

    static void check(ClassLoader game, String side, long start) throws Throwable {
        Class<?> mixins = Class.forName("org.spongepowered.asm.mixin.Mixins", true, game);
        registerHandler(mixins);
        String mixinVersion = "";
        try { mixinVersion = String.valueOf(Class.forName("org.spongepowered.asm.launch.MixinBootstrap", true, mixins.getClassLoader()).getField("VERSION").get(null)); } catch (Throwable ignored) { }
        emit("start", "side", side.toLowerCase(Locale.ROOT), "mixin", mixinVersion, "java", System.getProperty("java.version"));
        // Mixin moves configs out of its pending set once it selects them, so hold on to them first.
        // Mixin's types are used by reflection only: under NeoForge the game's Mixin lives in a module
        // layer, a different copy of the classes from the one on this class's own class path.
        ClassLoader mixinLoader = mixins.getClassLoader();
        Class<?> configApi = Class.forName("org.spongepowered.asm.mixin.extensibility.IMixinConfig", false, mixinLoader);
        Class<?> mixinApi = Class.forName("org.spongepowered.asm.mixin.extensibility.IMixinInfo", false, mixinLoader);
        Set<Object> configs = new LinkedHashSet<Object>();
        Method getConfig = Class.forName("org.spongepowered.asm.mixin.transformer.Config", false, mixinLoader).getMethod("getConfig");
        for (Object config : (Set<?>) mixins.getMethod("getConfigs").invoke(null)) configs.add(getConfig.invoke(config));
        // Mixin selects and prepares its configs on the first class it transforms. The game's own
        // entry point is in every launch; it is loaded, never run.
        String entry = side.equals("CLIENT") ? "net.minecraft.client.main.Main" : "net.minecraft.server.Main";
        try { Class.forName(entry, false, game); } catch (Throwable failure) { emit("note", "message", "Could not load " + entry + " to start Mixin: " + messages(unwrap(failure))); }
        configs.addAll(selectedConfigs(mixinLoader, configApi));
        Map<String, String> targets = new TreeMap<String, String>();
        for (Object info : configs) {
            String name = (String) configApi.getMethod("getName").invoke(info);
            boolean ours = OURS.isEmpty() || OURS.contains(name);
            @SuppressWarnings("unchecked") Set<String> names = (Set<String>) configApi.getMethod("getTargets").invoke(info);
            emit("config", "name", name, "package", configApi.getMethod("getMixinPackage").invoke(info), "required", configApi.getMethod("isRequired").invoke(info), "targets", names.size(), "ours", ours);
            if (ours) for (String target : names) if (!targets.containsKey(target.replace('/', '.'))) targets.put(target.replace('/', '.'), name);
        }
        for (Map.Entry<String, String> target : targets.entrySet()) {
            String error = null, exception = null;
            try { Class.forName(target.getKey(), false, game); }
            catch (Throwable failure) { Throwable cause = unwrap(failure); error = messages(cause); exception = cause.getClass().getName(); }
            emit("target", "target", target.getKey(), "config", target.getValue(), "loaded", error == null, "message", error, "exception", exception);
        }
        // Every mixin of the mod's configs, and whether Mixin finished applying it: Mixin records that
        // on the mixin's own ClassInfo once it has merged it into a target.
        Method fromCache = Class.forName("org.spongepowered.asm.mixin.transformer.ClassInfo", true, mixinLoader).getMethod("fromCache", String.class);
        for (Object info : configs) {
            String configName = (String) configApi.getMethod("getName").invoke(info);
            if (!OURS.isEmpty() && !OURS.contains(configName)) continue;
            Field list = find(info.getClass(), "mixins");
            if (list == null) { emit("note", "message", "This Mixin version does not list a config's mixins; applied counts are unavailable."); break; }
            list.setAccessible(true);
            for (Object mixin : (Collection<?>) list.get(info)) {
                String className = (String) mixinApi.getMethod("getClassName").invoke(mixin);
                boolean applied = false;
                try {
                    Object classInfo = fromCache.invoke(null, className.replace('.', '/'));
                    applied = classInfo != null && !((Set<?>) classInfo.getClass().getMethod("getAppliedMixins").invoke(classInfo)).isEmpty();
                } catch (Throwable failure) { emit("note", "message", "Could not read whether " + className + " applied: " + messages(unwrap(failure))); }
                emit("mixin", "mixin", className, "config", configName, "targets", mixinApi.getMethod("getTargetClasses").invoke(mixin), "applied", applied);
            }
        }
        emit("done", "seconds", seconds(start), "targets", targets.size());
    }

    /**
     * Mixin creates error handlers by name with its own class loader. Where that loader can see this
     * class's directory (Fabric: Mixin is on the app class path) the name is enough; under NeoForge
     * Mixin is in a module layer, so the handler's bytes are defined into Mixin's own package there.
     */
    static void registerHandler(Class<?> mixins) throws Throwable {
        ClassLoader mixinLoader = mixins.getClassLoader();
        Class<?> api = Class.forName("org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler", false, mixinLoader);
        Class<?> handler = null;
        try { handler = Class.forName(HANDLER, false, mixinLoader); } catch (ClassNotFoundException notVisible) { }
        if (handler == null || !api.isAssignableFrom(handler)) {
            InputStream in = ModaccelMixinCheck.class.getClassLoader().getResourceAsStream(HANDLER.replace('.', '/') + ".class");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) > 0) bytes.write(buffer, 0, n);
            in.close();
            Class<?> handles = Class.forName("java.lang.invoke.MethodHandles"), lookupType = Class.forName("java.lang.invoke.MethodHandles$Lookup");
            Object lookup = handles.getMethod("privateLookupIn", Class.class, lookupType).invoke(null, api, handles.getMethod("lookup").invoke(null));
            handler = (Class<?>) lookupType.getMethod("defineClass", byte[].class).invoke(lookup, (Object) bytes.toByteArray());
        }
        mixins.getMethod("registerErrorHandlerClass", String.class).invoke(null, handler.getName());
    }

    /** Configs Mixin had already selected (for example while the loader ran preLaunch code): the transformer's own list. */
    static List<Object> selectedConfigs(ClassLoader mixinLoader, Class<?> configApi) {
        List<Object> found = new ArrayList<Object>();
        try {
            Class<?> environment = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment", true, mixinLoader);
            Object current = environment.getMethod("getCurrentEnvironment").invoke(null);
            Object transformer = environment.getMethod("getActiveTransformer").invoke(current);
            Field processorField = transformer == null ? null : find(transformer.getClass(), "processor");
            if (processorField == null) { emit("note", "message", "Mixin's active transformer " + (transformer == null ? "is not set" : transformer.getClass().getName() + " has no processor") + ", so configs it had already selected cannot be read."); return found; }
            processorField.setAccessible(true);
            Object processor = processorField.get(transformer);
            for (String name : new String[] {"configs", "pendingConfigs"}) {
                Field field = find(processor.getClass(), name);
                if (field == null) continue;
                field.setAccessible(true);
                for (Object config : (Collection<?>) field.get(processor)) if (configApi.isInstance(config)) found.add(config);
            }
        } catch (Throwable failure) { emit("note", "message", "Could not read Mixin's selected configs: " + messages(unwrap(failure))); }
        return found;
    }
    static Field find(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) try { return c.getDeclaredField(name); } catch (NoSuchFieldException next) { }
        return null;
    }
    static Throwable unwrap(Throwable failure) {
        while ((failure instanceof InvocationTargetException || failure instanceof ExceptionInInitializerError) && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
    /** The message and each distinct cause's message, as Mixin and the loader wrote them. */
    static String messages(Throwable failure) {
        StringBuilder text = new StringBuilder(); Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
            String message = t.getMessage() == null ? t.getClass().getName() : t.getMessage();
            if (text.indexOf(message) >= 0) continue;
            if (text.length() > 0) text.append("\n  caused by ").append(t.getClass().getSimpleName()).append(": ");
            text.append(message);
        }
        return text.toString();
    }
    static double seconds(long start) { return (System.nanoTime() - start) / 1e9; }
    static synchronized void emit(String event, Object... fields) {
        StringBuilder line = new StringBuilder("{\"event\":").append(quote(event));
        for (int i = 0; i + 1 < fields.length; i += 2) line.append(',').append(quote(String.valueOf(fields[i]))).append(':').append(value(fields[i + 1]));
        try {
            Writer out = new OutputStreamWriter(new FileOutputStream(System.getProperty("modaccel.mixincheck.out"), true), "UTF-8");
            try { out.write(line.append("}\n").toString()); } finally { out.close(); }
        } catch (IOException failure) { System.err.println("modaccel mixin check could not record: " + line); }
    }
    static String value(Object value) {
        if (value == null) return "null";
        if (value instanceof Double) return ((Double) value).isNaN() || ((Double) value).isInfinite() ? "null" : value.toString();
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Collection) {
            StringBuilder list = new StringBuilder("[");
            for (Object item : (Collection<?>) value) list.append(list.length() > 1 ? "," : "").append(value(item));
            return list.append(']').toString();
        }
        return quote(value.toString());
    }
    static String quote(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"') quoted.append("\\\"");
            else if (c == '\\') quoted.append("\\\\");
            else if (c == '\n') quoted.append("\\n");
            else if (c == '\r') quoted.append("\\r");
            else if (c == '\t') quoted.append("\\t");
            else if (c < 0x20) quoted.append(String.format("\\u%04x", (int) c));
            else quoted.append(c);
        }
        return quoted.append('"').toString();
    }
}
