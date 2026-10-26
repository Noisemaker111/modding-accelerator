import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * Prototype: build a Mojang-named game jar on this machine, from Mojang's own downloads, in one parallel pass.
 *
 *   java tools/GamePrep.java named VERSION [--cache DIR]      client + server classes in official names
 *   java tools/GamePrep.java compare A.jar B.jar               class and member signatures, A against B
 *   java tools/GamePrep.java neoforge-install VERSION [AT...]   NeoForge compile jar via NeoForge's installer, plus ATs
 *
 * Nothing is redistributed: the jar is made from Mojang's client and server jars and their published
 * mappings, verified by the SHA-1s in Mojang's version file, and cached once per version per machine
 * (default ~/.modaccel/game/VERSION). Only class files go in; compile needs nothing else.
 */
public final class GamePrep {
    static Path cache = System.getenv("MODACCEL_GAME_CACHE") != null ? Path.of(System.getenv("MODACCEL_GAME_CACHE")) : Path.of(System.getProperty("user.home"), ".modaccel", "game");
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    public static void main(String[] args) throws Exception {
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) { if (args[i].equals("--cache")) cache = Path.of(args[++i]); else rest.add(args[i]); }
        if (rest.isEmpty()) { System.err.println("usage: named VERSION | compare A.jar B.jar"); System.exit(2); }
        switch (rest.get(0)) {
            case "named" -> { long t = System.nanoTime(); Path jar = named(rest.get(1)); System.out.printf("named jar %s (%.1f s)%n", jar, (System.nanoTime() - t) / 1e9); }
            case "fabric" -> { long t = System.nanoTime(); fabric(rest.get(1), Path.of(rest.get(2)), Path.of(rest.get(3)), rest.size() > 4 ? Path.of(rest.get(4)) : null); System.out.printf("fabric compile jar %s (%.1f s)%n", rest.get(3), (System.nanoTime() - t) / 1e9); }
            case "neoforge" -> { long t = System.nanoTime(); neoforge(Path.of(rest.get(1)), Path.of(rest.get(2)), Path.of(rest.get(3)), rest.subList(4, rest.size()).stream().map(Path::of).toList()); System.out.printf("neoforge compile jar %s (%.1f s)%n", rest.get(3), (System.nanoTime() - t) / 1e9); }
            case "neoforge-install" -> { long t = System.nanoTime(); Path jar = neoforgeInstall(rest.get(1), rest.subList(2, rest.size()).stream().map(Path::of).toList()); System.out.printf("neoforge compile jar %s (%.1f s)%n", jar, (System.nanoTime() - t) / 1e9); }
            case "compare" -> System.exit(compare(Path.of(rest.get(1)), Path.of(rest.get(2)), rest.size() > 3 ? Integer.parseInt(rest.get(3)) : 20));
            default -> { System.err.println("unknown " + rest.get(0)); System.exit(2); }
        }
    }

    // ---------------------------------------------------------------- downloads

    static final Map<String, Long> timings = new ConcurrentHashMap<>();
    static void time(String what, long start) { timings.merge(what, System.nanoTime() - start, Long::sum); }

    static byte[] get(String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
        return r.body();
    }
    static String sha1(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)); }
    /** A file verified by Mojang's SHA-1, fetched once. */
    static Path fetch(String url, String sha1, Path file) throws Exception {
        if (Files.isRegularFile(file) && (sha1 == null || sha1(Files.readAllBytes(file)).equals(sha1))) return file;
        byte[] bytes = get(url);
        if (sha1 != null && !sha1(bytes).equals(sha1)) throw new IOException("SHA-1 mismatch for " + url);
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), "dl-", ".tmp"); Files.write(tmp, bytes);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); return file;
    }
    static String str(String json, String key, int from) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find(from) ? m.group(1) : null;
    }
    record Download(String url, String sha1) {}
    static Download download(String versionJson, String key) {
        int at = versionJson.indexOf("\"" + key + "\""); if (at < 0) return null;
        return new Download(str(versionJson, "url", at), str(versionJson, "sha1", at));
    }

    // ---------------------------------------------------------------- the named jar

    static Path named(String version) throws Exception {
        Path dir = cache.resolve(version), out = dir.resolve("minecraft-" + version + "-named.jar");
        if (Files.isRegularFile(out)) return out;
        long t = System.nanoTime();
        String manifest = new String(get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"), StandardCharsets.UTF_8);
        int at = manifest.indexOf("\"id\": \"" + version + "\""); if (at < 0) at = manifest.indexOf("\"id\":\"" + version + "\"");
        if (at < 0) throw new IOException("Minecraft " + version + " is not in Mojang's version list");
        String versionJson = new String(Files.readAllBytes(fetch(str(manifest, "url", at), str(manifest, "sha1", at), dir.resolve(version + ".json"))), StandardCharsets.UTF_8);
        time("version json", t);
        // Four downloads in parallel: both jars and both mapping files.
        t = System.nanoTime();
        Map<String, Download> wanted = new LinkedHashMap<>();
        for (String key : List.of("client", "server", "client_mappings", "server_mappings")) { Download d = download(versionJson, key); if (d != null) wanted.put(key, d); }
        Map<String, Future<Path>> files = new LinkedHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (var e : wanted.entrySet()) files.put(e.getKey(), pool.submit(() -> fetch(e.getValue().url(), e.getValue().sha1(), dir.resolve(e.getKey() + (e.getKey().endsWith("mappings") ? ".txt" : ".jar")))));
        Map<String, Path> got = new HashMap<>();
        for (var e : files.entrySet()) got.put(e.getKey(), e.getValue().get());
        pool.shutdown(); time("download", t);

        t = System.nanoTime();
        Map<String, byte[]> classes = new ConcurrentHashMap<>();
        readClasses(got.get("client"), classes, false);
        if (got.containsKey("server")) readClasses(got.get("server"), classes, true);
        time("read jars", t);
        t = System.nanoTime();
        Mappings maps = new Mappings();
        if (got.containsKey("client_mappings")) maps.parse(Files.readString(got.get("client_mappings")));
        if (got.containsKey("server_mappings")) maps.parse(Files.readString(got.get("server_mappings")));
        Hierarchy hierarchy = new Hierarchy(classes);
        maps.index();
        time("mappings", t);

        t = System.nanoTime();
        Remapper remapper = new Remapper(maps, hierarchy);
        Map<String, byte[]> named = new ConcurrentHashMap<>();
        classes.entrySet().parallelStream().forEach(e -> {
            try { named.put(remapper.mapClass(e.getKey()) + ".class", remapper.remap(e.getValue())); }
            catch (Exception error) { throw new RuntimeException("remapping " + e.getKey() + ": " + error, error); }
        });
        time("remap", t);

        t = System.nanoTime();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "named-", ".tmp");
        try (var zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 20))) {
            zip.setMethod(ZipOutputStream.STORED);
            for (String name : new TreeSet<>(named.keySet())) {
                byte[] bytes = named.get(name); CRC32 crc = new CRC32(); crc.update(bytes);
                ZipEntry entry = new ZipEntry(name); entry.setTime(0); entry.setSize(bytes.length); entry.setCompressedSize(bytes.length); entry.setCrc(crc.getValue());
                zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
            }
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        time("write jar", t);
        timings.forEach((k, v) -> System.out.printf("  %-12s %6.2f s%n", k, v / 1e9));
        System.out.printf("  %d classes, %d remapped member references%n", named.size(), remapper.memberHits.get());
        return out;
    }

    // ---------------------------------------------------------------- Fabric: access wideners and injected interfaces

    /** What one class gets from access wideners and interface injection. */
    static final class Widen {
        int classAccess; // 1 accessible, 2 extendable
        final Map<String, Integer> methods = new HashMap<>(), fields = new HashMap<>(); // name+desc -> 1 accessible, 2 extendable, 4 mutable
        final List<String> interfaces = new ArrayList<>(); // internal name, optionally with <generic arguments>
        int[] atClass; // access transformer: {level 0 private .. 3 public, final -1 remove / 0 keep / 1 add}
        final Map<String, int[]> atMethods = new HashMap<>(), atFields = new HashMap<>(); // name+desc / name; "*()" and "*" for all
    }
    /**
     * The game jar a Fabric project compiles against: the named jar plus the transitive access wideners and
     * injected interfaces of every mod on the classpath, plus the project's own access widener.
     */
    static void fabric(String version, Path classpathFile, Path out, Path ownWidener) throws Exception {
        Path base = named(version);
        long t = System.nanoTime();
        Map<String, Widen> rules = new HashMap<>();
        Map<String, String> interToNamed = intermediaryToNamed(version);
        int widened = 0, injected = 0;
        for (String line : Files.readAllLines(classpathFile)) {
            if (line.isBlank() || !line.endsWith(".jar") || !Files.isRegularFile(Path.of(line))) continue;
            try (var zip = new ZipFile(line)) {
                var meta = zip.getEntry("fabric.mod.json"); if (meta == null) continue;
                String json = new String(zip.getInputStream(meta).readAllBytes(), StandardCharsets.UTF_8);
                String aw = str(json, "accessWidener", 0); if (aw == null) aw = str(json, "classTweaker", 0);
                if (aw != null && zip.getEntry(aw) != null) widened += widen(new String(zip.getInputStream(zip.getEntry(aw)).readAllBytes(), StandardCharsets.UTF_8), true, rules, interToNamed);
                int at = json.indexOf("\"loom:injected_interfaces\"");
                if (at >= 0) {
                    int open = json.indexOf('{', at), depth = 0, end = open;
                    do { char c = json.charAt(end); if (c == '{') depth++; else if (c == '}') depth--; end++; } while (depth > 0);
                    Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json.substring(open, end));
                    while (m.find()) {
                        String target = interToNamed.getOrDefault(m.group(1), m.group(1));
                        Matcher each = Pattern.compile("\"([^\"]+)\"").matcher(m.group(2));
                        while (each.find()) { String iface = unescape(each.group(1)); rules.computeIfAbsent(target, k -> new Widen()).interfaces.add(mapGeneric(iface, interToNamed)); injected++; }
                    }
                }
            }
        }
        if (ownWidener != null) widened += widen(Files.readString(ownWidener), false, rules, interToNamed);
        time("read rules", t);
        t = System.nanoTime();
        Map<String, byte[]> classes = new ConcurrentHashMap<>();
        try (var zip = new ZipFile(base.toFile())) {
            zip.stream().parallel().forEach(e -> { try (var in = zip.getInputStream(e)) { classes.put(e.getName(), in.readAllBytes()); } catch (IOException x) { throw new UncheckedIOException(x); } });
        }
        Map<String, Integer> classAccess = new HashMap<>(); rules.forEach((k, v) -> { if (v.classAccess != 0) classAccess.put(k, v.classAccess); });
        Map<String, byte[]> outClasses = new ConcurrentHashMap<>();
        classes.entrySet().parallelStream().forEach(e -> {
            String name = e.getKey().substring(0, e.getKey().length() - 6);
            Widen w = rules.get(name);
            boolean inner = !classAccess.isEmpty();
            outClasses.put(e.getKey(), w == null && !inner ? e.getValue() : new ClassFile(e.getValue(), null).widen(w, classAccess));
        });
        time("apply", t);
        t = System.nanoTime(); writeStored(outClasses, out); time("write jar", t);
        System.out.printf("  %d access widener entries, %d injected interfaces, %d classes touched%n", widened, injected, rules.size());
    }
    // ---------------------------------------------------------------- NeoForge: binary-patched game plus access transformers

    /**
     * The game jar a NeoForge project compiles against: the renamed game jar NeoForge's installer makes, with its
     * binary-patched classes laid over it, and every access transformer (NeoForge's own and the mod's) applied.
     * Access transformers here only widen, as at compile time in NeoForge's own toolchain.
     */
    static void neoforge(Path renamed, Path patched, Path out, List<Path> transformers) throws Exception {
        long t = System.nanoTime();
        Map<String, byte[]> classes = new ConcurrentHashMap<>();
        for (Path jar : List.of(renamed, patched)) try (var zip = new ZipFile(jar.toFile())) {
            zip.stream().parallel().filter(e -> e.getName().endsWith(".class")).forEach(e -> { try (var in = zip.getInputStream(e)) { classes.put(e.getName(), in.readAllBytes()); } catch (IOException x) { throw new UncheckedIOException(x); } });
        }
        time("read jars", t); t = System.nanoTime();
        Map<String, Widen> rules = new HashMap<>(); int entries = 0;
        for (Path file : transformers) {
            String text;
            if (file.toString().endsWith(".jar")) try (var zip = new ZipFile(file.toFile())) { var e = zip.getEntry("META-INF/accesstransformer.cfg"); if (e == null) continue; text = new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8); }
            else text = Files.readString(file);
            for (String line : text.split("\\R")) {
                int hash = line.indexOf('#'); if (hash >= 0) line = line.substring(0, hash);
                String[] p = line.trim().split("\\s+"); if (p.length < 2) continue;
                String mod = p[0]; int finalOp = mod.endsWith("-f") ? -1 : mod.endsWith("+f") ? 1 : 0;
                int level = switch (finalOp == 0 ? mod : mod.substring(0, mod.length() - 2)) { case "public" -> 3; case "protected" -> 2; case "default" -> 1; case "private" -> 0; default -> -1; };
                if (level < 0) continue;
                Widen w = rules.computeIfAbsent(p[1].replace('.', '/'), k -> new Widen());
                int[] rule = {level, finalOp};
                if (p.length == 2) w.atClass = rule;
                else { String m = p[2]; int paren = m.indexOf('('); if (paren >= 0) w.atMethods.put(m, rule); else w.atFields.put(m, rule); }
                entries++;
            }
        }
        Map<String, int[]> classAccess = new HashMap<>(); rules.forEach((k, v) -> { if (v.atClass != null) classAccess.put(k, v.atClass); });
        Map<String, byte[]> outClasses = new ConcurrentHashMap<>();
        classes.entrySet().parallelStream().forEach(e -> {
            Widen w = rules.get(e.getKey().substring(0, e.getKey().length() - 6));
            outClasses.put(e.getKey(), new ClassFile(e.getValue(), null).transform(w, classAccess));
        });
        // An override is never narrower than the method it overrides once ATs and patches are in: javac enforces
        // that when NeoForm recompiles, so do it here too, or a call through the subclass would not compile.
        Map<String, String[]> supers = new HashMap<>(); Map<String, Map<String, Integer>> levels = new ConcurrentHashMap<>();
        outClasses.forEach((k, v) -> supers.put(k.substring(0, k.length() - 6), ClassFile.supers(v)));
        outClasses.entrySet().parallelStream().forEach(e -> levels.put(e.getKey().substring(0, e.getKey().length() - 6), Shape.methodLevels(e.getValue())));
        int inherited = 0;
        for (String name : supers.keySet()) {
            Map<String, Integer> own = levels.get(name); Map<String, int[]> raise = new HashMap<>();
            Deque<String> todo = new ArrayDeque<>(); Set<String> seen = new HashSet<>();
            for (String s : supers.get(name)) if (s != null) todo.add(s);
            while (!todo.isEmpty()) {
                String s = todo.poll(); if (!seen.add(s)) continue;
                Map<String, Integer> up = levels.get(s); if (up == null) continue;
                up.forEach((m, l) -> { Integer mine = own.get(m); if (mine != null && mine < l) raise.merge(m, new int[]{l, 0}, (x, y) -> x[0] >= y[0] ? x : y); });
                for (String u : supers.get(s)) if (u != null) todo.add(u);
            }
            if (raise.isEmpty()) continue;
            Widen w = new Widen(); w.atMethods.putAll(raise); inherited += raise.size();
            outClasses.put(name + ".class", new ClassFile(outClasses.get(name + ".class"), null).transform(w, Map.of()));
        }
        time("apply", t); t = System.nanoTime();
        writeStored(outClasses, out); time("write jar", t);
        timings.forEach((k, v) -> System.out.printf("  %-12s %6.2f s%n", k, v / 1e9));
        System.out.printf("  %d classes, %d access transformer entries, %d widened overrides%n", outClasses.size(), entries, inherited);
    }
    /**
     * NeoForge's compile game jar for one version, made the way a player's install makes it: NeoForge's own
     * installer renames Mojang's client jar with Mojang's mappings and applies NeoForge's binary patches (no
     * decompile, no recompile), then NeoForge's access transformers and the mod's own are applied here.
     * Cached once per NeoForge version per machine; a mod with its own transformers gets its own jar.
     * Prints jar=PATH and universal=PATH (NeoForge's own classes, also needed on the compile classpath).
     */
    static Path neoforgeInstall(String version, List<Path> ownTransformers) throws Exception {
        if (!version.matches("[0-9][0-9A-Za-z.+-]*")) throw new IllegalArgumentException("Bad NeoForge version " + version);
        Path dir = cache.toAbsolutePath().resolve("neoforge-" + version), install = dir.resolve("install"), done = install.resolve("modaccel-installed");
        Files.createDirectories(dir);
        try (var channel = java.nio.channels.FileChannel.open(dir.resolve("lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.lock()) {
            if (!Files.exists(done)) {
                long t = System.nanoTime();
                String base = "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + version + "/neoforge-" + version + "-installer.jar";
                String sha1 = new String(get(base + ".sha1"), StandardCharsets.UTF_8).trim().split("\\s+")[0];
                Path installer = fetch(base, sha1, dir.resolve("installer.jar"));
                time("installer download", t); t = System.nanoTime();
                Files.createDirectories(install); Files.writeString(install.resolve("launcher_profiles.json"), "{\"profiles\":{}}");
                String java = ProcessHandle.current().info().command().orElse("java");
                Process p = new ProcessBuilder(java, "-jar", installer.toString(), "--install-client", install.toString())
                        .directory(dir.toFile()).redirectErrorStream(true).redirectOutput(dir.resolve("install.log").toFile()).start();
                if (p.waitFor() != 0) throw new IOException("NeoForge installer failed; see " + dir.resolve("install.log"));
                time("installer run", t);
                Files.writeString(done, version);
            }
        }
        Path neo = install.resolve("libraries/net/neoforged/neoforge/" + version);
        Path patched = neo.resolve("neoforge-" + version + "-client.jar"), universal = neo.resolve("neoforge-" + version + "-universal.jar");
        Path renamed;
        try (var found = Files.find(install.resolve("libraries/net/minecraft/client"), 2, (f, a) -> f.getFileName().toString().endsWith("-srg.jar"))) {
            renamed = found.findFirst().orElseThrow(() -> new IOException("NeoForge's install has no renamed client jar under " + install));
        }
        List<Path> transformers = new ArrayList<>(List.of(universal)); transformers.addAll(ownTransformers);
        String key = sha1(String.join("\n", ownTransformers.stream().map(f -> { try { return sha1(Files.readAllBytes(f)); } catch (Exception e) { throw new RuntimeException(e); } }).toList()).getBytes(StandardCharsets.UTF_8)).substring(0, 12);
        Path out = dir.resolve(ownTransformers.isEmpty() ? "compile.jar" : "compile-" + key + ".jar");
        if (!Files.isRegularFile(out)) {
            Path tmp = Files.createTempFile(dir, "compile-", ".tmp");
            neoforge(renamed, patched, tmp, transformers);
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("jar=" + out); System.out.println("universal=" + universal);
        return out;
    }
    static int level(int access) { return (access & 0x0001) != 0 ? 3 : (access & 0x0004) != 0 ? 2 : (access & 0x0002) != 0 ? 0 : 1; }
    static int withLevel(int access, int level) { return (access & ~0x0007) | (level == 3 ? 0x0001 : level == 2 ? 0x0004 : level == 0 ? 0x0002 : 0); }
    static int applyAt(int access, int[] rule) {
        if (rule == null) return access;
        int a = level(access) >= rule[0] ? access : withLevel(access, rule[0]);
        if (rule[1] < 0) a &= ~0x0010; else if (rule[1] > 0) a |= 0x0010;
        return a;
    }

    static String unescape(String json) {
        Matcher m = Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(json); StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1), 16))));
        return m.appendTail(out).toString();
    }
    static String mapGeneric(String iface, Map<String, String> interToNamed) {
        // e.g. net/fabricmc/.../FabricEntityType$Builder<TT;> or ...<Lnet/minecraft/class_1792;>
        StringBuilder out = new StringBuilder(); Matcher m = Pattern.compile("[A-Za-z_$][\\w$]*(/[\\w$]+)+").matcher(iface); int last = 0;
        while (m.find()) { out.append(iface, last, m.start()).append(interToNamed.getOrDefault(m.group(), m.group())); last = m.end(); }
        return out.append(iface.substring(last)).toString();
    }
    /** Reads one access widener (v1/v2) into rules; a dependency's counts only for its transitive entries. */
    static int widen(String text, boolean transitiveOnly, Map<String, Widen> rules, Map<String, String> interToNamed) {
        String[] lines = text.split("\\R"); if (lines.length == 0) return 0;
        boolean intermediary = lines[0].trim().endsWith("intermediary");
        int n = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i]; int hash = line.indexOf('#'); if (hash >= 0) line = line.substring(0, hash);
            String[] p = line.trim().split("\\s+"); if (p.length < 3) continue;
            String access = p[0]; boolean transitive = access.startsWith("transitive-");
            if (transitiveOnly && !transitive) continue;
            if (transitive) access = access.substring("transitive-".length());
            int bit = switch (access) { case "accessible" -> 1; case "extendable" -> 2; case "mutable" -> 4; default -> 0; };
            if (bit == 0) continue;
            String owner = p[2]; if (intermediary) owner = interToNamed.getOrDefault(owner, owner);
            Widen w = rules.computeIfAbsent(owner, k -> new Widen());
            switch (p[1]) {
                case "class" -> w.classAccess |= bit;
                case "method" -> w.methods.merge(p[3] + p[4], bit, (a, b) -> a | b);
                case "field" -> w.fields.merge(p[3] + ":" + p[4], bit, (a, b) -> a | b);
                default -> { continue; }
            }
            n++;
        }
        return n;
    }
    static Map<String, String> intermediaryToNamed(String version) throws Exception {
        Path jar = fetch("https://maven.fabricmc.net/net/fabricmc/intermediary/" + version + "/intermediary-" + version + "-v2.jar", null, cache.resolve(version).resolve("intermediary-v2.jar"));
        Mappings maps = new Mappings();
        Path dir = cache.resolve(version);
        for (String f : List.of("client_mappings.txt", "server_mappings.txt")) if (Files.isRegularFile(dir.resolve(f))) maps.parse(Files.readString(dir.resolve(f)));
        Map<String, String> result = new HashMap<>();
        try (var zip = new ZipFile(jar.toFile())) {
            for (String line : new String(zip.getInputStream(zip.getEntry("mappings/mappings.tiny")).readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.startsWith("c\t")) continue;
                String[] p = line.trim().split("\t");
                result.put(p[2], maps.classes.getOrDefault(p[1], p[1]));
            }
        }
        return result;
    }
    static void writeStored(Map<String, byte[]> entries, Path out) throws IOException {
        Files.createDirectories(out.toAbsolutePath().getParent());
        Path tmp = Files.createTempFile(out.toAbsolutePath().getParent(), "jar-", ".tmp");
        try (var zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 20))) {
            zip.setMethod(ZipOutputStream.STORED);
            for (String name : new TreeSet<>(entries.keySet())) {
                byte[] bytes = entries.get(name); CRC32 crc = new CRC32(); crc.update(bytes);
                ZipEntry entry = new ZipEntry(name); entry.setTime(0); entry.setSize(bytes.length); entry.setCompressedSize(bytes.length); entry.setCrc(crc.getValue());
                zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
            }
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Class files from a game jar; the server jar is a bundler since 1.18 and holds the real jar inside. */
    static void readClasses(Path jar, Map<String, byte[]> into, boolean onlyMissing) throws IOException {
        try (var zip = new ZipFile(jar.toFile())) {
            var inner = zip.stream().filter(e -> e.getName().matches("META-INF/versions/[^/]+/server-[^/]+\\.jar")).findFirst();
            if (inner.isPresent()) {
                try (var in = new ZipInputStream(new BufferedInputStream(zip.getInputStream(inner.get())))) {
                    for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                        String n = e.getName();
                        if (n.endsWith(".class") && !n.startsWith("META-INF/")) { String key = n.substring(0, n.length() - 6); if (!onlyMissing || !into.containsKey(key)) into.put(key, in.readAllBytes()); }
                    }
                }
                return;
            }
            zip.stream().parallel().filter(e -> e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/")).forEach(e -> {
                String key = e.getName().substring(0, e.getName().length() - 6);
                if (onlyMissing && into.containsKey(key)) return;
                try (var in = zip.getInputStream(e)) { into.put(key, in.readAllBytes()); } catch (IOException error) { throw new UncheckedIOException(error); }
            });
        }
    }

    // ---------------------------------------------------------------- mappings

    /** ProGuard-format official mappings, keyed on the obfuscated side. */
    static final class Mappings {
        final Map<String, String> classes = new HashMap<>();             // obf -> named (internal names)
        final Map<String, String> reverse = new HashMap<>();
        // Raw rows until every class is known, because descriptors need the obf names of their types.
        record Row(String namedOwner, boolean method, String namedName, String namedType, String obfName) {}
        final List<Row> rows = new ArrayList<>();
        final Map<String, Map<String, String>> methods = new HashMap<>(); // obf owner -> obfName+obfDesc -> named
        final Map<String, Map<String, String>> fields = new HashMap<>();  // obf owner -> obfName:obfDesc -> named
        void parse(String text) {
            String owner = null;
            for (String line : text.split("\n")) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (!line.startsWith(" ")) {
                    int arrow = line.indexOf(" -> ");
                    String named = line.substring(0, arrow).replace('.', '/'), obf = line.substring(arrow + 4, line.length() - 1).trim().replace('.', '/');
                    if (obf.endsWith(":")) obf = obf.substring(0, obf.length() - 1);
                    owner = named;
                    classes.putIfAbsent(obf, named); reverse.putIfAbsent(named, obf);
                    continue;
                }
                String s = line.trim(); int arrow = s.indexOf(" -> "); if (arrow < 0 || owner == null) continue;
                String left = s.substring(0, arrow), obf = s.substring(arrow + 4).trim();
                int paren = left.indexOf('(');
                if (paren >= 0) {
                    String head = left.substring(0, paren); int colon = head.lastIndexOf(':'); if (colon >= 0) head = head.substring(colon + 1);
                    int space = head.lastIndexOf(' ');
                    String ret = head.substring(0, space), name = head.substring(space + 1);
                    String params = left.substring(paren + 1, left.indexOf(')', paren));
                    StringBuilder desc = new StringBuilder("(");
                    if (!params.isEmpty()) for (String p : params.split(",")) desc.append(javaToDesc(p));
                    desc.append(')').append(javaToDesc(ret));
                    rows.add(new Row(owner, true, name, desc.toString(), obf));
                } else {
                    int space = left.lastIndexOf(' ');
                    rows.add(new Row(owner, false, left.substring(space + 1), javaToDesc(left.substring(0, space)), obf));
                }
            }
        }
        void index() {
            Remapper unmap = new Remapper(null, null) { @Override String mapClass(String named) { return reverse.getOrDefault(named, named); } };
            for (Row r : rows) {
                String obfOwner = reverse.getOrDefault(r.namedOwner, r.namedOwner);
                String obfDesc = r.method ? unmap.mapDesc(r.namedType) : unmap.mapDesc(r.namedType);
                if (r.method) methods.computeIfAbsent(obfOwner, k -> new HashMap<>()).putIfAbsent(r.obfName + obfDesc, r.namedName);
                else fields.computeIfAbsent(obfOwner, k -> new HashMap<>()).putIfAbsent(r.obfName + ":" + obfDesc, r.namedName);
            }
            rows.clear();
        }
        static String javaToDesc(String type) {
            int dims = 0; while (type.endsWith("[]")) { dims++; type = type.substring(0, type.length() - 2); }
            String base = switch (type) {
                case "void" -> "V"; case "boolean" -> "Z"; case "byte" -> "B"; case "char" -> "C"; case "short" -> "S";
                case "int" -> "I"; case "long" -> "J"; case "float" -> "F"; case "double" -> "D";
                default -> "L" + type.replace('.', '/') + ";";
            };
            return "[".repeat(dims) + base;
        }
    }

    /** Super types of every game class, read from the obfuscated jars. */
    static final class Hierarchy {
        final Map<String, String[]> supers = new HashMap<>();
        Hierarchy(Map<String, byte[]> classes) {
            Map<String, String[]> found = new ConcurrentHashMap<>();
            classes.entrySet().parallelStream().forEach(e -> found.put(e.getKey(), ClassFile.supers(e.getValue())));
            supers.putAll(found);
        }
    }

    // ---------------------------------------------------------------- class file remapping

    static class Remapper {
        final Mappings maps; final Hierarchy hierarchy; final AtomicLong memberHits = new AtomicLong();
        Remapper(Mappings maps, Hierarchy hierarchy) { this.maps = maps; this.hierarchy = hierarchy; }
        String mapClass(String obf) {
            String named = maps.classes.get(obf);
            if (named != null) return named;
            // Inner classes absent from the mappings keep their own suffix under the renamed outer class.
            int dollar = obf.lastIndexOf('$');
            if (dollar > 0) { String outer = mapClass(obf.substring(0, dollar)); if (!outer.equals(obf.substring(0, dollar))) return outer + obf.substring(dollar); }
            return obf;
        }
        String mapClassOrArray(String name) { return name.startsWith("[") ? mapDesc(name) : mapClass(name); }
        String mapDesc(String desc) {
            StringBuilder out = new StringBuilder(desc.length() + 16);
            for (int i = 0; i < desc.length(); i++) {
                char c = desc.charAt(i); out.append(c);
                if (c == 'L') { int end = desc.indexOf(';', i); out.append(mapClass(desc.substring(i + 1, end))).append(';'); i = end; }
            }
            return out.toString();
        }
        /** Generic signatures: class names, with inner segments after '.' renamed against their outer class. */
        String mapSignature(String sig) {
            StringBuilder out = new StringBuilder(sig.length() + 16);
            int i = 0;
            if (sig.startsWith("<")) i = formals(sig, 0, out);
            while (i < sig.length()) {
                char c = sig.charAt(i);
                if (c == '(' || c == ')' || c == '^') { out.append(c); i++; continue; }
                i = type(sig, i, out);
            }
            return out.toString();
        }
        /** Formal type parameters: identifiers, each with a class bound and interface bounds. */
        int formals(String sig, int i, StringBuilder out) {
            out.append('<'); i++;
            while (sig.charAt(i) != '>') {
                int colon = sig.indexOf(':', i); out.append(sig, i, colon); i = colon;
                while (sig.charAt(i) == ':') { out.append(':'); i++; if (sig.charAt(i) != ':' && sig.charAt(i) != '>' ) { if ("LT[".indexOf(sig.charAt(i)) >= 0) i = type(sig, i, out); } }
            }
            out.append('>'); return i + 1;
        }
        /** One type signature starting at i. */
        int type(String sig, int i, StringBuilder out) {
            char c = sig.charAt(i);
            if (c == '[') { out.append('['); return type(sig, i + 1, out); }
            if (c == 'T') { int end = sig.indexOf(';', i); out.append(sig, i, end + 1); return end + 1; }
            if (c != 'L') { out.append(c); return i + 1; }
            out.append('L'); i++;
            String obfOuter = null, mappedOuter = null;
            while (true) {
                int j = i; while ("<;.".indexOf(sig.charAt(j)) < 0) j++;
                String part = sig.substring(i, j);
                if (obfOuter == null) { obfOuter = part; mappedOuter = mapClass(part); out.append(mappedOuter); }
                else {
                    String obfFull = obfOuter + "$" + part, mappedFull = mapClass(obfFull);
                    String simple = mappedFull.startsWith(mappedOuter + "$") ? mappedFull.substring(mappedOuter.length() + 1) : part;
                    out.append(simple); obfOuter = obfFull; mappedOuter = mappedFull;
                }
                i = j;
                if (sig.charAt(i) == '<') {
                    out.append('<'); i++;
                    while (sig.charAt(i) != '>') {
                        char w = sig.charAt(i);
                        if (w == '*') { out.append('*'); i++; continue; }
                        if (w == '+' || w == '-') { out.append(w); i++; }
                        i = type(sig, i, out);
                    }
                    out.append('>'); i++;
                }
                if (sig.charAt(i) == '.') { out.append('.'); i++; continue; }
                out.append(';'); return i + 1;
            }
        }
        String mapMethod(String owner, String name, String desc) {
            if (name.startsWith("<")) return name;
            String found = findMember(owner, name + desc, true, new HashSet<>());
            if (found != null) { memberHits.incrementAndGet(); return found; }
            return name;
        }
        String mapField(String owner, String name, String desc) {
            String found = findMember(owner, name + ":" + desc, false, new HashSet<>());
            if (found != null) { memberHits.incrementAndGet(); return found; }
            return name;
        }
        String findMember(String owner, String key, boolean method, Set<String> seen) {
            if (owner.startsWith("[")) return method ? null : null;
            if (!seen.add(owner)) return null;
            Map<String, String> table = (method ? maps.methods : maps.fields).get(owner);
            if (table != null) { String named = table.get(key); if (named != null) return named; }
            String[] supers = hierarchy.supers.get(owner);
            if (supers == null) return null;
            for (String s : supers) { if (s == null) continue; String named = findMember(s, key, method, seen); if (named != null) return named; }
            return null;
        }
        byte[] remap(byte[] bytes) throws IOException { return new ClassFile(bytes, this).remap(); }
    }

    /**
     * Rewrites one class in place: original constant pool indices stay valid (the bytecode is not touched),
     * entries that need other text point at new constants appended to the pool, and every attribute that
     * names a type or member is re-pointed.
     */
    static final class ClassFile {
        final byte[] b; final Remapper r;
        int count; int[] offsets; byte[] tags; int cpEnd;
        final List<byte[]> appended = new ArrayList<>(); int next;
        final Map<String, Integer> utf = new HashMap<>(), nats = new HashMap<>(), strings = new HashMap<>();
        final Map<Integer, Integer> patches = new HashMap<>(); // byte offset of a u2 -> new value
        String thisObf;
        ClassFile(byte[] b, Remapper r) { this.b = b; this.r = r; parsePool(); }

        int u1(int at) { return b[at] & 255; }
        int u2(int at) { return ((b[at] & 255) << 8) | (b[at + 1] & 255); }
        int u4(int at) { return (u2(at) << 16) | u2(at + 2); }
        void parsePool() {
            count = u2(8); offsets = new int[count]; tags = new byte[count]; int at = 10;
            for (int i = 1; i < count; i++) {
                offsets[i] = at; tags[i] = b[at];
                switch (b[at]) {
                    case 1 -> at += 3 + u2(at + 1);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> at += 5;
                    case 5, 6 -> { at += 9; i++; }
                    case 7, 8, 16, 19, 20 -> at += 3;
                    case 15 -> at += 4;
                    default -> throw new IllegalStateException("constant tag " + b[at]);
                }
            }
            cpEnd = at; next = count;
        }
        String utf8(int index) {
            int at = offsets[index]; int len = u2(at + 1);
            return decodeModifiedUtf8(b, at + 3, len);
        }
        static String decodeModifiedUtf8(byte[] b, int from, int len) {
            boolean ascii = true; for (int i = from; i < from + len; i++) if (b[i] <= 0) { ascii = false; break; }
            if (ascii) return new String(b, from, len, StandardCharsets.ISO_8859_1);
            try { return new DataInputStream(new ByteArrayInputStream(lengthPrefixed(b, from, len))).readUTF(); } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        static byte[] lengthPrefixed(byte[] b, int from, int len) { byte[] out = new byte[len + 2]; out[0] = (byte)(len >> 8); out[1] = (byte) len; System.arraycopy(b, from, out, 2, len); return out; }
        String className(int classIndex) { return utf8(u2(offsets[classIndex] + 1)); }

        int newUtf(String s) {
            Integer have = utf.get(s); if (have != null) return have;
            try {
                var bytes = new ByteArrayOutputStream(); var data = new DataOutputStream(bytes); data.writeByte(1); data.writeUTF(s);
                appended.add(bytes.toByteArray());
            } catch (IOException e) { throw new UncheckedIOException(e); }
            utf.put(s, next); return next++;
        }
        int newNat(String name, String desc) {
            String key = name + " " + desc; Integer have = nats.get(key); if (have != null) return have;
            int n = newUtf(name), d = newUtf(desc);
            appended.add(new byte[]{12, (byte)(n >> 8), (byte) n, (byte)(d >> 8), (byte) d});
            nats.put(key, next); return next++;
        }
        int newString(String s) {
            Integer have = strings.get(s); if (have != null) return have;
            int u = newUtf(s); appended.add(new byte[]{8, (byte)(u >> 8), (byte) u});
            strings.put(s, next); return next++;
        }
        /** Re-point the u2 at {@code at} (a Utf8 index) to the mapped text, if it changed. */
        void mapUtf(int at, java.util.function.UnaryOperator<String> f) {
            int index = u2(at); if (index == 0) return;
            String before = utf8(index), after = f.apply(before);
            if (!after.equals(before)) patches.put(at, newUtf(after));
        }

        static String[] supers(byte[] bytes) {
            ClassFile c = new ClassFile(bytes, null);
            int at = c.cpEnd + 2; int superIndex = c.u2(at + 2); int n = c.u2(at + 4);
            String[] out = new String[n + 1]; out[0] = superIndex == 0 ? null : c.className(superIndex);
            for (int i = 0; i < n; i++) out[i + 1] = c.className(c.u2(at + 6 + 2 * i));
            return out;
        }

        byte[] remap() throws IOException {
            // Pool entries that point at other entries.
            for (int i = 1; i < count; i++) {
                int at = offsets[i];
                switch (tags[i]) {
                    case 7 -> mapUtf(at + 1, r::mapClassOrArray);
                    case 16 -> mapUtf(at + 1, r::mapDesc);
                    case 9, 10, 11 -> {
                        String owner = className(u2(at + 1)); int nat = u2(at + 3);
                        String name = utf8(u2(offsets[nat] + 1)), desc = utf8(u2(offsets[nat] + 3));
                        String newName = tags[i] == 9 ? r.mapField(owner, name, desc) : r.mapMethod(owner, name, desc), newDesc = r.mapDesc(desc);
                        if (!newName.equals(name) || !newDesc.equals(desc)) patches.put(at + 3, newNat(newName, newDesc));
                    }
                    default -> {}
                }
            }
            int at = cpEnd; int access = u2(at); thisObf = className(u2(at + 2));
            int interfaces = u2(at + 6); at += 8 + 2 * interfaces;
            // Fields, then methods.
            for (int kind = 0; kind < 2; kind++) {
                int n = u2(at); at += 2;
                for (int i = 0; i < n; i++) {
                    String name = utf8(u2(at + 2)), desc = utf8(u2(at + 4));
                    String newName = kind == 0 ? r.mapField(thisObf, name, desc) : r.mapMethod(thisObf, name, desc);
                    if (!newName.equals(name)) patches.put(at + 2, newUtf(newName));
                    mapUtf(at + 4, r::mapDesc);
                    at = attributes(at + 6);
                }
            }
            int classAttributes = at; at = attributes(at);
            if (at != b.length) throw new IOException("trailing bytes in " + thisObf);
            // Invokedynamic names depend on BootstrapMethods, read above.
            for (int i = 1; i < count; i++) if (tags[i] == 18 || tags[i] == 17) {
                int o = offsets[i], nat = u2(o + 3), bsm = u2(o + 1);
                String name = utf8(u2(offsets[nat] + 1)), desc = utf8(u2(offsets[nat] + 3)), newName = name;
                Bootstrap boot = bootstraps.size() > bsm ? bootstraps.get(bsm) : null;
                if (tags[i] == 18 && boot != null && boot.owner.equals("java/lang/invoke/LambdaMetafactory") && boot.args.length > 0 && tags[boot.args[0]] == 16) {
                    String iface = desc.substring(desc.indexOf(')') + 2, desc.length() - 1);
                    newName = r.mapMethod(iface, name, utf8(u2(offsets[boot.args[0]] + 1)));
                }
                String newDesc = r.mapDesc(desc);
                if (!newName.equals(name) || !newDesc.equals(desc)) patches.put(o + 3, newNat(newName, newDesc));
                if (boot != null && boot.owner.equals("java/lang/runtime/ObjectMethods") && boot.args.length > 1 && tags[boot.args[1]] == 8) {
                    String names = utf8(u2(offsets[boot.args[1]] + 1)); String[] parts = names.isEmpty() ? new String[0] : names.split(";", -1);
                    for (int k = 0; k < parts.length && k + 2 < boot.args.length; k++) {
                        int handle = boot.args[k + 2]; int ref = u2(offsets[handle] + 2); int refNat = u2(offsets[ref] + 3);
                        parts[k] = r.mapField(className(u2(offsets[ref] + 1)), parts[k], utf8(u2(offsets[refNat] + 3)));
                    }
                    String joined = String.join(";", parts);
                    if (!joined.equals(names)) patches.put(boot.argOffsets[1], newString(joined));
                }
            }
            return write();
        }
        record Bootstrap(String owner, int[] args, int[] argOffsets) {}
        final List<Bootstrap> bootstraps = new ArrayList<>();

        /** Walks an attribute table, re-pointing names and types; returns the offset after it. */
        int attributes(int at) {
            int n = u2(at); at += 2;
            for (int i = 0; i < n; i++) {
                String name = utf8(u2(at)); int len = u4(at + 2), body = at + 6;
                switch (name) {
                    case "Signature" -> mapUtf(body, r::mapSignature);
                    case "Code" -> {
                        int codeLength = u4(body + 4); int p = body + 8 + codeLength;
                        int exceptions = u2(p); p += 2 + 8 * exceptions;
                        attributes(p);
                    }
                    case "LocalVariableTable", "LocalVariableTypeTable" -> {
                        int entries = u2(body);
                        for (int k = 0; k < entries; k++) mapUtf(body + 2 + 10 * k + 6, name.equals("LocalVariableTable") ? r::mapDesc : r::mapSignature);
                    }
                    case "InnerClasses" -> {
                        int entries = u2(body);
                        for (int k = 0; k < entries; k++) {
                            int e = body + 2 + 8 * k; int inner = u2(e), simple = u2(e + 4);
                            if (simple == 0) continue;
                            String obf = className(inner), mapped = r.mapClass(obf);
                            if (mapped.equals(obf)) continue;
                            int dollar = mapped.lastIndexOf('$'); String newSimple = dollar >= 0 ? mapped.substring(dollar + 1) : mapped.substring(mapped.lastIndexOf('/') + 1);
                            if (!newSimple.equals(utf8(simple))) patches.put(e + 4, newUtf(newSimple));
                        }
                    }
                    case "EnclosingMethod" -> {
                        int nat = u2(body + 2);
                        if (nat != 0) {
                            String owner = className(u2(body)), mn = utf8(u2(offsets[nat] + 1)), md = utf8(u2(offsets[nat] + 3));
                            patches.put(body + 2, newNat(r.mapMethod(owner, mn, md), r.mapDesc(md)));
                        }
                    }
                    case "Record" -> {
                        int components = u2(body); int p = body + 2;
                        for (int k = 0; k < components; k++) {
                            String cn = utf8(u2(p)), cd = utf8(u2(p + 2));
                            String mapped = r.mapField(thisObf, cn, cd);
                            if (!mapped.equals(cn)) patches.put(p, newUtf(mapped));
                            mapUtf(p + 2, r::mapDesc);
                            p = attributes(p + 4);
                        }
                    }
                    case "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations" -> { int p = body + 2; for (int k = 0, c = u2(body); k < c; k++) p = annotation(p); }
                    case "RuntimeVisibleParameterAnnotations", "RuntimeInvisibleParameterAnnotations" -> {
                        int p = body + 1;
                        for (int q = 0, params = u1(body); q < params; q++) { int c = u2(p); p += 2; for (int k = 0; k < c; k++) p = annotation(p); }
                    }
                    case "AnnotationDefault" -> elementValue(body);
                    case "RuntimeVisibleTypeAnnotations", "RuntimeInvisibleTypeAnnotations" -> { int p = body + 2; for (int k = 0, c = u2(body); k < c; k++) p = typeAnnotation(p); }
                    case "BootstrapMethods" -> {
                        int c = u2(body); int p = body + 2;
                        for (int k = 0; k < c; k++) {
                            int handle = u2(p), argc = u2(p + 2); int[] args = new int[argc], argOffsets = new int[argc];
                            for (int q = 0; q < argc; q++) { argOffsets[q] = p + 4 + 2 * q; args[q] = u2(argOffsets[q]); }
                            int ref = u2(offsets[handle] + 2);
                            bootstraps.add(new Bootstrap(className(u2(offsets[ref] + 1)), args, argOffsets));
                            p += 4 + 2 * argc;
                        }
                    }
                    default -> {}
                }
                at = body + len;
            }
            return at;
        }
        int annotation(int p) {
            mapUtf(p, r::mapDesc);
            int pairs = u2(p + 2); p += 4;
            for (int k = 0; k < pairs; k++) p = elementValue(p + 2);
            return p;
        }
        int elementValue(int p) {
            char tag = (char) b[p];
            switch (tag) {
                case 'e' -> { mapUtf(p + 1, r::mapDesc); return p + 5; }
                case 'c' -> { mapUtf(p + 1, r::mapDesc); return p + 3; }
                case '@' -> { return annotation(p + 1); }
                case '[' -> { int c = u2(p + 1); p += 3; for (int k = 0; k < c; k++) p = elementValue(p); return p; }
                default -> { return p + 3; }
            }
        }
        int typeAnnotation(int p) {
            int target = u1(p); p++;
            switch (target) {
                case 0x00, 0x01, 0x16 -> p += 1;
                case 0x10, 0x11, 0x12, 0x17, 0x42, 0x43, 0x44, 0x45, 0x46 -> p += 2;
                case 0x13, 0x14, 0x15 -> {}
                case 0x40, 0x41 -> p += 2 + 6 * u2(p);
                case 0x47, 0x48, 0x49, 0x4A, 0x4B -> p += 3;
                default -> throw new IllegalStateException("type annotation target " + target);
            }
            p += 1 + 2 * u1(p); // type path
            return annotation(p);
        }

        static int makePublic(int a) { return (a & ~0x0006) | 0x0001; }
        static int applyClass(int a, int bits) { if ((bits & 1) != 0) a = makePublic(a); if ((bits & 2) != 0) a = makePublic(a) & ~0x0010; return a; }
        final List<Integer> addedInterfaces = new ArrayList<>(); int signatureAt = -1, classAttributesAt = -1; String newSignature;

        /** Applies access wideners and injected interfaces the way Fabric's class visitor does. */
        byte[] widen(Widen w, Map<String, Integer> classAccess) {
            int at = cpEnd; int access = u2(at); thisObf = className(u2(at + 2));
            int ownerAccess = access;
            if (w != null && w.classAccess != 0) patches.put(at, applyClass(access, w.classAccess));
            int superIndex = u2(at + 4), interfaces = u2(at + 6);
            boolean record = superIndex != 0 && className(superIndex).equals("java/lang/Record");
            Set<String> existing = new HashSet<>(); List<String> existingNames = new ArrayList<>();
            for (int i = 0; i < interfaces; i++) { String n = className(u2(at + 8 + 2 * i)); existing.add(n); existingNames.add(n); }
            at += 8 + 2 * interfaces;
            for (int kind = 0; kind < 2; kind++) {
                int n = u2(at); at += 2;
                for (int i = 0; i < n; i++) {
                    int acc = u2(at); String name = utf8(u2(at + 2)), desc = utf8(u2(at + 4));
                    Integer bits = w == null ? null : kind == 0 ? w.fields.get(name + ":" + desc) : w.methods.get(name + desc);
                    // Loom also opens the constructors of a record it makes accessible (seen: TagLoader$SortingEntry).
                    if (bits == null && kind == 1 && record && w != null && (w.classAccess & 1) != 0 && name.equals("<init>") && (acc & 0x0007) == 0) bits = 1;
                    if (bits != null) {
                        int a = acc;
                        if (kind == 0) { if ((bits & 1) != 0) a = makePublic(a); if ((bits & 4) != 0) a &= ~0x0010; }
                        else {
                            if ((bits & 1) != 0) {
                                if (!name.equals("<init>") && (a & 0x0002) != 0 && (a & 0x0008) == 0 && (ownerAccess & 0x0200) == 0) a |= 0x0010;
                                a = makePublic(a);
                            }
                            if ((bits & 2) != 0) { if ((a & 0x0001) == 0) a = (a & ~0x0002) | 0x0004; a &= ~0x0010; }
                        }
                        if (a != acc) patches.put(at, a);
                    }
                    at = skipAttributes(at + 6);
                }
            }
            classAttributesAt = at;
            int n = u2(at); int p = at + 2;
            for (int i = 0; i < n; i++) {
                String name = utf8(u2(p)); int len = u4(p + 2), body = p + 6;
                if (name.equals("Signature")) signatureAt = body;
                if (name.equals("InnerClasses")) {
                    for (int k = 0, entries = u2(body); k < entries; k++) {
                        int e = body + 2 + 8 * k; Integer bits = classAccess.get(className(u2(e)));
                        if (bits != null) { int a = u2(e + 6), na = applyClass(a, bits); if (na != a) patches.put(e + 6, na); }
                    }
                }
                p = body + len;
            }
            if (w != null && !w.interfaces.isEmpty()) {
                StringBuilder sig = new StringBuilder(); boolean generic = false;
                for (String iface : w.interfaces) {
                    String raw = iface.contains("<") ? iface.substring(0, iface.indexOf('<')) : iface;
                    if (!existing.add(raw)) continue;
                    utfClass(raw); generic |= iface.contains("<"); sig.append('L').append(iface).append(';');
                }
                if (sig.length() > 0) {
                    if (signatureAt >= 0) newSignature = utf8(u2(signatureAt)) + sig;
                    else if (generic) {
                        StringBuilder full = new StringBuilder("L").append(className(superIndex)).append(';');
                        for (String e : existingNames) full.append('L').append(e).append(';');
                        newSignature = full.append(sig).toString();
                    }
                    if (newSignature != null && signatureAt >= 0) patches.put(signatureAt, newUtf(newSignature));
                }
            }
            return write();
        }
        /** Applies access transformers: class, members (exact, or "*" / "*()"), and nested-class entries everywhere. */
        byte[] transform(Widen w, Map<String, int[]> classAccess) {
            int at = cpEnd; int access = u2(at);
            if (w != null && w.atClass != null) { int a = GamePrep.applyAt(access, w.atClass); if (a != access) patches.put(at, a); }
            at += 8 + 2 * u2(at + 6);
            for (int kind = 0; kind < 2; kind++) {
                int n = u2(at); at += 2;
                for (int i = 0; i < n; i++) {
                    if (w != null) {
                        int acc = u2(at); String name = utf8(u2(at + 2)), desc = utf8(u2(at + 4));
                        int[] rule = kind == 0 ? w.atFields.getOrDefault(name, w.atFields.get("*")) : w.atMethods.getOrDefault(name + desc, name.startsWith("<") ? null : w.atMethods.get("*()"));
                        int a = GamePrep.applyAt(acc, rule); if (a != acc) patches.put(at, a);
                    }
                    at = skipAttributes(at + 6);
                }
            }
            int n = u2(at); int p = at + 2;
            for (int i = 0; i < n; i++) {
                String name = utf8(u2(p)); int len = u4(p + 2), body = p + 6;
                if (name.equals("InnerClasses")) for (int k = 0, entries = u2(body); k < entries; k++) {
                    int e = body + 2 + 8 * k; int[] rule = classAccess.get(className(u2(e)));
                    if (rule != null) { int a = u2(e + 6), na = GamePrep.applyAt(a, rule); if (na != a) patches.put(e + 6, na); }
                }
                p = body + len;
            }
            return write();
        }
        void utfClass(String name) {
            int u = newUtf(name); appended.add(new byte[]{7, (byte)(u >> 8), (byte) u}); addedInterfaces.add(next++);
        }
        int skipAttributes(int at) { int n = u2(at); at += 2; for (int i = 0; i < n; i++) at += 6 + u4(at + 2); return at; }

        byte[] write() {
            if (patches.isEmpty() && appended.isEmpty()) return b;
            if (!addedInterfaces.isEmpty() || (newSignature != null && signatureAt < 0)) return writeWithHeader();
            byte[] copy = b.clone();
            for (var e : patches.entrySet()) { int at = e.getKey(), v = e.getValue(); copy[at] = (byte)(v >> 8); copy[at + 1] = (byte) v; }
            if (next > 65535) throw new IllegalStateException("constant pool overflow in " + thisObf);
            var out = new ByteArrayOutputStream(b.length + 256 + appended.stream().mapToInt(a -> a.length).sum());
            out.write(copy, 0, 8); out.write(next >> 8); out.write(next);
            out.write(copy, 10, cpEnd - 10);
            for (byte[] a : appended) out.write(a, 0, a.length);
            out.write(copy, cpEnd, copy.length - cpEnd);
            return out.toByteArray();
        }
        byte[] writeWithHeader() {
            boolean addSignature = newSignature != null && signatureAt < 0;
            int sigName = addSignature ? newUtf("Signature") : 0, sigValue = addSignature ? newUtf(newSignature) : 0;
            byte[] copy = b.clone();
            for (var e : patches.entrySet()) { int at = e.getKey(), v = e.getValue(); copy[at] = (byte)(v >> 8); copy[at + 1] = (byte) v; }
            var out = new ByteArrayOutputStream(b.length + 512);
            out.write(copy, 0, 8); out.write(next >> 8); out.write(next);
            out.write(copy, 10, cpEnd - 10);
            for (byte[] a : appended) out.write(a, 0, a.length);
            int interfaces = u2(cpEnd + 6), total = interfaces + addedInterfaces.size();
            out.write(copy, cpEnd, 6); out.write(total >> 8); out.write(total);
            out.write(copy, cpEnd + 8, 2 * interfaces);
            for (int index : addedInterfaces) { out.write(index >> 8); out.write(index); }
            int afterInterfaces = cpEnd + 8 + 2 * interfaces;
            out.write(copy, afterInterfaces, classAttributesAt - afterInterfaces);
            int attributes = u2(classAttributesAt) + (addSignature ? 1 : 0);
            out.write(attributes >> 8); out.write(attributes);
            out.write(copy, classAttributesAt + 2, copy.length - classAttributesAt - 2);
            if (addSignature) out.writeBytes(new byte[]{(byte)(sigName >> 8), (byte) sigName, 0, 0, 0, 2, (byte)(sigValue >> 8), (byte) sigValue});
            return out.toByteArray();
        }
    }

    // ---------------------------------------------------------------- signature comparison

    /** Every class with its super types, signature and members (access, name, descriptor, signature), one line each. */
    static SortedMap<String, SortedSet<String>> signatures(Path jar) throws IOException {
        SortedMap<String, SortedSet<String>> out = new TreeMap<>();
        try (var zip = new ZipFile(jar.toFile())) {
            Map<String, SortedSet<String>> found = new ConcurrentHashMap<>();
            zip.stream().parallel().filter(e -> e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/")).forEach(e -> {
                try (var in = zip.getInputStream(e)) { var s = Shape.of(in.readAllBytes()); found.put(s.getKey(), s.getValue()); }
                catch (IOException error) { throw new UncheckedIOException(error); }
            });
            out.putAll(found);
        }
        return out;
    }
    static boolean ALL = Boolean.getBoolean("gameprep.all");
    static final class Shape {
        static Map.Entry<String, SortedSet<String>> of(byte[] bytes) {
            ClassFile c = new ClassFile(bytes, null);
            int at = c.cpEnd; int access = c.u2(at); String name = c.className(c.u2(at + 2));
            int superIndex = c.u2(at + 4); int n = c.u2(at + 6);
            SortedSet<String> lines = new TreeSet<>();
            StringBuilder head = new StringBuilder("class ").append(access & 0x7631).append(" extends ").append(superIndex == 0 ? "-" : c.className(superIndex)).append(" implements");
            List<String> ifs = new ArrayList<>(); for (int i = 0; i < n; i++) ifs.add(c.className(c.u2(at + 8 + 2 * i))); Collections.sort(ifs);
            head.append(' ').append(String.join(",", ifs));
            at += 8 + 2 * n;
            for (int kind = 0; kind < 2; kind++) {
                int count = c.u2(at); at += 2;
                for (int i = 0; i < count; i++) {
                    int acc = c.u2(at); String mn = c.utf8(c.u2(at + 2)), md = c.utf8(c.u2(at + 4));
                    String[] sig = {""}; at = attrs(c, at + 6, sig);
                    if (!ALL && (acc & 0x1000) != 0) continue; // synthetic members are compiler detail, not API
                    if (!ALL && (acc & 0x0002) != 0) continue; // private members are not visible to a mod's compile (access wideners aside)
                    lines.add((kind == 0 ? "field " : "method ") + (ALL ? acc : acc & 0x0e5f) + " " + mn + " " + md + (sig[0].isEmpty() ? "" : " sig " + sig[0]));
                }
            }
            String[] sig = {""}; attrs(c, at, sig);
            lines.add(head + (sig[0].isEmpty() ? "" : " sig " + sig[0]));
            return Map.entry(name, lines);
        }
        /** Access level (0 private .. 3 public) of each non-private, non-static method other than constructors. */
        static Map<String, Integer> methodLevels(byte[] bytes) {
            ClassFile c = new ClassFile(bytes, null); Map<String, Integer> out = new HashMap<>();
            int at = c.cpEnd; int n = c.u2(at + 6); at += 8 + 2 * n;
            for (int kind = 0; kind < 2; kind++) {
                int count = c.u2(at); at += 2;
                for (int i = 0; i < count; i++) {
                    int acc = c.u2(at); String mn = c.utf8(c.u2(at + 2)), md = c.utf8(c.u2(at + 4));
                    at = attrs(c, at + 6, new String[]{""});
                    if (kind == 1 && (acc & 0x000a) == 0 && !mn.startsWith("<")) out.put(mn + md, GamePrep.level(acc));
                }
            }
            return out;
        }
        static int attrs(ClassFile c, int at, String[] signature) {
            int n = c.u2(at); at += 2;
            for (int i = 0; i < n; i++) { String name = c.utf8(c.u2(at)); int len = c.u4(at + 2); if (name.equals("Signature")) signature[0] = c.utf8(c.u2(at + 6)); at += 6 + len; }
            return at;
        }
    }
    static int compare(Path a, Path b, int show) throws IOException {
        var left = signatures(a); var right = signatures(b);
        Set<String> onlyLeft = new TreeSet<>(left.keySet()); onlyLeft.removeAll(right.keySet());
        Set<String> onlyRight = new TreeSet<>(right.keySet()); onlyRight.removeAll(left.keySet());
        int same = 0, differ = 0, memberOnlyLeft = 0, memberOnlyRight = 0; List<String> examples = new ArrayList<>();
        for (String name : left.keySet()) {
            if (!right.containsKey(name)) continue;
            var l = left.get(name); var r = right.get(name);
            if (l.equals(r)) { same++; continue; }
            differ++;
            for (String line : l) if (!r.contains(line)) { memberOnlyLeft++; if (examples.size() < show) examples.add("  - " + name + ": " + line); }
            for (String line : r) if (!l.contains(line)) { memberOnlyRight++; if (examples.size() < show) examples.add("  + " + name + ": " + line); }
        }
        System.out.printf("classes: %d in A, %d in B; %d identical, %d differ; %d only in A, %d only in B%n", left.size(), right.size(), same, differ, onlyLeft.size(), onlyRight.size());
        System.out.printf("lines (class headers and non-private, non-synthetic members) only in A: %d, only in B: %d%n", memberOnlyLeft, memberOnlyRight);
        onlyLeft.stream().limit(show).forEach(n -> System.out.println("  A only: " + n));
        onlyRight.stream().limit(show).forEach(n -> System.out.println("  B only: " + n));
        examples.forEach(System.out::println);
        return differ == 0 && onlyLeft.isEmpty() && onlyRight.isEmpty() ? 0 : 1;
    }
}
