import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
    Map<String, Path> snapshot = Map.of();
    final Map<String, Object> experiment;
    final Mmt.Options options;
    final int runs, minRuns;
    final double minSpeedup;
    final boolean parallel;
    final List<Object> log = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger stepNumber = new AtomicInteger();
    final PerfRecord record;

    PerfOptimize(Path home, Mmt.Options options) throws Exception {
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
        // A sequential test: from --min-runs pairs on, stop once the intervals decide (PerfStopping).
        minRuns = Performance.integer(options, "min-runs", Math.min(3, runs), 2, runs);
        minSpeedup = Double.parseDouble(options.get("min-speedup", "1"));
        parallel = parallel(options.get("arms", "auto"));
        out = Performance.output(options);
        if (Files.exists(out.resolve("optimize.json"))) throw new IOException("Output already holds an optimization run: " + out);
        record = new PerfRecord("perf-optimize", options);
        String label = options.get("label", patch.getFileName().toString().replaceFirst("\\.(diff|patch)$", ""));
        record.record.put("candidate", Json.map("label", label, "diffSha256", Performance.sha256(patch), "changedFiles", List.of()));
        record.record.put("metric", Json.object(experiment.get("measure")).get("metric"));
        Json.object(record.record.get("machine")).put("jit", "full".equals(experiment.get("jit")) ? "full" : "C1 only (hidden test client)");
        record.record.put("source", out.toString());
        record.record.put("arms", parallel ? "parallel" : "sequential");
    }

    /**
     * Both arms of a pair start together when the machine has the cores for two games (12 or more logical
     * processors): a pair then takes its slower arm's time, not both, and both arms see the same machine load.
     */
    static boolean parallel(String arms) {
        return switch (arms) {
            case "parallel" -> true;
            case "sequential" -> false;
            case "auto" -> Runtime.getRuntime().availableProcessors() >= 12;
            default -> throw new IllegalArgumentException("--arms is auto, parallel or sequential");
        };
    }

    static Map<String, Object> run(Path home, Mmt.Options options) throws Exception {
        if (options.values.containsKey("replay")) return replay(options);
        if (options.has("check-only")) {
            Path experimentFile = Path.of(options.get("experiment", "")).toAbsolutePath();
            if (!Files.isRegularFile(experimentFile)) throw new IllegalArgumentException("--experiment FILE is required");
            Path patch = options.values.containsKey("patch") ? Path.of(options.get("patch", "")).toAbsolutePath() : null;
            Path root = Path.of(options.get("root", ".")).toAbsolutePath().normalize();
            Map<String, Object> checked = PerfCheck.check(experimentFile, Json.read(experimentFile), root, patch, Preferences.read(Preferences.file()));
            checked.put("details", PerfCheck.text(checked));
            return checked;
        }
        return new PerfOptimize(home, options).execute();
    }

    /** Finished runs (comma-separated output folders) decided again with the stopping rule, with the wall time saved. */
    static Map<String, Object> replay(Mmt.Options options) throws Exception {
        List<Object> replays = new ArrayList<>();
        StringBuilder details = new StringBuilder();
        double before = 0;
        double sequential = 0;
        double together = 0;
        int minRuns = Performance.integer(options, "min-runs", 3, 2, 50);
        double minSpeedup = Double.parseDouble(options.get("min-speedup", "1"));
        for (String folder : options.get("replay", "").split(",")) {
            Map<String, Object> replay = PerfStopping.replay(Path.of(folder.strip()).toAbsolutePath(), minRuns, minSpeedup);
            replays.add(replay);
            before += ((Number) replay.get("recordedSeconds")).doubleValue();
            sequential += ((Number) replay.get("replaySequentialSeconds")).doubleValue();
            together += ((Number) replay.get("replayParallelSeconds")).doubleValue();
            details.append(String.format(Locale.ROOT, "%s: %s pairs -> %s, %s -> %s; %.0f s -> %.0f s sequential, %.0f s parallel%n",
                Path.of(folder.strip()).getFileName(), replay.get("pairsRecorded"), replay.get("pairsReplayed"), replay.get("verdictRecorded"),
                replay.get("verdictReplayed"), replay.get("recordedSeconds"), replay.get("replaySequentialSeconds"), replay.get("replayParallelSeconds")));
        }
        details.append(String.format(Locale.ROOT, "All: %.1f min -> %.1f min sequential, %.1f min with paired arms started together",
            before / 60, sequential / 60, together / 60));
        return Json.map("ok", true, "replays", replays, "recordedSeconds", before, "replaySequentialSeconds", sequential,
            "replayParallelSeconds", together, "details", details.toString());
    }

    static void validate(Map<String, Object> experiment) {
        if (!Integer.valueOf(1).equals(experiment.get("schema")) && !Long.valueOf(1).equals(experiment.get("schema"))) {
            throw new IllegalArgumentException("Experiment schema must be integer 1");
        }
        Map<String, Object> measure = Json.object(experiment.get("measure"));
        Map<String, Object> metric = Json.object(measure.get("metric"));
        if (!metric.containsKey("probe") && !metric.containsKey("report") && !metric.containsKey("mod") && !metric.containsKey("threadCpu")) {
            throw new IllegalArgumentException("measure.metric needs threadCpu (a thread-name pattern), probe (a timing label), mod (a mod id's attribution row) or report (a report.json path such as metrics.tick_ms.mean)");
        }
        Set<String> scopeNames = new HashSet<>();
        for (Object item : Json.array(measure.getOrDefault("scopes", List.of()))) {
            Map<String, Object> scope = Json.object(item);
            if (Json.text(scope, "name", "").isBlank() || !scopeNames.add(scope.get("name").toString())) throw new IllegalArgumentException("measure.scopes need unique names");
            if (!scope.containsKey("probe") && !scope.containsKey("report") && !scope.containsKey("mod") && !scope.containsKey("threadCpu")) {
                throw new IllegalArgumentException("Scope " + scope.get("name") + " needs threadCpu, probe, mod or report");
            }
        }
        for (Object item : Json.array(experiment.getOrDefault("reset", List.of()))) {
            String path = item.toString().replace('\\', '/');
            if (path.isBlank() || path.startsWith("/") || path.matches("^[A-Za-z]:.*") || Arrays.asList(path.split("/")).contains("..")) {
                throw new IllegalArgumentException("reset paths are relative to the mod checkout, without ..: " + item);
            }
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
            "root", root.toString(), "commit", commit, "runs", runs, "minRuns", minRuns, "arms", parallel ? "parallel" : "sequential",
            "started", Instant.now().toString(), "steps", log);
        long setupStart = System.nanoTime();
        long wallStart = setupStart;
        // Everything that can be wrong without a game is found before the first worktree and launch.
        Map<String, Object> checked = PerfCheck.check(experimentFile, experiment, root, patch, Preferences.read(Preferences.file()));
        result.put("check", checked);
        if (!Boolean.TRUE.equals(checked.get("ok"))) throw new IllegalArgumentException(PerfCheck.text(checked));
        snapshot = snapshot(experiment, experimentDir, out.resolve("experiment"));
        System.err.println("perf-optimize: to end after the pair in progress and keep the run's record, create " + out.resolve("stop"));
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
                    Files.copy(file(experimentDir, entry.getValue().toString()), target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            result.put("changedFiles", Arrays.stream(git(arms.get("candidate"), "diff", "--name-only").split("\n")).filter(s -> !s.isBlank()).toList());
            Json.object(record.record.get("candidate")).put("changedFiles", result.get("changedFiles"));
            record.step("setup", "worktrees", (System.nanoTime() - setupStart) / 1e9);
            if (Boolean.TRUE.equals(experiment.get("tests"))) {
                Map<String, Object> tests = new LinkedHashMap<>();
                for (String arm : ARMS) tests.put(arm, mmt(arms.get(arm), arm + "-tests", List.of("fast"), 3600).get("ok"));
                result.put("tests", tests);
            }
            List<Object> measured = new ArrayList<>();
            result.put("measurements", measured);
            record.share = parallel ? 0.5 : 1;
            for (int run = 0; run < runs; run++) {
                if (parallel) {
                    measured.addAll(together(arms, run, experimentDir));
                } else {
                    // Alternate which arm launches first so drift and thermal state do not favour one side.
                    List<String> order = run % 2 == 0 ? ARMS : List.of("candidate", "baseline");
                    for (String arm : order) measured.add(measure(arm, arms.get(arm), run, experimentDir));
                }
                Json.save(out.resolve("optimize.json"), result);
                String progress = PerfStopping.progress(experiment, measured, (System.nanoTime() - wallStart) / 1e9);
                if (progress != null) {
                    System.err.println("perf-optimize: " + progress);
                    System.err.flush();
                }
                Map<String, Object> stop = PerfStopping.decide(experiment, measured, minRuns, runs, minSpeedup);
                if (stop == null) stop = asked(out, PerfStopping.pairs(measured).size());
                if (stop != null) {
                    result.put("stopped", stop);
                    record.record.put("stopped", stop);
                    break;
                }
            }
            record.share = 1;
            evaluate(experiment, result, measured);
        } catch (Exception failure) {
            result.put("accepted", false);
            result.put("error", failure.toString());
        } finally {
            record.share = 1;
            for (Path tree : arms.values()) {
                try { mmt(tree, "final-game-stop", List.of("game-stop"), 120); } catch (Exception ignored) { }
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
        result.put("wallSeconds", (System.nanoTime() - wallStart) / 1e9);
        record.record.put("wallSeconds", result.get("wallSeconds"));
        finishRecord(result, accepted);
        Json.save(out.resolve("optimize.json"), result);
        Files.writeString(out.resolve("optimize.html"), html(result));
        result.put("ok", accepted);
        result.put("verdict", verdict(result));
        result.put("html", out.resolve("optimize.html").toString());
        result.put("details", summary(result));
        return result;
    }

    /** Both arms of one pair at once, each in its own game; waits for both so neither game is left running. */
    List<Map<String, Object>> together(Map<String, Path> arms, int run, Path experimentDir) throws Exception {
        record.sampleLoad("pair " + run + " before launch");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Map<String, Future<Map<String, Object>>> running = new LinkedHashMap<>();
            for (String arm : ARMS) running.put(arm, pool.submit(() -> measure(arm, arms.get(arm), run, experimentDir)));
            List<Map<String, Object>> rows = new ArrayList<>();
            Exception failure = null;
            for (var arm : running.entrySet()) {
                try {
                    rows.add(arm.getValue().get());
                } catch (ExecutionException error) {
                    if (failure == null) failure = error.getCause() instanceof Exception cause ? cause : error;
                }
            }
            if (failure != null) throw failure;
            return rows;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A stop someone asked for by creating OUT/stop while the run went on (a candidate already shown not to matter, the
     * machine needed back): the run ends after the pair in progress and is decided, recorded and kept like any other.
     */
    static Map<String, Object> asked(Path out, int pairs) {
        if (!Files.exists(out.resolve("stop"))) return null;
        return Json.map("afterPairs", pairs, "decided", "asked", "reason", "asked to stop after " + pairs + " pair(s): " + out.resolve("stop") + " exists");
    }

    /** The run's copy of a file the experiment names (snapshot), or the file itself when it has none. */
    Path file(Path experimentDir, String name) {
        return snapshot.getOrDefault(name, experimentDir.resolve(name));
    }

    /**
     * Copies of every file the experiment names (snippets, probes, the files each launch copies), taken when the run
     * starts and keyed by the name the experiment uses. A run reads only these, so editing the experiment while it runs
     * cannot give its later launches other gates: Storage Network's third run compared a one-network trace in its first
     * pair with a two-network trace in its second, and failed its own gate. The world source is still read where it is.
     */
    static Map<String, Path> snapshot(Map<String, Object> experiment, Path experimentDir, Path into) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        for (Object step : Json.array(experiment.getOrDefault("setup", List.of()))) names.add(Json.text(Json.object(step), "file", ""));
        Map<String, Object> measure = Json.object(experiment.getOrDefault("measure", Map.of()));
        for (var phase : Json.object(measure.getOrDefault("before", Map.of())).entrySet()) {
            List<Object> steps = phase.getValue() instanceof List<?> list ? Json.array(list) : List.of(phase.getValue());
            for (Object step : steps) names.add(Json.text(Json.object(step), "file", ""));
        }
        if (measure.containsKey("probes")) names.add(measure.get("probes").toString());
        for (Object gate : Json.array(experiment.getOrDefault("gates", List.of()))) names.add(Json.text(Json.object(gate), "file", ""));
        for (Object source : Json.object(experiment.getOrDefault("files", Map.of())).values()) names.add(source.toString());
        names.remove("");
        Map<String, Path> copies = new LinkedHashMap<>();
        int number = 0;
        for (String name : names) {
            Path source = experimentDir.resolve(name);
            if (!Files.isRegularFile(source)) continue;
            Files.createDirectories(into);
            Path copy = into.resolve(String.format(Locale.ROOT, "%02d-%s", number++, source.getFileName()));
            Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
            copies.put(name, copy);
        }
        return copies;
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
        // State the mod keeps beside the save (map caches, per-machine records) would otherwise carry
        // from one launch of an arm into the next and change what it does.
        for (Object item : Json.array(experiment.getOrDefault("reset", List.of()))) deleteTree(tree.resolve(item.toString()).normalize());
        // Two arms sampling at once read each other's interval (the second reads 0): a pair together samples once.
        if (!parallel) record.sampleLoad(arm + " run " + run + " before launch");
        List<String> launch = new ArrayList<>(world.isBlank() ? List.of("ui-check") : List.of("world-check", "--world", world));
        // "jit": "full" measures with the JIT players have, not the C1 a hidden test client starts with.
        if ("full".equals(experiment.get("jit"))) launch.addAll(List.of("--jit", "full"));
        long started = System.nanoTime();
        Map<String, Object> launched = mmt(tree, arm + "-launch", launch, 1800);
        row.put("launchSeconds", (System.nanoTime() - started) / 1e9);
        record.modFromLaunch(launched);
        if (!Boolean.TRUE.equals(launched.get("ok"))) throw new IOException(arm + " run " + run + " did not start: " + launched.get("error"));
        try {
            for (Object step : Json.array(experiment.getOrDefault("setup", List.of()))) {
                Map<String, Object> setup = Json.object(step);
                Map<String, Object> answer = eval(tree, arm + "-setup", file(experimentDir, Json.text(setup, "file", "")), Json.text(setup, "thread", "game"));
                if (setup.containsKey("expect") && !String.valueOf(answer.get("value")).equals(String.valueOf(setup.get("expect")))) {
                    throw new IOException("Setup " + setup.get("file") + " returned " + answer.get("value") + ", expected " + setup.get("expect"));
                }
            }
            Map<String, Object> measure = Json.object(experiment.get("measure"));
            Map<String, Object> captures = new LinkedHashMap<>();
            // Launches follow the user's window and sound preferences, so the game may be hidden; attach by PID.
            Object pid = Json.object(mmt(tree, arm + "-status", List.of("game-status"), 120).get("result")).get("pid");
            if (pid == null) throw new IOException(arm + " run " + run + ": no running game to measure");
            for (String phase : List.of("cold", "warm")) {
                Path capture = dir.resolve(phase);
                // A snippet that starts this capture's workload right before it, so the window and the
                // work line up whatever the previous capture's report took.
                Object before = Json.object(measure.getOrDefault("before", Map.of())).get(phase);
                for (Object item : before == null ? List.of() : before instanceof List<?> list ? Json.array(list) : List.of(before)) {
                    Map<String, Object> step = Json.object(item);
                    Map<String, Object> answer = eval(tree, arm + "-before-" + phase, file(experimentDir, Json.text(step, "file", "")), Json.text(step, "thread", "game"));
                    if (step.containsKey("expect") && !String.valueOf(answer.get("value")).equals(String.valueOf(step.get("expect")))) {
                        throw new IOException("Before " + phase + " " + step.get("file") + " returned " + answer.get("value") + ", expected " + step.get("expect"));
                    }
                }
                List<String> args = new ArrayList<>(List.of("perf", "--attach", String.valueOf(((Number) pid).longValue()),
                    "--allow-hidden", "--mod-root", tree.toString(), "--duration", String.valueOf(measure.getOrDefault("seconds", 20)),
                    "--warmup", phase.equals("cold") ? "0" : String.valueOf(measure.getOrDefault("warmup", 10)), "--out", capture.toString(), "--no-heap",
                    "--parent", record.record.get("id").toString()));
                if (!Json.text(record.record, "session", "").isBlank()) {
                    args.add("--session");
                    args.add(Json.text(record.record, "session", ""));
                }
                if (measure.containsKey("probes")) {
                    args.add("--probes");
                    args.add(file(experimentDir, measure.get("probes").toString()).toString());
                }
                Map<String, Object> captured = mmt(tree, arm + "-" + phase, args, 900);
                if (!Boolean.TRUE.equals(captured.get("ok")) && Files.isRegularFile(capture.resolve("capture.jfr"))
                    && "complete".equals(Performance.properties(capture.resolve("status.properties")).getProperty("state"))) {
                    // The recording finished but the command lost track of it: render the report from what was saved.
                    captured = PerfReport.write(capture, capture);
                    log.add(Json.map("step", arm + "-" + phase + "-report-again", "reason", "capture completed after its command gave up"));
                }
                if (!Boolean.TRUE.equals(captured.get("ok"))) throw new IOException(arm + " " + phase + " capture failed: " + captured.get("error"));
                Map<String, Object> report = Json.read(capture.resolve("report.json"));
                Map<String, Object> scopes = new LinkedHashMap<>();
                for (Object item : Json.array(measure.getOrDefault("scopes", List.of()))) {
                    Map<String, Object> scope = Json.object(item);
                    try {
                        scopes.put(scope.get("name").toString(), metric(report, scope));
                    } catch (IllegalStateException absent) {
                        scopes.put(scope.get("name").toString(), null);
                    }
                }
                captures.put(phase, Json.map("value", metric(report, Json.object(measure.get("metric"))), "scopes", scopes,
                    "tickMeanMs", PerfAdvice.value(report, "metrics.tick_ms.mean"), "frameMeanMs", PerfAdvice.value(report, "metrics.frame_ms.mean"),
                    "report", capture.resolve("report.json").toString()));
            }
            row.put("captures", captures);
            Map<String, Object> gates = new LinkedHashMap<>();
            for (Object item : Json.array(experiment.get("gates"))) {
                Map<String, Object> gate = Json.object(item);
                String name = gate.get("name").toString();
                if (Boolean.TRUE.equals(gate.get("frame"))) {
                    if (gate.containsKey("file")) eval(tree, arm + "-frame-setup", file(experimentDir, gate.get("file").toString()), Json.text(gate, "thread", "game"));
                    Map<String, Object> shot = mmt(tree, arm + "-frame", List.of("ui-check"), 600);
                    if (!Boolean.TRUE.equals(shot.get("ok"))) throw new IOException("Frame capture failed: " + shot.get("error"));
                    Path png = Path.of(Json.text(Json.object(Json.object(shot.get("result")).get("capture")), "path", ""));
                    Path kept = dir.resolve("frame-" + name + ".png");
                    Files.copy(png, kept, StandardCopyOption.REPLACE_EXISTING);
                    gates.put(name, kept.toString());
                } else {
                    Map<String, Object> answer = eval(tree, arm + "-gate-" + name, file(experimentDir, gate.get("file").toString()), Json.text(gate, "thread", "game"));
                    gates.put(name, String.valueOf(answer.get("value")));
                }
            }
            row.put("gates", gates);
        } finally {
            mmt(tree, arm + "-game-stop", List.of("game-stop"), 300);
        }
        row.put("seconds", (System.nanoTime() - started) / 1e9);
        return row;
    }

    static double metric(Map<String, Object> report, Map<String, Object> metric) {
        if (metric.containsKey("threadCpu")) {
            // The operating system's CPU time of the threads whose names match, over the window: exact, and a
            // preempted thread's wait is not counted, as it is in execution samples on a busy machine.
            java.util.regex.Pattern threads = java.util.regex.Pattern.compile(metric.get("threadCpu").toString());
            String statistic = Json.text(metric, "statistic", "cpuMsPerSecond");
            double total = 0;
            int matched = 0;
            for (Object item : Json.array(report.getOrDefault("threadCpuTime", List.of()))) {
                Map<String, Object> row = Json.object(item);
                if (!threads.matcher(row.get("thread").toString()).find()) continue;
                matched++;
                total += ((Number) row.get(statistic)).doubleValue();
            }
            if (matched == 0) throw new IllegalStateException("No thread matching " + metric.get("threadCpu") + " has CPU time in " + report.get("recording"));
            if (metric.containsKey("perCall")) {
                // CPU ms per call of a counted probe: what each unit of work cost, when the arms finish different amounts in the window.
                Object calls = Json.object(report.getOrDefault("probeCalls", Map.of())).get(metric.get("perCall").toString());
                double seconds = ((Number) report.getOrDefault("seconds", 0)).doubleValue();
                if (!(calls instanceof Number n) || n.longValue() <= 0 || seconds <= 0) {
                    throw new IllegalStateException("Probe " + metric.get("perCall") + " counted no calls in " + report.get("recording"));
                }
                return total * seconds / n.doubleValue();
            }
            return total;
        }
        if (metric.containsKey("report")) {
            Object value = PerfAdvice.value(report, metric.get("report").toString());
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                throw new IllegalStateException("Metric " + metric.get("report") + " is unavailable in " + report.get("recording"));
            }
            return number.doubleValue();
        }
        if (metric.containsKey("mod")) {
            String statistic = Json.text(metric, "statistic", metric.containsKey("method") ? "cpuMsPerSecond" : "sampledCpuMsPerSecond");
            for (Object item : Json.array(Json.object(report.get("attribution")).get("mods"))) {
                Map<String, Object> row = Json.object(item);
                if (!metric.get("mod").equals(row.get("id"))) continue;
                if (metric.containsKey("threads")) {
                    // Sampled CPU on the mod's threads whose names match: one feature's own work, apart from a
                    // background job of the same mod that takes up whatever time the rest leaves it.
                    java.util.regex.Pattern threads = java.util.regex.Pattern.compile(metric.get("threads").toString());
                    double period = ((Number) Json.object(report.get("attribution")).getOrDefault("samplePeriodMs", 0)).doubleValue();
                    double seconds = ((Number) report.getOrDefault("seconds", 0)).doubleValue();
                    if (period <= 0 || seconds <= 0) throw new IllegalStateException("No sample period or duration in " + report.get("recording"));
                    long samples = 0;
                    for (Object t : Json.array(row.getOrDefault("threads", List.of()))) {
                        Map<String, Object> thread = Json.object(t);
                        if (threads.matcher(thread.get("name").toString()).find()) samples += ((Number) thread.get("samples")).longValue();
                    }
                    return samples * period / seconds;
                }
                if (metric.containsKey("method")) {
                    // Sampled CPU with the method anywhere on the stack; the busiest overload when several match.
                    double best = 0;
                    for (Object m : Json.array(row.getOrDefault("inclusiveMethods", List.of()))) {
                        Map<String, Object> method = Json.object(m);
                        if (method.get("name").toString().contains(metric.get("method").toString()) && method.get(statistic) instanceof Number n) best = Math.max(best, n.doubleValue());
                    }
                    return best;
                }
                if (row.get(statistic) instanceof Number number) return number.doubleValue();
            }
            throw new IllegalStateException("Mod " + metric.get("mod") + " has no " + statistic + " in " + report.get("recording") + "; was it sampled?");
        }
        String label = metric.get("probe").toString();
        String statistic = Json.text(metric, "statistic", "inclusiveMsPerTick");
        Map<String, Object> attribution = Json.object(report.get("attribution"));
        List<Object> rows = new ArrayList<>(Json.array(attribution.getOrDefault("unattributed", List.of())));
        for (Object mod : Json.array(attribution.get("mods"))) rows.addAll(Json.array(Json.object(mod).get("exact")));
        for (Object item : rows) {
            Map<String, Object> row = Json.object(item);
            if (!row.get("label").equals(label)) continue;
            if (row.get(statistic) instanceof Number number) return number.doubleValue();
            // A per-thread statistic (serverInclusiveMsPerTick) is absent when no call ran on that thread.
            if (row.get("threads") instanceof List<?> && statistic.matches("(server|render|other)[A-Z][A-Za-z]*")) return 0;
        }
        throw new IllegalStateException("Timing probe " + label + " has no " + statistic + " in " + report.get("recording") + "; was the method called during the capture?");
    }

    static void evaluate(Map<String, Object> experiment, Map<String, Object> result, List<Object> measured) throws Exception {
        Map<Integer, Map<String, Map<String, Object>>> pairs = PerfStopping.pairs(measured);
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
        Map<String, Object> scopeSpeedups = new LinkedHashMap<>();
        for (Object item : Json.array(Json.object(experiment.get("measure")).getOrDefault("scopes", List.of()))) {
            String name = Json.object(item).get("name").toString();
            Map<String, Object> phases = new LinkedHashMap<>();
            for (String phase : List.of("cold", "warm")) {
                List<Double> ratios = new ArrayList<>(), base = new ArrayList<>(), cand = new ArrayList<>();
                for (var pair : pairs.values()) {
                    Object b = scope(pair.get("baseline"), phase, name);
                    Object c = scope(pair.get("candidate"), phase, name);
                    // A scope one arm did not register (a method below the sampled top list reads 0) is not a ratio.
                    if (!(b instanceof Number bn) || !(c instanceof Number cn) || bn.doubleValue() <= 0 || cn.doubleValue() <= 0) continue;
                    base.add(bn.doubleValue());
                    cand.add(cn.doubleValue());
                    ratios.add(bn.doubleValue() / Math.max(cn.doubleValue(), 1e-12));
                }
                if (ratios.isEmpty()) {
                    phases.put(phase, Json.map("pairs", 0));
                    continue;
                }
                Map<String, Object> summary = speedup(ratios, 20261114L);
                summary.put("baselineMean", base.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN));
                summary.put("candidateMean", cand.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN));
                phases.put(phase, summary);
            }
            scopeSpeedups.put(name, phases);
        }
        if (!scopeSpeedups.isEmpty()) speedups.put("scopes", scopeSpeedups);
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
            + "and the 95% bootstrap interval of the speedup is above 1 for both cold and warm captures. Runs stop early at the first failed gate, "
            + "or from the minimum pairs on once 99% t intervals lie above 1 (both) or below the minimum speedup (either).");
    }

    static Object scope(Map<String, Object> row, String phase, String name) {
        return Json.object(Json.object(Json.object(row.get("captures")).get(phase)).getOrDefault("scopes", Map.of())).get(name);
    }

    /** The run's record: phases from every step, gates, speedups with intervals, the verdict and why. */
    void finishRecord(Map<String, Object> result, boolean accepted) throws Exception {
        List<Object> gates = new ArrayList<>();
        for (Object item : Json.array(result.getOrDefault("gates", List.of()))) {
            Map<String, Object> gate = Json.object(item);
            gates.add(Json.map("name", gate.get("name"), "compare", gate.get("compare"), "passed", gate.get("passed"), "failures", gate.get("failures")));
        }
        record.record.put("gates", gates);
        if (result.get("speedup") instanceof Map<?, ?> speedup) {
            Map<String, Object> summary = new LinkedHashMap<>();
            for (String phase : List.of("cold", "warm")) {
                if (speedup.get(phase) instanceof Map<?, ?> s) summary.put(phase, PerfRecord.interval(Json.object(s)));
            }
            if (speedup.get("scopes") instanceof Map<?, ?> scopes) {
                Map<String, Object> each = new LinkedHashMap<>();
                for (var entry : Json.object(scopes).entrySet()) {
                    Map<String, Object> phases = new LinkedHashMap<>();
                    for (var phase : Json.object(entry.getValue()).entrySet()) phases.put(phase.getKey(), PerfRecord.interval(Json.object(phase.getValue())));
                    each.put(entry.getKey(), phases);
                }
                summary.put("scopes", each);
            }
            record.record.put("speedup", summary);
        }
        String verdict = verdict(result);
        record.finish(verdict, accepted ? null : PerfRecord.rejection(result));
        result.put("record", record.save(home, out).toString());
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
        Map<String, Object> answer = mmt(tree, name, List.of("eval", "--file", file.toString(), "--thread", thread, "--timeout", "120"), 300);
        if (!Boolean.TRUE.equals(answer.get("ok"))) throw new IOException(name + " (" + file.getFileName() + ") failed: " + answer.get("error"));
        return Json.object(answer.get("result"));
    }

    /** Runs a mmt command as a child process, so each step keeps its own receipt and lock. */
    /**
     * A child mmt JVM that writes its answer in UTF-8, as childOutput reads it. Redirected to a file, Java's stdout
     * otherwise uses the platform charset (windows-1252): Storage Network's second candidate died when a capture matched
     * a known pattern whose text holds a middle dot. Java 18+ reads stdout.encoding, Java 17 sun.stdout.encoding.
     */
    static List<String> childJava(Path home) throws Exception {
        return new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java"),
            "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8",
            "-Dmmt.resources=" + home,
            "-cp", Path.of(Mmt.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(), "Mmt"));
    }

    /** A child's output as UTF-8; a byte that is not UTF-8 becomes U+FFFD rather than ending the run. */
    static String childOutput(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    Map<String, Object> mmt(Path tree, String name, List<String> args, int timeout) throws Exception {
        List<String> command = childJava(home);
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
        String file = String.format(Locale.ROOT, "%03d-%s", stepNumber.getAndIncrement(), name.replaceAll("[^A-Za-z0-9_.-]", "_"));
        Path stdout = logs.resolve(file + ".json"), stderr = logs.resolve(file + ".log");
        long start = System.nanoTime();
        Process process = new ProcessBuilder(command).directory(home.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroyForcibly();
            throw new IOException(name + " timed out after " + timeout + " s; see " + stderr);
        }
        Map<String, Object> answer;
        String text = childOutput(stdout).strip();
        try {
            answer = Json.object(Json.parse(text.substring(Math.max(0, text.lastIndexOf("\n{") + 1))));
        } catch (Exception unreadable) {
            answer = Json.map("ok", false, "error", "No JSON result (exit " + process.exitValue() + "): " + Run.tail(stderr));
        }
        if (!Boolean.TRUE.equals(answer.get("ok")) && !answer.containsKey("error")) {
            answer.put("error", Json.text(Json.object(answer.getOrDefault("result", Map.of())), "error", Run.tail(stderr)));
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        log.add(Json.map("step", name, "exit", process.exitValue(), "seconds", seconds, "log", stderr.toString(), "result", stdout.toString()));
        classify(name, answer, seconds);
        return answer;
    }

    /** Which session phase a child step's wall time belongs to. */
    void classify(String name, Map<String, Object> answer, double seconds) {
        if (name.endsWith("-launch")) {
            record.launchStages(name, answer, seconds);
        } else if (name.endsWith("-tests")) {
            record.step("build", name, seconds);
        } else if (name.endsWith("-frame-setup")) {
            record.step("gates", name, seconds);
        } else if (name.endsWith("-setup") || name.endsWith("-status") || name.contains("-before-")) {
            record.step("setup", name, seconds);
        } else if (name.endsWith("-warm")) {
            double warmup = Math.min(seconds, ((Number) Json.object(experiment.get("measure")).getOrDefault("warmup", 10)).doubleValue());
            record.step("warmup", name, warmup);
            record.step("measure", name, seconds - warmup);
        } else if (name.endsWith("-cold")) {
            record.step("measure", name, seconds);
        } else if (name.contains("-gate-") || name.endsWith("-frame")) {
            record.step("gates", name, seconds);
        } else {
            record.step("other", name, seconds);
        }
    }

    static String git(Path directory, String... args) throws Exception {
        List<String> command = gitCommand(directory, args);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(300, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IOException("git " + String.join(" ", args) + ": " + output.strip());
        return output;
    }

    /**
     * git with long paths allowed: a worktree under OUT adds OUT's length to every file path, and on Windows Storage
     * Network's Patchouli pages then passed 260 characters and the checkout failed before any launch.
     */
    static List<String> gitCommand(Path directory, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.longpaths=true", "-C", directory.toString()));
        command.addAll(Arrays.asList(args));
        return command;
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

    /**
     * accepted, rejected or error. A run that threw (a tool crash, a game that never answered, unreadable child output)
     * decided nothing about the candidate, so it is an error even when some pairs were measured.
     */
    static String verdict(Map<String, Object> result) {
        if (result.containsKey("error")) return "error";
        return Boolean.TRUE.equals(result.get("accepted")) ? "accepted" : "rejected";
    }

    static String summary(Map<String, Object> result) {
        StringBuilder text = new StringBuilder(verdict(result).toUpperCase(Locale.ROOT));
        if (result.containsKey("error")) text.append(": ").append(result.get("error"));
        if (result.get("stopped") instanceof Map<?, ?> stopped) text.append("\nStopped early: ").append(stopped.get("reason"));
        if (result.get("wallSeconds") instanceof Number wall) {
            text.append(String.format(Locale.ROOT, "%nWall time %.1f min, arms %s", wall.doubleValue() / 60, result.get("arms")));
        }
        if (result.get("speedup") instanceof Map<?, ?> speedup) for (String phase : List.of("cold", "warm")) {
            Map<String, Object> s = Json.object(speedup.get(phase));
            text.append(String.format(Locale.ROOT, "%n%s: %.2fx (95%% %.2f–%.2f) over %s paired launches; baseline %.4f → candidate %.4f",
                phase, s.get("geometricMean"), s.get("low"), s.get("high"), s.get("pairs"), s.get("baselineMean"), s.get("candidateMean")));
        }
        if (result.get("speedup") instanceof Map<?, ?> speedup && speedup.get("scopes") instanceof Map<?, ?> scopes) {
            for (var entry : Json.object(scopes).entrySet()) {
                text.append("\nscope ").append(entry.getKey()).append(":");
                for (var phase : Json.object(entry.getValue()).entrySet()) {
                    Map<String, Object> s = Json.object(phase.getValue());
                    if (!(s.get("geometricMean") instanceof Number)) {
                        text.append(" ").append(phase.getKey()).append(" unmeasured;");
                        continue;
                    }
                    text.append(String.format(Locale.ROOT, " %s %.2fx (95%% %.2f–%.2f);", phase.getKey(), s.get("geometricMean"), s.get("low"), s.get("high")));
                }
            }
        }
        if (result.containsKey("record")) text.append("\nRun record: ").append(result.get("record"));
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
            <title>Minecraft Modding Tools · Optimization</title>
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
            el('verdict').textContent=(d.error?'Error (nothing decided about the candidate)':d.accepted?'Accepted':'Rejected')+(d.error?': '+d.error:'');el('verdict').className=d.accepted?'ok':'bad';
            el('rule').textContent=d.rule||'';
            for(const [k,s] of Object.entries(d.speedup||{}))if(k!=='scopes')row('speed',[k,f(s.geometricMean)+'×',f(s.low)+'–'+f(s.high),f(s.baselineMean),f(s.candidateMean),s.pairs]);
            const scopeRow=(name,k,s)=>row('speed',[name+' · '+k,s.geometricMean?f(s.geometricMean)+'×':'unmeasured',
              s.low?f(s.low)+'–'+f(s.high):'',f(s.baselineMean),f(s.candidateMean),s.pairs]);
            for(const [name,ph] of Object.entries(d.speedup?.scopes||{}))for(const [k,s] of Object.entries(ph))scopeRow(name,k,s);
            const detail=g=>g.failures.length?g.failures:(g.baselineToBaseline||g.value||g.worstRelativeDifference||g.expected||'');
            for(const g of d.gates||[])row('gates',[g.name,g.compare,g.passed?'passed':'failed',JSON.stringify(detail(g))]);
            for(const m of d.measurements||[])row('runs',[m.run,m.arm,f(m.captures?.cold?.value),f(m.captures?.warm?.value),f(m.launchSeconds)]);
            </script></html>
            """.replace("__DATA__", data);
    }
}
