import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Everything about a perf-optimize experiment that can be wrong without a game: missing snippet, probe and
 * world files, metrics and scopes naming probes the probe file lacks, gates still holding the template's
 * placeholder, exact gates that read clocks or randomness, a frame metric on a hidden client, the C1-only
 * JIT, and a patch that does not apply. perf-optimize runs it before making worktrees, so a typo costs
 * seconds instead of a launch; --check runs only this.
 */
final class PerfCheck {
    /** Written into generated snippets; a gate still holding it was never written for the mod. */
    static final String TEMPLATE_MARK = "MODACCEL-TEMPLATE";
    static final Pattern NONDETERMINISTIC = Pattern.compile(
        "currentTimeMillis|nanoTime|Math\\.random|new Random\\(\\)|ThreadLocalRandom|identityHashCode|UUID\\.randomUUID|Instant\\.now|getRandom\\(\\)");
    static final Pattern FRAME_METRIC = Pattern.compile("(?i)frame|fps");

    final List<String> problems = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();
    final Path dir;

    PerfCheck(Path experimentFile) {
        dir = experimentFile.toAbsolutePath().getParent();
    }

    /** problems block a run; warnings are printed with it. patch and root may be null. */
    static Map<String, Object> check(Path experimentFile, Map<String, Object> experiment, Path root, Path patch, Map<String, Object> preferences) {
        PerfCheck check = new PerfCheck(experimentFile);
        check.run(experiment, root, patch, preferences);
        return Json.map("ok", check.problems.isEmpty(), "problems", check.problems, "warnings", check.warnings);
    }

    void run(Map<String, Object> experiment, Path root, Path patch, Map<String, Object> preferences) {
        try {
            PerfOptimize.validate(experiment);
        } catch (RuntimeException invalid) {
            problems.add(invalid.getMessage());
            return;
        }
        Map<String, Object> measure = Json.object(experiment.get("measure"));
        for (Object step : Json.array(experiment.getOrDefault("setup", List.of()))) snippet("setup", Json.object(step), false);
        Map<String, Object> before = Json.object(measure.getOrDefault("before", Map.of()));
        for (var phase : before.entrySet()) {
            List<Object> steps = phase.getValue() instanceof List<?> list ? Json.array(list) : List.of(phase.getValue());
            for (Object step : steps) snippet("before." + phase.getKey(), Json.object(step), false);
        }
        for (Object item : Json.array(experiment.get("gates"))) {
            Map<String, Object> gate = Json.object(item);
            String compare = Json.text(gate, "compare", Boolean.TRUE.equals(gate.get("frame")) ? "pixels" : "exact");
            if (gate.containsKey("file")) snippet("gate " + gate.get("name"), gate, compare.equals("exact") || compare.equals("tolerance"));
        }
        for (var entry : Json.object(experiment.getOrDefault("files", Map.of())).entrySet()) {
            if (!Files.isRegularFile(dir.resolve(entry.getValue().toString()))) problems.add("files: " + entry.getValue() + " (for " + entry.getKey() + ") does not exist");
        }
        String world = Json.text(experiment, "world", "");
        if (!world.isBlank()) {
            String source = Json.text(experiment, "worldSource", "");
            if (source.isBlank()) problems.add("world " + world + " needs worldSource, the save folder each launch copies");
            else if (!Files.isRegularFile(dir.resolve(source).resolve("level.dat"))) {
                problems.add("worldSource " + source + " is not a save folder with level.dat; make the world once and copy it there");
            }
        }
        Set<String> timed = probes(measure);
        List<Map<String, Object>> metrics = new ArrayList<>();
        metrics.add(Json.object(measure.get("metric")));
        for (Object scope : Json.array(measure.getOrDefault("scopes", List.of()))) metrics.add(Json.object(scope));
        for (Map<String, Object> metric : metrics) {
            if (metric.containsKey("probe") && !timed.contains(metric.get("probe").toString())) {
                problems.add("probe " + metric.get("probe") + " is not a timing probe in " + measure.getOrDefault("probes", "the experiment (no probes file)"));
            }
            if (metric.containsKey("threads")) {
                try {
                    Pattern.compile(metric.get("threads").toString());
                } catch (RuntimeException bad) {
                    problems.add("threads is not a regular expression: " + metric.get("threads"));
                }
            }
        }
        Map<String, Object> metric = Json.object(measure.get("metric"));
        boolean hidden = preferences != null && Boolean.TRUE.equals(preferences.get("background"));
        if (hidden && metric.containsKey("report") && FRAME_METRIC.matcher(metric.get("report").toString()).find()) {
            problems.add("metric " + metric.get("report") + " is frame timing, but launches are hidden (preferences background on): a hidden client presents"
                + " about 8 frames per second, so frame time measures the throttle. Measure a timing probe, server ticks or the mod's sampled CPU");
        }
        if (!"full".equals(experiment.get("jit"))) {
            warnings.add("jit is not \"full\": hidden test clients compile with C1 alone, which weighs code differently from a player's game."
                + " Diorama's air-over-ground candidate looked faster under C1 and was 0.77x with the full JIT");
        }
        Object seconds = measure.getOrDefault("seconds", 20);
        if (seconds instanceof Number number && number.doubleValue() < 15) {
            warnings.add("measure.seconds " + number + " is short: sampled CPU at 20 ms per sample needs about 15 s to separate a 10% change");
        }
        if (root != null && patch != null) patch(root, patch);
    }

