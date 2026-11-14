import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import com.sun.tools.attach.*;

/** Local profiler entry point. Attaches only to the selected process and owns only its recording. */
final class Performance {
    static Map<String, Object> run(Path home, Modaccel.Options options, Modaccel.Report receipt) throws Exception {
        if (options.command.equals("perf-template")) return PerfTemplates.run(options);
        if (options.command.equals("perf-diff")) return PerfDiff.run(options);
        if (options.command.equals("perf-report")) {
            Path input = Path.of(options.get("file", "")).toAbsolutePath();
            return PerfReport.write(input, output(options));
        }
        if (options.has("list")) return Json.map("processes", processes(), "ok", true);
        if (options.values.containsKey("scenario")) return PerfScenario.run(home, options, receipt);
        Path out = output(options);
        Path agent = agent(home, receipt);
        int seconds = integer(options, "duration", 30, 1, 3600);
        int warmup = integer(options, "warmup", 5, 0, 600);
        Path request = request(out, seconds, warmup, options.has("no-hooks"));
        probes(request, options);
        stamp(request, agent);
        if (options.has("prepare")) {
            return Json.map("ok", true, "agent", agent.toString(), "request", request.toString(),
                "jvmArgument", "-javaagent:" + agent + "=" + request,
                "details", "Add the JVM argument in your launcher. After the capture completes, run perf-report --file \"" + out + "\".");
        }
        String pid = select(home, options, receipt);
        capture(pid, agent, request, out, seconds, warmup, !options.has("json"));
        String instance = options.get("instance", properties(out.resolve("environment.properties")).getProperty("user.dir", ""));
        if (!instance.isBlank()) Json.save(out.resolve("inventory.json"), inventory(Path.of(instance)));
        Map<String, Object> result = PerfReport.write(out, out);
        result.put("pid", pid);
        return result;
    }

    static String text(Map<String, Object> result) {
        if (result.containsKey("processes")) {
            StringBuilder text = new StringBuilder("PID       Main class (game candidates marked *)\n");
            for (Object entry : Json.array(result.get("processes"))) {
                Map<String, Object> process = Json.object(entry);
                text.append(String.format("%-9s %s %s%n", process.get("pid"),
                    Boolean.TRUE.equals(process.get("gameCandidate")) ? "*" : " ", process.get("main")));
            }
            return text.toString();
        }
        String text = Json.text(result, "details", "");
        for (String key : List.of("jvmArgument", "scenario", "html", "warning")) {
            if (result.containsKey(key)) text += "\n" + key + ": " + result.get(key);
        }
        return text;
    }

    static void stamp(Path request, Path agent) throws IOException {
        Properties properties = properties(request);
        properties.setProperty("agentBuild", agent.getParent().getFileName().toString());
        try (Writer writer = Files.newBufferedWriter(request)) { properties.store(writer, "Mod Accel capture request"); }
    }

    static String sha256(Path file) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    static Path output(Modaccel.Options options) throws IOException {
        Path path = options.values.containsKey("out") ? Path.of(options.get("out", ""))
            : Path.of(".runs/performance", Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID().toString().substring(0, 8));
        path = path.toAbsolutePath().normalize();
        Files.createDirectories(path);
        return path;
    }

    static int integer(Modaccel.Options options, String name, int fallback, int min, int max) {
        int value = Integer.parseInt(options.get(name, Integer.toString(fallback)));
        if (value < min || value > max) throw new IllegalArgumentException("--" + name + " must be " + min + ".." + max);
        return value;
    }

    static List<Object> processes() {
        return VirtualMachine.list().stream().filter(v -> !v.id().equals(Long.toString(ProcessHandle.current().pid())))
            .map(v -> (Object) Json.map("pid", v.id(), "main", v.displayName().split(" ", 2)[0],
                "gameCandidate", candidate(v.displayName()))).toList();
    }

    static boolean candidate(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        return List.of("minecraft", "fabricmc", "modlauncher", "bootstraplauncher", "launchwrapper", "quiltmc",
            "paperclip", "craftbukkit", "prismlauncher", "multimc", "polymc")
            .stream().anyMatch(value::contains);
    }

