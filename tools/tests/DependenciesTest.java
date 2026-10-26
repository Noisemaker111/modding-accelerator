import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Offline identity gate for unindexed published mod dependencies. */
public final class DependenciesTest {
    static final String SOURCE = "https://github.com/Ladysnake/Cardinal-Components-API";
    static final String NAME = "Cardinal Components API (base)";
    static final String TO = "1.21.6";
    public static void main(String[] args) throws Exception {
        var found = Dependencies.findPublishedProject(SOURCE, NAME, TO, fetch(Map.of(
                NAME, List.of(), "Cardinal-Components-API", List.of("K01OU20C")),
                Map.of("K01OU20C", project(SOURCE, "fabric", TO))));
        require("K01OU20C".equals(found.get("project_id")), "repository basename resolves exact source");
        var queries = Map.of(NAME, List.of("wrong", "forge", "old"), "Cardinal-Components-API", List.of("right"));
        var filtered = Dependencies.findPublishedProject(SOURCE, NAME, TO, fetch(queries, Map.of(
                "wrong", project("https://github.com/Other/Cardinal-Components-API", "fabric", TO),
                "forge", project(SOURCE, "forge", TO),
                "old", project(SOURCE, "fabric", "1.20.1"),
                "right", project(SOURCE + ".git", "fabric", TO))));
        require("right".equals(filtered.get("project_id")), "wrong source, loader, and game version filtered");
        reject("wrong source", Map.of("wrong", project("https://github.com/Other/Cardinal-Components-API", "fabric", TO)));
        reject("wrong loader", Map.of("wrong", project(SOURCE, "forge", TO)));
        reject("wrong version", Map.of("wrong", project(SOURCE, "fabric", "1.20.1")));
        try { Dependencies.findPublishedProject(SOURCE, NAME, TO, fetch(
                Map.of(NAME, List.of("one"), "Cardinal-Components-API", List.of("two")),
                Map.of("one", project(SOURCE, "fabric", TO), "two", project(SOURCE, "fabric", TO))));
            throw new AssertionError("ambiguous source accepted");
        } catch (IOException expected) { require(expected.getMessage().contains("Multiple"), "ambiguous source diagnostic"); }
        var exact = Dependencies.publishedProject(null, "digest", TO, url -> {
            if (!url.contains("/version_file/digest?")) throw new AssertionError("exact hash must win before search");
            return Json.write(Json.map("project_id", "exact"));
        });
        require("exact".equals(exact.get("project_id")), "exact artifact hash retains priority");
        String terraform = "https://github.com/TerraformersMC/Terraform";
        Path jar = jarWithHomepage(terraform);
        try {
            var home = Dependencies.publishedProject(jar, "unindexed", TO, unindexed(fetch(
                    Map.of("Terraform Module", List.of(), "Terraform", List.of("terraform")),
                    Map.of("terraform", project(terraform, "fabric", TO)))));
            require("terraform".equals(home.get("project_id")), "direct GitHub homepage resolves published source");
            try { Dependencies.publishedProject(jar, "unindexed", TO, unindexed(fetch(
                    Map.of("Terraform Module", List.of(), "Terraform", List.of("wrong")),
                    Map.of("wrong", project("https://github.com/Other/Terraform", "fabric", TO)))));
                throw new AssertionError("homepage wrong repository accepted");
            } catch (IOException expected) { require(expected.getMessage().contains("No published Fabric project"), "homepage wrong repository diagnostic"); }
            try { Dependencies.publishedProject(jar, "unindexed", TO, unindexed(fetch(
                    Map.of("Terraform Module", List.of("one"), "Terraform", List.of("two")),
                    Map.of("one", project(terraform, "fabric", TO), "two", project(terraform, "fabric", TO)))));
                throw new AssertionError("homepage ambiguous projects accepted");
            } catch (IOException expected) { require(expected.getMessage().contains("Multiple"), "homepage ambiguity diagnostic"); }
        } finally { Files.deleteIfExists(jar); }
        for (String website : List.of("https://terraform.example.com", "https://github.com/TerraformersMC/Terraform/issues",
                "https://github.com/TerraformersMC/Terraform#readme", "http://github.com/TerraformersMC/Terraform")) {
            require(Dependencies.directGithubRepository(website).isEmpty(), "homepage website rejected: " + website);
        }
        Path websiteJar = jarWithHomepage("https://terraform.example.com");
        try { Dependencies.publishedProject(websiteJar, "unindexed", TO, unindexed(url -> { throw new AssertionError("website must stop before search"); }));
            throw new AssertionError("website homepage accepted");
        } catch (IOException expected) { require(expected.getMessage().contains("no direct source repository"), "website homepage diagnostic"); }
        finally { Files.deleteIfExists(websiteJar); }
        selections();
        modrinthIds();
        versions();
        mixinTargets();
        loaders();
        System.out.println("unindexed dependency identity, homepage, ambiguity, exact hash, explicit selection, version ranges, dead mixin targets and loader guards: PASS");
    }
    static void modrinthIds() {
        Map<String, Object> current = Json.map("id", "S5y2NO2L", "version_number", "21.5.0.1");
        Map<String, Object> fabric = Json.map("id", "i9QFEv4n", "version_number", "21.6.0.0");
        List<Object> multiloader = List.of(Json.map("id", "FuJOYqfx", "version_number", "21.6.0.0"), fabric, Json.map("id", "i0GLDKIN", "version_number", "21.6.0.0"));
        require(Dependencies.modrinthNeedsId("21.5.0.1", current, fabric, multiloader), "a version number shared by several loaders uses the exact Modrinth id");
        require(Dependencies.modrinthNeedsId("S5y2NO2L", current, fabric, List.of(fabric)), "a project that pinned a Modrinth id keeps an id");
        require(!Dependencies.modrinthNeedsId("21.5.0.1", current, fabric, List.of(fabric)), "a unique version number stays a version number");
    }
    static void mixinTargets() throws Exception {
        String text = "@ModifyReturnValue(method = {\"method_5260\", \"method_5236\"}, at = @At(\"RETURN\"))\n";
        require(Port.removeDeadTarget(text, "method_5236").equals("@ModifyReturnValue(method = {\"method_5260\"}, at = @At(\"RETURN\"))\n"),
                "dead target removed from a multi-target mixin annotation");
        try { Port.removeDeadTarget(text, "method_9999"); throw new AssertionError("missing annotation accepted"); }
        catch (IOException expected) { require(expected.getMessage().contains("no single-line multi-target"), "unknown mixin target refused"); }
        try { Port.removeDeadTarget("@ModifyReturnValue(method = \"method_5236\")", "method_5236"); throw new AssertionError("single target removed"); }
        catch (IOException expected) { require(expected.getMessage().contains("no single-line multi-target"), "single-target annotation needs a person"); }
    }
    /** An explicit selection is verified, not trusted: publication, target-game support, and disable proof. */
    static void selections() throws Exception {
        Path file = Files.createTempFile("modaccel-selection-", ".json");
        try {
            Files.writeString(file, Json.write(Json.map("schema", 1, "selections", List.of(
                    Json.map("source", "me.shedaniel:RoughlyEnoughItems-api-fabric:18.0.796", "target", "me.shedaniel:RoughlyEnoughItems-api-fabric:20.0.811",
                            "modrinth", "rei", "repository", "https://maven.shedaniel.me/"),
                    Json.map("source", "dev.emi:emi-fabric:1.1.16+1.21.1", "disable", true, "modrinth", "emi", "reason", "EMI has no 1.21.6 release")))));
            var loaded = Dependencies.selections(file);
            require(loaded.size() == 2, "selection file entries load");
            require(loaded.get(0).matches("me.shedaniel", "RoughlyEnoughItems-api-fabric", "18.0.796"), "selection matches its exact source version");
            require(!loaded.get(0).matches("me.shedaniel", "RoughlyEnoughItems-api-fabric", "18.0.1"), "selection ignores another source version");
            require(!loaded.get(0).matches("other", "RoughlyEnoughItems-api-fabric", "18.0.796"), "selection ignores another artifact");
            require(loaded.get(1).disable() && loaded.get(1).reason().contains("1.21.6"), "disabled selection keeps its reason");

            String[] target = Dependencies.coordinate("me.shedaniel:RoughlyEnoughItems-api-fabric:20.0.811");
            Map<String, Object> project = Json.map("repositories", List.of("https://maven.shedaniel.me/"));
            var verified = Dependencies.verify(loaded.get(0), target, project, TO, url -> {
                if (url.endsWith(".pom")) return "<project/>";
                if (url.contains("/version?")) return Json.write(List.of(Json.map("version_number", "20.0.811+fabric")));
                throw new AssertionError("unexpected selection lookup " + url);
            }, url -> { throw new AssertionError("published project evidence needs no artifact download"); });
            require("rei".equals(verified.get("publishedProject")), "published project recorded as evidence");
            require("20.0.811+fabric".equals(verified.get("publishedVersion")) && TO.equals(verified.get("gameVersion")), "selection records its target-game evidence");

            try {
                Dependencies.verify(loaded.get(0), target, project, TO, url -> {
                    if (url.endsWith(".pom")) return "<project/>";
                    if (url.contains("/version?")) return Json.write(List.of(Json.map("version_number", "20.0.810+fabric")));
                    throw new AssertionError("unexpected selection lookup " + url);
                }, url -> { throw new AssertionError("unexpected download"); });
                throw new AssertionError("unpublished selected version accepted");
            } catch (IOException expected) { require(expected.getMessage().contains("no Fabric 20.0.811 release"), "unpublished selected version diagnostic"); }

            try {
                Dependencies.verify(loaded.get(0), target, Json.map("repositories", List.of("https://example.invalid/")), TO,
                        url -> { throw new IOException("HTTP 404"); }, url -> { throw new AssertionError("unexpected download"); });
                throw new AssertionError("unpublished coordinate accepted");
            } catch (IOException expected) { require(expected.getMessage().contains("is not published in"), "unpublished coordinate diagnostic"); }

            var manifestOnly = new Dependencies.Selection("me.shedaniel:example:1.0", "me.shedaniel:example:2.0", "https://maven.shedaniel.me/", "", false, "");
            Path jar = jarWithMinecraft(">=1.21.6 <1.22");
            try {
                var evidence = Dependencies.verify(manifestOnly, Dependencies.coordinate(manifestOnly.target()), Json.map("repositories", List.of("https://maven.shedaniel.me/")), TO,
                        url -> "<project/>", url -> jar);
                require(">=1.21.6 <1.22".equals(evidence.get("minecraftConstraint")), "artifact manifest proves the selected target game");
            } finally { Files.deleteIfExists(jar); }
            Path old = jarWithMinecraft("~1.20.1");
            try {
                Dependencies.verify(manifestOnly, Dependencies.coordinate(manifestOnly.target()), Json.map("repositories", List.of("https://maven.shedaniel.me/")), TO, url -> "<project/>", url -> old);
                throw new AssertionError("artifact without target support accepted");
            } catch (IOException expected) { require(expected.getMessage().contains("excludes 1.21.6"), "artifact without target support diagnostic"); }
            finally { Files.deleteIfExists(old); }

            var unavailable = Dependencies.unavailableProject("emi", TO, url -> Json.write(Json.map("game_versions", List.of("1.21", "1.21.1"))));
            require(!ProjectModel.strings(unavailable.get("game_versions")).contains(TO), "disable path accepts a project with no target release");
            try { Dependencies.unavailableProject("rei", TO, url -> Json.write(Json.map("game_versions", List.of(TO)))); throw new AssertionError("supported project disabled"); }
            catch (IOException expected) { require(expected.getMessage().contains("supports 1.21.6"), "supported project disable refusal"); }

            Files.writeString(file, Json.write(Json.map("selections", List.of(Json.map("source", "a:b:1.0", "disable", true, "modrinth", "a")))));
            try { Dependencies.selections(file); throw new AssertionError("disable without reason accepted"); }
            catch (IOException expected) { require(expected.getMessage().contains("needs a reason"), "disable reason required"); }
            Files.writeString(file, Json.write(Json.map("selections", List.of(Json.map("source", "a:b:1.0", "target", "a:b:2.0")))));
            try { Dependencies.selections(file); throw new AssertionError("unverifiable selection accepted"); }
            catch (IOException expected) { require(expected.getMessage().contains("needs either modrinth"), "selection evidence required"); }
        } finally { Files.deleteIfExists(file); }
    }
    /** The evaluated plugin decides the loader; a loader also drags in other groups' APIs. */
    static void loaders() throws Exception {
        var project = Json.map("plugins", List.of("net.neoforged.gradle.userdev.plugins.UserDevPlugin"),
                "dependencies", List.of(Json.map("group", "net.fabricmc"), Json.map("group", "net.minecraftforge"), Json.map("group", "net.neoforged")));
        require(Support.loaders(project).equals(Set.of("neoforge")), "the evaluated plugin wins over stray dependency groups");
        require(Support.loaders(Json.map("dependencies", List.of(Json.map("group", "net.fabricmc")))).equals(Set.of("fabric")), "dependency groups still decide when no plugin matches");
        require(Target.neoForgePrefix("1.21.6").equals("21.6.") && Target.neoForgePrefix("1.20.1").equals("47."), "NeoForge numbering follows the Minecraft minor");
        Map<String, Object> good = Json.map("projects", List.of(Json.map("dependencies", List.of(Json.map("group", "net.neoforged", "name", "neoforge", "version", "21.6.9")))));
        Port.verifyLoaderVersion(good, "1.21.6");
        Map<String, Object> stale = Json.map("projects", List.of(Json.map("dependencies", List.of(Json.map("group", "net.neoforged", "name", "neoforge", "version", "21.1.21")))));
        try { Port.verifyLoaderVersion(stale, "1.21.6"); throw new AssertionError("stale NeoForge accepted"); }
        catch (IOException expected) { require(expected.getMessage().contains("does not target 1.21.6"), "a build that keeps the old NeoForge is refused"); }
    }
    static void versions() {        require(Versions.matches(">1.18", TO), "wide lower bound accepts the target");
        require(Versions.matches(">=1.20.1 <1.22", TO), "two-term range accepts the target");
        require(!Versions.matches(">=1.20.1 <1.21", TO), "excluded upper bound rejects the target");
        require(Versions.matches("~1.21.2", TO) && !Versions.matches("~1.20.1", TO), "tilde stays inside its minor version");
        require(Versions.matches("1.21.6", TO) && !Versions.matches("1.21.5", TO), "plain version is exact");
        require(Versions.matches("*", TO), "wildcard accepts any version");
        require(Versions.compare("1.21", "1.21.0") == 0 && Versions.compare("1.21.6", "1.21.10") < 0, "numeric version ordering");
    }
    static Path jarWithMinecraft(String constraint) throws Exception {
        Path jar = Files.createTempFile("modaccel-manifest-", ".jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(Json.write(Json.map("id", "example", "depends", Json.map("minecraft", constraint))).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return jar;
    }
    static Dependencies.Fetch unindexed(Dependencies.Fetch next) {
        return url -> { if (url.contains("/version_file/")) throw new IOException("HTTP 404"); return next.get(url); };
    }
    static Path jarWithHomepage(String homepage) throws Exception {
        Path jar = Files.createTempFile("modaccel-dependency-", ".jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(Json.write(Json.map("id", "terraform", "name", "Terraform Module", "contact", Json.map("homepage", homepage))).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return jar;
    }
    static void reject(String label, Map<String, Map<String, Object>> projects) throws Exception {
        try { Dependencies.findPublishedProject(SOURCE, NAME, TO, fetch(
                Map.of(NAME, List.of("wrong"), "Cardinal-Components-API", List.of()), projects));
            throw new AssertionError(label + " accepted");
        } catch (IOException expected) { require(expected.getMessage().contains("No published Fabric project"), label + " diagnostic"); }
    }
    static Dependencies.Fetch fetch(Map<String, List<String>> queries, Map<String, Map<String, Object>> projects) {
        return url -> {
            String prefix = "https://api.modrinth.com/v2/search?query=";
            if (url.startsWith(prefix)) {
                String encoded = url.substring(prefix.length()).split("&", 2)[0];
                String query = java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
                List<Object> hits = new ArrayList<>();
                for (String id : queries.getOrDefault(query, List.of())) hits.add(Json.map("project_id", id));
                return Json.write(Json.map("hits", hits));
            }
            String id = url.substring("https://api.modrinth.com/v2/project/".length());
            if (!projects.containsKey(id)) throw new IOException("Unexpected project " + id);
            return Json.write(projects.get(id));
        };
    }
    static Map<String, Object> project(String source, String loader, String version) {
        return Json.map("source_url", source, "loaders", List.of(loader), "game_versions", List.of(version));
    }
    static void require(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
