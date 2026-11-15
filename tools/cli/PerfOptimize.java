import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * Deterministic accept/reject loop for one candidate change. The candidate comes from a person
 * or their own agent; this command never writes code. Baseline and candidate are separate git
 * worktrees of the same commit, measured in alternating fresh game launches, each with a cold
 * capture right after setup and a warm capture after it. The change is accepted only when every
 * gate passes against the baseline (and is stable baseline-to-baseline) and the speedup interval
 * lies above 1.
 */
final class PerfOptimize {
    static final List<String> ARMS = List.of("baseline", "candidate");

    final Path home, root, out, experimentFile, patch;
    final Map<String, Object> experiment;
    final Modaccel.Options options;
    final int runs;
    final List<Object> log = new ArrayList<>();

    PerfOptimize(Path home, Modaccel.Options options) throws Exception {
        this.home = home;
        this.options = options;
        root = Path.of(options.get("root", ".")).toAbsolutePath().normalize();
        experimentFile = Path.of(options.get("experiment", "")).toAbsolutePath();
        patch = Path.of(options.get("patch", "")).toAbsolutePath();
        if (!Files.isRegularFile(experimentFile)) throw new IllegalArgumentException("--experiment FILE is required");
        if (!Files.isRegularFile(patch)) throw new IllegalArgumentException("--patch FILE (the candidate change, a git diff) is required");
        experiment = Json.read(experimentFile);
        validate(experiment);
        runs = Performance.integer(options, "runs", 5, 2, 50);
        out = Performance.output(options);
        if (Files.exists(out.resolve("optimize.json"))) throw new IOException("Output already holds an optimization run: " + out);
    }

    static Map<String, Object> run(Path home, Modaccel.Options options) throws Exception {
        return new PerfOptimize(home, options).execute();
    }

    static void validate(Map<String, Object> experiment) {
        if (!Integer.valueOf(1).equals(experiment.get("schema")) && !Long.valueOf(1).equals(experiment.get("schema"))) {
            throw new IllegalArgumentException("Experiment schema must be integer 1");
        }
        Map<String, Object> measure = Json.object(experiment.get("measure"));
        Map<String, Object> metric = Json.object(measure.get("metric"));
        if (!metric.containsKey("probe") && !metric.containsKey("report")) {
            throw new IllegalArgumentException("measure.metric needs probe (a timing label) or report (a report.json path such as metrics.tick_ms.mean)");
        }
        List<Object> gates = Json.array(experiment.get("gates"));
        if (gates.isEmpty()) throw new IllegalArgumentException("An optimization needs at least one equivalence gate");
        Set<String> names = new HashSet<>();
        for (Object item : gates) {
            Map<String, Object> gate = Json.object(item);
            String name = Json.text(gate, "name", "");
            if (name.isBlank() || !names.add(name)) throw new IllegalArgumentException("Gate names must be nonempty and unique");
            boolean frame = Boolean.TRUE.equals(gate.get("frame"));
            if (!frame && Json.text(gate, "file", "").isBlank()) throw new IllegalArgumentException("Gate " + name + " needs file (an eval snippet) or frame: true");
            String compare = Json.text(gate, "compare", frame ? "pixels" : "exact");
            if (!Set.of("exact", "expect", "tolerance", "pixels").contains(compare)) throw new IllegalArgumentException("Gate " + name + ": compare is exact, expect, tolerance or pixels");
            if (compare.equals("expect") && !gate.containsKey("expect")) throw new IllegalArgumentException("Gate " + name + " needs expect");
            if (compare.equals("tolerance") && !(gate.get("tolerance") instanceof Number)) throw new IllegalArgumentException("Gate " + name + " needs a numeric relative tolerance");
        }
    }

