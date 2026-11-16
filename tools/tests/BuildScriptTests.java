import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Build-script edits a port makes: Loom for the unobfuscated game, dead plugins, removed Gradle settings, the plugin and Gradle versions it picks. */
public final class BuildScriptTests {

    static int count;

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    public static void main(String[] args) throws Exception {
        // Every check assumes nothing is there yet, so each run gets its own empty folder inside the one it is given.
        Path home = Path.of(args[0]), scratch = Files.createTempDirectory(
            Files.createDirectories(Path.of(args[1])),
            "run-"
        );
        // A Fabric build moving onto 26.x stops remapping: the plugin, the mappings statement (a layered one spans lines),
        // the remapping configurations, remapJar and the Java level all change; a build without Loom is left alone.
        String obfuscated =
            "plugins {\n    id 'fabric-loom' version '1.10-SNAPSHOT'\n}\n" +
            "dependencies {\n    minecraft \"com.mojang:minecraft:${minecraft_version}\"\n" +
            "    mappings loom.layered() {\n        officialMojangMappings()\n        parchment(\"org.parchmentmc.data:parchment-1.21:2024.07.28@zip\")\n    }\n" +
            "    modImplementation \"net.fabricmc:fabric-loader:${loader_version}\"\n    include modApi(\"teamreborn:energy:4.1.0\")\n" +
            "    modCompileOnly \"curse.maven:jei-238222:1\"\n}\n" +
            "tasks.remapJar { archiveClassifier = 'x' }\njava { sourceCompatibility = JavaVersion.VERSION_21 }\n" +
            "tasks.withType(JavaCompile).configureEach { it.options.release = 21 }\n";
        String unobfuscated = Target.unobfuscatedLoom(obfuscated, 25);
        check(
            unobfuscated.equals(
                "plugins {\n    id 'net.fabricmc.fabric-loom' version '1.10-SNAPSHOT'\n}\n" +
                "dependencies {\n    minecraft \"com.mojang:minecraft:${minecraft_version}\"\n" +
                "    implementation \"net.fabricmc:fabric-loader:${loader_version}\"\n    include api(\"teamreborn:energy:4.1.0\")\n" +
                "    compileOnly \"curse.maven:jei-238222:1\"\n}\n" +
                "tasks.jar { archiveClassifier = 'x' }\njava { sourceCompatibility = JavaVersion.VERSION_25 }\n" +
                "tasks.withType(JavaCompile).configureEach { it.options.release = 25 }\n"
            ),
            "a Loom build rewritten for the unobfuscated game: " + unobfuscated
        );
        check(Target.unobfuscatedLoom("plugins { id 'java' }\nmappings x\n", 25).equals("plugins { id 'java' }\nmappings x\n"), "a build without Loom is left alone");
        // A script applied by a Loom script (Waystones' fabric/dependencies.gradle) names no plugin but drops remapping too.
        check(
            Target.plainConfigurations("dependencies {\n    modImplementation(\"net.blay09.mods:balm-fabric:1\") { changing = true }\n    modCompileOnly x\n    myModImplementationHelper()\n}\n")
                .equals("dependencies {\n    implementation(\"net.blay09.mods:balm-fabric:1\") { changing = true }\n    compileOnly x\n    myModImplementationHelper()\n}\n"),
            "an applied script's remapping configurations become plain ones, other names untouched"
        );
        // A settings filter that passes only net.fabricmc (TorchMaster) must pass Loom's subgroups: its 26.x plugin marker
        // (net.fabricmc.fabric-loom) and libraries (net.fabricmc.unpick).
        check(
            Target.fabricSubgroups("filter {\n    includeGroup('net.fabricmc')\n    includeGroup('fabric-loom')\n    includeGroup(\"net.fabricmc.unpick\")\n}\n")
                .equals("filter {\n    includeGroupAndSubgroups('net.fabricmc')\n    includeGroup('fabric-loom')\n    includeGroup(\"net.fabricmc.unpick\")\n}\n"),
            "includeGroup('net.fabricmc') widened to its subgroups, other groups untouched"
        );
        check(Target.remapTasks("def main = name == 'Fabric' ? remapJar.archivePath : jar.archivePath\ntasks.remapSourcesJar { }\nmyRemapJarTask()\n")
            .equals("def main = name == 'Fabric' ? jar.archivePath : jar.archivePath\ntasks.sourcesJar { }\nmyRemapJarTask()\n"), "a convention script's remap tasks become the plain ones");
        check(Target.withoutAdditionalRuntimeClasspath("additionalRuntimeClasspath(shade('lucene'))\nmyAdditionalRuntimeClasspath2 x\n").equals("runtimeOnly(shade('lucene'))\nmyAdditionalRuntimeClasspath2 x\n"), "NeoForge's removed additional classpath -> runtimeOnly");
        // Loom 1.12+ in a multi-project build: the subproject drops its version and the root declares Loom with apply false.
        check(Target.withoutLoomVersion("plugins {\n    id 'fabric-loom' version '1.10-SNAPSHOT'\n}\n").equals("plugins {\n    id 'fabric-loom'\n}\n"), "subproject Loom loses its version");
        check(
            "plugins {\n    id 'fabric-loom' version '1.19.4' apply false\n    id 'idea'\n}\n".equals(Target.rootLoom("plugins {\n    id 'idea'\n}\n", "fabric-loom", "1.19.4", "'", false)),
            "root plugins block gains Loom with apply false"
        );
        check("plugins {\n    id(\"net.fabricmc.fabric-loom\") version \"1.19.4\" apply false\n}\n\nversion = 1\n".equals(Target.rootLoom("version = 1\n", "net.fabricmc.fabric-loom", "1.19.4", "'", true)), "a Kotlin root with no plugins block gets one");
        check(Target.rootLoom("buildscript { }\napply plugin: 'java'\n", "fabric-loom", "1.19.4", "'", false) == null, "a buildscript-only root is not rewritten");
        // NeoForm numbers 26.x releases (26.3-1) where 1.21.x used timestamps; snapshots of a later game are not releases.
        String neoForm = "<version>1.21.1-20240808.144430</version><version>1.21.1-20240801.000000</version><version>26.3-snapshot-1-1</version>"
            + "<version>26.2-1</version><version>26.2-10</version><version>26.2-2</version><version>26.3-1</version><version>26.4-snapshot-8-1</version>";
        check("26.2-10".equals(Target.neoFormRelease(neoForm, "26.2")), "NeoForm's newest numbered release: " + Target.neoFormRelease(neoForm, "26.2"));
        check("26.3-1".equals(Target.neoFormRelease(neoForm, "26.3")), "a snapshot is not a 26.3 release");
        check("1.21.1-20240808.144430".equals(Target.neoFormRelease(neoForm, "1.21.1")), "a timestamped release still resolves");
        check(Target.neoFormRelease(neoForm, "26.4") == null, "no release for a game NeoForm only has snapshots of");
        // A decompiler-only plugin no repository publishes any more is removed with its block; restorePending puts it back.
        {
            Path dead = Files.createDirectories(scratch.resolve("dead-plugin"));
            Files.createDirectories(dead.resolve("gradle/wrapper"));
            Files.writeString(dead.resolve("gradle/wrapper/gradle-wrapper.properties"), "distributionUrl=https\\://services.gradle.org/distributions/gradle-7.4.2-bin.zip\n");
            String script = "plugins {\n    id 'fabric-loom' version '0.12-SNAPSHOT'\n    id 'io.github.juuxel.loom-quiltflower' version \"1.6.0\"\n}\n\nquiltflower {\n    addToRuntimeClasspath.set(true)\n}\n\ndependencies {}\n";
            Files.writeString(dead.resolve("build.gradle"), script);
            ProjectModel deadProject = new ProjectModel(dead);
            check(Port.deadDevPlugin("Plugin [id: 'io.github.juuxel.loom-quiltflower', version: '1.6.0'] was not found").equals("io.github.juuxel.loom-quiltflower"), "a dead decompiler plugin is named");
            check(Port.deadDevPlugin("Plugin [id: 'fabric-loom', version: '0.12'] was not found") == null, "a plugin the build needs is never dropped");
            check(Port.removeDevPlugin(deadProject, "io.github.juuxel.loom-quiltflower").equals(List.of("build.gradle")), "the script that applies it is changed");
            check(
                Files.readString(dead.resolve("build.gradle")).equals("plugins {\n    id 'fabric-loom' version '0.12-SNAPSHOT'\n}\n\n\ndependencies {}\n"),
                "plugin line and its block removed: " + Files.readString(dead.resolve("build.gradle"))
            );
            Port.restorePending(deadProject);
            check(Files.readString(dead.resolve("build.gradle")).equals(script), "restorePending puts the script back byte for byte");
        }
        // A port's tier never claims more than happened: a build that did not compile is compile-failed, whatever Mixin said
        // before (Farmer's Delight 1.21.1 -> 1.21.4 once said compile-and-mixin-applied with compiled false).
        check(Port.tier(false, Json.map("ok", true), false).equals("compile-failed"), "no compile, no mixin claim");
        check(Port.tier(true, null, false).equals("compiled-mixins-failed"), "compiled but mixins unchecked");
        check(Port.tier(true, Json.map("ok", false), true).equals("compiled-mixins-failed"), "compiled, mixins failed");
        check(Port.tier(true, Json.map("ok", true), false).equals("compile-and-mixin-applied"), "compiled and mixins applied");
        check(Port.tier(true, Json.map("ok", true), true).equals("build-and-mixin-applied"), "built and mixins applied");
        // ModDevGradle moves to the newest release of its own line (1.0.0 -> 1.0.24, not 2.x), releases before betas.
        String modDev = "<versions><version>1.0.0</version><version>2.0.0-beta</version><version>1.0.23</version><version>1.0.24</version><version>2.0.148</version><version>1.1.0-beta</version></versions>";
        check("1.0.24".equals(Target.newestInLine(modDev, "1")), "ModDevGradle 1.x line: " + Target.newestInLine(modDev, "1"));
        check("2.0.148".equals(Target.newestInLine(modDev, "2")), "ModDevGradle 2.x line");
        check("3.0.0-beta".equals(Target.newestInLine("<version>3.0.0-beta</version>", "3")), "a line with only betas gives its newest beta");
        // A folder with its own settings.gradle is a separate build the port never touches (ZAA's forge1201/, neoforge1201/).
        {
            Path zaa = Files.createDirectories(scratch.resolve("separate-builds"));
            Files.writeString(zaa.resolve("settings.gradle"), "rootProject.name = 'zaa'\n");
            Files.writeString(zaa.resolve("build.gradle"), "plugins { id 'java' }\n");
            Files.createDirectories(zaa.resolve("forge1201/src/main/java/a"));
            Files.writeString(zaa.resolve("forge1201/settings.gradle"), "rootProject.name = 'zaa-forge'\n");
            Files.writeString(zaa.resolve("forge1201/build.gradle"), "minecraft { runs { data { } } }\n");
            Files.writeString(zaa.resolve("forge1201/src/main/java/a/A.java"), "package a; class A {}\n");
            Files.createDirectories(zaa.resolve("buildSrc"));
            Files.writeString(zaa.resolve("buildSrc/settings.gradle"), "\n");
            Files.writeString(zaa.resolve("buildSrc/build.gradle"), "\n");
            Files.createDirectories(zaa.resolve("src/main/java/b"));
            Files.writeString(zaa.resolve("src/main/java/b/B.java"), "package b; class B {}\n");
            ProjectModel separate = new ProjectModel(zaa);
            check(separate.inputs().stream().noneMatch(p -> p.toString().contains("forge1201")), "a separate build's scripts are not this build's inputs");
            check(separate.inputs().stream().anyMatch(p -> p.endsWith(Path.of("buildSrc", "build.gradle"))), "buildSrc stays part of the build");
            check(Port.sources(separate).stream().noneMatch(p -> p.toString().contains("forge1201")), "a separate build's sources are not ported");
            check(Port.sources(separate).stream().anyMatch(p -> p.endsWith(Path.of("b", "B.java"))), "the build's own sources are ported");
        }
        // Rules mined from other people's mods run only with --learn-from-mods; the rule book's own rules always run.
        check(!Port.ownRule(Json.map("provenance", "third-party-mods"), false), "third-party rules are off by default");
        check(Port.ownRule(Json.map("provenance", "third-party-mods"), true), "--learn-from-mods turns them on");
        check(Port.ownRule(Json.map("provenance", "hand"), false), "our own rules always run");
        // Forge's newest release for a game version, from its own maven-metadata.xml (multi-loader siblings follow the port).
        String forge = "<version>1.21.4-54.0.16</version><version>1.21.5-55.0.9</version><version>1.21.5-55.0.21</version><version>1.21.5-55.0.3</version><version>1.21.50-1.0</version>";
        check("1.21.5-55.0.21".equals(Target.forgeRelease(forge, "1.21.5")), "Forge's newest release for the target game");
        check(Target.forgeRelease(forge, "1.21.2") == null, "no Forge release for a game Forge skipped");
        // An exact game range is written [1.21.2]: Maven's rules refuse [1.21.2,1.21.2] (identical boundaries), and NeoForge refuses the mod.
        check(MavenRange.own("[1.21.2]", "1.21.2") && !MavenRange.own("[1.21.2]", "1.21.3"), "an exact range written the way Maven accepts it");
        // Build-script settings Gradle 8 and Loom 1.x removed: a jar's classifier, loom.shareCaches(); a dependency's classifier: key stays.
        check(
            Target.removedDsl("task sourcesJar(type: Jar) {\n    classifier = 'sources'\n}\nloom {\n    shareCaches()\n}\ndependencies { implementation(group: 'a', name: 'b', classifier: 'api') }\n", true)
                .equals("task sourcesJar(type: Jar) {\n    archiveClassifier = 'sources'\n}\nloom {\n}\ndependencies { implementation(group: 'a', name: 'b', classifier: 'api') }\n"),
            "classifier and shareCaches() rewritten for Gradle 8 and Loom 1.x"
        );
        check(Target.removedDsl("    classifier \"dev\"\n", false).equals("    classifier \"dev\"\n"), "a classifier stays on Gradle 7");
        check(
            Target.removedDsl("sourceCompatibility = JavaVersion.VERSION_17\ntargetCompatibility = JavaVersion.VERSION_17\njava { withSourcesJar() }\n", true, true)
                .equals("java.sourceCompatibility = JavaVersion.VERSION_17\njava.targetCompatibility = JavaVersion.VERSION_17\njava { withSourcesJar() }\n"),
            "Gradle 9: project-level compatibility settings move to the java extension"
        );
        check(
            Target.removedDsl("archivesBaseName = project.archives_base_name\njar { from(\"LICENSE\") { rename { \"${it}_${archivesBaseName}\" } } }\n", true, true)
                .equals("base.archivesName = project.archives_base_name\njar { from(\"LICENSE\") { rename { \"${it}_${project.base.archivesName.get()}\" } } }\n"),
            "Gradle 9: archivesBaseName is the base extension's archivesName"
        );
        // Architectury Loom is Loom: its version is read like Fabric Loom's, so a port can raise it (BetterF3 1.5-SNAPSHOT).
        check(
            "1.5-SNAPSHOT".equals(ProjectModel.loomVersion(new Properties(), List.of("plugins {\n    id \"dev.architectury.loom\" version \"1.5-SNAPSHOT\" apply false\n}\n"))),
            "Architectury Loom's version is the build's Loom"
        );
        // A Forge project a target has no Forge for leaves settings.gradle; the other loaders stay.
        check(
            Target.withoutForgeInclude("include(\":common\")\ninclude(\":fabric\")\ninclude(\":forge\")\ninclude(\":neoforge\")\n")
                .equals("include(\":common\")\ninclude(\":fabric\")\ninclude(\":neoforge\")\n"),
            "a one-name Forge include goes"
        );
        check(
            Target.withoutForgeInclude("include 'common', 'fabric', 'Forge', 'neoforge'\n").equals("include 'common', 'fabric', 'neoforge'\n"),
            "Forge leaves a longer include list"
        );
        check(Target.withoutForgeInclude("include 'Forge', 'Fabric'\n").equals("include 'Fabric'\n"), "Forge first in the list");
        check(
            Target.withoutForgeInclude("rootProject.name = 'ncr'\ninclude('forge')\nproject(\":forge\").name = \"Forge\"").equals("rootProject.name = 'ncr'\n"),
            "the line naming the Forge project goes with its include"
        );
        // A lost annotation library comes back compileOnly, once, in the script's own dialect.
        String groovy = "plugins { id 'java' }\ndependencies {\n    implementation 'a:b:1'\n}\n";
        String restored = Port.withCompileOnly(groovy, List.of("com.google.code.findbugs:jsr305:3.0.2"), false);
        check(restored.contains("    compileOnly \"com.google.code.findbugs:jsr305:3.0.2\"\n    implementation"), "jsr305 added to dependencies");
        check(Port.withCompileOnly(restored, List.of("com.google.code.findbugs:jsr305:3.0.2"), false).equals(restored), "not added twice");
        check(Port.withCompileOnly("dependencies {\n}\n", List.of("org.jspecify:jspecify:1.0.0"), true).contains("compileOnly(\"org.jspecify:jspecify:1.0.0\")"), "Kotlin form");
        // A rename that changes what the member returns (getMaxBuildHeight -> getMaxY is one lower) leaves the map; the rest stays.
        {
            Path meaningMap = scratch.resolve("meaning-map.json");
            Files.writeString(
                meaningMap,
                String.join(
                    "\n",
                    "{",
                    "  \"from\": \"1.21.1\", \"to\": \"1.21.2\", \"names\": \"mojmap\",",
                    "  \"methods\": [",
                    "    { \"owner\": \"a.LevelHeightAccessor\", \"from\": \"getMinBuildHeight\", \"desc\": \"()I\", \"to\": \"getMinY\", \"newDesc\": \"()I\" },",
                    "    { \"owner\": \"a.LevelHeightAccessor\", \"from\": \"getMaxBuildHeight\", \"desc\": \"()I\", \"to\": \"getMaxY\", \"newDesc\": \"()I\" }",
                    "  ]",
                    "}",
                    ""
                )
            );
            check(Port.withoutMeaningChanges(meaningMap, home.resolve("knowledge/meaning-changes.json")) == 1, "the meaning-changing rename is dropped");
            var kept = Json.read(meaningMap);
            check(
                Json.array(kept.get("methods")).size() == 1 && Json.text(Json.object(Json.array(kept.get("methods")).get(0)), "to", "").equals("getMinY"),
                "the plain rename stays and the map still parses"
            );
        }
        // Old Loom states its minimum Gradle only as a constant of its bootstrap class: class constants are read from the
        // class file itself (an int and a String constant of the JDK's own classes), and a major line becomes its newest release.
        try (var integer = Object.class.getResourceAsStream("/java/lang/Integer.class"); var jar = Object.class.getResourceAsStream("/java/util/jar/JarFile.class")) {
            check(Integer.valueOf(Integer.MAX_VALUE).equals(Target.classConstants(integer.readAllBytes()).get("MAX_VALUE")), "int class constant read from the class file");
            check("META-INF/MANIFEST.MF".equals(Target.classConstants(jar.readAllBytes()).get("MANIFEST_NAME")), "String class constant read from the class file");
        }
        check(
            "7.6.6".equals(Target.newestGradle("[{\"version\" : \"8.0\"}, {\"version\" : \"7.6.6\"}, {\"version\" : \"7.6\"}, {\"version\" : \"7.10-rc-1\"}, {\"version\":\"7.5.1\"}]", 7)),
            "a Gradle major line's newest final release"
        );
        System.out.println(count + " build script checks passed");
    }
}
