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
 * pack-check: a modpack, as its user has it, on a real modded server. Input is a CurseForge export (the .zip or its
 * folder) or an instance folder with mods/. The mod files are fetched from CurseForge's public file API and cached.
 *
 * Without the game: duplicate mod ids, missing required mods, and mods older than a proven repair in
 * knowledge/pack-fixes.json. In the game: the pack's loader server (installed with the loader's own installer) is
 * started with the pack's mods and configs and tools/packcheck's probe. Start 1 makes a new world, writes every
 * dimension the way level.dat stores it and reads it back, and places every block item through the server's own
 * right-click path; start 2 opens the same world again and reads every placed block back. --world SAVE also opens a
 * copy of the user's own save (the original is never touched). --fix applies the repairs the findings name, to an
 * instance folder (undo with --undo) or as a fixed copy of an export, and runs both starts again to prove them.
 */
final class PackCheck {
    static final List<String> PROBE = List.of("tools/packcheck/ModaccelPackProbe.java", "tools/packcheck/ModaccelPackOpener.java", "tools/packcheck/mods.toml", "tools/packcheck/pack.mcmeta", "tools/packcheck/ProbeNames.java");
    static final String CURSEFORGE = "https://www.curseforge.com/api/v1/mods/", MODRINTH = "https://api.modrinth.com/v2/";
    static final String AGENT = "Noisemaker111/modding-accelerator pack-check";
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();

    record Mod(String file, Path jar, String side, String source) { }
    record Pack(String name, String version, String minecraft, String loader, String loaderVersion, List<Mod> mods, Path overrides, Path input, boolean instance) { }
    record Provider(String jar, String version, boolean bundled) { }

    final Path harness, cache; final Modaccel.Options options; final Modaccel.Report report;
    final List<Object> findings = new ArrayList<>();
    /** Mod id to the jars that provide it, from scan(). */
    Map<String, List<Provider>> providers = Map.of();
    /** Jars a server start showed to be client-only; later starts (the repair's recheck) leave them out from the start. */
    final Set<String> dropped = new LinkedHashSet<>();
    PackCheck(Path harness, Modaccel.Options options, Modaccel.Report report) {
        this.harness = harness; this.options = options; this.report = report;
        cache = Path.of(System.getProperty("user.home"), ".modaccel", "packs");
    }
    static Path receipts() { return Path.of(System.getProperty("user.home"), ".modaccel", "packs"); }

    Map<String, Object> run() throws Exception {
        Path input = Path.of(options.get("pack", "")).toAbsolutePath().normalize();
        if (options.get("pack", "").isEmpty() || !Files.exists(input)) throw new IllegalArgumentException("Name the pack: modaccel pack-check --pack EXPORT.zip|FOLDER. Got: " + options.get("pack", "(nothing)"));
        if (options.values.containsKey("undo")) return undo(input);
        Pack pack = read(input);
        var result = Json.map("pack", pack.name() + " " + pack.version(), "minecraft", pack.minecraft(), "loader", pack.loader() + " " + pack.loaderVersion(), "mods", pack.mods().size());
        providers = scan(pack);
        List<Map<String, Object>> fixes = knownFaults(pack, providers);
        result.put("staticChecks", Json.map("modIds", providers.size(), "findings", findings.size()));
        if (!pack.loader().equals("forge")) {
            result.put("game", Json.map("ran", false, "blocker", "The in-game checks run Forge packs so far; this pack uses " + pack.loader() + "."));
        } else {
            Map<String, Object> game = new ServerLane(pack, List.of()).check(options.get("world", ""));
            result.put("game", game);
            if (options.has("fix")) {
                List<Map<String, Object>> chosen = new ArrayList<>();
                for (Object finding : findings) { Object fix = Json.object(finding).get("fix"); if (fix != null && !chosen.contains(fix)) chosen.add(Json.object(fix)); }
                result.put("fix", apply(pack, chosen));
            }
        }
        long problems = findings.stream().filter(f -> "problem".equals(Json.object(f).get("severity"))).count();
        result.put("findings", findings);
        result.put("ok", problems == 0 || Boolean.TRUE.equals(Json.object(result.getOrDefault("fix", Json.map())).get("proven")));
        result.put("details", text(result));
        return result;
    }