    Map<String, Object> execute() throws Exception {
        Path experimentDir = experimentFile.getParent();
        Map<String, Path> arms = new LinkedHashMap<>();
        String commit = git(root, "rev-parse", "HEAD").strip();
        Map<String, Object> result = Json.map("schema", 1, "kind", "optimization", "experiment", experimentFile.toString(),
            "experimentSha256", Performance.sha256(experimentFile), "patch", patch.toString(), "patchSha256", Performance.sha256(patch),
            "root", root.toString(), "commit", commit, "runs", runs, "started", Instant.now().toString(), "steps", log);
        try {
            for (String arm : ARMS) {
                Path tree = out.resolve(arm);
                git(root, "worktree", "add", "--detach", tree.toString(), commit);
                arms.put(arm, tree);
                if (arm.equals("candidate")) {
                    git(tree, "apply", "--check", patch.toString());
                    git(tree, "apply", patch.toString());
                }
                for (var entry : Json.object(experiment.getOrDefault("files", Map.of())).entrySet()) {
                    Path target = tree.resolve(entry.getKey()).normalize();
                    if (!target.startsWith(tree)) throw new IllegalArgumentException("files may only write inside the mod checkout: " + entry.getKey());
                    Files.createDirectories(target.getParent());
                    Files.copy(experimentDir.resolve(entry.getValue().toString()), target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            result.put("changedFiles", Arrays.stream(git(arms.get("candidate"), "diff", "--name-only").split("\n")).filter(s -> !s.isBlank()).toList());
            if (Boolean.TRUE.equals(experiment.get("tests"))) {
                Map<String, Object> tests = new LinkedHashMap<>();
                for (String arm : ARMS) tests.put(arm, modaccel(arms.get(arm), arm + "-tests", List.of("fast"), 3600).get("ok"));
                result.put("tests", tests);
            }
            List<Object> measured = new ArrayList<>();
            result.put("measurements", measured);
            for (int run = 0; run < runs; run++) {
                // Alternate which arm launches first so drift and thermal state do not favour one side.
                List<String> order = run % 2 == 0 ? ARMS : List.of("candidate", "baseline");
                for (String arm : order) measured.add(measure(arm, arms.get(arm), run, experimentDir));
                Json.save(out.resolve("optimize.json"), result);
            }
            evaluate(result, measured);
        } catch (Exception failure) {
            result.put("accepted", false);
            result.put("error", failure.toString());
        } finally {
            for (Path tree : arms.values()) {
                try { modaccel(tree, "final-game-stop", List.of("game-stop"), 120); } catch (Exception ignored) { }
                if (!options.has("keep")) {
                    try { git(root, "worktree", "remove", "--force", tree.toString()); }
                    catch (Exception error) {
                        // git cannot delete Gradle trees deeper than Windows' path limit; Java can.
                        try {
                            deleteTree(tree);
                            git(root, "worktree", "prune");
                        } catch (Exception again) { log.add(Json.map("step", "worktree-remove", "error", again.toString())); }
                    }
                }
            }
        }
        boolean accepted = Boolean.TRUE.equals(result.get("accepted"));
        if (accepted && !options.has("dry-run")) {
            git(root, "apply", "--check", patch.toString());
            git(root, "apply", patch.toString());
            result.put("applied", true);
        }
        result.put("finished", Instant.now().toString());
        Json.save(out.resolve("optimize.json"), result);
        Files.writeString(out.resolve("optimize.html"), html(result));
        result.put("ok", accepted);
        result.put("html", out.resolve("optimize.html").toString());
        result.put("details", summary(result));
        return result;
    }

    Map<String, Object> measure(String arm, Path tree, int run, Path experimentDir) throws Exception {
        Map<String, Object> row = Json.map("arm", arm, "run", run, "started", Instant.now().toString());
        Path dir = out.resolve(String.format(Locale.ROOT, "runs/%02d-%s", run, arm));
        Files.createDirectories(dir);
        String world = Json.text(experiment, "world", "");
        Path game = tree.resolve(Json.text(experiment, "gameDirectory", "run"));
        if (!world.isBlank()) {
            Path saves = game.resolve("saves").resolve(world);
            deleteTree(saves);
            copyTree(experimentDir.resolve(Json.text(experiment, "worldSource", "")), saves);
        }
        List<String> launch = new ArrayList<>(world.isBlank() ? List.of("ui-check") : List.of("world-check", "--world", world));
        long started = System.nanoTime();
        Map<String, Object> launched = modaccel(tree, arm + "-launch", launch, 1800);
        row.put("launchSeconds", (System.nanoTime() - started) / 1e9);
        if (!Boolean.TRUE.equals(launched.get("ok"))) throw new IOException(arm + " run " + run + " did not start: " + launched.get("error"));
        try {
            for (Object step : Json.array(experiment.getOrDefault("setup", List.of()))) {
                Map<String, Object> setup = Json.object(step);
                Map<String, Object> answer = eval(tree, arm + "-setup", experimentDir.resolve(Json.text(setup, "file", "")), Json.text(setup, "thread", "game"));
                if (setup.containsKey("expect") && !String.valueOf(answer.get("value")).equals(String.valueOf(setup.get("expect")))) {
                    throw new IOException("Setup " + setup.get("file") + " returned " + answer.get("value") + ", expected " + setup.get("expect"));
                }
            }
            Map<String, Object> measure = Json.object(experiment.get("measure"));
            Map<String, Object> captures = new LinkedHashMap<>();
            // Launches follow the user's window and sound preferences, so the game may be hidden; attach by PID.
            Object pid = Json.object(modaccel(tree, arm + "-status", List.of("game-status"), 120).get("result")).get("pid");
            if (pid == null) throw new IOException(arm + " run " + run + ": no running game to measure");
            for (String phase : List.of("cold", "warm")) {
                Path capture = dir.resolve(phase);
                List<String> args = new ArrayList<>(List.of("perf", "--attach", String.valueOf(((Number) pid).longValue()),
                    "--allow-hidden", "--mod-root", tree.toString(), "--duration", String.valueOf(measure.getOrDefault("seconds", 20)),
                    "--warmup", phase.equals("cold") ? "0" : String.valueOf(measure.getOrDefault("warmup", 10)), "--out", capture.toString(), "--no-heap"));
                if (measure.containsKey("probes")) {
                    args.add("--probes");
                    args.add(experimentDir.resolve(measure.get("probes").toString()).toString());
                }
                Map<String, Object> captured = modaccel(tree, arm + "-" + phase, args, 900);
                if (!Boolean.TRUE.equals(captured.get("ok"))) throw new IOException(arm + " " + phase + " capture failed: " + captured.get("error"));
                Map<String, Object> report = Json.read(capture.resolve("report.json"));
                captures.put(phase, Json.map("value", metric(report, Json.object(measure.get("metric"))),
                    "tickMeanMs", PerfAdvice.value(report, "metrics.tick_ms.mean"), "frameMeanMs", PerfAdvice.value(report, "metrics.frame_ms.mean"),
                    "report", capture.resolve("report.json").toString()));
            }
            row.put("captures", captures);
            Map<String, Object> gates = new LinkedHashMap<>();
            for (Object item : Json.array(experiment.get("gates"))) {
                Map<String, Object> gate = Json.object(item);
                String name = gate.get("name").toString();
                if (Boolean.TRUE.equals(gate.get("frame"))) {
                    if (gate.containsKey("file")) eval(tree, arm + "-frame-setup", experimentDir.resolve(gate.get("file").toString()), Json.text(gate, "thread", "game"));
                    Map<String, Object> shot = modaccel(tree, arm + "-frame", List.of("ui-check"), 600);
                    if (!Boolean.TRUE.equals(shot.get("ok"))) throw new IOException("Frame capture failed: " + shot.get("error"));
                    Path png = Path.of(Json.text(Json.object(Json.object(shot.get("result")).get("capture")), "path", ""));
                    Path kept = dir.resolve("frame-" + name + ".png");
                    Files.copy(png, kept, StandardCopyOption.REPLACE_EXISTING);
                    gates.put(name, kept.toString());
                } else {
                    Map<String, Object> answer = eval(tree, arm + "-gate-" + name, experimentDir.resolve(gate.get("file").toString()), Json.text(gate, "thread", "game"));
                    gates.put(name, String.valueOf(answer.get("value")));
                }
            }
            row.put("gates", gates);
        } finally {
            modaccel(tree, arm + "-game-stop", List.of("game-stop"), 300);
        }
        row.put("seconds", (System.nanoTime() - started) / 1e9);
        return row;
    }

    static double metric(Map<String, Object> report, Map<String, Object> metric) {
        if (metric.containsKey("report")) {
            Object value = PerfAdvice.value(report, metric.get("report").toString());
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                throw new IllegalStateException("Metric " + metric.get("report") + " is unavailable in " + report.get("recording"));
            }
            return number.doubleValue();
        }
        String label = metric.get("probe").toString();
        String statistic = Json.text(metric, "statistic", "inclusiveMsPerTick");
        Map<String, Object> attribution = Json.object(report.get("attribution"));
        List<Object> rows = new ArrayList<>(Json.array(attribution.getOrDefault("unattributed", List.of())));
        for (Object mod : Json.array(attribution.get("mods"))) rows.addAll(Json.array(Json.object(mod).get("exact")));
        for (Object item : rows) {
            Map<String, Object> row = Json.object(item);
            if (row.get("label").equals(label) && row.get(statistic) instanceof Number number) return number.doubleValue();
        }
        throw new IllegalStateException("Timing probe " + label + " has no " + statistic + " in " + report.get("recording") + "; was the method called during the capture?");
    }

    void evaluate(Map<String, Object> result, List<Object> measured) throws Exception {
        Map<Integer, Map<String, Map<String, Object>>> pairs = new TreeMap<>();
        for (Object item : measured) {
            Map<String, Object> row = Json.object(item);
            pairs.computeIfAbsent(((Number) row.get("run")).intValue(), k -> new HashMap<>()).put(row.get("arm").toString(), row);
        }
        Map<String, Object> speedups = new LinkedHashMap<>();
        boolean faster = true;
        for (String phase : List.of("cold", "warm")) {
            List<Double> ratios = new ArrayList<>(), base = new ArrayList<>(), cand = new ArrayList<>();
            for (var pair : pairs.values()) {
                double b = capture(pair.get("baseline"), phase), c = capture(pair.get("candidate"), phase);
                base.add(b);
                cand.add(c);
                ratios.add(b / Math.max(c, 1e-12));
            }
            Map<String, Object> summary = speedup(ratios, 20261114L);
            summary.put("baseline", base);
            summary.put("candidate", cand);
            summary.put("baselineMean", base.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN));
            summary.put("candidateMean", cand.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN));
            speedups.put(phase, summary);
            faster &= ((Number) summary.get("low")).doubleValue() > 1;
        }
        result.put("speedup", speedups);
        List<Object> verdicts = new ArrayList<>();
        boolean gatesPass = true;
        for (Object item : Json.array(experiment.get("gates"))) {
            Map<String, Object> gate = Json.object(item);
            Map<String, Object> verdict = gate(gate, pairs);
            verdicts.add(verdict);
            gatesPass &= Boolean.TRUE.equals(verdict.get("passed"));
        }
        if (result.get("tests") instanceof Map<?, ?> tests && Boolean.TRUE.equals(tests.get("baseline"))) {
            boolean passed = Boolean.TRUE.equals(tests.get("candidate"));
            verdicts.add(Json.map("name", "unit tests", "passed", passed, "detail", "fast on both checkouts"));
            gatesPass &= passed;
        }
        result.put("gates", verdicts);
        result.put("gatesPassed", gatesPass);
        result.put("faster", faster);
        result.put("accepted", gatesPass && faster);
        result.put("rule", "Accepted only when every gate passes for every paired run, each gate is stable baseline-to-baseline, "
            + "and the 95% bootstrap interval of the speedup is above 1 for both cold and warm captures.");
    }

