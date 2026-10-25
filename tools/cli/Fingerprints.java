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
        model = withoutGeneratedSources(model);
        Set<Path> inputs = new TreeSet<>(Port.sources(project)); inputs.add(init);
        inputs.addAll(sourceFolderFiles(project));
        Set<Path> outputs = new HashSet<>();
        for (Object p : Json.array(model.get("projects"))) for (Object s : Json.array(Json.object(p).get("sourceSets")))
            for (String path : ProjectModel.strings(Json.object(s).get("output"))) outputs.add(Path.of(path));
        for (Object item : Json.array(model.get("projects"))) {
            var p = Json.object(item); inputs.add(Path.of(p.get("buildFile").toString()));
            for (Object set : Json.array(p.get("sourceSets"))) for (String path : ProjectModel.strings(Json.object(set).get("sources"))) inputs.add(Path.of(path));
            for (Object compile : Json.array(p.get("compile"))) {
                var task = Json.object(compile);
                for (String path : ProjectModel.strings(task.get("sources"))) inputs.add(Path.of(path));
                for (String path : ProjectModel.strings(task.get("classpath"))) if (!outputs.contains(Path.of(path))) inputs.add(Path.of(path));
            }
        }
        return bytes((files(inputs) + Json.write(model)).getBytes(StandardCharsets.UTF_8));
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
