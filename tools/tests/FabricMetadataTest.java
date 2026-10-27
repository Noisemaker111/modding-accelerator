import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Source-manifest target and guarded undo gate, without Gradle or a game launch. */
public final class FabricMetadataTest {
    public static void main(String[] args) throws Exception {
        String original = """
                {"id":"fixture","version":"${version}","description":"~1.20.1",
                 "entrypoints":{"main":["example.Mod"]},"mixins":["example.mixins.json"],
                 "depends":{"minecraft":"~1.20.1","fabricloader":">=0.14.21"}}
                """;
        var before = Json.object(Json.parse(original));
        Path root = Files.createTempDirectory("modaccel-fabric-metadata-").toRealPath();
        try {
            Path resources = root.resolve("src/main/resources"); Files.createDirectories(resources);
            Path manifest = resources.resolve("fabric.mod.json"); Files.writeString(manifest, original);
            Map<String, Object> selected = project(root, resources);
            var edits = Target.manifestEdits(selected, "1.21.6");
            require(edits.size() == 1 && edits.get(0).file().equals(manifest.toRealPath()), "one owned manifest edit");
            var forge = Json.map("dependencies", List.of(Json.map("group", "net.minecraftforge")));
            require(Target.manifestEditsForModel(Json.map("projects", List.of(forge)), "1.21.6").isEmpty(), "prepared Forge port bypasses Fabric manifest setup");
            selected.put("dependencies", List.of(Json.map("group", "net.fabricmc")));
            require(Target.manifestEditsForModel(Json.map("projects", List.of(forge, selected)), "1.21.6").size() == 1,
                    "prepared multi-project port checks only Fabric manifest");
            require(edits.get(0).after().equals(original.replaceFirst("(\"minecraft\"\\s*:\\s*\")~1\\.20\\.1\"", "$11.21.6\"")),
                    "only the depends.minecraft literal changes; formatting and other fields stay byte-exact");
            var after = Json.object(Json.parse(edits.get(0).after()));
            require("1.21.6".equals(Json.object(after.get("depends")).get("minecraft")), "target exact constraint");
            require(Objects.equals(before.get("version"), after.get("version")) && Objects.equals(before.get("description"), after.get("description"))
                    && Objects.equals(before.get("entrypoints"), after.get("entrypoints"))
                    && Objects.equals(before.get("mixins"), after.get("mixins")), "version placeholder and unrelated fields preserved");
            require(Files.readString(manifest).equals(original), "planning does not edit source");
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
            // A formatted manifest keeps its layout: one line changes, not a re-serialized file.
            Files.delete(client.resolve("fabric.mod.json"));
            Json.array(selected.get("sourceSets")).remove(Json.array(selected.get("sourceSets")).size() - 1);
            String formatted = """
                    {
                    	"schemaVersion": 1,
                    	"id": "fixture",
                    	"description": "~1.20.1 fixes",
                    	"suggests": {
                    		"minecraft": "~1.20.1"
                    	},
                    	"depends": {
                    		"fabricloader": ">=0.14",
                    		"minecraft": "~1.20.1"
                    	}
                    }
                    """;
            Files.writeString(manifest, formatted);
            var formattedEdits = Target.manifestEdits(selected, "1.21.6");
            require(formattedEdits.size() == 1, "formatted manifest edited once");
            int dependsAt = formatted.indexOf("\"depends\"");
            String expected = formatted.substring(0, dependsAt) + formatted.substring(dependsAt).replace("\"minecraft\": \"~1.20.1\"", "\"minecraft\": \"1.21.6\"");
            require(formattedEdits.get(0).after().equals(expected),
                    "formatted manifest: only depends.minecraft changes, suggests and description untouched");
            // x-ranges are Fabric constraints too.
            require(Versions.matches("1.21.x", "1.21.6") && !Versions.matches("1.20.x", "1.21.6") && Versions.matches(">=1.21.x", "1.21.6"), "x-range constraints");
            // The packaged jar decides whether Fabric loads the mod, not the source manifest.
            Path libs = root.resolve("build/libs"); Files.createDirectories(libs);
            jar(libs.resolve("fixture-1.0-sources.jar"), "{\"depends\":{\"minecraft\":\"1.21.6\"}}");
            jar(libs.resolve("fixture-1.0.jar"), "{\"depends\":{\"minecraft\":\">=1.21.5 <1.21.6\"}}");
            require(Boolean.FALSE.equals(Port.verifyFabricMetadata(root, "1.21.6").get("ok")), "packaged range that refuses the target fails");
            jar(libs.resolve("fixture-1.0.jar"), "{\"depends\":{\"minecraft\":[\">=1.21.5 <1.21.6\",\"1.21.6\"]}}");
            require(Boolean.TRUE.equals(Port.verifyFabricMetadata(root, "1.21.6").get("ok")), "a list accepts the target when one entry does");
            System.out.println("Fabric manifest target and guards: PASS");
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
    static void jar(Path file, String manifest) throws IOException {
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new java.util.zip.ZipEntry("fabric.mod.json")); out.write(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.closeEntry();
        }
    }
    static void require(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