    static double capture(Map<String, Object> row, String phase) {
        return ((Number) Json.object(Json.object(row.get("captures")).get(phase)).get("value")).doubleValue();
    }

    /** Geometric-mean speedup with a seeded bootstrap over whole run pairs. */
    static Map<String, Object> speedup(List<Double> ratios, long seed) {
        double[] logs = ratios.stream().mapToDouble(Math::log).toArray();
        double mean = Arrays.stream(logs).average().orElse(Double.NaN);
        Random random = new Random(seed);
        double[] boot = new double[10_000];
        for (int i = 0; i < boot.length; i++) {
            double sum = 0;
            for (int j = 0; j < logs.length; j++) sum += logs[random.nextInt(logs.length)];
            boot[i] = sum / logs.length;
        }
        Arrays.sort(boot);
        return Json.map("ratios", ratios, "geometricMean", Math.exp(mean), "low", Math.exp(boot[249]), "high", Math.exp(boot[9749]),
            "pairs", ratios.size(), "basis", "baseline / candidate per paired launch; 95% percentile bootstrap of the mean log ratio (10,000 resamples)");
    }

    static Map<String, Object> gate(Map<String, Object> gate, Map<Integer, Map<String, Map<String, Object>>> pairs) throws Exception {
        String name = gate.get("name").toString();
        boolean frame = Boolean.TRUE.equals(gate.get("frame"));
        String compare = Json.text(gate, "compare", frame ? "pixels" : "exact");
        List<String> base = new ArrayList<>(), cand = new ArrayList<>();
        for (var pair : pairs.values()) {
            base.add(Json.object(pair.get("baseline").get("gates")).get(name).toString());
            cand.add(Json.object(pair.get("candidate").get("gates")).get(name).toString());
        }
        Map<String, Object> verdict = Json.map("name", name, "compare", compare);
        List<Object> failures = new ArrayList<>();
        switch (compare) {
            case "expect" -> {
                String expected = String.valueOf(gate.get("expect"));
                for (int i = 0; i < base.size(); i++) {
                    if (!base.get(i).equals(expected)) failures.add("baseline run " + i + ": " + base.get(i));
                    if (!cand.get(i).equals(expected)) failures.add("candidate run " + i + ": " + cand.get(i));
                }
                verdict.put("expected", expected);
            }
            case "exact" -> {
                boolean stable = new HashSet<>(base).size() == 1;
                verdict.put("baselineStable", stable);
                if (!stable) failures.add("baseline values differ between launches; this output is not deterministic enough for an exact gate");
                for (int i = 0; i < cand.size(); i++) if (!cand.get(i).equals(base.get(i))) failures.add("candidate run " + i + " differs from baseline");
                verdict.put("value", base.get(0));
            }
            case "tolerance" -> {
                double tolerance = ((Number) gate.get("tolerance")).doubleValue();
                double worst = 0, spread = 0;
                for (int i = 0; i < base.size(); i++) {
                    double[] b = numbers(base.get(i)), c = numbers(cand.get(i)), first = numbers(base.get(0));
                    if (b.length != c.length || b.length != first.length) {
                        failures.add("run " + i + ": different numbers of values");
                        continue;
                    }
                    for (int k = 0; k < b.length; k++) {
                        worst = Math.max(worst, relative(b[k], c[k]));
                        spread = Math.max(spread, relative(first[k], b[k]));
                    }
                }
                verdict.put("worstRelativeDifference", worst);
                verdict.put("baselineSpread", spread);
                if (worst > tolerance) failures.add("candidate differs from paired baseline by " + worst + " > " + tolerance);
                if (spread > tolerance) failures.add("baseline launches differ by " + spread + " > " + tolerance + "; tolerance cannot separate change from noise");
            }
            default -> {
                long allowed = ((Number) gate.getOrDefault("maxDifferentPixels", 0)).longValue();
                List<Object> diffs = new ArrayList<>();
                for (int i = 0; i < base.size(); i++) {
                    Map<String, Object> d = pixels(Path.of(base.get(i)), Path.of(cand.get(i)));
                    diffs.add(d);
                    if (((Number) d.get("differentPixels")).longValue() > allowed) failures.add("run " + i + ": " + d.get("differentPixels") + " pixels differ");
                }
                if (base.size() > 1) {
                    Map<String, Object> aa = pixels(Path.of(base.get(0)), Path.of(base.get(1)));
                    verdict.put("baselineToBaseline", aa);
                    if (((Number) aa.get("differentPixels")).longValue() > allowed) failures.add("baseline frames differ between launches; freeze the scene before capturing");
                }
                verdict.put("pairs", diffs);
                verdict.put("frames", Json.map("baseline", base, "candidate", cand));
            }
        }
        verdict.put("passed", failures.isEmpty());
        verdict.put("failures", failures);
        return verdict;
    }

