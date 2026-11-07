import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
import net.minecraft.commands.Commands;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.Util;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.validation.DirectoryValidator;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

/**
 * What the game loads for the mod, read from the game's own code with no window and no world: the
 * steps a dedicated server takes before it creates a world. Bootstrap, every mod's main and server
 * entrypoints, the registries frozen (Fabric delays that until its mods have registered), then the
 * data packs a new world would enable (vanilla, Fabric's mod packs) loaded by WorldLoader, as the
 * server does. Every warning and error the game logs meanwhile is kept, as it happens. It can stay loaded
 * and read the data again for each later check (see run()).
 *
 * Written against Minecraft 26.x (Mojang names); data-check compiles it against the mod's own launch.
 */
public final class ModaccelDataProbe {
    record Loaded(ReloadableServerResources resources, LayeredRegistryAccess<RegistryLayer> registries) {}

    static volatile PrintWriter out;
    static final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
    static final Set<String> ours = new LinkedHashSet<>();
    static boolean first = true;

    /**
     * Starts the game's code once, reads the data, and then, with -Dmodaccel.datacheck.serve=true, stays
     * loaded: each later check loads the data packs again from disk (as /reload does), which is all a
     * changed recipe, loot table or tag needs. A change to the mod's code needs a new JVM; data-check
     * decides that, not this class.
     */
    public static void run() throws Exception {
        open(System.getProperty("modaccel.datacheck.out"));
        capture();
        boolean serve = Boolean.getBoolean("modaccel.datacheck.serve"), started = false;
        // A data error leaves the loaded game usable, so it keeps serving; a failed start does not.
        try { init(); started = true; check(); }
        catch (Throwable failure) { if (!serve || !started) throw failure; error(failure); }
        finally { close(); }
        if (serve) serve();
    }

    static void init() throws Exception {
        // The project's own mods: those loaded from its source sets' outputs (the game jar can sit inside the project too, in .gradle).
        List<Path> outputs = new ArrayList<>();
        for (String output : System.getProperty("modaccel.datacheck.outputs", "").split(java.io.File.pathSeparator))
            if (!output.isBlank()) outputs.add(Path.of(output).toAbsolutePath().normalize());
        emit("start", "cpu", cpu(), "first", true);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        phase("bootstrap");

        FabricLoader loader = FabricLoader.getInstance();
        for (ModContainer mod : loader.getAllMods())
            for (Path path : mod.getOrigin().getPaths()) for (Path output : outputs) if (path.toAbsolutePath().normalize().startsWith(output)) ours.add(mod.getMetadata().getId());
        for (var entry : loader.getEntrypointContainers("main", ModInitializer.class)) entry.getEntrypoint().onInitialize();
        for (var entry : loader.getEntrypointContainers("server", DedicatedServerModInitializer.class)) entry.getEntrypoint().onInitializeServer();
        Method freeze = BuiltInRegistries.class.getDeclaredMethod("freeze");
        freeze.setAccessible(true);
        freeze.invoke(null);
        phase("mods");
    }

