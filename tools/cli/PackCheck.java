import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * pack-check: a modpack, as its user has it, on its own loader's real server.
 *
 * The parts, each a registry a new case is added to: PackInputs (the shapes a pack arrives in), PackLoaders (how each
 * loader's server is installed and started, from knowledge/pack-loaders.json), PackMods (what each jar declares, read
 * the way the loader reads it), PackChecks (the checks, before and in the game, and the log triage that names a mod)
 * and PackRepairs (proven repairs, from knowledge/pack-fixes.json). This class runs them in order.
 *
 * In the game: the loader's server starts with the pack's mods and configs and a probe mod. Start 1 makes a new world,
 * writes every dimension the way level.dat stores it and reads it back, and places every block item through the
 * server's own right-click path; start 2 opens the same world again and reads every placed block back. --world SAVE
 * also opens a copy of the user's own save. --fix runs the repaired pack's server beside the shipped one and changes
 * the user's pack only when the repaired one passes. Mods a start showed to be client-only are kept in
 * client-only.json and left out of every later start.
 */
final class PackCheck {

    static final String CURSEFORGE = "https://www.curseforge.com/api/v1/mods/", MODRINTH = "https://api.modrinth.com/v2/";
    static final String AGENT = "Noisemaker111/modding-accelerator pack-check";
    static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build();

    record Mod(String file, Path jar, String side, String source) {}

    /** layers: the folders the pack's configs come from, in order (an .mrpack's overrides then server-overrides); the first holds mods/ for an instance. */
    record Pack(
        String name,
        String version,
        String minecraft,
        String loader,
        String loaderVersion,
        List<Mod> mods,
        List<Path> layers,
        Path input,
        boolean instance,
        String format
    ) {
        Path overrides() {
            return layers.get(0);
        }

        Pack withLoaderVersion(String version) {
            return new Pack(name, this.version, minecraft, loader, version, mods, layers, input, instance, format);
        }

        Pack withMods(List<Mod> list) {
            return new Pack(name, version, minecraft, loader, loaderVersion, list, layers, input, instance, format);
        }
    }

    final Path harness, cache;
    final Modaccel.Options options;
    final Modaccel.Report report;
    final List<Object> findings = new ArrayList<>();
    /** Mod files the pack lists that its source no longer serves: a player's launcher cannot get them either. */
    final List<String> unavailable = new ArrayList<>();

    synchronized void unavailable(String what) {
        unavailable.add(what);
    }
    PackMods.Scan scan = new PackMods.Scan();
    PackLoaders.Loader loader;
    /** Jars a server start showed to be client-only, on this run or an earlier one (client-only.json); every start leaves them out. */
    final Set<String> dropped = Collections.synchronizedSet(new LinkedHashSet<>());
    /** Server starts share this; a start that lost a fixed port (a mod's own, such as a voice chat server's) to the other lane runs again alone. */
    final java.util.concurrent.locks.ReentrantReadWriteLock starts = new java.util.concurrent.locks.ReentrantReadWriteLock();

    PackCheck(Path harness, Modaccel.Options options, Modaccel.Report report) {
        this.harness = harness;
        this.options = options;
        this.report = report;
        cache = Path.of(System.getProperty("user.home"), ".modaccel", "packs");
    }

    static Path receipts() {
        return Path.of(System.getProperty("user.home"), ".modaccel", "packs");
    }

