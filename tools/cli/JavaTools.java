import java.io.*;
import java.nio.file.*;
import java.util.*;

/** One compiled copy of the existing mapping and mixin tools, shared by all projects. */
final class JavaTools {
    final Build build; final Runtimes.Jdk jdk; final Path classes;
    JavaTools(Build build) throws Exception {
        this.build = build; jdk = Runtimes.ensureAtLeast(build.harness, 25);
        List<Path> sources = List.of(build.harness.resolve("tools/VersionMap.java"), build.harness.resolve("tools/MixinAudit.java"));
        classes = build.harness.resolve(".modaccel/portable-tools/" + Fingerprints.files(sources));
        Path ready = classes.resolve("ready");
        if (!Files.exists(ready)) {
            Files.createDirectories(classes); var command = new ArrayList<>(List.of(jdk.home().resolve("bin/" + (Runtimes.WINDOWS ? "javac.exe" : "javac")).toString(),
                    "--release", "25", "-encoding", "UTF-8", "-d", classes.toString())); sources.forEach(p -> command.add(p.toString()));
            var result = Run.command(command, build.project.root, Map.of(), build.report.log("tools-compile"), 120); build.report.stage("tools-compile", result, Map.of());
            if (result.exit() != 0) throw new IOException("Mapping tools could not compile. " + Run.tail(result.log()));
            Files.writeString(ready, "ok");
        }
    }
    Run.Result run(String tool, List<String> arguments, String stage) throws Exception {
        List<String> command = new ArrayList<>(List.of(jdk.java().toString(), "-cp", classes.toString(), tool)); command.addAll(arguments);
        var result = Run.command(command, build.project.root, Map.of(), build.report.log(stage), 300); build.report.stage(stage, result, Map.of()); return result;
    }
    Run.Result audit(Map<String, Object> model) throws Exception {
        Set<String> classpath = new LinkedHashSet<>(); Set<String> roots = new LinkedHashSet<>();
        for (Object item : Json.array(model.get("projects"))) {
            var p = Json.object(item);
            for (Object c : Json.array(p.get("compile"))) classpath.addAll(ProjectModel.strings(Json.object(c).get("classpath")));
            for (Object s : Json.array(p.get("sourceSets"))) roots.addAll(ProjectModel.strings(Json.object(s).get("java")));
        }
        Path file = build.report.directory.resolve("classpath.txt"); Files.createDirectories(file.getParent()); Files.writeString(file, String.join(File.pathSeparator, classpath));
        List<String> args = new ArrayList<>(List.of("--root", build.project.root.toString(), "--classpath", file.toString()));
        for (String root : roots) if (Files.isDirectory(Path.of(root))) args.addAll(List.of("--src", root));
        return run("MixinAudit", args, "mixin-audit");
    }
}
