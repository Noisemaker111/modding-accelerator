import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * pack-check's loader adapters: how a pack's loader server is provisioned and started, and the game itself
 * (vanilla server, its names, its Java) that every loader builds on.
 *
 * A loader is an entry in knowledge/pack-loaders.json with a "style": "args-file" (an installer that writes the
 * server's launch arguments to a file, as Forge and NeoForge do) or "launch-jar" (an installer that writes a launch
 * jar beside the vanilla server, as Fabric and Quilt do). Versions come from the loader's own metadata; nothing here
 * names a loader or game version. A loader that fits neither style is one more Style class here.
 */
final class PackLoaders {

    private PackLoaders() {}

    /** What every adapter is given: where modaccel lives, the download cache, the receipt, and the Java the game needs. */
    record Context(Path harness, Path cache, Modaccel.Report report, Runtimes.Jdk java) {}

    /** The game's run-time names: "mojang" (no renaming), or another kind with a mapping file from the official names. */
    record Names(String kind, Path mapping) {}

    /** What pack-check asks of a loader. */
    interface Loader {
        String id();

        /** The newest stable loader version for this game version, from the loader's own metadata. */
        String newest(Context context, String minecraft) throws Exception;

        /** The loader's server, installed once per version by its official installer. */
        Path install(Context context, String minecraft, String version) throws Exception;

        /** The arguments after java and its options that start the installed server with instance as its folder. */
        List<String> launch(Path install, Path instance, String minecraft, String version) throws IOException;

        /** The names the game's code has at run time on this loader. */
        Names names(Context context, Path install, String minecraft) throws Exception;

        /** The metadata files this loader reads in a mod jar, most preferred first. */
        List<String> metadata(String minecraft);

        /** Mod ids the loader supplies itself, so no jar needs to provide them. */
        Set<String> builtIn();

        /** Words in a server log that mean the start failed. */
        List<String> failed();

        /** Patterns whose first group is a mod id the loader blames in its log. */
        List<Pattern> modNamed();

        /** Words in a failure that mean a mod reached client-only game code on a server. */
        List<String> clientOnly();
    }

    static Map<String, Object> data(Path harness) throws IOException {
        return Json.read(harness.resolve("knowledge/pack-loaders.json"));
    }

    /** Every loader in pack-loaders.json, by id. */
    static Map<String, Loader> all(Path harness) throws IOException {
        Map<String, Object> data = data(harness);
        Map<String, Loader> loaders = new LinkedHashMap<>();
        for (var entry : Json.object(data.get("loaders")).entrySet()) {
            var spec = Json.object(entry.getValue());
            String style = Json.text(spec, "style", "");
            Loader loader = switch (style) {
                case "args-file" -> new ArgsFile(entry.getKey(), spec, Json.object(data.get("common")));
                case "launch-jar" -> new LaunchJar(entry.getKey(), spec, Json.object(data.get("common")));
                default -> throw new IllegalStateException(
                    "pack-loaders.json: " + entry.getKey() + " has style \"" + style + "\"; known: args-file, launch-jar"
                );
            };
            loaders.put(entry.getKey(), loader);
        }
        return loaders;
    }

    /** The loader a pack names (forge, net.neoforged, fabric-loader ...), or null. */
    static Loader find(Path harness, String name) throws IOException {
        String wanted = name.toLowerCase(Locale.ROOT).strip();
        for (Loader loader : all(harness).values()) {
            var spec = ((Style) loader).spec;
            if (loader.id().equals(wanted) || Json.array(spec.get("aliases")).contains(wanted)) return loader;
        }
        return null;
    }

    // ------------------------------------------------------------------ the styles
    abstract static class Style implements Loader {

        final String id;
        final Map<String, Object> spec, common;

        Style(String id, Map<String, Object> spec, Map<String, Object> common) {
            this.id = id;
            this.spec = spec;
            this.common = common;
        }

        public String id() {
            return id;
        }

        /** A setting, from the entry for this game version when the loader has one (byMinecraft), else the loader's own. */
        String setting(String minecraft, String key) {
            var special = Json.object(Json.object(spec.getOrDefault("byMinecraft", Map.of())).getOrDefault(minecraft, Map.of()));
            return Json.text(special.containsKey(key) ? special : spec, key, "");
        }

        String fill(String template, String minecraft, String version, Path dir) {
            return template
                .replace("{mc}", minecraft)
                .replace("{version}", version)
                .replace("{dir}", dir == null ? "" : dir.toString())
                .replace("{neoforge}", neoforgeLine(minecraft));
        }

