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
    private static final String BUILD_ID = "308468310ba77e2cbd62061a78f374a61981fe6d1ffd1ae243554d827b46f4d0";
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
    private static final ThreadLocal<TimingStack> timing = ThreadLocal.withInitial(TimingStack::new);
    private static final java.util.concurrent.atomic.AtomicLong sequence = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong completed = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong depthDropped = new java.util.concurrent.atomic.AtomicLong();
    private static final long TIMING_LIMIT = 100_000;
    private static final long SAMPLE_MS = 10;
    /** Game base types in Mojang, Yarn and intermediary names; first match wins, so renderers precede entities. */
    private static final String[][] KINDS = {
        {"block entity renderer", "net.minecraft.client.renderer.blockentity.BlockEntityRenderer", "net.minecraft.client.render.block.entity.BlockEntityRenderer", "net.minecraft.class_827"},
        {"entity renderer", "net.minecraft.client.renderer.entity.EntityRenderer", "net.minecraft.client.render.entity.EntityRenderer", "net.minecraft.class_897"},
        {"block entity", "net.minecraft.world.level.block.entity.BlockEntity", "net.minecraft.block.entity.BlockEntity", "net.minecraft.class_2586"},
        {"block", "net.minecraft.world.level.block.Block", "net.minecraft.block.Block", "net.minecraft.class_2248"},
        {"entity", "net.minecraft.world.entity.Entity", "net.minecraft.entity.Entity", "net.minecraft.class_1297"},
        {"AI goal", "net.minecraft.world.entity.ai.goal.Goal", "net.minecraft.entity.ai.goal.Goal", "net.minecraft.class_1352"},
        {"item", "net.minecraft.world.item.Item", "net.minecraft.item.Item", "net.minecraft.class_1792"},
        {"screen", "net.minecraft.client.gui.screens.Screen", "net.minecraft.client.gui.screen.Screen", "net.minecraft.class_437"},
        {"menu", "net.minecraft.world.inventory.AbstractContainerMenu", "net.minecraft.screen.ScreenHandler", "net.minecraft.class_1703"},
        {"saved data", "net.minecraft.world.level.saveddata.SavedData", "net.minecraft.world.PersistentState", "net.minecraft.class_18"},
    };

    private static final class TimingStack {
        long generation;
        int overflow;
        final Deque<Span> spans = new ArrayDeque<>();
        // This thread's totals for each label, kept in totals as label@server, label@render or label@other.
        final Map<String, java.util.concurrent.atomic.LongAdder[]> threadSums = new java.util.HashMap<>();
        final String thread = threadKind(Thread.currentThread().getName());
    }

    /** The game thread a timed call ran on, for per-thread totals: a block entity can tick on the server and the client. */
    static String threadKind(String name) {
        if (name.equals("Server thread")) return "server";
        if (name.equals("Render thread")) return "render";
        return "other";
    }

    private static final class Span {
        final MethodTiming event = new MethodTiming();
        long started, children;
    }

    @Name("modaccel.MethodTiming") @jdk.jfr.Label("Selected function wall time") @StackTrace(false)
    public static final class MethodTiming extends Event {
        public String label;
        public String caller;
        public long callId;
        public long parentId;
        @Timespan(Timespan.NANOSECONDS) public long inclusiveNanos;
        @Timespan(Timespan.NANOSECONDS) public long selfNanos;
        public boolean exceptional;
    }

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

    /** Complete per-label totals; unlike per-call events these are never dropped at the event limit. */
    @Name("modaccel.TimingTotals") @jdk.jfr.Label("Selected function totals") @StackTrace(false)
    public static final class TimingTotals extends Event {
        public String label;
        public long calls;
        @Timespan(Timespan.NANOSECONDS) public long inclusiveNanos;
        @Timespan(Timespan.NANOSECONDS) public long selfNanos;
    }

    private static final Map<String, java.util.concurrent.atomic.LongAdder[]> totals = new ConcurrentHashMap<>();

    /**
     * A thread's CPU time over the capture window, from the operating system's per-thread accounting (ThreadMXBean):
     * time on a processor only, unlike execution samples, which also count a preempted thread that is in Java code.
     * {@code whole} is false for a thread that ended during the window (its last second may be missing).
     */
    @Name("modaccel.ThreadCpuTime") @jdk.jfr.Label("Thread CPU time in the window") @StackTrace(false)
    public static final class ThreadCpuTime extends Event {
        public String thread;
        public long threadId;
        @Timespan(Timespan.NANOSECONDS) public long cpuNanos;
        @Timespan(Timespan.NANOSECONDS) public long userNanos;
        public boolean whole;
    }

    /** Per-thread CPU times: at the window's start, as last seen, and the thread's name. */
    private static final class ThreadClock {
        final java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        final Map<Long, long[]> first = new HashMap<>(), last = new HashMap<>();
        final Map<Long, String> names = new HashMap<>();
        final boolean supported;

        ThreadClock() {
            boolean ok = bean.isThreadCpuTimeSupported();
            if (ok && !bean.isThreadCpuTimeEnabled()) {
                try { bean.setThreadCpuTimeEnabled(true); }
                catch (Throwable error) { ok = false; }
            }
            supported = ok;
        }

        /** Reads every live thread; threads first seen after the start began during the window and count from zero. */
        Map<Long, long[]> read(boolean start) {
            Map<Long, long[]> now = new HashMap<>();
            if (!supported) return now;
            for (long id : bean.getAllThreadIds()) {
                long cpu = bean.getThreadCpuTime(id), user = bean.getThreadUserTime(id);
                if (cpu < 0) continue;
                long[] value = {cpu, Math.max(0, user)};
                now.put(id, value);
                if (start) first.put(id, value);
                else first.putIfAbsent(id, new long[]{0, 0});
                last.put(id, value);
                if (!names.containsKey(id)) {
                    java.lang.management.ThreadInfo info = bean.getThreadInfo(id, 0);
                    if (info != null) names.put(id, info.getThreadName());
                }
            }
            return now;
        }

        void commit() {
            Map<Long, long[]> end = read(false);
            for (var entry : last.entrySet()) {
                long id = entry.getKey();
                long[] from = first.get(id), to = entry.getValue();
                if (from == null || !names.containsKey(id)) continue;
                ThreadCpuTime event = new ThreadCpuTime();
                event.thread = names.get(id);
                event.threadId = id;
                event.cpuNanos = Math.max(0, to[0] - from[0]);
                event.userNanos = Math.max(0, to[1] - from[1]);
                event.whole = end.containsKey(id);
                event.commit();
            }
        }
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
            if (fields.length == 4) fields = new String[]{fields[0], fields[1], fields[2], fields[3], "count"};
            if (fields.length != 5 || !Set.of("count", "timing").contains(fields[4])) throw new IllegalArgumentException("Invalid probe selector");
            selected.add(fields);
        }
        if (selected.size() > 32) throw new IllegalArgumentException("At most 32 probes");
        probes = List.copyOf(selected);
        calls.clear();
        totals.clear();
        sequence.set(0);
        completed.set(0);
        depthDropped.set(0);
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
        if (kind.startsWith("enter.") || kind.startsWith("exit.") || kind.startsWith("throw.")) {
            timed(kind);
            return;
        }
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

    private static void timed(String kind) {
        TimingStack stack = timing.get();
        if (stack.generation != generation) {
            stack.spans.clear();
            stack.overflow = 0;
            stack.threadSums.clear();
            stack.generation = generation;
        }
        int dot = kind.indexOf('.');
        String label = kind.substring(dot + 1);
        if (kind.startsWith("enter.")) {
            if (stack.spans.size() >= 128 || stack.overflow > 0) {
                stack.overflow++;
                depthDropped.incrementAndGet();
                return;
            }
            Span span = new Span();
            span.event.label = label;
            span.event.callId = sequence.incrementAndGet();
            Span parent = stack.spans.peek();
            span.event.parentId = parent == null ? 0 : parent.event.callId;
            span.event.caller = parent == null ? "[outside timed scope]" : parent.event.label;
            span.event.begin();
            span.started = System.nanoTime();
            stack.spans.push(span);
            return;
        }
        if (stack.overflow > 0) {
            stack.overflow--;
            return;
        }
        Span span = stack.spans.peek();
        // Calls begun before the capture are not timed; never invent a partial duration.
        if (span == null) return;
        if (!span.event.label.equals(label)) {
            stack.spans.clear();
            if (errors.size() < 16) errors.add("Unbalanced timing scope " + label);
            return;
        }
        long elapsed = System.nanoTime() - span.started;
        stack.spans.pop();
        Span parent = stack.spans.peek();
        if (parent != null) parent.children += elapsed;
        span.event.inclusiveNanos = elapsed;
        span.event.selfNanos = Math.max(0, elapsed - span.children);
        span.event.exceptional = kind.startsWith("throw.");
        span.event.end();
        java.util.concurrent.atomic.LongAdder[] sum = totals.computeIfAbsent(label, k -> new java.util.concurrent.atomic.LongAdder[]{
            new java.util.concurrent.atomic.LongAdder(), new java.util.concurrent.atomic.LongAdder(), new java.util.concurrent.atomic.LongAdder()});
        sum[0].increment();
        sum[1].add(elapsed);
        sum[2].add(span.event.selfNanos);
        java.util.concurrent.atomic.LongAdder[] onThread = stack.threadSums.computeIfAbsent(label, k -> totals.computeIfAbsent(k + "@" + stack.thread,
            key -> new java.util.concurrent.atomic.LongAdder[]{
                new java.util.concurrent.atomic.LongAdder(), new java.util.concurrent.atomic.LongAdder(), new java.util.concurrent.atomic.LongAdder()}));
        onThread[0].increment();
        onThread[1].add(elapsed);
        onThread[2].add(span.event.selfNanos);
        if (completed.incrementAndGet() <= TIMING_LIMIT) span.event.commit();
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
                    boolean deep = probes.stream().anyMatch(p -> p[4].equals("timing"));
                    ClassWriter writer = new ClassWriter(reader, deep ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS) {
                        protected ClassLoader getClassLoader() { return loader == null ? ClassLoader.getSystemClassLoader() : loader; }
                    };
                    boolean[] edited = {false};
                    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                        public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                            MethodVisitor mv = super.visitMethod(access, method, desc, signature, exceptions);
                            String kind = hook(name, method, desc);
                            List<String> counters = new ArrayList<>();
                            String timer = null;
                            for (String[] probe : probes) {
                                if (probe[0].equals(name) && probe[1].equals(method) && probe[2].equals(desc)) {
                                    counters.add("call." + probe[3]);
                                    if (probe[4].equals("timing")) timer = probe[3];
                                }
                            }
                            if (deep && kind != null && timer == null) timer = "@" + kind;
                            if (kind == null && counters.isEmpty()) return mv;
                            if ((access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0) return mv;
                            for (String counter : counters) calls.putIfAbsent(counter, new java.util.concurrent.atomic.LongAdder());
                            edited[0] = true;
                            hooks.add(name + "." + method + desc + "=" + kind + counters);
                            final String timedLabel = timer;
                            return new MethodVisitor(Opcodes.ASM9, mv) {
                                final org.objectweb.asm.Label begin = new org.objectweb.asm.Label();
                                final org.objectweb.asm.Label end = new org.objectweb.asm.Label();
                                final org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
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
                                    if (timedLabel != null) {
                                        emit("enter." + timedLabel);
                                        super.visitLabel(begin);
                                    }
                                }
                                public void visitInsn(int opcode) {
                                    if (timedLabel != null && opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) emit("exit." + timedLabel);
                                    if (opcode == Opcodes.RETURN && kind != null) emit(kind);
                                    super.visitInsn(opcode);
                                }
                                public void visitMaxs(int maxStack, int maxLocals) {
                                    if (timedLabel != null) {
                                        super.visitLabel(end);
                                        // Last handler: existing catches run first; only propagated exceptions leave the scope.
                                        super.visitTryCatchBlock(begin, end, handler, null);
                                        super.visitLabel(handler);
                                        emit("throw." + timedLabel);
                                        super.visitInsn(Opcodes.ATHROW);
                                    }
                                    super.visitMaxs(maxStack, maxLocals);
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
            recording.enable(TimingTotals.class).withoutStackTrace();
            recording.enable(ThreadCpuTime.class).withoutStackTrace();
            recording.enable(MethodTiming.class).withoutStackTrace().withThreshold(Duration.ZERO);
            // Attribution converts samples to time with this period; record it rather than assume a configuration.
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(SAMPLE_MS));
            recording.enable("jdk.ThreadCPULoad").withPeriod(Duration.ofSeconds(1));
            status.setProperty("samplePeriodMs", Long.toString(SAMPLE_MS));
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
            ThreadClock clock = new ThreadClock();
            clock.read(true);
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
                clock.read(false);
                long remaining = seconds * 1_000_000_000L - (System.nanoTime() - begin);
                if (remaining <= 0) break;
                Thread.sleep(Math.min(1000, Math.max(1, remaining / 1_000_000)));
            } while (true);
            measuring = false;
            clock.commit();
            status.setProperty("elapsedSeconds", Double.toString((System.nanoTime() - begin) / 1e9));
            for (var entry : calls.entrySet()) {
                ProbeCalls event = new ProbeCalls();
                event.label = entry.getKey().substring(5);
                event.calls = entry.getValue().sum();
                event.commit();
            }
            for (var entry : totals.entrySet()) {
                TimingTotals event = new TimingTotals();
                event.label = entry.getKey();
                event.calls = entry.getValue()[0].sum();
                event.inclusiveNanos = entry.getValue()[1].sum();
                event.selfNanos = entry.getValue()[2].sum();
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
            save(out.resolve("features.properties"), features(inst));
            if (request.getProperty("heap", "on").equals("on")) heapHistogram(out, status);
            Properties env = new Properties();
            for (String key : List.of("java.version", "java.vendor", "os.name", "os.version", "os.arch", "user.dir", "modaccel.background")) {
                env.setProperty(key, System.getProperty(key, ""));
            }
            env.setProperty("processors", Integer.toString(Runtime.getRuntime().availableProcessors()));
            env.setProperty("c1Only", Boolean.toString(ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:TieredStopAtLevel=1")));
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
            status.setProperty("timingProbes", String.join("\n", probes.stream().filter(p -> p[4].equals("timing"))
                .map(p -> p[3] + "=" + p[0].replace('/', '.') + "." + p[1] + p[2]).toList()));
            status.setProperty("timingCompleted", Long.toString(completed.get()));
            status.setProperty("timingDropped", Long.toString(Math.max(0, completed.get() - TIMING_LIMIT)));
            status.setProperty("timingDepthDropped", Long.toString(depthDropped.get()));
            status.setProperty("hooks", String.join("\n", new TreeSet<>(hooks)));
            status.setProperty("errors", String.join("\n", errors));
            try { save(out.resolve("status.properties"), status); }
            catch (IOException error) { System.err.println("Mod Accel capture status: " + error); }
            synchronized (PerfAgent.class) { busy = false; }
        }
    }

    /** Feature kind of each loaded non-game class, from its own superclass and interface chain; no class is loaded. */
    private static Properties features(Instrumentation inst) {
        Properties kinds = new Properties();
        for (Class<?> cls : inst.getAllLoadedClasses()) {
            String name = cls.getName();
            if (cls.isArray() || cls.isPrimitive() || name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.")
                || name.startsWith("com.sun.") || name.startsWith("net.minecraft.") || name.startsWith("com.mojang.")) continue;
            try {
                Set<String> chain = new HashSet<>();
                Deque<Class<?>> pending = new ArrayDeque<>();
                pending.add(cls);
                while (!pending.isEmpty() && chain.size() < 256) {
                    Class<?> next = pending.poll();
                    if (next == null || !chain.add(next.getName())) continue;
                    if (next.getSuperclass() != null) pending.add(next.getSuperclass());
                    pending.addAll(Arrays.asList(next.getInterfaces()));
                }
                for (String[] kind : KINDS) {
                    boolean match = false;
                    for (int i = 1; i < kind.length; i++) match |= chain.contains(kind[i]);
                    if (match) {
                        kinds.setProperty(name, kind[0]);
                        break;
                    }
                }
            } catch (Throwable ignored) { }
        }
        return kinds;
    }

    /** Live shallow bytes per class after a full GC, taken once the recording has stopped. */
    private static void heapHistogram(Path out, Properties status) {
        try {
            javax.management.ObjectName name = new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand");
            long started = System.nanoTime();
            Object text = ManagementFactory.getPlatformMBeanServer().invoke(name, "gcClassHistogram",
                new Object[]{new String[0]}, new String[]{String[].class.getName()});
            Files.writeString(out.resolve("heap-histogram.txt"), String.valueOf(text));
            status.setProperty("heapHistogramMs", Double.toString((System.nanoTime() - started) / 1e6));
        } catch (Throwable error) {
            status.setProperty("heapHistogramError", error.toString());
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
