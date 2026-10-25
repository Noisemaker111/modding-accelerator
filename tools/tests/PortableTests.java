import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Outcome checks: stale bytes, empty test reports, rollback conflicts, and unrelated checkouts. */
public final class PortableTests {
    static int count;
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); count++; System.out.println("PASS " + message); }
    public static void main(String[] args) throws Exception {
        Path home = Path.of(args[0]), scratch = Path.of(args[1]); Files.createDirectories(scratch);
        Path existingArgs = scratch.resolve("existing loom args.txt");
        Files.writeString(existingArgs, "-Dmodaccel.launchProbe=existing-file\n");
        String classpath = System.getProperty("java.class.path") + (File.pathSeparator + scratch.resolve("nonexistent # ' path")).repeat(1100);
        List<String> launch = List.of(Path.of(System.getProperty("java.home"), "bin", Runtimes.WINDOWS ? "java.exe" : "java").toString(),
                "@" + existingArgs.toAbsolutePath(), "-cp", classpath, "PortableTests$LaunchProbe", "@literal", "spaces and slash\\");
        List<String> saved = Game.savedCommand(launch, scratch.resolve("saved classpath.args"));
        Process child = new ProcessBuilder(saved).redirectErrorStream(true).start();
        String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        check(child.waitFor() == 0 && output.contains("LAUNCH_OK"), "saved launches handle classpaths beyond Windows limits, existing @files and literal program arguments: " + output.strip());
        check(saved.stream().mapToInt(String::length).sum() < 8000 && saved.contains(launch.get(1)), "saved launches keep existing argument files at the top level");
        Object json = Json.parse(Json.write(Json.map("unicode", "mod 🧱", "path", "C:\\mods with spaces\\test", "rows", List.of(1, true, "\n\""))));
        check(Json.object(json).get("unicode").equals("mod 🧱"), "structured results preserve Unicode and quoted paths");
        Path dark = scratch.resolve("frame dark.png"), half = scratch.resolve("frame half.png");
        BufferedImage left = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), right = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 5; y++) for (int x = 0; x < 10; x++) right.setRGB(x, y, 0xFFFFFF);
        ImageIO.write(left, "png", dark.toFile()); ImageIO.write(right, "png", half.toFile());
        check(Boolean.TRUE.equals(Game.compare(dark, half, 40).get("passed")), "visual gate accepts a real frame change (50% vs 40% required)");
        try { Game.compare(dark, half, 60); throw new AssertionError("small change accepted"); }
        catch (IOException expected) { check(expected.getMessage().contains("no visible result"), "visual gate rejects an invisible result (50% vs 60% required)"); }
        try { Game.compare(dark, dark, 1); throw new AssertionError("identical frames accepted"); }
        catch (IOException expected) { check(expected.getMessage().contains("only 0.000%"), "identical frames are reported as no visible change"); }
        Map<String, Object> positionA = Json.map("x", 1.5, "y", 64.0, "z", -3.5), positionB = Json.map("x", 1.5, "y", 64.0, "z", -3.5), positionC = Json.map("x", 1.56, "y", 64.0, "z", -3.5);
        check(Game.samePosition(positionA, positionB, 0.05), "save round-trip accepts an unchanged position");
        check(!Game.samePosition(positionA, positionC, 0.05), "save round-trip rejects a moved position beyond tolerance");
        try { Json.parse("{\"ok\":true,\"ok\":false}"); throw new AssertionError("duplicate accepted"); } catch (IllegalArgumentException expected) { count++; }
        Path root = scratch.resolve("mod with spaces"); Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(root.resolve("gradle/wrapper/gradle-wrapper.properties"), "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n");
        Files.write(root.resolve("gradle/wrapper/gradle-wrapper.jar"), new byte[0]);
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }\n");
        Path source = root.resolve("src/custom/Food.java"); Files.createDirectories(source.getParent()); Files.writeString(source, "class Food { int n = 1; }\r\n");
        ProjectModel project = new ProjectModel(root); Path init = home.resolve("tools/cli/gradle.init.gradle");
        String key = project.configurationHash(init); FileTime time = Files.getLastModifiedTime(root.resolve("build.gradle.kts"));
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java } //changed\n"); Files.setLastModifiedTime(root.resolve("build.gradle.kts"), time);
        check(!key.equals(project.configurationHash(init)), "build configuration changes invalidate metadata even with preserved timestamps");
        Path nested = root.resolve("unrelated/.git"); Files.createDirectories(nested); Files.writeString(nested.getParent().resolve("build.gradle"), "secret build");
        check(project.inputs().stream().noneMatch(p -> p.startsWith(nested.getParent())), "nested git checkouts are excluded from project discovery");
        Map<String, Object> model = Json.map("projects", List.of(Json.map("directory", root.toString(), "buildFile", root.resolve("build.gradle.kts").toString(),
                "sourceSets", List.of(Json.map("name", "test", "sources", List.of(source.getParent().toString()), "java", List.of(source.getParent().toString()), "output", List.of())), "compile", List.of())));
        String sourceHash = Fingerprints.build(model, project, init); FileTime modified = Files.getLastModifiedTime(source);
        Files.writeString(source, "class Food { int n = 2; }\r\n"); Files.setLastModifiedTime(source, modified);
        check(!sourceHash.equals(Fingerprints.build(model, project, init)), "source content invalidates warm builds even when timestamps and length match");
        Path layered = root.resolve("custom-client/Layered.java"); Files.createDirectories(layered.getParent()); Files.writeString(layered, "class Layered {}\n");
        String layeredHash = Fingerprints.build(model, project, init); Files.writeString(layered, "class Layered { int n; }\n");
        check(!layeredHash.equals(Fingerprints.build(model, project, init)), "custom source layouts cannot bypass the build identity");
        Path stale = root.resolve("build/test-results/modaccelPure/TEST-old.xml"); Files.createDirectories(stale.getParent()); Files.writeString(stale, "<testsuite tests=\"8\" failures=\"0\" errors=\"0\" skipped=\"0\"/>");
        check(((Number)TestResults.read(model, "modaccelPure", "fresh-run").get("executed")).intValue() == 0, "old successful XML cannot make an empty run pass");
        Build build = new Build(project, home, "", new Modaccel.Report(root, "test")); Port port = new Port(build, model);
        Build portBuild = new Build(project, home, "", new Modaccel.Report(root, "port"));
        for (var groups : List.of(List.of("net.minecraftforge"), List.of("net.fabricmc", "net.neoforged"), List.<String>of())) {
            var dependencies = groups.stream().map(group -> Json.map("group", group)).toList();
            try {
                Target.plan(portBuild, Json.map("projects", List.of(Json.map("dependencies", dependencies))), "1.21.6");
                throw new AssertionError("unsupported loader reached automatic target setup: " + groups);
            } catch (IOException expected) {
                check(expected.getMessage().contains("Fabric or NeoForge") && expected.getMessage().contains("target dependencies"),
                        "unsupported loader gives manual setup guidance before network or edits: " + groups);
            }
        }
        check(portBuild.command(List.of("help")).contains("-Dfabric.loom.ci=true"), "port Gradle skips dependency source remapping");
        check(!portBuild.command(List.of("help")).contains("--no-configure-on-demand"), "modaccel's Gradle runs keep the build's configure-on-demand");
        Path missed = scratch.resolve("missed.log");
        Files.writeString(missed, "* What went wrong:\nCannot locate tasks that match ':modaccelMetadata' as task 'modaccelMetadata' not found in root project 'diorama'.\n");
        check(Build.selectionFailed(missed), "a task-selection failure is recognized for the configure-on-demand retry");
        Files.writeString(missed, "* What went wrong:\nExecution failed for task ':compileJava'.\n");
        check(!Build.selectionFailed(missed), "a compile failure does not trigger the configure-on-demand retry");
        check(build.command(List.of("help")).contains("-Dfabric.loom.ci=true"), "verification Gradle also skips dependency source remapping");
        Path transaction = project.state().resolve("ports/test-undo"), backup = transaction.resolve("before/src/custom/Food.java"); Files.createDirectories(backup.getParent());
        byte[] original = "class Food { int n = 1; }\r\n// original 🧱".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(backup, original); Map<String, Object> before = Json.map("src/custom/Food.java", Fingerprints.bytes(original));
        port.finishTransaction(transaction, before);
        String repaired = Files.readString(source); Files.writeString(source, repaired + "// later user edit\n");
        try { port.undo(transaction.toString()); throw new AssertionError("overwrote later edit"); } catch (IOException expected) { check(Files.readString(source).contains("later user edit"), "undo refuses and preserves edits made after a port"); }
        Files.writeString(source, repaired); port.undo(transaction.toString());
        check(Arrays.equals(original, Files.readAllBytes(source)), "undo restores exact original bytes, Unicode, CRLF and missing final newline");
        check(!Files.readString(backup).contains("later user edit"), "repair backups remain independent of working files");
        check(Target.replace("# MC\r\nminecraft_version = 1.21.5\r\nother=kept\r\n", "minecraft_version", "1.21.6")
                .equals("# MC\r\nminecraft_version = 1.21.6\r\nother=kept\r\n"), "target setup preserves property spacing, comments and line endings");
        try { Target.replace("minecraft_version=1\nminecraft_version=2\n", "minecraft_version", "3"); throw new AssertionError("ambiguous pin accepted"); }
        catch (IOException expected) { check(true, "target setup refuses duplicate property owners"); }
        Path pin = root.resolve("gradle.properties"); Files.writeString(pin, "minecraft_version=1\n");
        var edit = new Target.Edit(pin, Files.readString(pin), "minecraft_version=2\n"); Files.writeString(pin, "minecraft_version=3\n");
        try { Target.apply(List.of(edit)); throw new AssertionError("overwrote concurrent config edit"); }
        catch (IOException expected) { check(Files.readString(pin).contains("=3"), "target setup preserves configuration edited after resolution"); }
        String groovy = "// keep\r\nmodCompileOnly 'example:mod:1.0'\r\n";
        check(Dependencies.literal(groovy, "example:mod", "1.0", "2.0").equals(groovy.replace(":1.0'", ":2.0'")), "dependency migration preserves Groovy quotes, comments and CRLF");
        String kotlin = "modImplementation(\"example:mod:1.0\")\n";
        check(Dependencies.literal(kotlin, "example:mod", "1.0", "2.0").equals(kotlin.replace(":1.0\"", ":2.0\"")), "dependency migration preserves Kotlin syntax");
        try { Dependencies.literal(groovy + groovy, "example:mod", "1.0", "2.0"); throw new AssertionError("ambiguous dependency accepted"); }
        catch (IOException expected) { check(true, "dependency migration refuses ambiguous literal owners"); }
        Path crash = scratch.resolve("startup.log"); Files.writeString(crash, "Caused by: third.party.MixinFailure: broken hook\n" + "Gradle footer\n".repeat(40));
        check(Run.failure(crash).contains("third.party.MixinFailure"), "startup errors retain the actual cause above the Gradle footer");
        Path metadataRoot = scratch.resolve("metadata-mod"); Path libs = metadataRoot.resolve("build/libs"); Files.createDirectories(libs);
        String toml = "[modLoader=\"javafml\"]\n[[mods]]\nmodId=\"m\"\n[[dependencies.m]]\nmodId=\"neoforge\"\nversionRange=\"[21.4,)\"\n[[dependencies.m]]\nmodId=\"minecraft\"\nversionRange=\"%s\"\n";
        Map<String, Object> neoModel = Json.map("projects", List.of(Json.map("plugins", List.of("net.neoforged.moddev.NeoForgeModDevPlugin"))));
        Path packaged = libs.resolve("m-1.0.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(packaged))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/neoforge.mods.toml"));
            zip.write(String.format(toml, "[1.21.1,1.21.1]").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Map<String, Object> staleMetadata = Port.verifyModMetadata(metadataRoot, neoModel, "1.21.4");
        check(Boolean.FALSE.equals(staleMetadata.get("ok")) && staleMetadata.get("details").toString().contains("0 mods"),
                "a packaged mod that still declares the old Minecraft range is refused instead of looking like a passing port");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(packaged))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/neoforge.mods.toml"));
            zip.write(String.format(toml, "[1.21.4,1.21.4]").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        check(Boolean.TRUE.equals(Port.verifyModMetadata(metadataRoot, neoModel, "1.21.4").get("ok")),
                "packaged metadata that accepts the target version passes the loader check");
        System.out.println(count + " portable checks passed");
    }
    public static final class LaunchProbe {
        public static void main(String[] args) {
            if (!"existing-file".equals(System.getProperty("modaccel.launchProbe")) || !Arrays.equals(args, new String[] {"@literal", "spaces and slash\\"}))
                throw new AssertionError("launch arguments changed: " + System.getProperty("modaccel.launchProbe") + " " + Arrays.toString(args));
            System.out.println("LAUNCH_OK");
        }
    }
}
