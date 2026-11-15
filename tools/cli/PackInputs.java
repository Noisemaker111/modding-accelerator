import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * pack-check's inputs: the shapes a modpack arrives in. Each adapter recognises its shape from a file only it has and
 * reads the game version, the loader and the mod jars from it; --minecraft and --loader override what it reads. The
 * first adapter that accepts a folder reads it, so a more specific shape comes before a more general one. A new
 * shape is one more Input in ALL.
 */
final class PackInputs {

    private PackInputs() {}

    interface Input {
        /** A short name, shown in the result as the pack's format. */
        String id();

        String describe();

        boolean accepts(Path directory) throws IOException;

        PackCheck.Pack read(PackCheck check, Path input, Path directory) throws Exception;
    }

    static final List<Input> ALL = List.of(
        new CurseForgeExport(),
        new ModrinthPack(),
        new CurseForgeInstance(),
        new PrismInstance(),
        new LauncherFolder(),
        new ModsFolder()
    );

    static Input find(Path directory) throws IOException {
        for (Input input : ALL) if (input.accepts(directory)) return input;
        return null;
    }

    /** A loader id as packs write it ("forge-47.4.20", "fabric-loader", "net.neoforged"), as {loader, version}. */
    static String[] loader(PackCheck check, String name, String version) throws IOException {
        if (name.isEmpty()) return new String[] { "", "" };
        PackLoaders.Loader known = PackLoaders.find(check.harness, name);
        if (known == null && version.isEmpty() && name.contains("-")) {
            // "forge-47.4.20": the loader and its version in one word.
            int dash = name.indexOf('-');
            PackLoaders.Loader split = PackLoaders.find(check.harness, name.substring(0, dash));
            if (split != null) return new String[] { split.id(), name.substring(dash + 1) };
        }
        return new String[] { known == null ? name.toLowerCase(Locale.ROOT) : known.id(), version };
    }

    /** --minecraft and --loader win over what the pack says. */
    static String[] overridden(PackCheck check, String minecraft, String loaderId, String loaderVersion) throws IOException {
        String mc = check.options.get("minecraft", "");
        String[] loader = new String[] { loaderId, loaderVersion };
        String flag = check.options.get("loader", "");
        if (!flag.isEmpty()) loader = loader(check, flag, "");
        return new String[] { mc.isEmpty() ? minecraft : mc, loader[0], loader[1] };
    }

    // ------------------------------------------------------------------ CurseForge
    static final class CurseForgeExport implements Input {

        public String id() {
            return "curseforge-export";
        }

        public String describe() {
            return "a CurseForge export (.zip or its folder): manifest.json lists each mod's project and file";
        }

        public boolean accepts(Path directory) throws IOException {
            Path manifest = directory.resolve("manifest.json");
            return Files.isRegularFile(manifest) && Json.read(manifest).containsKey("minecraft");
        }

        public PackCheck.Pack read(PackCheck check, Path input, Path directory) throws Exception {
            var m = Json.read(directory.resolve("manifest.json"));
            var minecraft = Json.object(m.get("minecraft"));
            String loaderId = "";
            for (Object l : Json.array(minecraft.get("modLoaders"))) if (
                Boolean.TRUE.equals(Json.object(l).get("primary")) || loaderId.isEmpty()
            ) loaderId = Json.text(Json.object(l), "id", "");
            List<int[]> files = new ArrayList<>();
            for (Object f : Json.array(m.get("files"))) {
                var file = Json.object(f);
                files.add(new int[] { ((Number) file.get("projectID")).intValue(), ((Number) file.get("fileID")).intValue() });
            }
            Path overrides = directory.resolve(Json.text(m, "overrides", "overrides"));
            List<PackCheck.Mod> mods = new ArrayList<>(check.fetch(files, Map.of()));
            mods.addAll(PackCheck.local(overrides.resolve("mods")));
            mods = check.modrinthSides(mods);
            String[] loader = loader(check, loaderId, "");
            String[] chosen = overridden(check, Json.text(minecraft, "version", ""), loader[0], loader[1]);
            return new PackCheck.Pack(
                Json.text(m, "name", input.getFileName().toString()).strip(),
                Json.text(m, "version", "").strip(),
                chosen[0],
                chosen[1],
                chosen[2],
                mods,
                List.of(overrides),
                input,
                false,
                id()
            );
        }
    }

    static final class CurseForgeInstance implements Input {

        public String id() {
            return "curseforge-instance";
        }

