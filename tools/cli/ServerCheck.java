import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * server-check: a scenario of in-game steps against a real, headless server with the project loaded and a
 * protocol bot player. For a Paper/Bukkit plugin the server is the real Paper server for the plugin's API
 * version, downloaded from PaperMC's official API (fill.papermc.io) and cached per version and build; the
 * plugin's own jar task builds the jar. tools/servercheck/ModaccelServerCheck runs the scenario in its own
 * JVM with MCProtocolLib (knowledge/mcprotocollib.json) and reports each step. The bot speaks the newest
 * protocol it has; a server on any other version is joined through ViaProxy (knowledge/viaproxy.json).
 */
final class ServerCheck {
    static final List<String> SOURCES = List.of("tools/servercheck/ModaccelServerCheck.java", "tools/cli/Json.java");
    static final String FILL = "https://fill.papermc.io/v3/projects/paper";
    static final int TIMEOUT_SECONDS = 900;

    final Build build; final Map<String, Object> model;
    ServerCheck(Build build, Map<String, Object> model) { this.build = build; this.model = model; }

    record Bot(String minecraft, String version, List<String> repositories) { }
    static Bot bot(Path harness, String minecraft) throws IOException {
        var knowledge = Json.read(harness.resolve("knowledge/mcprotocollib.json"));
        for (Object r : Json.array(knowledge.get("releases"))) {
            var release = Json.object(r);
            if (Json.text(release, "minecraft", "").equals(minecraft)) return new Bot(minecraft, Json.text(release, "version", ""), Json.array(knowledge.get("repositories")).stream().map(String::valueOf).toList());
        }
        return null;
    }
    /** The bot for a server it cannot speak to directly: the newest release, joined through ViaProxy. */
    static Bot newestBot(Path harness) throws IOException {
        var knowledge = Json.read(harness.resolve("knowledge/mcprotocollib.json"));
        List<Object> releases = Json.array(knowledge.get("releases"));
        var release = Json.object(releases.get(releases.size() - 1));
        return new Bot(Json.text(release, "minecraft", ""), Json.text(release, "version", ""), Json.array(knowledge.get("repositories")).stream().map(String::valueOf).toList());
    }
    /** ViaProxy's name for a Minecraft version from its own --list-versions: exact (1.21.4), a range (1.16.4-1.16.5) or a line (1.8.x). */
    static String viaName(String minecraft, List<String> names) {
        for (String name : names) {
            if (name.equals(minecraft)) return name;
            if (name.endsWith(".x")) { String line = name.substring(0, name.length() - 2); if (minecraft.equals(line) || minecraft.startsWith(line + ".")) return name; }
            int dash = name.indexOf('-');
            if (dash > 0 && compare(name.substring(0, dash), minecraft) <= 0 && compare(minecraft, name.substring(dash + 1)) <= 0) return name;
        }
        return null;
    }
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int u = i < x.length ? Integer.parseInt(x[i]) : 0, v = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (u != v) return Integer.compare(u, v);
        }
        return 0;
    }
    /** The Minecraft version of a Paper API version: 26.3.build.214-stable is 26.3, 1.21.10-R0.1-SNAPSHOT is 1.21.10. */
    static String minecraft(String api) {
        Matcher m = Pattern.compile("^(\\d+\\.\\d+(?:\\.\\d+)?)(?=[.-]build|-R|$)").matcher(api);
        if (m.find()) return m.group(1);
        Matcher plain = Pattern.compile("^(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(api);
        return plain.find() ? plain.group(1) : api;
    }
    /**
     * The Java a Paper server of this version runs on: 26.x needs 25, 1.20.5 and later 21, 1.17 to 1.20.4 17, 1.12 to
     * 1.16 11, older lines 8. Lines up to 1.16 break on a newer Java, so serverJdk gives them exactly this one.
     */
    static int serverJava(String minecraft) {
        String[] p = minecraft.split("\\.");
        int major = Integer.parseInt(p[0]), minor = p.length > 1 ? Integer.parseInt(p[1]) : 0, patch = p.length > 2 ? Integer.parseInt(p[2]) : 0;
        if (major >= 26) return 25;
        if (major == 1 && (minor > 20 || minor == 20 && patch >= 5)) return 21;
        if (major == 1 && minor >= 17) return 17;
        if (major == 1 && minor >= 12) return 11;
        return 8;
    }
    static Runtimes.Jdk serverJdk(Path harness, String minecraft) throws IOException {
        int java = serverJava(minecraft);
        return java <= 11 ? Runtimes.ensureExact(harness, java) : Runtimes.ensureAtLeast(harness, java);
    }

    Map<String, Object> run(Modaccel.Options options) throws Exception {
        long begin = System.nanoTime();
        PluginCheck plugins = new PluginCheck(build, model);
        PluginCheck.Api api = PluginCheck.api(model);
        if (api == null) return unchecked("server-check runs Paper/Bukkit plugins on a real Paper server today, and this build compiles against no Paper, Spigot or Bukkit API. "
                + "For a mod's dedicated server, mp-check starts it; the bot joining a modded server is not wired up yet.");
        List<Map<String, Object>> projects = plugins.pluginProjects();
        if (projects.isEmpty()) return unchecked("No plugin.yml or paper-plugin.yml in the selected project's resources (src/main/resources), so there is no plugin to load. Select the plugin's module with --project.");
        if (projects.size() > 1) return unchecked("Several projects have a plugin descriptor (" + String.join(", ", projects.stream().map(p -> p.get("path").toString()).toList()) + "). Pick one with --project.");
        Map<String, Object> project = projects.get(0);
        String minecraft = options.get("server-version", minecraft(api.version()));
        Bot direct = bot(build.harness, minecraft), bot = direct != null ? direct : newestBot(build.harness);
        Path scenario = scenario(options);

        // One Gradle run: the plugin's own jar task, and the bot library resolved next to it.
        Path work = build.report.directory.resolve("server-check"); Files.createDirectories(work);
        Path spec = work.resolve("classpath-spec.json"), classpathFile = work.resolve("classpath.json");
        Json.save(spec, Json.map("coordinates", List.of("org.geysermc.mcprotocollib:protocol:" + bot.version()), "repositories", bot.repositories(),
                "output", classpathFile.toString(), "jarTask", options.get("task", "")));
        String path = project.get("path").toString();
        Run.Result gradle = build.gradle(List.of("-Dmodaccel.pluginCheck=" + spec, (path.equals(":") ? "" : path) + ":modaccelPluginClasspath"), "plugin-build");
        if (gradle.exit() != 0 || !Files.isRegularFile(classpathFile)) {
            String reason = "The plugin jar did not build, or the bot library (MCProtocolLib " + bot.version() + ") did not resolve, so no server started. "
                    + Run.gradleFailure(gradle.log(), model) + "\nLog: " + gradle.log();
            return Json.map("ok", false, "tier", "plugin-build", "gameVerified", false, "reason", reason, "details", reason, "log", gradle.log().toString());
        }
        Map<String, Object> resolved = Json.read(classpathFile);
        List<String> classpath = ProjectModel.strings(resolved.get("files"));
        String jar = Json.text(resolved, "jar", "");
        if (jar.isEmpty() || !Files.isRegularFile(Path.of(jar))) return unchecked("The plugin's jar task produced no jar file, so there is nothing to put on the server.");

        // The bot and ViaProxy run on the bot's own Java; the server on its version's Java.
        Runtimes.Jdk jdk = Runtimes.ensureAtLeast(build.harness, serverJava(bot.minecraft())), serverJdk = direct != null ? jdk : serverJdk(build.harness, minecraft);
        String requestedBuild = options.get("server-build", "");
        if (!requestedBuild.isEmpty() && !requestedBuild.matches("[1-9][0-9]*")) throw new IllegalArgumentException("--server-build must be a positive Paper build number.");
        Paper paper = paper(minecraft, serverJdk, options.has("refresh"), direct != null, requestedBuild);
        // Through ViaProxy the bot sees the world in its own version's ids, so they come from that version's Paper.
        Paper names = direct != null ? paper : paper(bot.minecraft(), jdk, false, true);
        Map<String, Object> proxy = direct != null ? null : viaProxy(jdk, minecraft);
        Path tools = tools(jdk, classpath);
        Path serverDir = options.has("fresh-server") ? work.resolve("paper-" + minecraft) : build.project.state().resolve("server-check/paper-" + minecraft);
        Path result = work.resolve("result.json"), checkSpec = work.resolve("check-spec.json");
        String serverXms = options.get("server-xms", "512M");
        if (!serverXms.matches("[1-9][0-9]*[mMgG]")) throw new IllegalArgumentException("--server-xms must be a heap size such as 512M or 2G.");
        List<String> serverCommand = List.of(serverJdk.java().toString(), "-Xms" + serverXms, "-Xmx2G", "-DIReallyKnowWhatIAmDoingISwear=true", "-Dpaper.disableChannelLimit=true",
                // Before 1.13 the server's option parser has no --nogui and prints its help instead; it takes the bare word.
                "-jar", paper.jar().toString(), compare(minecraft, "1.13") < 0 ? "nogui" : "--nogui");
        List<String> extraPlugins = new ArrayList<>();
        if (options.values.containsKey("plugins")) extraPlugins.addAll(pluginPaths(build.project.root, options.get("plugins", "")));
        List<String> serverPlugins = new ArrayList<>(List.of(jar)); serverPlugins.addAll(extraPlugins);
        // .modaccel holds secrets.properties and the server's logs; keep the whole folder out of the plugin's git.
        Path ignore = build.project.root.resolve(".modaccel/.gitignore");
        if (!Files.exists(ignore)) { Files.createDirectories(ignore.getParent()); Files.writeString(ignore, "# Written by modaccel: secrets and test-server files stay out of git.\n*\n"); }
        Json.save(checkSpec, Json.map("root", build.project.root.toString(), "harness", build.harness.toString(), "scenario", scenario.toString(), "result", result.toString(), "logDir", work.toString(),
                "secretsFile", build.project.root.resolve(".modaccel/secrets.properties").toString(), "ids", names.ids().toString(), "lang", names.lang().toString(),
                "botLibrary", "MCProtocolLib " + bot.version() + ", Minecraft " + bot.minecraft() + (proxy == null ? "" : ", through ViaProxy " + proxy.get("version") + " to Minecraft " + minecraft),
                "proxy", proxy,
                "server", Json.map("kind", "paper", "minecraft", minecraft, "dir", serverDir.toString(), "command", serverCommand, "plugins", serverPlugins, "startSeconds", 240)));
        List<String> command = new ArrayList<>(List.of(jdk.java().toString(), "-Xshare:auto", "-Djava.awt.headless=true", "-cp", tools + File.pathSeparator + String.join(File.pathSeparator, classpath),
                "ModaccelServerCheck", checkSpec.toString()));
        Run.Result check = Run.command(command, build.project.root, Map.of(), build.report.log("server-check"), TIMEOUT_SECONDS);
        build.report.stage("server-check", check, Json.map("paper", paper.version() + " build " + paper.build(), "bot", "MCProtocolLib " + bot.version()));
        if (!Files.isRegularFile(result)) {
            String reason = "The server-check JVM stopped before writing a result (exit " + check.exit() + "). " + Run.tail(check.log());
            return Json.map("ok", false, "tier", "server-check", "gameVerified", false, "reason", reason, "details", reason, "log", check.log().toString());
        }
        Map<String, Object> out = Json.read(result);
        out.put("tier", "server-check");
        out.put("server", Json.map("kind", "paper", "minecraft", minecraft, "build", paper.build(), "jar", paper.jar().toString(), "downloaded", paper.downloaded(), "dir", serverDir.toString(), "xms", serverXms, "freshDirectory", options.has("fresh-server")));
        boolean external = Json.array(out.getOrDefault("steps", List.of())).stream().map(Json::object)
                .anyMatch(step -> String.valueOf(step.get("step")).startsWith("expect-process "));
        if (external) Json.object(build.report.stages.get(build.report.stages.size() - 1)).put("bot", "external test program");
        out.put("bot", external ? Json.map("library", "external test program", "version", "provided by scenario", "minecraft", null, "through", null)
                : Json.map("library", "MCProtocolLib", "version", bot.version(), "minecraft", bot.minecraft(),
                "through", proxy == null ? null : Json.map("proxy", "ViaProxy", "version", proxy.get("version"), "targetVersion", proxy.get("targetVersion"))));
        out.put("plugin", Json.map("jar", jar, "jarTask", resolved.get("jarTask")));
        out.put("gameVerified", Boolean.TRUE.equals(out.get("ok")));
        out.put("seconds", (System.nanoTime() - begin) / 1e9);
        out.put("log", check.log().toString());
        String text = text(out, build.project.root);
        out.put("details", text);
        if (!Boolean.TRUE.equals(out.get("ok"))) out.put("reason", text);
        return out;
    }

    static List<String> pluginPaths(Path root, String selection) throws IOException {
        List<String> plugins = new ArrayList<>();
        boolean wholeExists = false;
        try { wholeExists = Files.exists(root.resolve(selection).normalize()); } catch (InvalidPathException ignored) { }
        String[] entries = wholeExists ? new String[] { selection }
                : selection.contains(File.pathSeparator) ? selection.split(Pattern.quote(File.pathSeparator)) : selection.split(",");
        for (String entry : entries) {
            Path location = root.resolve(entry.strip()).normalize();
            if (Files.isRegularFile(location) && location.toString().endsWith(".jar")) plugins.add(location.toAbsolutePath().toString());
            else if (Files.isDirectory(location)) try (var files = Files.list(location)) { files.filter(f -> f.toString().endsWith(".jar")).sorted().forEach(f -> plugins.add(f.toAbsolutePath().toString())); }
            else throw new IllegalArgumentException("--plugins needs a folder or plugin jars separated by " + File.pathSeparator + "; " + location + " is neither.");
        }
        return plugins;
    }

    /** --file, else .modaccel/server-check.scenario, else modaccel/server-check.scenario in the project. */
    Path scenario(Modaccel.Options options) throws IOException {
        Path root = build.project.root;
        if (options.values.containsKey("file")) {
            Path file = root.resolve(options.get("file", "")).normalize();
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("No scenario file at " + file + ".");
            return file;
        }
        for (String candidate : List.of(".modaccel/server-check.scenario", "modaccel/server-check.scenario")) if (Files.isRegularFile(root.resolve(candidate))) return root.resolve(candidate);
        throw new IllegalArgumentException("server-check needs a scenario: pass --file SCENARIO, or put one at .modaccel/server-check.scenario. Run modaccel help server-check for the steps.");
    }

    /** One HTTP call, tried three times: a runner's network can drop for a moment ("No route to host" on macOS CI). */
    static <T> HttpResponse<T> send(HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try { return client.send(request, handler); }
            catch (java.io.IOException e) {
                if (attempt == 3) throw new java.io.IOException(request.uri() + " failed 3 times: " + e.getMessage(), e);
                Thread.sleep(3000L * attempt);
            }
        }
    }
    record Paper(String version, int build, Path jar, Path ids, Path lang, boolean downloaded) { }
    /**
     * The newest Paper build for this Minecraft version from PaperMC's fill API, verified by its SHA-256, cached under
     * ~/.modaccel/servers (shared by every modaccel install, like the JDK cache). The cached build is reused until --refresh asks the API again. Its block and
     * item ids (the server's own data reports) and en_us translations are extracted once beside it, for the bot.
     */
    Paper paper(String minecraft, Runtimes.Jdk jdk, boolean refresh, boolean names) throws Exception {
        return paper(minecraft, jdk, refresh, names, "");
    }
    Paper paper(String minecraft, Runtimes.Jdk jdk, boolean refresh, boolean names, String requestedBuild) throws Exception {
        Path cache = Path.of(System.getProperty("user.home"), ".modaccel", "servers", "paper-" + minecraft).toAbsolutePath();
        Files.createDirectories(cache);
        Path pin = cache.resolve(requestedBuild.isEmpty() ? "build.json" : "build-" + requestedBuild + ".json");
        boolean downloaded = false;
        if (refresh || !Files.isRegularFile(pin) || !Files.isRegularFile(cache.resolve(Json.text(Json.read(pin), "file", "missing")))) {
            long begin = System.nanoTime();
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
            String endpoint = FILL + "/versions/" + minecraft + "/builds/" + (requestedBuild.isEmpty() ? "latest" : requestedBuild);
            HttpResponse<String> answer = send(client, HttpRequest.newBuilder(URI.create(endpoint))
                    .header("User-Agent", "modaccel server-check (github.com/Noisemaker111/modding-accelerator)").timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (answer.statusCode() == 404) throw new IOException("PaperMC has no matching Paper build (" + endpoint + " answered 404). Check --server-version and --server-build.");
            if (answer.statusCode() != 200) throw new IOException("PaperMC's API answered " + answer.statusCode() + " for Paper " + minecraft + ": " + answer.body());
            var latest = Json.object(Json.parse(answer.body()));
            var download = Json.object(Json.object(latest.get("downloads")).get("server:default"));
            String url = Json.text(download, "url", ""), name = Json.text(download, "name", ""), sha = Json.text(Json.object(download.get("checksums")), "sha256", "");
            if (url.isEmpty() || sha.isEmpty() || !name.matches("[A-Za-z0-9_.-]+\\.jar")) throw new IOException("PaperMC's API gave no usable download for Paper " + minecraft + ": " + answer.body());
            int number = ((Number) latest.get("id")).intValue();
            if (!requestedBuild.isEmpty() && !requestedBuild.equals(Integer.toString(number))) throw new IOException("PaperMC returned build " + number + " for requested build " + requestedBuild + ".");
            Path target = cache.resolve(name);
            if (!Files.isRegularFile(target) || !sha256(target).equals(sha)) {
                Path partial = cache.resolve(name + ".part");
                HttpResponse<Path> file = send(client, HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "modaccel server-check").timeout(Duration.ofMinutes(10)).GET().build(), HttpResponse.BodyHandlers.ofFile(partial));
                if (file.statusCode() != 200) throw new IOException("Downloading " + url + " answered " + file.statusCode());
                String got = sha256(partial);
                if (!got.equals(sha)) { Files.deleteIfExists(partial); throw new IOException("Paper " + name + " from " + url + " has SHA-256 " + got + ", not the " + sha + " PaperMC published; it was not used."); }
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
                downloaded = true;
            }
            Json.save(pin, Json.map("minecraft", minecraft, "build", number, "file", name, "url", url, "sha256", sha, "channel", latest.get("channel"), "time", latest.get("time")));
            build.report.stages.add(Json.map("name", "paper-download", "seconds", (System.nanoTime() - begin) / 1e9, "build", number, "file", target.toString(), "downloaded", downloaded, "source", url));
        }
        var pinned = Json.read(pin);
        Path jar = cache.resolve(Json.text(pinned, "file", ""));
        int number = ((Number) pinned.get("build")).intValue();
        Path ids = cache.resolve("ids-" + number + ".json"), lang = cache.resolve("en_us-" + number + ".json");
        if (!names) return new Paper(minecraft, number, jar, null, null, downloaded);
        if (!Files.isRegularFile(ids) || !Files.isRegularFile(lang)) reports(cache, jar, jdk, ids, lang);
        return new Paper(minecraft, number, jar, ids, lang, downloaded);
    }

    /**
     * ViaProxy for a server the bot cannot speak to directly: the pinned jar (SHA-256 checked, cached under
     * ~/.modaccel/tools like Paper) and its own name for the server's version. Throws when ViaProxy cannot reach it.
     */
    Map<String, Object> viaProxy(Runtimes.Jdk jdk, String minecraft) throws Exception {
        var pin = Json.read(build.harness.resolve("knowledge/viaproxy.json"));
        String version = Json.text(pin, "version", ""), file = Json.text(pin, "file", ""), sha = Json.text(pin, "sha256", "");
        Path cache = Path.of(System.getProperty("user.home"), ".modaccel", "tools", "viaproxy-" + version).toAbsolutePath();
        Files.createDirectories(cache);
        Path jar = cache.resolve(file);
        if (!Files.isRegularFile(jar) || !sha256(jar).equals(sha)) {
            long begin = System.nanoTime();
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
            Path partial = cache.resolve(file + ".part");
            HttpResponse<Path> got = send(client, HttpRequest.newBuilder(URI.create(Json.text(pin, "url", ""))).header("User-Agent", "modaccel server-check").timeout(Duration.ofMinutes(10)).GET().build(), HttpResponse.BodyHandlers.ofFile(partial));
            if (got.statusCode() != 200) throw new IOException("Downloading ViaProxy " + version + " answered " + got.statusCode() + " (" + pin.get("url") + ").");
            String actual = sha256(partial);
            if (!actual.equals(sha)) { Files.deleteIfExists(partial); throw new IOException("ViaProxy " + file + " has SHA-256 " + actual + ", not the pinned " + sha + "; it was not used."); }
            Files.move(partial, jar, StandardCopyOption.REPLACE_EXISTING);
            build.report.stages.add(Json.map("name", "viaproxy-download", "seconds", (System.nanoTime() - begin) / 1e9, "file", jar.toString()));
        }
        Path list = cache.resolve("versions.txt");
        if (!Files.isRegularFile(list)) {
            Path run = cache.resolve("run"); Files.createDirectories(run);
            var listed = Run.command(List.of(jdk.java().toString(), "-jar", jar.toString(), "cli", "--list-versions"), run, Map.of("VP_RUN_DIR", run.toString()), build.report.log("viaproxy-versions"), 120);
            List<String> names = new ArrayList<>();
            for (String line : Files.readAllLines(listed.log(), StandardCharsets.UTF_8)) {
                Matcher m = Pattern.compile("\\(ViaProxy\\)\\s+(\\d+\\.\\d+(?:\\.\\d+|\\.x)?(?:-\\d+\\.\\d+(?:\\.\\d+)?)?)\\s*$").matcher(line.replaceAll("\u001B\\[[0-9;]*m", ""));
                if (m.find()) names.add(m.group(1));
            }
            if (names.isEmpty()) throw new IOException("ViaProxy " + version + " listed no versions. " + Run.tail(listed.log()));
            Files.write(list, names, StandardCharsets.UTF_8);
        }
        List<String> names = Files.readAllLines(list, StandardCharsets.UTF_8);
        String target = viaName(minecraft, names);
        if (target == null) throw new IOException("The bot cannot join a Minecraft " + minecraft + " server: ViaProxy " + version + ", which translates for it, reaches "
                + names.get(0) + " to " + names.get(names.size() - 1) + " but has no " + minecraft + ".");
        return Json.map("version", version, "targetVersion", target, "command", List.of(jdk.java().toString(), "-jar", jar.toString(), "cli"),
                "dir", build.project.state().resolve("server-check/viaproxy").toString());
    }

    /** Paperclip patches the server once in the cache; the server's own data generator then writes its block and item ids. */
    void reports(Path cache, Path jar, Runtimes.Jdk jdk, Path ids, Path lang) throws Exception {
        Path work = cache.resolve("reports-work"); Files.createDirectories(work);
        var patch = Run.command(List.of(jdk.java().toString(), "-Dpaperclip.patchonly=true", "-jar", jar.toString()), work, Map.of(), build.report.log("paper-patch"), 300);
        build.report.stage("paper-patch", patch, Map.of());
        if (patch.exit() != 0) throw new IOException("Paper's patcher (paperclip) failed. " + Run.tail(patch.log()));
        Path server;
        try (var found = Files.walk(work.resolve("versions"))) { server = found.filter(f -> f.toString().endsWith(".jar")).findFirst().orElseThrow(() -> new IOException("Paperclip left no server jar in " + work.resolve("versions"))); }
        List<String> classpath = new ArrayList<>(List.of(server.toString()));
        try (var libraries = Files.walk(work.resolve("libraries"))) { libraries.filter(f -> f.toString().endsWith(".jar")).sorted().forEach(f -> classpath.add(f.toString())); }
        Path out = work.resolve("generated");
        var generate = Run.command(List.of(jdk.java().toString(), "-cp", String.join(File.pathSeparator, classpath), "net.minecraft.data.Main", "--reports", "--output", out.toString()),
                work, Map.of(), build.report.log("paper-reports"), 300);
        build.report.stage("paper-reports", generate, Map.of());
        Path blocks = out.resolve("reports/blocks.json"), registries = out.resolve("reports/registries.json");
        if (generate.exit() != 0 || !Files.isRegularFile(blocks) || !Files.isRegularFile(registries))
            throw new IOException("The server's data generator did not write blocks.json and registries.json, so the bot cannot name blocks or items. " + Run.tail(generate.log()));
        Map<String, Object> states = new TreeMap<>(), items = new TreeMap<>();
        for (var block : Json.read(blocks).entrySet()) for (Object s : Json.array(Json.object(block.getValue()).get("states"))) {
            var state = Json.object(s);
            StringBuilder name = new StringBuilder(block.getKey());
            if (state.get("properties") instanceof Map<?, ?> props && !props.isEmpty()) {
                name.append('[');
                boolean first = true;
                for (var p : new TreeMap<>(Json.object(props)).entrySet()) { if (!first) name.append(','); name.append(p.getKey()).append('=').append(p.getValue()); first = false; }
                name.append(']');
            }
            states.put(String.valueOf(((Number) state.get("id")).intValue()), name.toString());
        }
        var registry = Json.read(registries);
        for (var item : Json.object(Json.object(registry.get("minecraft:item")).get("entries")).entrySet())
            items.put(String.valueOf(((Number) Json.object(item.getValue()).get("protocol_id")).intValue()), item.getKey());
        int biomes = 64;
        Files.createDirectories(ids.getParent());
        Json.save(ids, Json.map("blocks", states, "items", items, "biomes", biomes));
        try (ZipFile zip = new ZipFile(server.toFile())) {
            ZipEntry entry = zip.getEntry("assets/minecraft/lang/en_us.json");
            if (entry == null) Files.writeString(lang, "{}");
            else try (InputStream in = zip.getInputStream(entry)) { Files.copy(in, lang, StandardCopyOption.REPLACE_EXISTING); }
        }
        deleteTree(work);
    }
    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) { for (Path q : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(q); }
    }
    static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) { byte[] b = new byte[1 << 16]; for (int n; (n = in.read(b)) > 0; ) digest.update(b, 0, n); }
        return HexFormat.of().formatHex(digest.digest());
    }

    Path tools(Runtimes.Jdk jdk, List<String> classpath) throws Exception {
        List<Path> sources = new ArrayList<>(); for (String name : SOURCES) sources.add(build.harness.resolve(name));
        String key = Fingerprints.bytes((Fingerprints.files(sources) + "|" + String.join("|", classpath) + "|" + jdk.home()).getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        Path directory = build.project.state().resolve("server-check-tools/" + key);
        if (Files.exists(directory.resolve("ready"))) return directory.resolve("classes");
        Path classes = directory.resolve("classes"); Files.createDirectories(classes);
        List<String> command = new ArrayList<>(List.of(jdk.home().resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString(), "--release", "21",
                "-nowarn", "-encoding", "UTF-8", "-cp", String.join(File.pathSeparator, classpath), "-d", classes.toString()));
        sources.forEach(p -> command.add(p.toString()));
        var compiled = Run.command(command, build.project.root, Map.of(), build.report.log("server-check-tools"), 120);
        build.report.stage("server-check-tools", compiled, Map.of());
        if (compiled.exit() != 0) throw new IOException("server-check's bot did not compile against this MCProtocolLib. " + Run.tail(compiled.log()));
        Files.writeString(directory.resolve("ready"), "ok");
        return classes;
    }

    Map<String, Object> unchecked(String reason) {
        return Json.map("ok", false, "tier", "server-unchecked", "checked", false, "gameVerified", false, "reason", reason, "details", reason);
    }

    /** One line per step, the failure in words, then the times: server start, scenario, stop. */
    static String text(Map<String, Object> out, Path root) {
        StringBuilder b = new StringBuilder();
        var server = Json.object(out.getOrDefault("server", Json.map()));
        var bot = Json.object(out.getOrDefault("bot", Json.map()));
        boolean ok = Boolean.TRUE.equals(out.get("ok"));
        b.append(ok ? "server-check passed" : "server-check FAILED").append(" on Paper ").append(server.get("minecraft")).append(" build ").append(server.get("build"))
                .append(", bot ").append(bot.getOrDefault("library", "MCProtocolLib")).append(" ").append(bot.get("version"));
        if (bot.get("through") instanceof Map<?, ?> through) b.append(" (Minecraft ").append(bot.get("minecraft")).append(") through ViaProxy ").append(Json.object(through).get("version"));
        b.append(".\n");
        double serverStart = 0, scenario = 0;
        for (Object s : Json.array(out.get("steps"))) {
            var step = Json.object(s);
            String status = String.valueOf(step.getOrDefault("status", "not run"));
            double seconds = step.get("seconds") instanceof Number n ? n.doubleValue() : 0;
            if (step.get("serverStart") instanceof Map<?, ?> start && Json.object(start).get("seconds") instanceof Number n) { double startSeconds = n.doubleValue(); serverStart += startSeconds; seconds -= startSeconds; }
            for (Object restart : Json.array(step.getOrDefault("serverRestarts", List.of()))) if (Json.object(restart).get("seconds") instanceof Number n) { serverStart += n.doubleValue(); seconds -= n.doubleValue(); }
            scenario += seconds;
            b.append(String.format(Locale.ROOT, "  %-4s %3s  %-60s %5.1fs%n", status.equals("pass") ? "PASS" : status.equals("fail") ? "FAIL" : status.equals("done") ? "  ok" : "----", step.get("line"), clip(String.valueOf(step.get("step")), 60), seconds));
        }
        if (!ok && out.get("reason") != null) b.append("  ").append(String.valueOf(out.get("reason")).replace("\n", "\n  ")).append('\n');
        b.append(String.format(Locale.ROOT, "Times: server start %.1fs, scenario %.1fs%s.", serverStart, scenario,
                out.get("serverStopSeconds") instanceof Number n ? String.format(Locale.ROOT, ", stop %.1fs", n.doubleValue()) : ""));
        if (Boolean.TRUE.equals(server.get("downloaded"))) b.append(" Paper was downloaded this run.");
        if (out.get("serverLog") != null) b.append("\nServer log: ").append(out.get("serverLog"));
        b.append("\nScenario: ").append(PluginCheck.relative(root, String.valueOf(out.get("scenario"))));
        return b.toString();
    }
    static String clip(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 3) + "..."; }
}