        public List<String> metadata(String minecraft) {
            List<String> files = new ArrayList<>();
            for (Object m : Json.array(spec.get("metadata"))) {
                var entry = Json.object(m);
                String from = Json.text(entry, "from", ""), below = Json.text(entry, "below", "");
                if (!from.isEmpty() && Versions.compare(minecraft, from) < 0) continue;
                if (!below.isEmpty() && Versions.compare(minecraft, below) >= 0) continue;
                files.add(Json.text(entry, "file", ""));
            }
            return files;
        }

        public Set<String> builtIn() {
            Set<String> ids = new TreeSet<>();
            for (Object id : Json.array(common.get("builtIn"))) ids.add(String.valueOf(id));
            for (Object id : Json.array(spec.get("builtIn"))) ids.add(String.valueOf(id));
            return ids;
        }

        public List<String> failed() {
            List<String> words = new ArrayList<>();
            for (Object w : Json.array(common.get("failed"))) words.add(String.valueOf(w));
            for (Object w : Json.array(spec.get("failed"))) words.add(String.valueOf(w));
            return words;
        }

        public List<Pattern> modNamed() {
            List<Pattern> patterns = new ArrayList<>();
            for (Object p : Json.array(spec.get("modNamed"))) patterns.add(Pattern.compile(String.valueOf(p)));
            return patterns;
        }

        public List<String> clientOnly() {
            List<String> words = new ArrayList<>();
            for (Object w : Json.array(spec.get("clientOnly"))) words.add(String.valueOf(w));
            return words;
        }

        /** Installs once: the folder is complete only when the marker is written. */
        Path installed(Context context, String minecraft, String version) throws IOException {
            if (!(minecraft + version).matches("[0-9A-Za-z.+\\-]+")) throw new IllegalArgumentException(
                "Bad loader version " + id + " " + minecraft + "-" + version
            );
            return context.cache().resolve("loaders").resolve(id + "-" + minecraft + "-" + version);
        }

        void runInstaller(Context context, Path installer, List<String> arguments, Path dir, String version)
            throws Exception {
            List<String> command = new ArrayList<>(List.of(context.java().java().toString(), "-jar", installer.toString()));
            command.addAll(arguments);
            var result = Run.command(command, dir, Map.of(), context.report().log(id + "-install"), 900);
            context.report().stage(id + "-install", result, Map.of("version", version));
            if (result.exit() != 0) throw new IOException(id + "'s installer failed; see " + result.log());
        }

        /**
         * Forge's own names (SRG: methods named m_12345_, before 1.17 func_12345_a) when the game its install made has them,
         * else the Mojang names newer Forge and every NeoForge since 1.20.2 run with. Read from the installed game itself: NeoForge calls its
         * Mojang-named jar -srg.jar too, so the file name proves nothing.
         */
        Names forgeNames(Path install, String minecraft) throws IOException {
            Path server = install.resolve("libraries/net/minecraft/server");
            if (!Files.isDirectory(server)) return new Names("mojang", null);
            Path game;
            try (var walk = Files.walk(server)) {
                game = walk.filter(p -> p.getFileName().toString().endsWith("-srg.jar")).findFirst().orElse(null);
            }
            if (game == null) return new Names("mojang", null);
            try (var zip = new ZipFile(game.toFile())) {
                ZipEntry type = zip.getEntry("net/minecraft/server/MinecraftServer.class");
                if (type == null) return new Names("mojang", null);
                String constants = new String(zip.getInputStream(type).readAllBytes(), StandardCharsets.ISO_8859_1);
                if (!Pattern.compile("m_[0-9]+_|func_[0-9]+_[a-z]").matcher(constants).find()) return new Names("mojang", null);
            }
            // Official to SRG: MCP config's merged file from 1.17, its plain TSRG file before.
            Path config = PackCheck.only(install.resolve("libraries/de/oceanlabs/mcp/mcp_config"), minecraft + "-");
            Path mcp;
            try {
                mcp = PackCheck.file(config, "-mappings-merged.txt");
            } catch (IOException older) {
                mcp = PackCheck.file(config, "-mappings.txt");
            }
            return new Names("srg", mcp);
        }
    }

    /** An installer that writes the launch arguments to a file under libraries/ (Forge, NeoForge). */
    static final class ArgsFile extends Style {

        ArgsFile(String id, Map<String, Object> spec, Map<String, Object> common) {
            super(id, spec, common);
        }

