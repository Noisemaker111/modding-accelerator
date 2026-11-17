import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipFile;

/**
 * A required library with no release for the target is ported from its own source by the same porter, then built and kept
 * in a local Maven repository (~/.mmt/ported), which every mmt Gradle run can read. The source is the repository the
 * library's published project names; the commit is the release's tag, else the last commit on the branch for its game
 * version made before the release was published. The port's receipt goes with the jar. Nothing is published anywhere.
 */
final class LibraryPort {

    private LibraryPort() {}

    /** The local Maven repository of ported libraries. */
    static Path store() {
        return Path.of(System.getProperty("user.home"), ".mmt/ported").toAbsolutePath().normalize();
    }

    /** The version a ported library is kept under: the original release, marked as mmt's port to the target. */
    static String version(String old, String to) {
        return old + "-mmt-" + to;
    }

    /** The ported jar for group:name:old on the target, or null when there is none yet. */
    static Path kept(String group, String name, String old, String to) {
        String version = version(old, to);
        Path jar = store().resolve(group.replace('.', '/')).resolve(name).resolve(version).resolve(name + "-" + version + ".jar");
        return Files.isRegularFile(jar) ? jar : null;
    }

    /**
     * Ports the library behind binary from `from` to `to` unless it is already kept, and returns the receipt of what was
     * kept. Throws with the reason when the source cannot be found, or the port does not build.
     */
    static Map<String, Object> ensure(Target.Setup setup, String group, String name, String old, Path binary, String from, String loader)
        throws Exception {
        Path jar = kept(group, name, old, setup.to);
        if (jar != null) return Json.read(jar.resolveSibling("receipt.json"));
        // A library port may need a library of its own ported in turn; two levels down, it stops and says so.
        int depth = Integer.parseInt(System.getenv().getOrDefault("MMT_LIBRARY_DEPTH", "0"));
        if (depth >= 2) throw new IOException("it is a library of a library of a library; mmt ports libraries at most two levels down");
        String hash = Dependencies.sha512(binary);
        String source = "", published = "";
        try {
            var release = Json.object(Json.parse(Target.fetch(setup.build, "https://api.modrinth.com/v2/version_file/" + hash + "?algorithm=sha512")));
            var project = Json.object(Json.parse(Target.fetch(setup.build, "https://api.modrinth.com/v2/project/" + release.get("project_id"))));
            source = Json.text(project, "source_url", "");
            published = Json.text(release, "date_published", "");
        } catch (IOException unindexed) {
            // A Maven jar is often not the file uploaded to Modrinth; the jar's own metadata names its repository, and its
            // entries carry the time it was built.
        }
        if (!source.matches(REPOSITORY)) source = metadataRepository(binary);
        if (published.isEmpty()) published = builtAt(binary);
        // A jar built with fixed entry times: another loader's jar of the same release (same group and version) may carry
        // the real ones.
        if (published.isEmpty()) {
            Path groupDir = binary;
            for (int i = 0; i < 4 && groupDir != null; i++) groupDir = groupDir.getParent();
            if (groupDir != null && Files.isDirectory(groupDir)) try (var walk = Files.walk(groupDir, 4)) {
                for (Path sibling : walk.filter(p -> p.getFileName().toString().endsWith("-" + old + ".jar")).toList()) {
                    published = builtAt(sibling);
                    if (!published.isEmpty()) break;
                }
            }
        }
        if (!source.matches(REPOSITORY)) throw new IOException(
            "neither its published project nor its own metadata names a source repository"
        );
        source = source.replaceAll("/$", "");
        Path work = Path.of(System.getProperty("user.home"), ".mmt/library-ports", name + "-" + old + "-" + setup.to);
        if (Files.isDirectory(work)) deleteTree(work);
        Files.createDirectories(work.getParent());
        git(work.getParent(), "clone", "--quiet", "--filter=blob:none", "--no-checkout", source + ".git", work.getFileName().toString());
        String[] commit = commit(work, old, from, published);
        git(work, "checkout", "--quiet", "--detach", commit[0]);
        String projectPath = loaderProject(work, loader);
        // The original builds first: that proves the commit is a working release, and it sets up the old decompiled game
        // the port carries removed classes from.
        Path baselineLog = work.resolveSibling(work.getFileName() + ".baseline.json");
        for (int attempt = 0; attempt < 2; attempt++) {
            List<String> baseline = PerfOptimize.childJava(Installation.home());
            baseline.addAll(List.of("compile", "--root", work.toString(), "--json"));
            if (projectPath != null) baseline.addAll(List.of("--project", projectPath));
            new ProcessBuilder(baseline).directory(work.toFile()).redirectOutput(baselineLog.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor();
            // A build that renames its projects (GlitchCore includes neoforge as :NeoForge) says what it has; the loader's
            // project is the one of that name, whatever its case.
            java.util.regex.Matcher has = java.util.regex.Pattern.compile("It has \\d+: ([^.\\\\]+)\\.").matcher(PerfOptimize.childOutput(baselineLog));
            String named = null;
            if (has.find()) for (String p : has.group(1).split(",\\s*")) if (p.trim().equalsIgnoreCase(":" + loader)) named = p.trim();
            if (named == null || named.equals(projectPath)) break;
            projectPath = named;
        }
        // The port runs as its own mmt process, with its own receipt and lock, on the selected loader's project.
        List<String> command = PerfOptimize.childJava(Installation.home());
        command.addAll(List.of("port", "--root", work.toString(), "--from", from, "--to", setup.to, "--fix", "--json"));
        if (projectPath != null) command.addAll(List.of("--project", projectPath));
        Path log = work.resolveSibling(work.getFileName() + ".port.json");
        ProcessBuilder child = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(false)
            .redirectOutput(log.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        child.environment().put("MMT_LIBRARY_DEPTH", String.valueOf(depth + 1));
        Process process = child.start();
        process.waitFor();
        Map<String, Object> result;
        try {
            result = Json.object(Json.parse(PerfOptimize.childOutput(log)));
        } catch (Exception unreadable) {
            throw new IOException("its source (" + source + " at " + commit[0] + ") was found, but its port wrote no result: " + log);
        }
        var inner = Json.object(result.getOrDefault("result", result));
        if (!Boolean.TRUE.equals(inner.get("built"))) throw new IOException(
            "its source (" + source + " at " + commit[0] + ", " + commit[1] + ") was found and ported, but the port did not build: " +
            Json.text(inner, "reason", Json.text(inner, "error", "see " + log))
        );
        Path built = matchingJar(work, binary);
        if (built == null) throw new IOException("its port built, but no jar holds the classes of " + binary.getFileName());
        String version = version(old, setup.to);
        Path dir = store().resolve(group.replace('.', '/')).resolve(name).resolve(version);
        Files.createDirectories(dir);
        Files.copy(built, dir.resolve(name + "-" + version + ".jar"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(dir.resolve(name + "-" + version + ".pom"),
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n  <modelVersion>4.0.0</modelVersion>\n" +
            "  <groupId>" + group + "</groupId>\n  <artifactId>" + name + "</artifactId>\n  <version>" + version + "</version>\n</project>\n");
        Map<String, Object> receipt = Json.map(
            "artifact", group + ":" + name,
            "from", old,
            "to", version,
            "gameVersion", setup.to,
            "source", source,
            "commit", commit[0],
            "commitEvidence", commit[1],
            "portReceipt", Json.text(result, "receipt", log.toString()),
            "evidence", "ported by mmt from its own source; kept only in the local repository " + store()
        );
        Json.save(dir.resolve("receipt.json"), receipt);
        return receipt;
    }

    static final String REPOSITORY = "https://(github\\.com|gitlab\\.com|codeberg\\.org)/[\\w.-]+/[\\w.-]+/?";

    /** The source repository a mod jar's own metadata names (issue tracker, sources, homepage), or "". */
    static String metadataRepository(Path jar) {
        try (var zip = new ZipFile(jar.toFile())) {
            StringBuilder text = new StringBuilder();
            for (String name : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml", "fabric.mod.json")) {
                var entry = zip.getEntry(name);
                if (entry != null) text.append(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8)).append('\n');
            }
            java.util.regex.Matcher url = java.util.regex.Pattern.compile("https://(?:github\\.com|gitlab\\.com|codeberg\\.org)/([\\w.-]+)/([\\w.-]+)").matcher(text);
            while (url.find()) {
                String repo = url.group(2).replaceAll("\\.git$", "");
                if (!url.group(1).equalsIgnoreCase("sponsors")) return "https://" + url.group().split("/")[2] + "/" + url.group(1) + "/" + repo;
            }
        } catch (IOException unreadable) {
            return "";
        }
        return "";
    }

    /** When a jar was built: its newest entry time, unless the build wrote fixed times (reproducible archives), else "". */
    static String builtAt(Path jar) {
        long newest = 0;
        try (var zip = new ZipFile(jar.toFile())) {
            for (var e : java.util.Collections.list(zip.entries())) newest = Math.max(newest, e.getTime());
        } catch (IOException unreadable) {
            return "";
        }
        return newest < Instant.parse("2012-01-01T00:00:00Z").toEpochMilli() ? "" : Instant.ofEpochMilli(newest).plusSeconds(86400).toString();
    }

    /** {commit, why}: the release's tag, else the last commit before the release on the branch for its game version. */
    static String[] commit(Path work, String version, String from, String published) throws Exception {
        String core = version.replaceFirst("\\+.*", "");
        List<String> tags = git(work, "tag", "--list").lines().map(String::trim).filter(t -> !t.isEmpty()).toList();
        for (String tag : tags) {
            String bare = tag.replaceFirst("^(?i)v", "");
            if (bare.equals(version) || bare.equals(core) || bare.endsWith("-" + version) || bare.endsWith("-" + core)) return new String[] {
                git(work, "rev-list", "-n", "1", tag).trim(),
                "tag " + tag
            };
        }
        if (published.isEmpty()) throw new IOException("no tag names release " + version + ", and the release has no publish date");
        List<String> branches = git(work, "branch", "-r", "--format=%(refname:short)").lines().map(String::trim).toList();
        String branch = null;
        for (String b : branches) if (b.equals("origin/" + from)) branch = b;
        if (branch == null) for (String b : branches) if (b.startsWith("origin/") && from.startsWith(b.substring(7).replace(".x", "."))) branch = b;
        if (branch == null) branch = "origin/HEAD";
        String at = git(work, "rev-list", "-n", "1", "--before=" + Instant.parse(published).getEpochSecond(), branch).trim();
        if (at.isEmpty()) throw new IOException("no commit on " + branch + " predates release " + version + " (" + published + ")");
        return new String[] { at, "last commit on " + branch.substring(7) + " before the release was published (" + published + ")" };
    }

    /** The project of the library's build for this loader (":neoforge"), when it builds several. */
    static String loaderProject(Path work, String loader) {
        Path settings = Files.isRegularFile(work.resolve("settings.gradle")) ? work.resolve("settings.gradle") : work.resolve("settings.gradle.kts");
        try {
            String text = Files.readString(settings);
            if (Files.isDirectory(work.resolve(loader)) && text.matches("(?s).*['\"]:?" + loader + "['\"].*")) return ":" + loader;
        } catch (IOException none) {
            return null;
        }
        return null;
    }

    /** The built jar sharing the most classes with the original library jar. */
    static Path matchingJar(Path work, Path original) throws IOException {
        Set<String> wanted = classes(original);
        Path best = null;
        int bestShared = 0;
        List<Path> jars = new ArrayList<>();
        Files.walkFileTree(work, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return n.startsWith(".") && !dir.equals(work) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                String n = file.getFileName().toString();
                if (n.endsWith(".jar") && file.getParent().endsWith(Path.of("build", "libs")) && !n.matches(".*-(sources|javadoc|slim)\\.jar")) jars.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        for (Path jar : jars) {
            Set<String> have = classes(jar);
            have.retainAll(wanted);
            if (have.size() > bestShared) {
                bestShared = have.size();
                best = jar;
            }
        }
        return bestShared * 2 >= wanted.size() ? best : null;
    }

    static Set<String> classes(Path jar) throws IOException {
        Set<String> names = new HashSet<>();
        try (var zip = new ZipFile(jar.toFile())) {
            zip.stream().map(e -> e.getName()).filter(n -> n.endsWith(".class")).forEach(names::add);
        }
        return names;
    }

    static String git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IOException("git " + String.join(" ", args) + " failed: " + out.strip());
        return out;
    }

    static void deleteTree(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                p.toFile().setWritable(true);
                Files.deleteIfExists(p);
            }
        }
    }
}
