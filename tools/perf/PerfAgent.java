package dev.modaccel.perf;

import java.io.*;
import java.lang.instrument.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import jdk.jfr.*;
import org.objectweb.asm.*;

/** Bounded observation only: no world edits, game settings changes or control socket. */
public final class PerfAgent {
    private static final String BUILD_ID = "@BUILD_ID@";
    private static boolean busy;
    private static volatile boolean official1211;
    private static volatile boolean measuring;
    private static final ThreadLocal<Long> frame = new ThreadLocal<>();
    private static final ThreadLocal<Long> frameGeneration = new ThreadLocal<>();
    private static volatile long generation;
    private static final ThreadLocal<Long> tick = new ThreadLocal<>();
    private static final String KEY = "dev.modaccel.perf.pulse.v1";
    private static final List<String> errors = new CopyOnWriteArrayList<>();
    private static volatile List<String[]> probes = List.of();
    private static final Map<String, java.util.concurrent.atomic.LongAdder> calls = new ConcurrentHashMap<>();
    private static final Set<String> hooks = ConcurrentHashMap.newKeySet();

    @Name("modaccel.Pulse") @jdk.jfr.Label("Mod Accel timing") @StackTrace(false)
    public static final class Pulse extends Event {
        public String kind;
        @Timespan(Timespan.NANOSECONDS) public long nanos;
    }

    @Name("modaccel.Process") @jdk.jfr.Label("Mod Accel process") @StackTrace(false)
    public static final class ProcessSample extends Event {
        public long heapUsed;
        public long heapCommitted;
        public long processCpuNanos;
        public long physicalMemory;
    }

    @Name("modaccel.ProbeCalls") @jdk.jfr.Label("Selected method calls") @StackTrace(false)
    public static final class ProbeCalls extends Event {
        public String label;
        public long calls;
    }

    public static void premain(String args, Instrumentation inst) throws Exception {
        agentmain(args, inst);
    }