        public String newest(Context context, String minecraft) throws Exception {
            String prefix = fill(setting(minecraft, "versionPrefix"), minecraft, "", null);
            String xml = new String(PackCheck.get(fill(setting(minecraft, "versions"), minecraft, "", null)), StandardCharsets.UTF_8);
            List<String> found = new ArrayList<>();
            Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
            while (m.find()) if (m.group(1).startsWith(prefix)) found.add(m.group(1).substring(prefix.length()));
            if (found.isEmpty()) throw new IOException(id + " publishes no server for Minecraft " + minecraft + ".");
            List<String> stable = found.stream().filter(v -> !v.contains("beta") && !v.contains("alpha")).toList();
            return (stable.isEmpty() ? found : stable).stream().max(Versions::compare).get();
        }

        public Path install(Context context, String minecraft, String version) throws Exception {
            Path dir = installed(context, minecraft, version), done = dir.resolve("modaccel-installed");
            if (Files.isRegularFile(done)) return dir;
            Files.createDirectories(dir);
            Path installer = dir.resolve("installer.jar");
            PackCheck.download(fill(setting(minecraft, "installer"), minecraft, version, null), installer, null);
            List<String> arguments = new ArrayList<>();
            for (Object a : Json.array(spec.get("install"))) arguments.add(fill(String.valueOf(a), minecraft, version, dir));
            runInstaller(context, installer, arguments, dir, version);
            Files.writeString(done, minecraft + "-" + version + "\n");
            return dir;
        }

        /** The installer's own argument file, with its library paths made absolute so the server runs in the instance. */
        public List<String> launch(Path install, Path instance, String minecraft, String version) throws IOException {
            String name = Runtimes.WINDOWS ? "win_args.txt" : "unix_args.txt";
            Path source;
            try (var walk = Files.walk(install.resolve("libraries"))) {
                source = walk
                    .filter(p -> p.getFileName().toString().equals(name) && p.toString().contains(version))
                    .findFirst()
                    .orElse(null);
            }
            if (source == null) {
                // Before 1.17 the installer wrote a runnable jar instead of an argument file.
                try (var list = Files.list(install)) {
                    Path jar = list
                        .filter(p -> p.getFileName().toString().matches(".*" + Pattern.quote(version) + ".*\\.jar"))
                        .filter(p -> !p.getFileName().toString().equals("installer.jar"))
                        .findFirst()
                        .orElseThrow(() -> new IOException("No " + name + " or server jar in " + install));
                    return List.of("-jar", jar.toString());
                }
            }
            String libraries = install.resolve("libraries").toString().replace('\\', '/');
            String text = Files.readString(source)
                .replace("libraries/", libraries + "/")
                .replace("-DlibraryDirectory=libraries", "-DlibraryDirectory=" + libraries);
            Path target = instance.resolve("modaccel-args.txt");
            Files.writeString(target, text);
            return List.of("@" + target);
        }

        public Names names(Context context, Path install, String minecraft) throws IOException {
            return forgeNames(install, minecraft);
        }
    }

    /** An installer that writes a launch jar beside the vanilla server (Fabric, Quilt). */
    static final class LaunchJar extends Style {

        LaunchJar(String id, Map<String, Object> spec, Map<String, Object> common) {
            super(id, spec, common);
        }

        public String newest(Context context, String minecraft) throws Exception {
            List<String> stable = new ArrayList<>(), any = new ArrayList<>();
            String url = fill(Json.text(spec, "versions", ""), minecraft, "", null);
            for (Object row : Json.array(Json.parse(new String(PackCheck.get(url), StandardCharsets.UTF_8)))) {
                var loader = Json.object(Json.object(row).get("loader"));
                String version = Json.text(loader, "version", "");
                any.add(version);
                boolean marked = loader.containsKey("stable");
                if (marked ? Boolean.TRUE.equals(loader.get("stable")) : !version.contains("beta")) stable.add(version);
            }
            if (any.isEmpty()) throw new IOException(id + " publishes no server for Minecraft " + minecraft + ".");
            return (stable.isEmpty() ? any : stable).stream().max(Versions::compare).get();
        }