    static String select(Path home, Modaccel.Options options, Modaccel.Report receipt) throws Exception {
        long selectors = List.of("attach", "root", "instance", "launch").stream().filter(options.values::containsKey).count();
        if (selectors > 1) throw new IllegalArgumentException("Choose just one of --attach, --root, --instance or --launch");
        if (options.values.containsKey("launch")) return launch(options, receipt);
        if (options.values.containsKey("attach")) {
            String pid = options.get("attach", "");
            if (!pid.matches("[1-9][0-9]*")) throw new IllegalArgumentException("--attach requires a PID from perf --list");
            if (pid.equals(Long.toString(ProcessHandle.current().pid()))) throw new IllegalArgumentException("Cannot profile the profiler itself");
            return pid;
        }
        if (options.values.containsKey("root")) {
            ProjectModel project = new ProjectModel(Path.of(options.get("root", ".")));
            project.requireGradleBuild();
            try (ProjectLock ignored = ProjectLock.acquire(project, "perf")) {
                Build build = new Build(project, home, options.get("project", ""), receipt);
                Game game = new Game(build, Json.map());
                Map<String, Object> session = game.session();
                if (session == null) {
                    game = new Game(build, build.model(options.has("refresh"), false));
                    session = game.start(options, game.fingerprint());
                }
                if (Boolean.TRUE.equals(session.get("background"))) throw new IOException(
                    "This source session is throttled/hidden. Run game-stop for this project, then perf --root again to launch visibly.");
                return session.get("pid").toString();
            }
        }
        List<String> matching = new ArrayList<>();
        Path instance = options.values.containsKey("instance") ? Path.of(options.get("instance", "")).toRealPath() : null;
        for (VirtualMachineDescriptor process : VirtualMachine.list()) {
            if (instance == null && !candidate(process.displayName())) continue;
            if (instance != null) {
                VirtualMachine vm = null;
                try {
                    vm = VirtualMachine.attach(process.id());
                    Path directory = Path.of(vm.getSystemProperties().getProperty("user.dir")).toRealPath();
                    if (!directory.startsWith(instance)) continue;
                } catch (Exception unavailable) { continue; }
                finally { if (vm != null) vm.detach(); }
            }
            matching.add(process.id());
        }
        if (matching.size() != 1) throw new IOException(
            "Found " + matching.size() + " matching running games. Start your game/server normally, then run perf --list and perf --attach PID.");
        return matching.get(0);
    }

