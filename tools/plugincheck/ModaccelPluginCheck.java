import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.logging.*;
import java.util.regex.*;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.event.*;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.brigadier.PaperCommandsMock;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;
import org.mockbukkit.mockbukkit.plugin.MockBukkitURLClassLoader;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;

/**
 * plugin-check, inside a fresh JVM with MockBukkit (a fake Paper server) and the plugin's Paper API.
 * Loads the built plugin jar the way Paper does, enables it, and reports what a real server would:
 * a bad descriptor, a missing main class, a missing hard dependency, an exception in onLoad/onEnable/
 * a task/onDisable, a plugin.yml command nothing handles, permissions that do not register, and
 * listeners Paper would skip. What MockBukkit cannot stand in for is reported "unchecked", never passed.
 *
 * Usage: ModaccelPluginCheck <spec.json>  (spec: jar, result, descriptorSource, javaRoots, plugins)
 */
public final class ModaccelPluginCheck {
    static final List<Map<String, Object>> checks = new ArrayList<>();
    static final List<String> warnings = new ArrayList<>(), unchecked = new ArrayList<>();
    static List<Path> javaRoots = List.of();
    static Path descriptorSource;
    static List<String> descriptorLines = List.of();
    static String descriptorName = "plugin.yml";

    public static void main(String[] args) throws Exception {
        Map<String, Object> spec = Json.read(Path.of(args[0]));
        Path jar = Path.of(Json.text(spec, "jar", "")), result = Path.of(Json.text(spec, "result", ""));
        javaRoots = new ArrayList<>(); for (Object r : Json.array(spec.get("javaRoots"))) javaRoots.add(Path.of(r.toString()));
        Map<String, Object> out = new LinkedHashMap<>();
        // Paper writes plugin output to the console; keep ours in the result, with the plugin's own log lines.
        List<String> log = new ArrayList<>();
        try {
            run(spec, jar, out, log);
        } catch (Throwable t) {
            fail("plugin-check", "plugin-check itself stopped: " + t + at(t), null, 0);
        } finally {
            try { MockBukkit.unmock(); } catch (Throwable ignored) { }
        }
        boolean failed = checks.stream().anyMatch(c -> "fail".equals(c.get("status")));
        out.put("ok", !failed && unchecked.isEmpty());
        out.put("failed", failed);
        out.put("checks", checks);
        out.put("warnings", warnings);
        out.put("unchecked", unchecked);
        out.put("pluginLog", log.size() > 60 ? log.subList(log.size() - 60, log.size()) : log);
        Json.save(result, out);
        System.exit(0);
    }