    /** One eval snippet: present, not the template, returning something, and reproducible when compared exactly. */
    void snippet(String where, Map<String, Object> step, boolean exact) {
        String file = Json.text(step, "file", "");
        if (file.isBlank()) {
            problems.add(where + " has no file");
            return;
        }
        Path path = dir.resolve(file);
        if (!Files.isRegularFile(path)) {
            problems.add(where + ": " + file + " does not exist");
            return;
        }
        String text;
        try {
            text = Files.readString(path);
        } catch (Exception unreadable) {
            problems.add(where + ": " + file + " cannot be read: " + unreadable.getMessage());
            return;
        }
        String thread = Json.text(step, "thread", "game");
        if (!thread.equals("game") && !thread.equals("control")) problems.add(where + ": thread is game or control, not " + thread);
        if (text.contains(TEMPLATE_MARK)) {
            problems.add(where + ": " + file + " is still the generated template; write what it returns for this mod and delete the " + TEMPLATE_MARK + " line");
        }
        String code = text.replaceAll("(?m)//.*$", "").replaceAll("(?s)/\\*.*?\\*/", "");
        if (code.isBlank()) problems.add(where + ": " + file + " has no code");
        if (exact) {
            var found = NONDETERMINISTIC.matcher(code);
            if (found.find()) {
                warnings.add(where + ": " + file + " reads " + found.group() + "; an exact gate fails when two baseline launches disagree,"
                    + " so wait with it only, and return values that do not depend on it");
            }
        }
    }

    /** Labels of the timing probes in the experiment's probe file; problems for a file that is not one. */
    Set<String> probes(Map<String, Object> measure) {
        Set<String> timed = new HashSet<>();
        if (!measure.containsKey("probes")) return timed;
        Path file = dir.resolve(measure.get("probes").toString());
        if (!Files.isRegularFile(file)) {
            problems.add("measure.probes " + measure.get("probes") + " does not exist");
            return timed;
        }
        try {
            List<Object> probes = Json.array(Json.read(file).get("probes"));
            if (probes.size() > 32) problems.add("measure.probes has " + probes.size() + " selectors; at most 32 are allowed");
            for (Object item : probes) {
                Map<String, Object> probe = Json.object(item);
                String label = Json.text(probe, "label", Json.text(probe, "method", ""));
                if (Json.text(probe, "class", "").isBlank() || Json.text(probe, "method", "").isBlank()) problems.add("probe " + label + " needs class and method");
                if (!Json.text(probe, "descriptor", "").startsWith("(")) problems.add("probe " + label + " needs a JVM descriptor such as ()V");
                if ("timing".equals(probe.get("mode"))) timed.add(label);
            }
        } catch (Exception unreadable) {
            problems.add("measure.probes " + measure.get("probes") + " is not a probe file: " + unreadable.getMessage());
        }
        return timed;
    }

    void patch(Path root, Path patch) {
        try {
            if (Files.size(patch) == 0) {
                problems.add("the patch " + patch.getFileName() + " is empty");
                return;
            }
            PerfOptimize.git(root, "apply", "--check", patch.toString());
        } catch (Exception broken) {
            problems.add("the patch does not apply to " + root.getFileName() + " as it is now: " + broken.getMessage());
        }
    }

    static String text(Map<String, Object> result) {
        StringBuilder out = new StringBuilder(Boolean.TRUE.equals(result.get("ok")) ? "Experiment check passed" : "Experiment check FAILED");
        for (Object problem : Json.array(result.get("problems"))) out.append("\n  problem: ").append(problem);
        for (Object warning : Json.array(result.get("warnings"))) out.append("\n  warning: ").append(warning);
        return out.toString();
    }
}