    /** One check: the data packs a new world enables, loaded from disk by WorldLoader, then the report. */
    static void check() throws Exception {
        if (!first) emit("start", "cpu", cpu(), "first", false);
        first = false;
        emit("mods", "ours", new ArrayList<>(ours), "loaded", FabricLoader.getInstance().getAllMods().size());
        Path packs = Files.createTempDirectory("modaccel-datacheck");
        PackRepository repository = ServerPacksSource.createPackRepository(packs, new DirectoryValidator(path -> true));
        repository.reload();
        WorldDataConfiguration config = MinecraftServer.configurePackRepository(repository, WorldDataConfiguration.DEFAULT, true, false);
        emit("packs", "available", new ArrayList<>(repository.getAvailableIds()), "selected", new ArrayList<>(repository.getSelectedIds()));
        WorldLoader.InitConfig init = new WorldLoader.InitConfig(new WorldLoader.PackConfig(repository, config, false, true),
                Commands.CommandSelection.DEDICATED, LevelBasedPermissionSet.OWNER);
        Loaded data = Util.<Loaded>blockUntilDone(executor -> WorldLoader.load(init,
                context -> new WorldLoader.DataLoadOutput<>("no world", context.datapackDimensions()),
                (manager, resources, registries, cookie) -> new Loaded(resources, registries),
                Util.backgroundExecutor(), executor)).get();
        data.resources().updateComponentsAndStaticRegistryTags();
        phase("data");

        // Everything with one of the mod's namespaces, registry by registry (built-in and data-driven).
        Map<String, Map<String, Integer>> counts = new TreeMap<>();
        Set<String> seen = new java.util.HashSet<>();
        data.registries().compositeAccess().registries().forEach(entry -> {
            if (!seen.add(entry.key().identifier().toString())) return; // a registry can appear in more than one layer
            count(counts, entry.key().identifier().getPath(), entry.value().keySet());
            entry.value().listTagIds().forEach(tag -> { if (ours.contains(tag.location().getNamespace())) counts.computeIfAbsent(tag.location().getNamespace(), k -> new TreeMap<>()).merge(entry.key().identifier().getPath() + " tag", 1, Integer::sum); });
        });
        // Loot tables, predicates and item modifiers live only in the full lookup, apart from the rest.
        data.resources().fullRegistries().lookup().listRegistries().forEach(registry -> {
            if (!seen.add(registry.key().identifier().toString())) return; // this lookup holds every registry; count only the reloadable ones
            String name = registry.key().identifier().getPath();
            registry.listElementIds().forEach(key -> { if (ours.contains(key.identifier().getNamespace())) counts.computeIfAbsent(key.identifier().getNamespace(), k -> new TreeMap<>()).merge(name, 1, Integer::sum); });
        });
        Map<String, Integer> recipes = new TreeMap<>();
        for (var recipe : data.resources().getRecipeManager().getRecipes()) bump(recipes, recipe.id().identifier().getNamespace());
        recipes.forEach((ns, n) -> counts.computeIfAbsent(ns, k -> new TreeMap<>()).put("recipe", n));
        Map<String, Integer> advancements = new TreeMap<>();
        for (var advancement : data.resources().getAdvancements().getAllAdvancements()) bump(advancements, advancement.id().getNamespace());
        advancements.forEach((ns, n) -> counts.computeIfAbsent(ns, k -> new TreeMap<>()).put("advancement", n));
        for (String mod : ours) emit("registered", "mod", mod, "counts", counts.getOrDefault(mod, Map.of()));

        // Each of the mod's items as a stack: its components bind only once data has loaded, as in a world.
        for (Item item : BuiltInRegistries.ITEM) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (!ours.contains(id.getNamespace())) continue;
            try {
                ItemStack stack = new ItemStack(item);
                FoodProperties food = stack.get(DataComponents.FOOD);
                emit("item", "id", id.toString(), "name", stack.getHoverName().getString(), "maxStack", stack.getMaxStackSize(), "maxDamage", stack.getMaxDamage(),
                        "rarity", String.valueOf(stack.getRarity()).toLowerCase(), "nutrition", food == null ? null : food.nutrition(), "components", stack.getComponents().size());
            } catch (Throwable failure) {
                emit("item", "id", id.toString(), "error", String.valueOf(failure));
            }
        }
        emit("done", "cpu", cpu());
    }

    /**
     * Stays loaded and answers one request per connection on 127.0.0.1: "TOKEN check OUTFILE" or "TOKEN quit".
     * The port, token and pid go to -Dmodaccel.datacheck.state, which data-check reads.
     */
    static void serve() throws Exception {
        String token = System.getProperty("modaccel.datacheck.token");
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress())) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("pid", ProcessHandle.current().pid()); state.put("port", server.getLocalPort()); state.put("token", token);
            state.put("fingerprint", System.getProperty("modaccel.datacheck.fingerprint", ""));
            Path file = Path.of(System.getProperty("modaccel.datacheck.state")), temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, json(state), StandardCharsets.UTF_8);
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            while (true) {
                try (java.net.Socket socket = server.accept()) {
                    var reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    var reply = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                    String[] request = String.valueOf(reader.readLine()).split(" ", 3);
                    if (request.length < 2 || !request[0].equals(token)) { reply.println("refused"); continue; }
                    if (request[1].equals("quit")) { reply.println("ok"); return; }
                    if (request[1].equals("check") && request.length == 3) {
                        open(request[2]);
                        try { check(); } catch (Throwable failure) { error(failure); } finally { close(); }
                        reply.println("ok");
                    } else reply.println("unknown");
                }
            }
        }
    }

    static void open(String file) throws Exception {
        out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8), true);
    }
    static void close() { PrintWriter writer = out; out = null; if (writer != null) writer.close(); }
    static void error(Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.ExecutionException && failure.getCause() != null ? failure.getCause() : failure;
        java.io.StringWriter trace = new java.io.StringWriter(); failure.printStackTrace(new PrintWriter(trace));
        emit("error", "phase", "probe", "message", String.valueOf(cause), "trace", trace.toString());
    }

    static void count(Map<String, Map<String, Integer>> counts, String registry, Set<Identifier> keys) {
        for (Identifier key : keys) if (ours.contains(key.getNamespace())) counts.computeIfAbsent(key.getNamespace(), k -> new TreeMap<>()).merge(registry, 1, Integer::sum);
    }
    static void bump(Map<String, Integer> counts, String namespace) { if (ours.contains(namespace)) counts.merge(namespace, 1, Integer::sum); }

    static double cpu() {
        return Math.round(((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime() / 1e8) / 10.0;
    }
    static void phase(String name) { emit("phase", "name", name, "cpu", cpu()); }

    /** The game's own warnings and errors (any logger) from here on, kept with the logger's name. */
    static void capture() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        AbstractAppender appender = new AbstractAppender("modaccel-datacheck", null, null, true, Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) {
                if (!event.getLevel().isMoreSpecificThan(Level.WARN)) return;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("event", "log"); e.put("level", event.getLevel().name()); e.put("logger", event.getLoggerName());
                e.put("message", event.getMessage().getFormattedMessage());
                if (event.getThrown() != null) e.put("thrown", String.valueOf(event.getThrown()));
                // Written at once: a load that then throws must still show what the game said first.
                if (count.incrementAndGet() <= 500) write(e);
            }
        };
        appender.start();
        LoggerConfig rootLogger = context.getConfiguration().getRootLogger();
        rootLogger.addAppender(appender, Level.WARN, null);
        context.updateLoggers();
    }

    static void emit(String event, Object... pairs) {
        Map<String, Object> e = new LinkedHashMap<>(); e.put("event", event);
        for (int i = 0; i + 1 < pairs.length; i += 2) e.put((String) pairs[i], pairs[i + 1]);
        write(e);
    }
    static synchronized void write(Map<String, Object> event) { PrintWriter writer = out; if (writer != null) writer.println(json(event)); }

    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            StringBuilder b = new StringBuilder("{"); boolean first = true;
            for (var entry : map.entrySet()) { if (!first) b.append(','); first = false; b.append(json(String.valueOf(entry.getKey()))).append(':').append(json(entry.getValue())); }
            return b.append('}').toString();
        }
        if (value instanceof Iterable<?> list) {
            StringBuilder b = new StringBuilder("["); boolean first = true;
            for (Object item : list) { if (!first) b.append(','); first = false; b.append(json(item)); }
            return b.append(']').toString();
        }
        return ModaccelDataCheck.quote(value.toString());
    }
}
