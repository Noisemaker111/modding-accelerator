import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;

/**
 * mixin-check: Mixin itself applies the mod's mixins to the real game classes, in the mod's own dev
 * launch, without starting the game (no window, no world). Every verdict is Mixin's own message.
 *
 * How: the build's client run task is recorded once (Fabric: the init script's launch record;
 * NeoForge: a recorder agent inside the launched JVM, because NeoGradle and ModDevGradle assemble
 * the launch inside exec). The same launch is then started with tools/mixincheck/ModaccelMixinCheck
 * in place of the game's main class: Fabric Loader's Knot is set up as fabric-loader-junit does,
 * NeoForge through its unit-test launch target (forgejunituserdev) as junit-fml does, and every
 * class the mod's mixin configs target is loaded, which is what makes Mixin apply them.
 *
 * MixinAudit (the hand-written rules) is not consulted for the verdict.
 */
final class MixinCheck {
    static final List<String> SOURCES = List.of("tools/mixincheck/ModaccelMixinCheck.java", "tools/mixincheck/ModaccelMixinErrors.java", "tools/mixincheck/ModaccelLaunchRecorder.java", "tools/mixincheck/ModaccelNeoForgeTarget.java");
    static final List<String> STUBS = List.of("tools/mixincheck/stubs/org/spongepowered/asm/mixin/extensibility/IMixinErrorHandler.java",
            "tools/mixincheck/stubs/org/spongepowered/asm/mixin/extensibility/IMixinInfo.java", "tools/mixincheck/stubs/org/spongepowered/asm/mixin/extensibility/IMixinConfig.java");
    static final Set<String> KNOT = Set.of("net.fabricmc.loader.impl.launch.knot.KnotClient", "net.fabricmc.loader.impl.launch.knot.KnotServer",
            "net.fabricmc.loader.launch.knot.KnotClient", "net.fabricmc.loader.launch.knot.KnotServer");
    static final String NEO_MAIN = "cpw.mods.bootstraplauncher.BootstrapLauncher";
    static final int TIMEOUT_SECONDS = 600;

    final Build build; final Map<String, Object> model;
    MixinCheck(Build build, Map<String, Object> model) { this.build = build; this.model = model; }

    /** The mod's own loader, from the evaluated dependencies (not plugin names: Architectury Loom builds NeoForge too). */
    static String loader(Map<String, Object> model) {
        boolean fabric = false, neoforge = false;
        for (Object p : Json.array(model.get("projects"))) for (Object d : Json.array(Json.object(p).get("dependencies"))) {
            var dependency = Json.object(d); String group = Json.text(dependency, "group", ""), name = Json.text(dependency, "name", "");
            if (group.equals("net.neoforged") && (name.equals("neoforge") || name.equals("forge"))) neoforge = true;
            if (group.equals("net.fabricmc") && name.equals("fabric-loader")) fabric = true;
        }
        return neoforge ? "neoforge" : fabric ? "fabric" : "";
    }

    Map<String, Object> run(Modaccel.Options options) throws Exception {
        long begin = System.nanoTime();
        String loader = loader(model);
        Map<String, Object> mods = modMixins(null);
        @SuppressWarnings("unchecked") List<String> configs = (List<String>) mods.get("configs");
        if (loader.isEmpty()) return unchecked("This build has neither Fabric Loader nor NeoForge among its evaluated dependencies, so there is no loader to apply mixins with.", mods);
        String task = clientTask(options);
        Path tools = null; Launch launch = null;
        // Generated sources (Stonecutter) and processed resources exist only once Gradle has run, so an
        // empty answer from a tree that was never built is not "no mixins": prepare the launch and look again.
        if (configs.isEmpty() && task != null && missingRoots(mods)) {
            tools = tools(); launch = launch(task, loader, tools, options.has("refresh"));
            mods = modMixins(null);
            @SuppressWarnings("unchecked") List<String> again = (List<String>) mods.get("configs"); configs = again;
        }
        if (configs.isEmpty()) return Json.map("ok", true, "tier", "no-mixins", "checkedBy", "the mod's metadata", "loader", loader, "mixins", Json.map("total", 0),
                "metadata", mods.get("metadata"), "details", "The mod declares no mixin configs (" + mods.get("metadata") + "), so there is nothing for Mixin to apply.", "gameVerified", false);
        if (task == null) return unchecked("No client run task (runClient) in the selected project, so there is no dev launch to apply the mixins in. Select --project, or pass --client-task.", mods);
        if (tools == null) tools = tools();
        if (launch == null) launch = launch(task, loader, tools, options.has("refresh"));
        // Only the source sets this launch puts on its class path are the mod being checked; a helper
        // set with its own metadata (a test shim, another loader's set) is not loaded by this game.
        mods = modMixins(launch);
        @SuppressWarnings("unchecked") List<String> launched = (List<String>) mods.get("configs"); configs = launched;
        if (configs.isEmpty()) return unchecked("None of the source sets that declare mixin configs are on " + task + "'s class path, so this launch would not load them.", mods);
        List<Map<String, Object>> sides = new ArrayList<>();
        sides.add(side(launch, loader, tools, configs, "client"));
        if (Boolean.TRUE.equals(mods.get("serverMixins"))) sides.add(side(launch, loader, tools, configs, "server"));
        Map<String, Object> result = verdict(sides, loader, mods);
        result.put("launch", Json.map("task", task, "recorded", launch.recorded, "mode", launch.mode));
        result.put("seconds", (System.nanoTime() - begin) / 1e9);
        return result;
    }