    static void run(Map<String, Object> spec, Path jarPath, Map<String, Object> out, List<String> log) throws Exception {
        if (!Files.isRegularFile(jarPath)) { fail("jar", "The plugin jar " + jarPath + " was not built.", null, 0); return; }
        out.put("jar", jarPath.toString());
        Map<String, byte[]> classes = new TreeMap<>();
        String descriptorText = null; boolean paperPlugin = false;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry paper = jar.getJarEntry("paper-plugin.yml"), bukkit = jar.getJarEntry("plugin.yml");
            // Paper prefers paper-plugin.yml when a jar has both.
            JarEntry entry = paper != null ? paper : bukkit;
            if (entry == null) { fail("descriptor", "The jar " + jarPath.getFileName() + " has no plugin.yml or paper-plugin.yml at its root, so Paper does not see a plugin in it. Put plugin.yml in src/main/resources.", null, 0); return; }
            paperPlugin = entry == paper; descriptorName = entry.getName();
            descriptorText = new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            for (JarEntry e : Collections.list(jar.entries())) if (e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/"))
                classes.put(e.getName(), jar.getInputStream(e).readAllBytes());
        }
        String source = Json.text(spec, "descriptorSource", "");
        descriptorSource = source.isEmpty() ? null : Path.of(source);
        // Line numbers come from the jar's copy (what Paper reads); the file named is the source it came from.
        descriptorLines = descriptorText.lines().toList();
        out.put("descriptor", Json.map("file", descriptorName, "source", descriptorSource == null ? null : descriptorSource.toString(), "paperPlugin", paperPlugin));

        // 1. The descriptor parses, and names what Paper requires.
        Map<String, Object> yaml;
        try {
            Object parsed = new Yaml().load(descriptorText);
            if (!(parsed instanceof Map<?, ?> m)) { fail("descriptor", descriptorName + " is not a YAML map of keys (name:, version:, main:).", null, 1); return; }
            @SuppressWarnings("unchecked") Map<String, Object> cast = (Map<String, Object>) m; yaml = cast;
        } catch (MarkedYAMLException e) {
            Mark mark = e.getProblemMark();
            fail("descriptor", descriptorName + " is not valid YAML: " + e.getProblem() + (e.getContext() == null ? "" : " (" + e.getContext() + ")") + ".", null, mark == null ? 0 : mark.getLine() + 1);
            return;
        }
        PluginDescriptionFile description;
        try {
            description = new PluginDescriptionFile(new StringReader(paperPlugin ? bukkitView(yaml) : descriptorText));
            description.getPermissions(); // parsed lazily: a malformed entry would otherwise fail later, at enable
        } catch (InvalidDescriptionException | RuntimeException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            String key = message.contains("main") ? "main" : message.contains("version") ? "version" : message.contains("name") ? "name" : message.contains("permission") ? "permissions" : null;
            fail("descriptor", descriptorName + " is rejected by Paper: " + message + (e.getCause() != null && e.getCause() != e ? " (" + e.getCause().getMessage() + ")" : "") + ".", null, key == null ? 0 : line(key));
            return;
        }
        String name = description.getName();
        out.put("plugin", Json.map("name", name, "version", description.getVersion(), "main", description.getMain(), "apiVersion", description.getAPIVersion()));

        ServerMock server = MockBukkit.mock();
        String serverVersion = server.getMinecraftVersion();
        String onClasspath = null;
        try { var codeSource = org.bukkit.Server.class.getProtectionDomain().getCodeSource(); if (codeSource != null) onClasspath = Path.of(codeSource.getLocation().toURI()).getFileName().toString().replaceAll("^paper-api-|\\.jar$", ""); } catch (Exception ignored) { }
        out.put("server", Json.map("implementation", "MockBukkit", "minecraft", serverVersion, "builtFor", server.getBukkitVersion(), "paperApi", onClasspath == null ? server.getBukkitVersion() : onClasspath));
        String api = description.getAPIVersion();
        if (api == null) warn(descriptorName + " has no api-version, so Paper loads " + name + " as a legacy (pre-1.13) plugin with legacy material names. Add api-version: '" + serverVersion + "'.");
        else if (compare(api, serverVersion) > 0) fail("descriptor", descriptorName + " says api-version: " + api + ", newer than this server's " + serverVersion + ". Paper refuses to load it; build against Paper " + serverVersion + " or lower the api-version.", null, line("api-version"));
        pass("descriptor", descriptorName + " parses: " + name + " " + description.getVersion() + ", main " + description.getMain() + (api == null ? "" : ", api-version " + api) + ".");
        if (paperPlugin) {
            for (String key : List.of("bootstrapper", "loader")) if (yaml.get(key) != null)
                uncheck(descriptorName + " names a " + key + " (" + yaml.get(key) + "); MockBukkit has no Paper bootstrap phase, so it is not run.");
        }

        // 2. Server internals: MockBukkit has no Minecraft server inside.
        Set<String> internals = new TreeSet<>();
        for (var e : classes.entrySet()) for (String s : ClassStrings.of(e.getValue()))
            if (s.contains("net/minecraft/") || s.contains("org/bukkit/craftbukkit/") || s.startsWith("net.minecraft.") || s.startsWith("org.bukkit.craftbukkit"))
            { internals.add(e.getKey().replace('/', '.').replaceAll("\\.class$", "")); break; }
        if (!internals.isEmpty()) uncheck(internals.size() + " class(es) use server internals (net.minecraft / CraftBukkit), e.g. " + String.join(", ", internals.stream().limit(3).toList())
                + ". MockBukkit has no Minecraft server inside, so code through them is not checked; it needs a real Paper server.");

        // 3. Dependencies: Paper does not enable a plugin whose hard dependency is missing.
        server.addSimpleWorld("world");
        Map<String, Plugin> present = new LinkedHashMap<>();
        for (Object p : Json.array(spec.get("plugins"))) {
            try {
                Plugin other = server.getPluginManager().loadPlugin(new File(p.toString()));
                server.getPluginManager().enablePlugin(other);
                present.put(other.getName(), other);
            } catch (Throwable t) { warn("Could not load the dependency jar " + p + ": " + root(t)); }
        }
        List<String> depend = new ArrayList<>(description.getDepend()), soft = new ArrayList<>(description.getSoftDepend());
        if (paperPlugin && yaml.get("dependencies") instanceof Map<?, ?> deps && deps.get("server") instanceof Map<?, ?> serverDeps)
            for (var d : serverDeps.entrySet()) {
                boolean required = !(d.getValue() instanceof Map<?, ?> m && Boolean.FALSE.equals(m.get("required")));
                (required ? depend : soft).add(String.valueOf(d.getKey()));
            }
        List<String> missing = depend.stream().filter(d -> !present.containsKey(d)).toList();
        out.put("dependencies", Json.map("depend", depend, "softdepend", soft, "loadbefore", description.getLoadBefore(), "present", new ArrayList<>(present.keySet()), "missing", missing));
        for (String d : missing)
            fail("dependencies", descriptorName + " needs the plugin " + d + " (hard dependency), and no plugin named " + d + " is on the server, so Paper would not enable " + name
                    + ". Make it a softdepend if " + name + " works without it, or pass its jar with --plugins FOLDER.", null, dependencyLine(d));
        for (String d : soft) if (!present.containsKey(d)) warn("softdepend " + d + " is not on the server; " + name + " was checked without it.");
        if (missing.isEmpty()) pass("dependencies", depend.isEmpty() && soft.isEmpty() ? "No dependencies declared." : "Hard: " + (depend.isEmpty() ? "none" : String.join(", ", depend)) + "; soft: " + (soft.isEmpty() ? "none" : String.join(", ", soft)) + ".");
        else { out.put("enabled", false); return; }

        // 4. Load: the main class exists, is a JavaPlugin, and constructs; onLoad runs.
        Path data = Files.createTempDirectory("modaccel-plugin-data-");
        JavaPlugin plugin;
        MockBukkitURLClassLoader loader = new MockBukkitURLClassLoader(jarPath.toFile(), ModaccelPluginCheck.class.getClassLoader(), server, description, data.toFile());
        String main = description.getMain();
        Class<?> mainClass;
        try {
            mainClass = loader.loadClass(main, true, false, false);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            boolean inJar = classes.containsKey(main.replace('.', '/') + ".class");
            fail("main-class", inJar ? "The main class " + main + " is in the jar but could not load: " + root(e) + "."
                    : descriptorName + " says main: " + main + ", but the jar has no class " + main + " (" + main.replace('.', '/') + ".class). Fix main: to the class that extends JavaPlugin.", null, line("main"));
            return;
        }
        if (!JavaPlugin.class.isAssignableFrom(mainClass)) { fail("main-class", "main: " + main + " does not extend JavaPlugin, so Paper cannot start it.", null, line("main")); return; }
        Handler capture = new Handler() {
            @Override public void publish(LogRecord r) {
                String text = r.getLevel() + " [" + name + "] " + r.getMessage() + (r.getThrown() == null ? "" : " " + r.getThrown());
                log.add(text);
                if (r.getLevel().intValue() >= Level.SEVERE.intValue()) warn(name + " logged an error: " + r.getMessage() + (r.getThrown() == null ? "" : " (" + root(r.getThrown()) + at(r.getThrown()) + ")"));
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        try {
            plugin = (JavaPlugin) mainClass.getConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException i ? i.getCause() : e;
            if (unimplemented(cause, "constructing " + main)) return;
            fail("main-class", "Constructing " + main + " threw " + root(cause) + at(cause) + ". Paper creates the main class with a public no-argument constructor.", cause, 0);
            return;
        }
        plugin.getLogger().addHandler(capture);
        try {
            server.getPluginManager().registerLoadedPlugin(plugin); // adds plugin.yml commands, then onLoad
        } catch (Throwable t) {
            if (unimplemented(t, "onLoad")) return;
            fail("load", "onLoad threw " + root(t) + at(t) + ". Paper logs this and does not enable " + name + ".", t, 0);
            return;
        }
        pass("main-class", main + " loads and constructs" + (overrides(mainClass, "onLoad") ? "; onLoad ran." : "."));

        // 5. Enable: Paper registers the descriptor's permissions, then calls onEnable.
        List<String> permissionProblems = new ArrayList<>();
        for (Permission permission : description.getPermissions()) {
            try { server.getPluginManager().addPermission(permission); }
            catch (IllegalArgumentException e) { permissionProblems.add("permission " + permission.getName() + " is already registered (declared twice, or by another plugin): " + e.getMessage()); }
        }
        try {
            server.getPluginManager().enablePlugin(plugin);
        } catch (Throwable t) {
            if (unimplemented(t, "onEnable")) return;
            fail("enable", "onEnable threw " + root(t) + at(t) + ". Paper logs this and disables " + name + ", so none of its commands or listeners work.", t, 0);
            return;
        }
        if (!plugin.isEnabled()) { fail("enable", name + " disabled itself during onEnable (it called setEnabled(false) or disablePlugin).", null, 0); return; }
        pass("enable", "onEnable finished without an exception.");

        // 6. Commands: Paper fires the commands lifecycle event once plugins are enabled.
        List<String> brigadier = new ArrayList<>();
        try {
            server.dispatchCommand(server.getConsoleSender(), "modaccel-plugin-check-no-such-command");
            String prefix = name.toLowerCase(Locale.ROOT) + ":";
            for (var node : PaperCommandsMock.INSTANCE.getDispatcherInternal().getRoot().getChildren())
                // plugin.yml commands sit in the same tree as Bukkit wrappers; only nodes registered through Paper's Commands API count here.
                if (node.getName().startsWith(prefix) && !(node instanceof org.mockbukkit.mockbukkit.command.brigadier.bukkit.BukkitCommandNode))
                    brigadier.add(node.getName().substring(prefix.length()));
            Collections.sort(brigadier);
        } catch (Throwable t) {
            if (unimplemented(t, "the commands lifecycle event")) return;
            fail("commands", "Registering Brigadier commands (LifecycleEvents.COMMANDS) threw " + root(t) + at(t) + ".", t, 0);
            return;
        }
        Map<String, Map<String, Object>> declared = description.getCommands();
        List<Object> commands = new ArrayList<>(); int unhandled = 0;
        boolean ownOnCommand = overrides(mainClass, "onCommand", CommandSender.class, Command.class, String.class, String[].class);
        for (var command : declared.entrySet()) {
            String label = command.getKey();
            PluginCommand registered = plugin.getCommand(label);
            String handler = registered == null ? null : registered.getExecutor() != plugin ? registered.getExecutor().getClass().getName()
                    : ownOnCommand ? main + ".onCommand" : brigadier.contains(label) ? "Brigadier" : null;
            commands.add(Json.map("label", label, "declared", true, "handler", handler));
            if (handler == null) {
                unhandled++;
                fail("commands", "/" + label + " is declared in " + descriptorName + " but nothing handles it: onEnable never calls getCommand(\"" + label + "\").setExecutor(...), and "
                        + mainClass.getSimpleName() + " does not override onCommand. On a server /" + label + " only prints its usage.", null, commandLine(label));
            }
            Object permission = command.getValue() == null ? null : command.getValue().get("permission");
            if (permission != null && description.getPermissions().stream().noneMatch(p -> p.getName().equals(permission)))
                warn("/" + label + " needs permission " + permission + ", which " + descriptorName + " does not declare, so Paper gives it to operators only.");
        }
        for (String label : brigadier) if (!label.contains(":")) commands.add(Json.map("label", label, "declared", false, "handler", "Brigadier (LifecycleEvents.COMMANDS)"));
        out.put("commands", commands);
        if (unhandled == 0) pass("commands", (declared.isEmpty() ? "No commands in " + descriptorName : declared.size() + " command(s) in " + descriptorName + " all have a handler")
                + (brigadier.isEmpty() ? "." : "; Brigadier registered /" + String.join(", /", brigadier.stream().filter(l -> !l.contains(":")).toList()) + "."));

        // 7. Permissions: every declared one registered with its default, and the ones the code names are declared.
        List<Object> permissions = new ArrayList<>();
        for (Permission declaredPermission : description.getPermissions()) {
            Permission live = server.getPluginManager().getPermission(declaredPermission.getName());
            permissions.add(Json.map("name", declaredPermission.getName(), "default", declaredPermission.getDefault().name().toLowerCase(Locale.ROOT), "registered", live != null));
            if (live == null) permissionProblems.add("permission " + declaredPermission.getName() + " is declared but not registered after enable (the plugin removed it)");
        }
        out.put("permissions", permissions);
        for (String problem : permissionProblems) fail("permissions", problem + ".", null, line(problem.split(" ")[1]));
        if (permissionProblems.isEmpty()) pass("permissions", description.getPermissions().isEmpty() ? "No permissions declared." : description.getPermissions().size() + " permission(s) registered: "
                + String.join(", ", description.getPermissions().stream().map(p -> p.getName() + " (" + p.getDefault().name().toLowerCase(Locale.ROOT) + ")").toList()) + ".");
        Set<String> prefixes = new TreeSet<>(); prefixes.add(name.toLowerCase(Locale.ROOT));
        for (Permission p : description.getPermissions()) if (p.getName().contains(".")) prefixes.add(p.getName().substring(0, p.getName().indexOf('.')));
        Set<String> named = new TreeSet<>();
        for (byte[] bytes : classes.values()) for (String s : ClassStrings.of(bytes))
            if (s.matches("[a-z0-9_-]+(\\.[a-z0-9_*-]+)+") && prefixes.contains(s.substring(0, s.indexOf('.'))) && !s.matches(".*\\.(yml|yaml|json|txt|png|properties|toml|conf|zip|jar|dat)"))
                named.add(s);
        for (String s : named) if (description.getPermissions().stream().noneMatch(p -> p.getName().equals(s)) && server.getPluginManager().getPermission(s) == null)
            warn("The code names " + s + ", which looks like a permission but is not declared in " + descriptorName + " (an undeclared permission is operator-only).");

        // 8. Listeners: what registerEvents really registered, and handlers Paper would skip.
        Map<Listener, List<RegisteredListener>> byListener = new LinkedHashMap<>();
        for (RegisteredListener r : HandlerList.getRegisteredListeners(plugin)) byListener.computeIfAbsent(r.getListener(), k -> new ArrayList<>()).add(r);
        List<Object> listeners = new ArrayList<>(); int skipped = 0;
        Set<Class<?>> registeredClasses = new HashSet<>();
        for (var l : byListener.entrySet()) {
            Class<?> type = l.getKey().getClass(); registeredClasses.add(type);
            listeners.add(Json.map("class", type.getName(), "handlers", l.getValue().size()));
            for (Method m : handlerMethods(type)) if (m.getParameterCount() != 1 || !Event.class.isAssignableFrom(m.getParameterTypes()[0])) {
                skipped++;
                fail("listeners", type.getSimpleName() + "." + m.getName() + " has @EventHandler but takes " + m.getParameterCount() + " parameter(s); Paper skips it (an event handler takes exactly one Event).", null, 0, sourceOf(type));
            }
        }
        out.put("listeners", listeners);
        for (String entry : classes.keySet()) {
            byte[] bytes = classes.get(entry);
            if (!ClassStrings.of(bytes).contains("Lorg/bukkit/event/EventHandler;")) continue;
            String className = entry.replace('/', '.').replaceAll("\\.class$", "");
            try {
                Class<?> type = Class.forName(className, false, loader);
                if (Listener.class.isAssignableFrom(type) && !registeredClasses.contains(type) && !handlerMethods(type).isEmpty() && !java.lang.reflect.Modifier.isAbstract(type.getModifiers()))
                    warn(type.getName() + " has @EventHandler methods but was not registered during onEnable (fine only if it is registered later).");
            } catch (Throwable ignored) { }
        }
        if (skipped == 0) pass("listeners", byListener.isEmpty() ? "No listeners registered." : byListener.size() + " listener(s) registered with " + byListener.values().stream().mapToInt(List::size).sum() + " event handler(s): "
                + String.join(", ", byListener.keySet().stream().map(k -> k.getClass().getSimpleName()).toList()) + ".");

        // 9. Tasks scheduled at enable run for 5 seconds of server time (100 ticks), as on a started server.
        int taskFailures = 0;
        for (int tick = 0; tick < 100; tick++) {
            try { server.getScheduler().performOneTick(); }
            catch (Throwable t) {
                if (unimplemented(t, "a task scheduled at enable")) break;
                taskFailures++;
                fail("tasks", "A task " + name + " scheduled threw " + root(t) + at(t) + " at tick " + (tick + 1) + ". Paper logs this every time the task runs.", t, 0);
                if (taskFailures >= 3) break;
            }
        }
        if (taskFailures == 0) pass("tasks", "100 server ticks ran with " + name + "'s scheduled tasks and no exception.");

        // 10. Disable: Paper calls onDisable at shutdown.
        try {
            server.getPluginManager().disablePlugin(plugin);
            pass("disable", "onDisable finished without an exception.");
        } catch (Throwable t) {
            if (!unimplemented(t, "onDisable")) fail("disable", "onDisable threw " + root(t) + at(t) + ". Paper logs this at shutdown and may lose unsaved data.", t, 0);
        }
        out.put("enabled", true);
    }

    /** Paper plugins keep Bukkit's name/version/main/api-version keys; the rest is Paper-only. */
    static String bukkitView(Map<String, Object> yaml) {
        Map<String, Object> view = new LinkedHashMap<>();
        for (String key : List.of("name", "version", "main", "api-version", "description", "authors", "author", "website", "prefix", "load", "permissions", "default-permission"))
            if (yaml.containsKey(key)) view.put(key, yaml.get(key));
        return new Yaml().dump(view);
    }
    static List<Method> handlerMethods(Class<?> type) {
        Set<Method> methods = new LinkedHashSet<>();
        try { methods.addAll(Arrays.asList(type.getMethods())); methods.addAll(Arrays.asList(type.getDeclaredMethods())); } catch (Throwable ignored) { return List.of(); }
        return methods.stream().filter(m -> m.isAnnotationPresent(EventHandler.class) && !m.isBridge() && !m.isSynthetic()).toList();
    }
    static boolean overrides(Class<?> type, String method, Class<?>... parameters) {
        try { return type.getMethod(method, parameters).getDeclaringClass() != JavaPlugin.class; } catch (NoSuchMethodException e) { return false; }
    }
    /** MockBukkit throws this for API it does not implement: that part is unchecked, never a pass or a plugin bug. */
    static boolean unimplemented(Throwable t, String where) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause())
            if (c instanceof UnimplementedOperationException) {
                StackTraceElement call = pluginFrame(c);
                uncheck(where + " calls Paper API that MockBukkit does not implement" + (call == null ? "" : " (" + call.getClassName() + "." + call.getMethodName() + ", " + location(call) + ")")
                        + ", so everything from there on is not checked; it needs a real Paper server.");
                return true;
            }
        return false;
    }
    static StackTraceElement pluginFrame(Throwable t) {
        for (StackTraceElement e : t.getStackTrace()) if (sourceFile(e) != null) return e;
        return null;
    }
    static Path sourceFile(StackTraceElement e) {
        if (e.getFileName() == null) return null;
        String pkg = e.getClassName().contains(".") ? e.getClassName().substring(0, e.getClassName().lastIndexOf('.')).replace('.', '/') : "";
        for (Path root : javaRoots) { Path f = root.resolve(pkg).resolve(e.getFileName()); if (Files.isRegularFile(f)) return f; }
        return null;
    }
    /** File name and line; the check's file field carries the full path. */
    static String location(StackTraceElement e) { return e.getFileName() + ":" + e.getLineNumber(); }
    static Path sourceOf(Class<?> type) {
        String path = type.getName().replace('.', '/');
        if (path.contains("$")) path = path.substring(0, path.indexOf('$'));
        for (Path root : javaRoots) { Path f = root.resolve(path + ".java"); if (Files.isRegularFile(f)) return f; }
        return null;
    }
    /** The deepest cause, in one line. */
    static String root(Throwable t) {
        Throwable c = t; while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getClass().getName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
    }
    /** Where in the plugin's own code the deepest cause was thrown. */
    static String at(Throwable t) {
        StackTraceElement e = frame(t);
        return e == null ? "" : " at " + e.getClassName().substring(e.getClassName().lastIndexOf('.') + 1) + "." + e.getMethodName() + " (" + location(e) + ")";
    }
    /** The plugin's own frame nearest the deepest cause. */
    static StackTraceElement frame(Throwable t) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable c = t; c != null && !chain.contains(c); c = c.getCause()) chain.add(c);
        Collections.reverse(chain);
        for (Throwable c : chain) { StackTraceElement e = pluginFrame(c); if (e != null) return e; }
        return null;
    }
    static int line(String key) {
        Pattern p = Pattern.compile("^\\s*['\"]?" + Pattern.quote(key) + "['\"]?\\s*:");
        for (int i = 0; i < descriptorLines.size(); i++) if (p.matcher(descriptorLines.get(i)).find()) return i + 1;
        return 0;
    }
    static int commandLine(String label) {
        int commands = line("commands");
        Pattern p = Pattern.compile("^\\s+['\"]?" + Pattern.quote(label) + "['\"]?\\s*:");
        for (int i = Math.max(0, commands); i < descriptorLines.size(); i++) if (p.matcher(descriptorLines.get(i)).find()) return i + 1;
        return commands;
    }
    static int dependencyLine(String name) {
        for (int i = 0; i < descriptorLines.size(); i++) {
            String l = descriptorLines.get(i);
            if ((l.matches("^\\s*depend\\s*:.*") || l.matches("^\\s*-\\s*.*") || l.matches("^\\s+\\S+\\s*:.*")) && l.matches(".*\\b" + Pattern.quote(name) + "\\b.*")) return i + 1;
        }
        int d = line("depend"); return d > 0 ? d : line("dependencies");
    }
    static int compare(String a, String b) {
        String[] x = a.split("[.-]"), y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }
    static int number(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } }

    static void pass(String name, String details) { checks.add(Json.map("name", name, "status", "pass", "details", details)); }
    static void warn(String text) { if (!warnings.contains(text)) warnings.add(text); }
    static void uncheck(String text) { if (!unchecked.contains(text)) unchecked.add(text); }
    static void fail(String name, String details, Throwable t, int line) { fail(name, details, t, line, null); }
    static void fail(String name, String details, Throwable t, int line, Path file) {
        Map<String, Object> check = Json.map("name", name, "status", "fail", "details", details);
        StackTraceElement frame = t == null ? null : frame(t);
        if (file != null) check.put("file", file.toString());
        else if (frame != null && sourceFile(frame) != null) { check.put("file", sourceFile(frame).toString()); check.put("line", frame.getLineNumber()); }
        else if (line > 0 || t == null) { check.put("file", descriptorSource != null ? descriptorSource.toString() : descriptorName); if (line > 0) check.put("line", line); }
        checks.add(check);
    }

    /** UTF-8 constants of a class file: class names, descriptors and string literals. */
    static final class ClassStrings {
        static Set<String> of(byte[] b) {
            Set<String> out = new HashSet<>();
            try {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
                if (in.readInt() != 0xCAFEBABE) return out;
                in.readUnsignedShort(); in.readUnsignedShort();
                int count = in.readUnsignedShort();
                for (int i = 1; i < count; i++) {
                    int tag = in.readUnsignedByte();
                    switch (tag) {
                        case 1 -> out.add(in.readUTF());
                        case 3, 4 -> in.skipBytes(4);
                        case 5, 6 -> { in.skipBytes(8); i++; }
                        case 7, 8, 16, 19, 20 -> in.skipBytes(2);
                        case 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                        case 15 -> in.skipBytes(3);
                        default -> { return out; }
                    }
                }
            } catch (IOException ignored) { }
            return out;
        }
    }
}