    // ------------------------------------------------------------------ the pack
    Pack read(Path input) throws Exception {
        Path directory = input;
        if (Files.isRegularFile(input)) {
            directory = report.directory.resolve("pack");
            unzip(input, directory);
        }
        Path manifest = directory.resolve("manifest.json"), instance = directory.resolve("minecraftinstance.json");
        if (Files.isRegularFile(manifest)) {
            var m = Json.read(manifest);
            var minecraft = Json.object(m.get("minecraft"));
            String loaderId = "";
            for (Object l : Json.array(minecraft.get("modLoaders"))) if (Boolean.TRUE.equals(Json.object(l).get("primary")) || loaderId.isEmpty()) loaderId = Json.text(Json.object(l), "id", "");
            List<int[]> files = new ArrayList<>();
            for (Object f : Json.array(m.get("files"))) { var file = Json.object(f); files.add(new int[]{ ((Number) file.get("projectID")).intValue(), ((Number) file.get("fileID")).intValue() }); }
            Path overrides = directory.resolve(Json.text(m, "overrides", "overrides"));
            List<Mod> mods = new ArrayList<>(fetch(files, Map.of()));
            mods.addAll(local(overrides.resolve("mods")));
            mods = modrinthSides(mods);
            String[] loader = loaderId.split("-", 2);
            return new Pack(Json.text(m, "name", input.getFileName().toString()).strip(), Json.text(m, "version", "").strip(), Json.text(minecraft, "version", ""),
                    loader[0], loader.length > 1 ? loader[1] : "", mods, overrides, input, false);
        }
        if (!Files.isDirectory(directory.resolve("mods"))) throw new IllegalArgumentException("Not a pack: " + input + " has no manifest.json (a CurseForge export) and no mods folder (an instance).");
        String minecraft = options.get("minecraft", ""), loaderId = options.get("loader", "");
        List<Mod> mods = local(directory.resolve("mods"));
        if (Files.isRegularFile(instance)) {
            var i = Json.read(instance);
            if (minecraft.isEmpty()) minecraft = Json.text(i, "gameVersion", "");
            if (loaderId.isEmpty() && i.get("baseModLoader") instanceof Map<?, ?> base) loaderId = String.valueOf(base.get("name"));
            // The CurseForge app lists each installed mod's project and file: its sides come from the same file API as an export's.
            List<int[]> files = new ArrayList<>(); Map<Integer, Path> onDisk = new HashMap<>();
            for (Object a : Json.array(i.getOrDefault("installedAddons", List.of()))) {
                var addon = Json.object(a); var file = Json.object(addon.getOrDefault("installedFile", Map.of()));
                String disk = Json.text(addon, "fileNameOnDisk", Json.text(file, "fileNameOnDisk", ""));
                Path jar = directory.resolve("mods").resolve(disk);
                if (disk.isEmpty() || !disk.endsWith(".jar") || disk.contains("/") || disk.contains("\\") || !Files.isRegularFile(jar) || !(addon.get("addonID") instanceof Number project) || !(file.get("id") instanceof Number id)) continue;
                files.add(new int[]{project.intValue(), id.intValue()}); onDisk.put(id.intValue(), jar);
            }
            if (!files.isEmpty()) {
                Set<Path> known = new HashSet<>(onDisk.values());
                List<Mod> listed = new ArrayList<>(fetch(files, onDisk));
                for (Mod mod : mods) if (!known.contains(mod.jar())) listed.add(mod);   // added by hand: sides unknown
                mods = listed;
            }
        }
        mods = modrinthSides(mods);
        if (minecraft.isEmpty() || loaderId.isEmpty()) throw new IllegalArgumentException("This instance folder does not say its game and loader version; add --minecraft 1.20.1 --loader forge-47.4.20.");
        String[] loader = loaderId.split("-", 2);
        return new Pack(input.getFileName().toString(), "", minecraft, loader[0], loader.length > 1 ? loader[1] : "", mods, directory, input, true);
    }
    static List<Mod> local(Path mods) throws IOException {
        List<Mod> found = new ArrayList<>();
        if (!Files.isDirectory(mods)) return found;
        try (var list = Files.list(mods)) { for (Path jar : list.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) found.add(new Mod(jar.getFileName().toString(), jar, "unknown", "folder")); }
        return found;
    }
    static void unzip(Path zip, Path into) throws IOException {
        Path root = into.toAbsolutePath().normalize();
        try (var in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                Path out = root.resolve(entry.getName()).normalize();
                if (!out.startsWith(root)) throw new IOException("Unsafe path in " + zip + ": " + entry.getName());
                if (entry.isDirectory()) { Files.createDirectories(out); continue; }
                Files.createDirectories(out.getParent()); Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Each CurseForge file, from its public file API: name, size and sides, then the file itself; cached per file id.
     * A file already on disk (an instance's mods folder, keyed by file id) is used as it is, and only its sides are looked up.
     */
    List<Mod> fetch(List<int[]> files, Map<Integer, Path> onDisk) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(12);
        long start = System.nanoTime(); int[] downloaded = {0};
        try {
            List<Future<Mod>> jobs = new ArrayList<>();
            for (int[] file : files) jobs.add(pool.submit(() -> {
                Path folder = cache.resolve("curseforge").resolve(file[0] + "-" + file[1]);
                Path metaFile = folder.resolve("file.json");
                if (!Files.isRegularFile(metaFile)) { Files.createDirectories(folder); Files.write(metaFile, get(CURSEFORGE + file[0] + "/files/" + file[1])); }
                var data = Json.object(Json.read(metaFile).get("data"));
                String name = Json.text(data, "fileName", file[1] + ".jar");
                if (name.contains("/") || name.contains("\\") || name.startsWith(".")) throw new IOException("Unsafe file name from CurseForge: " + name);
                Path target = onDisk.containsKey(file[1]) ? onDisk.get(file[1]) : folder.resolve(name); long length = ((Number) data.get("fileLength")).longValue();
                if (onDisk.containsKey(file[1])) name = target.getFileName().toString();
                else if (!Files.isRegularFile(target) || Files.size(target) != length) {
                    byte[] bytes = get(CURSEFORGE + file[0] + "/files/" + file[1] + "/download");
                    if (bytes.length != length) throw new IOException(name + ": CurseForge said " + length + " bytes, sent " + bytes.length);
                    Path temporary = Files.createTempFile(folder, "download-", ".tmp"); Files.write(temporary, bytes);
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                    synchronized (downloaded) { downloaded[0]++; }
                }
                List<Object> versions = Json.array(data.get("gameVersions"));
                boolean client = versions.contains("Client"), server = versions.contains("Server");
                return new Mod(name, target, client && !server ? "client" : server && !client ? "server" : client ? "both" : "unknown", "curseforge " + file[0] + "/" + file[1]);
            }));
            List<Mod> mods = new ArrayList<>();
            for (Future<Mod> job : jobs) mods.add(job.get());
            report.stages.add(Json.map("name", "fetch", "seconds", (System.nanoTime() - start) / 1e9, "files", files.size(), "downloaded", downloaded[0], "cache", cache.resolve("curseforge").toString()));
            return mods;
        } catch (ExecutionException failed) { throw failed.getCause() instanceof Exception e ? e : failed; }
        finally { pool.shutdownNow(); }
    }
    /**
     * Sides for jars nothing else describes (a hand-added jar, any launcher's folder), from Modrinth by the jar's SHA-1:
     * version_files gives the project, projects gives client_side and server_side. A mod whose server side is
     * "unsupported" is client-only. Answers are cached per SHA-1; jars Modrinth does not know stay "unknown".
     */
    List<Mod> modrinthSides(List<Mod> mods) throws Exception {
        Path folder = cache.resolve("modrinth"); Files.createDirectories(folder);
        Map<Mod, String> hashes = new LinkedHashMap<>();
        for (Mod mod : mods) if (mod.side().equals("unknown") && mod.file().endsWith(".jar")) hashes.put(mod, sha1(mod.jar()));
        if (hashes.isEmpty()) return mods;
        long start = System.nanoTime();
        List<String> asked = new ArrayList<>();
        // A known answer is kept; "Modrinth does not know this jar" is asked again after a week.
        for (String hash : new TreeSet<>(hashes.values())) {
            Path answer = folder.resolve(hash + ".json");
            if (!Files.isRegularFile(answer) || (Json.text(Json.read(answer), "project", "").isEmpty() && Files.getLastModifiedTime(answer).toMillis() < System.currentTimeMillis() - 7 * 86_400_000L)) asked.add(hash);
        }
        String blocker = "";
        if (!asked.isEmpty()) try {
            var versions = Json.object(Json.parse(new String(post(MODRINTH + "version_files", Json.write(Json.map("hashes", asked, "algorithm", "sha1"))), StandardCharsets.UTF_8)));
            Map<String, String> projectOf = new HashMap<>();
            for (var entry : versions.entrySet()) projectOf.put(entry.getKey(), Json.text(Json.object(entry.getValue()), "project_id", ""));
            Set<String> ids = new TreeSet<>(projectOf.values()); ids.remove("");
            Map<String, Map<String, Object>> projects = new HashMap<>();
            if (!ids.isEmpty()) for (Object p : Json.array(Json.parse(new String(get(MODRINTH + "projects?ids=" + java.net.URLEncoder.encode(Json.write(new ArrayList<>(ids)), StandardCharsets.UTF_8)), StandardCharsets.UTF_8)))) {
                var project = Json.object(p); projects.put(Json.text(project, "id", ""), project);
            }
            for (String hash : asked) {
                var project = projects.get(projectOf.getOrDefault(hash, ""));
                Json.save(folder.resolve(hash + ".json"), project == null ? Json.map("project", "") : Json.map("project", Json.text(project, "id", ""), "slug", Json.text(project, "slug", ""),
                        "client_side", Json.text(project, "client_side", ""), "server_side", Json.text(project, "server_side", "")));
            }
        } catch (Exception failed) { blocker = "Modrinth could not be reached for mod sides: " + failed.getMessage(); }
        List<Mod> out = new ArrayList<>(); List<String> unknown = new ArrayList<>(); int found = 0;
        for (Mod mod : mods) {
            Path answer = hashes.containsKey(mod) ? folder.resolve(hashes.get(mod) + ".json") : null;
            var a = answer != null && Files.isRegularFile(answer) ? Json.read(answer) : Map.<String, Object>of();
            if (answer == null || Json.text(a, "project", "").isEmpty()) { if (answer != null) unknown.add(mod.file()); out.add(mod); continue; }
            found++;
            boolean client = !"unsupported".equals(a.get("client_side")), server = !"unsupported".equals(a.get("server_side"));
            out.add(new Mod(mod.file(), mod.jar(), client && !server ? "client" : server && !client ? "server" : "both", "modrinth " + Json.text(a, "slug", "")));
        }
        report.stages.add(Json.map("name", "modrinth-sides", "seconds", (System.nanoTime() - start) / 1e9, "asked", hashes.size(), "found", found, "unknown", unknown, "blocker", blocker));
        return out;
    }
    static String sha1(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(file)) { byte[] buffer = new byte[1 << 16]; for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n); }
        return HexFormat.of().formatHex(digest.digest());
    }
    static byte[] post(String url, String json) throws Exception {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            var response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).header("User-Agent", AGENT).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) return response.body();
            last = new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        throw last;
    }
    static byte[] get(String url) throws Exception {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            var response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).header("User-Agent", AGENT).build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) return response.body();
            last = new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        throw last;
    }

    // ------------------------------------------------------------------ without the game
    /** Every mod id each jar provides (with the copies bundled inside other jars), then the checks that need no game. */
    Map<String, List<Provider>> scan(Pack pack) throws IOException {
        Map<String, List<Provider>> providers = new TreeMap<>();
        Map<String, List<String>> required = new TreeMap<>();
        Map<String, List<String>> blockFiles = new TreeMap<>();
        Map<String, Set<String>> blockNames = new TreeMap<>();
        for (Mod mod : pack.mods()) {
            if (!mod.file().endsWith(".jar")) continue;
            try (var zip = new ZipFile(mod.jar().toFile())) {
                read(zip, mod.file(), false, providers, required);
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement(); String name = entry.getName();
                    if (LOOK.matcher(name).matches()) blockFiles.computeIfAbsent(name, k -> new ArrayList<>()).add(mod.file());
                    if (name.matches("assets/[^/]+/lang/en_us\\.json")) try (var in = zip.getInputStream(entry)) {
                        if (Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)) instanceof Map<?, ?> lang)
                            for (var line : lang.entrySet()) {
                                Matcher block = BLOCK_KEY.matcher(String.valueOf(line.getKey()));
                                if (block.matches() && line.getValue() instanceof String shown && !shown.isBlank())
                                    blockNames.computeIfAbsent(shown.strip().toLowerCase(Locale.ROOT), k -> new TreeSet<>()).add(block.group(1) + ":" + block.group(2));
                            }
                    } catch (RuntimeException unreadable) { /* a lang file that is not JSON: the game skips it too */ }
                }
            } catch (ZipException unreadable) {
                findings.add(Json.map("kind", "unreadable-jar", "severity", "problem", "summary", mod.file() + " is not a readable jar: " + unreadable.getMessage()));
            }
        }
        for (var entry : providers.entrySet()) {
            List<String> top = entry.getValue().stream().filter(p -> !p.bundled()).map(Provider::jar).distinct().toList();
            if (top.size() > 1) findings.add(Json.map("kind", "duplicate-mod", "severity", "problem", "summary", "Mod id " + entry.getKey() + " is in " + top.size() + " jars; Forge refuses to start with both.", "jars", top));
        }
        for (var entry : required.entrySet()) if (!providers.containsKey(entry.getKey()) && !Set.of("minecraft", "forge", "neoforge", "javafml", "lowcodefml", "mixin").contains(entry.getKey()))
            findings.add(Json.map("kind", "missing-mod", "severity", "problem", "summary", entry.getValue() + " require mod id " + entry.getKey() + ", which no jar in the pack provides."));
        // One note per set of jars that ship the same look or drop files: only one copy of each is used.
        Map<List<String>, List<String>> shared = new TreeMap<>(Comparator.comparing(Object::toString));
        for (var entry : blockFiles.entrySet()) {
            List<String> jars = entry.getValue().stream().distinct().sorted().toList();
            if (jars.size() > 1) shared.computeIfAbsent(jars, k -> new ArrayList<>()).add(entry.getKey());
        }
        for (var entry : shared.entrySet())
            findings.add(Json.map("kind", "same-block-file", "severity", "note", "summary", String.join(" and ", entry.getKey()) + " both ship " + entry.getValue().size()
                    + " block or item look/drop file(s); only one copy of each is used, so a block or item can look or drop like the other mod's: " + examples(entry.getValue()), "jars", entry.getKey(), "files", entry.getValue()));
        // Blocks from different mods with the same English name: a player can hold one while meaning the other.
        Map<String, Object> sameName = new TreeMap<>();
        for (var entry : blockNames.entrySet())
            if (entry.getValue().stream().map(id -> id.split(":")[0]).distinct().count() > 1) sameName.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        if (!sameName.isEmpty()) {
            List<String> shown = new ArrayList<>();
            for (var entry : sameName.entrySet()) shown.add("\"" + entry.getKey() + "\" " + entry.getValue());
            findings.add(Json.map("kind", "same-block-name", "severity", "note", "summary", sameName.size() + " block name(s) belong to blocks from two or more mods,"
                    + " so a player can place one while expecting the other: " + examples(shown), "names", sameName));
        }
        return providers;
    }
    static final Pattern LOOK = Pattern.compile("assets/[^/]+/(blockstates/.+\\.json|models/(block|item)/.+\\.json|textures/(block|item)/.+\\.png)|data/[^/]+/loot_tables/blocks/.+\\.json");
    static final Pattern BLOCK_KEY = Pattern.compile("block\\.([a-z0-9_\\-]+)\\.([a-z0-9_/]+)");
    static String examples(List<String> items) { return String.join(", ", items.subList(0, Math.min(6, items.size()))) + (items.size() > 6 ? " and " + (items.size() - 6) + " more" : ""); }
    static final Pattern KEY = Pattern.compile("^\\s*([A-Za-z]+)\\s*=\\s*[\"']?([^\"'#]*)[\"']?");
    static void read(ZipFile zip, String owner, boolean bundled, Map<String, List<Provider>> providers, Map<String, List<String>> required) throws IOException {
        ZipEntry toml = zip.getEntry("META-INF/mods.toml");
        if (toml != null) {
            String jarVersion = "";
            ZipEntry manifest = zip.getEntry("META-INF/MANIFEST.MF");
            if (manifest != null) try (var in = zip.getInputStream(manifest)) { jarVersion = String.valueOf(new java.util.jar.Manifest(in).getMainAttributes().getValue("Implementation-Version")); }
            String section = "", modId = null, dependency = null; boolean mandatory = false; String dependencyType = "";
            List<String> lines = new String(zip.getInputStream(toml).readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            List<String> padded = new ArrayList<>(lines); padded.add("[[end]]");
            for (String raw : padded) {
                String line = raw.strip();
                if (line.startsWith("[")) {
                    if (dependency != null && (mandatory || dependencyType.equalsIgnoreCase("required"))) required.computeIfAbsent(dependency, k -> new ArrayList<>()).add(owner);
                    // "[[mods]] #mandatory" is how Forge's template writes it: the comment is not part of the name.
                    section = line.replaceFirst("\\s*#.*$", ""); dependency = null; mandatory = false; dependencyType = "";
                    continue;
                }
                Matcher match = KEY.matcher(line);
                if (!match.find()) continue;
                String key = match.group(1), value = match.group(2).strip();
                if (section.equals("[[mods]]") && key.equals("modId")) modId = value;
                if (section.equals("[[mods]]") && key.equals("version") && modId != null) {
                    String version = value.equals("${file.jarVersion}") ? jarVersion : value;
                    providers.computeIfAbsent(modId, k -> new ArrayList<>()).add(new Provider(owner, version, bundled)); modId = null;
                }
                if (section.startsWith("[[dependencies")) {
                    if (key.equals("modId")) dependency = value;
                    if (key.equals("mandatory")) mandatory = value.equals("true");
                    if (key.equals("type")) dependencyType = value;
                }
            }
            if (modId != null) providers.computeIfAbsent(modId, k -> new ArrayList<>()).add(new Provider(owner, jarVersion, bundled));
        }
        ZipEntry jarjar = zip.getEntry("META-INF/jarjar/metadata.json");
        if (jarjar == null) return;
        for (Object j : Json.array(Json.object(Json.parse(new String(zip.getInputStream(jarjar).readAllBytes(), StandardCharsets.UTF_8))).get("jars"))) {
            String path = Json.text(Json.object(j), "path", "");
            ZipEntry nested = zip.getEntry(path);
            if (nested == null) continue;
            Path temporary = Files.createTempFile("modaccel-nested-", ".jar");
            try {
                try (var in = zip.getInputStream(nested)) { Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING); }
                try (var inner = new ZipFile(temporary.toFile())) { read(inner, owner + "!" + path.substring(path.lastIndexOf('/') + 1), true, providers, required); }
            } catch (ZipException unreadable) { /* a bundled non-jar resource */ }
            finally { Files.deleteIfExists(temporary); }
        }
    }

    /** Forge loads the highest version of a mod id found anywhere, bundled or not; an entry applies when that version is older than its fix. */
    List<Map<String, Object>> knownFaults(Pack pack, Map<String, List<Provider>> providers) throws IOException {
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Object f : Json.array(Json.read(harness.resolve("knowledge/pack-fixes.json")).get("fixes"))) {
            var fix = Json.object(f);
            if (!Json.text(fix, "loader", "").equals(pack.loader()) || !Json.array(fix.get("minecraft")).contains(pack.minecraft())) continue;
            List<Provider> found = providers.get(Json.text(fix, "mod", ""));
            if (found == null) continue;
            Provider used = found.stream().max((a, b) -> Versions.compare(a.version(), b.version())).get();
            if (Versions.compare(used.version(), Json.text(fix, "fixedIn", "")) >= 0) continue;
            matched.add(fix);
            findings.add(Json.map("kind", "known-fault", "severity", "problem", "id", fix.get("id"),
                    "summary", fix.get("mod") + " " + used.version() + " (from " + used.jar() + ") has a proven fault, fixed in " + fix.get("fixedIn") + ": " + fix.get("symptom"),
                    "cause", fix.get("cause"), "evidence", fix.get("evidence"), "fix", fix));
        }
        return matched;
    }

    // ------------------------------------------------------------------ in the game
    /** The pack's own loader server, its mods (less client-only ones) and configs, and the probe. */
    final class ServerLane {
        final Pack pack; final List<Path> extra;
        Path install, instance, probe; Runtimes.Jdk java;
        ServerLane(Pack pack, List<Path> extra) { this.pack = pack; this.extra = extra; }

        Map<String, Object> check(String userWorld) throws Exception {
            String mc = pack.minecraft();
            java = Runtimes.ensureExact(harness, Versions.compare(mc, "1.20.5") >= 0 ? 21 : 17);
            install = forgeServer(mc, pack.loaderVersion());
            probe = probe();
            instance = instance();
            var game = Json.map("ran", true, "server", "Forge " + mc + "-" + pack.loaderVersion(), "java", java.version(), "instance", instance.toString());
            // Start 1: a new world.
            Map<String, Object> first = start("place", "world", Map.of());
            game.put("newWorld", first);
            if (!dropped.isEmpty()) {
                game.put("clientOnly", new ArrayList<>(dropped));
                findings.add(Json.map("kind", "client-only", "severity", "note", "summary", dropped.size() + " mod(s) are client-only but not marked so on CurseForge or Modrinth; a server of this pack must leave them out.", "jars", new ArrayList<>(dropped)));
            }
            if (Boolean.TRUE.equals(first.get("opened"))) {
                Map<String, Object> probeResult = Json.object(first.get("probe"));
                dimensionFindings(probeResult);
                placementFindings(probeResult);
                // Start 2: the same world again.
                Map<String, Object> second = start("verify", "world", Map.of("modaccel.probe.placed", first.get("probeFile").toString()));
                game.put("reopen", second);
                if (!Boolean.TRUE.equals(second.get("opened")))
                    findings.add(Json.map("kind", "world-reopen", "severity", "problem", "summary", "A world this pack made opens once and then fails to open again.", "evidence", second.get("failure")));
                else {
                    var verify = Json.object(second.get("probe"));
                    for (Object change : Json.array(verify.get("changed")))
                        findings.add(Json.map("kind", "changed-by-save", "severity", "problem", "summary", "Saving and reopening changed a block: " + Json.text(Json.object(change), "before", "") + " became " + Json.text(Json.object(change), "after", ""), "evidence", change));
                }
            } else if (String.valueOf(first.get("failure")).contains("invalid dist DEDICATED_SERVER"))
                // Forge names a mod only when its own constructor fails; game code reached through another route names none.
                findings.add(Json.map("kind", "server-start", "severity", "problem", "summary", "A client-only mod stopped the pack's server and Forge did not say which. Neither CurseForge nor Modrinth knows the sides of the mods listed here;"
                        + " one of them is the likely cause. Remove the client-only ones from a copy of this folder and run again.", "evidence", first.get("failure"),
                        "unknownSides", pack.mods().stream().filter(m -> m.side().equals("unknown")).map(Mod::file).toList()));
            else findings.add(Json.map("kind", "server-start", "severity", "problem", "summary", "The pack's server did not finish starting a new world.", "evidence", first.get("failure")));
            if (!userWorld.isEmpty()) {
                Path save = Path.of(userWorld).toAbsolutePath().normalize();
                if (!Files.isRegularFile(save.resolve("level.dat"))) throw new IllegalArgumentException("--world needs a save folder with level.dat; got " + save);
                Path copy = instance.resolve("user-world");
                deleteTree(copy); copyTree(save, copy);
                Map<String, Object> opened = start("open", "user-world", Map.of());
                opened.put("save", save.toString()); opened.put("copiedTo", copy.toString());
                game.put("userWorld", opened);
                if (!Boolean.TRUE.equals(opened.get("opened")))
                    findings.add(Json.map("kind", "user-world", "severity", "problem", "summary", "Your save does not open with this pack (checked on a copy; the original is untouched).", "evidence", opened.get("failure")));
                else dimensionFindings(Json.object(opened.get("probe")));
            }
            return game;
        }

        void dimensionFindings(Map<String, Object> probeResult) throws IOException {
            for (Object d : Json.array(probeResult.get("dimensions"))) {
                var dimension = Json.object(d);
                if (dimension.get("write") == null && dimension.get("read") == null && dimension.get("error") == null) continue;
                List<Object> biomes = Json.array(dimension.getOrDefault("outOfRangeBiomes", List.of()));
                Set<String> mods = new TreeSet<>();
                for (Object b : biomes) mods.add(String.valueOf(b).split(":", 2)[0]);
                String what = dimension.get("write") != null ? "cannot be saved so the next start can read it" : "is saved in a form the next start rejects";
                var finding = Json.map("kind", "dimension-save", "severity", "problem", "dimension", dimension.get("dimension"),
                        "summary", dimension.get("dimension") + " " + what + "; the world will not open again" + (mods.isEmpty() ? "." : ". Biomes from " + String.join(", ", mods) + " carry climate values outside -2..2: " + biomes),
                        "evidence", Json.map("write", dimension.get("write"), "read", dimension.get("read"), "part", dimension.get("failingPart"), "error", dimension.get("error")));
                // A proven repair for this check, when the pack has a matching mod, travels with the finding.
                for (Object f : findings) if ("known-fault".equals(Json.object(f).get("kind")) && "dimension-save".equals(Json.object(Json.object(f).get("fix")).get("check"))) finding.put("fix", Json.object(f).get("fix"));
                findings.add(finding);
            }
        }

        void placementFindings(Map<String, Object> probeResult) {
            for (Object d : Json.array(probeResult.get("different"))) {
                var row = Json.object(d);
                String item = Json.text(row, "item", ""), expected = Json.text(row, "expected", ""), landed = Json.text(row, "landed", "");
                String itemMod = item.split(":")[0], landedMod = landed.split(":")[0];
                boolean otherMod = !landedMod.equals(itemMod) && !landedMod.equals(expected.split(":")[0]) && !landedMod.equals("minecraft");
                findings.add(Json.map("kind", "placed-other-block", "severity", otherMod ? "problem" : "note",
                        "summary", "Placing " + item + " put down " + landed + " instead of " + expected + (otherMod ? ": a block from another mod." : ": the item's own mod does this (a growth stage or a deliberate swap)."),
                        "evidence", row));
            }
            // Items that need a wall, water, a ceiling or more room never landed: say they went unchecked.
            List<Object> unplaced = Json.array(probeResult.getOrDefault("unplaced", List.of()));
            if (!unplaced.isEmpty()) {
                List<String> items = new ArrayList<>();
                for (Object u : unplaced) items.add(Json.text(Json.object(u), "item", ""));
                findings.add(Json.map("kind", "not-checked", "severity", "note", "summary", unplaced.size() + " block item(s) did not place on stone, grass, farmland, sand, a wall, a ceiling or in water, so the swap check did not cover them"
                        + " (some need other ground, darkness or an operator; a mod can also refuse a placement on purpose): "
                        + String.join(", ", items.subList(0, Math.min(12, items.size()))) + (items.size() > 12 ? " ..." : ""), "items", items));
            }
            for (Object f : Json.array(probeResult.get("failed")))
                findings.add(Json.map("kind", "placement-error", "severity", "note", "summary", "Placing " + Json.text(Json.object(f), "item", "") + " by a fake player threw an error (a real player may differ).", "evidence", Json.text(Json.object(f), "error", "").lines().findFirst().orElse("")));
        }

        /** One server start. place makes the world; verify and open read an existing one. */
        Map<String, Object> start(String mode, String level, Map<String, String> properties) throws Exception {
            for (int round = 0; ; round++) {
                Path out = report.directory.resolve("probe-" + mode + ".json"); Files.deleteIfExists(out);
                Path log = report.log("server-" + mode);
                writeServerFiles(level);
                List<String> command = new ArrayList<>(List.of(java.java().toString(), "-Xmx" + options.get("memory", "6G"),
                        "-Dmodaccel.probe.mode=" + mode, "-Dmodaccel.probe.out=" + out));
                for (var p : properties.entrySet()) command.add("-D" + p.getKey() + "=" + p.getValue());
                if (!options.get("only", "").isEmpty()) command.add("-Dmodaccel.probe.only=" + options.get("only", ""));
                command.add("@" + argsFile()); command.add("nogui");
                long start = System.nanoTime();
                String ended = runServer(command, log, out);
                double seconds = (System.nanoTime() - start) / 1e9;
                // Mods log in the system code page as well as UTF-8; a strict read would fail on the first such byte.
                String text = Files.isRegularFile(log) ? new String(Files.readAllBytes(log), StandardCharsets.UTF_8) : "";
                var stage = Json.map("name", "server-" + mode, "seconds", seconds, "log", log.toString(), "ended", ended);
                report.stages.add(stage);
                if (Files.isRegularFile(out)) {
                    var probeResult = Json.read(out);
                    return Json.map("opened", true, "seconds", seconds, "log", log.toString(), "probeFile", out, "probe", probeResult, "doneSeconds", doneSeconds(text));
                }
                // A mod that only works in a game client stops a server at start-up; leave it out and start again.
                Set<String> clientOnly = new LinkedHashSet<>();
                for (String id : clientOnly(text)) for (Provider p : providers.getOrDefault(id, List.of())) if (!p.bundled()) clientOnly.add(p.jar());
                if (mode.equals("place") && !clientOnly.isEmpty() && round < 5) {
                    for (String jar : clientOnly) { Files.deleteIfExists(instance.resolve("mods").resolve(jar)); dropped.add(jar); }
                    deleteTree(instance.resolve(level));
                    continue;
                }
                return Json.map("opened", false, "seconds", seconds, "log", log.toString(), "failure", failure(text));
            }
        }

        /** Runs until the probe is done or the server fails; a server whose main thread died is stopped, since leftover threads keep its JVM alive. */
        String runServer(List<String> command, Path log, Path out) throws Exception {
            Files.createDirectories(log.getParent());
            Process process = new ProcessBuilder(command).directory(instance.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            process.getOutputStream().close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Integer.parseInt(options.get("timeout", "1200")));
            String ended = "exited";
            try {
                while (process.isAlive()) {
                    String text = Files.readString(log, StandardCharsets.ISO_8859_1);
                    String failed = FAILED.stream().filter(text::contains).findFirst().orElse(null);
                    if (failed != null || text.contains("MODACCEL-PROBE-DONE")) {
                        ended = failed != null ? "failed: " + failed : "probe done";
                        if (!process.waitFor(failed != null ? 15 : 120, TimeUnit.SECONDS)) stop(process);
                        break;
                    }
                    if (System.nanoTime() > deadline) { ended = "timed out"; stop(process); break; }
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } finally { if (process.isAlive()) stop(process); }
            return ended;
        }
        void stop(Process process) throws InterruptedException {
            process.descendants().forEach(ProcessHandle::destroy); process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
        }

        void writeServerFiles(String level) throws IOException {
            // Accepted by modaccel only for this throwaway, offline, localhost-only test server, as server-check does.
            Files.writeString(instance.resolve("eula.txt"), "# Accepted by modaccel pack-check for a throwaway local test server (offline mode, 127.0.0.1 only).\neula=true\n");
            int port; try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
            Files.writeString(instance.resolve("server.properties"), "server-ip=127.0.0.1\nserver-port=" + port + "\nonline-mode=false\nspawn-protection=0\nlevel-name=" + level + "\nmotd=modaccel pack-check\n");
        }

        /** The loader's own launch arguments, with its library paths made absolute so the server can run in the instance folder. */
        Path argsFile() throws IOException {
            Path source = install.resolve("libraries/net/minecraftforge/forge/" + pack.minecraft() + "-" + pack.loaderVersion() + "/" + (Runtimes.WINDOWS ? "win_args.txt" : "unix_args.txt"));
            String libraries = install.resolve("libraries").toString().replace('\\', '/');
            String text = Files.readString(source).replace("libraries/", libraries + "/").replace("-DlibraryDirectory=libraries", "-DlibraryDirectory=" + libraries);
            Path target = instance.resolve("modaccel-args.txt"); Files.writeString(target, text); return target;
        }

        Path instance() throws Exception {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (Mod mod : pack.mods()) hash.update((mod.file() + "\n").getBytes(StandardCharsets.UTF_8));
            for (Path jar : extra) hash.update(("extra " + jar.getFileName() + "\n").getBytes(StandardCharsets.UTF_8));
            Path dir = cache.resolve("instances").resolve(HexFormat.of().formatHex(hash.digest()).substring(0, 16));
            Path mods = dir.resolve("mods");
            deleteTree(mods); Files.createDirectories(mods);
            for (Mod mod : pack.mods()) if (mod.file().endsWith(".jar") && !mod.side().equals("client") && !dropped.contains(mod.file())) link(mod.jar(), mods.resolve(mod.file()));
            for (Path jar : extra) link(jar, mods.resolve(jar.getFileName().toString()));
            link(probe, mods.resolve("zz-modaccel-pack-probe.jar"));
            // The pack's own settings, fresh each run; its world, if any, is not a test world.
            for (String folder : List.of("config", "defaultconfigs", "kubejs", "scripts")) {
                deleteTree(dir.resolve(folder));
                if (Files.isDirectory(pack.overrides().resolve(folder))) copyTree(pack.overrides().resolve(folder), dir.resolve(folder));
            }
            deleteTree(dir.resolve("world")); deleteTree(dir.resolve("crash-reports"));
            return dir;
        }

        /** The loader's server, installed by the loader's own installer once per version. */
        Path forgeServer(String mc, String version) throws Exception {
            if (!(mc + version).matches("[0-9A-Za-z.\\-]+")) throw new IllegalArgumentException("Bad loader version " + mc + "-" + version);
            Path dir = cache.resolve("loaders").resolve("forge-" + mc + "-" + version), done = dir.resolve("modaccel-installed");
            if (Files.isRegularFile(done)) return dir;
            Files.createDirectories(dir);
            Path installer = dir.resolve("installer.jar");
            Files.write(installer, get("https://maven.minecraftforge.net/net/minecraftforge/forge/" + mc + "-" + version + "/forge-" + mc + "-" + version + "-installer.jar"));
            var result = Run.command(List.of(java.java().toString(), "-jar", installer.toString(), "--installServer", dir.toString()), dir, Map.of(), report.log("forge-install"), 900);
            report.stage("forge-install", result, Map.of("version", mc + "-" + version));
            if (result.exit() != 0) throw new IOException("Forge's installer failed; see " + result.log());
            Files.writeString(done, mc + "-" + version + "\n");
            return dir;
        }

        /**
         * The probe, written in Mojang names, renamed to Forge's runtime names with the tools Forge's own install
         * downloaded (srgutils, ForgeAutoRenamingTool) and its published mappings. Cached per probe source.
         */
        Path probe() throws Exception {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String source : PROBE) hash.update(Files.readAllBytes(harness.resolve(source)));
            Path dir = install.resolve("modaccel-probe").resolve(HexFormat.of().formatHex(hash.digest()).substring(0, 16));
            Path jar = dir.resolve("probe.jar");
            if (Files.isRegularFile(jar)) return jar;
            Files.createDirectories(dir);
            Path libraries = install.resolve("libraries");
            Path serverMc = only(libraries.resolve("net/minecraft/server"), pack.minecraft() + "-");
            Path mappings = file(serverMc, "-mappings.txt"), srg = file(serverMc, "-srg.jar");
            Path mcp = file(only(libraries.resolve("de/oceanlabs/mcp/mcp_config"), pack.minecraft() + "-"), "-mappings-merged.txt");
            Path forge = libraries.resolve("net/minecraftforge/forge/" + pack.minecraft() + "-" + pack.loaderVersion());
            Path patched = forge.resolve("forge-" + pack.minecraft() + "-" + pack.loaderVersion() + "-server.jar"), universal = forge.resolve("forge-" + pack.minecraft() + "-" + pack.loaderVersion() + "-universal.jar");
            Path srgutils = newest(libraries.resolve("net/minecraftforge/srgutils"), ".jar"), art = newest(libraries.resolve("net/minecraftforge/ForgeAutoRenamingTool"), "-all.jar");
            String javaExe = java.java().toString(), javac = java.home().resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString(), jarTool = java.home().resolve("bin/" + (Runtimes.WINDOWS ? "jar.exe" : "jar")).toString();
            Path names = dir.resolve("mojang-to-runtime.tsrg"), named = dir.resolve("server-mojang.jar"), namedPatched = dir.resolve("forge-server-mojang.jar");
            step("probe-names", List.of(javaExe, "-cp", srgutils.toString(), harness.resolve("tools/packcheck/ProbeNames.java").toString(), mappings.toString(), mcp.toString(), names.toString()), dir);
            step("probe-game-names", List.of(javaExe, "-jar", art.toString(), "--input", srg.toString(), "--output", named.toString(), "--map", names.toString(), "--reverse"), dir);
            step("probe-forge-names", List.of(javaExe, "-jar", art.toString(), "--input", patched.toString(), "--output", namedPatched.toString(), "--map", names.toString(), "--reverse", "--lib", srg.toString()), dir);
            List<String> classpath = new ArrayList<>(List.of(namedPatched.toString(), named.toString()));
            try (var walk = Files.walk(libraries)) {
                walk.filter(p -> p.toString().endsWith(".jar") && !p.startsWith(libraries.resolve("net/minecraft/server")) && !p.equals(patched)).sorted().forEach(p -> classpath.add(p.toString()));
            }
            Path classes = dir.resolve("classes"); deleteTree(classes); Files.createDirectories(classes.resolve("META-INF"));
            step("probe-compile", List.of(javac, "-nowarn", "-proc:none", "--release", "17", "-encoding", "UTF-8", "-cp", String.join(File.pathSeparator, classpath), "-d", classes.toString(),
                    harness.resolve("tools/packcheck/ModaccelPackProbe.java").toString()), dir);
            Files.copy(harness.resolve("tools/packcheck/mods.toml"), classes.resolve("META-INF/mods.toml"), StandardCopyOption.REPLACE_EXISTING);
            // Without pack.mcmeta Forge shows a loading warning for the probe itself, so the check would change what the player sees.
            Files.copy(harness.resolve("tools/packcheck/pack.mcmeta"), classes.resolve("pack.mcmeta"), StandardCopyOption.REPLACE_EXISTING);
            Path namedJar = dir.resolve("probe-mojang.jar");
            step("probe-jar", List.of(jarTool, "--create", "--file", namedJar.toString(), "-C", classes.toString(), "."), dir);
            Path temporary = dir.resolve("probe.jar.tmp");
            step("probe-rename", List.of(javaExe, "-jar", art.toString(), "--input", namedJar.toString(), "--output", temporary.toString(), "--map", names.toString(),
                    "--lib", namedPatched.toString(), "--lib", named.toString(), "--lib", universal.toString()), dir);
            Files.move(temporary, jar, StandardCopyOption.REPLACE_EXISTING);
            return jar;
        }
        void step(String name, List<String> command, Path dir) throws Exception {
            var result = Run.command(command, dir, Map.of(), report.log(name), 600);
            report.stage(name, result, Map.of());
            if (result.exit() != 0) throw new IOException(name + " failed:\n" + Run.failure(result.log()));
        }
    }

    static final List<String> FAILED = List.of("Failed to start the minecraft server", "Failed to load datapacks", "Crash report saved to", "Encountered an unexpected exception", "Mod Loading has failed", "Failed to complete lifecycle event");
    static double doneSeconds(String log) {
        Matcher m = Pattern.compile("Done \\(([0-9.]+)s\\)").matcher(log);
        return m.find() ? Double.parseDouble(m.group(1)) : -1;
    }
    static final Pattern MOD_FAILED = Pattern.compile("\\[[^\\]]*/LOADING\\]: Failed to [^.]+\\. ModID: ([a-z0-9_.\\-]+)");
    /**
     * The mod ids whose failure while loading on a server comes from client-only game classes. Forge writes one block
     * per failed mod: "Failed to create mod instance. ModID: blur, ..." then the stack trace, whose cause reads
     * "Attempted to load class net/minecraft/client/Minecraft for invalid dist DEDICATED_SERVER". A mod failing for any
     * other reason is not counted: leaving it out would hide a real fault.
     */
    static Set<String> clientOnly(String log) {
        Set<String> ids = new LinkedHashSet<>();
        if (!log.contains("Mod Loading has failed")) return ids;
        List<String> lines = log.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            Matcher failed = MOD_FAILED.matcher(lines.get(i));
            if (!failed.find()) continue;
            for (int j = i + 1; j < lines.size() && !lines.get(j).startsWith("["); j++)
                if (lines.get(j).contains("for invalid dist DEDICATED_SERVER")) { ids.add(failed.group(1)); break; }
        }
        return ids;
    }
    /** What stopped a start: the loader's or game's own words, and their causes. */
    static String failure(String log) {
        List<String> lines = log.lines().toList(), out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (FAILED.stream().anyMatch(line::contains) || line.startsWith("Caused by:") || line.contains("Exception message:") || line.contains("/FATAL]")) out.add(line.strip());
        }
        if (out.isEmpty()) out.addAll(lines.subList(Math.max(0, lines.size() - 12), lines.size()));
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(out));
        return String.join("\n", unique.subList(Math.max(0, unique.size() - 14), unique.size()));
    }

    // ------------------------------------------------------------------ repairs
    /** Runs both starts again with each catalogued repair added, and changes the user's pack only when that shows it working. */
    Map<String, Object> apply(Pack pack, List<Map<String, Object>> fixes) throws Exception {
        if (fixes.isEmpty()) return Json.map("applied", List.of(), "details", "No finding has a proven repair in knowledge/pack-fixes.json.");
        List<Path> jars = new ArrayList<>(); List<Object> applied = new ArrayList<>();
        for (var fix : fixes) {
            var add = Json.object(fix.get("add"));
            Path jar = cache.resolve("fixes").resolve(Json.text(add, "file", ""));
            if (!Files.isRegularFile(jar) || !sha512(Files.readAllBytes(jar)).equals(add.get("sha512"))) {
                byte[] bytes = get(Json.text(add, "url", ""));
                if (!sha512(bytes).equals(add.get("sha512"))) throw new IOException(add.get("file") + " from " + add.get("url") + " does not match its recorded SHA-512; not applied.");
                Files.createDirectories(jar.getParent()); Files.write(jar, bytes);
            }
            jars.add(jar); applied.add(Json.map("id", fix.get("id"), "add", add.get("file"), "page", add.get("page")));
        }
        var result = Json.map("applied", applied);
        // Proof first: the same two starts with the repair added, on modaccel's own copy. The user's files change only after it passes.
        List<Object> before = new ArrayList<>(findings);
        findings.clear();
        Map<String, Object> again = new ServerLane(pack, jars).check(options.get("world", ""));
        List<Object> after = new ArrayList<>(findings);
        findings.clear(); findings.addAll(before);
        long remaining = after.stream().filter(f -> "problem".equals(Json.object(f).get("severity")) && !"known-fault".equals(Json.object(f).get("kind"))).count();
        boolean proven = remaining == 0 && Boolean.TRUE.equals(Json.object(again.getOrDefault("reopen", Json.map())).get("opened"));
        result.put("recheck", again); result.put("recheckFindings", after); result.put("proven", proven);
        if (!proven) {
            result.put("applied", List.of()); result.put("tried", applied);
            result.put("details", "The repair did not clear every problem when checked, so nothing of yours was changed. See recheckFindings.");
            return result;
        }
        if (pack.instance()) {
            // The user's instance: add the files and record them so --undo removes exactly those.
            Path record = pack.input().resolve(".modaccel/pack-fixes.json");
            List<Object> added = new ArrayList<>(Files.isRegularFile(record) ? Json.array(Json.read(record).get("added")) : List.of());
            for (Path jar : jars) {
                Path target = pack.input().resolve("mods").resolve(jar.getFileName().toString());
                if (Files.exists(target)) continue;
                Files.copy(jar, target); added.add(target.getFileName().toString());
            }
            Json.save(record, Json.map("added", added));
            result.put("changed", pack.input().resolve("mods").toString()); result.put("undo", "modaccel pack-check --pack \"" + pack.input() + "\" --undo");
        } else {
            // An export: a fixed copy, the original untouched. CurseForge puts overrides/mods/ into the instance's mods folder on import.
            Path fixed = report.directory.resolve(pack.input().getFileName().toString().replaceFirst("(?i)\\.zip$", "") + " (modaccel fixed).zip");
            Path source = Files.isRegularFile(pack.input()) ? pack.input() : null;
            try (var out = new ZipOutputStream(Files.newOutputStream(fixed))) {
                if (source != null) try (var in = new ZipInputStream(Files.newInputStream(source))) {
                    for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) { out.putNextEntry(new ZipEntry(entry.getName())); in.transferTo(out); out.closeEntry(); }
                } else try (var walk = Files.walk(pack.input())) {
                    for (Path file : walk.filter(Files::isRegularFile).toList()) { out.putNextEntry(new ZipEntry(pack.input().relativize(file).toString().replace('\\', '/'))); Files.copy(file, out); out.closeEntry(); }
                }
                for (Path jar : jars) { out.putNextEntry(new ZipEntry(pack.overrides().getFileName() + "/mods/" + jar.getFileName())); Files.copy(jar, out); out.closeEntry(); }
            }
            result.put("fixedExport", fixed.toString());
            result.put("import", "Import the fixed .zip in the CurseForge app, or copy " + jars.stream().map(p -> p.getFileName().toString()).toList() + " into the instance's mods folder.");
        }
        return result;
    }
    Map<String, Object> undo(Path input) throws IOException {
        Path record = input.resolve(".modaccel/pack-fixes.json");
        if (!Files.isRegularFile(record)) return Json.map("ok", true, "undone", List.of(), "details", "pack-check has not changed this folder.");
        List<Object> removed = new ArrayList<>();
        for (Object name : Json.array(Json.read(record).get("added"))) if (Files.deleteIfExists(input.resolve("mods").resolve(String.valueOf(name)))) removed.add(name);
        Files.delete(record);
        return Json.map("ok", true, "undone", removed, "details", "Removed " + removed + " from " + input.resolve("mods") + ".");
    }

    // ------------------------------------------------------------------ text and files
    static String text(Map<String, Object> result) {
        StringBuilder out = new StringBuilder();
        out.append(result.get("pack")).append(" (Minecraft ").append(result.get("minecraft")).append(", ").append(result.get("loader")).append(", ").append(result.get("mods")).append(" files)\n");
        var game = Json.object(result.getOrDefault("game", Json.map()));
        if (Boolean.TRUE.equals(game.get("ran"))) {
            var first = Json.object(game.getOrDefault("newWorld", Json.map()));
            out.append("New world: ").append(Boolean.TRUE.equals(first.get("opened")) ? "opened" : "did not open").append('\n');
            if (first.get("probe") instanceof Map<?, ?> p) out.append("Block items placed: ").append(p.get("blockItems")).append(", landed as the item says: ").append(p.get("landedAsExpected")).append('\n');
            if (game.get("reopen") instanceof Map<?, ?> r) out.append("Same world, second start: ").append(Boolean.TRUE.equals(r.get("opened")) ? "opened" : "FAILED").append('\n');
            if (game.get("userWorld") instanceof Map<?, ?> u) out.append("Your save (a copy): ").append(Boolean.TRUE.equals(u.get("opened")) ? "opened" : "FAILED").append('\n');
        } else if (game.get("blocker") != null) out.append("Not run in the game: ").append(game.get("blocker")).append('\n');
        for (Object f : Json.array(result.get("findings"))) {
            var finding = Json.object(f);
            out.append("problem".equals(finding.get("severity")) ? "PROBLEM " : "note    ").append(finding.get("summary")).append('\n');
        }
        if (result.get("fix") instanceof Map<?, ?> fix) {
            out.append("Repair: ").append(fix.get("applied")).append('\n');
            if (fix.get("fixedExport") != null) out.append("Fixed copy: ").append(fix.get("fixedExport")).append('\n');
            if (fix.get("undo") != null) out.append("Undo: ").append(fix.get("undo")).append('\n');
            out.append("Checked with the repair: ").append(Boolean.TRUE.equals(fix.get("proven")) ? "both starts open, no problems left" : "problems remain (see recheckFindings), so nothing of yours was changed. Tried: " + fix.get("tried")).append('\n');
        }
        return out.toString();
    }
    static String sha512(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes)); }
    static Path only(Path parent, String prefix) throws IOException {
        try (var list = Files.list(parent)) { return list.filter(p -> p.getFileName().toString().startsWith(prefix)).findFirst().orElseThrow(() -> new IOException("Nothing named " + prefix + "* in " + parent)); }
    }
    static Path file(Path dir, String suffix) throws IOException {
        try (var list = Files.list(dir)) { return list.filter(p -> p.getFileName().toString().endsWith(suffix)).findFirst().orElseThrow(() -> new IOException("No *" + suffix + " in " + dir)); }
    }
    static Path newest(Path dir, String suffix) throws IOException {
        try (var walk = Files.walk(dir)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(suffix) && !p.getFileName().toString().endsWith("-sources.jar"))
                    .max((a, b) -> Versions.compare(a.getParent().getFileName().toString(), b.getParent().getFileName().toString())).orElseThrow(() -> new IOException("No *" + suffix + " under " + dir));
        }
    }
    static void link(Path from, Path to) throws IOException {
        try { Files.createLink(to, from); } catch (IOException | UnsupportedOperationException differentDrive) { Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }
    static void copyTree(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target); else { Files.createDirectories(target.getParent()); Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
    }
    static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) { for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
    }
}
