import java.io.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

/** Source-manifest target and guarded undo gate, without Gradle or a game launch. */
public final class FabricMetadataTest {
    public static void main(String[] args) throws Exception {
        String original = args.length < 1 ? """
                {"id":"fixture","version":"${version}","description":"~1.20.1",
                 "entrypoints":{"main":["example.Mod"]},"mixins":["example.mixins.json"],
                 "depends":{"minecraft":"~1.20.1","fabricloader":">=0.14.21"}}
                """ : Files.readString(Path.of(args[0]));
        var before = Json.object(Json.parse(original));
        require("~1.20.1".equals(Json.object(before.get("depends")).get("minecraft")), "real original F3Nope constraint");
        Path root = Files.createTempDirectory("modaccel-fabric-metadata-");
        try {
            Path resources = root.resolve("src/main/resources"); Files.createDirectories(resources);
            Path manifest = resources.resolve("fabric.mod.json"); Files.writeString(manifest, original);
            Map<String, Object> selected = project(root, resources);
            var edits = Target.manifestEdits(selected, "1.21.6");
            require(edits.size() == 1 && edits.get(0).file().equals(manifest), "one owned manifest edit");
            var forge = Json.map("dependencies", List.of(Json.map("group", "net.minecraftforge")));
            require(Target.manifestEditsForModel(Json.map("projects", List.of(forge)), "1.21.6").isEmpty(), "prepared Forge port bypasses Fabric manifest setup");
            selected.put("dependencies", List.of(Json.map("group", "net.fabricmc")));
            require(Target.manifestEditsForModel(Json.map("projects", List.of(forge, selected)), "1.21.6").size() == 1,
                    "prepared multi-project port checks only Fabric manifest");
            var after = Json.object(Json.parse(edits.get(0).after()));
            require("1.21.6".equals(Json.object(after.get("depends")).get("minecraft")), "target exact constraint");
            require(Objects.equals(before.get("version"), after.get("version")) && Objects.equals(before.get("description"), after.get("description"))
                    && Objects.equals(before.get("entrypoints"), after.get("entrypoints"))
                    && Objects.equals(before.get("mixins"), after.get("mixins")), "version placeholder and unrelated fields preserved");
            if (args.length > 1) {
                require(!accepts(args[1], "~1.20.1", "1.21.6"), "Fabric Loader rejects original constraint, first predicate");
                require(!accepts(args[1], "~1.20.1", "1.21.6"), "Fabric Loader rejects original constraint, second predicate");
                require(accepts(args[1], "1.21.6", "1.21.6"), "Fabric Loader accepts target constraint");
            }
            require(Target.manifestEdits(selected, "1.21.6").size() == 1, "planning does not edit source");
            Path transaction = root.resolve(".modaccel/portable/ports/test");
            Path backup = transaction.resolve("before/src/main/resources/fabric.mod.json"); Files.createDirectories(backup.getParent());
            Files.copy(manifest, backup); Map<String, Object> hashes = Map.of("src/main/resources/fabric.mod.json", Fingerprints.bytes(Files.readAllBytes(manifest)));
            Files.writeString(manifest, edits.get(0).after());
            ProjectModel owner = new ProjectModel(root); Port.finishTransaction(owner, transaction, hashes);
            Files.writeString(manifest, edits.get(0).after() + " ");
            try { Port.undo(owner, transaction.toString()); throw new AssertionError("later user edit overwritten"); }
            catch (IOException expected) { require(expected.getMessage().contains("Refusing undo"), "guarded undo refusal"); }
            Files.writeString(manifest, edits.get(0).after());
            Port.undo(owner, transaction.toString());
            require(Arrays.equals(Files.readAllBytes(manifest), original.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "byte-exact manifest restore");
            var placeholder = Json.object(Json.parse(original)); Json.object(placeholder.get("depends")).put("minecraft", "${minecraft_version}");
            Files.writeString(manifest, Json.write(placeholder));
            require(Target.manifestEdits(selected, "1.21.6").isEmpty(), "processed placeholder retains owner");
            Json.object(placeholder.get("depends")).remove("minecraft");
            Files.writeString(manifest, Json.write(placeholder));
            require(Target.manifestEdits(selected, "1.21.6").isEmpty(), "missing Minecraft constraint is not invented");
            Json.object(placeholder.get("depends")).put("minecraft", List.of("~1.20.1", "1.21.6"));
            Files.writeString(manifest, Json.write(placeholder));
            reject(selected, "not one literal string");
            Json.object(placeholder.get("depends")).put("minecraft", ">=1.20.1 <1.22");
            Files.writeString(manifest, Json.write(placeholder));
            require(Target.manifestEdits(selected, "1.21.6").isEmpty(), "range that already accepts the target is left byte-exact");
            Json.object(placeholder.get("depends")).put("minecraft", ">1.18");
            Files.writeString(manifest, Json.write(placeholder));
            require(Target.manifestEdits(selected, "1.21.6").isEmpty(), "real Trinkets wide range needs no rewrite");
            Json.object(placeholder.get("depends")).put("minecraft", ">=1.20.1 <1.21");
            Files.writeString(manifest, Json.write(placeholder));
            reject(selected, "complex constraint");
            Files.writeString(manifest, original);
            Path client = root.resolve("src/client/resources"); Files.createDirectories(client); Files.writeString(client.resolve("fabric.mod.json"), original);
            Json.array(selected.get("sourceSets")).add(Json.map("name", "client", "resources", List.of(client.toString())));
            reject(selected, "Multiple source");
            System.out.println("F3Nope manifest target, guards, and byte-exact undo: PASS");
        } finally {
            try (var paths = Files.walk(root)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }
    static Map<String, Object> project(Path root, Path resources) {
        return Json.map("directory", root.toString(), "sourceSets", new ArrayList<>(List.of(Json.map("name", "main", "resources", List.of(resources.toString())))));
    }
    static void reject(Map<String, Object> selected, String message) throws Exception {
        try { Target.manifestEdits(selected, "1.21.6"); throw new AssertionError(message + " accepted"); }
        catch (IOException expected) { require(expected.getMessage().contains(message), message + " diagnostic"); }
    }
    @SuppressWarnings("unchecked")
    static boolean accepts(String jar, String predicate, String version) throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(jar).toUri().toURL()})) {
            Class<?> rule = loader.loadClass("net.fabricmc.loader.api.metadata.version.VersionPredicate");
            Class<?> value = loader.loadClass("net.fabricmc.loader.api.Version");
            Object parsed = rule.getMethod("parse", String.class).invoke(null, predicate);
            Object game = value.getMethod("parse", String.class).invoke(null, version);
            return ((java.util.function.Predicate<Object>)parsed).test(game);
        }
    }
    static void require(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
