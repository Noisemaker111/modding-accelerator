import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * pack-check's client: the pack's mods in the loader's own client, the way a player starts it, with mmt's control agent.
 *
 * The client comes from the loader's installer (its "installClient" arguments in knowledge/pack-loaders.json), which
 * writes a launcher install: the loader's version file over the game's, the loader's libraries and the vanilla client
 * jar. The game's own libraries and asset index come from Mojang's version file. The launch is built from those
 * version files as a launcher builds it, hidden and muted as the player's mmt preferences say, and carries
 * tools/agent. One start loads every mod to the title screen, opens the flat world fingerprint makes with the vanilla
 * server, shows the inventory and every creative tab (each draws every item a mod adds to it), and quits. A start that
 * stops, or reaches the loader's error screen, names the mods its log and crash report point at.
 */
final class PackClient {
    private PackClient() {}

    static final String OS = Runtimes.WINDOWS ? "windows" : System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac") ? "osx" : "linux";

    /** The loader's client, installed once per version, with the game's libraries and asset index beside it; null when the loader has no client installer. */
    static Path install(PackLoaders.Context context, PackLoaders.Loader loader, String minecraft, String version) throws Exception {
        var style = (PackLoaders.Style) loader;
        List<Object> arguments = Json.array(style.spec.getOrDefault("installClient", List.of()));
        String profile = Json.text(style.spec, "clientProfile", "");
        if (arguments.isEmpty() && profile.isEmpty()) return null;
        Path dir = context.cache().resolve("loaders").resolve(loader.id() + "-client-" + minecraft + "-" + version);
        if (PackLoaders.Style.finished(dir)) return dir;
        if (!profile.isEmpty()) {
            // The loader publishes its launcher profile itself (Fabric's meta): its version file over the game's, which
            // comes from Mojang's list as a launcher gets it.
            Path game = dir.resolve("versions").resolve(minecraft).resolve(minecraft + ".json");
            Files.createDirectories(game.getParent());
            Files.writeString(game, Json.write(PackLoaders.Vanilla.version(context.cache(), context.harness(), minecraft)));
            var own = Json.object(Json.parse(new String(PackCheck.get(style.fill(profile, minecraft, version, dir)), StandardCharsets.UTF_8)));
            String id = Json.text(own, "id", loader.id() + "-" + minecraft);
            Path file = dir.resolve("versions").resolve(id).resolve(id + ".json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.write(own));
        } else {
            Path installer = dir.resolve("installer.jar");
            PackCheck.download(style.fill(style.setting(minecraft, "installer"), minecraft, version, null), installer, null);
            // Installers that write into a launcher folder refuse one without a profile list.
            Files.writeString(dir.resolve("launcher_profiles.json"), "{\"profiles\":{}}");
            List<String> filled = new ArrayList<>();
            for (Object a : arguments) filled.add(style.fill(String.valueOf(a), minecraft, version, dir));
            style.runInstaller(context, installer, filled, dir, version);
        }
        Map<String, Object> merged = merged(dir, minecraft);
        for (Object o : Json.array(merged.get("libraries"))) {
            var library = Json.object(o);
            if (!allowed(library.get("rules"))) continue;
            String[] artifact = artifact(library);
            if (artifact == null) continue;
            Path target = dir.resolve("libraries").resolve(artifact[0]);
            if (!Files.isRegularFile(target)) PackCheck.download(artifact[1], target, artifact[2]);
        }
        // A launcher runs the game's jar under the name of the version it starts (a loader's ignore list names it so).
        var client = Json.object(Json.object(merged.getOrDefault("downloads", Map.of())).getOrDefault("client", Map.of()));
        Path jar = gameJar(dir, merged);
        if (!client.isEmpty() && !Files.isRegularFile(jar)) PackCheck.download(Json.text(client, "url", ""), jar, Json.text(client, "sha1", null));
        var index = Json.object(merged.get("assetIndex"));
        PackCheck.download(Json.text(index, "url", ""), dir.resolve("assets/indexes/" + Json.text(index, "id", "") + ".json"), Json.text(index, "sha1", null));
        Files.writeString(dir.resolve("mmt-installed"), minecraft + "-" + version + "\n");
        return dir;
    }

    static Path gameJar(Path install, Map<String, Object> merged) {
        String id = Json.text(merged, "id", "");
        return install.resolve("versions").resolve(id).resolve(id + ".jar");
    }

    /**
     * {path under libraries/, url, sha1 or null} of a library a version file lists: its "downloads" entry, or else its
     * maven coordinates under its repository ("url", Mojang's by default), as Fabric's profile lists them; null for none.
     */
    static String[] artifact(Map<String, Object> library) {
        var artifact = Json.object(Json.object(library.getOrDefault("downloads", Map.of())).getOrDefault("artifact", Map.of()));
        if (!artifact.isEmpty()) return new String[] { Json.text(artifact, "path", ""), Json.text(artifact, "url", ""), Json.text(artifact, "sha1", null) };
        String[] name = Json.text(library, "name", "").split(":");
        if (name.length < 3 || library.containsKey("downloads")) return null;
        String path = name[0].replace('.', '/') + "/" + name[1] + "/" + name[2] + "/" + name[1] + "-" + name[2] + (name.length > 3 ? "-" + name[3] : "") + ".jar";
        String repository = Json.text(library, "url", "https://libraries.minecraft.net/");
        return new String[] { path, repository + (repository.endsWith("/") ? "" : "/") + path, Json.text(library, "sha1", null) };
    }

    /** The loader's version file with the game's under it (inheritsFrom), as a launcher merges them: lists joined, the loader's first. */
    static Map<String, Object> merged(Path install, String minecraft) throws IOException {
        Map<String, Object> child = null;
        try (var list = Files.list(install.resolve("versions"))) {
            for (Path dir : list.toList()) {
                Path file = dir.resolve(dir.getFileName() + ".json");
                if (!Files.isRegularFile(file)) continue;
                var version = Json.read(file);
                if (minecraft.equals(version.get("inheritsFrom"))) child = version;
            }
        }
        if (child == null) throw new IOException("The loader's installer wrote no version file over Minecraft " + minecraft + " in " + install.resolve("versions"));
        var parent = Json.read(install.resolve("versions").resolve(minecraft).resolve(minecraft + ".json"));
        Map<String, Object> out = new LinkedHashMap<>(parent);
        out.putAll(child);
        // One version of a library: the loader's, as a launcher keeps it (two ASM builds on one module path stop the start).
        List<Object> libraries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object o : concat(Json.array(child.get("libraries")), Json.array(parent.get("libraries")))) {
            String[] name = Json.text(Json.object(o), "name", "").split(":");
            String key = name.length < 2 ? Json.text(Json.object(o), "name", "") : name[0] + ":" + name[1] + (name.length > 3 ? ":" + name[3] : "");
            if (seen.add(key)) libraries.add(o);
        }
        out.put("libraries", libraries);
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (String side : List.of("jvm", "game")) {
            List<Object> joined = new ArrayList<>(Json.array(Json.object(parent.getOrDefault("arguments", Map.of())).get(side)));
            joined.addAll(Json.array(Json.object(child.getOrDefault("arguments", Map.of())).get(side)));
            arguments.put(side, joined);
        }
        out.put("arguments", arguments);
        out.put("assetIndex", parent.get("assetIndex"));
        return out;
    }