        public String describe() {
            return "a CurseForge app instance: minecraftinstance.json and mods/";
        }

        public boolean accepts(Path directory) {
            return Files.isRegularFile(directory.resolve("minecraftinstance.json"));
        }

        public PackCheck.Pack read(PackCheck check, Path input, Path directory) throws Exception {
            var i = Json.read(directory.resolve("minecraftinstance.json"));
            String minecraft = Json.text(i, "gameVersion", ""), loaderId = "";
            if (i.get("baseModLoader") instanceof Map<?, ?> base) loaderId = String.valueOf(base.get("name"));
            List<PackCheck.Mod> mods = PackCheck.local(directory.resolve("mods"));
            // The CurseForge app lists each installed mod's project and file: its sides come from the same file API as an export's.
            List<int[]> files = new ArrayList<>();
            Map<Integer, Path> onDisk = new HashMap<>();
            for (Object a : Json.array(i.getOrDefault("installedAddons", List.of()))) {
                var addon = Json.object(a);
                var file = Json.object(addon.getOrDefault("installedFile", Map.of()));
                String disk = Json.text(addon, "fileNameOnDisk", Json.text(file, "fileNameOnDisk", ""));
                Path jar = directory.resolve("mods").resolve(disk);
                if (
                    disk.isEmpty() ||
                    !disk.endsWith(".jar") ||
                    disk.contains("/") ||
                    disk.contains("\\") ||
                    !Files.isRegularFile(jar) ||
                    !(addon.get("addonID") instanceof Number project) ||
                    !(file.get("id") instanceof Number id)
                ) continue;
                files.add(new int[] { project.intValue(), id.intValue() });
                onDisk.put(id.intValue(), jar);
            }
            if (!files.isEmpty()) {
                Set<Path> known = new HashSet<>(onDisk.values());
                List<PackCheck.Mod> listed = new ArrayList<>(check.fetch(files, onDisk));
                for (PackCheck.Mod mod : mods) if (!known.contains(mod.jar())) listed.add(mod); // added by hand: sides unknown
                mods = listed;
            }
            mods = check.modrinthSides(mods);
            String[] loader = loader(check, loaderId, "");
            String[] chosen = overridden(check, minecraft, loader[0], loader[1]);
            return named(check, input, chosen, mods, directory, id());
        }
    }

    // ------------------------------------------------------------------ Modrinth
    static final class ModrinthPack implements Input {

        /** The hosts a .mrpack may download from, as Modrinth's format allows. */
        static final Set<String> HOSTS = Set.of("cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com");

        public String id() {
            return "modrinth-mrpack";
        }

        public String describe() {
            return "a Modrinth .mrpack (or its folder): modrinth.index.json lists each file with its download, hashes and sides";
        }

        public boolean accepts(Path directory) {
            return Files.isRegularFile(directory.resolve("modrinth.index.json"));
        }

        public PackCheck.Pack read(PackCheck check, Path input, Path directory) throws Exception {
            var index = Json.read(directory.resolve("modrinth.index.json"));
            var dependencies = Json.object(index.get("dependencies"));
            String minecraft = Json.text(dependencies, "minecraft", ""), loaderId = "", loaderVersion = "";
            for (var entry : dependencies.entrySet()) if (!entry.getKey().equals("minecraft")) {
                loaderId = entry.getKey();
                loaderVersion = String.valueOf(entry.getValue());
            }
            long start = System.nanoTime();
            int others = 0;
            int[] downloaded = { 0 };
            List<java.util.concurrent.Callable<PackCheck.Mod>> jobs = new ArrayList<>();
            Path cache = check.cache.resolve("modrinth-files");
            for (Object f : Json.array(index.get("files"))) {
                var file = Json.object(f);
                String path = Json.text(file, "path", "");
                if (path.contains("..") || path.startsWith("/") || path.contains("\\") || path.contains(":")) throw new IOException(
                    "Unsafe path in modrinth.index.json: " + path
                );
                if (!path.startsWith("mods/") || !path.endsWith(".jar")) {
                    others++;
                    continue;
                }
                jobs.add(() -> file(file, path, cache, downloaded));
            }
            List<PackCheck.Mod> mods = new ArrayList<>();
            var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
            try {
                for (var job : pool.invokeAll(jobs)) mods.add(job.get());
            } catch (java.util.concurrent.ExecutionException failed) {
                throw failed.getCause() instanceof Exception e ? e : failed;
            } finally {
                pool.shutdownNow();
            }
            check.report.stages.add(
                Json.map("name", "fetch", "seconds", (System.nanoTime() - start) / 1e9, "files", mods.size(), "downloaded", downloaded[0], "notMods", others)
            );
            List<Path> layers = new ArrayList<>();
            for (String layer : List.of("overrides", "server-overrides")) if (Files.isDirectory(directory.resolve(layer))) {
                layers.add(directory.resolve(layer));
                mods.addAll(PackCheck.local(directory.resolve(layer).resolve("mods")));
            }
            if (layers.isEmpty()) layers.add(directory.resolve("overrides"));
            mods = check.modrinthSides(mods);
            String[] loader = loader(check, loaderId, loaderVersion);
            String[] chosen = overridden(check, minecraft, loader[0], loader[1]);
            return new PackCheck.Pack(
                Json.text(index, "name", input.getFileName().toString()).strip(),
                Json.text(index, "versionId", "").strip(),
                chosen[0],
                chosen[1],
                chosen[2],
                mods,
                layers,
                input,
                false,
                id()
            );
        }
    }

