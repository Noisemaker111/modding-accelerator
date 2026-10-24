import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import javax.tools.ToolProvider;

/** Source-checkout bootstrap only; packaged installations run the precompiled jar. */
public final class Bootstrap {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Bootstrap needs the harness directory");
        Path harness = Path.of(args[0]).toAbsolutePath().normalize();
        Path source = harness.resolve("tools/cli"), cache = harness.resolve(".modaccel/cli");
        Files.createDirectories(cache);
        List<Path> files;
        try (var stream = Files.list(source)) { files = stream.filter(p -> p.toString().endsWith(".java") && !p.getFileName().toString().equals("Bootstrap.java")).sorted().toList(); }
        var digest = MessageDigest.getInstance("SHA-256");
        for (Path file : files) { digest.update(file.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); digest.update(Files.readAllBytes(file)); }
        String modified = HexFormat.of().formatHex(digest.digest());
        Path output = cache.resolve(modified);
        Path stamp = output.resolve("build.stamp");
        try (var channel = java.nio.channels.FileChannel.open(cache.resolve("build.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
        if (!Files.exists(stamp)) {
            Files.createDirectories(output);
            var compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) throw new IllegalStateException("A source checkout needs JDK 17 or newer. A packaged modaccel.jar also runs on a JRE.");
            List<String> options = new ArrayList<>(List.of("--release", "17", "-encoding", "UTF-8", "-d", output.toString()));
            files.forEach(p -> options.add(p.toString()));
            if (compiler.run(null, System.err, System.err, options.toArray(String[]::new)) != 0) System.exit(2);
            Files.writeString(stamp, modified);
        }
        }
        System.setProperty("modaccel.home", harness.toString());
        try (var loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            loader.loadClass("Modaccel").getMethod("main", String[].class).invoke(null, (Object)Arrays.copyOfRange(args, 1, args.length));
        }
    }
}