    static List<Object> concat(List<Object> a, List<Object> b) {
        List<Object> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** A launcher's rules: the last rule that matches this system decides; a rule on a launcher feature never matches. */
    static boolean allowed(Object rules) {
        if (rules == null) return true;
        boolean allow = false;
        for (Object o : Json.array(rules)) {
            var rule = Json.object(o);
            if (rule.containsKey("features")) continue;
            var os = Json.object(rule.getOrDefault("os", Map.of()));
            boolean matches = !os.containsKey("name") || OS.equals(os.get("name"));
            if (matches && os.containsKey("arch")) matches = System.getProperty("os.arch").contains(String.valueOf(os.get("arch")));
            if (matches) allow = "allow".equals(rule.get("action"));
        }
        return allow;
    }

    /** The launch, from the merged version file, as a launcher writes it: the JVM's options, the main class and the game's arguments. */
    static List<String> command(Path install, String minecraft, Path java, Path instance, List<String> options) throws IOException {
        var version = merged(install, minecraft);
        String libraries = install.resolve("libraries").toString();
        List<String> classpath = new ArrayList<>();
        for (Object o : Json.array(version.get("libraries"))) {
            var library = Json.object(o);
            String[] artifact = artifact(library);
            if (artifact == null || !allowed(library.get("rules"))) continue;
            String jar = Path.of(libraries, artifact[0]).toString();
            if (!classpath.contains(jar)) classpath.add(jar);
        }
        Path game = gameJar(install, version);
        if (Files.isRegularFile(game)) classpath.add(game.toString());
        Path natives = Files.createDirectories(instance.resolve("natives"));
        Map<String, String> values = new HashMap<>();
        values.put("library_directory", libraries);
        values.put("classpath_separator", File.pathSeparator);
        values.put("classpath", String.join(File.pathSeparator, classpath));
        values.put("natives_directory", natives.toString());
        values.put("version_name", Json.text(version, "id", minecraft));
        values.put("launcher_name", "mmt");
        values.put("launcher_version", "1");
        values.put("game_directory", instance.toString());
        values.put("assets_root", install.resolve("assets").toString());
        values.put("assets_index_name", Json.text(Json.object(version.get("assetIndex")), "id", ""));
        values.put("auth_player_name", "Dev");
        values.put("auth_uuid", "00000000-0000-0000-0000-000000000000");
        values.put("auth_access_token", "0");
        values.put("clientid", "0");
        values.put("auth_xuid", "0");
        values.put("user_type", "legacy");
        values.put("version_type", "release");
        List<String> command = new ArrayList<>(List.of(java.toString()));
        command.addAll(options);
        Map<String, Object> arguments = Json.object(version.get("arguments"));
        command.addAll(arguments(arguments.get("jvm"), values));
        command.add(Json.text(version, "mainClass", ""));
        command.addAll(arguments(arguments.get("game"), values));
        return command;
    }

    static final Pattern VALUE = Pattern.compile("\\$\\{(\\w+)}");

    static List<String> arguments(Object list, Map<String, String> values) {
        List<String> out = new ArrayList<>();
        for (Object o : Json.array(list)) {
            List<Object> each = new ArrayList<>();
            if (o instanceof String s) each.add(s);
            else if (allowed(Json.object(o).get("rules"))) {
                Object value = Json.object(o).get("value");
                each.addAll(value instanceof List<?> l ? l : List.of(String.valueOf(value)));
            }
            for (Object a : each) {
                Matcher m = VALUE.matcher(String.valueOf(a));
                StringBuilder sb = new StringBuilder();
                while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(values.getOrDefault(m.group(1), m.group())));
                out.add(m.appendTail(sb).toString());
            }
        }
        return out;
    }

