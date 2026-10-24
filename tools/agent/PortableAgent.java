import java.io.*;
import java.lang.instrument.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.objectweb.asm.*;

/** Loader-independent dev-client control. All game operations run after a rendered frame. */
public final class PortableAgent {
    private static final Queue<Runnable> pending = new ConcurrentLinkedQueue<Runnable>();
    private static final String token = System.getProperty("modaccel.token", "");
    private static Object client; private static boolean started;
    private static Object pauseOptions; private static Field pauseField; private static boolean originalPauseOnLostFocus;
    private static String pauseControlError;
    private static Object lastScreen; private static long screenSince;
    private static final Map<String, Object> identity = new LinkedHashMap<String, Object>();
    public static void premain(String args, Instrumentation instrumentation) {
        // Only JDK classes are referenced by transformed game code. Isolated loaders
        // need neither our jar on their mod path nor access to our implementation classes.
        System.getProperties().put("modaccel.frame", new Consumer<Object>() { public void accept(Object game) { frame(game); } });
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
                if (!name.equals("net/minecraft/client/Minecraft") && !name.equals("net/minecraft/client/MinecraftClient")) return null;
                try {
                    ClassReader reader = new ClassReader(bytes); final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                    final int[] hooks = {0};
                    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                            MethodVisitor delegate = super.visitMethod(access, method, desc, signature, exceptions);
                            if ((method.equals("render") || method.equals("runTick")) && desc.equals("(Z)V")) {
                                hooks[0]++;
                                return new MethodVisitor(Opcodes.ASM9, delegate) {
                                    @Override public void visitInsn(int opcode) {
                                        if (opcode == Opcodes.RETURN) {
                                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
                                            super.visitLdcInsn("modaccel.frame");
                                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer");
                                            // Not ALOAD 0: the shipped game's stack frames mark `this` dead before some
                                            // returns, and with no mixin recomputing them (a mod without Fabric API) the
                                            // verifier rejects the class. The singleton getter needs no local.
                                            super.visitMethodInsn(Opcodes.INVOKESTATIC, name, "getInstance", "()L" + name + ";", false);
                                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", "(Ljava/lang/Object;)V", true);
                                        }
                                        super.visitInsn(opcode);
                                    }
                                };
                            }
                            return delegate;
                        }
                    }, 0);
                    if (hooks[0] == 0) { System.err.println("MODACCEL_AGENT_UNSUPPORTED no render hook in " + name); return null; }
                    return writer.toByteArray();
                } catch (Throwable error) { System.err.println("MODACCEL_AGENT_ERROR " + error); return null; }
            }
        });
    }
    public static void frame(Object game) {
        if (!started) {
            client = game; started = true;
            try {
                pauseOptions = field(client, "options");
                pauseField = pauseOptions.getClass().getField("pauseOnLostFocus");
                originalPauseOnLostFocus = pauseField.getBoolean(pauseOptions);
                pauseField.setBoolean(pauseOptions, false);
            } catch (Exception error) { pauseControlError = cause(error); System.err.println("MODACCEL_PAUSE_CONTROL_ERROR " + pauseControlError); }
            Thread server = new Thread(new Runnable() { public void run() { serve(); } }, "modaccel-control");
            server.setDaemon(true); server.start();
        }
        try { Object current = screen(); if (current != lastScreen) { lastScreen = current; screenSince = System.nanoTime(); } } catch (Exception ignored) {}
        int count = pending.size();
        for (int i = 0; i < count; i++) { Runnable action = pending.poll(); if (action != null) action.run(); }
    }
    private static void serve() {
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))) {
            String runtime = java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            identity.putAll(Json.map("schema", 1, "root", System.getProperty("modaccel.root"), "fingerprint", System.getProperty("modaccel.fingerprint"),
                    "token", token, "pid", Long.parseLong(runtime), "port", server.getLocalPort(), "clientClass", client.getClass().getName(), "started", System.currentTimeMillis()));
            Json.save(Paths.get(System.getProperty("modaccel.state"), "agent.json"), identity);
            System.out.println("MODACCEL_READY " + Json.write(identity)); System.out.flush();
            while (true) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(135000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    PrintWriter output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                    String line = input.readLine(); if (line == null) continue;
                    final Map<String, Object> request = Json.object(Json.parse(line));
                    if (!token.equals(request.get("token"))) { output.println(Json.write(Json.map("ok", false, "error", "Wrong session token"))); continue; }
                    final CompletableFuture<Map<String, Object>> result = new CompletableFuture<Map<String, Object>>();
                    pending.add(new Runnable() { public void run() { try { perform(request, result); } catch (Throwable e) { result.complete(Json.map("ok", false, "error", cause(e))); } } });
                    try { output.println(Json.write(result.get(130, TimeUnit.SECONDS))); }
                    catch (Exception error) { result.cancel(false); output.println(Json.write(Json.map("ok", false, "error", "Game did not finish the requested action: " + cause(error)))); }
                } catch (Throwable error) { System.err.println("MODACCEL_CONTROL_ERROR " + cause(error)); }
            }
        } catch (Throwable error) { System.err.println("MODACCEL_AGENT_ERROR " + cause(error)); }
    }
    private static void perform(Map<String, Object> request, final CompletableFuture<Map<String, Object>> done) throws Exception {
        if (done.isCancelled()) return;
        String command = Json.text(request, "command", "status");
        if (pauseControlError != null && !command.equals("quit")) throw new IllegalStateException("Cannot keep owned game active without focus: " + pauseControlError);
        if (command.equals("status")) {
            Object screen = screen();
            Object player = optionalField(client, "player");
            Object singleplayer = null; try { singleplayer = call(client, "getSingleplayerServer", "getServer"); } catch (NoSuchMethodException ignored) {}
            boolean hasLevel = worldLevel() != null;
            Object debugHud = null;
            try { debugHud = call(client, "getDebugHud"); } catch (NoSuchMethodException unsupported) {}
            Map<String, Object> debugInput = debugHud == null ? Json.map("available", false)
                    : Json.map("available", true, "shown", optionalField(debugHud, "showDebugHud"),
                            "hudHidden", optionalField(pauseOptions, "hudHidden"),
                            "f3Handled", optionalField(field(client, "keyboard"), "switchF3State"));
            done.complete(Json.map("ok", true, "identity", identity, "screen", screen == null ? "" : screen.getClass().getName(), "inWorld", inWorld(),
                    "integratedServer", singleplayer != null, "level", hasLevel, "worldPath", singleplayer == null ? "" : worldPath(singleplayer),
                    "pauseOnLostFocus", pauseField.getBoolean(pauseOptions), "debugInput", debugInput));
        } else if (command.equals("resize")) {
            int width = number(request, "width"), height = number(request, "height");
            if (width < 320 || width > 7680 || height < 240 || height > 4320) throw new IllegalArgumentException("Unsupported frame size");
            invoke(call(client, "getWindow"), new String[]{"setWindowedSize", "setWindowed"}, width, height);
            done.complete(Json.map("ok", true, "requestedWidth", width, "requestedHeight", height));
        } else if (command.equals("open-world")) {
            String world = Json.text(request, "world", "lab");
            if (world.isEmpty() || world.equals(".") || world.equals("..") || world.indexOf('/') >= 0 || world.indexOf('\\') >= 0 || world.indexOf('\0') >= 0)
                throw new IllegalArgumentException("World must be one existing save name");
            Object flows = call(client, "createWorldOpenFlows", "createIntegratedServerLoader");
            invoke(flows, new String[]{"openWorld", "start"}, world, (Runnable)() -> {});
            done.complete(Json.map("ok", true, "requestedWorld", world));
        } else if (command.equals("connect")) {
            String address = Json.text(request, "address", "127.0.0.1:25565");
            Class<?> addressType = load("net.minecraft.client.multiplayer.resolver.ServerAddress", "net.minecraft.client.network.ServerAddress");
            Object parsed = invoke(addressType, new String[]{"parseString", "parse"}, address);
            Class<?> dataType = load("net.minecraft.client.multiplayer.ServerData", "net.minecraft.client.network.ServerInfo");
            Object data = null;
            for (Constructor<?> constructor : dataType.getConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length != 3 || parameters[0] != String.class || parameters[1] != String.class || !parameters[2].isEnum()) continue;
                data = constructor.newInstance("modaccel", address, parameters[2].getEnumConstants()[0]); break;
            }
            if (data == null) throw new IllegalStateException("Unsupported server data constructor");
            Object title = load("net.minecraft.client.gui.screens.TitleScreen", "net.minecraft.client.gui.screen.TitleScreen").getDeclaredConstructor().newInstance();
            Object connectType = load("net.minecraft.client.gui.screens.ConnectScreen", "net.minecraft.client.gui.screen.multiplayer.ConnectScreen");
            invoke(connectType, new String[]{"startConnecting", "connect"}, title, client, parsed, data, false, null);
            done.complete(Json.map("ok", true, "requestedServer", address));
        } else if (command.equals("state")) {
            Object player = optionalField(client, "player");
            if (player == null) { done.complete(Json.map("ok", true, "inWorld", false)); return; }
            Map<String, Object> state = Json.map("ok", true, "inWorld", true,
                    "x", ((Number)call(player, "getX")).doubleValue(),
                    "y", ((Number)call(player, "getY")).doubleValue(),
                    "z", ((Number)call(player, "getZ")).doubleValue());
            String dimension = "";
            Object world = null;
            try { world = call(player, "getWorld", "level"); } catch (NoSuchMethodException unsupported) { }
            if (world != null) try {
                Object key = call(world, "getRegistryKey", "dimension");
                Object value = key == null ? null : call(key, "getValue");
                if (value != null) dimension = value.toString();
            } catch (Exception unsupported) { }
            state.put("dimension", dimension);
            done.complete(state);
        } else if (command.equals("capture") || command.equals("ready")) {
            Object overlay = optionalField(client, "overlay"), current = screen();
            Object gui = optionalField(client, "gui");
            if (overlay == null && gui != null) try { overlay = call(gui, "overlay"); } catch (Exception ignored) {}
            String screenName = current == null ? "" : current.getClass().getSimpleName();
            // Loading screens and the title screen's initial fade are transitional frames. The title
            // fades its background over 2 s and then its buttons over 2 more (1.21.6), so wait 4.5 s.
            boolean transition = screenName.equals("MessageScreen") || screenName.equals("GenericMessageScreen")
                    || screenName.equals("LevelLoadingScreen") || screenName.equals("ProgressScreen") || screenName.equals("ReceivingLevelScreen")
                    || System.nanoTime() - screenSince < (screenName.equals("TitleScreen") ? 4500000000L : 1000000000L);
            String expectedScreen = Json.text(request, "expectScreen", ""), leaveScreen = Json.text(request, "leaveScreen", "");
            boolean outcomePending = !expectedScreen.isEmpty() && (current == null || !expectedScreen.equals(current.getClass().getName()))
                    || !leaveScreen.isEmpty() && current != null && leaveScreen.equals(current.getClass().getName())
                    || Boolean.TRUE.equals(request.get("expectWorld")) && !inWorld()
                    || Boolean.TRUE.equals(request.get("requireScreen")) && current == null;
            if (overlay != null || transition || outcomePending) {
                long deadline = request.containsKey("deadline") ? ((Number)request.get("deadline")).longValue() : System.nanoTime() + 120000000000L;
                request.put("deadline", deadline);
                if (System.nanoTime() >= deadline) throw new IOException("Game did not reach the requested state; expected screen="
                        + (Boolean.TRUE.equals(request.get("requireScreen")) ? "open" : leaveScreen.isEmpty() ? expectedScreen : "not " + leaveScreen)
                        + ", world=" + request.get("expectWorld") + "; current screen=" + screenName);
                pending.add(new Runnable() { public void run() { try { perform(request, done); } catch (Throwable e) { done.complete(Json.map("ok", false, "error", cause(e))); } } });
                return;
            }
            if (command.equals("ready")) { done.complete(Json.map("ok", true, "screen", screenName)); return; }
            final Path file = Paths.get(Json.text(request, "path", "")).toAbsolutePath().normalize();
            Path allowed = Paths.get(System.getProperty("modaccel.root"), "build/modaccel/shots").toAbsolutePath().normalize();
            if (!file.startsWith(allowed)) throw new IllegalArgumentException("Captures must be inside " + allowed);
            final int width = number(request, "width"), height = number(request, "height");
            Object target;
            try { target = call(client, "getFramebuffer", "getMainRenderTarget"); }
            catch (NoSuchMethodException changed) { target = call(field(client, "gameRenderer"), "mainRenderTarget"); }
            Class<?> screenshot = load("net.minecraft.client.util.ScreenshotRecorder", "net.minecraft.client.Screenshot");
            final Consumer<Object> save = new Consumer<Object>() { public void accept(Object image) {
                try {
                    int actualWidth = ((Number)call(image, "getWidth")).intValue(), actualHeight = ((Number)call(image, "getHeight")).intValue();
                    if (actualWidth != width || actualHeight != height) throw new IOException("Framebuffer was " + actualWidth + "x" + actualHeight + "; expected " + width + "x" + height);
                    Files.createDirectories(file.getParent());
                    invoke(image, new String[]{"writeTo", "writeToFile"}, file);
                    done.complete(Json.map("ok", true, "path", file.toString(), "width", width, "height", height, "source", "Minecraft framebuffer"));
                } catch (Throwable error) { done.complete(Json.map("ok", false, "error", cause(error))); }
                finally { try { call(image, "close"); } catch (Exception ignored) {} }
            }};
            try { invoke(screenshot, new String[]{"takeScreenshot"}, target, save); }
            catch (NoSuchMethodException older) { save.accept(invoke(screenshot, new String[]{"takeScreenshot"}, target)); }
        } else if (command.equals("screen")) {
            Class<?> type = load(Json.text(request, "class", "")); Object next;
            try { next = type.getDeclaredConstructor().newInstance(); }
            catch (NoSuchMethodException needsParent) {
                next = null; boolean found = false;
                for (Constructor<?> constructor : type.getConstructors()) if (constructor.getParameterTypes().length == 1 && constructor.getParameterTypes()[0].getSimpleName().equals("Screen")) {
                    next = constructor.newInstance(screen()); found = true; break;
                }
                if (!found) throw new IllegalArgumentException("This screen needs a scenario-specific constructor: " + type.getName());
            }
            try { invoke(client, new String[]{"setScreen"}, next); }
            catch (NoSuchMethodException modern) { invoke(field(client, "gui"), new String[]{"setScreen"}, next); }
            done.complete(Json.map("ok", true, "screen", type.getName()));
        } else if (command.equals("click")) {
            String wanted = Json.text(request, "label", "");
            Object button = button(screen(), wanted);
            if (button == null) throw new IllegalArgumentException("No enabled button labelled '" + wanted + "' on " + screen().getClass().getName());
            double x = ((Number)call(button, "getX")).doubleValue() + ((Number)call(button, "getWidth")).doubleValue() / 2;
            double y = ((Number)call(button, "getY")).doubleValue() + ((Number)call(button, "getHeight")).doubleValue() / 2;
            Object accepted;
            try { accepted = invoke(button, new String[]{"mouseClicked"}, x, y, 0); }
            catch (NoSuchMethodException newInput) {
                accepted = false;
                for (Method method : button.getClass().getMethods()) if (method.getName().equals("mouseClicked") && method.getParameterTypes().length == 2) {
                    Class<?> eventType = method.getParameterTypes()[0];
                    for (Constructor<?> constructor : eventType.getConstructors()) if (constructor.getParameterTypes().length == 3 && constructor.getParameterTypes()[0] == double.class) {
                        Class<?> infoType = constructor.getParameterTypes()[2];
                        Object info = infoType.getConstructor(int.class, int.class).newInstance(infoType.getSimpleName().equals("MouseButtonInfo") ? 1 : 0, 0);
                        accepted = method.invoke(button, constructor.newInstance(x, y, info), false); break;
                    }
                }
            }
            if (!Boolean.TRUE.equals(accepted)) throw new IllegalStateException("Button rejected the mouse event: " + wanted);
            done.complete(Json.map("ok", true, "clicked", wanted));
        } else if (command.equals("click-at")) {
            int px = number(request, "x"), py = number(request, "y");
            if (px < 0 || px >= 1280 || py < 0 || py >= 720) throw new IllegalArgumentException("click-at needs a point inside the 1280x720 framebuffer");
            Object showing = screen();
            if (showing == null) throw new IllegalStateException("No screen is open for click-at");
            int width = ((Number)field(showing, "width")).intValue(), height = ((Number)field(showing, "height")).intValue();
            double x = px * (double)width / 1280, y = py * (double)height / 720;
            Object accepted = invoke(showing, new String[]{"mouseClicked"}, x, y, 0);
            if (!Boolean.TRUE.equals(accepted)) throw new IllegalStateException("Screen rejected click-at " + px + "," + py);
            done.complete(Json.map("ok", true, "frameX", px, "frameY", py, "screen", showing.getClass().getName()));
        } else if (command.equals("move-at")) {
            int px = number(request, "x"), py = number(request, "y");
            if (px < 0 || px >= 1280 || py < 0 || py >= 720) throw new IllegalArgumentException("move-at needs a point inside the 1280x720 framebuffer");
            Object mouse = field(client, "mouse", "mouseHandler"), window = call(client, "getWindow");
            long handle = ((Number)call(window, "getHandle", "getWindow")).longValue();
            Method move = null;
            for (Class<?> type = mouse.getClass(); type != null && move == null; type = type.getSuperclass())
                for (String name : new String[]{"onCursorPos", "onMove"}) try { move = type.getDeclaredMethod(name, long.class, double.class, double.class); break; }
                catch (NoSuchMethodException ignored) { }
            if (move == null) throw new NoSuchMethodException("Mouse cursor callback not found");
            move.setAccessible(true); move.invoke(mouse, handle, (double)px, (double)py);
            done.complete(Json.map("ok", true, "frameX", px, "frameY", py));
        } else if (command.equals("key")) {
            int key = number(request, "key");
            if (key < 32 || key > 348) throw new IllegalArgumentException("Unsupported GLFW key code " + key);
            Object keyboard = field(client, "keyboard", "keyboardHandler");
            Object window = call(client, "getWindow");
            long handle = ((Number)call(window, "getHandle", "getWindow")).longValue();
            invoke(keyboard, new String[]{"onKey", "keyPress"}, handle, key, 0, 1, 0);
            invoke(keyboard, new String[]{"onKey", "keyPress"}, handle, key, 0, 0, 0);
            done.complete(Json.map("ok", true, "key", key));
        } else if (command.equals("type")) {
            String value = Json.text(request, "text", "");
            if (value.length() > 256 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) throw new IllegalArgumentException("type needs one line of at most 256 characters");
            Object showing = screen();
            if (showing == null) throw new IllegalStateException("No screen is open for typing");
            for (int index = 0; index < value.length();) {
                int codepoint = value.codePointAt(index); index += Character.charCount(codepoint);
                Object accepted;
                try { accepted = invoke(showing, new String[]{"charTyped"}, codepoint, 0); }
                catch (NoSuchMethodException oldCharSignature) {
                    if (!Character.isBmpCodePoint(codepoint)) throw new IllegalArgumentException("This game cannot type non-BMP characters");
                    accepted = invoke(showing, new String[]{"charTyped"}, (char)codepoint, 0);
                }
                if (!Boolean.TRUE.equals(accepted))
                    throw new IllegalStateException("Screen rejected typed character at offset " + index);
            }
            done.complete(Json.map("ok", true, "typed", value.length()));
        } else if (command.equals("expect-value")) {
            String getter = Json.text(request, "getter", ""), expected = Json.text(request, "value", "");
            Object actual = call(screen(), getter);
            if (!expected.equals(String.valueOf(actual))) throw new AssertionError("Screen " + getter + " expected " + expected + "; got " + actual);
            done.complete(Json.map("ok", true, "getter", getter, "expected", expected, "actual", actual));
        } else if (command.equals("assert")) {
            String[] target = Json.text(request, "method", "").split("#", -1);
            if (target.length != 2) throw new IllegalArgumentException("Assertion must be class#staticMethod");
            Method method = load(target[0]).getDeclaredMethod(target[1]); method.setAccessible(true);
            if (!Modifier.isStatic(method.getModifiers())) throw new IllegalArgumentException("Assertion must be static");
            if (method.getReturnType() != void.class && method.getReturnType() != boolean.class) throw new IllegalArgumentException("Assertion must return void or boolean");
            Object result = method.invoke(null);
            if (method.getReturnType() == boolean.class && !Boolean.TRUE.equals(result)) throw new AssertionError("Assertion returned false: " + request.get("method"));
            done.complete(Json.map("ok", true, "assertion", request.get("method")));
        } else if (command.equals("mods")) {
            // Ask the running loader, not the log, whether each of the project's mods is loaded.
            Object ids = request.get("ids");
            Map<String, Object> loaded = new LinkedHashMap<String, Object>(), versions = new LinkedHashMap<String, Object>();
            String loaderName = "unknown"; Object fabric = null, neo = null; Class<?> fabricType = null, neoType = null;
            try { fabricType = load("net.fabricmc.loader.api.FabricLoader"); fabric = fabricType.getMethod("getInstance").invoke(null); loaderName = "fabric"; } catch (Throwable notFabric) {}
            if (fabric == null) try { neoType = load("net.neoforged.fml.ModList"); neo = neoType.getMethod("get").invoke(null); loaderName = "neoforge"; } catch (Throwable notNeoForge) {}
            if (ids instanceof List) for (Object id : (List<?>)ids) {
                String name = String.valueOf(id); Object present = null;
                if (fabric != null) {
                    present = fabricType.getMethod("isModLoaded", String.class).invoke(fabric, name);
                    try {
                        Object container = ((Optional<?>)fabricType.getMethod("getModContainer", String.class).invoke(fabric, name)).orElse(null);
                        // Through the public API interfaces: the loader's implementation classes are not public.
                        if (container != null) {
                            Object meta = load("net.fabricmc.loader.api.ModContainer").getMethod("getMetadata").invoke(container);
                            Object version = load("net.fabricmc.loader.api.metadata.ModMetadata").getMethod("getVersion").invoke(meta);
                            versions.put(name, String.valueOf(load("net.fabricmc.loader.api.Version").getMethod("getFriendlyString").invoke(version)));
                        }
                    } catch (Throwable noVersion) {}
                } else if (neo != null) present = neoType.getMethod("isLoaded", String.class).invoke(neo, name);
                loaded.put(name, present);
            }
            done.complete(Json.map("ok", true, "loader", loaderName, "loaded", loaded, "versions", versions));
        } else if (command.equals("quit")) {
            if (pauseField != null) pauseField.setBoolean(pauseOptions, originalPauseOnLostFocus);
            done.complete(Json.map("ok", true, "stopping", true)); call(client, "scheduleStop", "stop");
        } else throw new IllegalArgumentException("Unsupported command " + command + "; supported: status, resize, capture, screen, click, assert, quit");
    }
    private static Object button(Object element, String wanted) throws Exception {
        if (element == null) return null;
        try {
            Object message = call(element, "getMessage");
            if (wanted.equals(call(message, "getString"))) {
                Object active = optionalField(element, "active"), visible = optionalField(element, "visible");
                if (!Boolean.FALSE.equals(active) && !Boolean.FALSE.equals(visible)) return element;
            }
        } catch (NoSuchMethodException notButton) { }
        try { for (Object child : (Iterable<?>)call(element, "children")) { Object found = button(child, wanted); if (found != null) return found; } }
        catch (NoSuchMethodException noChildren) { }
        return null;
    }
    private static Object screen() throws Exception {
        try { return field(client, "currentScreen", "screen"); }
        catch (NoSuchFieldException modern) { return call(field(client, "gui"), "screen"); }
    }
    private static Object worldLevel() {
        Object value = optionalField(client, "level");
        return value != null ? value : optionalField(client, "world");
    }
    private static boolean inWorld() { return optionalField(client, "player") != null && worldLevel() != null; }
    private static String worldPath(Object server) throws Exception {
        Class<?> resource = load("net.minecraft.world.level.storage.LevelResource", "net.minecraft.util.WorldSavePath");
        Object root = resource.getField("ROOT").get(null);
        Object path = invoke(server, new String[]{"getWorldPath", "getSavePath"}, root);
        return ((Path)path).toAbsolutePath().normalize().toString();
    }
    private static int number(Map<String, Object> request, String key) { return ((Number)request.get(key)).intValue(); }
    private static Object optionalField(Object owner, String name) { try { return field(owner, name); } catch (Exception ignored) { return null; } }
    private static Class<?> load(String... names) throws ClassNotFoundException {
        for (String name : names) try { return Class.forName(name, true, client.getClass().getClassLoader()); } catch (ClassNotFoundException ignored) {}
        throw new ClassNotFoundException(Arrays.toString(names));
    }
    private static Object field(Object owner, String... names) throws Exception {
        for (String name : names) for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) try {
            Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner);
        } catch (NoSuchFieldException ignored) {}
        throw new NoSuchFieldException(owner.getClass().getName() + " " + Arrays.toString(names));
    }
    private static Object call(Object owner, String... names) throws Exception { return invoke(owner, names, new Object[0]); }
    private static Object invoke(Object owner, String[] names, Object... arguments) throws Exception {
        Class<?> type = owner instanceof Class ? (Class<?>)owner : owner.getClass();
        for (String name : names) for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != arguments.length) continue;
            try { return method.invoke(owner instanceof Class ? null : owner, arguments); }
            catch (IllegalArgumentException mismatch) { }
        }
        throw new NoSuchMethodException(type.getName() + " " + Arrays.toString(names) + "/" + arguments.length);
    }
    private static String cause(Throwable error) { while (error.getCause() != null) error = error.getCause(); return error.toString(); }
}