        public Path install(Context context, String minecraft, String version) throws Exception {
            Path dir = installed(context, minecraft, version), done = dir.resolve("modaccel-installed");
            if (Files.isRegularFile(done)) return dir;
            Files.createDirectories(dir);
            String installerUrl = "";
            String installerSha1 = null;
            String newest = "";
            for (Object row : Json.array(Json.parse(new String(PackCheck.get(Json.text(spec, "installers", "")), StandardCharsets.UTF_8)))) {
                var installer = Json.object(row);
                String v = Json.text(installer, "version", "");
                if (Boolean.FALSE.equals(installer.get("stable")) || v.contains("beta")) continue;
                if (!newest.isEmpty() && Versions.compare(v, newest) <= 0) continue;
                newest = v;
                installerUrl = Json.text(installer, "url", "");
                var hashes = Json.object(installer.getOrDefault("hashes", Map.of()));
                installerSha1 = hashes.containsKey("sha1") ? Json.text(hashes, "sha1", "") : null;
            }
            if (installerUrl.isEmpty()) throw new IOException(id + " lists no installer");
            Path installer = dir.resolve("installer.jar");
            PackCheck.download(installerUrl, installer, installerSha1);
            List<String> arguments = new ArrayList<>();
            for (Object a : Json.array(spec.get("install"))) arguments.add(fill(String.valueOf(a), minecraft, version, dir));
            runInstaller(context, installer, arguments, dir, version);
            if (!Files.isRegularFile(dir.resolve(Json.text(spec, "launchJar", "")))) throw new IOException(
                id + "'s installer wrote no " + Json.text(spec, "launchJar", "") + " in " + dir
            );
            Files.writeString(done, minecraft + "-" + version + "\n");
            return dir;
        }

        /** The launch jar, told where the vanilla server is through its properties file in the instance. */
        public List<String> launch(Path install, Path instance, String minecraft, String version) throws IOException {
            String server = install.resolve("server.jar").toAbsolutePath().toString().replace('\\', '/');
            Files.writeString(instance.resolve(Json.text(spec, "launcherProperties", "")), "serverJar=" + server + "\n");
            return List.of("-jar", install.resolve(Json.text(spec, "launchJar", "")).toString());
        }