    /**
     * One client start with the given mods: the title screen, the flat world, the inventory and every creative tab, then
     * quit. Returns what it reached ("opened" true when every step ran), and on a failure why and the mods it names.
     */
    static Map<String, Object> check(PackCheck owner, PackCheck.Pack pack, PackLoaders.Context context, Path install, List<PackCheck.Mod> mods) throws Exception {
        long began = System.nanoTime();
        Path instance = PackCheck.receipts().resolve("client").resolve(Integer.toHexString((pack.name() + pack.minecraft() + pack.loaderVersion()).hashCode()));
        PackCheck.deleteTree(instance);
        Files.createDirectories(instance.resolve("mods"));
        if (pack.overrides() != null && Files.isDirectory(pack.overrides())) PackCheck.copyTree(pack.overrides(), instance);
        for (PackCheck.Mod mod : mods) PackCheck.link(mod.jar(), instance.resolve("mods").resolve(mod.file()));
        Path world = Fingerprint.template(owner.harness, pack.minecraft(), 0, context.report());
        PackCheck.copyTree(world, instance.resolve("saves/lab"));
        Path asm;
        try (var walk = Files.walk(install.resolve("libraries/org/ow2/asm/asm"))) {
            asm = walk.filter(p -> p.getFileName().toString().matches("asm-[0-9.]+\\.jar")).max(Comparator.naturalOrder()).orElseThrow();
        }
        Path agent = Game.agentJar(owner.harness, asm, PackCheck.receipts().resolve("agents"), instance, context.report());
        Map<String, Object> preferences = Preferences.read(Preferences.file());
        String token = UUID.randomUUID().toString();
        List<String> options = new ArrayList<>(List.of(
            "-Xmx4G", "-XX:TieredStopAtLevel=1",
            "-javaagent:" + agent,
            "-Dmmt.token=" + token, "-Dmmt.state=" + Files.createDirectories(instance.resolve(".mmt")), "-Dmmt.root=" + instance,
            "-Dmmt.fingerprint=pack-check-client", "-Dmmt.c1=true",
            "-Dmmt.background=" + preferences.get("background"), "-Dmmt.quiet=" + preferences.get("quiet")
        ));
        // The game's classes for the agent's snippets: the loader's named game jar and its own jars (they sit on no class path).
        List<String> game = new ArrayList<>();
        for (Object o : Json.array(((PackLoaders.Style) owner.loader).spec.getOrDefault("clientClasses", List.of()))) {
            var where = Json.object(o);
            Path dir = install.resolve(Json.text(where, "dir", ""));
            if (!Files.isDirectory(dir)) continue;
            try (var walk = Files.walk(dir)) {
                walk.filter(p -> p.getFileName().toString().matches(Json.text(where, "match", ""))).forEach(p -> game.add(p.toString()));
            }
        }
        // A client that runs other names than Mojang's (Fabric's intermediary before 26.1): the agent gets the table of both,
        // and its snippets compile against the game in Mojang's names.
        PackLoaders.Names names = owner.loader.names(context, install, pack.minecraft());
        if ("intermediary".equals(names.kind())) {
            Path[] mojang = runtimeNames(context, install, pack.minecraft(), names.mapping());
            options.add("-Dmmt.names=" + mojang[0]);
            game.add(0, mojang[1].toString());
        }
        options.add("-Dmmt.classpath=" + String.join(File.pathSeparator, game));
        List<String> command = command(install, pack.minecraft(), context.java().java(), instance, options);
        Path log = context.report().log("client");
        Files.createDirectories(log.getParent());
        Files.writeString(instance.resolve("mmt-launch.txt"), String.join("\n", command) + "\n");
        long since = System.currentTimeMillis();
        java.time.Instant launched = java.time.Instant.now();
        Process process = new ProcessBuilder(command).directory(instance.toFile()).redirectErrorStream(true).start();
        CompletableFuture<Map<String, Object>> ready = new CompletableFuture<>();
        // Programs a mod starts from the game, seen while it runs: one started as the game dies has no parent left to find it by.
        Set<ProcessHandle> started = ConcurrentHashMap.newKeySet();
        Thread reader = new Thread(() -> {
            try (var in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                 var out = Files.newBufferedWriter(log, StandardCharsets.UTF_8)) {
                int lines = 0;
                for (String line; (line = in.readLine()) != null; ) {
                    if (++lines % 200 == 0 || line.contains("Exception") || line.contains("crash")) process.descendants().forEach(started::add);
                    out.write(line);
                    out.newLine();
                    out.flush();
                    int at = line.indexOf("MMT_READY ");
                    if (at >= 0) ready.complete(Json.object(Json.parse(line.substring(at + "MMT_READY ".length()))));
                }
            } catch (Exception unreadable) {
                ready.completeExceptionally(unreadable);
            }
            int exit;
            try {
                exit = process.waitFor();
            } catch (InterruptedException stopped) {
                exit = -1;
            }
            ready.completeExceptionally(new IOException("The client stopped before its first frame (exit " + exit + ")"));
        }, "mmt-client-log");
        reader.setDaemon(true);
        reader.start();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("log", log.toString());
        result.put("mods", mods.size());
        List<String> reached = new ArrayList<>();
        result.put("reached", reached);
        try {
            Map<String, Object> session;
            try {
                session = ready.get(15, TimeUnit.MINUTES);
            } catch (ExecutionException stopped) {
                throw stopped.getCause() instanceof Exception e ? e : stopped;
            }
            session.put("log", log.toString());
            session.put("processStart", ProcessHandle.of(((Number) session.get("pid")).longValue()).flatMap(p -> p.info().startInstant()).map(Object::toString).orElse(""));
            String screen = Json.text(Game.request(session, Json.map("command", "ready")), "screen", "");
            if (screen.contains("Error") && !screen.equals("TitleScreen")) throw new IOException("the loader showed its error screen (" + screen + ") instead of the title screen");
            reached.add("title");
            Game.request(session, Json.map("command", "open-world", "world", "lab"));
            Game.request(session, Json.map("command", "ready", "expectWorld", true));
            reached.add("world");
            snippet(session, "var game = net.minecraft.client.Minecraft.getInstance();\ngame.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(game.player));\nreturn true;");
            Game.request(session, Json.map("command", "ready", "requireScreen", true));
            reached.add("inventory");
            Object tabs = snippet(session, String.join("\n",
                "var game = net.minecraft.client.Minecraft.getInstance();",
                "var screen = new net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen(game.player, game.player.connection.enabledFeatures(), true);",
                "game.setScreen(screen);",
                "vars.put(\"tabs\", net.minecraft.world.item.CreativeModeTabs.allTabs());",
                "return ((java.util.List<?>) vars.get(\"tabs\")).size();"));
            Game.request(session, Json.map("command", "ready", "requireScreen", true));
            int count = Integer.parseInt(String.valueOf(tabs));
            for (int i = 0; i < count; i++) {
                // A tab is drawn on the frame after it is chosen; the next request is answered after that frame.
                snippet(session, String.join("\n",
                    "var game = net.minecraft.client.Minecraft.getInstance();",
                    "var tab = ((java.util.List<?>) vars.get(\"tabs\")).get(" + i + ");",
                    // call() finds the private method by Mojang's name in the names the game runs.
                    "call(game.screen, \"selectTab\", tab);",
                    "return true;"));
                Game.request(session, Json.map("command", "ready", "requireScreen", true));
            }
            reached.add("creative tabs: " + count);
            result.put("opened", true);
            try {
                Game.request(session, Json.map("command", "quit"));
            } catch (IOException closing) {
                // the game closes its control connection as it quits
            }
        } catch (Exception failure) {
            result.put("opened", false);
            String message = String.valueOf(failure.getMessage());
            if (process.isAlive() && message.contains("Snippet did not compile")) {
                // pack-check's own step does not fit this game version: no mod is at fault.
                process.destroyForcibly();
                result.put("toolError", "pack-check's client step does not compile against Minecraft " + pack.minecraft() + ": " + message);
                return result;
            }
            if (!process.waitFor(2, TimeUnit.MINUTES)) process.destroyForcibly();
            String text = Files.isRegularFile(log) ? new String(Files.readAllBytes(log), StandardCharsets.UTF_8) : "";
            List<String> reports = PackChecks.crashReports(instance, since);
            result.put("failure", String.valueOf(failure.getMessage()).lines().findFirst().orElse("") + (reports.isEmpty() ? "" : "; " + PackChecks.failure(reports.get(0), List.of())));
            result.put("suspects", PackChecks.suspects(text, reports, owner.scan, owner.loader));
        } finally {
            // Programs a mod starts from the game (a crash helper's own window) end with it: none outlives the check.
            process.descendants().forEach(started::add);
            if (!process.waitFor(2, TimeUnit.MINUTES)) process.destroyForcibly();
            process.descendants().forEach(started::add);
            for (ProcessHandle child : started) child.destroyForcibly();
            strays(process.pid(), launched, install, instance);
            result.put("seconds", (System.nanoTime() - began) / 1e9);
        }
        return result;
    }

