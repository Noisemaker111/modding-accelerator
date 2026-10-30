import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * server-check merge gate: each pinned plugin in bench/server-plugins/manifest.json with a serverCheck entry is cloned
 * fresh and its scenario must pass through the packaged command on a real Paper server with the bot, cold then warm,
 * with every expect line passing. The bot's own scenario (tools/tests/server-check-bot.scenario) must pass with its
 * secret from .modaccel/secrets.properties, leave that secret nowhere in receipts, logs or server files, and fail with
 * "needs secret" without it. Then each break made on purpose must fail at the step the manifest names.
 * Usage: java ServerCheckFixtures.java <checkout> <modaccel.jar> [scenario|bot|breaks]
 * With a part named, only that part runs, so CI runs the parts side by side; with none, all of them run.
 */
public final class ServerCheckFixtures {
    record Run(int exit, String output, double seconds) { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("Usage: java ServerCheckFixtures.java <checkout> <modaccel.jar> [scenario|bot|breaks]");
        Set<String> parts = args.length == 3 ? Set.of(args[2]) : Set.of("scenario", "bot", "breaks");
        if (!Set.of("scenario", "bot", "breaks").containsAll(parts)) throw new IllegalArgumentException("Unknown part " + args[2] + "; use scenario, bot or breaks");
        Path home = Path.of(args[0]).toRealPath(), jar = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) throw new IOException("Missing package: " + jar);
        @SuppressWarnings("unchecked") List<Object> plugins = (List<Object>) ((Map<String, Object>) new Parser(Files.readString(home.resolve("bench/server-plugins/manifest.json"))).value()).get("plugins");
        int checked = 0;
        Path runs = home.resolve(".runs"); Files.createDirectories(runs);
        for (Object item : plugins) {
            @SuppressWarnings("unchecked") Map<String, Object> plugin = (Map<String, Object>) item;
            @SuppressWarnings("unchecked") Map<String, Object> check = (Map<String, Object>) plugin.get("serverCheck");
            if (check == null) continue;
            String id = plugin.get("id").toString();
            Path root = Files.createTempDirectory(runs, "server-check-" + id + "-").resolve(id);
            require(process(List.of("git", "clone", "--quiet", plugin.get("repo").toString(), root.toString()), home, Map.of(), 5), "git clone");
            require(process(List.of("git", "-C", root.toString(), "checkout", "--quiet", plugin.get("commit").toString()), home, Map.of(), 2), "git checkout");
            Path scenario = home.resolve(check.get("scenario").toString());
            long expects = Files.readAllLines(scenario).stream().filter(l -> l.strip().startsWith("expect-")).count();

            if (parts.contains("scenario")) {
                Run run = modaccel(home, jar, root, Map.of(), "server-check", "--file", scenario.toString());
                long passed = count(run.output(), "\"status\":\"pass\"");
                if (run.exit() != 0 || !run.output().contains("server-check passed") || passed != expects)
                    throw new AssertionError(id + " server-check: expected exit 0 and all " + expects + " expect lines passing; got exit " + run.exit() + ", " + passed + " passing\n" + run.output());
                System.out.printf(Locale.ROOT, "PASS %s server-check, fresh world: %d expect lines in %.1fs (%s)%n", id, expects, run.seconds(), times(run.output()));
                for (Object extra : (List<?>) check.getOrDefault("additionalScenarios", List.of())) {
                    @SuppressWarnings("unchecked") Map<String, Object> entry = (Map<String, Object>) extra;
                    Path file = home.resolve(entry.get("scenario").toString());
                    @SuppressWarnings("unchecked") Map<String, String> env = (Map<String, String>) entry.getOrDefault("env", Map.of());
                    long required = Files.readAllLines(file).stream().filter(l -> l.strip().startsWith("expect-")).count();
                    Run extraRun = modaccel(home, jar, root, env, "server-check", "--file", file.toString());
                    long extraPassed = count(extraRun.output(), "\"status\":\"pass\"");
                    if (extraRun.exit() != 0 || !extraRun.output().contains("server-check passed") || extraPassed != required)
                        throw new AssertionError(id + " " + file + ": expected " + required + " passing assertions, got " + extraPassed + "\n" + extraRun.output());
                    System.out.printf(Locale.ROOT, "PASS %s %s: %d expect lines in %.1fs%n", id, file.getFileName(), required, extraRun.seconds());
                }
            }

            // The bot itself, with a secret from the gitignored secrets file; then without it.
            Path bot = home.resolve("tools/tests/server-check-bot.scenario");
            if (parts.contains("bot")) {
            long botExpects = Files.readAllLines(bot).stream().filter(l -> l.strip().startsWith("expect-")).count();
            String secret = "modaccel-" + UUID.randomUUID();
            Path secrets = root.resolve(".modaccel/secrets.properties"); Files.createDirectories(secrets.getParent());
            Files.writeString(secrets, "BOT_SECRET=" + secret + "\n");
            Run withSecret = modaccel(home, jar, root, Map.of(), "server-check", "--file", bot.toString());
            if (withSecret.exit() != 0 || count(withSecret.output(), "\"status\":\"pass\"") != botExpects)
                throw new AssertionError(id + " bot scenario: expected all " + botExpects + " expect lines passing; got exit " + withSecret.exit() + "\n" + withSecret.output());
            if (withSecret.output().contains(secret)) throw new AssertionError("The secret was printed by server-check:\n" + withSecret.output());
            List<Path> leaks = new ArrayList<>();
            for (Path dir : List.of(root.resolve(".runs"), root.resolve(".modaccel"))) if (Files.isDirectory(dir)) try (var walk = Files.walk(dir)) {
                for (Path f : walk.filter(Files::isRegularFile).filter(f -> !f.equals(secrets)).toList())
                    if (new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1).contains(secret)) leaks.add(f);
            }
            if (!leaks.isEmpty()) throw new AssertionError("The secret was written in clear to: " + leaks);
            System.out.printf(Locale.ROOT, "PASS %s bot scenario: %d expect lines in %.1fs, secret masked in every receipt, log and server file%n", id, botExpects, withSecret.seconds());
            Path negativeRcon = home.resolve("tools/tests/server-check-negative-rcon.scenario");
            Run forbidden = modaccel(home, jar, root, Map.of(), "server-check", "--file", negativeRcon.toString());
            if (forbidden.exit() != 1 || !"expect-no-rcon list ~players online~ for 0s".equals(failedStep(forbidden.output())))
                throw new AssertionError("expect-no-rcon must reject a matching RCON response: " + forbidden.output());
            System.out.println("PASS expect-no-rcon rejects a matching response");
            Path externalFailure = home.resolve("tools/tests/server-check-external-failure.scenario");
            Run external = modaccel(home, jar, root, Map.of(), "server-check", "--file", externalFailure.toString());
            if (external.exit() != 1 || !external.output().contains("External test exit 1"))
                throw new AssertionError("A failing external test must not pass even with matching output: " + external.output());
            System.out.println("PASS external test rejects a nonzero exit despite matching output");
            Files.delete(secrets);
            Run without = modaccel(home, jar, root, Map.of(), "server-check", "--file", bot.toString());
            if (without.exit() != 1 || !without.output().contains("needs secret BOT_SECRET") || without.output().contains("\"status\":\"pass\""))
                throw new AssertionError(id + " bot scenario without its secret: expected exit 1 with \"needs secret BOT_SECRET\" and nothing run; got exit " + without.exit() + "\n" + without.output());
            System.out.printf(Locale.ROOT, "PASS %s bot scenario without BOT_SECRET fails: needs secret BOT_SECRET (%.1fs)%n", id, without.seconds());
            }

