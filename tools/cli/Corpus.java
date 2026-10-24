import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Reuse the strict learner and its existing corpus; never learn the target mod's answer. */
final class Corpus {
    static Path rules(Build build, Map<String, Object> model, JavaTools tools, Path map, String from, String to, String names) throws Exception {
        Set<String> self = new TreeSet<>();
        Path remoteLog = build.report.log("origin");
        try {
            if (Files.exists(build.project.root.resolve(".git"))) {
                var run = Run.command(List.of("git", "remote", "get-url", "origin"), build.project.root, Map.of(), remoteLog, 10);
                if (run.exit() == 0) identify(Files.readString(remoteLog), self);
            }
        } catch (IOException noGit) { }
        for (Object p : Json.array(model.get("projects"))) for (Object set : Json.array(Json.object(p).get("sourceSets")))
            for (String resource : ProjectModel.strings(Json.object(set).get("resources"))) {
                Path metadata = Path.of(resource).resolve("fabric.mod.json");
                if (Files.isRegularFile(metadata)) {
                    var contact = Json.object(Json.read(metadata).getOrDefault("contact", Map.of()));
                    identify(Json.text(contact, "sources", ""), self);
                }
            }
        if (self.isEmpty()) { build.report.stages.add(Json.map("name", "corpus", "skipped", "No source repository identity; refusing possible target-answer leakage")); return null; }
        List<String> args = new ArrayList<>(List.of("--map", map.toString())); List<Path> inputs = new ArrayList<>(List.of(map, build.harness.resolve("tools/VersionMap.java"), build.harness.resolve("knowledge/port-corpus.json")));
        List<Object> evidence = new ArrayList<>(); long started = System.nanoTime();
        for (Object item : Json.array(Json.read(build.harness.resolve("knowledge/port-corpus.json")).get("repos"))) {
            var entry = Json.object(item); String repo = entry.get("repo").toString();
            if (self.contains(repo.toLowerCase(Locale.ROOT))) { evidence.add(Json.map("repo", repo, "excluded", "target mod")); continue; }
            String a = entry.get("branch").toString().replace("{v}", from), b = entry.get("branch").toString().replace("{v}", to);
            try {
                String props = Target.fetch(build, "https://api.github.com/repos/" + repo + "/contents/gradle.properties?ref=" + URLEncoder.encode(b, StandardCharsets.UTF_8));
                String text = new String(Base64.getMimeDecoder().decode(Json.text(Json.object(Json.parse(props)), "content", "")), StandardCharsets.UTF_8);
                String namespace = Pattern.compile("(?m)^\\s*yarn_(?:mappings|version)\\s*=").matcher(text).find() ? "yarn" : "mojmap";
                if (!namespace.equals(names)) { evidence.add(Json.map("repo", repo, "skipped", "different mapping namespace")); continue; }
                String diff = Target.fetch(build, "https://github.com/" + repo + "/compare/" + URLEncoder.encode(a, StandardCharsets.UTF_8) + "..." + URLEncoder.encode(b, StandardCharsets.UTF_8) + ".diff");
                if (!diff.contains("diff --git ")) throw new IOException("No usable diff");
                Path file = build.project.state().resolve("corpus/" + repo.replace('/', '_') + "-" + from + "-" + to + ".diff");
                Files.createDirectories(file.getParent()); Files.writeString(file, diff); inputs.add(file); args.addAll(List.of("--learn", file.toString()));
                evidence.add(Json.map("repo", repo, "from", a, "to", b, "diff", file.toString()));
            } catch (IOException unavailable) { evidence.add(Json.map("repo", repo, "skipped", unavailable.getMessage())); }
        }
        build.report.stages.add(Json.map("name", "corpus", "seconds", (System.nanoTime() - started) / 1e9, "identity", self, "sources", evidence));
        if (args.size() == 2) return null;
        Path rules = build.project.state().resolve("learned/" + Fingerprints.files(inputs) + ".json");
        if (!Files.isRegularFile(rules)) {
            Files.createDirectories(rules.getParent());
            args.addAll(List.of("--out", rules.toString())); var learned = tools.run("VersionMap", args, "learn-rewrites");
            if (learned.exit() != 0) throw new IOException("Corpus learning failed: " + Run.tail(learned.log()));
        }
        return rules;
    }
    static void identify(String text, Set<String> result) {
        Matcher matcher = Pattern.compile("(?i)github\\.com[:/]([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)").matcher(text);
        while (matcher.find()) result.add(matcher.group(1).replaceFirst("\\.git$", "").toLowerCase(Locale.ROOT));
    }
}