    /** One file of an .mrpack: from the cache by its SHA-1, or downloaded from a host the format allows and checked against its hashes. */
    static PackCheck.Mod file(Map<String, Object> file, String path, Path cache, int[] downloaded) throws Exception {
        var hashes = Json.object(file.get("hashes"));
        String sha1 = Json.text(hashes, "sha1", ""), sha512 = Json.text(hashes, "sha512", "");
        if (!sha1.matches("[0-9a-f]{40}")) throw new IOException(path + " has no SHA-1 in modrinth.index.json");
        String name = path.substring(path.lastIndexOf('/') + 1);
        Path jar = cache.resolve(sha1).resolve(name);
        if (!Files.isRegularFile(jar) || !PackCheck.sha1(jar).equals(sha1)) {
            byte[] bytes = null;
            IOException last = null;
            for (Object u : Json.array(file.get("downloads"))) {
                String url = String.valueOf(u);
                if (!ModrinthPack.HOSTS.contains(String.valueOf(URI.create(url).getHost()))) {
                    last = new IOException(path + ": downloads only from " + ModrinthPack.HOSTS + ", not " + url);
                    continue;
                }
                try {
                    bytes = PackCheck.get(url);
                    break;
                } catch (Exception failed) {
                    last = new IOException(path + " from " + url + ": " + failed.getMessage());
                }
            }
            if (bytes == null) throw last == null ? new IOException(path + " lists no download") : last;
            if (!PackCheck.hash("SHA-1", bytes).equals(sha1) || (!sha512.isEmpty() && !PackCheck.hash("SHA-512", bytes).equals(sha512))) {
                throw new IOException(path + " does not match its hashes in modrinth.index.json");
            }
            Files.createDirectories(jar.getParent());
            Path temporary = Files.createTempFile(jar.getParent(), "download-", ".tmp");
            Files.write(temporary, bytes);
            Files.move(temporary, jar, StandardCopyOption.REPLACE_EXISTING);
            synchronized (downloaded) {
                downloaded[0]++;
            }
        }
        var env = Json.object(file.getOrDefault("env", Map.of()));
        boolean client = !"unsupported".equals(env.get("client")), server = !"unsupported".equals(env.get("server"));
        // The pack's own word only when it rules a side out; "required" on both is the default, so Modrinth's project is asked.
        String side = client && !server ? "client" : server && !client ? "server" : "unknown";
        return new PackCheck.Mod(name, jar, side, "modrinth-index " + path);
    }

    // ------------------------------------------------------------------ launchers
    /** Prism Launcher and MultiMC: mmc-pack.json names the game and loader components; the game folder is .minecraft or minecraft. */
    static final class PrismInstance implements Input {

        static final Map<String, String> COMPONENTS = Map.of(
            "net.minecraftforge",
            "forge",
            "net.neoforged",
            "neoforge",
            "net.fabricmc.fabric-loader",
            "fabric",
            "org.quiltmc.quilt-loader",
            "quilt"
        );

        public String id() {
            return "prism-instance";
        }

        public String describe() {
            return "a Prism Launcher or MultiMC instance: mmc-pack.json and .minecraft/mods";
        }

        public boolean accepts(Path directory) {
            return Files.isRegularFile(directory.resolve("mmc-pack.json"));
        }