    static String launch(Modaccel.Options options, Modaccel.Report receipt) throws Exception {
        Path file = Path.of(options.get("launch", "")).toAbsolutePath();
        Map<String, Object> config = Json.read(file);
        List<String> command = ProjectModel.strings(config.get("command"));
        if (command.isEmpty()) throw new IllegalArgumentException("Launch file needs a command argument array");
        String executable = Path.of(command.get(0)).getFileName().toString();
        if (!Set.of("java", "java.exe").contains(executable)) throw new IllegalArgumentException("Launch command must start with java, not a shell");
        Path directory = file.getParent().resolve(Json.text(config, "directory", ".")).normalize();
        String ready = Json.text(config, "readyText", "");
        if (ready.isBlank()) throw new IllegalArgumentException("Launch file needs readyText to identify successful game startup");
        int timeout = integer(options, "timeout", 180, 1, 900);
        Path log = receipt.directory.resolve("perf-launch.log");
        Files.createDirectories(log.getParent());
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean succeeded = false;
        try {
            long deadline = System.nanoTime() + timeout * 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) throw new IOException("Game launch exited: " + Run.tail(log));
                if (Files.exists(log) && Run.tail(log).contains(ready)) {
                    succeeded = true;
                    Json.save(receipt.directory.resolve("launched-process.json"), Json.map("pid", process.pid(), "log", log.toString()));
                    return Long.toString(process.pid());
                }
                Thread.sleep(500);
            }
            throw new IOException("Game did not print readyText within " + timeout + " seconds: " + log);
        } finally {
            if (!succeeded) {
                process.destroy();
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
            }
        }
    }

    static Path request(Path out, int seconds, int warmup, boolean noHooks) throws IOException {
        if (Files.exists(out.resolve("request.properties")) || Files.exists(out.resolve("capture.jfr"))) {
            throw new IOException("Capture output is already in use; choose a fresh --out directory: " + out);
        }
        Properties properties = new Properties();
        properties.setProperty("output", out.toString());
        properties.setProperty("seconds", Integer.toString(seconds));
        properties.setProperty("warmup", Integer.toString(warmup));
        properties.setProperty("hooks", noHooks ? "off" : "on");
        Path file = out.resolve("request.properties");
        try (Writer writer = Files.newBufferedWriter(file)) { properties.store(writer, "Mod Accel capture request"); }
        return file;
    }

    static void probes(Path request, Modaccel.Options options) throws Exception {
        if (!options.values.containsKey("probes")) return;
        if (options.has("no-hooks")) throw new IllegalArgumentException("--probes cannot be used with --no-hooks");
        List<Object> probes = Json.array(Json.read(Path.of(options.get("probes", ""))).get("probes"));
        if (probes.size() > 32) throw new IllegalArgumentException("At most 32 explicit method probes per capture");
        Properties properties = properties(request);
        Set<String> labels = new HashSet<>();
        int index = 0;
        for (Object item : probes) {
            Map<String, Object> probe = Json.object(item);
            String owner = Json.text(probe, "class", "").replace('.', '/');
            String method = Json.text(probe, "method", "");
            String descriptor = Json.text(probe, "descriptor", "");
            String label = Json.text(probe, "label", "");
            if (!owner.matches("[A-Za-z0-9_$/]+") || owner.startsWith("java/") || owner.startsWith("jdk/") ||
                owner.startsWith("dev/modaccel/") || owner.startsWith("sun/") || owner.startsWith("com/sun/") || !method.matches("[A-Za-z0-9_$]+") ||
                !descriptor.startsWith("(") || descriptor.contains("|") || !label.matches("[A-Za-z0-9_.-]+") || !labels.add(label)) {
                throw new IllegalArgumentException("Invalid probe; use exact class, method, JVM descriptor and unique plain label");
            }
            properties.setProperty("probe." + index++, owner + "|" + method + "|" + descriptor + "|" + label);
        }
        try (Writer writer = Files.newBufferedWriter(request)) { properties.store(writer, "Mod Accel capture request"); }
    }

    static void capture(String pid, Path agent, Path request, Path out, int seconds, int warmup, boolean progress) throws Exception {
        stamp(request, agent);
        VirtualMachine vm = VirtualMachine.attach(pid);
        try {
            Properties props = vm.getSystemProperties();
            if (Boolean.parseBoolean(props.getProperty("modaccel.background", "false"))) {
                throw new IOException("Refusing a throttled hidden game. Restart visibly for representative frame timing.");
            }
            vm.loadAgent(agent.toString(), request.toString());
        } finally { vm.detach(); }
        long deadline = System.nanoTime() + (seconds + warmup + 90L) * 1_000_000_000;
        String previous = "";
        while (System.nanoTime() < deadline) {
            Properties status = properties(out.resolve("status.properties"));
            String state = status.getProperty("state", "attaching");
            if (!state.equals(previous) && progress) System.err.println("perf: " + state + " — " + out);
            previous = state;
            if (state.equals("complete")) return;
            if (state.equals("failed")) throw new IOException(status.getProperty("error"));
            if (ProcessHandle.of(Long.parseLong(pid)).filter(Run::running).isEmpty()) {
                throw new IOException("Target exited before capture completed. Partial files: " + out);
            }
            Thread.sleep(250);
        }
        throw new IOException("Capture did not finish in time. The bounded agent may still be saving; inspect " + out);
    }

    static Properties properties(Path path) throws IOException {
        Properties properties = new Properties();
        if (Files.isRegularFile(path)) try (Reader in = Files.newBufferedReader(path)) { properties.load(in); }
        return properties;
    }

    static Map<String, Object> inventory(Path instance) throws Exception {
        Path mods = Files.isDirectory(instance.resolve("mods")) ? instance.resolve("mods") : instance.resolve(".minecraft/mods");
        List<Object> jars = new ArrayList<>();
        if (Files.isDirectory(mods)) try (var files = Files.list(mods)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                Map<String, Object> entry = Json.map("file", file.getFileName().toString(),
                    "sha256", sha256(file), "bytes", Files.size(file));
                try (JarFile jar = new JarFile(file.toFile())) {
                    JarEntry metadata = jar.getJarEntry("fabric.mod.json");
                    if (metadata != null && metadata.getSize() < 1_048_576) try (InputStream in = jar.getInputStream(metadata)) {
                        Map<String, Object> data = Json.object(Json.parse(new String(in.readNBytes(1_048_576), java.nio.charset.StandardCharsets.UTF_8)));
                        entry.put("id", data.get("id"));
                        entry.put("version", data.get("version"));
                    }
                } catch (Exception error) { entry.put("metadataError", error.toString()); }
                jars.add(entry);
            }
        }
        return Json.map("jars", jars, "meaning", "Installed top-level jars; not proof that every jar loaded. Nested jars remain separate attribution limitations.");
    }

    static Path agent(Path home, Modaccel.Report receipt) throws Exception {
        Path source = home.resolve("tools/perf/PerfAgent.java");
        Path asm = home.resolve("tools/perf/lib/asm-9.9.jar");
        if (!Fingerprints.bytes(Files.readAllBytes(asm)).equals("03d99a74ad1ee5c71334ef67437f4ef4fe3488caa7c96d8645abc73c8e2017d4")) {
            throw new IOException("Bundled ASM checksum mismatch");
        }
        String hash = Fingerprints.files(List.of(source, asm, home.resolve("tools/cli/Relocate.java")));
        Path dir = home.resolve(".modaccel/perf-agent/" + hash);
        Path target = dir.resolve("modaccel-perf-agent.jar");
        if (Files.isRegularFile(target)) return target;
        Files.createDirectories(dir);
        Path classes = Files.createTempDirectory(dir, "classes-");
        Path compiledSource = classes.resolve("PerfAgent.java");
        Files.writeString(compiledSource, Files.readString(source).replace("@BUILD_ID@", hash));
        Path jdk = Runtimes.ensureAtLeast(home, 17).home();
        Run.Result compile = Run.command(List.of(jdk.resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString(),
            "--release", "17", "-cp", asm.toString(), "-d", classes.toString(), compiledSource.toString()),
            home, Map.of(), receipt.log("perf-agent-compile"), 120);
        receipt.stage("perf-agent-compile", compile, Map.of());
        if (compile.exit() != 0) throw new IOException(Run.tail(compile.log()));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Agent-Class", "dev.modaccel.perf.PerfAgent");
        manifest.getMainAttributes().putValue("Premain-Class", "dev.modaccel.perf.PerfAgent");
        manifest.getMainAttributes().putValue("Can-Retransform-Classes", "true");
        Path temp = Files.createTempFile(dir, "agent-", ".jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(temp), manifest);
             var walk = Files.walk(classes); ZipFile library = new ZipFile(asm.toFile())) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                jar.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                jar.write(Relocate.classes(Files.readAllBytes(file)));
                jar.closeEntry();
            }
            for (ZipEntry entry : Collections.list(library.entries())) {
                if (!entry.getName().startsWith("org/objectweb/asm/") || !entry.getName().endsWith(".class")) continue;
                jar.putNextEntry(new JarEntry(Relocate.name(entry.getName())));
                try (InputStream in = library.getInputStream(entry)) { jar.write(Relocate.classes(in.readAllBytes())); }
                jar.closeEntry();
            }
            jar.putNextEntry(new JarEntry("ASM-LICENSE.txt"));
            Files.copy(home.resolve("tools/perf/lib/ASM-LICENSE.txt"), jar);
            jar.closeEntry();
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