    static double relative(double a, double b) {
        return Math.abs(a - b) / Math.max(Math.abs(a), 1e-12);
    }

    static double[] numbers(String value) {
        Object parsed = Json.parse(value);
        if (parsed instanceof Number number) return new double[]{number.doubleValue()};
        return Json.array(parsed).stream().mapToDouble(v -> ((Number) v).doubleValue()).toArray();
    }

    /** Every pixel and channel of two 1280x720 framebuffer captures. */
    static Map<String, Object> pixels(Path a, Path b) throws IOException {
        BufferedImage x = ImageIO.read(a.toFile()), y = ImageIO.read(b.toFile());
        if (x == null || y == null) throw new IOException("Unreadable frame: " + a + " / " + b);
        if (x.getWidth() != 1280 || x.getHeight() != 720 || y.getWidth() != 1280 || y.getHeight() != 720) {
            throw new IOException("Frames must be 1280x720 game framebuffer captures");
        }
        long different = 0;
        int maxDelta = 0;
        for (int py = 0; py < 720; py++) for (int px = 0; px < 1280; px++) {
            int p = x.getRGB(px, py), q = y.getRGB(px, py);
            if (p == q) continue;
            different++;
            for (int shift = 0; shift < 32; shift += 8) maxDelta = Math.max(maxDelta, Math.abs(((p >>> shift) & 255) - ((q >>> shift) & 255)));
        }
        return Json.map("differentPixels", different, "maxChannelDelta", maxDelta, "pixels", 1280 * 720);
    }