        public PackCheck.Pack read(PackCheck check, Path input, Path directory) throws Exception {
            String minecraft = "", loaderId = "", loaderVersion = "";
            for (Object c : Json.array(Json.read(directory.resolve("mmc-pack.json")).get("components"))) {
                var component = Json.object(c);
                String uid = Json.text(component, "uid", ""), version = Json.text(component, "version", "");
                if (uid.equals("net.minecraft")) minecraft = version;
                if (COMPONENTS.containsKey(uid)) {
                    loaderId = COMPONENTS.get(uid);
                    loaderVersion = version;
                }
            }
            Path game = Files.isDirectory(directory.resolve(".minecraft")) ? directory.resolve(".minecraft") : directory.resolve("minecraft");
            List<PackCheck.Mod> mods = check.modrinthSides(PackCheck.local(game.resolve("mods")));
            String[] chosen = overridden(check, minecraft, loaderId, loaderVersion);
            return named(check, input, chosen, mods, game, id());
        }
    }

    /** The official launcher's game folder: versions/*.json says which loader each installed profile adds to which game version. */
    static final class LauncherFolder implements Input {

        public String id() {
            return "launcher-folder";
        }

        public String describe() {
            return "the vanilla launcher's game folder (.minecraft): versions/ names the loader, mods/ holds the jars";
        }

        public boolean accepts(Path directory) {
            return Files.isDirectory(directory.resolve("versions")) && Files.isDirectory(directory.resolve("mods"));
        }

        public PackCheck.Pack read(PackCheck check, Path input, Path directory) throws Exception {
            // Each loader profile: its id, the game it inherits from, and the loader library it adds.
            Map<String, String[]> profiles = new TreeMap<>();
            try (var list = Files.list(directory.resolve("versions"))) {
                for (Path folder : list.filter(Files::isDirectory).toList()) {
                    Path json = folder.resolve(folder.getFileName() + ".json");
                    if (!Files.isRegularFile(json)) continue;
                    var version = Json.read(json);
                    String inherits = Json.text(version, "inheritsFrom", "");
                    for (Object l : Json.array(version.getOrDefault("libraries", List.of()))) {
                        String[] coordinates = Json.text(Json.object(l), "name", "").split(":");
                        if (coordinates.length < 3) continue;
                        String loaderId = switch (coordinates[0] + ":" + coordinates[1]) {
                            case "net.fabricmc:fabric-loader" -> "fabric";
                            case "org.quiltmc:quilt-loader" -> "quilt";
                            case "net.neoforged:neoforge" -> "neoforge";
                            case "net.minecraftforge:forge", "net.minecraftforge:fmlloader" -> "forge";
                            default -> "";
                        };
                        if (loaderId.isEmpty()) continue;
                        String loaderVersion = coordinates[2].startsWith(inherits + "-") ? coordinates[2].substring(inherits.length() + 1) : coordinates[2];
                        if (loaderId.equals("forge") && coordinates[1].equals("fmlloader")) continue;
                        profiles.put(folder.getFileName().toString(), new String[] { inherits, loaderId, loaderVersion });
                    }
                }
            }
            String[] picked = null;
            if (profiles.size() == 1) picked = profiles.values().iterator().next();
            else if (profiles.size() > 1) {
                // Several loader profiles: the one the launcher used last.
                Path launcher = directory.resolve("launcher_profiles.json");
                String newest = "", last = "";
                if (Files.isRegularFile(launcher)) for (Object p : Json.object(Json.read(launcher).get("profiles")).values()) {
                    var profile = Json.object(p);
                    String used = Json.text(profile, "lastUsed", "");
                    if (used.compareTo(newest) > 0 && profiles.containsKey(Json.text(profile, "lastVersionId", ""))) {
                        newest = used;
                        last = Json.text(profile, "lastVersionId", "");
                    }
                }
                picked = profiles.get(last);
            }
            String[] chosen = overridden(check, picked == null ? "" : picked[0], picked == null ? "" : picked[1], picked == null ? "" : picked[2]);
            if (chosen[0].isEmpty() || chosen[1].isEmpty()) throw new IllegalArgumentException(
                "This game folder has " + profiles.size() + " loader profile(s) " + profiles.keySet() + " and none was used last; add --minecraft and --loader."
            );
            return named(check, input, chosen, check.modrinthSides(PackCheck.local(directory.resolve("mods"))), directory, id());
        }
    }

    /** Any folder with mods/: the loader is read from the jars when they all agree, the game version from --minecraft or the jars' own ranges. */
    static final class ModsFolder implements Input {

        public String id() {
            return "mods-folder";
        }

        public String describe() {
            return "any folder with mods/ (the loader and game version come from --loader/--minecraft or from the jars)";
        }