    Map<String, Object> unchecked(String reason, Map<String, Object> mods) {
        return Json.map("ok", false, "tier", "mixins-unchecked", "checked", false, "loader", loader(model), "metadata", mods.get("metadata"),
                "reason", reason, "details", reason, "gameVerified", false);
    }

    // --- The mod's own mixin configs, as the loader reads them: from the processed resources ---

    /** Mixin config names the mod declares (fabric.mod.json "mixins", neoforge.mods.toml [[mixins]], or the MixinConfigs manifest attribute). */
    Map<String, Object> modMixins(Launch launch) throws IOException {
        Set<String> configs = new LinkedHashSet<>(); List<String> metadata = new ArrayList<>(); boolean server = false;
        Set<Path> roots = new LinkedHashSet<>();
        Set<String> onLaunch = new HashSet<>();
        if (launch != null) {
            List<String> entries = new ArrayList<>(Arrays.asList(launch.classPath().split(File.pathSeparator)));
            for (int i = 0; i + 1 < launch.options().size(); i++) if (List.of("--module-path", "-p").contains(launch.options().get(i))) entries.addAll(Arrays.asList(launch.options().get(i + 1).split(File.pathSeparator)));
            for (String entry : entries) if (!entry.isBlank()) onLaunch.add(normal(entry));
        }
        for (Object p : Json.array(model.get("projects"))) for (Object s : Json.array(Json.object(p).get("sourceSets"))) {
            var set = Json.object(s); if (Json.text(set, "name", "").toLowerCase(Locale.ROOT).contains("test")) continue;
            if (launch != null && ProjectModel.strings(set.get("output")).stream().noneMatch(output -> onLaunch.contains(normal(output)))) continue;
            for (String path : ProjectModel.strings(set.get("output"))) roots.add(Path.of(path));
            for (String path : ProjectModel.strings(set.get("sources"))) if (path.replace('\\', '/').endsWith("/resources")) roots.add(Path.of(path));
        }
        Pattern tomlConfig = Pattern.compile("(?m)^\\s*config\\s*=\\s*\"([^\"]+)\"");
        for (Path root : roots) {
            Path fabric = root.resolve("fabric.mod.json");
            if (Files.isRegularFile(fabric)) {
                metadata.add(fabric.toString());
                try {
                    for (Object item : Json.array(Json.object(Json.parse(Files.readString(fabric, StandardCharsets.UTF_8))).get("mixins")))
                        configs.add(item instanceof String name ? name : Json.text(Json.object(item), "config", ""));
                } catch (RuntimeException unreadable) { metadata.add("unreadable: " + fabric); }
            }
            for (String name : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
                Path toml = root.resolve(name); if (!Files.isRegularFile(toml)) continue;
                metadata.add(toml.toString());
                Matcher matcher = tomlConfig.matcher(Files.readString(toml, StandardCharsets.UTF_8));
                while (matcher.find()) if (!matcher.group(1).contains("${")) configs.add(matcher.group(1));
            }
            Path manifest = root.resolve("META-INF/MANIFEST.MF");
            if (Files.isRegularFile(manifest)) try (InputStream in = Files.newInputStream(manifest)) {
                String value = new Manifest(in).getMainAttributes().getValue("MixinConfigs");
                if (value != null) { metadata.add(manifest.toString()); for (String name : value.split(",")) if (!name.isBlank()) configs.add(name.trim()); }
            }
        }
        configs.removeIf(String::isBlank);
        // Server-only mixins are applied only on a dedicated server, so they need a server-side pass.
        for (String config : configs) for (Path root : roots) {
            Path file = root.resolve(config); if (!Files.isRegularFile(file)) continue;
            try { if (!Json.array(Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8))).get("server")).isEmpty()) server = true; }
            catch (RuntimeException unreadable) { }
        }
        return Json.map("configs", new ArrayList<>(configs), "metadata", metadata, "serverMixins", server, "roots", roots.stream().map(Path::toString).toList());
    }

    static String normal(String path) {
        String text = path.trim().replace('\\', '/');
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return Runtimes.WINDOWS ? text.toLowerCase(Locale.ROOT) : text;
    }

    static boolean missingRoots(Map<String, Object> mods) {
        for (Object root : Json.array(mods.get("roots"))) if (!Files.isDirectory(Path.of(root.toString()))) return true;
        return false;
    }

    String clientTask(Modaccel.Options options) {
        String task = options.get("client-task", "");
        if (!task.isEmpty()) return task;
        List<String> tasks = new ArrayList<>();
        for (Object p : Json.array(model.get("projects"))) for (Object t : Json.array(Json.object(p).get("tasks"))) {
            String path = Json.text(Json.object(t), "task", ""); if (path.endsWith(":runClient")) tasks.add(path);
        }
        return tasks.size() == 1 ? tasks.get(0) : null;
    }

    // --- The check's own classes, compiled once per source change (Java 8 class files: they run on the mod's Java) ---

    Path tools() throws Exception {
        List<Path> sources = new ArrayList<>(); for (String name : SOURCES) sources.add(build.harness.resolve(name));
        List<Path> stubs = new ArrayList<>(); for (String name : STUBS) stubs.add(build.harness.resolve(name));
        List<Path> all = new ArrayList<>(sources); all.addAll(stubs);
        Path directory = build.harness.resolve(".modaccel/mixin-check/" + Fingerprints.files(all).substring(0, 16));
        if (Files.exists(directory.resolve("ready"))) return directory;
        Runtimes.Jdk jdk = Runtimes.ensureAtLeast(build.harness, 17);
        Path stubClasses = directory.resolve("stubs"), classes = directory.resolve("classes");
        Files.createDirectories(stubClasses); Files.createDirectories(classes);
        String javac = jdk.home().resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString();
        List<String> stubCommand = new ArrayList<>(List.of(javac, "--release", "8", "-Xlint:-options", "-encoding", "UTF-8", "-d", stubClasses.toString()));
        stubs.forEach(p -> stubCommand.add(p.toString()));
        var compiled = Run.command(stubCommand, build.project.root, Map.of(), build.report.log("mixin-check-tools"), 120);
        if (compiled.exit() == 0) {
            List<String> command = new ArrayList<>(List.of(javac, "--release", "8", "-Xlint:-options", "-encoding", "UTF-8", "-cp", stubClasses.toString(), "-d", classes.toString()));
            sources.forEach(p -> command.add(p.toString()));
            compiled = Run.command(command, build.project.root, Map.of(), build.report.log("mixin-check-tools"), 120);
        }
        build.report.stage("mixin-check-tools", compiled, Map.of());
        if (compiled.exit() != 0) throw new IOException("The Mixin check's own classes did not compile. " + Run.tail(compiled.log()));
        // The recorder is a Java agent, so it needs a jar with a Premain-Class.
        agentJar(directory.resolve("recorder.jar"), classes, "ModaccelLaunchRecorder");
        agentJar(directory.resolve("neoforge-target.jar"), classes, "ModaccelNeoForgeTarget");
        Files.writeString(directory.resolve("ready"), "ok");
        return directory;
    }

    static void agentJar(Path jarFile, Path classes, String premain) throws IOException {
        Manifest manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0"); manifest.getMainAttributes().putValue("Premain-Class", premain);
        try (var jar = new JarOutputStream(Files.newOutputStream(jarFile), manifest)) {
            jar.putNextEntry(new JarEntry(premain + ".class")); Files.copy(classes.resolve(premain + ".class"), jar); jar.closeEntry();
        }
    }

    // --- The dev launch, recorded once per build fingerprint ---

    record Launch(String java, List<String> options, String classPath, String main, List<String> arguments, Path directory, Map<String, String> environment, boolean recorded, String mode) {}

    Launch launch(String task, String loader, Path tools, boolean refresh) throws Exception {
        String fingerprint = Fingerprints.build(model, build.project, build.init);
        String safe = task.replaceAll("[^A-Za-z0-9]+", "_");
        Path record = build.project.state().resolve("mixin-check/" + safe + ".json"), launchFile = build.project.state().resolve("mixin-check/" + safe + "-launch.json");
        Files.createDirectories(record.getParent());
        if (!refresh && Files.isRegularFile(record)) {
            var saved = Json.read(record);
            if (fingerprint.equals(saved.get("fingerprint")) && Files.isRegularFile(Path.of(Json.text(saved, "java", ""))))
                return fromRecord(saved, false);
        }
        // Gradle prepares the launch exactly as it would for the game (compile, resources, loader setup),
        // without downloading game assets, which applying mixins never reads.
        Files.deleteIfExists(record); Files.deleteIfExists(launchFile);
        boolean viaAgent = loader.equals("neoforge");
        Path recorded = build.project.state().resolve("mixin-check/" + safe + "-jvm.json"); Files.deleteIfExists(recorded);
        List<String> arguments = new ArrayList<>(List.of("-Dmodaccel.captureTask=" + task, "-Dmodaccel.launchFile=" + launchFile, "-Dmodaccel.skipAssets=true", "-Dmodaccel.fingerprint=" + fingerprint));
        if (viaAgent) arguments.add("-Dmodaccel.agent=" + tools.resolve("recorder.jar") + "=" + recorded);
        else arguments.add("-Dmodaccel.recordOnly=true");
        arguments.add(task);
        var prepared = build.gradle(arguments, "mixin-check-prepare");
        Map<String, Object> saved;
        if (viaAgent) {
            if (!Files.isRegularFile(recorded)) throw new IOException("Gradle did not start " + task + ", so its launch could not be recorded.\n" + Run.failure(prepared.log()) + "\nLog: " + prepared.log());
            saved = fromJvm(Json.read(recorded), launchFile);
        } else {
            if (prepared.exit() != 0 || !Files.isRegularFile(launchFile)) throw new IOException("Gradle could not prepare " + task + ".\n" + Run.failure(prepared.log()) + "\nLog: " + prepared.log());
            saved = fromTask(Json.read(launchFile));
        }
        saved.put("fingerprint", fingerprint); saved.put("task", task);
        Json.save(record, saved);
        return fromRecord(saved, true);
    }

    static Launch fromRecord(Map<String, Object> saved, boolean fresh) {
        Map<String, String> environment = new LinkedHashMap<>();
        Json.object(saved.get("environment")).forEach((k, v) -> { if (v != null) environment.put(k, v.toString()); });
        return new Launch(Json.text(saved, "java", ""), ProjectModel.strings(saved.get("options")), Json.text(saved, "classPath", ""), Json.text(saved, "main", ""),
                ProjectModel.strings(saved.get("arguments")), Path.of(Json.text(saved, "directory", "")), environment, fresh, Json.text(saved, "mode", ""));
    }

    /** The init script's record of the run task: JVM options, class path (possibly inside Loom's @argfile), main class, arguments. */
    static Map<String, Object> fromTask(Map<String, Object> launch) throws IOException {
        List<String> all = ProjectModel.strings(launch.get("arguments")), options = new ArrayList<>();
        String classPath = ""; int i = 0;
        for (; i < all.size(); i++) {
            String argument = all.get(i);
            if (List.of("-cp", "-classpath", "--class-path").contains(argument) && i + 1 < all.size()) { classPath = all.get(++i); continue; }
            if (argument.startsWith("@")) {
                // Java's last class path option wins; Loom keeps it in an @argfile.
                List<String> inFile = argumentFile(Path.of(argument.substring(1)));
                for (int j = 0; j < inFile.size(); j++) {
                    if (List.of("-cp", "-classpath", "--class-path").contains(inFile.get(j)) && j + 1 < inFile.size()) classPath = inFile.get(++j);
                    else options.add(inFile.get(j));
                }
                continue;
            }
            if (!argument.startsWith("-")) break;
            options.add(argument);
            if (TAKES_VALUE.contains(argument) && i + 1 < all.size()) options.add(all.get(++i));
        }
        if (i >= all.size()) throw new IOException("The recorded launch has no main class: " + all);
        String main = all.get(i);
        return Json.map("java", Json.text(launch, "executable", ""), "options", options, "classPath", classPath, "main", main,
                "arguments", all.subList(i + 1, all.size()), "directory", Json.text(launch, "directory", ""), "environment", launch.get("environment"), "mode", "recorded run task");
    }
    static final Set<String> TAKES_VALUE = Set.of("-p", "--module-path", "--add-modules", "--add-opens", "--add-exports", "--add-reads", "--patch-module", "--limit-modules", "--upgrade-module-path", "--enable-native-access");

    /**
     * What the recorder agent saw inside the launched JVM. The java launcher turns module options into
     * reserved jdk.module.* properties, which a new launch must pass as options again.
     */
    static Map<String, Object> fromJvm(Map<String, Object> jvm, Path launchFile) throws IOException {
        List<String> options = new ArrayList<>();
        String modulePath = Json.text(jvm, "modulePath", "");
        if (!modulePath.isEmpty()) options.addAll(List.of("--module-path", modulePath));
        for (String option : ProjectModel.strings(jvm.get("options"))) {
            if (option.startsWith("-javaagent:") && option.contains("recorder.jar")) continue;
            if (option.startsWith("-Djdk.module.path=")) continue;
            Matcher module = Pattern.compile("^-Djdk\\.module\\.(addmods|addopens|addexports|addreads|patch|limitmods|upgrade\\.path|enable\\.native\\.access)(?:\\.\\d+)?=(.*)$").matcher(option);
            if (module.matches()) {
                String flag = switch (module.group(1)) {
                    case "addmods" -> "--add-modules"; case "addopens" -> "--add-opens"; case "addexports" -> "--add-exports"; case "addreads" -> "--add-reads";
                    case "patch" -> "--patch-module"; case "limitmods" -> "--limit-modules"; case "upgrade.path" -> "--upgrade-module-path"; default -> "--enable-native-access";
                };
                options.addAll(List.of(flag, module.group(2))); continue;
            }
            if (option.startsWith("-Dmodaccel.") || option.startsWith("-Dsun.java.command") || option.equals("-Dfabric.noGui=true")) continue;
            options.add(option);
        }
        // sun.java.command is the main class and its arguments joined by spaces. Only the main class and
        // the loader's own --flag value pairs are used, and those never contain spaces.
        String[] command = Json.text(jvm, "command", "").trim().split("\\s+");
        List<String> arguments = new ArrayList<>(Arrays.asList(command).subList(Math.min(1, command.length), command.length));
        Map<String, Object> environment = Files.isRegularFile(launchFile) ? Json.object(Json.read(launchFile).get("environment")) : Map.of();
        return Json.map("java", Path.of(Json.text(jvm, "java", "")).resolve("bin/" + (Runtimes.WINDOWS ? "java.exe" : "java")).toString(), "options", options,
                "classPath", Json.text(jvm, "classPath", ""), "main", command.length == 0 ? "" : command[0], "arguments", arguments,
                "directory", Json.text(jvm, "directory", ""), "environment", environment, "mode", "recorded inside the launched JVM");
    }

    /** Java @argfile tokens: whitespace separated, double or single quotes, backslash escapes inside quotes. */
    static List<String> argumentFile(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8); List<String> tokens = new ArrayList<>(); StringBuilder token = null; char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else if (c == '\\' && i + 1 < text.length()) {
                    char n = text.charAt(++i);
                    token.append(switch (n) { case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r'; case 'f' -> '\f'; default -> n; });
                } else token.append(c);
            } else if (c == '"' || c == '\'') { if (token == null) token = new StringBuilder(); quote = c; }
            else if (Character.isWhitespace(c)) { if (token != null) { tokens.add(token.toString()); token = null; } }
            else if (c == '#' && token == null) { while (i < text.length() && text.charAt(i) != '\n') i++; }
            else { if (token == null) token = new StringBuilder(); token.append(c); }
        }
        if (token != null) tokens.add(token.toString());
        return tokens;
    }

    // --- One run of the check on one side ---

    Map<String, Object> side(Launch launch, String loader, Path tools, List<String> configs, String side) throws Exception {
        Path out = build.report.directory.resolve("mixin-check-" + side + ".jsonl"), argfile = build.report.directory.resolve("mixin-check-" + side + ".args");
        Files.createDirectories(out.getParent());
        List<String> command = new ArrayList<>(List.of(launch.java()));
        Set<String> agents = new HashSet<>();
        for (String option : launch.options()) {
            if (option.startsWith("-Dfabric.dli.env=") && side.equals("server")) { command.add("-Dfabric.dli.env=server"); continue; }
            // Recorded launches can list the same agent twice (Gradle's two JVM argument lists); load each once.
            if (option.startsWith("-javaagent:") && !agents.add(option)) continue;
            command.add(option);
        }
        command.addAll(List.of("-Dmixin.debug.injectors=true", "-Dmodaccel.mixincheck.out=" + out, "-Dmodaccel.mixincheck.configs=" + String.join(",", configs),
                "-Dmodaccel.mixincheck.side=" + side, "-Dmodaccel.mixincheck.loader=" + loader, "-Dfabric.noGui=true"));
        String main = launch.main(); List<String> arguments = new ArrayList<>(launch.arguments());
        if (loader.equals("fabric")) {
            // Loom starts Knot through its dev launch injector (and Architectury through its transformer first);
            // both read the next main class from fabric.dli.main, so only that changes.
            command.add("-Dfabric.dli.main=ModaccelMixinCheck");
            if (KNOT.contains(main)) main = "ModaccelMixinCheck";
            else if (!main.equals("net.fabricmc.devlaunchinjector.Main") && !main.startsWith("dev.architectury.transformer."))
                throw new IOException("Unrecognised Fabric dev launch (main class " + main + "); the Mixin check knows Knot, Loom's dev launch injector and Architectury's transformer.");
        } else {
            if (!main.equals(NEO_MAIN)) throw new IOException("Unrecognised NeoForge dev launch (main class " + main + "); the Mixin check needs BootstrapLauncher.");
            main = "ModaccelMixinCheck";
            // NeoForge's unit-test launch target sets up FML, its mods and Mixin with no early window, as junit-fml does.
            // It is fixed to the server side and then bootstraps the game; the agent makes it build the game layer
            // for the side being checked and return (see ModaccelNeoForgeTarget).
            command.add("-javaagent:" + tools.resolve("neoforge-target.jar") + "=" + side);
            List<String> kept = new ArrayList<>(List.of("--launchTarget", "forgejunituserdev"));
            for (int i = 0; i + 1 < arguments.size(); i++) if (arguments.get(i).startsWith("--fml.") && !arguments.get(i + 1).startsWith("--")) { kept.add(arguments.get(i)); kept.add(arguments.get(++i)); }
            arguments = kept;
        }
        String classPath = tools.resolve("classes") + File.pathSeparator + launch.classPath();
        Files.writeString(argfile, "-cp\n\"" + classPath.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n", StandardCharsets.UTF_8);
        command.add("@" + argfile); command.add(main); command.addAll(arguments);
        Map<String, String> environment = new HashMap<>(launch.environment());
        // The run directory is made by the loader plugin when it starts the game, so a fresh checkout has none yet.
        Files.createDirectories(launch.directory());
        var result = Run.command(command, launch.directory(), environment, build.report.log("mixin-check-" + side), TIMEOUT_SECONDS);
        build.report.stage("mixin-check-" + side, result, Json.map("launch", launch.recorded() ? "recorded now" : "reused recording"));
        List<Map<String, Object>> events = new ArrayList<>();
        if (Files.isRegularFile(out)) for (String line : Files.readAllLines(out, StandardCharsets.UTF_8)) if (!line.isBlank()) events.add(Json.object(Json.parse(line)));
        return Json.map("side", side, "exit", result.exit(), "seconds", result.seconds(), "log", result.log().toString(), "events", events);
    }

    // --- Mixin's results, sorted into what the game would do ---

    Map<String, Object> verdict(List<Map<String, Object>> sides, String loader, Map<String, Object> mods) throws IOException {
        List<Object> failures = new ArrayList<>(), dead = new ArrayList<>(), optional = new ArrayList<>(), notApplied = new ArrayList<>(), otherMods = new ArrayList<>(), notes = new ArrayList<>();
        Map<String, Boolean> applied = new TreeMap<>(); Set<String> ours = new HashSet<>(ProjectModel.strings(mods.get("configs")));
        Set<String> failedMixins = new HashSet<>(); boolean ran = true; String mixinVersion = ""; int targets = 0;
        List<Object> sideSummary = new ArrayList<>(); Set<String> seenConfigs = new HashSet<>();
        for (Map<String, Object> side : sides) {
            boolean started = false, done = false;
            for (Object item : Json.array(side.get("events"))) {
                var event = Json.object(item); String kind = Json.text(event, "event", "");
                switch (kind) {
                    case "start" -> { started = true; mixinVersion = Json.text(event, "mixin", ""); }
                    case "done" -> { done = true; if (event.get("targets") instanceof Number n) targets = Math.max(targets, n.intValue()); }
                    case "note" -> notes.add(Json.text(event, "message", ""));
                    case "config" -> seenConfigs.add(Json.text(event, "name", ""));
                    case "mixin" -> applied.merge(Json.text(event, "mixin", ""), Boolean.TRUE.equals(event.get("applied")), Boolean::logicalOr);
                    case "target" -> {
                        if (Boolean.TRUE.equals(event.get("loaded"))) break;
                        String message = Json.text(event, "message", "");
                        // A class that a failed mixin left unloadable is reported once, at the mixin's own error.
                        failures.add(finding("would-stop-game", null, Json.text(event, "target", ""), message, side.get("side"),
                                "Loading this class failed, so the game would stop when it first uses it."));
                    }
                    case "error" -> {
                        String config = Json.text(event, "config", ""), mixin = Json.text(event, "mixin", "");
                        if (Json.text(event, "phase", "").equals("loader")) { ran = false; notes.add("The loader did not start: " + Json.text(event, "message", "")); break; }
                        if (!ours.contains(config)) { otherMods.add(finding("other-mod", mixin, Json.text(event, "target", ""), Json.text(event, "message", ""), side.get("side"), "A mixin of another mod in this launch (" + config + ").")); break; }
                        failedMixins.add(mixin);
                        Integer required = integer(event.get("required")), injected = integer(event.get("injected"));
                        String message = Json.text(event, "message", "");
                        boolean validation = message.startsWith("Injection validation failed") || message.contains("\n  caused by InvalidInjectionException: Injection validation failed");
                        if (validation && required != null && injected != null && injected >= required) {
                            if (Boolean.TRUE.equals(event.get("requireDeclared")) && required == 0)
                                optional.add(finding("optional", mixin, Json.text(event, "target", ""), message, side.get("side"), "The mod marks this injection optional (require = 0), so the game starts without it."));
                            else dead.add(finding("dead", mixin, Json.text(event, "target", ""), message, side.get("side"), "The game would start, but this injection does nothing: Mixin found fewer places than it expects."));
                        } else if (!"ERROR".equals(Json.text(event, "action", "")) && !validation) {
                            dead.add(finding("dead", mixin, Json.text(event, "target", ""), message, side.get("side"), "The config is not required, so Mixin only logs this and the game starts without the mixin."));
                        } else failures.add(finding("would-stop-game", mixin, Json.text(event, "target", ""), message, side.get("side"), "Mixin stops the game on this."));
                    }
                    default -> { }
                }
            }
            if (!started || !done) ran = false;
            sideSummary.add(Json.map("side", side.get("side"), "exit", side.get("exit"), "seconds", side.get("seconds"), "log", side.get("log"), "completed", started && done));
        }
        // A declared config Mixin never loaded was not checked at all, which is not a pass.
        List<String> missing = new ArrayList<>(); for (String config : ours) if (!seenConfigs.contains(config)) missing.add(config);
        if (ran && !missing.isEmpty()) { ran = false; notes.add("Mixin never loaded the mod's config" + (missing.size() == 1 ? " " : "s ") + String.join(", ", missing) + ", so its mixins were not checked"); }
        for (var entry : applied.entrySet()) if (!entry.getValue() && !failedMixins.contains(entry.getKey()))
            notApplied.add(finding("not-applied", entry.getKey(), null, "Mixin reported no error, but did not apply this mixin to any loaded class.", null,
                    "Skipped without an error: its config plugin declined it, or its target is not in this game."));
        for (Object item : failures) attachSource(Json.object(item));
        for (Object item : dead) attachSource(Json.object(item));
        for (Object item : optional) attachSource(Json.object(item));
        for (Object item : notApplied) attachSource(Json.object(item));
        long appliedCount = applied.values().stream().filter(Boolean::booleanValue).count();
        boolean ok = ran && failures.isEmpty() && dead.isEmpty();
        String checkedBy = "Mixin " + mixinVersion + " in " + (loader.equals("fabric") ? "Fabric Loader's Knot (as fabric-loader-junit sets it up)" : "NeoForge's unit-test launch (forgejunituserdev, as junit-fml sets it up, built for the checked side and stopped before NeoForge bootstraps)")
                + ", every target class loaded, the game not started";
        StringBuilder details = new StringBuilder();
        if (!ran) details.append("The Mixin check did not complete").append(notes.isEmpty() ? "" : ": " + notes.get(notes.size() - 1)).append(". Log: ").append(Json.object(sideSummary.get(sideSummary.size() - 1)).get("log"));
        else {
            details.append(applied.size()).append(" mixins, ").append(appliedCount).append(" applied by Mixin to ").append(targets).append(" target classes");
            if (!failures.isEmpty()) details.append("; ").append(failures.size()).append(" would stop the game");
            if (!dead.isEmpty()) details.append("; ").append(dead.size()).append(" dead");
            if (!optional.isEmpty()) details.append("; ").append(optional.size()).append(" optional injections matched nothing (allowed by the mod)");
            if (!notApplied.isEmpty()) details.append("; ").append(notApplied.size()).append(" skipped without an error");
            details.append(".");
            for (Object item : failures) details.append("\n").append(line(Json.object(item)));
            for (Object item : dead) details.append("\n").append(line(Json.object(item)));
        }
        Map<String, Object> result = Json.map("ok", ok, "tier", "mixin-applied", "checked", ran, "checkedBy", checkedBy, "loader", loader,
                "mixins", Json.map("total", applied.size(), "applied", appliedCount, "targets", targets, "wouldStopGame", failures.size(), "dead", dead.size(),
                        "optionalUnmatched", optional.size(), "skippedWithoutError", notApplied.size()),
                "failures", failures, "dead", dead, "optional", optional, "skipped", notApplied, "otherMods", otherMods, "notes", notes,
                "sides", sideSummary, "configs", mods.get("configs"), "gameVerified", false, "behaviorVerified", false, "details", details.toString());
        if (!ok) result.put("reason", details.toString());
        return result;
    }
    static Integer integer(Object value) { return value instanceof Number n ? n.intValue() : null; }
    static Map<String, Object> finding(String kind, String mixin, String target, String message, Object side, String meaning) {
        return Json.map("kind", kind, "mixin", mixin, "target", target, "side", side, "message", message, "meaning", meaning);
    }
    static String line(Map<String, Object> finding) {
        String where = finding.get("file") != null ? finding.get("file") + (finding.get("line") != null ? ":" + finding.get("line") : "") : String.valueOf(finding.get(finding.get("mixin") != null ? "mixin" : "target"));
        return "  " + where + " [" + finding.get("kind") + "] " + String.valueOf(finding.get("message")).replace("\n", "\n    ");
    }

    /** The mixin's source file, and the handler's line when Mixin named it. Stonecutter's generated copies map back to src. */
    void attachSource(Map<String, Object> finding) throws IOException {
        String mixin = (String) finding.get("mixin"); if (mixin == null || mixin.isEmpty()) return;
        String relative = mixin.replaceFirst("\\$.*$", "").replace('.', '/') + ".java";
        for (Object p : Json.array(model.get("projects"))) for (Object s : Json.array(Json.object(p).get("sourceSets"))) for (String root : ProjectModel.strings(Json.object(s).get("java"))) {
            Path file = Path.of(root).resolve(relative); if (!Files.isRegularFile(file)) continue;
            String normalized = file.toString().replace('\\', '/');
            Matcher generated = Pattern.compile("^(.*)/build/generated/stonecutter/([^/]+)/([^/]+)/(.*)$").matcher(normalized);
            if (generated.matches()) {
                Path source = Path.of(generated.group(1), "src", generated.group(2), generated.group(3), generated.group(4));
                if (!Files.isRegularFile(source)) source = build.project.root.resolve("src/" + generated.group(2) + "/" + generated.group(3) + "/" + generated.group(4));
                if (Files.isRegularFile(source)) file = source;
            }
            finding.put("file", build.project.root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/'));
            String handler = handlerName(String.valueOf(finding.get("message")));
            if (handler != null) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                Pattern declaration = Pattern.compile("\\b" + Pattern.quote(handler) + "\\s*\\(");
                for (int i = 0; i < lines.size(); i++) if (declaration.matcher(lines.get(i)).find() && !lines.get(i).trim().startsWith("//")) { finding.put("line", i + 1); break; }
            }
            return;
        }
    }
    /** The handler method Mixin names: "annotation on NAME", a merged "handler$id$mod$NAME(", or "Class::NAME(". */
    static String handlerName(String message) {
        for (String pattern : List.of("annotation on ([A-Za-z_$][A-Za-z0-9_$]*)", "\\b[a-z]+\\$[a-z0-9]+\\$[^$\\s]+\\$([A-Za-z_][A-Za-z0-9_]*)\\(", "::([A-Za-z_][A-Za-z0-9_]*)\\(")) {
            Matcher matcher = Pattern.compile(pattern).matcher(message);
            if (matcher.find()) return matcher.group(1);
        }
        return null;
    }
}
