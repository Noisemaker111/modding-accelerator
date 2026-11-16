import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.*;

/**
 * pack-run: a modpack's game client, started the way a player's launcher starts it, and left running for eval, perf,
 * perf-toggle and game-stop (each with --root INSTANCE). The loader's own installer installs its client once; the
 * game's libraries, natives and assets come from Mojang's version file, as a launcher gets them; the pack's mods (every
 * jar, as a launcher installs them, but any --without) and its configs go into an instance folder that keeps its saves between
 * starts. mmt's control agent rides along, so the client is hidden and muted as the preferences say.
 */
final class PackClient {

    /** In an instance's state folder: what pack-run started there, so eval, game-status and game-stop accept it as --root. */
    static final String MARKER = "pack-client.json";

    private PackClient() {}

    /** Whether ROOT is a pack client's instance (pack-run made it), not a mod's Gradle checkout. */
    static boolean instance(Path root) {
        return Files.isRegularFile(root.toAbsolutePath().normalize().resolve(".mmt/portable").resolve(MARKER));
    }

    static Map<String, Object> run(Path harness, Mmt.Options options, Mmt.Report report) throws Exception {
        Path input = Path.of(options.get("pack", "")).toAbsolutePath().normalize();
        if (options.get("pack", "").isEmpty() || !Files.exists(input)) throw new IllegalArgumentException(
            "Name the pack: mmt pack-run --pack PACK.zip|PACK.mrpack|FOLDER. Got: " + options.get("pack", "(nothing)")
        );
        long started = System.nanoTime();
        PackCheck check = new PackCheck(harness, options, report);
        PackCheck.Pack pack = check.read(input);
        if (!check.unavailable.isEmpty()) throw new IllegalArgumentException(
            check.unavailable.size() + " mod file(s) the pack lists cannot be downloaded any more: " +
            String.join("; ", check.unavailable.stream().map(PackCheck.Unavailable::describe).toList()) +
            ". Run mmt pack-check --pack PACK --fix and start its fixed copy."
        );
        PackLoaders.Loader loader = PackLoaders.find(harness, pack.loader());
        if (!(loader instanceof PackLoaders.Style style) || style.setting(pack.minecraft(), "clientInstall").isEmpty() &&
            Json.array(style.spec.get("clientInstall")).isEmpty()) throw new IllegalArgumentException(
            "pack-run cannot install a " + pack.loader() + " client yet: knowledge/pack-loaders.json gives that loader no clientInstall."
        );
        Path cache = PackCheck.receipts();
        Runtimes.Jdk java = Runtimes.ensureExact(harness, PackLoaders.Vanilla.java(cache, harness, pack.minecraft()));
        PackLoaders.Context context = new PackLoaders.Context(harness, cache, report, java);
        if (pack.loaderVersion().isBlank()) pack = pack.withLoaderVersion(loader.newest(context, pack.minecraft()));
        PackMods.Scan scan = PackMods.scan(pack.mods(), loader.metadata(pack.minecraft()));
        List<PackCheck.Mod> mods = PackCheck.sides(pack.mods(), scan);
        List<String> without = Arrays.stream(options.get("without", "").split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        Map<String, String> leftOut = leftOut(mods, scan, without);
        report.stages.add(Json.map("name", "read", "seconds", (System.nanoTime() - started) / 1e9));

        started = System.nanoTime();
        Path install = installClient(style, context, pack.minecraft(), pack.loaderVersion());
        Profile profile = profile(context, install);
        report.stages.add(Json.map("name", "client-files", "seconds", (System.nanoTime() - started) / 1e9, "libraries", profile.classpath.size()));

        Path instance = options.values.containsKey("dir")
            ? Path.of(options.get("dir", "")).toAbsolutePath().normalize()
            : cache.resolve("clients").resolve(key(input, without));
        Path state = instance.resolve(".mmt/portable");
        Map<String, Object> running = Files.isRegularFile(state.resolve("agent.json")) ? Json.read(state.resolve("agent.json")) : null;
        if (running != null && Game.alive(running)) throw new IllegalStateException(
            "A client of this pack is already running (process " + running.get("pid") + "). Use it with --root \"" + instance + "\", or stop it: mmt game-stop --root \"" + instance + "\""
        );
        boolean fresh = !Files.isDirectory(instance.resolve("config"));
        Files.createDirectories(state);
        // The pack's own files (configs, scripts, resource packs, options) are copied once; a later start keeps what the
        // game changed since, and its saves.
        if (fresh) for (Path layer : pack.layers()) try (var list = Files.list(layer)) {
            for (Path p : list.toList()) {
                String name = p.getFileName().toString();
                if (name.equals("mods") || name.equals(".mmt-unpacked")) continue;
                if (Files.isDirectory(p)) PackCheck.copyTree(p, instance.resolve(name));
                else if (!name.endsWith(".json") && !name.endsWith(".html")) Files.copy(p, instance.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path modsFolder = instance.resolve("mods");
        PackCheck.deleteTree(modsFolder);
        Files.createDirectories(modsFolder);
        int kept = 0;
        for (PackCheck.Mod mod : mods) {
            if (mod.jar() == null || leftOut.containsKey(mod.file()) || !mod.file().endsWith(".jar")) continue;
            PackCheck.link(mod.jar(), modsFolder.resolve(mod.file()));
            kept++;
        }
        // A pack that carries jars itself (an instance's or export's mods folder) has them in its first layer.
        Path own = pack.overrides().resolve("mods");
        if (!pack.instance() && Files.isDirectory(own)) try (var list = Files.list(own)) {
            for (Path jar : list.filter(p -> p.toString().endsWith(".jar")).toList()) if (!Files.exists(modsFolder.resolve(jar.getFileName().toString()))) {
                PackCheck.link(jar, modsFolder.resolve(jar.getFileName().toString()));
            }
        }

        String token = UUID.randomUUID().toString();
        Map<String, Object> preferences = Preferences.read(Preferences.file());
        Path asm = harness.resolve("tools/perf/lib/asm-9.9.jar");
        Path agent = Game.agent(harness, asm, state, report);
        String world = options.get("world", "");
        List<String> command = command(java, profile, install, instance, options.get("memory", "10G"), world);
        int main = command.indexOf(profile.mainClass);
        command.addAll(main, List.of(
            "-javaagent:" + agent,
            "-Dmmt.root=" + instance,
            "-Dmmt.state=" + state,
            "-Dmmt.token=" + token,
            "-Dmmt.fingerprint=" + token,
            "-Dmmt.background=" + preferences.get("background"),
            "-Dmmt.quiet=" + preferences.get("quiet")
        ));
        // eval compiles against the game as it runs: the loader's patched game and the mods, which load off the class path.
        List<String> compile = new ArrayList<>();
        try (var walk = Files.walk(profile.libraries)) {
            for (Path jar : walk.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                String path = profile.libraries.relativize(jar).toString().replace(File.separatorChar, '/');
                boolean game = path.startsWith("net/minecraft/client/") && (path.endsWith("-srg.jar") || path.endsWith("-extra.jar"));
                boolean patched = (path.startsWith("net/minecraftforge/forge/") || path.startsWith("net/neoforged/")) && (path.endsWith("-client.jar") || path.endsWith("-universal.jar"));
                if (patched) compile.add(0, jar.toString());
                else if (game) compile.add(jar.toString());
            }
        }
        try (var list = Files.list(modsFolder)) {
            for (Path jar : list.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) compile.add(jar.toString());
        }
        Path compileList = state.resolve("compile-classpath.txt");
        Files.writeString(compileList, String.join(File.pathSeparator, compile));
        command.add(main + 7, "-Dmmt.compileClasspath=" + compileList);
        Json.save(state.resolve(MARKER), Json.map(
            "pack", (pack.name() + " " + pack.version()).strip(), "input", input.toString(), "minecraft", pack.minecraft(),
            "loader", pack.loader() + " " + pack.loaderVersion(), "mods", kept, "leftOut", leftOut, "command", command
        ));
        if (options.has("dry-run")) return Json.map(
            "ok", true, "instance", instance.toString(), "mods", kept, "leftOut", leftOut, "command", command,
            "details", "Ready, not started (--dry-run): " + kept + " mods in " + instance + (leftOut.isEmpty() ? "" : "; left out: " + String.join(", ", leftOut.keySet()))
        );
        Files.deleteIfExists(state.resolve("agent.json"));
        Path log = report.log("client");
        Files.createDirectories(log.getParent());
        long launched = System.nanoTime();
        Process process = new ProcessBuilder(command).directory(instance.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        process.getOutputStream().close();
        Map<String, Object> session;
        try {
            session = awaitReady(process, state, token, log, Integer.parseInt(options.get("timeout", "600")));
        } catch (Exception failed) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw failed;
        }
        // A game asked to be hidden or quiet that is not would be on the user's screen or speakers: it is stopped.
        String unwanted = "";
        try {
            // A big pack draws its first frame while mods still load; the sound engine starts when loading ends.
            var status = Game.request(session, Json.map("command", "status"));
            long loading = System.nanoTime() + TimeUnit.MINUTES.toNanos(30);
            while (Boolean.TRUE.equals(preferences.get("quiet")) && Json.text(status, "quietApplied", "").isEmpty() && System.nanoTime() < loading) {
                String text = Files.readString(log, StandardCharsets.ISO_8859_1);
                if (text.contains("MMT_QUIET_ERROR") || !process.isAlive()) break;
                Thread.sleep(1000);
                status = Game.request(session, Json.map("command", "status"));
            }
            if (Boolean.TRUE.equals(preferences.get("background")) && Json.text(status, "backgroundApplied", "").isEmpty()) unwanted += " its window could not be hidden (" + Json.text(status, "backgroundError", "no window library rewritten") + ");";
            if (Boolean.TRUE.equals(preferences.get("quiet")) && Json.text(status, "quietApplied", "").isEmpty()) unwanted += " its sound could not be muted;";
        } catch (Exception unanswered) {
            unwanted += " it did not answer mmt (" + unanswered.getMessage() + ");";
        }
        if (!unwanted.isEmpty()) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new IOException("Stopped the client:" + unwanted + " Log: " + log);
        }
        session.put("processStart", process.toHandle().info().startInstant().map(Instant::toString).orElse(""));
        session.put("log", log.toString());
        session.put("pack", true);
        Json.save(state.resolve("agent.json"), session);
        double seconds = (System.nanoTime() - launched) / 1e9;
        report.stages.add(Json.map("name", "client-start", "seconds", seconds, "log", log.toString()));
        return Json.map(
            "ok", true,
            "pack", (pack.name() + " " + pack.version()).strip(),
            "loader", pack.loader() + " " + pack.loaderVersion(),
            "minecraft", pack.minecraft(),
            "instance", instance.toString(),
            "pid", session.get("pid"),
            "mods", kept,
            "leftOut", leftOut,
            "startSeconds", seconds,
            "log", log.toString(),
            "details", String.format(Locale.ROOT,
                "%s client running (process %s, %d mods, first frame after %.0f s)%s.%nInstance: %s%n" +
                "Use it with --root \"%s\": eval, game-status, perf-toggle; perf --root \"%s\"; stop it with mmt game-stop --root \"%s\".",
                (pack.name() + " " + pack.version()).strip(), session.get("pid"), kept, seconds,
                leftOut.isEmpty() ? "" : "; left out: " + String.join(", ", leftOut.keySet()), instance, instance, instance, instance)
        );
    }

    /** --without ID,ID: the jars providing those ids, and every jar that requires one of them, through theirs. */
    static Map<String, String> leftOut(List<PackCheck.Mod> mods, PackMods.Scan scan, List<String> without) {
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        for (String id : without) {
            List<PackMods.Provider> providers = scan.providers.getOrDefault(id, List.of());
            if (providers.isEmpty()) throw new IllegalArgumentException("--without names " + id + ", which no jar of the pack provides");
            for (PackMods.Provider p : providers) if (!p.bundled()) out.put(PackCheck.top(p.jar()), "--without " + id);
        }
        for (boolean grew = true; grew;) {
            grew = false;
            List<PackMods.Dependency> all = new ArrayList<>(scan.dependencies);
            all.addAll(scan.clientDependencies);
            for (var d : all) {
                String owner = PackCheck.top(d.owner());
                if (!d.kind().equals("required") || out.containsKey(owner)) continue;
                List<PackMods.Provider> providers = scan.providers.getOrDefault(d.id(), List.of());
                if (providers.isEmpty() || !providers.stream().allMatch(p -> out.containsKey(PackCheck.top(p.jar())))) continue;
                out.put(owner, "it requires " + d.id() + ", which is left out");
                grew = true;
            }
        }
        return out;
    }

    static String key(Path input, List<String> without) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        hash.update(input.toString().getBytes(StandardCharsets.UTF_8));
        for (String id : without) hash.update(("\n-" + id).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash.digest()).substring(0, 16);
    }

    // ------------------------------------------------------------------ the loader's client, installed once
    static Path installClient(PackLoaders.Style loader, PackLoaders.Context context, String minecraft, String version) throws Exception {
        Path dir = loader.installed(context, minecraft, version).resolveSibling(loader.id() + "-" + minecraft + "-" + version + "-client");
        Path done = dir.resolve("mmt-installed");
        if (Files.isRegularFile(done)) return dir;
        Files.createDirectories(dir);
        // An installer writes its profile into a launcher's folder, and refuses one without a launcher_profiles.json.
        Files.writeString(dir.resolve("launcher_profiles.json"), "{\"profiles\":{}}\n");
        Path installer = dir.resolve("installer.jar");
        PackCheck.download(loader.fill(loader.setting(minecraft, "installer"), minecraft, version, null), installer, null);
        List<String> arguments = new ArrayList<>();
        for (Object a : Json.array(loader.spec.get("clientInstall"))) arguments.add(loader.fill(String.valueOf(a), minecraft, version, dir));
        loader.runInstaller(context, installer, arguments, dir, version);
        Files.writeString(done, minecraft + "-" + version + "\n");
        return dir;
    }

    /** What a launcher reads from the version files: the main class, arguments, classpath and the folders they name. */
    static final class Profile {
        String id, mainClass, assetIndex;
        final List<Path> classpath = new ArrayList<>();
        final List<String> jvm = new ArrayList<>(), game = new ArrayList<>();
        Path natives, assets, libraries;
        String versionType = "release";
    }

    /**
     * The loader's version file over the game's (inheritsFrom), resolved for this machine: its libraries downloaded
     * and its natives unpacked, the game jar named as the loader expects, the asset index and objects downloaded.
     */
    static Profile profile(PackLoaders.Context context, Path install) throws Exception {
        Path versions = install.resolve("versions");
        Map<String, Object> child = null;
        try (var list = Files.list(versions)) {
            for (Path dir : list.toList()) {
                Path file = dir.resolve(dir.getFileName() + ".json");
                if (Files.isRegularFile(file) && Json.read(file).containsKey("inheritsFrom")) child = Json.read(file);
            }
        }
        if (child == null) throw new IOException("The loader's installer wrote no version file that inherits from the game's in " + versions);
        String minecraft = Json.text(child, "inheritsFrom", "");
        Map<String, Object> parent = PackLoaders.Vanilla.version(context.cache(), context.harness(), minecraft);
        Profile profile = new Profile();
        profile.id = Json.text(child, "id", "");
        profile.mainClass = Json.text(child, "mainClass", Json.text(parent, "mainClass", ""));
        profile.libraries = install.resolve("libraries");
        profile.natives = install.resolve("natives").resolve(minecraft);
        profile.versionType = Json.text(parent, "type", "release");
        // Libraries: the loader's first; a later one with the same group, name and classifier is the game's older copy.
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> downloads = new ArrayList<>();
        for (Map<String, Object> version : List.of(child, parent)) for (Object o : Json.array(version.get("libraries"))) {
            var library = Json.object(o);
            if (!allowed(library.get("rules"), Set.of())) continue;
            String[] name = Json.text(library, "name", "").split(":");
            if (name.length < 3) continue;
            String key = name[0] + ":" + name[1] + (name.length > 3 ? ":" + name[3] : "");
            if (!seen.add(key)) continue;
            var files = Json.object(library.getOrDefault("downloads", Map.of()));
            var artifact = Json.object(files.getOrDefault("artifact", Map.of()));
            // Natives before 1.19: a classifier per system, unpacked; since then a library like any other, also unpacked.
            var classifiers = Json.object(files.getOrDefault("classifiers", Map.of()));
            String nativeKey = Json.text(Json.object(library.getOrDefault("natives", Map.of())), system(), "").replace("${arch}", "64");
            if (!nativeKey.isEmpty() && classifiers.containsKey(nativeKey)) {
                var file = Json.object(classifiers.get(nativeKey));
                Path nativeJar = profile.libraries.resolve(Json.text(file, "path", ""));
                if (!Files.isRegularFile(nativeJar)) downloads.add(Json.map("url", Json.text(file, "url", ""), "path", nativeJar.toString(), "sha1", Json.text(file, "sha1", "")));
                profile.classpath.add(nativeJar);
                if (artifact.isEmpty()) continue;
            }
            Path jar = profile.libraries.resolve(Json.text(artifact, "path", mavenPath(name)));
            // A library with no address is one the installer made (the patched game): it is there or the install failed.
            String url = Json.text(artifact, "url", library.containsKey("url") ? Json.text(library, "url", "") + mavenPath(name) : "");
            if (!Files.isRegularFile(jar) && !url.isEmpty()) downloads.add(Json.map("url", url, "path", jar.toString(), "sha1", Json.text(artifact, "sha1", "")));
            profile.classpath.add(jar);
        }
        // The game jar, named as the loader's profile (its ignore list names ${version_name}.jar), as a launcher copies it.
        var client = Json.object(Json.object(parent.get("downloads")).get("client"));
        Path gameJar = versions.resolve(minecraft).resolve(minecraft + ".jar");
        if (!Files.isRegularFile(gameJar) || !sha1(gameJar).equals(Json.text(client, "sha1", ""))) downloads.add(Json.map("url", Json.text(client, "url", ""), "path", gameJar.toString(), "sha1", Json.text(client, "sha1", "")));
        // Assets: the index, then every object it names, shared by every pack of any version.
        var index = Json.object(parent.get("assetIndex"));
        profile.assetIndex = Json.text(index, "id", minecraft);
        profile.assets = context.cache().resolve("minecraft").resolve("assets");
        Path indexFile = profile.assets.resolve("indexes").resolve(profile.assetIndex + ".json");
        if (!Files.isRegularFile(indexFile)) PackCheck.download(Json.text(index, "url", ""), indexFile, Json.text(index, "sha1", ""));
        for (var entry : Json.object(Json.read(indexFile).get("objects")).entrySet()) {
            String hash = Json.text(Json.object(entry.getValue()), "hash", "");
            Path object = profile.assets.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
            if (!Files.isRegularFile(object)) downloads.add(Json.map("url", "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash, "path", object.toString(), "sha1", hash));
        }
        fetch(downloads, context.report());
        Set<Path> unique = new LinkedHashSet<>(profile.classpath);
        profile.classpath.clear();
        for (Path p : unique) {
            if (!Files.isRegularFile(p)) throw new IOException("The loader's install lacks " + p + "; delete " + install + " and start again.");
            profile.classpath.add(p);
        }
        Path named = versions.resolve(profile.id).resolve(profile.id + ".jar");
        if (!Files.isRegularFile(named) || Files.size(named) != Files.size(gameJar)) Files.copy(gameJar, named, StandardCopyOption.REPLACE_EXISTING);
        profile.classpath.add(named);
        unpackNatives(profile);
        for (Map<String, Object> version : List.of(parent, child)) {
            var arguments = Json.object(version.getOrDefault("arguments", Map.of()));
            profile.jvm.addAll(arguments(arguments.get("jvm")));
            profile.game.addAll(arguments(arguments.get("game")));
            // Before 1.13 the game arguments were one string.
            if (version.containsKey("minecraftArguments")) {
                profile.game.clear();
                profile.game.addAll(Arrays.asList(Json.text(version, "minecraftArguments", "").split(" ")));
            }
        }
        if (profile.jvm.isEmpty()) profile.jvm.addAll(List.of("-Djava.library.path=${natives_directory}", "-cp", "${classpath}"));
        return profile;
    }

    static void unpackNatives(Profile profile) throws IOException {
        Path done = profile.natives.resolve("mmt-unpacked");
        if (Files.isRegularFile(done)) return;
        Files.createDirectories(profile.natives);
        for (Path jar : profile.classpath) {
            if (!jar.getFileName().toString().contains("natives-" + system())) continue;
            try (var zip = new ZipFile(jar.toFile())) {
                for (var entries = zip.entries(); entries.hasMoreElements();) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || name.startsWith("META-INF/") || !(name.endsWith(".dll") || name.endsWith(".so") || name.endsWith(".dylib") || name.endsWith(".jnilib"))) continue;
                    Path target = profile.natives.resolve(name.substring(name.lastIndexOf('/') + 1));
                    try (var in = zip.getInputStream(entry)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        Files.writeString(done, "unpacked\n");
    }

    /** The arguments a version file lists for this machine, with no optional features (demo, custom size, quick play). */
    static List<String> arguments(Object list) {
        List<String> out = new ArrayList<>();
        for (Object o : Json.array(list)) {
            if (o instanceof String s) {
                out.add(s);
                continue;
            }
            var rule = Json.object(o);
            if (!allowed(rule.get("rules"), Set.of())) continue;
            Object value = rule.get("value");
            if (value instanceof String s) out.add(s);
            else for (Object v : Json.array(value)) out.add(String.valueOf(v));
        }
        return out;
    }

    /** A launcher's rules: none allows; otherwise the last rule that matches this system decides. */
    static boolean allowed(Object rules, Set<String> features) {
        if (!(rules instanceof List<?> list) || list.isEmpty()) return true;
        boolean allow = false;
        for (Object o : list) {
            var rule = Json.object(o);
            boolean matches = true;
            var os = Json.object(rule.getOrDefault("os", Map.of()));
            if (os.containsKey("name") && !system().equals(Json.text(os, "name", ""))) matches = false;
            if (os.containsKey("arch") && !Json.text(os, "arch", "").equals(System.getProperty("os.arch").contains("64") ? "x64" : "x86")) matches = false;
            for (var feature : Json.object(rule.getOrDefault("features", Map.of())).entrySet()) if (
                Boolean.TRUE.equals(feature.getValue()) != features.contains(feature.getKey())
            ) matches = false;
            if (matches) allow = "allow".equals(rule.get("action"));
        }
        return allow;
    }

    static String system() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        return os.contains("win") ? "windows" : os.contains("mac") ? "osx" : "linux";
    }

    static String mavenPath(String[] name) {
        if (name.length < 3) return "";
        String group = name[0].replace('.', '/'), artifact = name[1], version = name[2];
        String extension = "jar";
        String classifier = name.length > 3 ? name[3] : "";
        if (version.contains("@")) {
            extension = version.substring(version.indexOf('@') + 1);
            version = version.substring(0, version.indexOf('@'));
        }
        if (classifier.contains("@")) {
            extension = classifier.substring(classifier.indexOf('@') + 1);
            classifier = classifier.substring(0, classifier.indexOf('@'));
        }
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + (classifier.isEmpty() ? "" : "-" + classifier) + "." + extension;
    }

    static String sha1(Path file) throws Exception {
        return PackCheck.sha1(file);
    }

    /** Downloads in parallel, each checked against its SHA-1, into a temporary name first. */
    static void fetch(List<Map<String, Object>> downloads, Mmt.Report report) throws Exception {
        if (downloads.isEmpty()) return;
        long start = System.nanoTime();
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(30)).build();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicLong bytes = new AtomicLong();
        List<Future<?>> jobs = new ArrayList<>();
        for (var d : downloads) jobs.add(pool.submit(() -> {
            Path target = Path.of(Json.text(d, "path", ""));
            String sha1 = Json.text(d, "sha1", "");
            for (int attempt = 0;; attempt++) {
                try {
                    Files.createDirectories(target.getParent());
                    Path part = target.resolveSibling(target.getFileName() + ".part" + Thread.currentThread().getId());
                    HttpResponse<Path> response = http.send(
                        HttpRequest.newBuilder(URI.create(Json.text(d, "url", ""))).header("User-Agent", "mmt").timeout(java.time.Duration.ofMinutes(5)).build(),
                        HttpResponse.BodyHandlers.ofFile(part)
                    );
                    if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " for " + Json.text(d, "url", ""));
                    if (!sha1.isEmpty() && !sha1(part).equals(sha1)) throw new IOException("SHA-1 differs for " + Json.text(d, "url", ""));
                    bytes.addAndGet(Files.size(part));
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                    return null;
                } catch (Exception failed) {
                    if (attempt >= 3) throw failed;
                    Thread.sleep(1000L << attempt);
                }
            }
        }));
        pool.shutdown();
        try {
            for (Future<?> job : jobs) job.get();
        } catch (ExecutionException failed) {
            pool.shutdownNow();
            throw new IOException("Could not download the game's files: " + failed.getCause().getMessage(), failed.getCause());
        }
        report.stages.add(Json.map("name", "client-downloads", "seconds", (System.nanoTime() - start) / 1e9, "files", downloads.size(), "bytes", bytes.get()));
    }

    /** The launch command a launcher would build: the JVM options players get, the profile's arguments filled in. */
    static List<String> command(Runtimes.Jdk java, Profile profile, Path install, Path instance, String memory, String world) {
        String separator = File.pathSeparator;
        // Offline, as a player with no account: a fixed name and the UUID offline servers derive from it.
        String player = "Player";
        String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + player).getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        Map<String, String> values = new HashMap<>();
        values.put("natives_directory", profile.natives.toString());
        values.put("launcher_name", "mmt");
        values.put("launcher_version", "1");
        values.put("classpath", String.join(separator, profile.classpath.stream().map(Path::toString).toList()));
        values.put("classpath_separator", separator);
        values.put("library_directory", profile.libraries.toString());
        values.put("version_name", profile.id);
        values.put("auth_player_name", player);
        values.put("game_directory", instance.toString());
        values.put("assets_root", profile.assets.toString());
        values.put("game_assets", profile.assets.toString());
        values.put("assets_index_name", profile.assetIndex);
        values.put("auth_uuid", uuid);
        values.put("auth_access_token", "0");
        values.put("auth_session", "0");
        values.put("clientid", "0");
        values.put("auth_xuid", "0");
        values.put("user_type", "legacy");
        values.put("user_properties", "{}");
        values.put("version_type", profile.versionType);
        List<String> command = new ArrayList<>();
        command.add(java.java().toString());
        command.add("-Xmx" + memory);
        // The official launcher's own JVM options for every game it starts.
        command.addAll(List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UseG1GC", "-XX:G1NewSizePercent=20", "-XX:G1ReservePercent=20",
            "-XX:MaxGCPauseMillis=50", "-XX:G1HeapRegionSize=32M"));
        for (String a : profile.jvm) command.add(fill(a, values));
        command.add(profile.mainClass);
        for (String a : profile.game) command.add(fill(a, values));
        if (!world.isEmpty()) command.addAll(List.of("--quickPlaySingleplayer", world));
        return command;
    }

    static String fill(String argument, Map<String, String> values) {
        String out = argument;
        for (var e : values.entrySet()) out = out.replace("${" + e.getKey() + "}", e.getValue());
        return out;
    }

    /**
     * The agent writes agent.json at the first rendered frame. A big pack's client loads for many minutes, logging as it
     * goes: it has failed when its process ends, or its log has said nothing new for --timeout seconds.
     */
    static Map<String, Object> awaitReady(Process process, Path state, String token, Path log, int quietSeconds) throws Exception {
        long last = System.nanoTime(), size = -1;
        long ceiling = System.nanoTime() + TimeUnit.MINUTES.toNanos(45);
        Path file = state.resolve("agent.json");
        while (true) {
            if (Files.isRegularFile(file)) try {
                var value = Json.read(file);
                if (token.equals(value.get("token"))) return value;
            } catch (Exception notWrittenYet) {}
            if (!process.isAlive()) throw new IOException("The client stopped before its first frame (exit " + process.exitValue() + "). Log: " + log + crash(log));
            long now = Files.isRegularFile(log) ? Files.size(log) : 0;
            if (now != size) {
                size = now;
                last = System.nanoTime();
            }
            if (System.nanoTime() - last > TimeUnit.SECONDS.toNanos(quietSeconds)) throw new IOException("The client's log said nothing for " + quietSeconds + " s before its first frame. Log: " + log);
            if (System.nanoTime() > ceiling) throw new IOException("The client showed no frame in 45 minutes. Log: " + log);
            Thread.sleep(1000);
        }
    }

    static String crash(Path log) {
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.ISO_8859_1);
            return "\n" + String.join("\n", lines.subList(Math.max(0, lines.size() - 30), lines.size()));
        } catch (IOException unreadable) {
            return "";
        }
    }
}