        public boolean accepts(Path directory) {
            return Files.isDirectory(directory.resolve("mods"));
        }

        public PackCheck.Pack read(PackCheck check, Path input, Path directory) throws Exception {
            List<PackCheck.Mod> mods = PackCheck.local(directory.resolve("mods"));
            String[] chosen = overridden(check, "", "", "");
            Map<String, Object> evidence = new LinkedHashMap<>();
            if (chosen[1].isEmpty()) chosen[1] = inferLoader(check, mods, evidence);
            if (chosen[0].isEmpty() && !chosen[1].isEmpty()) chosen[0] = inferMinecraft(check, mods, chosen[1], evidence);
            if (chosen[0].isEmpty() || chosen[1].isEmpty()) throw new IllegalArgumentException(
                "This folder does not say its game and loader, and its jars do not settle it" +
                (evidence.isEmpty() ? "" : " (" + evidence + ")") +
                "; add --minecraft 1.20.1 --loader forge-47.4.20."
            );
            if (!evidence.isEmpty()) check.report.stages.add(Json.map("name", "inferred", "evidence", evidence));
            return named(check, input, chosen, check.modrinthSides(mods), directory, id());
        }

        /** The loader every jar with loader metadata is written for, when they agree. */
        static String inferLoader(PackCheck check, List<PackCheck.Mod> mods, Map<String, Object> evidence) throws IOException {
            Map<String, Integer> votes = new TreeMap<>();
            for (var loader : PackLoaders.all(check.harness).values()) {
                List<String> files = loader.metadata("99");
                files = files.isEmpty() ? loader.metadata("1.0") : files;
                for (PackCheck.Mod mod : mods) try (var zip = new java.util.zip.ZipFile(mod.jar().toFile())) {
                    if (zip.getEntry(files.get(0)) != null) votes.merge(loader.id(), 1, Integer::sum);
                } catch (IOException unreadable) {
                    /* counted by the scan */
                }
            }
            evidence.put("loaderVotes", votes);
            // Quilt reads Fabric mods, so a folder of Fabric jars is Fabric; Forge and NeoForge share mods.toml before 1.20.5.
            votes.remove("quilt");
            if (votes.size() == 1) return votes.keySet().iterator().next();
            if (votes.keySet().equals(Set.of("forge", "neoforge"))) return votes.get("neoforge") > votes.get("forge") ? "neoforge" : "forge";
            return "";
        }

        /** The newest release every jar's own "minecraft" dependency accepts. */
        static String inferMinecraft(PackCheck check, List<PackCheck.Mod> mods, String loaderId, Map<String, Object> evidence)
            throws Exception {
            PackLoaders.Loader loader = PackLoaders.find(check.harness, loaderId);
            PackMods.Scan scan = PackMods.scan(mods, loader.metadata("99").isEmpty() ? loader.metadata("1.0") : loader.metadata("99"));
            List<PackMods.Dependency> ranges = scan.dependencies.stream().filter(d -> d.id().equals("minecraft") && d.kind().equals("required")).toList();
            if (ranges.isEmpty()) return "";
            String manifest = Json.text(Json.object(PackLoaders.data(check.harness).get("minecraft")), "manifest", "");
            List<String> accepted = new ArrayList<>();
            for (Object v : Json.array(Json.object(Json.parse(new String(PackCheck.get(manifest), StandardCharsets.UTF_8))).get("versions"))) {
                var row = Json.object(v);
                if (!"release".equals(row.get("type"))) continue;
                String id = Json.text(row, "id", "");
                if (ranges.stream().allMatch(d -> PackMods.accepts(d, id))) accepted.add(id);
            }
            evidence.put("minecraftFrom", ranges.size() + " mods' minecraft ranges");
            evidence.put("minecraftAccepted", accepted.size() > 8 ? accepted.subList(0, 8) + " ..." : accepted);
            return accepted.stream().max(Versions::compare).orElse("");
        }
    }

    static PackCheck.Pack named(PackCheck check, Path input, String[] chosen, List<PackCheck.Mod> mods, Path game, String format) {
        if (chosen[0].isEmpty() || chosen[1].isEmpty()) throw new IllegalArgumentException(
            "This " + format + " does not say its game and loader version; add --minecraft 1.20.1 --loader forge-47.4.20."
        );
        return new PackCheck.Pack(input.getFileName().toString(), "", chosen[0], chosen[1], chosen[2], mods, List.of(game), input, true, format);
    }
}