    /**
     * Ends every process the game started, and theirs, after the game itself is gone. A program a mod starts from the game
     * (a crash helper with its own window, run on the game's own libraries) may be started through a short-lived launcher,
     * so by the end it is nobody's child; it is found as a process started during the check whose command line names the
     * client install or the game folder, and as a child of the game where the system still records the parent.
     */
    static void strays(long game, java.time.Instant since, Path install, Path instance) {
        Set<ProcessHandle> found = new LinkedHashSet<>();
        String[] marks = { install.toAbsolutePath().toString(), instance.toAbsolutePath().toString() };
        if (Runtimes.WINDOWS) {
            try {
                ProcessBuilder list = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "$since = [datetime]::Parse($env:MMT_SINCE).ToUniversalTime(); Get-CimInstance Win32_Process | Where-Object { " +
                    "$_.ProcessId -ne [int]$env:MMT_SELF -and ($_.ParentProcessId -eq [int]$env:MMT_GAME -or ($_.CreationDate.ToUniversalTime() -ge $since -and $_.CommandLine -and " +
                    "($_.CommandLine.Contains($env:MMT_INSTALL) -or $_.CommandLine.Contains($env:MMT_INSTANCE)))) } | ForEach-Object { $_.ProcessId }")
                    .redirectErrorStream(true);
                Map<String, String> env = list.environment();
                env.put("MMT_SINCE", since.toString());
                env.put("MMT_SELF", String.valueOf(ProcessHandle.current().pid()));
                env.put("MMT_GAME", String.valueOf(game));
                env.put("MMT_INSTALL", marks[0]);
                env.put("MMT_INSTANCE", marks[1]);
                Process running = list.start();
                String text = new String(running.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                running.waitFor(60, TimeUnit.SECONDS);
                for (String line : text.split("\\R")) if (line.strip().matches("\\d+")) ProcessHandle.of(Long.parseLong(line.strip())).ifPresent(found::add);
            } catch (IOException | InterruptedException unavailable) {
                // without the process list a stray keeps running until it quits itself
            }
        } else {
            ProcessHandle.allProcesses()
                .filter(p -> p.pid() != ProcessHandle.current().pid() && p.info().startInstant().map(t -> !t.isBefore(since)).orElse(false))
                .filter(p -> p.info().commandLine().map(c -> c.contains(marks[0]) || c.contains(marks[1])).orElse(false))
                .forEach(found::add);
        }
        for (ProcessHandle p : found) p.destroyForcibly();
        for (ProcessHandle p : found) {
            try {
                p.onExit().get(30, TimeUnit.SECONDS);
            } catch (Exception still) {
                // reported by the next start, which cannot reuse the folder
            }
        }
    }

