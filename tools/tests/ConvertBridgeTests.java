import java.nio.file.*;
import java.util.*;

/** convert's bridges (knowledge/loaders.json bridges[].tests), part of the convert suite (ConvertTests). */
final class ConvertBridgeTests {

    private ConvertBridgeTests() {}

    /**
     * Each bridge's before/after tests (bridges[].tests): the tests for one Minecraft version are files of one small mod of
     * the bridge's from-loader (a test marked alone gets its own), converted (no compile): each file must read as its "to",
     * listing its "mixins" and injecting its "injects" (an interface, or CLASS=INTERFACE for the class it goes on): in
     * fabric.mod.json for Loom, or in ModDevGradle's interface injection data. Converted back, each file reads as its "from"
     * (or "back"). A Fabric mod's fabric.mod.json keeps, through NeoForge and back, the entrypoints and custom values other
     * mods read, and the bridge and its interface injection data are gone.
     */
    static void run(Path home, Path scratch, Map<String, Object> rules, Map<String, String> answers) throws Exception {
        Map<String, String> seeded = new LinkedHashMap<>(answers);
        seeded.put("https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml", "<metadata><versioning><versions><version>0.116.18+1.21.1</version><version>0.161.0+26.3</version><version>0.166.2+26.3</version><version>0.150.0+26.1.2</version></versions></versioning></metadata>");
        seeded.put("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml", "<metadata><versioning><versions><version>21.1.250</version><version>26.3.0.90</version><version>26.3.0.103</version><version>26.1.2.126</version></versions></versioning></metadata>");
        String fabricMeta = "{\n\t\"schemaVersion\": 1,\n\t\"id\": \"demo\",\n\t\"version\": \"1.0.0\",\n\t\"entrypoints\": {\n\t\t\"main\": [\"demo.Demo\"],\n\t\t\"demo\": [\"demo.Demo\"]\n\t},\n\t\"custom\": {\n\t\t\"demo\": {\n\t\t\t\"badge\": true\n\t\t}\n\t}\n}\n";
        int tested = 0;
        for (Object item : LoaderRules.list(rules.get("bridges"))) {
            var b = Json.object(item);
            boolean fromNeo = "neoforge".equals(b.get("from"));
            Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            for (Object t : LoaderRules.list(b.get("tests"))) {
                var test = Json.object(t);
                String key = Json.text(test, "minecraft", "26.3") + (Boolean.TRUE.equals(test.get("alone")) ? "-" + Json.text(test, "name", "") : "");
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(test);
            }
            for (var group : groups.entrySet()) {
                String game = group.getKey().replaceAll("-.*$", "");
                Path dir = scratch.resolve("bridge-" + b.get("id") + "-" + group.getKey());
                Map<String, String> mod = new LinkedHashMap<>();
                mod.put("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n");
                mod.put("settings.gradle", "rootProject.name = 'demo'\n");
                if (fromNeo) {
                    mod.put("gradle.properties", "minecraft_version=" + game + "\nneo_version=" + ("1.21.1".equals(game) ? "21.1.250" : "26.3.0.103") + "\nmod_id=demo\n");
                    mod.put("build.gradle", "plugins {\n\tid 'net.neoforged.moddev' version '2.0.148'\n}\n\nneoForge {\n\tversion = project.neo_version\n}\n\ndependencies {\n}\n");
                    mod.put("src/main/resources/META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n\n[[mods]]\nmodId=\"demo\"\nversion=\"1.0.0\"\n");
                    mod.put("src/main/java/demo/Demo.java", "package demo;\n\nimport net.neoforged.fml.common.Mod;\n\n@Mod(\"demo\")\npublic class Demo {\n}\n");
                } else {
                    mod.put("gradle.properties", "minecraft_version=" + game + "\nloader_version=0.19.5\nfabric_api_version=0.161.0+26.3\n");
                    mod.put("build.gradle", "plugins {\n\tid 'net.fabricmc.fabric-loom' version '1.18.2'\n}\n\ndependencies {\n\tminecraft \"com.mojang:minecraft:${project.minecraft_version}\"\n\timplementation \"net.fabricmc:fabric-loader:${project.loader_version}\"\n\timplementation \"net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}\"\n}\n");
                    mod.put("src/main/resources/fabric.mod.json", fabricMeta);
                    mod.put("src/main/java/demo/Demo.java", "package demo;\n\nimport net.fabricmc.api.ModInitializer;\n\npublic class Demo implements ModInitializer {\n\t@Override\n\tpublic void onInitialize() {\n\t}\n}\n");
                }
                for (var test : group.getValue()) mod.put("src/main/java/demo/" + Json.text(test, "name", "") + ".java", Json.text(test, "from", ""));
                ProjectModel model = ConvertTests.fixture(dir, mod, seeded);
                String to = fromNeo ? "fabric" : "neoforge", origin = fromNeo ? "neoforge" : "fabric";
                String forward = String.valueOf(new Convert(new Build(model, home, "", new Mmt.Report(dir, "convert"))).run(new Mmt.Options(new String[] { "convert", "--to", to }), false).get("details"));
                Map<String, Object> injected = injected(dir, fromNeo);
                for (var test : group.getValue()) {
                    String name = Json.text(test, "name", ""), converted = Files.readString(dir.resolve("src/main/java/demo/" + name + ".java"));
                    // The @Mod constructor starts the Fabric bridge; Demo, also the "demo" entrypoint, keeps a no-argument one.
                    String demo = Files.readString(dir.resolve("src/main/java/demo/Demo.java"));
                    boolean started = fromNeo || demo.contains("FabricBridge.init(modBus);") && demo.contains(Convert.KEPT_CONSTRUCTOR + "\n\tDemo() {\n\t}\n");
                    ConvertTests.check(
                        started && converted.equals(Json.text(test, "to", "")) &&
                        LoaderRules.strings(test.get("mixins")).stream().allMatch(forward::contains) &&
                        LoaderRules.strings(test.get("injects")).stream().allMatch(t -> injects(injected, t)),
                        "bridge " + b.get("id") + " test " + name + " to " + to + ":\n" + converted + forward + "\ninjected: " + Json.write(injected)
                    );
                }
                String back = String.valueOf(new Convert(new Build(model, home, "", new Mmt.Report(dir, "convert"))).run(new Mmt.Options(new String[] { "convert", "--to", origin }), false).get("details"));
                for (var test : group.getValue()) {
                    String name = Json.text(test, "name", ""), again = Files.readString(dir.resolve("src/main/java/demo/" + name + ".java"));
                    ConvertTests.check(again.equals(Json.text(test, "back", Json.text(test, "from", ""))), "bridge " + b.get("id") + " test " + name + " back to " + origin + ":\n" + again + back);
                    tested++;
                }
                if (!fromNeo) {
                    var meta = Json.object(Json.parse(Files.readString(dir.resolve("src/main/resources/fabric.mod.json"))));
                    String gradle = Files.readString(dir.resolve("build.gradle"));
                    ConvertTests.check(
                        Json.write(meta.get("custom")).equals(Json.write(Json.object(Json.parse(fabricMeta)).get("custom"))) &&
                        Json.write(Json.object(meta.get("entrypoints")).get("demo")).equals("[\"demo.Demo\"]") &&
                        Files.readString(dir.resolve("src/main/java/demo/Demo.java")).equals(mod.get("src/main/java/demo/Demo.java")) &&
                        !Files.exists(dir.resolve("src/main/java/demo/fabricbridge")) &&
                        !Files.exists(dir.resolve(Convert.INJECTED_INTERFACES)) && !gradle.contains("interfaceInjectionData"),
                        "back to Fabric, the entrypoints and custom values other mods read are fabric.mod.json's again and the bridge is gone: " + Json.write(meta) + "\n" + gradle
                    );
                }
            }
        }
        ConvertTests.check(tested >= 20, "every bridge test converts its source both ways (" + tested + " tests)");
    }

