import java.util.*;

/** Public capability labels; examples are evidence, never a version allowlist. */
final class Support {
    static final String VERSION = "0.1.0-beta.1";
    private Support() { }
    static Map<String, Object> features() {
        return Json.map("version", VERSION, "input", "Gradle mod source checkout with a wrapper; a compiled mod JAR is not source",
                "features", List.of(
                        Json.map("name", "automatic-target", "status", "conditional", "scope", "One selected Fabric project, version properties with one owner, verifiable published dependencies and mappings; multi-project orchestration remains unfinished"),
                        Json.map("name", "dependency-selection", "status", "available", "scope", "Automatic identity, then an explicit selection file whose target coordinate, published repository and target-game support are verified; optional dependencies can be disabled with proof and a reason"),
                        Json.map("name", "dependency-blockers", "status", "available", "scope", "Unresolved dependencies stop before edits and name the selection entry to add"),
                        Json.map("name", "other-loader-target", "status", "conditional", "scope", "NeoForge version pins, loader version and declared game range resolve and rewrite; a real NeoForge mod built, loaded and ran its command on the target. Forge target setup remains manual, and larger jumps stop with the named API families that remain"),
                        Json.map("name", "api-migration", "status", "guarded", "scope", "Verified mappings and recognized recipes; unfamiliar semantic changes need guided repair"),
                        Json.map("name", "build-and-mixin", "status", "available", "scope", "Port runs the evaluated build, then Mixin itself applies every mixin against the target game (Fabric Knot, or NeoForge FML's unit-test launch); no window, no world"),
                        Json.map("name", "safe-undo", "status", "available", "scope", "Restores recorded original bytes; refuses to overwrite subsequent edits"),
                        Json.map("name", "logic-tests", "status", "available", "scope", "fast runs supplied pure JUnit tests; absent tests fail, and tests are not generated for arbitrary mods"),
                        Json.map("name", "game-behavior", "status", "separate-check", "scope", "ui-check and world-check capture the actual game; a meaningful assertion is required for behaviorVerified=true"),
                        Json.map("name", "visual-change", "status", "available", "scope", "ui-check/world-check --expect-change PERCENT fails when the key, click or screen changes too little of the real frame; it caught the invisible F3Nope overlay that a passing build missed"),
                        Json.map("name", "save-roundtrip", "status", "available", "scope", "world-check --save-roundtrip quits to the title, reloads the same save and requires the player position to come back within 0.05 blocks"),
                        Json.map("name", "game-console", "status", "conditional", "scope", "eval runs a Java snippet inside the owned client and returns its value in about a second; verified on Fabric 26.3"),
                        Json.map("name", "portable-hot-reload", "status", "conditional", "scope", "reload recompiles and redefines changed classes in the owned client and reloads resources; method bodies on a stock JVM, added methods and fields with --jbr; mixins and mod metadata still need a restart; verified on Fabric 26.3"),
                        Json.map("name", "operating-systems", "status", "partial", "scope", "Windows command and game checks; Windows, Linux and macOS portable checks pass in hosted CI; Linux and macOS game rendering unverified"),
                        Json.map("name", "installation", "status", "partial", "scope", "Portable ZIP and JAR; a Windows machine with no Java downloads a verified JRE or JDK once; macOS and Linux still need Java installed")),
                "testedExamples", List.of("Fabric AppleSkin 1.21.5 -> 1.21.6 automatic port and client check",
                        "Fabric AppleSkin 1.21.6 -> 1.21.5 automatic backport and mixin audit",
                        "Fabric AppleSkin 1.21.6 -> 1.21.9 guided debug HUD migration",
                        "Fabric F3Nope 1.21.5 -> 1.21.6 automatic port and packaged build/mixin audit; gameplay checked separately",
                        "NeoForge ZombieApocalypseAddon 1.21.1 -> 1.21.4 build, mixin audit and a client that loads the mod",
                        "Kotlin-script Stonecutter Diorama: node setup, compile and a node version port attempt"));
    }
    static String text() {
        var data = features(); var text = new StringBuilder("Modding Accelerator " + VERSION + " (scoped beta)\n");
        text.append("Input: ").append(data.get("input")).append('\n');
        for (Object item : Json.array(data.get("features"))) {
            var feature = Json.object(item);
            text.append(feature.get("name")).append(": ").append(feature.get("status"))
                    .append(" - ").append(feature.get("scope")).append('\n');
        }
        text.append("Tested examples (not a version allowlist):\n");
        for (Object example : Json.array(data.get("testedExamples"))) text.append("  ").append(example).append('\n');
        return text.toString();
    }
    static Map<String, Object> project(Collection<String> loaders) {
        boolean fabricOnly = loaders.size() == 1 && loaders.contains("fabric");
        return Json.map("automaticTarget", fabricOnly ? "eligible when version pins have one owner and published artifacts resolve" : "manual target setup required",
                "loaders", new TreeSet<>(loaders),
                "next", fabricOnly ? "Run modaccel port --from OLD --to NEW --fix --root SOURCE" :
                        "Update this loader's target dependencies in the source build, then run modaccel port --from OLD --to NEW --fix --root SOURCE");
    }
    /**
     * The loader a project builds against, from the loader artifact it depends on: Architectury Loom
     * builds NeoForge and Forge projects with plugins all named net.fabricmc.loom.*, so a plugin name
     * cannot tell them apart. Tool classpaths (Architectury's transformer pulls in Fabric Loader for
     * every platform) don't count. Plugin names decide only when no loader artifact is declared.
     */
    static Set<String> loaders(Map<String, Object> selected) {
        Set<String> fromArtifacts = new TreeSet<>();
        if (selected.get("dependencies") instanceof List<?> dependencies) for (Object entry : dependencies) {
            Map<String, Object> dependency = Json.object(entry);
            String coordinate = Json.text(dependency, "group", "") + ":" + Json.text(dependency, "name", "");
            if (Json.text(dependency, "configuration", "").toLowerCase(Locale.ROOT).startsWith("architecturytransformer")) continue;
            switch (coordinate) {
                case "net.fabricmc:fabric-loader" -> fromArtifacts.add("fabric");
                case "net.neoforged:neoforge", "net.neoforged:forge" -> fromArtifacts.add("neoforge");
                case "net.minecraftforge:forge" -> fromArtifacts.add("forge");
                default -> { }
            }
        }
        if (!fromArtifacts.isEmpty()) return fromArtifacts;
        Set<String> fromPlugins = new TreeSet<>();
        if (selected.get("plugins") instanceof List<?> plugins) for (Object entry : plugins) {
            String plugin = String.valueOf(entry).toLowerCase(Locale.ROOT);
            if (plugin.contains("loom")) fromPlugins.add("fabric");
            if (plugin.contains("moddev") || plugin.contains("neoforged")) fromPlugins.add("neoforge");
            if (plugin.contains("forgegradle") || plugin.contains("minecraftforge")) fromPlugins.add("forge");
        }
        if (!fromPlugins.isEmpty()) return fromPlugins;
        Set<String> loaders = new TreeSet<>();
        for (Object entry : Json.array(selected.get("dependencies"))) {
            String group = Json.text(Json.object(entry), "group", "");
            if (group.equals("net.fabricmc")) loaders.add("fabric");
            if (group.startsWith("net.neoforged")) loaders.add("neoforge");
            if (group.equals("net.minecraftforge")) loaders.add("forge");
        }
        return loaders;
    }
    static Map<String, Object> evaluated(Map<String, Object> model) {
        List<Object> projects = new ArrayList<>();
        for (Object item : Json.array(model.get("projects"))) {
            var selected = Json.object(item); var status = project(loaders(selected));
            status.put("path", selected.get("path")); projects.add(status);
        }
        return Json.map("features", features(), "projects", projects, "authoritative", true);
    }
    static Map<String, Object> discovered(Map<String, Object> discovery) {
        List<Object> builds = new ArrayList<>();
        for (Object item : Json.array(discovery.get("builds"))) {
            var hint = Json.object(item); Set<String> loaders = new TreeSet<>();
            if (hint.get("loaderHints") instanceof Collection<?> values) for (Object value : values) loaders.add(value.toString());
            var status = project(loaders);
            status.put("directory", hint.get("directory")); builds.add(status);
        }
        return Json.map("features", features(), "builds", builds, "authoritative", false);
    }
}