        /** Fabric's intermediary names, for any game version that is still shipped with obfuscated names. */
        public Names names(Context context, Path install, String minecraft) throws Exception {
            if (Vanilla.mappingsUrl(context, minecraft).isEmpty()) return new Names("mojang", null);
            Path dir = context.cache().resolve("minecraft").resolve(minecraft);
            Path tiny = dir.resolve("intermediary.tiny");
            if (!Files.isRegularFile(tiny)) {
                Path jar = dir.resolve("intermediary-v2.jar");
                PackCheck.download(fill(Json.text(spec, "intermediary", ""), minecraft, "", null), jar, null);
                try (var zip = new ZipFile(jar.toFile())) {
                    ZipEntry entry = zip.getEntry("mappings/mappings.tiny");
                    if (entry == null) throw new IOException(jar + " has no mappings/mappings.tiny");
                    Path temporary = dir.resolve("intermediary.tiny.tmp");
                    try (var in = zip.getInputStream(entry)) {
                        Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.move(temporary, tiny, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return new Names("intermediary", tiny);
        }
    }

    /** NeoForge numbers its builds from the game version: 1.21.1 is 21.1, 1.21 is 21.0, 26.1 is 26.1. */
    static String neoforgeLine(String minecraft) {
        String[] parts = minecraft.split("\\.");
        if (parts.length >= 2 && parts[0].equals("1")) return parts[1] + "." + (parts.length > 2 ? parts[2] : "0");
        return minecraft;
    }

    // ------------------------------------------------------------------ the Java build a loader needs
    /**
     * The Java a loader's server runs on: the game's Java as installed, unless a rule in pack-loaders.json ("javaBuilds")
     * applies. A rule names a JDK member the installed loader calls (read from its jars' bytecode) and an exact release
     * that still has it; it applies when this Java's own class lacks the member. Old ModLauncher (Forge 1.16.5 36.2.20)
     * calls ManifestEntryVerifier(Manifest), which Java 8 replaced in 8u321, and stops before any mod loads.
     */
    static Runtimes.Jdk javaFor(Context context, Path install) throws Exception {
        for (Object r : Json.array(data(context.harness()).getOrDefault("javaBuilds", List.of()))) {
            var rule = Json.object(r);
            if (((Number) rule.get("java")).intValue() != context.java().version()) continue;
            String call = Json.text(rule, "call", "");
            if (caller(install.resolve("libraries").resolve(Json.text(rule, "libraries", "")), call) == null) continue;
            if (jdkDeclares(context.java().home(), call)) continue;
            return new Runtimes.Jdk(JdkDownloads.installRelease(context.java().version(), Json.text(rule, "release", "")), context.java().version());
        }
        return context.java();
    }

    /** The first class in the jars under folder that calls the method "owner.name:descriptor"; null when none does. */
    static String caller(Path folder, String call) throws IOException {
        if (!Files.isDirectory(folder)) return null;
        String owner = call.substring(0, call.lastIndexOf('.', call.indexOf(':')));
        byte[] needle = owner.getBytes(StandardCharsets.UTF_8);
        List<Path> jars;
        try (var walk = Files.walk(folder)) {
            jars = walk.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
        }
        for (Path jar : jars) try (var zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (!entry.getName().endsWith(".class")) continue;
                byte[] bytes = zip.getInputStream(entry).readAllBytes();
                if (indexOf(bytes, needle) < 0) continue;
                if (ClassRefs.read(bytes).methodRefs.contains(call)) return jar.getFileName() + "!" + entry.getName();
            }
        }
        return null;
    }

    /** Whether a JDK 8 home's rt.jar declares the member "owner.name:descriptor"; true when it cannot tell (no rt.jar). */
    static boolean jdkDeclares(Path home, String call) throws IOException {
        int dot = call.lastIndexOf('.', call.indexOf(':'));
        Path rt = Files.isRegularFile(home.resolve("jre/lib/rt.jar")) ? home.resolve("jre/lib/rt.jar") : home.resolve("lib/rt.jar");
        if (!Files.isRegularFile(rt)) return true;
        try (var zip = new ZipFile(rt.toFile())) {
            ZipEntry entry = zip.getEntry(call.substring(0, dot) + ".class");
            return entry != null && ClassRefs.read(zip.getInputStream(entry).readAllBytes()).declared.contains(call.substring(dot + 1));
        }
    }

    static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ which game classes a server has
    /**
     * The game classes a loader's server has, in the names its code runs with. all: every game class the names know
     * (client and server), or null for Mojang's names, where client classes are the ones under net/minecraft/client/
     * and com/mojang/blaze3d/. A class the names know and the server lacks is client-only.
     */
    record GameClasses(Set<String> server, Set<String> all) {
        boolean clientOnly(String type) {
            // Fabric API's item-groups-v0 (to 1.19.2) keeps its builder under api/client, but it is common code that servers run:
            // Drink Beer, MC Dungeons Weapons and Automobility build their item groups with it in their main entrypoints.
            if (type.startsWith("net/fabricmc/fabric/api/client/itemgroup/")) return false;
            if (type.startsWith("net/fabricmc/fabric/api/client/")) return true;
            if (server.contains(type)) return false;
            if (all != null) return all.contains(type);
            return type.startsWith("net/minecraft/client/") || type.startsWith("com/mojang/blaze3d/");
        }
    }

    static GameClasses gameClasses(Context context, Loader loader, Path install, String minecraft) throws Exception {
        Vanilla game = Vanilla.get(context, minecraft);
        Names names = loader.names(context, install, minecraft);
        if (names.kind().equals("mojang") || names.mapping() == null) return new GameClasses(classes(game.mojang), null);
        Map<String, String> map = classMap(names.mapping());
        Set<String> server = new HashSet<>();
        for (String type : classes(game.official)) if (map.containsKey(type)) server.add(map.get(type));
        return new GameClasses(server, new HashSet<>(map.values()));
    }

    /** The classes in a jar, as internal names. */
    static Set<String> classes(Path jar) throws IOException {
        Set<String> out = new HashSet<>();
        try (var zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                if (name.endsWith(".class") && !name.startsWith("META-INF/")) out.add(name.substring(0, name.length() - 6));
            }
        }
        return out;
    }

    /** A mapping file's classes, official name to the loader's: Tiny v1 and v2 (Fabric), TSRG and TSRG2 (Forge). */
    static Map<String, String> classMap(Path mapping) throws IOException {
        Map<String, String> map = new HashMap<>();
        boolean tiny = false;
        for (String line : Files.readAllLines(mapping, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("\t") || line.startsWith(" ") || line.startsWith("#")) continue;
            String[] t = line.split("\\s+");
            if (t[0].equals("tiny") || t[0].equals("v1")) tiny = true;
            else if (tiny && (t[0].equals("c") || t[0].equals("CLASS")) && t.length >= 3) map.put(t[1], t[2]);
            else if (!tiny && !t[0].equals("tsrg2") && t.length >= 2 && !t[0].endsWith("/")) map.put(t[0], t[1]);
        }
        return map;
    }

    // ------------------------------------------------------------------ the game every loader builds on
    /** Mojang's server for a game version: its official jar, bundled libraries and names, from Mojang's version manifest. */
    static final class Vanilla {

        final Path official, mojang, mappings;
        final List<Path> libraries;
        final int java;

        private Vanilla(Path official, Path mojang, Path mappings, List<Path> libraries, int java) {
            this.official = official;
            this.mojang = mojang;
            this.mappings = mappings;
            this.libraries = libraries;
            this.java = java;
        }

        static Map<String, Object> version(Path cache, Path harness, String minecraft) throws Exception {
            Path dir = cache.resolve("minecraft").resolve(minecraft), file = dir.resolve("version.json");
            if (Files.isRegularFile(file)) return Json.read(file);
            String manifest = Json.text(Json.object(data(harness).get("minecraft")), "manifest", "");
            for (Object v : Json.array(Json.parse(new String(PackCheck.get(manifest), StandardCharsets.UTF_8)) instanceof Map<?, ?> m ? m.get("versions") : List.of())) {
                var row = Json.object(v);
                if (!minecraft.equals(row.get("id"))) continue;
                Files.createDirectories(dir);
                PackCheck.download(Json.text(row, "url", ""), file, Json.text(row, "sha1", ""));
                return Json.read(file);
            }
            throw new IllegalArgumentException("Mojang lists no Minecraft " + minecraft + ".");
        }

        /** The Java the game says it needs (version.json's javaVersion). */
        static int java(Path cache, Path harness, String minecraft) throws Exception {
            var java = Json.object(version(cache, harness, minecraft).getOrDefault("javaVersion", Map.of()));
            return java.get("majorVersion") instanceof Number n ? n.intValue() : 17;
        }

        static String mappingsUrl(Context context, String minecraft) throws Exception {
            var downloads = Json.object(version(context.cache(), context.harness(), minecraft).get("downloads"));
            return Json.text(Json.object(downloads.getOrDefault("server_mappings", Map.of())), "url", "");
        }

        /** Downloaded and unbundled once per version; renamed to Mojang's names when it ships obfuscated. */
        static Vanilla get(Context context, String minecraft) throws Exception {
            Path dir = context.cache().resolve("minecraft").resolve(minecraft);
            var version = version(context.cache(), context.harness(), minecraft);
            var downloads = Json.object(version.get("downloads"));
            var server = Json.object(downloads.get("server"));
            Path bundle = dir.resolve("server.jar");
            if (!Files.isRegularFile(bundle)) PackCheck.download(Json.text(server, "url", ""), bundle, Json.text(server, "sha1", ""));
            Path official = dir.resolve("server-official.jar"), libraries = dir.resolve("libraries");
            if (!Files.isRegularFile(official)) unbundle(bundle, official, libraries);
            List<Path> libs = new ArrayList<>();
            if (Files.isDirectory(libraries)) try (var walk = Files.walk(libraries)) {
                walk.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(libs::add);
            }
            var names = Json.object(downloads.getOrDefault("server_mappings", Map.of()));
            int java = java(context.cache(), context.harness(), minecraft);
            if (names.isEmpty()) return new Vanilla(official, official, null, libs, java);
            Path mappings = dir.resolve("server.txt");
            if (!Files.isRegularFile(mappings)) PackCheck.download(Json.text(names, "url", ""), mappings, Json.text(names, "sha1", ""));
            Path mojang = dir.resolve("server-mojang.jar");
            if (!Files.isRegularFile(mojang)) {
                Path temporary = dir.resolve("server-mojang.jar.tmp");
                rename(context, "mojang-names", official, temporary, mappings, true, List.of());
                Files.move(temporary, mojang, StandardCopyOption.REPLACE_EXISTING);
            }
            return new Vanilla(official, mojang, mappings, libs, java);
        }

        /** Since 1.18 the server jar is a bundle: the game in META-INF/versions, its libraries in META-INF/libraries. */
        static void unbundle(Path bundle, Path official, Path libraries) throws IOException {
            try (var zip = new ZipFile(bundle.toFile())) {
                ZipEntry list = zip.getEntry("META-INF/versions.list");
                if (list == null) {
                    Files.copy(bundle, official, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
                PackCheck.deleteTree(libraries);
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.endsWith(".jar")) continue;
                    if (name.startsWith("META-INF/libraries/")) {
                        Path out = libraries.resolve(name.substring("META-INF/libraries/".length())).normalize();
                        if (!out.startsWith(libraries)) throw new IOException("Unsafe path in " + bundle + ": " + name);
                        Files.createDirectories(out.getParent());
                        try (var in = zip.getInputStream(entry)) {
                            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
                String game = new String(zip.getInputStream(list).readAllBytes(), StandardCharsets.UTF_8).strip().split("\t")[2];
                Path temporary = official.resolveSibling(official.getFileName() + ".tmp");
                try (var in = zip.getInputStream(zip.getEntry("META-INF/versions/" + game))) {
                    Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(temporary, official, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Forge's renaming tool, once per machine, checked against the SHA-1 in pack-loaders.json. */
    static Path renamer(Context context) throws Exception {
        var spec = Json.object(Json.object(data(context.harness()).get("tools")).get("renamer"));
        String url = Json.text(spec, "url", "");
        Path jar = context.cache().resolve("tools").resolve(url.substring(url.lastIndexOf('/') + 1));
        if (!Files.isRegularFile(jar)) PackCheck.download(url, jar, Json.text(spec, "sha1", ""));
        return jar;
    }

    static void rename(Context context, String step, Path input, Path output, Path map, boolean reverse, List<Path> libs)
        throws Exception {
        List<String> command = new ArrayList<>(
            List.of(
                context.java().java().toString(),
                "-jar",
                renamer(context).toString(),
                "--input",
                input.toString(),
                "--output",
                output.toString(),
                "--map",
                map.toString()
            )
        );
        if (reverse) command.add("--reverse");
        for (Path lib : libs) {
            command.add("--lib");
            command.add(lib.toString());
        }
        var result = Run.command(command, output.getParent(), Map.of(), context.report().log(step), 900);
        context.report().stage(step, result, Map.of());
        if (result.exit() != 0) throw new IOException(step + " failed:\n" + Run.failure(result.log()));
    }

    // ------------------------------------------------------------------ the probe
    /** The probe's sources: one loader-neutral mod, its Fabric entrypoint, and the stand-ins it compiles against. */
    static final List<String> PROBE = List.of(
        "tools/packcheck/ModaccelPackProbe.java",
        "tools/packcheck/ModaccelPackProbeFabric.java",
        "tools/packcheck/stubs/net/minecraftforge/fml/common/Mod.java",
        "tools/packcheck/stubs/net/neoforged/fml/common/Mod.java",
        "tools/packcheck/stubs/net/fabricmc/api/DedicatedServerModInitializer.java",
        "tools/packcheck/mods.toml",
        "tools/packcheck/neoforge.mods.toml",
        "tools/packcheck/fabric.mod.json",
        "tools/packcheck/pack.mcmeta"
    );

    /**
     * The probe for one loader on one game version: compiled in Mojang names against the vanilla server, given the
     * metadata file that loader reads, then renamed to the loader's run-time names in two passes (Mojang to official
     * with Mojang's class tree, official to the loader's names with the official tree), so inherited members resolve.
     * Cached per probe source, loader and game version.
     */
    static Path probe(Context context, Loader loader, String minecraft, Path install) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        for (String source : PROBE) hash.update(Files.readAllBytes(context.harness().resolve(source)));
        Path layer = gameLayer(context.harness(), minecraft);
        hash.update(Files.readAllBytes(layer));
        // The behaviour fingerprint (ModaccelFingerprint) goes in the same jar when it has a layer for this version.
        List<String> fingerprint = new ArrayList<>();
        try {
            fingerprint.add(context.harness().resolve("tools/packcheck/ModaccelFingerprint.java").toString());
            fingerprint.add(Fingerprint.fingerprintLayer(context.harness(), minecraft).toString());
            for (String source : fingerprint) hash.update(Files.readAllBytes(Path.of(source)));
        } catch (IOException noLayer) {
            fingerprint.clear();
        }
        Names names = loader.names(context, install, minecraft);
        hash.update((loader.id() + " " + minecraft + " " + names.kind()).getBytes(StandardCharsets.UTF_8));
        Path dir = context.cache().resolve("probes").resolve(HexFormat.of().formatHex(hash.digest()).substring(0, 16));
        Path jar = dir.resolve("probe.jar");
        if (Files.isRegularFile(jar)) return jar;
        Vanilla game = Vanilla.get(context, minecraft);
        PackCheck.deleteTree(dir);
        Path stubs = dir.resolve("stubs"), classes = dir.resolve("classes");
        Files.createDirectories(stubs);
        Files.createDirectories(classes);
        String release = String.valueOf(game.java);
        // Java 8's javac (games before 1.17) has no --release; it targets its own release with -source and -target.
        List<String> javaTarget = release.equals("8") ? List.of("-source", "8", "-target", "8") : List.of("--release", release);
        List<String> stubSources = new ArrayList<>();
        for (String source : PROBE) if (source.contains("/stubs/")) stubSources.add(context.harness().resolve(source).toString());
        List<String> compileStubs = new ArrayList<>(List.of(javac(context), "-nowarn", "-proc:none"));
        compileStubs.addAll(javaTarget);
        compileStubs.addAll(List.of("-d", stubs.toString()));
        compileStubs.addAll(stubSources);
        step(context, "probe-stubs", compileStubs, dir);
        List<String> classpath = new ArrayList<>(List.of(game.mojang.toString(), stubs.toString()));
        for (Path lib : game.libraries) classpath.add(lib.toString());
        List<String> compileProbe = new ArrayList<>(List.of(javac(context), "-nowarn", "-proc:none"));
        compileProbe.addAll(javaTarget);
        compileProbe.addAll(
            List.of(
                "-encoding",
                "UTF-8",
                "-cp",
                String.join(File.pathSeparator, classpath),
                "-d",
                classes.toString(),
                context.harness().resolve("tools/packcheck/ModaccelPackProbe.java").toString(),
                context.harness().resolve("tools/packcheck/ModaccelPackProbeFabric.java").toString(),
                layer.toString()
            )
        );
        List<String> withFingerprint = new ArrayList<>(compileProbe);
        withFingerprint.addAll(fingerprint);
        if (fingerprint.isEmpty()) step(context, "probe-compile", compileProbe, dir);
        else {
            var result = Run.command(withFingerprint, dir, Map.of(), context.report().log("probe-compile"), 600);
            context.report().stage("probe-compile", result, Map.of("fingerprint", result.exit() == 0));
            // A fingerprint layer that does not compile for this exact version leaves pack-check's own probe as it was.
            if (result.exit() != 0) {
                PackCheck.deleteTree(classes);
                Files.createDirectories(classes);
                step(context, "probe-compile-without-fingerprint", compileProbe, dir);
            }
        }
        // Only the metadata this loader reads goes in, so no loader sees a file meant for another and warns about it.
        // Quilt reads fabric.mod.json too, so a loader is served by the first of its files the probe has.
        int given = 0;
        for (String file : loader.metadata(minecraft)) {
            Path source = context.harness().resolve("tools/packcheck").resolve(Path.of(file).getFileName().toString());
            if (!Files.isRegularFile(source)) continue;
            Path target = classes.resolve(file);
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            given++;
        }
        if (given == 0) throw new IOException("The probe has none of " + loader.metadata(minecraft) + " for " + loader.id());
        // Without pack.mcmeta Forge shows a loading warning for the probe itself, so the check would change what the player sees.
        Files.copy(context.harness().resolve("tools/packcheck/pack.mcmeta"), classes.resolve("pack.mcmeta"));
        Path named = dir.resolve("probe-mojang.jar");
        jar(classes, named);
        Path temporary = dir.resolve("probe.jar.tmp");
        if (names.kind().equals("mojang") || game.mappings == null) Files.copy(named, temporary, StandardCopyOption.REPLACE_EXISTING);
        else {
            Path official = dir.resolve("probe-official.jar");
            rename(context, "probe-official-names", named, official, game.mappings, false, List.of(game.mojang));
            rename(context, "probe-" + names.kind() + "-names", official, temporary, names.mapping(), false, List.of(game.official));
        }
        Files.move(temporary, jar, StandardCopyOption.REPLACE_EXISTING);
        return jar;
    }

    /**
     * The probe's version layer for one game version: tools/packcheck/game/VERSION/ModaccelPackGame.java with the newest
     * VERSION at or below the game's. Each layer has the same methods, written in that version's API.
     */
    static Path gameLayer(Path harness, String minecraft) throws IOException {
        Path root = harness.resolve("tools/packcheck/game");
        String best = null;
        try (var list = Files.list(root)) {
            for (Path dir : list.toList()) {
                String version = dir.getFileName().toString();
                if (Versions.compare(version, minecraft) <= 0 && (best == null || Versions.compare(version, best) > 0)) best = version;
            }
        }
        if (best == null) throw new IOException(
            "pack-check's probe has no game layer for Minecraft " + minecraft + ": tools/packcheck/game has none at or below it."
        );
        return root.resolve(best).resolve("ModaccelPackGame.java");
    }

    static String javac(Context context) {
        return context.java().home().resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString();
    }

    static void jar(Path classes, Path target) throws IOException {
        try (var out = new ZipOutputStream(Files.newOutputStream(target)); var walk = Files.walk(classes)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new ZipEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
    }

    static void step(Context context, String name, List<String> command, Path dir) throws Exception {
        var result = Run.command(command, dir, Map.of(), context.report().log(name), 600);
        context.report().stage(name, result, Map.of());
        if (result.exit() != 0) throw new IOException(name + " failed:\n" + Run.failure(result.log()));
    }
}