    /**
     * {table, jar}: every class and member of the client in Mojang's names and in the loader's run-time names (written for
     * the agent, tools/agent PortableAgent.Names), and the client renamed to Mojang's names. Both come from the game's
     * own files: Mojang's client mappings (named -> obfuscated) joined through the obfuscated names with the loader's
     * (obfuscated -> run time, Fabric's intermediary tiny). Kept per game version.
     */
    static Path[] runtimeNames(PackLoaders.Context context, Path install, String minecraft, Path tiny) throws Exception {
        Path dir = context.cache().resolve("minecraft").resolve(minecraft);
        Path table = dir.resolve("client-runtime-names.tsv"), jar = dir.resolve("client-mojang.jar");
        var merged = merged(install, minecraft);
        var downloads = Json.object(merged.getOrDefault("downloads", Map.of()));
        var named = Json.object(downloads.getOrDefault("client_mappings", Map.of()));
        Path mappings = dir.resolve("client.txt");
        if (!Files.isRegularFile(mappings)) PackCheck.download(Json.text(named, "url", ""), mappings, Json.text(named, "sha1", null));
        if (!Files.isRegularFile(table)) {
            Path part = dir.resolve(table.getFileName() + ".tmp");
            Files.writeString(part, runtimeTable(Files.readString(mappings), Files.readString(tiny)));
            Files.move(part, table, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!Files.isRegularFile(jar)) {
            Path part = dir.resolve("client-mojang.jar.tmp");
            PackLoaders.rename(context, "client-mojang-names", gameJar(install, merged), part, mappings, true, List.of());
            Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
        }
        return new Path[] { table, jar };
    }

    static final Pattern PROGUARD_METHOD = Pattern.compile("^\\s+(?:\\d+:\\d+:)?(\\S+) ([^ (]+)\\(([^)]*)\\)(?::\\d+(?::\\d+)?)? -> (\\S+)$");
    static final Pattern PROGUARD_FIELD = Pattern.compile("^\\s+(\\S+) (\\S+) -> (\\S+)$");

    /** The table: "c MOJANG RUNTIME", "m OWNER NAME DESC RUNTIME", "f OWNER NAME RUNTIME" (Mojang names, internal form). */
    static String runtimeTable(String proguard, String tiny) {
        Map<String, String> runtimeClass = new HashMap<>(), runtimeMember = new HashMap<>();
        String owner = null;
        for (String line : tiny.split("\n")) {
            String[] p = line.split("\t", -1);
            if (line.startsWith("c\t") && p.length >= 3) {
                owner = p[1];
                runtimeClass.put(p[1], p[2]);
            } else if (owner != null && p.length >= 5 && p[0].isEmpty() && (p[1].equals("m") || p[1].equals("f"))) {
                runtimeMember.put(owner + "." + p[3] + p[2], p[4]);
            }
        }
        Map<String, String> obf = new HashMap<>();
        for (String line : proguard.split("\n")) {
            if (!line.startsWith(" ") && !line.startsWith("#") && line.endsWith(":") && line.contains(" -> ")) {
                String[] p = line.substring(0, line.length() - 1).split(" -> ");
                obf.put(p[0].replace('.', '/'), p[1].replace('.', '/'));
            }
        }
        StringBuilder out = new StringBuilder();
        String mojang = null, obfOwner = null;
        for (String line : proguard.split("\n")) {
            line = line.stripTrailing();
            if (line.startsWith("#") || line.isEmpty()) continue;
            if (!line.startsWith(" ")) {
                mojang = line.substring(0, line.indexOf(" -> ")).replace('.', '/');
                obfOwner = obf.get(mojang);
                out.append("c\t").append(mojang).append('\t').append(runtimeClass.getOrDefault(obfOwner, obfOwner)).append('\n');
                continue;
            }
            Matcher m = PROGUARD_METHOD.matcher(line);
            if (m.matches()) {
                StringBuilder args = new StringBuilder("(");
                for (String a : m.group(3).isEmpty() ? new String[0] : m.group(3).split(",")) args.append(descriptor(a, null));
                String desc = args + ")" + descriptor(m.group(1), null);
                String obfDesc = "(" + Arrays.stream(m.group(3).isEmpty() ? new String[0] : m.group(3).split(",")).map(a -> descriptor(a, obf)).reduce("", String::concat)
                    + ")" + descriptor(m.group(1), obf);
                String runtime = runtimeMember.get(obfOwner + "." + m.group(4) + obfDesc);
                if (runtime != null) out.append("m\t").append(mojang).append('\t').append(m.group(2)).append('\t').append(desc).append('\t').append(runtime).append('\n');
                continue;
            }
            Matcher f = PROGUARD_FIELD.matcher(line);
            if (f.matches()) {
                String runtime = runtimeMember.get(obfOwner + "." + f.group(3) + descriptor(f.group(1), obf));
                if (runtime != null) out.append("f\t").append(mojang).append('\t').append(f.group(2)).append('\t').append(runtime).append('\n');
            }
        }
        return out.toString();
    }

    /** A Java type as Mojang's mappings write it ("int[]", "net.minecraft.world.Foo"), as a descriptor; classes through obf when given. */
    static String descriptor(String type, Map<String, String> obf) {
        StringBuilder arrays = new StringBuilder();
        while (type.endsWith("[]")) {
            arrays.append('[');
            type = type.substring(0, type.length() - 2);
        }
        String base = switch (type) {
            case "void" -> "V";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> {
                String internal = type.replace('.', '/');
                yield "L" + (obf == null ? internal : obf.getOrDefault(internal, internal)) + ";";
            }
        };
        return arrays + base;
    }

    static Object snippet(Map<String, Object> session, String code) throws Exception {
        return Game.request(session, Json.map("command", "eval", "code", code)).get("value");
    }
}