    /**
     * The interfaces the conversion injects into game classes, by class: fabric.mod.json's loom:injected_interfaces on
     * Fabric; on NeoForge, ModDevGradle's interface injection data, read only when build.gradle names it.
     */
    static Map<String, Object> injected(Path dir, boolean toFabric) throws Exception {
        if (toFabric) {
            Object custom = Json.object(Json.parse(Files.readString(dir.resolve("src/main/resources/fabric.mod.json")))).get("custom");
            Object found = custom instanceof Map<?, ?> map ? map.get("loom:injected_interfaces") : null;
            return found instanceof Map<?, ?> ? Json.object(found) : Map.of();
        }
        Path data = dir.resolve(Convert.INJECTED_INTERFACES);
        boolean named = Files.readString(dir.resolve("build.gradle")).contains("interfaceInjectionData {\n\t\tfrom(file('" + Convert.INJECTED_INTERFACES + "'))");
        return named && Files.isRegularFile(data) ? Json.object(Json.parse(Files.readString(data))) : Map.of();
    }

    /** Whether the injected interfaces name this (a game class or an interface), or hold CLASS=INTERFACE. */
    static boolean injects(Map<String, Object> injected, String expected) {
        int at = expected.indexOf('=');
        for (var e : injected.entrySet()) {
            List<String> names = LoaderRules.strings(e.getValue());
            if (at < 0 ? e.getKey().equals(expected) || names.contains(expected) : e.getKey().equals(expected.substring(0, at)) && names.contains(expected.substring(at + 1))) return true;
        }
        return false;
    }
}