    Map<String, Object> run() throws Exception {
        if (options.has("list")) return list();
        Path input = Path.of(options.get("pack", "")).toAbsolutePath().normalize();
        if (options.get("pack", "").isEmpty() || !Files.exists(input)) throw new IllegalArgumentException(
            "Name the pack: modaccel pack-check --pack PACK.zip|PACK.mrpack|FOLDER. Got: " + options.get("pack", "(nothing)")
        );
        if (options.values.containsKey("undo")) return undo(input);
        long started = System.nanoTime();
        Pack pack = read(input);
        loader = PackLoaders.find(harness, pack.loader());
        if (loader == null) throw new IllegalArgumentException(
            "pack-check has no adapter for the loader \"" + pack.loader() + "\"; it knows " + PackLoaders.all(harness).keySet() +
            ". A loader is added in knowledge/pack-loaders.json (see tools/packcheck/CONTRIBUTING.md)."
        );
        report.stages.add(Json.map("name", "read", "seconds", (System.nanoTime() - started) / 1e9, "note", "includes fetch and modrinth-sides"));
        Runtimes.Jdk java = Runtimes.ensureExact(harness, PackLoaders.Vanilla.java(cache, harness, pack.minecraft()));
        PackLoaders.Context context = new PackLoaders.Context(harness, cache, report, java);
        String resolved = "";
        if (pack.loaderVersion().isBlank()) {
            resolved = loader.newest(context, pack.minecraft());
            pack = pack.withLoaderVersion(resolved);
        }
        var result = Json.map(
            "pack",
            (pack.name() + " " + pack.version()).strip(),
            "format",
            pack.format(),
            "minecraft",
            pack.minecraft(),
            "loader",
            pack.loader() + " " + pack.loaderVersion(),
            "mods",
            pack.mods().size()
        );
        if (!resolved.isEmpty()) result.put("loaderVersionFrom", loader.id() + "'s metadata: the newest stable for " + pack.minecraft());
        started = System.nanoTime();
        scan = PackMods.scan(pack.mods(), loader.metadata(pack.minecraft()));
        pack = pack.withMods(sides(pack.mods(), scan));
        PackChecks.Context statics = new PackChecks.Context(pack, scan, loader, findings);
        if (!unavailable.isEmpty()) findings.add(
            Json.map(
                "kind",
                "unavailable-file",
                "severity",
                "problem",
                "summary",
                unavailable.size() + " mod file(s) the pack lists cannot be downloaded any more, so a player cannot install the pack as published; the rest is checked without them: " +
                String.join("; ", unavailable),
                "files",
                new ArrayList<>(unavailable)
            )
        );
        PackChecks.run("static", statics);
        List<Map<String, Object>> fixes = PackRepairs.match(harness, pack, scan, findings);
        report.stages.add(Json.map("name", "static-checks", "seconds", (System.nanoTime() - started) / 1e9));
        result.put("staticChecks", Json.map("modIds", scan.providers.size(), "findings", findings.size()));
        readDropped();
        // Every repair is a known fault's, so it is known now: the repaired pack's server starts beside the shipped one.
        List<Map<String, Object>> chosen = options.has("fix") ? fixes : List.of();
        PackRepairs.Change change = PackRepairs.change(this, chosen, scan);
        ServerLane shipped = new ServerLane(pack, new PackRepairs.Change(List.of(), List.of()), "", findings, context);
        shipped.prepare();
        ServerLane repaired = chosen.isEmpty() ? null : new ServerLane(pack, change, "repaired-", new ArrayList<>(), context);
        ExecutorService side = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> recheck = null;
            if (repaired != null) {
                repaired.prepare();
                recheck = side.submit(() -> repaired.check(options.get("world", "")));
            }
            result.put("game", shipped.check(options.get("world", "")));
            if (options.has("fix")) result.put("fix", apply(pack, chosen, change, recheck, shipped, repaired));
        } finally {
            side.shutdownNow();
        }
        long problems = findings.stream().filter(f -> "problem".equals(Json.object(f).get("severity"))).count();
        var fix = Json.object(result.getOrDefault("fix", Json.map()));
        result.put("findings", findings);
        result.put("checks", PackChecks.ALL.stream().map(PackChecks.Check::id).toList());
        result.put("ok", problems == 0 || (Boolean.TRUE.equals(fix.get("proven")) && Json.array(fix.get("remaining")).isEmpty()));
        result.put("details", text(result));
        return result;
    }

    /** --list: what pack-check knows, from its registries. */
    Map<String, Object> list() throws IOException {
        List<Object> inputs = new ArrayList<>(), loaders = new ArrayList<>(), checks = new ArrayList<>(), repairs = new ArrayList<>();
        for (var input : PackInputs.ALL) inputs.add(Json.map("id", input.id(), "what", input.describe()));
        for (var loader : PackLoaders.all(harness).values()) loaders.add(
            Json.map("id", loader.id(), "style", ((PackLoaders.Style) loader).spec.get("style"), "reads", loader.metadata("99"))
        );
        for (var check : PackChecks.ALL) checks.add(Json.map("id", check.id(), "stage", check.stage(), "what", check.what()));
        for (var fix : PackRepairs.all(harness)) repairs.add(
            Json.map("id", fix.get("id"), "loader", fix.get("loader"), "minecraft", fix.get("minecraft"), "symptom", fix.get("symptom"))
        );
        StringBuilder text = new StringBuilder();
        text.append("Inputs:\n");
        for (var input : PackInputs.ALL) text.append("  ").append(input.id()).append(": ").append(input.describe()).append('\n');
        text.append("Loaders: ").append(PackLoaders.all(harness).keySet()).append('\n').append("Checks:\n");
        for (var check : PackChecks.ALL) text.append("  ").append(check.id()).append(" (").append(check.stage()).append("): ").append(check.what()).append('\n');
        text.append("Repairs:\n");
        for (var fix : PackRepairs.all(harness)) text.append("  ").append(fix.get("id")).append(" (").append(fix.get("loader")).append(' ').append(fix.get("minecraft")).append(")\n");
        return Json.map("ok", true, "inputs", inputs, "loaders", loaders, "checks", checks, "repairs", repairs, "details", text.toString());
    }

    /** A jar whose own metadata says it is client-only (Fabric's "environment") is that, whatever the pack's lists say. */
    static List<Mod> sides(List<Mod> mods, PackMods.Scan scan) {
        List<Mod> out = new ArrayList<>();
        for (Mod mod : mods) {
            String own = scan.sides.get(mod.file());
            out.add(own != null && mod.side().equals("unknown") ? new Mod(mod.file(), mod.jar(), own, mod.source() + ", its own metadata") : mod);
        }
        return out;
    }

    // ------------------------------------------------------------------ the pack
    /** An archive is unpacked once per file (by its SHA-256) and only read after; then the first input adapter that knows the shape reads it. */
    Pack read(Path input) throws Exception {
        Path directory = input;
        if (Files.isRegularFile(input)) {
            // 1,800 small files took 10-25 s to unpack each run on Windows.
            directory = cache.resolve("exports").resolve(HexFormat.of().formatHex(digest("SHA-256", input)).substring(0, 16));
            if (!Files.isRegularFile(directory.resolve(".modaccel-unpacked"))) {
                Path temporary = Files.createDirectories(cache.resolve("exports")).resolve(directory.getFileName() + "-" + ProcessHandle.current().pid());
                deleteTree(temporary);
                unzip(input, temporary);
                Files.writeString(temporary.resolve(".modaccel-unpacked"), input.getFileName() + "\n");
                deleteTree(directory);
                Files.move(temporary, directory);
            }
        }
        PackInputs.Input shape = PackInputs.find(directory);
        if (shape == null) throw new IllegalArgumentException(
            "Not a pack: " + input + " is none of: " + PackInputs.ALL.stream().map(PackInputs.Input::describe).toList()
        );
        return shape.read(this, input, directory);
    }

    static List<Mod> local(Path mods) throws IOException {
        List<Mod> found = new ArrayList<>();
        if (!Files.isDirectory(mods)) return found;
        try (var list = Files.list(mods)) {
            for (Path jar : list.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) found.add(
                new Mod(jar.getFileName().toString(), jar, "unknown", "folder")
            );
        }
        return found;
    }

    static void unzip(Path zip, Path into) throws IOException {
        Path root = into.toAbsolutePath().normalize();
        try (var in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null;) {
                Path out = root.resolve(entry.getName()).normalize();
                if (!out.startsWith(root)) throw new IOException("Unsafe path in " + zip + ": " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Each CurseForge file, from its public file API: name, size and sides, then the file itself; cached per file id.
     * A file already on disk (an instance's mods folder, keyed by file id) is used as it is, and only its sides are looked up.
     */
    List<Mod> fetch(List<int[]> files, Map<Integer, Path> onDisk) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(12);
        long start = System.nanoTime();
        int[] downloaded = { 0 };
        try {
            List<Future<Mod>> jobs = new ArrayList<>();
            for (int[] file : files) jobs.add(
                pool.submit(() -> {
                    Path folder = cache.resolve("curseforge").resolve(file[0] + "-" + file[1]);
                    Path metaFile = folder.resolve("file.json");
                    if (!Files.isRegularFile(metaFile)) {
                        Files.createDirectories(folder);
                        byte[] answer = get(CURSEFORGE + file[0] + "/files/" + file[1]);
                        // CurseForge answers {"data": null} for a file it no longer serves: not kept, so it is asked again next time.
                        if (!(Json.parse(new String(answer, StandardCharsets.UTF_8)) instanceof Map<?, ?> m && m.get("data") instanceof Map<?, ?>)) {
                            unavailable("project " + file[0] + ", file " + file[1] + ": CurseForge no longer lists it");
                            return null;
                        }
                        Files.write(metaFile, answer);
                    }
                    var data = Json.object(Json.read(metaFile).get("data"));
                    String name = Json.text(data, "fileName", file[1] + ".jar");
                    if (name.contains("/") || name.contains("\\") || name.startsWith(".")) throw new IOException(
                        "Unsafe file name from CurseForge: " + name
                    );
                    Path target = onDisk.containsKey(file[1]) ? onDisk.get(file[1]) : folder.resolve(name);
                    long length = ((Number) data.get("fileLength")).longValue();
                    if (onDisk.containsKey(file[1])) name = target.getFileName().toString();
                    else if (!Files.isRegularFile(target) || Files.size(target) != length) {
                        byte[] bytes;
                        try {
                            bytes = get(CURSEFORGE + file[0] + "/files/" + file[1] + "/download");
                        } catch (IOException gone) {
                            if (!gone.getMessage().startsWith("HTTP 404")) throw gone;
                            unavailable(name + " (project " + file[0] + ", file " + file[1] + "): CurseForge does not serve its download");
                            return null;
                        }
                        if (bytes.length != length) throw new IOException(name + ": CurseForge said " + length + " bytes, sent " + bytes.length);
                        Path temporary = Files.createTempFile(folder, "download-", ".tmp");
                        Files.write(temporary, bytes);
                        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                        synchronized (downloaded) {
                            downloaded[0]++;
                        }
                    }
                    List<Object> versions = Json.array(data.get("gameVersions"));
                    boolean client = versions.contains("Client"), server = versions.contains("Server");
                    return new Mod(
                        name,
                        target,
                        client && !server ? "client" : server && !client ? "server" : client ? "both" : "unknown",
                        "curseforge " + file[0] + "/" + file[1]
                    );
                })
            );
            List<Mod> mods = new ArrayList<>();
            for (Future<Mod> job : jobs) {
                Mod mod = job.get();
                if (mod != null) mods.add(mod);
            }
            report.stages.add(
                Json.map(
                    "name",
                    "fetch",
                    "seconds",
                    (System.nanoTime() - start) / 1e9,
                    "files",
                    files.size(),
                    "downloaded",
                    downloaded[0],
                    "cache",
                    cache.resolve("curseforge").toString()
                )
            );
            return mods;
        } catch (ExecutionException failed) {
            throw failed.getCause() instanceof Exception e ? e : failed;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Sides for jars nothing else describes (a hand-added jar, any launcher's folder), from Modrinth by the jar's SHA-1:
     * version_files gives the project, projects gives client_side and server_side. A mod whose server side is
     * "unsupported" is client-only. Answers are cached per SHA-1; jars Modrinth does not know stay "unknown".
     */
    List<Mod> modrinthSides(List<Mod> mods) throws Exception {
        Path folder = cache.resolve("modrinth");
        Files.createDirectories(folder);
        Map<Mod, String> hashes = new LinkedHashMap<>();
        for (Mod mod : mods) if (mod.side().equals("unknown") && mod.file().endsWith(".jar")) hashes.put(mod, sha1(mod.jar()));
        if (hashes.isEmpty()) return mods;
        long start = System.nanoTime();
        List<String> asked = new ArrayList<>();
        // A known answer is kept; "Modrinth does not know this jar" is asked again after a week.
        for (String hash : new TreeSet<>(hashes.values())) {
            Path answer = folder.resolve(hash + ".json");
            if (
                !Files.isRegularFile(answer) ||
                (Json.text(Json.read(answer), "project", "").isEmpty() &&
                    Files.getLastModifiedTime(answer).toMillis() < System.currentTimeMillis() - 7 * 86_400_000L)
            ) asked.add(hash);
        }
        String blocker = "";
        // Tests run with no network: modaccel.pack.offline keeps every answer from the cache.
        if (Boolean.getBoolean("modaccel.pack.offline")) asked.clear();
        if (!asked.isEmpty()) try {
            var versions = Json.object(
                Json.parse(new String(post(MODRINTH + "version_files", Json.write(Json.map("hashes", asked, "algorithm", "sha1"))), StandardCharsets.UTF_8))
            );
            Map<String, String> projectOf = new HashMap<>();
            for (var entry : versions.entrySet()) projectOf.put(entry.getKey(), Json.text(Json.object(entry.getValue()), "project_id", ""));
            Set<String> ids = new TreeSet<>(projectOf.values());
            ids.remove("");
            Map<String, Map<String, Object>> projects = new HashMap<>();
            if (!ids.isEmpty()) for (Object p : Json.array(
                Json.parse(
                    new String(
                        get(MODRINTH + "projects?ids=" + java.net.URLEncoder.encode(Json.write(new ArrayList<>(ids)), StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8
                    )
                )
            )) {
                var project = Json.object(p);
                projects.put(Json.text(project, "id", ""), project);
            }
            for (String hash : asked) {
                var project = projects.get(projectOf.getOrDefault(hash, ""));
                Json.save(
                    folder.resolve(hash + ".json"),
                    project == null
                        ? Json.map("project", "")
                        : Json.map(
                            "project",
                            Json.text(project, "id", ""),
                            "slug",
                            Json.text(project, "slug", ""),
                            "client_side",
                            Json.text(project, "client_side", ""),
                            "server_side",
                            Json.text(project, "server_side", "")
                        )
                );
            }
        } catch (Exception failed) {
            blocker = "Modrinth could not be reached for mod sides: " + failed.getMessage();
        }
        List<Mod> out = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        int found = 0;
        for (Mod mod : mods) {
            Path answer = hashes.containsKey(mod) ? folder.resolve(hashes.get(mod) + ".json") : null;
            var a = answer != null && Files.isRegularFile(answer) ? Json.read(answer) : Map.<String, Object>of();
            if (answer == null || Json.text(a, "project", "").isEmpty()) {
                if (answer != null) unknown.add(mod.file());
                out.add(mod);
                continue;
            }
            found++;
            boolean client = !"unsupported".equals(a.get("client_side")), server = !"unsupported".equals(a.get("server_side"));
            out.add(new Mod(mod.file(), mod.jar(), client && !server ? "client" : server && !client ? "server" : "both", mod.source() + ", sides from modrinth " + Json.text(a, "slug", "")));
        }
        report.stages.add(
            Json.map(
                "name",
                "modrinth-sides",
                "seconds",
                (System.nanoTime() - start) / 1e9,
                "asked",
                hashes.size(),
                "found",
                found,
                "unknown",
                unknown,
                "blocker",
                blocker
            )
        );
        return out;
    }

    static byte[] digest(String algorithm, Path file) throws Exception {
        var digest = MessageDigest.getInstance(algorithm);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0;) digest.update(buffer, 0, n);
        }
        return digest.digest();
    }

    static String sha1(Path file) throws Exception {
        return HexFormat.of().formatHex(digest("SHA-1", file));
    }

    static String hash(String algorithm, byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    }

    /** A download checked against a SHA-1: the one given, or the .sha1 its maven publishes beside it. Written whole or not at all. */
    static void download(String url, Path target, String sha1) throws Exception {
        String expected = sha1;
        if (expected == null || expected.isBlank()) expected = new String(get(url + ".sha1"), StandardCharsets.UTF_8).strip().split("\\s+")[0];
        byte[] bytes = get(url);
        if (!hash("SHA-1", bytes).equalsIgnoreCase(expected)) throw new IOException(url + " does not match its published SHA-1 " + expected);
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "download-", ".tmp");
        Files.write(temporary, bytes);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }

    static byte[] post(String url, String json) throws Exception {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            var response = HTTP.send(
                HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(2))
                    .header("User-Agent", AGENT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
            if (response.statusCode() == 200) return response.body();
            last = new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        throw last;
    }

    static byte[] get(String url) throws Exception {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            var response = HTTP.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).header("User-Agent", AGENT).build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
            if (response.statusCode() == 200) return response.body();
            last = new IOException("HTTP " + response.statusCode() + " from " + url);
            if (response.statusCode() == 404) break;
        }
        throw last;
    }

    /** Client-only jars earlier starts found (any pack: a jar's file name is its version), so no start pays for them again. */
    Path clientOnlyFile() {
        return cache.resolve("client-only.json");
    }

    void readDropped() throws IOException {
        if (Files.isRegularFile(clientOnlyFile())) dropped.addAll(Json.read(clientOnlyFile()).keySet());
    }

    synchronized void drop(String jar, String modId) throws IOException {
        dropped.add(jar);
        Map<String, Object> known = Files.isRegularFile(clientOnlyFile()) ? Json.read(clientOnlyFile()) : new TreeMap<>();
        known.put(
            jar,
            Json.map("modId", modId, "seen", java.time.LocalDate.now().toString(), "evidence", "the server stopped at start-up with this mod's client-only code")
        );
        Json.save(clientOnlyFile(), known);
    }

    // ------------------------------------------------------------------ in the game
    /** The pack's own loader server, its mods (less client-only ones) and configs, and the probe. */
    final class ServerLane {

        final Pack pack;
        final PackRepairs.Change change;
        final String lane;
        /** What this lane's starts found; known: the findings before any start (the known faults), read only. */
        final List<Object> found, known;
        final PackLoaders.Context context;
        Path install, instance, probe;

        ServerLane(Pack pack, PackRepairs.Change change, String lane, List<Object> found, PackLoaders.Context context) {
            this.pack = pack;
            this.change = change;
            this.lane = lane;
            this.found = found;
            this.context = context;
            known = List.copyOf(findings);
        }

        /** The server and the probe, installed or built once and shared by every lane: run before lanes start side by side. */
        void prepare() throws Exception {
            install = loader.install(context, pack.minecraft(), pack.loaderVersion());
            probe = PackLoaders.probe(context, loader, pack.minecraft(), install);
        }

        Map<String, Object> check(String userWorld) throws Exception {
            instance = instance();
            var game = Json.map(
                "ran",
                true,
                "server",
                loader.id() + " " + pack.minecraft() + " " + pack.loaderVersion(),
                "java",
                context.java().version(),
                "instance",
                instance.toString()
            );
            if (!change.add().isEmpty() || !change.remove().isEmpty()) game.put(
                "changed",
                Json.map("added", change.add().stream().map(p -> p.getFileName().toString()).toList(), "removed", change.remove())
            );
            // Start 1: a new world.
            Map<String, Object> first = start("place", "world", Map.of());
            game.put("newWorld", first);
            List<String> left = pack.mods().stream().map(Mod::file).filter(dropped::contains).toList();
            if (!left.isEmpty()) {
                game.put("clientOnly", left);
                found.add(
                    Json.map(
                        "kind",
                        "client-only",
                        "severity",
                        "note",
                        "summary",
                        left.size() + " mod(s) are client-only but not marked so on CurseForge or Modrinth; a server of this pack must leave them out.",
                        "jars",
                        left
                    )
                );
            }
            PackChecks.Context checks = new PackChecks.Context(pack, scan, loader, found);
            checks.start = first;
            try (var list = Files.list(instance.resolve("mods"))) {
                checks.onServer = new HashSet<>(list.map(p -> p.getFileName().toString()).toList());
            }
            stage("new-world", checks);
            if (Boolean.TRUE.equals(first.get("opened"))) {
                // Start 2: the same world again.
                Map<String, Object> second = start("verify", "world", Map.of("modaccel.probe.placed", first.get("probeFile").toString()));
                game.put("reopen", second);
                checks.first = first;
                checks.start = second;
                stage("reopen", checks);
            }
            if (!userWorld.isEmpty()) {
                Path save = Path.of(userWorld).toAbsolutePath().normalize();
                if (!Files.isRegularFile(save.resolve("level.dat"))) throw new IllegalArgumentException("--world needs a save folder with level.dat; got " + save);
                Path copy = instance.resolve("user-world");
                deleteTree(copy);
                copyTree(save, copy);
                Map<String, Object> opened = start("open", "user-world", Map.of());
                opened.put("save", save.toString());
                opened.put("copiedTo", copy.toString());
                game.put("userWorld", opened);
                checks.start = opened;
                stage("user-world", checks);
            }
            return game;
        }

        /** Runs a stage's checks; a finding of the kind a matched repair is proven against carries that repair. */
        void stage(String name, PackChecks.Context checks) throws Exception {
            int before = found.size();
            PackChecks.run(name, checks);
            for (Object f : new ArrayList<>(found.subList(before, found.size()))) for (Object k : known) {
                var fault = Json.object(k);
                if ("known-fault".equals(fault.get("kind")) && Json.object(f).get("kind").equals(Json.object(fault.get("fix")).get("check"))) Json.object(f).put(
                    "fix",
                    fault.get("fix")
                );
            }
        }

        /** One server start. place makes the world; verify and open read an existing one. */
        Map<String, Object> start(String mode, String level, Map<String, String> properties) throws Exception {
            boolean alone = false;
            for (int round = 0;; round++) {
                Path out = report.directory.resolve(lane + "probe-" + mode + ".json");
                Files.deleteIfExists(out);
                Path log = report.log(lane + "server-" + mode);
                writeServerFiles(level);
                List<String> command = new ArrayList<>(
                    List.of(
                        context.java().java().toString(),
                        "-Xmx" + options.get("memory", "6G"),
                        "-Dmodaccel.probe.mode=" + mode,
                        "-Dmodaccel.probe.out=" + out
                    )
                );
                for (var p : properties.entrySet()) command.add("-D" + p.getKey() + "=" + p.getValue());
                if (!options.get("only", "").isEmpty()) command.add("-Dmodaccel.probe.only=" + options.get("only", ""));
                command.addAll(loader.launch(install, instance, pack.minecraft(), pack.loaderVersion()));
                command.add("nogui");
                var lock = alone ? starts.writeLock() : starts.readLock();
                lock.lock();
                long start = System.nanoTime(), wall = System.currentTimeMillis();
                String ended;
                try {
                    ended = runServer(command, log, out, mode.equals("place"));
                } finally {
                    lock.unlock();
                }
                double seconds = (System.nanoTime() - start) / 1e9;
                // Mods log in the system code page as well as UTF-8; a strict read would fail on the first such byte.
                String text = Files.isRegularFile(log) ? new String(Files.readAllBytes(log), StandardCharsets.UTF_8) : "";
                var stage = Json.map("name", lane + "server-" + mode, "seconds", seconds, "log", log.toString(), "ended", ended);
                if (alone) stage.put("alone", "started again with no other server running: the side-by-side start lost a port");
                report.stages.add(stage);
                if (Files.isRegularFile(out)) {
                    var probeResult = Json.read(out);
                    return Json.map(
                        "opened",
                        true,
                        "seconds",
                        seconds,
                        "log",
                        log.toString(),
                        "probeFile",
                        out,
                        "probe",
                        probeResult,
                        "doneSeconds",
                        doneSeconds(text)
                    );
                }
                // A mod that only works in a game client stops a server at start-up; leave it out and start again.
                Map<String, String> clientOnly = new LinkedHashMap<>();
                for (String id : PackChecks.clientOnly(text, loader, scan)) for (PackMods.Provider p : scan.providers.getOrDefault(id, List.of())) if (
                    !p.bundled() && !change.remove().contains(p.jar())
                ) clientOnly.put(p.jar(), id);
                clientOnly.keySet().removeIf(dropped::contains);
                if (mode.equals("place") && !clientOnly.isEmpty() && round < 8) {
                    for (var jar : clientOnly.entrySet()) {
                        Files.deleteIfExists(instance.resolve("mods").resolve(jar.getKey()));
                        drop(jar.getKey(), jar.getValue());
                    }
                    deleteTree(instance.resolve(level));
                    continue;
                }
                // Two servers side by side can want the same fixed port; the one that lost it starts again once nothing else runs.
                if (!alone && lostPort(text)) {
                    alone = true;
                    if (mode.equals("place")) deleteTree(instance.resolve(level));
                    continue;
                }
                List<String> reports = PackChecks.crashReports(instance, wall);
                return Json.map(
                    "opened",
                    false,
                    "seconds",
                    seconds,
                    "log",
                    log.toString(),
                    "ended",
                    ended,
                    "failure",
                    PackChecks.failure(text + "\n" + String.join("\n", reports), loader.failed()),
                    "suspects",
                    PackChecks.suspects(text, reports, scan, loader)
                );
            }
        }

        /**
         * Runs until the probe is done or the server fails; a server whose main thread died is stopped, since leftover threads keep its JVM alive.
         * Only a start that made the world (keepWorld) is let save it; a start that only read it is stopped as soon as the probe is done.
         */
        String runServer(List<String> command, Path log, Path out, boolean keepWorld) throws Exception {
            Files.createDirectories(log.getParent());
            Process process = new ProcessBuilder(command).directory(instance.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            process.getOutputStream().close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Integer.parseInt(options.get("timeout", "1200")));
            String ended = "exited";
            List<String> failed = loader.failed();
            try {
                while (process.isAlive()) {
                    String text = Files.readString(log, StandardCharsets.ISO_8859_1);
                    String failure = text.contains("MODACCEL-PROBE-DONE")
                        ? null
                        : failed.stream()
                            .filter(text::contains)
                            .findFirst()
                            .orElse(
                                text.contains("MODACCEL-PROBE-STARTED") && text.contains("Stopping server")
                                    ? "the server stopped before the probe finished"
                                    : null
                            );
                    if (failure != null || text.contains("MODACCEL-PROBE-DONE")) {
                        ended = failure != null ? "failed: " + failure : "probe done";
                        if (!process.waitFor(failure != null ? 15 : keepWorld ? 120 : 0, TimeUnit.SECONDS)) stop(process);
                        break;
                    }
                    if (System.nanoTime() > deadline) {
                        ended = "timed out";
                        stop(process);
                        break;
                    }
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } finally {
                if (process.isAlive()) stop(process);
            }
            return ended;
        }

        void stop(Process process) throws InterruptedException {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }

        void writeServerFiles(String level) throws IOException {
            // Accepted by modaccel only for this throwaway, offline, localhost-only test server, as server-check does.
            Files.writeString(
                instance.resolve("eula.txt"),
                "# Accepted by modaccel pack-check for a throwaway local test server (offline mode, 127.0.0.1 only).\neula=true\n"
            );
            int port;
            try (var socket = new java.net.ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            Files.writeString(
                instance.resolve("server.properties"),
                "server-ip=127.0.0.1\nserver-port=" + port + "\nonline-mode=false\nspawn-protection=0\nlevel-name=" + level + "\nmotd=modaccel pack-check\n"
            );
            voiceChatPort(instance);
        }

        /**
         * Simple Voice Chat, in many packs, opens a fixed UDP port (24454) and stops the whole server when it cannot, so two
         * servers side by side (the shipped and the repaired pack) collide. modaccel's copy of its config gets a free port.
         */
        static void voiceChatPort(Path instance) throws IOException {
            try (var mods = Files.list(instance.resolve("mods"))) {
                if (mods.noneMatch(p -> p.getFileName().toString().startsWith("voicechat-"))) return;
            }
            int port;
            try (var socket = new java.net.DatagramSocket(0)) {
                port = socket.getLocalPort();
            }
            Path config = instance.resolve("config/voicechat/voicechat-server.properties");
            String text = Files.isRegularFile(config) ? Files.readString(config, StandardCharsets.ISO_8859_1) : "";
            text = Pattern.compile("(?m)^port=.*$").matcher(text).find() ? text.replaceAll("(?m)^port=.*$", "port=" + port) : text + "port=" + port + "\n";
            Files.createDirectories(config.getParent());
            Files.writeString(config, text, StandardCharsets.ISO_8859_1);
        }

        Path instance() throws Exception {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update((loader.id() + " " + pack.minecraft() + "\n").getBytes(StandardCharsets.UTF_8));
            for (Mod mod : pack.mods()) hash.update((mod.file() + "\n").getBytes(StandardCharsets.UTF_8));
            for (Path jar : change.add()) hash.update(("add " + jar.getFileName() + "\n").getBytes(StandardCharsets.UTF_8));
            for (String jar : change.remove()) hash.update(("remove " + jar + "\n").getBytes(StandardCharsets.UTF_8));
            Path dir = cache.resolve("instances").resolve(HexFormat.of().formatHex(hash.digest()).substring(0, 16));
            Path mods = dir.resolve("mods");
            deleteTree(mods);
            Files.createDirectories(mods);
            for (Mod mod : pack.mods()) if (
                mod.file().endsWith(".jar") && !mod.side().equals("client") && !dropped.contains(mod.file()) && !change.remove().contains(mod.file())
            ) link(mod.jar(), mods.resolve(mod.file()));
            for (Path jar : change.add()) link(jar, mods.resolve(jar.getFileName().toString()));
            link(probe, mods.resolve("zz-modaccel-pack-probe.jar"));
            // The pack's own settings, fresh each run, layer by layer; its world, if any, is not a test world.
            for (String folder : List.of("config", "defaultconfigs", "kubejs", "scripts")) {
                deleteTree(dir.resolve(folder));
                for (Path layer : pack.layers()) if (Files.isDirectory(layer.resolve(folder))) copyTree(layer.resolve(folder), dir.resolve(folder));
            }
            deleteTree(dir.resolve("world"));
            deleteTree(dir.resolve("crash-reports"));
            return dir;
        }
    }

    /** A port another process held, in the start's own log: Java's bind failure. */
    static boolean lostPort(String log) {
        return log.contains("java.net.BindException: Address already in use");
    }

    static double doneSeconds(String log) {
        Matcher m = Pattern.compile("Done \\(([0-9.]+)s\\)").matcher(log);
        return m.find() ? Double.parseDouble(m.group(1)) : -1;
    }

    // ------------------------------------------------------------------ repairs
    /**
     * Waits for both starts on the repaired pack (run beside the shipped one) and compares them check by check. The
     * repair is proven when the repaired pack opens a new world and opens it again, has no problem of the kind the
     * repair is for, and no problem the shipped pack does not have. Only then is the user's pack changed.
     */
    Map<String, Object> apply(
        Pack pack,
        List<Map<String, Object>> fixes,
        PackRepairs.Change change,
        Future<Map<String, Object>> recheck,
        ServerLane shipped,
        ServerLane lane
    ) throws Exception {
        if (fixes.isEmpty()) return Json.map("applied", List.of(), "details", "No finding has a proven repair in knowledge/pack-fixes.json.");
        List<Object> applied = new ArrayList<>();
        for (var fix : fixes) {
            var action = Json.object(fix.get("action"));
            applied.add(Json.map("id", fix.get("id"), "action", action.get("type"), "file", action.get("file"), "mod", action.get("mod"), "page", action.get("page")));
        }
        var result = Json.map("applied", applied);
        Map<String, Object> again;
        try {
            again = recheck.get();
        } catch (ExecutionException failed) {
            throw failed.getCause() instanceof Exception e ? e : failed;
        }
        Set<String> targets = new HashSet<>(List.of("server-start", "world-reopen", "probe-error", "dimension-save"));
        for (var fix : fixes) targets.add(Json.text(fix, "check", ""));
        Set<String> before = new HashSet<>();
        for (Object f : findings) if ("problem".equals(Json.object(f).get("severity"))) before.add(Json.object(f).get("kind") + " " + Json.object(f).get("summary"));
        List<Object> remaining = new ArrayList<>(), introduced = new ArrayList<>();
        for (Object f : lane.found) {
            var finding = Json.object(f);
            if (!"problem".equals(finding.get("severity"))) continue;
            remaining.add(finding);
            if (targets.contains(finding.get("kind")) || !before.contains(finding.get("kind") + " " + finding.get("summary"))) introduced.add(finding);
        }
        boolean opened = Boolean.TRUE.equals(Json.object(again.getOrDefault("newWorld", Json.map())).get("opened"));
        boolean reopened = Boolean.TRUE.equals(Json.object(again.getOrDefault("reopen", Json.map())).get("opened"));
        boolean proven = opened && reopened && introduced.isEmpty();
        result.put("comparison", compare(shipped.found, lane.found, targets));
        result.put("recheck", again);
        result.put("recheckFindings", lane.found);
        result.put("remaining", remaining);
        result.put("proven", proven);
        if (!proven) {
            result.put("applied", List.of());
            result.put("tried", applied);
            result.put("details", "The repaired pack did not pass where the shipped one failed, or failed somewhere new, so nothing of yours was changed. See recheckFindings.");
            return result;
        }
        if (pack.instance()) {
            // The user's instance: add and take out the files, and record them so --undo restores exactly those.
            Path mods = pack.overrides().resolve("mods"), record = pack.input().resolve(".modaccel/pack-fixes.json");
            var old = Files.isRegularFile(record) ? Json.read(record) : Json.map();
            List<Object> added = new ArrayList<>(Json.array(old.getOrDefault("added", List.of())));
            List<Object> removed = new ArrayList<>(Json.array(old.getOrDefault("removed", List.of())));
            Path keep = pack.input().resolve(".modaccel/removed");
            for (String jar : change.remove()) if (Files.isRegularFile(mods.resolve(jar))) {
                Files.createDirectories(keep);
                Files.move(mods.resolve(jar), keep.resolve(jar), StandardCopyOption.REPLACE_EXISTING);
                removed.add(jar);
            }
            for (Path jar : change.add()) {
                Path target = mods.resolve(jar.getFileName().toString());
                if (Files.exists(target)) continue;
                Files.copy(jar, target);
                added.add(target.getFileName().toString());
            }
            Json.save(record, Json.map("mods", mods.toString(), "added", added, "removed", removed));
            result.put("changed", mods.toString());
            result.put("undo", "modaccel pack-check --pack \"" + pack.input() + "\" --undo");
        } else {
            Path fixed = fixedCopy(pack, change);
            result.put("fixedExport", fixed.toString());
            result.put(
                "import",
                "Import the fixed file in your launcher, or in the instance add " + change.add().stream().map(p -> p.getFileName().toString()).toList() +
                (change.remove().isEmpty() ? "" : " and remove " + change.remove()) + " in mods/."
            );
        }
        return result;
    }

    /** Check by check: what the shipped pack and the repaired one each showed. */
    static List<Object> compare(List<Object> shipped, List<Object> repaired, Set<String> targets) {
        Map<String, long[]> counts = new TreeMap<>();
        for (Object f : shipped) if ("problem".equals(Json.object(f).get("severity"))) counts.computeIfAbsent(String.valueOf(Json.object(f).get("kind")), k -> new long[2])[0]++;
        for (Object f : repaired) if ("problem".equals(Json.object(f).get("severity"))) counts.computeIfAbsent(String.valueOf(Json.object(f).get("kind")), k -> new long[2])[1]++;
        for (String target : targets) counts.computeIfAbsent(target, k -> new long[2]);
        List<Object> rows = new ArrayList<>();
        for (var entry : counts.entrySet()) if (!entry.getKey().equals("known-fault")) rows.add(
            Json.map("check", entry.getKey(), "shipped", entry.getValue()[0], "repaired", entry.getValue()[1])
        );
        return rows;
    }

    /** An archive input (a CurseForge export or an .mrpack): a fixed copy, the original untouched. */
    Path fixedCopy(Pack pack, PackRepairs.Change change) throws Exception {
        String name = pack.input().getFileName().toString();
        String extension = name.toLowerCase(Locale.ROOT).endsWith(".mrpack") ? ".mrpack" : ".zip";
        Path fixed = report.directory.resolve(name.replaceFirst("(?i)\\.(zip|mrpack)$", "") + " (modaccel fixed)" + extension);
        Files.createDirectories(fixed.getParent());
        Set<String> projects = new HashSet<>(), indexPaths = new HashSet<>();
        for (Mod mod : pack.mods()) if (change.remove().contains(mod.file())) {
            if (mod.source().startsWith("curseforge ")) projects.add(mod.source().substring("curseforge ".length()).split(",")[0]);
            if (mod.source().startsWith("modrinth-index ")) indexPaths.add(mod.source().substring("modrinth-index ".length()).split(",")[0]);
        }
        Map<String, byte[]> entries = new LinkedHashMap<>();
        if (Files.isRegularFile(pack.input())) try (var in = new ZipInputStream(Files.newInputStream(pack.input()))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null;) if (!entry.isDirectory()) entries.put(entry.getName(), in.readAllBytes());
        }
        else try (var walk = Files.walk(pack.input())) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) entries.put(pack.input().relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
        }
        String overrides = pack.overrides().getFileName().toString();
        try (var out = new ZipOutputStream(Files.newOutputStream(fixed))) {
            for (var entry : entries.entrySet()) {
                String path = entry.getKey();
                byte[] bytes = entry.getValue();
                if (path.matches("(server-)?overrides/mods/[^/]+\\.jar") && change.remove().contains(path.substring(path.lastIndexOf('/') + 1))) continue;
                if (path.equals("manifest.json") && !projects.isEmpty()) {
                    var manifest = Json.object(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
                    List<Object> kept = new ArrayList<>();
                    for (Object f : Json.array(manifest.get("files"))) if (
                        !projects.contains(Json.object(f).get("projectID") + "/" + Json.object(f).get("fileID"))
                    ) kept.add(f);
                    manifest.put("files", kept);
                    bytes = Json.write(manifest).getBytes(StandardCharsets.UTF_8);
                }
                if (path.equals("modrinth.index.json")) {
                    var index = Json.object(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
                    List<Object> kept = new ArrayList<>();
                    for (Object f : Json.array(index.get("files"))) if (!indexPaths.contains(Json.text(Json.object(f), "path", ""))) kept.add(f);
                    for (Path jar : change.add()) {
                        var source = change.sources().getOrDefault(jar.getFileName().toString(), Map.of());
                        byte[] content = Files.readAllBytes(jar);
                        kept.add(
                            Json.map(
                                "path",
                                "mods/" + jar.getFileName(),
                                "hashes",
                                Json.map("sha1", hash("SHA-1", content), "sha512", hash("SHA-512", content)),
                                "env",
                                Json.map("client", "required", "server", "required"),
                                "downloads",
                                List.of(Json.text(source, "url", "")),
                                "fileSize",
                                content.length
                            )
                        );
                    }
                    index.put("files", kept);
                    bytes = Json.write(index).getBytes(StandardCharsets.UTF_8);
                }
                out.putNextEntry(new ZipEntry(path));
                out.write(bytes);
                out.closeEntry();
            }
            // An .mrpack names its downloads: an added jar becomes an index entry with its URL and hashes, not a copy in the pack.
            // Other archives carry it in overrides/mods/, which launchers put into the instance's mods folder on import.
            for (Path jar : change.add()) {
                if (entries.containsKey("modrinth.index.json")) continue;
                out.putNextEntry(new ZipEntry(overrides + "/mods/" + jar.getFileName()));
                Files.copy(jar, out);
                out.closeEntry();
            }
        }
        return fixed;
    }

    Map<String, Object> undo(Path input) throws IOException {
        Path record = input.resolve(".modaccel/pack-fixes.json");
        if (!Files.isRegularFile(record)) return Json.map("ok", true, "undone", List.of(), "details", "pack-check has not changed this folder.");
        var done = Json.read(record);
        Path mods = Path.of(Json.text(done, "mods", input.resolve("mods").toString()));
        List<Object> removed = new ArrayList<>(), restored = new ArrayList<>();
        for (Object name : Json.array(done.get("added"))) if (Files.deleteIfExists(mods.resolve(String.valueOf(name)))) removed.add(name);
        for (Object name : Json.array(done.getOrDefault("removed", List.of()))) {
            Path kept = input.resolve(".modaccel/removed").resolve(String.valueOf(name));
            if (!Files.isRegularFile(kept)) continue;
            Files.move(kept, mods.resolve(String.valueOf(name)), StandardCopyOption.REPLACE_EXISTING);
            restored.add(name);
        }
        Files.delete(record);
        return Json.map(
            "ok",
            true,
            "undone",
            removed,
            "restored",
            restored,
            "details",
            "Removed " + removed + (restored.isEmpty() ? "" : " and put back " + restored) + " in " + mods + "."
        );
    }

    // ------------------------------------------------------------------ text and files
    static String text(Map<String, Object> result) {
        StringBuilder out = new StringBuilder();
        out
            .append(result.get("pack"))
            .append(" (")
            .append(result.get("format"))
            .append(", Minecraft ")
            .append(result.get("minecraft"))
            .append(", ")
            .append(result.get("loader"))
            .append(", ")
            .append(result.get("mods"))
            .append(" files)\n");
        var game = Json.object(result.getOrDefault("game", Json.map()));
        if (Boolean.TRUE.equals(game.get("ran"))) {
            var first = Json.object(game.getOrDefault("newWorld", Json.map()));
            out.append("New world: ").append(Boolean.TRUE.equals(first.get("opened")) ? "opened" : "did not open").append('\n');
            if (first.get("probe") instanceof Map<?, ?> p) out
                .append("Block items placed: ")
                .append(p.get("blockItems"))
                .append(", landed as the item says: ")
                .append(p.get("landedAsExpected"))
                .append('\n');
            if (game.get("reopen") instanceof Map<?, ?> r) out.append("Same world, second start: ").append(Boolean.TRUE.equals(r.get("opened")) ? "opened" : "FAILED").append('\n');
            if (game.get("userWorld") instanceof Map<?, ?> u) out.append("Your save (a copy): ").append(Boolean.TRUE.equals(u.get("opened")) ? "opened" : "FAILED").append('\n');
        } else if (game.get("blocker") != null) out.append("Not run in the game: ").append(game.get("blocker")).append('\n');
        for (Object f : Json.array(result.get("findings"))) {
            var finding = Json.object(f);
            String severity = String.valueOf(finding.get("severity"));
            out.append(severity.equals("problem") ? "PROBLEM " : severity.equals("warning") ? "warning " : "note    ").append(finding.get("summary"));
            if (finding.get("game") != null) out.append(" [").append(finding.get("game")).append(']');
            out.append('\n');
        }
        if (result.get("fix") instanceof Map<?, ?> fix) {
            out.append("Repair: ").append(fix.get("applied")).append('\n');
            for (Object row : Json.array(fix.get("comparison"))) {
                var r = Json.object(row);
                out.append("  ").append(r.get("check")).append(": shipped ").append(r.get("shipped")).append(" problem(s), repaired ").append(r.get("repaired")).append('\n');
            }
            if (fix.get("fixedExport") != null) out.append("Fixed copy: ").append(fix.get("fixedExport")).append('\n');
            if (fix.get("undo") != null) out.append("Undo: ").append(fix.get("undo")).append('\n');
            out
                .append("Checked with the repair: ")
                .append(
                    Boolean.TRUE.equals(fix.get("proven"))
                        ? "both starts open and it clears what it is for"
                        : "not proven (see recheckFindings), so nothing of yours was changed. Tried: " + fix.get("tried")
                )
                .append('\n');
        }
        return out.toString();
    }

    static Path only(Path parent, String prefix) throws IOException {
        try (var list = Files.list(parent)) {
            return list
                .filter(p -> p.getFileName().toString().startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new IOException("Nothing named " + prefix + "* in " + parent));
        }
    }

    static Path file(Path dir, String suffix) throws IOException {
        try (var list = Files.list(dir)) {
            return list.filter(p -> p.getFileName().toString().endsWith(suffix)).findFirst().orElseThrow(() -> new IOException("No *" + suffix + " in " + dir));
        }
    }

    static void link(Path from, Path to) throws IOException {
        try {
            Files.createLink(to, from);
        } catch (IOException | UnsupportedOperationException differentDrive) {
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