    Map<String, Object> eval(Path tree, String name, Path file, String thread) throws Exception {
        Map<String, Object> answer = modaccel(tree, name, List.of("eval", "--file", file.toString(), "--thread", thread, "--timeout", "120"), 300);
        if (!Boolean.TRUE.equals(answer.get("ok"))) throw new IOException(name + " (" + file.getFileName() + ") failed: " + answer.get("error"));
        return Json.object(answer.get("result"));
    }

    /** Runs a modaccel command as a child process, so each step keeps its own receipt and lock. */
    Map<String, Object> modaccel(Path tree, String name, List<String> args, int timeout) throws Exception {
        List<String> command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java"),
            "-Dmodaccel.home=" + home, "-cp", Path.of(Modaccel.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(), "Modaccel"));
        command.add(args.get(0));
        // perf selects its game by --attach; every other step works on the arm's checkout.
        if (!args.contains("--attach")) {
            command.addAll(List.of("--root", tree.toString()));
            if (!Json.text(experiment, "project", "").isBlank()) command.addAll(List.of("--project", experiment.get("project").toString()));
        }
        command.addAll(args.subList(1, args.size()));
        command.add("--json");
        Path logs = out.resolve("logs");
        Files.createDirectories(logs);
        String file = String.format(Locale.ROOT, "%03d-%s", log.size(), name.replaceAll("[^A-Za-z0-9_.-]", "_"));
        Path stdout = logs.resolve(file + ".json"), stderr = logs.resolve(file + ".log");
        long start = System.nanoTime();
        Process process = new ProcessBuilder(command).directory(home.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroyForcibly();
            throw new IOException(name + " timed out after " + timeout + " s; see " + stderr);
        }
        Map<String, Object> answer;
        String text = Files.readString(stdout, StandardCharsets.UTF_8).strip();
        try {
            answer = Json.object(Json.parse(text.substring(Math.max(0, text.lastIndexOf("\n{") + 1))));
        } catch (Exception unreadable) {
            answer = Json.map("ok", false, "error", "No JSON result (exit " + process.exitValue() + "): " + Run.tail(stderr));
        }
        if (!Boolean.TRUE.equals(answer.get("ok")) && !answer.containsKey("error")) {
            answer.put("error", Json.text(Json.object(answer.getOrDefault("result", Map.of())), "error", Run.tail(stderr)));
        }
        log.add(Json.map("step", name, "exit", process.exitValue(), "seconds", (System.nanoTime() - start) / 1e9, "log", stderr.toString(), "result", stdout.toString()));
        return answer;
    }

    static String git(Path directory, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(300, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IOException("git " + String.join(" ", args) + ": " + output.strip());
        return output;
    }

    static void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) throw new IOException("worldSource is not a save folder: " + from);
        try (var walk = Files.walk(from)) {
            for (Path source : walk.toList()) {
                if (source.getFileName().toString().equals("session.lock")) continue;
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) Files.createDirectories(target);
                else Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            for (Path item : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(item);
        }
    }

    static String summary(Map<String, Object> result) {
        StringBuilder text = new StringBuilder(Boolean.TRUE.equals(result.get("accepted")) ? "ACCEPTED" : "REJECTED");
        if (result.containsKey("error")) text.append(": ").append(result.get("error"));
        if (result.get("speedup") instanceof Map<?, ?> speedup) for (String phase : List.of("cold", "warm")) {
            Map<String, Object> s = Json.object(speedup.get(phase));
            text.append(String.format(Locale.ROOT, "%n%s: %.2fx (95%% %.2f–%.2f) over %s paired launches; baseline %.4f → candidate %.4f",
                phase, s.get("geometricMean"), s.get("low"), s.get("high"), s.get("pairs"), s.get("baselineMean"), s.get("candidateMean")));
        }
        if (result.get("gates") instanceof List<?> gates) for (Object g : gates) {
            Map<String, Object> gate = Json.object(g);
            text.append("\ngate ").append(gate.get("name")).append(": ").append(Boolean.TRUE.equals(gate.get("passed")) ? "passed" : "FAILED " + gate.get("failures"));
        }
        if (Boolean.TRUE.equals(result.get("applied"))) text.append("\nThe change was applied to ").append(result.get("root"));
        return text.toString();
    }

    static String html(Map<String, Object> result) {
        String data = Json.write(result).replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        return """
            <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Mod Accel · Optimization</title>
            <style>:root{color-scheme:light dark;font:15px system-ui}body{max-width:1100px;margin:auto;padding:24px 16px}
            td,th{padding:8px;text-align:left;border-bottom:1px solid #8884}table{border-collapse:collapse;width:100%}
            .ok{color:#2a8a4a}.bad{color:#c0392b}pre{white-space:pre-wrap;overflow-wrap:anywhere}.scroll{overflow:auto}</style>
            <h1 id="verdict"></h1><p id="rule"></p>
            <h2>Speedup</h2><div class="scroll"><table id="speed"><thead><tr><th>Capture</th><th>Speedup (geo. mean)</th><th>95% interval</th>
            <th>Baseline mean</th><th>Candidate mean</th><th>Pairs</th></tr></thead><tbody></tbody></table></div>
            <h2>Equivalence gates</h2><div class="scroll"><table id="gates"><thead><tr><th>Gate</th><th>Comparison</th><th>Result</th><th>Detail</th></tr></thead><tbody></tbody></table></div>
            <h2>Launches</h2><div class="scroll"><table id="runs"><thead><tr><th>Run</th><th>Arm</th><th>Cold</th><th>Warm</th><th>Launch s</th></tr></thead><tbody></tbody></table></div>
            <p><a href="optimize.json">Full JSON evidence</a></p>
            <script>const d=__DATA__,el=id=>document.getElementById(id),f=v=>typeof v==='number'?v.toPrecision(4):String(v);
            function row(t,v){const r=document.createElement('tr');for(const x of v){const c=document.createElement('td');c.textContent=x;r.append(c)}el(t).tBodies[0].append(r)}
            el('verdict').textContent=(d.accepted?'Accepted':'Rejected')+(d.error?': '+d.error:'');el('verdict').className=d.accepted?'ok':'bad';
            el('rule').textContent=d.rule||'';
            for(const [k,s] of Object.entries(d.speedup||{}))row('speed',[k,f(s.geometricMean)+'×',f(s.low)+'–'+f(s.high),f(s.baselineMean),f(s.candidateMean),s.pairs]);
            const detail=g=>g.failures.length?g.failures:(g.baselineToBaseline||g.value||g.worstRelativeDifference||g.expected||'');
            for(const g of d.gates||[])row('gates',[g.name,g.compare,g.passed?'passed':'failed',JSON.stringify(detail(g))]);
            for(const m of d.measurements||[])row('runs',[m.run,m.arm,f(m.captures?.cold?.value),f(m.captures?.warm?.value),f(m.launchSeconds)]);
            </script></html>
            """.replace("__DATA__", data);
    }
}
