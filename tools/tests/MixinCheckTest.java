import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;

/**
 * The Mixin check's own logic, without a game: how Mixin's reports are sorted into a verdict, how a
 * failure is traced to its handler, how a recorded launch is read back, and the NeoForge launch-target
 * edit. The check's runtime classes are compiled for Java 8 here, as modaccel compiles them for a mod.
 * Usage: MixinCheckTest <harness root>
 */
public final class MixinCheckTest {
    static int count;
    public static void main(String[] args) throws Exception {
        Path harness = Path.of(args[0]), scratch = Files.createTempDirectory("modaccel-mixincheck");
        try {
            verdicts();
            handlerNames();
            argumentFiles(scratch);
            jvmRecords(scratch);
            neoForgeTarget(harness, scratch);
            System.out.println(count + " mixin-check checks passed: verdict sorting, missing configs, handler names, argfiles, JVM records, Java 8 build, NeoForge target edit");
        } finally {
            try (var paths = Files.walk(scratch)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }

    static Map<String, Object> side(List<Map<String, Object>> events) {
        List<Object> all = new ArrayList<>(); all.add(Json.map("event", "start", "mixin", "0.8.7"));
        all.add(Json.map("event", "config", "name", "mod.mixins.json")); all.addAll(events); all.add(Json.map("event", "done", "targets", 3));
        return Json.map("side", "client", "exit", 0, "seconds", 1.0, "log", "client.log", "events", all);
    }
    static Map<String, Object> mods() { return Json.map("configs", List.of("mod.mixins.json")); }
    static Map<String, Object> verdict(List<Map<String, Object>> events) throws Exception {
        return new MixinCheck(null, Json.map("projects", List.of())).verdict(List.of(side(events)), "fabric", mods());
    }
    static Map<String, Object> error(String message, String action, Integer required, Integer injected, Boolean requireDeclared, String config) {
        Map<String, Object> event = Json.map("event", "error", "phase", "apply", "mixin", "a.B", "target", "net.minecraft.X", "config", config, "action", action, "message", message);
        if (required != null) event.put("required", required);
        if (injected != null) event.put("injected", injected);
        if (requireDeclared != null) event.put("requireDeclared", requireDeclared);
        return event;
    }
    static int number(Map<String, Object> result, String key) { return ((Number) Json.object(result.get("mixins")).get(key)).intValue(); }

    /** What the game would do with each kind of Mixin report. */
    static void verdicts() throws Exception {
        var clean = verdict(List.of(Json.map("event", "mixin", "mixin", "a.B", "applied", true)));
        require(Boolean.TRUE.equals(clean.get("ok")) && number(clean, "applied") == 1, "every mixin applied is a pass: " + clean.get("details"));

        var critical = verdict(List.of(error("Critical injection failure: @Inject annotation on foo could not find any targets", "ERROR", 1, 0, false, "mod.mixins.json")));
        require(Boolean.FALSE.equals(critical.get("ok")) && number(critical, "wouldStopGame") == 1, "fewer than require stops the game");

        var dead = verdict(List.of(error("Injection validation failed: expected 2 invocation(s) but 1 succeeded", "ERROR", 1, 1, false, "mod.mixins.json")));
        require(Boolean.FALSE.equals(dead.get("ok")) && number(dead, "dead") == 1 && number(dead, "wouldStopGame") == 0, "fewer than expected but enough for require is dead, not fatal");

        var optional = verdict(List.of(error("Injection validation failed: expected 1 invocation(s) but 0 succeeded", "ERROR", 0, 0, true, "mod.mixins.json")));
        require(Boolean.TRUE.equals(optional.get("ok")) && number(optional, "optionalUnmatched") == 1, "require = 0 written by the mod is allowed");

        var undeclared = verdict(List.of(error("Injection validation failed: expected 1 invocation(s) but 0 succeeded", "ERROR", 0, 0, false, "mod.mixins.json")));
        require(Boolean.FALSE.equals(undeclared.get("ok")) && number(undeclared, "dead") == 1, "require = 0 from the config default is still dead");

        var other = verdict(List.of(error("Critical injection failure", "ERROR", 1, 0, false, "fabric-api.mixins.json")));
        require(Boolean.TRUE.equals(other.get("ok")) && Json.array(other.get("otherMods")).size() == 1, "another mod's failure is reported apart, not as ours");

        var target = verdict(List.of(Json.map("event", "target", "target", "net.minecraft.X", "loaded", false, "message", "NoClassDefFoundError")));
        require(Boolean.FALSE.equals(target.get("ok")), "a target class that fails to load would stop the game");

        var loader = verdict(List.of(Json.map("event", "error", "phase", "loader", "message", "boom")));
        require(Boolean.FALSE.equals(loader.get("ok")) && Boolean.FALSE.equals(loader.get("checked")), "a loader that did not start is unchecked, never a pass");

        var missing = new MixinCheck(null, Json.map("projects", List.of())).verdict(List.of(Json.map("side", "client", "exit", 0, "seconds", 1.0, "log", "l",
                "events", List.of(Json.map("event", "start"), Json.map("event", "done", "targets", 0)))), "neoforge", mods());
        require(Boolean.FALSE.equals(missing.get("ok")) && String.valueOf(missing.get("details")).contains("never loaded the mod's config mod.mixins.json"),
                "a declared config Mixin never loaded is not a pass: " + missing.get("details"));

        var cut = new MixinCheck(null, Json.map("projects", List.of())).verdict(List.of(Json.map("side", "client", "exit", 137, "seconds", 1.0, "log", "l",
                "events", List.of(Json.map("event", "start"), Json.map("event", "config", "name", "mod.mixins.json")))), "fabric", mods());
        require(Boolean.FALSE.equals(cut.get("ok")), "a run that never finished is not a pass");
    }

    static void handlerNames() {
        require("renderFoodPre".equals(MixinCheck.handlerName("Critical injection failure: @Inject annotation on renderFoodPre could not find any targets")), "annotation on NAME");
        require("diorama$gpuBakes".equals(MixinCheck.handlerName("@Inject annotation on diorama$gpuBakes could not find")), "handler names may hold $");
        require("onTick".equals(MixinCheck.handlerName("in handler$zza000$mymod$onTick(Lnet/X;)V")), "merged handler name");
        require(MixinCheck.handlerName("nothing here") == null, "no handler named");
    }

    static void argumentFiles(Path scratch) throws Exception {
        Path file = scratch.resolve("args.txt");
        Files.writeString(file, "-cp\n\"C:\\\\a b\\\\x.jar;y.jar\"\n-Dk=v # comment\n'single quoted'\n", StandardCharsets.UTF_8);
        List<String> tokens = MixinCheck.argumentFile(file);
        require(tokens.equals(List.of("-cp", "C:\\a b\\x.jar;y.jar", "-Dk=v", "single quoted")), "argfile tokens as the java launcher reads them: " + tokens);
    }

    static void jvmRecords(Path scratch) throws Exception {
        Map<String, Object> jvm = Json.map("java", scratch.toString(), "directory", scratch.toString(), "classPath", "a.jar", "modulePath", "m.jar",
                "command", "cpw.mods.bootstraplauncher.BootstrapLauncher --launchTarget forgeclientuserdev --fml.mcVersion 1.21.1",
                "options", List.of("-Djdk.module.addmods=ALL-MODULE-PATH", "-Djdk.module.addopens=java.base/java.lang=ALL-UNNAMED", "-javaagent:recorder.jar=x.json", "-Dmodaccel.agent=y", "-Xmx2G", "-Dfoo=bar"));
        var record = MixinCheck.fromJvm(jvm, scratch.resolve("launch.json"));
        List<String> options = ProjectModel.strings(record.get("options"));
        require(options.containsAll(List.of("--module-path", "m.jar", "--add-modules", "ALL-MODULE-PATH", "--add-opens", "java.base/java.lang=ALL-UNNAMED", "-Xmx2G", "-Dfoo=bar")),
                "module options come back as launcher options: " + options);
        require(options.stream().noneMatch(o -> o.contains("recorder.jar") || o.startsWith("-Dmodaccel.") || o.startsWith("-Djdk.module.")), "the recorder and reserved properties are dropped: " + options);
        require("cpw.mods.bootstraplauncher.BootstrapLauncher".equals(record.get("main")) && ProjectModel.strings(record.get("arguments")).contains("--fml.mcVersion"), "main class and arguments are kept");
    }

    /** The check's classes build for Java 8, and the NeoForge target edit renames the side and empties runService. */
    static void neoForgeTarget(Path harness, Path scratch) throws Exception {
        var javac = ToolProvider.getSystemJavaCompiler();
        Path stubs = scratch.resolve("stubs"), classes = scratch.resolve("classes"); Files.createDirectories(stubs); Files.createDirectories(classes);
        List<String> stubArgs = new ArrayList<>(List.of("--release", "8", "-Xlint:-options", "-encoding", "UTF-8", "-d", stubs.toString()));
        for (String name : MixinCheck.STUBS) stubArgs.add(harness.resolve(name).toString());
        require(javac.run(null, null, null, stubArgs.toArray(String[]::new)) == 0, "Mixin API stubs compile");
        List<String> sourceArgs = new ArrayList<>(List.of("--release", "8", "-Xlint:-options", "-encoding", "UTF-8", "-cp", stubs.toString(), "-d", classes.toString()));
        for (String name : MixinCheck.SOURCES) sourceArgs.add(harness.resolve(name).toString());
        require(javac.run(null, null, null, sourceArgs.toArray(String[]::new)) == 0, "the check's runtime classes compile for Java 8");

        Path fake = scratch.resolve("fake/net/neoforged/fml/loading/targets"); Files.createDirectories(fake);
        Files.writeString(fake.resolve("FakeDist.java"), "package net.neoforged.fml.loading.targets;\npublic class FakeDist { public static final String DEDICATED_SERVER = new String(\"server\"), CLIENT = new String(\"client\"); }\n");
        Files.writeString(fake.resolve("JUnitFakeTarget.java"), "package net.neoforged.fml.loading.targets;\npublic class JUnitFakeTarget {\n"
                + "  public String getDist() { return FakeDist.DEDICATED_SERVER; }\n"
                + "  protected void runService(String[] arguments, ModuleLayer layer) throws Throwable { if (arguments.length >= 0) throw new IllegalStateException(\"ran\"); System.out.println(layer); }\n}\n");
        Path fakeClasses = scratch.resolve("fake-classes"); Files.createDirectories(fakeClasses);
        require(javac.run(null, null, null, "-d", fakeClasses.toString(), fake.resolve("FakeDist.java").toString(), fake.resolve("JUnitFakeTarget.java").toString()) == 0, "fake launch target compiles");
        byte[] original = Files.readAllBytes(fakeClasses.resolve("net/neoforged/fml/loading/targets/JUnitFakeTarget.class"));
        try (var loader = new java.net.URLClassLoader(new java.net.URL[] {classes.toUri().toURL(), fakeClasses.toUri().toURL()}, MixinCheckTest.class.getClassLoader())) {
            Class<?> agent = loader.loadClass("ModaccelNeoForgeTarget");
            var patch = agent.getDeclaredMethod("patch", byte[].class, boolean.class); patch.setAccessible(true);
            for (boolean client : new boolean[] {true, false}) {
                byte[] bytes = (byte[]) patch.invoke(null, original, client);
                Class<?> target = new ClassLoader(loader) {
                    Class<?> define() { return defineClass("net.neoforged.fml.loading.targets.JUnitFakeTarget", bytes, 0, bytes.length); }
                }.define();
                Object instance = target.getConstructor().newInstance();
                Object dist = target.getMethod("getDist").invoke(instance);
                require(dist.equals(client ? "client" : "server"), "getDist answers " + (client ? "CLIENT" : "DEDICATED_SERVER") + " after the edit: " + dist);
                var run = target.getDeclaredMethod("runService", String[].class, ModuleLayer.class); run.setAccessible(true);
                run.invoke(instance, new String[0], ModuleLayer.boot()); // throws "ran" if the body was kept
                count++;
            }
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        count++;
    }
}