    public static synchronized void agentmain(String args, Instrumentation inst) throws Exception {
        if (busy) throw new IllegalStateException("A Mod Accel capture is already running");
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(Path.of(args))) { p.load(in); }
        if (!BUILD_ID.equals(p.getProperty("agentBuild"))) {
            throw new IllegalStateException("A different profiler build is loaded or requested. Restart the game and regenerate the agent arguments for this build.");
        }
        Path out = Path.of(p.getProperty("output")).toAbsolutePath();
        int seconds = Integer.parseInt(p.getProperty("seconds", "30"));
        int warmup = Integer.parseInt(p.getProperty("warmup", "5"));
        if (seconds < 1 || seconds > 3600 || warmup < 0 || warmup > 600) throw new IllegalArgumentException("Invalid duration");
        Files.createDirectories(out);
        if (Files.exists(out.resolve("capture.jfr"))) throw new IOException("Capture already exists");
        List<String[]> selected = new ArrayList<>();
        for (String key : p.stringPropertyNames()) if (key.startsWith("probe.")) {
            String[] fields = p.getProperty(key).split("\\|", -1);
            if (fields.length != 4) throw new IllegalArgumentException("Invalid probe selector");
            selected.add(fields);
        }
        if (selected.size() > 32) throw new IllegalArgumentException("At most 32 probes");
        probes = List.copyOf(selected);
        calls.clear();
        official1211 = false;
        busy = true;
        errors.clear();
        hooks.clear();
        Thread worker = new Thread(() -> capture(inst, out, seconds, warmup, p), "modaccel-perf-capture");
        worker.setDaemon(true);
        worker.start();
    }

    private static void pulse(String kind) {
        if (!measuring) return;
        if (kind.startsWith("call.")) {
            java.util.concurrent.atomic.LongAdder counter = calls.get(kind);
            if (counter != null) counter.increment();
            return;
        }
        long now = System.nanoTime();
        if (kind.equals("tick-start")) {
            tick.set(now);
            return;
        }
        ThreadLocal<Long> clock = kind.equals("frame") ? frame : tick;
        Long previous = clock.get();
        if (kind.equals("frame") && !Long.valueOf(generation).equals(frameGeneration.get())) {
            previous = null;
            frameGeneration.set(generation);
        }
        clock.set(kind.equals("frame") ? now : null);
        if (previous != null && now > previous) {
            Pulse event = new Pulse();
            event.kind = kind;
            event.nanos = now - previous;
            event.commit();
        }
    }

    private static void capture(Instrumentation inst, Path out, int seconds, int warmup, Properties request) {
        Set<Class<?>> changed = ConcurrentHashMap.newKeySet();
        ClassFileTransformer transformer = new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain pd, byte[] bytes) {
                if (!target(name)) return null;
                if ((name.equals("net/minecraft/server/MinecraftServer") || name.equals("fgo")) && loader != null) {
                    try (InputStream version = loader.getResourceAsStream("version.json")) {
                        if (version != null) {
                            String json = new String(version.readNBytes(65536), java.nio.charset.StandardCharsets.UTF_8);
                            official1211 = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"1\\.21\\.1\"").matcher(json).find();
                        }
                    } catch (IOException ignored) { }
                }
                try {
                    ClassReader reader = new ClassReader(bytes);
                    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                    boolean[] edited = {false};
                    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                        public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                            MethodVisitor mv = super.visitMethod(access, method, desc, signature, exceptions);
                            String kind = hook(name, method, desc);
                            List<String> counters = new ArrayList<>();
                            for (String[] probe : probes) {
                                if (probe[0].equals(name) && probe[1].equals(method) && probe[2].equals(desc)) counters.add("call." + probe[3]);
                            }
                            if (kind == null && counters.isEmpty()) return mv;
                            if ((access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0) return mv;
                            for (String counter : counters) calls.putIfAbsent(counter, new java.util.concurrent.atomic.LongAdder());
                            edited[0] = true;
                            hooks.add(name + "." + method + desc + "=" + kind + counters);
                            return new MethodVisitor(Opcodes.ASM9, mv) {
                                private void emit(String label) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
                                    super.visitLdcInsn(KEY);
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                    super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer");
                                    super.visitLdcInsn(label);
                                    super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", "(Ljava/lang/Object;)V", true);
                                }
                                public void visitCode() {
                                    super.visitCode();
                                    if ("tick".equals(kind)) emit("tick-start");
                                    for (String counter : counters) emit(counter);
                                }
                                public void visitInsn(int opcode) {
                                    if (opcode == Opcodes.RETURN && kind != null) emit(kind);
                                    super.visitInsn(opcode);
                                }
                            };
                        }
                    }, 0);
                    if (edited[0]) {
                        if (type != null) changed.add(type);
                        return writer.toByteArray();
                    }
                } catch (Throwable error) { errors.add(name + ": " + error); }
                return null;
            }
        };
        boolean installed = false;
        Properties status = new Properties();
        try (Recording recording = new Recording(Configuration.getConfiguration("profile"))) {
            recording.setName("Mod Accel " + out.getFileName());
            recording.setMaxSize(512L * 1024 * 1024);
            recording.enable(Pulse.class).withoutStackTrace();
            recording.enable(ProcessSample.class).withoutStackTrace();
            recording.enable(ProbeCalls.class).withoutStackTrace();
            if (!request.getProperty("hooks", "on").equals("off")) {
                System.getProperties().put(KEY, (Consumer<String>) kind -> {
                    try { pulse(kind); }
                    catch (Throwable error) { if (errors.size() < 16) errors.add("timing callback: " + error); }
                });
                inst.addTransformer(transformer, true);
                installed = true;
                for (Class<?> cls : inst.getAllLoadedClasses()) {
                    if (target(cls.getName().replace('.', '/')) && inst.isModifiableClass(cls)) {
                        try { inst.retransformClasses(cls); }
                        catch (Throwable error) { errors.add(cls.getName() + ": " + error); }
                    }
                }
            }
            status.setProperty("state", "warmup");
            save(out.resolve("status.properties"), status);
            Thread.sleep(warmup * 1000L);
            recording.start();
            long begin = System.nanoTime();
            generation++;
            measuring = true;
            status.setProperty("state", "recording");
            save(out.resolve("status.properties"), status);
            do {
                ProcessSample sample = new ProcessSample();
                var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                sample.heapUsed = memory.getUsed();
                sample.heapCommitted = memory.getCommitted();
                if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                    sample.processCpuNanos = os.getProcessCpuTime();
                    sample.physicalMemory = os.getTotalMemorySize();
                }
                sample.commit();
                long remaining = seconds * 1_000_000_000L - (System.nanoTime() - begin);
                if (remaining <= 0) break;
                Thread.sleep(Math.min(1000, Math.max(1, remaining / 1_000_000)));
            } while (true);
            measuring = false;
            status.setProperty("elapsedSeconds", Double.toString((System.nanoTime() - begin) / 1e9));
            for (var entry : calls.entrySet()) {
                ProbeCalls event = new ProbeCalls();
                event.label = entry.getKey().substring(5);
                event.calls = entry.getValue().sum();
                event.commit();
            }
            recording.stop();
            recording.dump(out.resolve("capture.jfr"));
            Properties classes = new Properties();
            for (Class<?> cls : inst.getAllLoadedClasses()) {
                try {
                    if (cls.getProtectionDomain().getCodeSource() != null) {
                        String source = cls.getProtectionDomain().getCodeSource().getLocation().toString();
                        String previous = classes.getProperty(cls.getName());
                        classes.setProperty(cls.getName(), previous == null || previous.equals(source) ? source : "ambiguous");
                    }
                } catch (Throwable ignored) { }
            }
            save(out.resolve("classes.properties"), classes);
            Properties env = new Properties();
            for (String key : List.of("java.version", "java.vendor", "os.name", "os.version", "os.arch", "user.dir", "modaccel.background")) {
                env.setProperty(key, System.getProperty(key, ""));
            }
            env.setProperty("processors", Integer.toString(Runtime.getRuntime().availableProcessors()));
            env.setProperty("maxHeap", Long.toString(Runtime.getRuntime().maxMemory()));
            save(out.resolve("environment.properties"), env);
            status.setProperty("state", "complete");
        } catch (Throwable error) {
            status.setProperty("state", "failed");
            status.setProperty("error", error.toString());
        } finally {
            measuring = false;
            try {
              if (installed) {
                inst.removeTransformer(transformer);
                for (Class<?> cls : inst.getAllLoadedClasses()) {
                    if (target(cls.getName().replace('.', '/')) && inst.isModifiableClass(cls)) changed.add(cls);
                }
                for (Class<?> cls : changed) {
                    try { inst.retransformClasses(cls); }
                    catch (Throwable error) { errors.add("restore " + cls.getName() + ": " + error); }
                }
              }
            } catch (Throwable error) { errors.add("hook cleanup: " + error); }
            status.setProperty("agentBuild", BUILD_ID);
            status.setProperty("requestedProbes", String.join("\n", probes.stream().map(p -> p[3]).toList()));
            status.setProperty("hooks", String.join("\n", new TreeSet<>(hooks)));
            status.setProperty("errors", String.join("\n", errors));
            try { save(out.resolve("status.properties"), status); }
            catch (IOException error) { System.err.println("Mod Accel capture status: " + error); }
            synchronized (PerfAgent.class) { busy = false; }
        }
    }

    private static boolean target(String name) {
        // This guard runs while arbitrary JDK classes are being defined. No streams, lambdas,
        // temporary collections or iterators: loading their helpers here can cause circularity.
        if (name == null) return false;
        if (name.equals("net/minecraft/client/Minecraft") || name.equals("net/minecraft/client/MinecraftClient") ||
            name.equals("net/minecraft/class_310") || name.equals("net/minecraft/server/MinecraftServer") || name.equals("fgo")) return true;
        for (int i = 0; i < probes.size(); i++) if (probes.get(i)[0].equals(name)) return true;
        return false;
    }

    private static String hook(String owner, String method, String desc) {
        if (owner.equals("net/minecraft/server/MinecraftServer") && desc.equals("(Ljava/util/function/BooleanSupplier;)V") &&
            (Set.of("tickServer", "tick", "method_3748", "m_5705_").contains(method) || (method.equals("a") && official1211))) return "tick";
        if (!owner.equals("net/minecraft/server/MinecraftServer") && desc.equals("(Z)V") &&
            (Set.of("runTick", "render", "method_1523", "m_91383_").contains(method) || (owner.equals("fgo") && method.equals("c") && official1211))) return "frame";
        return null;
    }

    private static void save(Path path, Properties properties) throws IOException {
        Path temp = Files.createTempFile(path.getParent(), "perf-", ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp)) { properties.store(writer, "Mod Accel performance"); }
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
    }
}
