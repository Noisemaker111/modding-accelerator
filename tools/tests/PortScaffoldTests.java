import java.io.*;
import java.nio.file.*;
import java.util.*;

/** The RFG scaffold for a NeoForge build retargeted at 1.7.10: scripts, pins, undo records. */
public final class PortScaffoldTests {
    static int count;

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    /** A scaffold fixture root with the wrapper the Build constructor reads. */
    static Path scaffoldRoot(Path root) throws IOException {
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(
            root.resolve("gradle/wrapper/gradle-wrapper.properties"),
            "distributionUrl=gradle-9.7.1-bin.zip\n"
        );
        Files.write(root.resolve("gradle/wrapper/gradle-wrapper.jar"), new byte[0]);
        return root;
    }

    /** A NeoForge build retargeted at 1.7.10 swaps its whole build for an RFG scaffold, no game jar needed. */
    static void scaffoldChecks(Path home, Path scratch) throws Exception {
        Path groovy = scaffoldRoot(scratch.resolve("scaffold-groovy"));
        Files.writeString(groovy.resolve("settings.gradle"), "rootProject.name = \"apotheosis\"\n");
        Files.writeString(groovy.resolve("build.gradle"), "plugins { id(\"net.neoforged.moddev\") }\n");
        Files.writeString(groovy.resolve("gradle.properties"), "minecraft_version = 1.21.1\nmod_id = apotheosis\n");
        ProjectModel groovyProject = new ProjectModel(groovy);
        Build groovyBuild = new Build(groovyProject, home, "", new Mmt.Report(groovy, "scaffold"));
        Map<String, Object> groovyModel = Json.map(
            "path",
            ":",
            "directory",
            groovy.toString(),
            "buildFile",
            groovy.resolve("build.gradle").toString(),
            "pins",
            Json.map("minecraft_version", "1.21.1")
        );
        Target.Setup groovySetup = new Target.Setup(groovyBuild, groovyModel, "1.7.10", List.of());
        Target.scaffoldFiles(groovySetup, groovyBuild, Json.map("minecraft_version", "1.21.1"), "2.6", "1.7.10");
        String build = groovySetup.text(groovy.resolve("build.gradle"));
        check(
            build.contains("com.gtnewhorizons.retrofuturagradle") &&
            build.contains("version \"2.6\"") &&
            build.contains("mcVersion.set(\"1.7.10\")") &&
            build.contains("JavaLanguageVersion.of(17)") &&
            build.contains("release.set(8)") &&
            build.contains("withSourcesJar()") &&
            build.contains("nexus.gtnewhorizons.com"),
            "the scaffold build is RFG 2.6 on Java 17 targeting 1.7.10 with Java 8 bytecode"
        );
        String settings = groovySetup.text(groovy.resolve("settings.gradle"));
        check(
            settings.contains("rootProject.name = \"apotheosis\"") &&
            settings.contains("foojay") &&
            settings.contains("nexus.gtnewhorizons.com"),
            "the scaffold settings keep the project name and provision the toolchain"
        );
        String properties = groovySetup.text(groovy.resolve("gradle.properties"));
        check(
            properties.contains("mcVersion = 1.7.10") &&
            properties.contains("mod_id = apotheosis") &&
            !properties.contains("1.21.1"),
            "the owned game pin becomes mcVersion at the target and nothing else keeps the old game"
        );
        check(
            groovySetup.excluded.isEmpty(),
            "a Groovy layout rewrites in place with nothing to delete"
        );
        Path daemon = groovy.resolve("gradle/gradle-daemon-jvm.properties");
        check(
            "toolchainVersion=25\n".equals(groovySetup.text(daemon)),
            "the scaffold pins the daemon the RFG plugin needs"
        );
        check(
            groovySetup.edits().stream().anyMatch(e -> e.file().equals(daemon) && e.before() == null) &&
            groovySetup.edits().stream().anyMatch(
                e -> e.file().equals(groovy.resolve("build.gradle")) && e.before() != null
            ),
            "creations track a null before for undo while rewrites keep theirs"
        );
        check(
            groovySetup.applied.toString().contains("retrofuturagradle") &&
            groovySetup.applied.toString().contains("10.13.4.1614"),
            "the receipt records the RFG version and the Forge it maps to"
        );
        Path kotlin = scaffoldRoot(scratch.resolve("scaffold-kotlin"));
        Files.writeString(kotlin.resolve("settings.gradle.kts"), "rootProject.name = \"ktsmod\"\n");
        Files.writeString(kotlin.resolve("build.gradle.kts"), "plugins { id(\"net.neoforged.moddev\") }\n");
        Files.writeString(kotlin.resolve("gradle.properties"), "minecraft_version = 1.21.1\n");
        ProjectModel kotlinProject = new ProjectModel(kotlin);
        Build kotlinBuild = new Build(kotlinProject, home, "", new Mmt.Report(kotlin, "scaffold"));
        Map<String, Object> kotlinModel = Json.map(
            "path",
            ":",
            "directory",
            kotlin.toString(),
            "buildFile",
            kotlin.resolve("build.gradle.kts").toString(),
            "pins",
            Json.map("minecraft_version", "1.21.1")
        );
        Target.Setup kotlinSetup = new Target.Setup(kotlinBuild, kotlinModel, "1.7.10", List.of());
        Target.scaffoldFiles(kotlinSetup, kotlinBuild, Json.map("minecraft_version", "1.21.1"), "2.6", "1.7.10");
        check(
            kotlinSetup.excluded.contains(kotlin.resolve("build.gradle.kts")) &&
            kotlinSetup.excluded.contains(kotlin.resolve("settings.gradle.kts")) &&
            Files.isRegularFile(kotlin.resolve("build.gradle.kts")),
            "a Kotlin layout deletes the old scripts at apply time, not at plan time"
        );
        check(
            kotlinSetup.text(kotlin.resolve("settings.gradle")).contains("rootProject.name = \"ktsmod\""),
            "a Kotlin layout is replaced with the Groovy scaffold under the same project name"
        );
        Path dual = scaffoldRoot(scratch.resolve("scaffold-dual"));
        Files.writeString(dual.resolve("settings.gradle"), "");
        Files.writeString(dual.resolve("build.gradle"), "");
        Files.writeString(
            dual.resolve("gradle.properties"),
            "minecraft_version = 1.21.1\nmod_id = apotheosis\nminecraftVersion = 1.21.1\n"
        );
        ProjectModel dualProject = new ProjectModel(dual);
        Build dualBuild = new Build(dualProject, home, "", new Mmt.Report(dual, "scaffold"));
        Map<String, Object> dualModel = Json.map(
            "path",
            ":",
            "directory",
            dual.toString(),
            "buildFile",
            dual.resolve("build.gradle").toString(),
            "pins",
            Json.map("minecraft_version", "1.21.1", "minecraftVersion", "1.21.1")
        );
        Target.Setup dualSetup = new Target.Setup(dualBuild, dualModel, "1.7.10", List.of());
        Target.scaffoldFiles(
            dualSetup,
            dualBuild,
            Json.map("minecraft_version", "1.21.1", "minecraftVersion", "1.21.1"),
            "2.6",
            "1.7.10"
        );
        String merged = dualSetup.text(dual.resolve("gradle.properties"));
        check(
            merged.lines().filter(line -> line.contains("mcVersion")).count() == 1 &&
            !merged.contains("1.21.1"),
            "a second game pin spelling goes instead of re-evaluating to two games"
        );
        Path missing = scaffoldRoot(scratch.resolve("scaffold-missing"));
        Files.writeString(missing.resolve("settings.gradle"), "");
        Files.writeString(missing.resolve("build.gradle"), "");
        Files.writeString(missing.resolve("gradle.properties"), "mod_id = apotheosis\n");
        ProjectModel missingProject = new ProjectModel(missing);
        Build missingBuild = new Build(missingProject, home, "", new Mmt.Report(missing, "scaffold"));
        Map<String, Object> missingModel = Json.map(
            "path",
            ":",
            "directory",
            missing.toString(),
            "buildFile",
            missing.resolve("build.gradle").toString(),
            "pins",
            Json.map()
        );
        boolean missingThrown = false;
        try {
            Target.scaffoldFiles(
                new Target.Setup(missingBuild, missingModel, "1.7.10", List.of()),
                missingBuild,
                Json.map(),
                "2.6",
                "1.7.10"
            );
        } catch (IOException expected) {
            missingThrown = expected.getMessage().contains("gradle.properties");
        }
        check(missingThrown, "a build with no game pin says which file owns the pin");
        Path ambiguous = scaffoldRoot(scratch.resolve("scaffold-ambiguous"));
        Files.createDirectories(ambiguous.resolve("alpha"));
        Files.createDirectories(ambiguous.resolve("beta"));
        Files.writeString(ambiguous.resolve("settings.gradle"), "");
        Files.writeString(ambiguous.resolve("build.gradle"), "");
        Files.writeString(ambiguous.resolve("gradle.properties"), "mod_id = apotheosis\n");
        Files.writeString(ambiguous.resolve("alpha/gradle.properties"), "minecraft_version = 1.21.1\n");
        Files.writeString(ambiguous.resolve("beta/gradle.properties"), "minecraft_version = 1.21.1\n");
        ProjectModel ambiguousProject = new ProjectModel(ambiguous);
        Build ambiguousBuild = new Build(ambiguousProject, home, "", new Mmt.Report(ambiguous, "scaffold"));
        Map<String, Object> ambiguousModel = Json.map(
            "path",
            ":",
            "directory",
            ambiguous.toString(),
            "buildFile",
            ambiguous.resolve("build.gradle").toString(),
            "pins",
            Json.map("minecraft_version", "1.21.1")
        );
        boolean ambiguousThrown = false;
        try {
            Target.scaffoldFiles(
                new Target.Setup(ambiguousBuild, ambiguousModel, "1.7.10", List.of()),
                ambiguousBuild,
                Json.map("minecraft_version", "1.21.1"),
                "2.6",
                "1.7.10"
            );
        } catch (IOException expected) {
            ambiguousThrown = expected.getMessage().contains("unambiguous");
        }
        check(ambiguousThrown, "two owners for the game pin stop the scaffold instead of guessing");
        check(
            Target.rfgBuildGradle("9.9").contains("version \"9.9\"") &&
            Target.rfgSettingsGradle("weird").contains("rootProject.name = \"weird\"") &&
            Target.rfgDaemonJvmProperties().equals("toolchainVersion=25\n"),
            "the scaffold templates render the resolved versions and names"
        );
        check(
            Target.rfgSettingsGradle("a\"b").contains("rootProject.name = \"a\\\"b\""),
            "a quote in the project name is escaped instead of breaking the settings"
        );
    }

    public static void main(String[] args) throws Exception {
        Path home = Path.of(args[0]), scratch = Files.createTempDirectory(
            Files.createDirectories(Path.of(args[1])),
            "run-"
        );
        scaffoldChecks(home, scratch);
        System.out.println(count + " port-scaffold checks passed");
    }
}
