import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

final class Fingerprints {
    static String bytes(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static String files(Collection<Path> inputs) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        for (Path path : new TreeSet<>(inputs)) {
            hash.update(path.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
            if (Files.isDirectory(path)) try (var walk = Files.walk(path)) {
                for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) add(hash, file);
            } else if (Files.isRegularFile(path)) add(hash, path);
            else hash.update((byte)0);
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    static void add(MessageDigest hash, Path file) throws IOException {
        hash.update(file.toString().getBytes(StandardCharsets.UTF_8));
        try (InputStream input = Files.newInputStream(file)) { byte[] buffer = new byte[65536]; int n; while ((n = input.read(buffer)) >= 0) hash.update(buffer, 0, n); }
    }
    static String build(Map<String, Object> model, ProjectModel project, Path init) throws Exception {
        Inputs inputs = inputs(model, project, init);
        return bytes((files(inputs.content()) + String.join("|", inputs.byName().stream().map(Path::toString).toList()) + Json.write(withoutGeneratedSources(model))).getBytes(StandardCharsets.UTF_8));
    }
    /**
     * content: files hashed byte for byte. byName: classpath jars Gradle makes inside the project (its .gradle folder,
     * a build folder) from the build files already counted, such as NeoGradle's ng_dummy_ng repository or Loom's
     * remapped game; the launch's own Gradle run rewrites them, so their bytes would make every first launch
     * look like a changed project. Their names still count.
     */
    record Inputs(Set<Path> content, Set<Path> byName) { }
    /** Each input file's own hash, so a changed fingerprint can name the files that changed. */
    static Map<String, String> each(Map<String, Object> model, ProjectModel project, Path init) throws Exception {
        Map<String, String> out = new TreeMap<>();
        Inputs inputs = inputs(model, project, init);
        for (Path path : inputs.byName()) out.put(path.toString(), "by name");
        for (Path path : inputs.content()) {
            if (Files.isDirectory(path)) try (var walk = Files.walk(path)) { for (Path file : walk.filter(Files::isRegularFile).toList()) out.put(file.toString(), files(List.of(file))); }
            else out.put(path.toString(), Files.isRegularFile(path) ? files(List.of(path)) : "missing");
        }
        return out;
    }
    /** The files whose hash differs between two each() maps, appeared or vanished included. */
    static List<String> changed(Map<String, String> before, Map<String, String> after) {
        Set<String> keys = new TreeSet<>(before.keySet()); keys.addAll(after.keySet());
        List<String> out = new ArrayList<>();
        for (String key : keys) if (!Objects.equals(before.get(key), after.get(key)))
            out.add(key + (before.get(key) == null || "missing".equals(before.get(key)) ? " (appeared)" : after.get(key) == null || "missing".equals(after.get(key)) ? " (vanished)" : " (changed)"));
        return out;
    }
    static Inputs inputs(Map<String, Object> model, ProjectModel project, Path init) throws Exception {
        model = withoutGeneratedSources(model);
        Set<Path> inputs = new TreeSet<>(Port.sources(project)); inputs.add(init);
        inputs.addAll(sourceFolderFiles(project));
        Set<Path> outputs = new HashSet<>(), byName = new TreeSet<>();
        List<Path> generatedRoots = new ArrayList<>(List.of(project.root.resolve(".gradle").toAbsolutePath().normalize()));
        for (Object p : Json.array(model.get("projects"))) generatedRoots.add(Path.of(Json.text(Json.object(p), "directory", project.root.toString()), "build").toAbsolutePath().normalize());
        for (Object p : Json.array(model.get("projects"))) for (Object s : Json.array(Json.object(p).get("sourceSets")))
            for (String path : ProjectModel.strings(Json.object(s).get("output"))) outputs.add(Path.of(path));
        for (Object item : Json.array(model.get("projects"))) {
            var p = Json.object(item); inputs.add(Path.of(p.get("buildFile").toString()));
            for (Object set : Json.array(p.get("sourceSets"))) for (String path : ProjectModel.strings(Json.object(set).get("sources"))) inputs.add(Path.of(path));
            for (Object compile : Json.array(p.get("compile"))) {
                var task = Json.object(compile);
                for (String path : ProjectModel.strings(task.get("sources"))) inputs.add(Path.of(path));
                for (String path : ProjectModel.strings(task.get("classpath"))) {
                    Path entry = Path.of(path);
                    if (outputs.contains(entry)) continue;
                    if (generatedRoots.stream().anyMatch(entry.toAbsolutePath().normalize()::startsWith)) byName.add(entry); else inputs.add(entry);
                }
            }
        }
        return new Inputs(inputs, byName);
    }
    /**
     * Every file under a src folder, whatever its extension. Generated sources are left out of the
     * model below, so the files they are generated from must count instead: Blossom templates
     * (src/main/java-templates/*.java.peb), data files a build turns into code, .proto or grammar files.
     * Docs and other files outside src still don't make a running game stale.
     */
    static List<Path> sourceFolderFiles(ProjectModel project) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(project.root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attributes) {
                return !dir.equals(project.root) && (ProjectModel.IGNORED.contains(dir.getFileName().toString()) || Files.exists(dir.resolve(".git"))) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attributes) {
                Path relative = project.root.relativize(file);
                boolean underSrc = false;
                for (int i = 0; i < relative.getNameCount() - 1; i++) if (relative.getName(i).toString().equals("src")) { underSrc = true; break; }
                if (underSrc && attributes.isRegularFile()) files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }
    /**
     * The model without the sources a build generates under its build directory (Stonecutter's
     * per-version copies, annotation processor output). They are made from the inputs counted
     * anyway, and the launch's own Gradle run writes them: counted, a fresh checkout's first
     * client refuses every check after it as a different build.
     */
    static Map<String, Object> withoutGeneratedSources(Map<String, Object> model) {
        Map<String, Object> copy = Json.object(Json.parse(Json.write(model)));
        for (Object item : Json.array(copy.get("projects"))) {
            var p = Json.object(item);
            Path generated = Path.of(Json.text(p, "directory", ""), "build");
            List<Object> holders = new ArrayList<>(Json.array(p.get("sourceSets"))); holders.addAll(Json.array(p.get("compile")));
            for (Object holder : holders) {
                var sources = Json.object(holder);
                if (sources.containsKey("sources"))
                    sources.put("sources", ProjectModel.strings(sources.get("sources")).stream().filter(path -> !Path.of(path).startsWith(generated)).toList());
            }
        }
        return copy;
    }
}