            // Each break made on purpose in a throwaway copy must fail at the step the manifest names.
            if (parts.contains("breaks")) {
            Path copy = Files.createTempDirectory(root.getParent(), id + "-broken-").resolve(id);
            copyTree(root, copy);
            for (Object b : (List<?>) check.get("breaks")) {
                @SuppressWarnings("unchecked") Map<String, Object> brk = (Map<String, Object>) b;
                Path file = copy.resolve(brk.get("file").toString());
                String original = Files.readString(file, StandardCharsets.UTF_8), find = brk.get("find").toString();
                int at = original.indexOf(find);
                if (at < 0 || original.indexOf(find, at + 1) >= 0) throw new AssertionError(id + " break \"" + brk.get("name") + "\": expected exactly one " + find + " in " + file);
                Files.writeString(file, original.replace(find, brk.get("replace").toString()), StandardCharsets.UTF_8);
                Run run = modaccel(home, jar, copy, Map.of(), "server-check", "--file", scenario.toString());
                String failsAt = brk.get("failsAt").toString();
                String failed = failedStep(run.output());
                if (run.exit() != 1 || failed == null || !failed.contains(failsAt))
                    throw new AssertionError(id + " break \"" + brk.get("name") + "\": expected exit 1 failing at " + failsAt + "; got exit " + run.exit() + ", failing step " + failed + "\n" + run.output());
                System.out.printf(Locale.ROOT, "PASS %s break \"%s\" fails at: %s (%.1fs)%n", id, brk.get("name"), failed, run.seconds());
                Files.writeString(file, original, StandardCharsets.UTF_8);
            }
            }
            checked++;
        }
        if (checked == 0) throw new AssertionError("bench/server-plugins/manifest.json has no plugin with a serverCheck entry");
        System.out.println("PASS server-check fixtures: " + String.join(", ", new TreeSet<>(parts)));
    }

    static long count(String text, String needle) { long n = 0; for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) n++; return n; }
    /** The step text of the first failing step in the JSON receipt printed by --json. */
    static String failedStep(String output) {
        int at = output.indexOf("\"status\":\"fail\"");
        if (at < 0) return null;
        int step = output.lastIndexOf("\"step\":\"", at);
        if (step < 0) return null;
        int end = output.indexOf("\",\"", step + 8);
        return output.substring(step + 8, end < 0 ? at : end).replace("\\\\", "\\");
    }
    static String times(String output) {
        int at = output.indexOf("Times: ");
        if (at < 0) return "";
        int end = output.indexOf('.', output.indexOf("scenario", at));
        return output.substring(at + 7, end < 0 ? Math.min(output.length(), at + 80) : end);
    }
    static void copyTree(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path rel = from.relativize(p);
                if (rel.getNameCount() > 0 && Set.of(".git", ".gradle", "build", ".modaccel", ".runs").contains(rel.getName(0).toString())) continue;
                Path target = to.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(target); else Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    static Run modaccel(Path home, Path jar, Path root, Map<String, String> env, String... args) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows") ? "java.exe" : "java").toString();
        List<String> command = new ArrayList<>(List.of(java, "-Dmodaccel.home=" + home, "-jar", jar.toString()));
        command.addAll(List.of(args)); command.addAll(List.of("--root", root.toString(), "--json"));
        return process(command, home, env, 10);
    }
    static Run process(List<String> command, Path directory, Map<String, String> env, int minutes) throws Exception {
        Path output = Files.createTempFile("modaccel-server-check-", ".log");
        long start = System.nanoTime();
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().remove("BOT_SECRET"); builder.environment().putAll(env);
        Process process = builder.start();
        if (!process.waitFor(Duration.ofMinutes(minutes).toMillis(), TimeUnit.MILLISECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly();
            throw new AssertionError("Timed out after " + minutes + " min: " + String.join(" ", command));
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        String text = new String(Files.readAllBytes(output), StandardCharsets.UTF_8); Files.deleteIfExists(output);
        return new Run(process.exitValue(), text, seconds);
    }
    static Run require(Run run, String what) throws IOException { if (run.exit() != 0) throw new IOException(what + " failed with exit " + run.exit() + "\n" + run.output()); return run; }

    /** A small JSON reader for the manifest, so this file runs on its own with java ServerCheckFixtures.java. */
    static final class Parser {
        final String s; int i;
        Parser(String s) { this.s = s; }
        Object value() {
            skip(); char c = s.charAt(i);
            if (c == '{') { i++; Map<String, Object> m = new LinkedHashMap<>(); skip(); if (s.charAt(i) == '}') { i++; return m; }
                while (true) { skip(); String k = string(); skip(); i++; m.put(k, value()); skip(); if (s.charAt(i++) == '}') return m; } }
            if (c == '[') { i++; List<Object> l = new ArrayList<>(); skip(); if (s.charAt(i) == ']') { i++; return l; }
                while (true) { l.add(value()); skip(); if (s.charAt(i++) == ']') return l; } }
            if (c == '"') return string();
            int start = i; while (i < s.length() && ",}] \n\r\t".indexOf(s.charAt(i)) < 0) i++;
            String word = s.substring(start, i);
            return switch (word) { case "true" -> true; case "false" -> false; case "null" -> null; default -> Double.parseDouble(word); };
        }
        String string() {
            StringBuilder b = new StringBuilder(); i++;
            while (s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) { case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r'); case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; } default -> b.append(e); }
            }
            i++; return b.toString();
        }
        void skip() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    }
}
