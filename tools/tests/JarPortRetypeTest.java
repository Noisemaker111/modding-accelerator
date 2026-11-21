import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/**
 * Overrides whose game method's parameters changed type, and injection points a method no longer has, run on this JVM
 * against real classes of the new release. The game's Screen.render(Pose, int) became render(Graphics, int) and
 * key(int, int, int) became key(Key); its own Title screen shows graphics.pose() and key.code() where the old code used
 * the old parameters. A mod screen's render and key move to the new signatures with those conversions, and the calls in
 * them get what the game passes now: back(pose) -> back(graphics), the static Gui.fill(pose, x) -> graphics.fill(x),
 * super.key(k, s, m) -> super.key(event), super.render(pose, x) of the mod's own parent -> its moved render. A call with a
 * pose the game did not pass, or outside a moved body, stays reported, and so does a mixin injection point the new
 * method no longer has.
 */
final class JarPortRetypeTest {

    static final String POSE = "Lg6/Pose;", GFX = "Lg6/Graphics;", KEY = "Lg6/Key;";
    static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";

    static void check(Path scratch) throws Exception {
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        b.loaderVersion = "2.0.1";
        JarPortTest.snap(a, "g6/Pose", 0, null, "push()V");
        JarPortTest.snap(b, "g6/Pose", 0, null, "push()V");
        JarPortTest.snap(b, "g6/Graphics", 0, null, "pose()" + POSE, "fill(I)V", "tip2(Lg6/Font;I)V");
        JarPortTest.snap(b, "g6/Key", 0, null, "code()I", "scan()I");
        JarPortTest.snap(a, "g6/Gui", 0, null);
        JarPortTest.snap(a, "g7/Bundled", 0, null, "run()V");
        JarPortTest.snap(a, "g6/Dropped", 0, null, "run()V");
        a.classes.get("g6/Gui").methods.put("fill(" + POSE + "I)V", new GameDiff.Meth(a.classes.get("g6/Gui"), "fill", "(" + POSE + "I)V", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        JarPortTest.snap(a, "g6/Screen", 0, null, "render(" + POSE + "I)V", "back(" + POSE + ")V", "key(III)Z", "tick()V", "old()V", "kept()V", "hover(" + POSE + ")V", "chr(CI)Z", "drag(IIII)Z", "scroll(III)Z");
        JarPortTest.snap(b, "g6/Screen", 0, null, "render(" + GFX + "I)V", "back(" + GFX + ")V", "key(" + KEY + ")Z", "tick()V", "old()V", "kept()V", "hover(" + GFX + ")V", "chr(" + KEY + ")Z", "drag(" + KEY + "II)Z", "scroll(IIII)Z");
        JarPortTest.snap(a, "g6/Title", 0, "g6/Screen", "render(" + POSE + "I)V", "key(III)Z", "chr(CI)Z", "drag(IIII)Z");
        JarPortTest.snap(b, "g6/Title", 0, "g6/Screen", "render(" + GFX + "I)V", "key(" + KEY + ")Z", "chr(" + KEY + ")Z", "drag(" + KEY + "II)Z");
        // Screen.tip(pose, x) goes in 2.0; the game's own code writes graphics.tip2(this.font, x), font being protected.
        for (GameDiff.Snap s : List.of(a, b)) {
            JarPortTest.snap(s, "g6/Font", 0, null);
            GameDiff.Cls screenClass = s.classes.get("g6/Screen");
            screenClass.fields.put("font", new GameDiff.Fld(screenClass, "font", "Lg6/Font;", Opcodes.ACC_PROTECTED));
        }
        a.classes.get("g6/Screen").methods.put("tip(" + POSE + "I)V", new GameDiff.Meth(a.classes.get("g6/Screen"), "tip", "(" + POSE + "I)V", Opcodes.ACC_PUBLIC));
        JarPortTest.snap(a, "g6/Row", 0, null, "render(" + POSE + "I)V");
        JarPortTest.snap(b, "g6/Row", 0, null, "render(" + GFX + "I)V");
        // A callback interface whose method took the pose and takes the graphics in 2.0 (as a Fabric API callback did).
        JarPortTest.snap(a, "g6/Hud", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, "draw(" + POSE + "I)V");
        JarPortTest.snap(b, "g6/Hud", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, "draw(" + GFX + "I)V");
        // g6.Model's transforms() is abstract in both releases' jars (the loader supplies it); 2.0 adds an abstract extra().
        JarPortTest.snap(a, "g6/Model", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, "quads()V", "transforms()V");
        JarPortTest.snap(b, "g6/Model", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, "quads()V", "transforms()V", "extra()V");
        // A factory interface whose method's argument became another type altogether (a pose, now a key).
        JarPortTest.snap(a, "g6/Maker", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, "make(" + POSE + ")V");
        JarPortTest.snap(b, "g6/Maker", Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, "make(" + KEY + ")V");
        JarPortTest.snap(a, "g6/Loc", 0, null);
        // g6.Part goes in 2.0 and the game's own Title2 extends Object instead, though the map follows Part's members to Graphics.
        JarPortTest.snap(a, "g6/Part", 0, null, "own()V");
        JarPortTest.snap(a, "g6/Title2", 0, "g6/Part");
        JarPortTest.snap(b, "g6/Title2", 0, null);
        JarPortTest.snap(b, "g6/Loc", 0, null);
        JarPortTest.snap(a, "g6/ModelLoc", 0, "g6/Loc");
        JarPortTest.snap(b, "g6/ModelLoc", 0, null);
        Path map = scratch.resolve("route-retype.json");
        Files.writeString(map, """
            {"classes": [{"from": "g6.Part", "to": "g6.Graphics"}],
             "signatures": [{"owner": "g6.Screen", "from": "render", "desc": "(Lg6/Pose;I)V", "to": "render", "newDesc": "(Lg6/Graphics;I)V"},
                            {"owner": "g6.Screen", "from": "back", "desc": "(Lg6/Pose;)V", "to": "back", "newDesc": "(Lg6/Graphics;)V"},
                            {"owner": "g6.Screen", "from": "key", "desc": "(III)Z", "to": "key", "newDesc": "(Lg6/Key;)Z"},
                            {"owner": "g6.Screen", "from": "hover", "desc": "(Lg6/Pose;)V", "to": "hover", "newDesc": "(Lg6/Graphics;)V"},
                            {"owner": "g6.Screen", "from": "chr", "desc": "(CI)Z", "to": "chr", "newDesc": "(Lg6/Key;)Z"},
                            {"owner": "g6.Screen", "from": "drag", "desc": "(IIII)Z", "to": "drag", "newDesc": "(Lg6/Key;II)Z"},
                            {"owner": "g6.Screen", "from": "scroll", "desc": "(III)Z", "to": "scroll", "newDesc": "(IIII)Z"},
                            {"owner": "g6.Row", "from": "render", "desc": "(Lg6/Pose;I)V", "to": "render", "newDesc": "(Lg6/Graphics;I)V"},
                            {"owner": "g6.Hud", "from": "draw", "desc": "(Lg6/Pose;I)V", "to": "draw", "newDesc": "(Lg6/Graphics;I)V"},
                            {"owner": "g6.Maker", "from": "make", "desc": "(Lg6/Pose;)V", "to": "make", "newDesc": "(Lg6/Key;)V"}],
             "retyped": [{"step": "1.0 -> 2.0", "owner": "g6.Screen", "from": "tip", "desc": "(Lg6/Pose;I)V", "at": 1, "as": "Lg6/Graphics;",
                          "expr": "$1.tip2($0.font, $2)", "votes": 3, "of": 3}],
             "rewrites": [{"owner": "g6.Gui", "from": "fill", "desc": "(Lg6/Pose;I)V", "static": true, "toOwner": "g6.Graphics", "to": "fill",
                           "newDesc": "(I)V", "newStatic": false, "votes": 3, "of": 3}]}
            """);
        JarPort port = new JarPort(a, b, map);
        port.oldCode = new JarPort.GameCode(Map.of("g6/Title", title(true), "g6/Screen", screen(true))::get, null);
        port.newCode = new JarPort.GameCode(Map.of("g6/Title", title(false), "g6/Screen", screen(false))::get, null);

        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, byte[]> ported = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("m6/MyScreen.class", myScreen(), "m6/MyChild.class", myChild(), "m6/Extra.class", extra(), "m6/Lambda.class", lambda())), "retype.jar", results));
        List<String> unlinked = Json.array(results.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(unlinked.isEmpty(), "a screen whose render and key moved to the new parameter types links: " + unlinked);
        Map<String, String> members = JarPortTest.members(ported.get("m6/MyScreen.class"));
        JarPortTest.expect(members.containsKey("method render(" + GFX + "I)V") && !members.containsKey("method render(" + POSE + "I)V")
            && members.containsKey("method key(" + KEY + ")Z"), "the old methods are replaced by the new signatures: " + members.keySet());

        List<Map<String, Object>> called = new ArrayList<>();
        Map<String, byte[]> keptJar = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("m6/MyScreen.class", myScreen(), "m6/Caller.class", caller())), "kept.jar", called));
        Map<String, String> kept = JarPortTest.members(keptJar.get("m6/MyScreen.class"));
        List<String> keptUnlinked = Json.array(called.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(kept.containsKey("method render(" + POSE + "I)V") && kept.containsKey("method render(" + GFX + "I)V")
            && keptUnlinked.stream().noneMatch(u -> u.contains("Caller") || u.contains("MyScreen.render")),
            "a moved method that a call elsewhere cannot follow is also kept as it was, so that call still links: " + kept.keySet() + " " + keptUnlinked);

        List<Map<String, Object>> scrolled = new ArrayList<>();
        Map<String, String> scroller = JarPortTest.members(JarPortTest.entries(port.port(JarPortTest.jar(Map.of("m6/Scroller.class", scroller())), "scroll.jar", scrolled)).get("m6/Scroller.class"));
        List<String> scrollSilent = Json.array(scrolled.get(0).get("silent")).stream().map(String::valueOf).toList();
        JarPortTest.expect(scroller.containsKey("method scroll(III)Z") && !scroller.containsKey("method scroll(IIII)Z")
            && scrollSilent.stream().anyMatch(s -> s.startsWith("override no longer called: m6.Scroller.scroll(III)Z")),
            "a body with a call that cannot be given what the game passes now (no value for a parameter it only passes on) stays as it was: "
                + scroller.keySet() + " " + scrollSilent);

        // A NeoForge jar from before 1.20.5 names its metadata META-INF/mods.toml, which NeoForge 1.20.5 on no longer reads.
        Map<String, byte[]> toml = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("META-INF/mods.toml",
            "modLoader=\"javafml\"\n[[dependencies.m6]]\nmodId=\"minecraft\"\nversionRange=\"[1.0]\"\n[[dependencies.m6]]\nmodId=\"forge\"\nmandatory=true\nversionRange=\"*\"\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
            "old.jar", new ArrayList<>()));
        JarPortTest.expect(!toml.containsKey("META-INF/mods.toml") && new String(toml.getOrDefault("META-INF/neoforge.mods.toml", new byte[0]),
                java.nio.charset.StandardCharsets.UTF_8).contains("versionRange=\"[2.0]\""),
            "a jar ported past 1.20.5 gets its metadata as META-INF/neoforge.mods.toml, asking for the new release: " + toml.keySet());
        String neoToml = new String(toml.getOrDefault("META-INF/neoforge.mods.toml", new byte[0]), java.nio.charset.StandardCharsets.UTF_8);
        JarPortTest.expect(neoToml.contains("modId=\"neoforge\"") && !neoToml.contains("modId=\"forge\""),
            "a Forge jar's dependency on the loader asks for NeoForge, which provides no mod called forge: " + neoToml);

        // A class the new release lacks but the mod carries in its own nested jar: where the release has no such package any
        // more (a module the platform stopped shipping), the bundled copy runs; where it still has the package, it does not.
        List<Map<String, Object>> bundled = new ArrayList<>();
        port.port(JarPortTest.jar(Map.of("m6/UsesLib.class", usesLib(), "META-INF/jarjar/lib.jar",
            JarPortTest.jar(Map.of("g7/Bundled.class", JarPortTest.real("g7/Bundled", "java/lang/Object", 0, new String[0], w -> JarPortTest.traced(w, "run", "()V", "", m -> { })),
                "g6/Dropped.class", JarPortTest.real("g6/Dropped", "java/lang/Object", 0, new String[0], w -> JarPortTest.traced(w, "run", "()V", "", m -> { })))))),
            "bundled.jar", bundled);
        List<String> outer = Json.array(bundled.stream().filter(b2 -> "bundled.jar".equals(b2.get("jar"))).findFirst().orElseThrow().get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(outer.stream().noneMatch(u -> u.contains("g7.Bundled")) && outer.stream().anyMatch(u -> u.contains("g6.Dropped")),
            "a class the mod's own nested jar carries, from a package the new release dropped, is the mod's; one from a package it kept is gone: " + outer);

        List<Map<String, Object>> others = new ArrayList<>();
        Map<String, byte[]> other = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("m6/Other.class", other(), "m6/ScreenMixin.class", mixin(), "m6/ByName.class", byName(),
            "m6.mixins.json", "{\"package\": \"m6\", \"mixins\": [\"ScreenMixin\", \"ByName\"], \"injectors\": {\"defaultRequire\": 1}}".getBytes(java.nio.charset.StandardCharsets.UTF_8))), "other.jar", others));
        List<String> stays = Json.array(others.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(stays.contains("call: g6.Gui.fill(Lg6/Pose;I)V"),
            "a pose the game did not pass, and one outside a moved body, still get no graphics: " + stays);
        JarPortTest.expect(stays.contains("mixin injection point gone: g6.Screen.old()V in g6.Screen.tick()V (in m6.ScreenMixin)")
            && stays.contains("mixin injection point gone: STORE #1 of a int local in g6.Screen.tick()V (in m6.ScreenMixin)")
            && stays.contains("mixin injection point gone: g6.Screen.old()V in g6.Screen.tick()V (in m6.ByName)")
            && stays.stream().filter(u -> u.startsWith("mixin injection point gone")).count() == 3,
            "an @Inject at a call the new method no longer makes, and a @ModifyVariable at a second int store it no longer has, are reported; "
                + "one at a call it still makes is not: " + stays);
        // Other injectors into render: one that takes none of its arguments follows render to its new signature; one that
        // captures the pose, which render now takes as graphics, stays reported.
        List<Map<String, Object>> varRuns = new ArrayList<>();
        Map<String, byte[]> vars = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("m6/ByVar.class", byVar(),
            "m6.mixins.json", "{\"package\": \"m6\", \"mixins\": [\"ByVar\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8))), "var.jar", varRuns));
        List<String> varUnlinked = Json.array(varRuns.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        Map<String, String> varStrings = JarPortFixesTest.strings(vars.get("m6/ByVar.class"));
        JarPortTest.expect("render(Lg6/Graphics;I)V HEAD".equals(varStrings.get("onX(I)I"))
            && varUnlinked.equals(List.of("mixin target changed signature: g6.Screen.render(Lg6/Pose;I)V")),
            "a @ModifyVariable that takes nothing of render's moves to render's new signature, one capturing the pose stays reported: " + varStrings + " " + varUnlinked);

        // A row (no screen) calling screen.tip(pose, x): written as graphics.tip2(screen.font, x), the protected font opened
        // through the access widener when the loader reads one, and reported where it does not.
        List<Map<String, Object>> rowRuns = new ArrayList<>();
        Map<String, byte[]> rowJar = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("m6/MyRow.class", myRow(),
            "fabric.mod.json", "{\"id\": \"m6\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))), "row.jar", rowRuns));
        List<String> rowUnlinked = Json.array(rowRuns.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        String widener = rowJar.entrySet().stream().filter(e -> e.getKey().endsWith(".accesswidener")).map(e -> new String(e.getValue(), java.nio.charset.StandardCharsets.UTF_8)).findFirst().orElse("");
        String rowRender = String.join("\n", JarPortTest.members(rowJar.get("m6/MyRow.class")).values());
        JarPortTest.expect(rowUnlinked.isEmpty() && widener.contains("accessible field g6/Screen font Lg6/Font;") && rowRender.contains("get g6/Screen.font Lg6/Font;") && rowRender.contains("call g6/Graphics.tip2(Lg6/Font;I)V"),
            "a rewrite reading a closed field opens it through the access widener: " + rowUnlinked + " " + widener + " " + rowRender);
        List<Map<String, Object>> closedRuns = new ArrayList<>();
        port.port(JarPortTest.jar(Map.of("m6/MyRow.class", myRow())), "closed.jar", closedRuns);
        List<String> closedUnlinked = Json.array(closedRuns.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(closedUnlinked.contains("call: g6.Screen.tip(Lg6/Pose;I)V"),
            "where the loader reads no access widener the call stays reported: " + closedUnlinked);

        // A mixin casting its own this to an accessor of Loc relies on ModelLoc being a Loc, which 2.0 ends; Title is still a Screen.
        List<Map<String, Object>> castRuns = new ArrayList<>();
        port.port(JarPortTest.jar(Map.of("m6/LocAccess.class", locAccess(), "m6/ModelLocMixin.class", selfCast("m6/ModelLocMixin", "g6/ModelLoc", "m6/LocAccess"),
            "m6/TitleMixin.class", selfCast("m6/TitleMixin", "g6/Title", "g6/Screen"))), "cast.jar", castRuns);
        List<String> castUnlinked = Json.array(castRuns.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(castUnlinked.equals(List.of("mixin target no longer a g6.Loc: g6.ModelLoc (in m6.ModelLocMixin)")),
            "a mixin's this cast to a type its target stopped being is reported; one its target still is is not: " + castUnlinked);
        List<Map<String, Object>> quiet = new ArrayList<>();
        port.port(JarPortTest.jar(Map.of("m6/ScreenMixin.class", mixin())), "quiet.jar", quiet);
        List<String> quietUnlinked = Json.array(quiet.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        List<String> quietSilent = Json.array(quiet.get(0).get("silent")).stream().map(String::valueOf).toList();
        JarPortTest.expect(quietUnlinked.stream().noneMatch(u -> u.contains("injection point"))
            && quietSilent.contains("mixin injection finds no place: g6.Screen.old()V in g6.Screen.tick()V (in m6.ScreenMixin)"),
            "where nothing requires the place (no defaultRequire), a gone injection point is a quiet hook, not a break: " + quietUnlinked + " " + quietSilent);
        Map<String, String> handlers = JarPortFixesTest.strings(other.get("m6/ScreenMixin.class"));
        String onRender = JarPortTest.members(other.get("m6/ScreenMixin.class")).getOrDefault("method onRender(" + GFX + "I" + CI + ")V", "");
        JarPortTest.expect("render(Lg6/Graphics;I)V HEAD".equals(handlers.get("onRender(" + GFX + "I" + CI + ")V"))
            && onRender.contains("call g6/Graphics.pose()Lg6/Pose;") && onRender.contains("call g6/Pose.push()V")
            && !handlers.containsKey("onRender(" + POSE + "I" + CI + ")V") && handlers.keySet().stream().noneMatch(k -> k.startsWith("onRender$mmt")),
            "a mixin handler that reads the old argument moves whole to the new signature, its pose worked out as graphics.pose(): " + handlers + " " + onRender);

        Map<String, byte[]> classes = new HashMap<>(JarPortTest.newGame());
        classes.put("g6/Pose", JarPortTest.real("g6/Pose", "java/lang/Object", 0, new String[0], w -> JarPortTest.traced(w, "push", "()V", "push;", m -> { })));
        classes.put("g6/Graphics", graphics());
        classes.put("g6/Key", key());
        classes.put("g6/Screen", screen(false));
        classes.put("m6/MyScreen", ported.get("m6/MyScreen.class"));
        classes.put("m6/MyChild", ported.get("m6/MyChild.class"));
        classes.put("m6/Extra", ported.get("m6/Extra.class"));
        classes.put("m6/Lambda", ported.get("m6/Lambda.class"));
        ClassLoader loader = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) {
                    return Class.forName(name, false, ClassLoader.getPlatformClassLoader());
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        String trace;
        try {
            Class<?> screen = loader.loadClass("g6.Screen");
            screen.getMethod("run", screen).invoke(null, loader.loadClass("m6.MyScreen").getConstructor().newInstance());
            screen.getMethod("run", screen).invoke(null, loader.loadClass("m6.MyChild").getConstructor().newInstance());
            screen.getMethod("run2", screen).invoke(null, loader.loadClass("m6.Extra").getConstructor().newInstance());
            trace = String.valueOf(loader.loadClass("g2.Log").getField("out").get(null));
        } catch (ReflectiveOperationException | LinkageError failed) {
            trace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        String lambdaTrace;
        try {
            Class<?> screen = loader.loadClass("g6.Screen");
            StringBuilder log = (StringBuilder) loader.loadClass("g2.Log").getField("out").get(null);
            int before = log.length();
            screen.getMethod("run", screen).invoke(null, loader.loadClass("m6.Lambda").getConstructor().newInstance());
            lambdaTrace = log.substring(before);
        } catch (ReflectiveOperationException | LinkageError failed) {
            lambdaTrace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(lambdaTrace.startsWith("fill7;L;"),
            "a lambda made in a moved render that captures the pose moves with it, and its Gui.fill(pose, x) gets the graphics: " + lambdaTrace);

        // A lambda for the callback: its body moves to the graphics and the lambda is made for the new draw.
        List<Map<String, Object>> hudRuns = new ArrayList<>();
        Map<String, byte[]> hudJar = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("m6/Overlay.class", overlay())), "hud.jar", hudRuns));
        List<String> hudUnlinked = Json.array(hudRuns.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        classes.put("m6/Overlay", hudJar.get("m6/Overlay.class"));
        classes.put("g6/Hud", hudInterface());
        String hudTrace;
        try {
            StringBuilder log = (StringBuilder) loader.loadClass("g2.Log").getField("out").get(null);
            int before = log.length();
            Object hud = loader.loadClass("m6.Overlay").getMethod("make").invoke(null);
            Class<?> gfx = loader.loadClass("g6.Graphics");
            loader.loadClass("g6.Hud").getMethod("draw", gfx, int.class).invoke(hud, gfx.getConstructor().newInstance(), 7);
            hudTrace = log.substring(before);
        } catch (ReflectiveOperationException | LinkageError failed) {
            hudTrace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(hudUnlinked.isEmpty() && hudTrace.equals("push;H;"),
            "a lambda for a callback whose method now takes the graphics moves with it and runs with graphics.pose(): " + hudUnlinked + " " + hudTrace);
        List<Map<String, Object>> makerRuns = new ArrayList<>();
        Map<String, byte[]> makerJar = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("m6/Factory.class", factory())), "maker.jar", makerRuns));
        List<String> makerUnlinked = Json.array(makerRuns.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        classes.put("m6/Factory", makerJar.get("m6/Factory.class"));
        classes.put("g6/Maker", makerInterface());
        String makerTrace;
        try {
            StringBuilder log = (StringBuilder) loader.loadClass("g2.Log").getField("out").get(null);
            int before = log.length();
            Object maker = loader.loadClass("m6.Factory").getMethod("make").invoke(null);
            Class<?> key = loader.loadClass("g6.Key");
            loader.loadClass("g6.Maker").getMethod("make", key).invoke(maker, key.getConstructor(int.class).newInstance(65));
            makerTrace = log.substring(before);
        } catch (ReflectiveOperationException | LinkageError failed) {
            makerTrace = "failed: " + (failed.getCause() == null ? failed : failed.getCause());
        }
        JarPortTest.expect(makerUnlinked.isEmpty() && makerTrace.equals("M;"),
            "a lambda for a method whose argument became another type, which its body never reads, is made for the new method: " + makerUnlinked + " " + makerTrace);

        List<Map<String, Object>> stuckRuns = new ArrayList<>();
        port.port(JarPortTest.jar(Map.of("m6/Overlay.class", overlayReference())), "stuck.jar", stuckRuns);
        List<String> stuck = Json.array(stuckRuns.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(stuck.contains("lambda for a changed method: g6.Hud.draw(Lg6/Pose;I)V (in m6.Overlay)"),
            "a method reference to a public method, which cannot move, is reported instead of linking silently: " + stuck);

        List<Map<String, Object>> modelRuns = new ArrayList<>();
        port.port(JarPortTest.jar(Map.of("m6/MyModel.class", JarPortTest.real("m6/MyModel", "java/lang/Object", 0, new String[] { "g6/Model" },
            w -> JarPortTest.traced(w, "quads", "()V", "", m -> { })))), "model.jar", modelRuns);
        List<String> modelUnlinked = Json.array(modelRuns.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(modelUnlinked.equals(List.of("abstract method not written: g6.Model.extra()V (in m6.MyModel)")),
            "an abstract method the class left unwritten against the old release too is not the port's break; one the new release added is: " + modelUnlinked);
        List<Map<String, Object>> unreadRuns = new ArrayList<>();
        port.port(JarPortTest.jar(Map.of("m6/OddModel.class", JarPortTest.real("m6/OddModel", "java/lang/Object", 0, new String[] { "g6/Model" },
            w -> {
                JarPortTest.traced(w, "quads", "()V", "", m -> { });
                JarPortTest.traced(w, "method_4711", "()V", "", m -> { });
            }))), "odd.jar", unreadRuns);
        List<String> unread = Json.array(unreadRuns.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(unread.contains("abstract method not written: g6.Model.transforms()V (in m6.OddModel)"),
            "but one the class writes under an id its names were not read through (method_4711) did not load before either, and stays reported: " + unread);
        List<Map<String, Object>> forgeRuns = new ArrayList<>();
        port.port(JarPortTest.jar(Map.of("m6/FabricModel.class", JarPortTest.real("m6/FabricModel", "java/lang/Object", 0, new String[] { "g6/Model" },
            w -> JarPortTest.traced(w, "spin", "(Lnet/minecraft/class_1234;)V", "", m -> { })))), "forge.jar", forgeRuns);
        List<String> forgeUnlinked = Json.array(forgeRuns.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(forgeUnlinked.equals(List.of("built for another loader: every class names the game as another loader's build does")),
            "a jar whose every class names the game by intermediary is a Fabric build, which never loaded on NeoForge, and does not link: " + forgeUnlinked);
        List<Map<String, Object>> nestedRuns = new ArrayList<>();
        port.port(JarPortTest.jar(Map.of("META-INF/jars/fabric-part.jar", JarPortTest.jar(Map.of("m6/FabricModel.class", JarPortTest.real("m6/FabricModel", "java/lang/Object", 0,
            new String[] { "g6/Model" }, w -> JarPortTest.traced(w, "spin", "(Lnet/minecraft/class_1234;)V", "", m -> { })))),
            "META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\n[[mods]]\nmodId=\"m6\"\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "META-INF/jarjar/metadata.json", "{\"jars\": [{\"path\": \"META-INF/jars/fabric-part.jar\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8))), "both.jar", nestedRuns);
        List<String> nestedUnlinked = nestedRuns.stream().flatMap(r -> Json.array(r.get("unlinked")).stream()).map(String::valueOf).toList();
        JarPortTest.expect(nestedUnlinked.isEmpty(), "a jar nested for the other loader is never loaded here, so it holds nothing back: " + nestedUnlinked);

        // A mod class on the removed Part extends what the game's own subclasses of it extend now, not Graphics.
        List<Map<String, Object>> partRuns = new ArrayList<>();
        Map<String, byte[]> parts = JarPortTest.entries(port.port(JarPortTest.jar(Map.of("m6/Plain.class", writer("m6/Plain", "g6/Part").toByteArray())), "plain.jar", partRuns));
        List<String> plainUnlinked = Json.array(partRuns.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        String plainSuper = new ClassReader(parts.get("m6/Plain.class")).getSuperName();
        classes.put("m6/Plain", parts.get("m6/Plain.class"));
        String made;
        try {
            made = loader.loadClass("m6.Plain").getConstructor().newInstance().getClass().getSuperclass().getName();
        } catch (ReflectiveOperationException | LinkageError failed) {
            made = "failed: " + failed;
        }
        JarPortTest.expect(plainUnlinked.isEmpty() && plainSuper.equals("java/lang/Object") && made.equals("java.lang.Object"),
            "a class on a superclass the game removed extends what the game's own subclasses extend now, and runs: " + plainUnlinked + " " + plainSuper + " " + made);
        List<Map<String, Object>> ownRuns = new ArrayList<>();
        port.port(JarPortTest.jar(Map.of("m6/Panel.class", panel())), "panel.jar", ownRuns);
        List<String> panelUnlinked = Json.array(ownRuns.get(0).get("unlinked")).stream().map(String::valueOf).toList();
        JarPortTest.expect(panelUnlinked.equals(List.of("inherited from a removed superclass: g6.Part.own()V (in m6.Panel)")),
            "an instance method it inherited from that superclass, which what it extends now lacks, is reported: " + panelUnlinked);

        JarPortTest.expect(trace.endsWith("push;a;true;dx5;basedrag;true;"),
            "a method its family shows nothing for gets the conversion the game uses for that type elsewhere (pose()), a char comes from "
                + "the event's code point, and drag distances that moved behind the event are passed on: " + trace);
        JarPortTest.expect(trace.startsWith("back;fill7;push;fill8;fill1;r;A;basekey;true;back;fill7;push;fill8;fill1;r;child;A;basekey;true;"),
            "the game's new render and key reach the mod's code with graphics.pose() and event.code(), and its calls get the graphics and the event: " + trace);
    }

    static ClassWriter writer(String name, String sup) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String x, String y) {
                return "java/lang/Object";
            }
        };
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, sup, null);
        MethodVisitor init = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, sup, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        return w;
    }

    /**
     * m6.MyScreen, written for the old release: render(pose, x) calls back(pose) and Gui.fill(pose, x), pushes the pose
     * when x > 5, then fills with a local x + 1; key(k, s, m) logs A for 65 and returns super.key(k, s, m).
     */
    static byte[] myScreen() {
        ClassWriter w = writer("m6/MyScreen", "g6/Screen");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "render", "(" + POSE + "I)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "m6/MyScreen", "back", "(" + POSE + ")V", false);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitVarInsn(Opcodes.ILOAD, 2);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "g6/Gui", "fill", "(" + POSE + "I)V", false);
        Label skip = new Label();
        m.visitVarInsn(Opcodes.ILOAD, 2);
        m.visitInsn(Opcodes.ICONST_5);
        m.visitJumpInsn(Opcodes.IF_ICMPLE, skip);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Pose", "push", "()V", false);
        m.visitLabel(skip);
        m.visitVarInsn(Opcodes.ILOAD, 2);
        m.visitInsn(Opcodes.ICONST_1);
        m.visitInsn(Opcodes.IADD);
        m.visitVarInsn(Opcodes.ISTORE, 3);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitVarInsn(Opcodes.ILOAD, 3);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "g6/Gui", "fill", "(" + POSE + "I)V", false);
        // Gui.fill(pose, x > 5 ? 1 : 2): the pose crosses a branch on the stack.
        Label two = new Label(), joined = new Label();
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitVarInsn(Opcodes.ILOAD, 2);
        m.visitInsn(Opcodes.ICONST_5);
        m.visitJumpInsn(Opcodes.IF_ICMPLE, two);
        m.visitInsn(Opcodes.ICONST_1);
        m.visitJumpInsn(Opcodes.GOTO, joined);
        m.visitLabel(two);
        m.visitInsn(Opcodes.ICONST_2);
        m.visitLabel(joined);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "g6/Gui", "fill", "(" + POSE + "I)V", false);
        JarPortTest.append(m, "r;");
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        MethodVisitor k = w.visitMethod(Opcodes.ACC_PUBLIC, "key", "(III)Z", null, null);
        k.visitCode();
        Label notA = new Label();
        k.visitVarInsn(Opcodes.ILOAD, 1);
        k.visitIntInsn(Opcodes.BIPUSH, 65);
        k.visitJumpInsn(Opcodes.IF_ICMPNE, notA);
        JarPortTest.append(k, "A;");
        k.visitLabel(notA);
        k.visitVarInsn(Opcodes.ALOAD, 0);
        k.visitVarInsn(Opcodes.ILOAD, 1);
        k.visitVarInsn(Opcodes.ILOAD, 2);
        k.visitVarInsn(Opcodes.ILOAD, 3);
        k.visitMethodInsn(Opcodes.INVOKESPECIAL, "g6/Screen", "key", "(III)Z", false);
        k.visitInsn(Opcodes.IRETURN);
        k.visitMaxs(0, 0);
        k.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /**
     * m6.Extra, for the old release: hover(pose) pushes it; chr(c, mods) logs a for 'a'; drag(x, y, dx, dy) logs dx5 when dx
     * is 5 and returns super.drag(x, y, dx, dy).
     */
    static byte[] extra() {
        ClassWriter w = writer("m6/Extra", "g6/Screen");
        MethodVisitor h = w.visitMethod(Opcodes.ACC_PUBLIC, "hover", "(" + POSE + ")V", null, null);
        h.visitCode();
        h.visitVarInsn(Opcodes.ALOAD, 1);
        h.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Pose", "push", "()V", false);
        h.visitInsn(Opcodes.RETURN);
        h.visitMaxs(0, 0);
        h.visitEnd();
        MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "chr", "(CI)Z", null, null);
        c.visitCode();
        Label notA = new Label();
        c.visitVarInsn(Opcodes.ILOAD, 1);
        c.visitIntInsn(Opcodes.BIPUSH, 97);
        c.visitJumpInsn(Opcodes.IF_ICMPNE, notA);
        JarPortTest.append(c, "a;");
        c.visitLabel(notA);
        c.visitInsn(Opcodes.ICONST_1);
        c.visitInsn(Opcodes.IRETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();
        MethodVisitor d = w.visitMethod(Opcodes.ACC_PUBLIC, "drag", "(IIII)Z", null, null);
        d.visitCode();
        Label not5 = new Label();
        d.visitVarInsn(Opcodes.ILOAD, 3);
        d.visitInsn(Opcodes.ICONST_5);
        d.visitJumpInsn(Opcodes.IF_ICMPNE, not5);
        JarPortTest.append(d, "dx5;");
        d.visitLabel(not5);
        d.visitVarInsn(Opcodes.ALOAD, 0);
        for (int i = 1; i <= 4; i++) {
            d.visitVarInsn(Opcodes.ILOAD, i);
        }
        d.visitMethodInsn(Opcodes.INVOKESPECIAL, "g6/Screen", "drag", "(IIII)Z", false);
        d.visitInsn(Opcodes.IRETURN);
        d.visitMaxs(0, 0);
        d.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** m6.Scroller.scroll(x, y, d) returns super.scroll(x, y, d); the game's scroll took a fourth int. */
    static byte[] scroller() {
        ClassWriter w = writer("m6/Scroller", "g6/Screen");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "scroll", "(III)Z", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        for (int i = 1; i <= 3; i++) {
            m.visitVarInsn(Opcodes.ILOAD, i);
        }
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "g6/Screen", "scroll", "(III)Z", false);
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** m6.UsesLib.use() calls g7.Bundled.run() and g6.Dropped.run(). */
    static byte[] usesLib() {
        ClassWriter w = writer("m6/UsesLib", "java/lang/Object");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "use", "()V", null, null);
        m.visitCode();
        for (String c : List.of("g7/Bundled", "g6/Dropped")) {
            m.visitTypeInsn(Opcodes.NEW, c);
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, c, "<init>", "()V", false);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, c, "run", "()V", false);
        }
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** m6.Caller.call() renders a MyScreen with a pose it made itself: no graphics are at hand there. */
    static byte[] caller() {
        ClassWriter w = writer("m6/Caller", "java/lang/Object");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call", "()V", null, null);
        m.visitCode();
        m.visitTypeInsn(Opcodes.NEW, "m6/MyScreen");
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "m6/MyScreen", "<init>", "()V", false);
        m.visitTypeInsn(Opcodes.NEW, "g6/Pose");
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "g6/Pose", "<init>", "()V", false);
        m.visitInsn(Opcodes.ICONST_1);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "m6/MyScreen", "render", "(" + POSE + "I)V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** m6.MyChild extends MyScreen: render(pose, x) calls super.render(pose, x), then logs child. */
    static byte[] myChild() {
        ClassWriter w = writer("m6/MyChild", "m6/MyScreen");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "render", "(" + POSE + "I)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitVarInsn(Opcodes.ILOAD, 2);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "m6/MyScreen", "render", "(" + POSE + "I)V", false);
        JarPortTest.append(m, "child;");
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** m6.Other: render fills with a pose it made itself, and a static helper fills with a pose it was handed. */
    static byte[] other() {
        ClassWriter w = writer("m6/Other", "g6/Screen");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "render", "(" + POSE + "I)V", null, null);
        m.visitCode();
        m.visitTypeInsn(Opcodes.NEW, "g6/Pose");
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "g6/Pose", "<init>", "()V", false);
        m.visitVarInsn(Opcodes.ILOAD, 2);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "g6/Gui", "fill", "(" + POSE + "I)V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        MethodVisitor h = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "side", "(" + POSE + ")V", null, null);
        h.visitCode();
        h.visitVarInsn(Opcodes.ALOAD, 0);
        h.visitInsn(Opcodes.ICONST_1);
        h.visitMethodInsn(Opcodes.INVOKESTATIC, "g6/Gui", "fill", "(" + POSE + "I)V", false);
        h.visitInsn(Opcodes.RETURN);
        h.visitMaxs(0, 0);
        h.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /**
     * m6.ScreenMixin: @Inject into tick() at its call of old(), which the new tick no longer makes, and at kept(), which it
     * does; and into render(pose, x) with a handler that pushes the pose.
     */
    static byte[] mixin() {
        ClassWriter w = writer("m6/ScreenMixin", "java/lang/Object");
        AnnotationVisitor target = w.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor value = target.visitArray("value");
        value.visit(null, Type.getObjectType("g6/Screen"));
        value.visitEnd();
        target.visitEnd();
        JarPortFixesTest.handler(w, JarPortFixesTest.INJECT, "onOld", "(" + CI + ")V", "tick()V", "INVOKE", "Lg6/Screen;old()V", null);
        JarPortFixesTest.handler(w, JarPortFixesTest.INJECT, "onKept", "(" + CI + ")V", "tick()V", "INVOKE", "Lg6/Screen;kept()V", null);
        MethodVisitor store = w.visitMethod(Opcodes.ACC_PRIVATE, "onStore", "(I)I", null, null);
        AnnotationVisitor modify = store.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/ModifyVariable;", false);
        AnnotationVisitor into = modify.visitArray("method");
        into.visit(null, "tick()V");
        into.visitEnd();
        AnnotationVisitor point = modify.visitAnnotation("at", "Lorg/spongepowered/asm/mixin/injection/At;");
        point.visit("value", "STORE");
        point.visit("ordinal", 1);
        point.visitEnd();
        modify.visitEnd();
        store.visitCode();
        store.visitVarInsn(Opcodes.ILOAD, 1);
        store.visitInsn(Opcodes.IRETURN);
        store.visitMaxs(0, 0);
        store.visitEnd();
        JarPortFixesTest.handler(w, JarPortFixesTest.INJECT, "onRender", "(" + POSE + "I" + CI + ")V", "render(" + POSE + "I)V", "HEAD", null, m -> {
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Pose", "push", "()V", false);
        });
        w.visitEnd();
        return w.toByteArray();
    }

    /** m6.ByName: an @Inject into the method a regular expression names (tick), at its call of old(), which the new tick no longer makes. */
    static byte[] byName() {
        ClassWriter w = writer("m6/ByName", "java/lang/Object");
        AnnotationVisitor target = w.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor value = target.visitArray("value");
        value.visit(null, Type.getObjectType("g6/Screen"));
        value.visitEnd();
        target.visitEnd();
        JarPortFixesTest.handler(w, JarPortFixesTest.INJECT, "onOld", "(" + CI + ")V", "/^(tick|method_1)$/", "INVOKE", "Lg6/Screen;old()V", null);
        w.visitEnd();
        return w.toByteArray();
    }

    /** m6.Lambda, for the old release: render(pose, x) runs () -> Gui.fill(pose, x), then logs L. */
    static byte[] lambda() {
        ClassWriter w = writer("m6/Lambda", "g6/Screen");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "render", "(" + POSE + "I)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitVarInsn(Opcodes.ILOAD, 2);
        Handle factory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
        m.visitInvokeDynamicInsn("run", "(" + POSE + "I)Ljava/lang/Runnable;", factory, Type.getType("()V"),
            new Handle(Opcodes.H_INVOKESTATIC, "m6/Lambda", "lambda$render$0", "(" + POSE + "I)V", false), Type.getType("()V"));
        m.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true);
        JarPortTest.append(m, "L;");
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        MethodVisitor body = w.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "lambda$render$0", "(" + POSE + "I)V", null, null);
        body.visitCode();
        body.visitVarInsn(Opcodes.ALOAD, 0);
        body.visitVarInsn(Opcodes.ILOAD, 1);
        body.visitMethodInsn(Opcodes.INVOKESTATIC, "g6/Gui", "fill", "(" + POSE + "I)V", false);
        body.visitInsn(Opcodes.RETURN);
        body.visitMaxs(0, 0);
        body.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** m6.ByVar: @ModifyVariable handlers into Screen.render at HEAD, onX(int) taking only the int and onPosed(int, pose) also capturing the pose. */
    static byte[] byVar() {
        ClassWriter w = writer("m6/ByVar", "java/lang/Object");
        AnnotationVisitor target = w.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor value = target.visitArray("value");
        value.visit(null, Type.getObjectType("g6/Screen"));
        value.visitEnd();
        target.visitEnd();
        String modify = "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";
        JarPortFixesTest.handler(w, modify, "onX", "(I)I", "render(" + POSE + "I)V", "HEAD", null, m -> {
            m.visitVarInsn(Opcodes.ILOAD, 1);
            m.visitInsn(Opcodes.IRETURN);
        });
        JarPortFixesTest.handler(w, modify, "onPosed", "(I" + POSE + ")I", "render(" + POSE + "I)V", "HEAD", null, m -> {
            m.visitVarInsn(Opcodes.ILOAD, 1);
            m.visitInsn(Opcodes.IRETURN);
        });
        w.visitEnd();
        return w.toByteArray();
    }

    /** m6.MyRow extends g6.Row: render(pose, x) calls screen.tip(pose, x) on its screen field. */
    static byte[] myRow() {
        ClassWriter w = writer("m6/MyRow", "g6/Row");
        w.visitField(Opcodes.ACC_PUBLIC, "screen", "Lg6/Screen;", null, null).visitEnd();
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "render", "(" + POSE + "I)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, "m6/MyRow", "screen", "Lg6/Screen;");
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitVarInsn(Opcodes.ILOAD, 2);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Screen", "tip", "(" + POSE + "I)V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** g6.Maker in 2.0: make(key). */
    static byte[] makerInterface() {
        ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "g6/Maker", null, "java/lang/Object", null);
        w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "make", "(" + KEY + ")V", null, null).visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** m6.Factory, for 1.0: make() gives the lambda pose -> log M, which never reads the pose. */
    static byte[] factory() {
        ClassWriter w = writer("m6/Factory", "java/lang/Object");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "()Lg6/Maker;", null, null);
        m.visitCode();
        m.visitInvokeDynamicInsn("make", "()Lg6/Maker;", FACTORY, Type.getType("(" + POSE + ")V"),
            new Handle(Opcodes.H_INVOKESTATIC, "m6/Factory", "lambda$make$0", "(" + POSE + ")V", false), Type.getType("(" + POSE + ")V"));
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        MethodVisitor l = w.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "lambda$make$0", "(" + POSE + ")V", null, null);
        l.visitCode();
        JarPortTest.append(l, "M;");
        l.visitInsn(Opcodes.RETURN);
        l.visitMaxs(0, 0);
        l.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** g6.Hud in 2.0: draw(graphics, x). */
    static byte[] hudInterface() {
        ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "g6/Hud", null, "java/lang/Object", null);
        w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "draw", "(" + GFX + "I)V", null, null).visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    static final Handle FACTORY = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);

    /** m6.Overlay, for 1.0: make() gives the lambda (pose, x) -> { pose.push(); log H }. */
    static byte[] overlay() {
        return overlay("lambda$make$0", Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC);
    }

    /** The same made from overlay::paint, a public instance method another class may override. */
    static byte[] overlayReference() {
        return overlay("paint", Opcodes.ACC_PUBLIC);
    }

    static byte[] overlay(String body, int access) {
        ClassWriter w = writer("m6/Overlay", "java/lang/Object");
        boolean bound = (access & Opcodes.ACC_STATIC) == 0;
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", bound ? "(Lm6/Overlay;)Lg6/Hud;" : "()Lg6/Hud;", null, null);
        m.visitCode();
        if (bound) {
            m.visitVarInsn(Opcodes.ALOAD, 0);
        }
        m.visitInvokeDynamicInsn("draw", bound ? "(Lm6/Overlay;)Lg6/Hud;" : "()Lg6/Hud;", FACTORY, Type.getType("(" + POSE + "I)V"),
            new Handle(bound ? Opcodes.H_INVOKEVIRTUAL : Opcodes.H_INVOKESTATIC, "m6/Overlay", body, "(" + POSE + "I)V", false), Type.getType("(" + POSE + "I)V"));
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        MethodVisitor l = w.visitMethod(access, body, "(" + POSE + "I)V", null, null);
        l.visitCode();
        l.visitVarInsn(Opcodes.ALOAD, (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0);
        l.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Pose", "push", "()V", false);
        JarPortTest.append(l, "H;");
        l.visitInsn(Opcodes.RETURN);
        l.visitMaxs(0, 0);
        l.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** m6.Panel extends g6.Part, which 2.0 removes: use() calls own(), which it inherited. */
    static byte[] panel() {
        ClassWriter w = writer("m6/Panel", "g6/Part");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "use", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "m6/Panel", "own", "()V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** m6.LocAccess: an accessor mixin interface applied to g6.Loc. */
    static byte[] locAccess() {
        ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "m6/LocAccess", null, "java/lang/Object", null);
        AnnotationVisitor target = w.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor value = target.visitArray("value");
        value.visit(null, Type.getObjectType("g6/Loc"));
        value.visitEnd();
        target.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** A mixin into target whose method casts its own this to type: ((Type) (Object) this). */
    static byte[] selfCast(String name, String target, String type) {
        ClassWriter w = writer(name, "java/lang/Object");
        AnnotationVisitor mixin = w.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor value = mixin.visitArray("value");
        value.visit(null, Type.getObjectType(target));
        value.visitEnd();
        mixin.visitEnd();
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PRIVATE, "use", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitTypeInsn(Opcodes.CHECKCAST, type);
        m.visitInsn(Opcodes.POP);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(1, 1);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** g6.Title, the game's own screen, in one release: it pushes its pose and answers key 256. */
    static byte[] title(boolean old) {
        ClassWriter w = writer("g6/Title", "g6/Screen");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "render", old ? "(" + POSE + "I)V" : "(" + GFX + "I)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 1);
        if (!old) {
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Graphics", "pose", "()" + POSE, false);
        }
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Pose", "push", "()V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        MethodVisitor k = w.visitMethod(Opcodes.ACC_PUBLIC, "key", old ? "(III)Z" : "(" + KEY + ")Z", null, null);
        k.visitCode();
        Label no = new Label();
        if (old) {
            k.visitVarInsn(Opcodes.ILOAD, 1);
        } else {
            k.visitVarInsn(Opcodes.ALOAD, 1);
            k.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Key", "code", "()I", false);
        }
        k.visitIntInsn(Opcodes.SIPUSH, 256);
        k.visitJumpInsn(Opcodes.IF_ICMPNE, no);
        k.visitInsn(Opcodes.ICONST_1);
        k.visitInsn(Opcodes.IRETURN);
        k.visitLabel(no);
        k.visitInsn(Opcodes.ICONST_0);
        k.visitInsn(Opcodes.IRETURN);
        k.visitMaxs(0, 0);
        k.visitEnd();
        // chr: the old char compared with 'a', the new event's code point as a char; drag: x and dx must be positive.
        MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "chr", old ? "(CI)Z" : "(" + KEY + ")Z", null, null);
        c.visitCode();
        Label notA = new Label();
        if (old) {
            c.visitVarInsn(Opcodes.ILOAD, 1);
        } else {
            c.visitVarInsn(Opcodes.ALOAD, 1);
            c.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Key", "code", "()I", false);
            c.visitInsn(Opcodes.I2C);
        }
        c.visitIntInsn(Opcodes.BIPUSH, 97);
        c.visitJumpInsn(Opcodes.IF_ICMPNE, notA);
        c.visitInsn(Opcodes.ICONST_1);
        c.visitInsn(Opcodes.IRETURN);
        c.visitLabel(notA);
        c.visitInsn(Opcodes.ICONST_0);
        c.visitInsn(Opcodes.IRETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();
        MethodVisitor d = w.visitMethod(Opcodes.ACC_PUBLIC, "drag", old ? "(IIII)Z" : "(" + KEY + "II)Z", null, null);
        d.visitCode();
        Label stop = new Label();
        if (old) {
            d.visitVarInsn(Opcodes.ILOAD, 1);
        } else {
            d.visitVarInsn(Opcodes.ALOAD, 1);
            d.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Key", "code", "()I", false);
        }
        d.visitJumpInsn(Opcodes.IFLE, stop);
        d.visitVarInsn(Opcodes.ILOAD, old ? 3 : 2);
        d.visitJumpInsn(Opcodes.IFLE, stop);
        d.visitInsn(Opcodes.ICONST_1);
        d.visitInsn(Opcodes.IRETURN);
        d.visitLabel(stop);
        d.visitInsn(Opcodes.ICONST_0);
        d.visitInsn(Opcodes.IRETURN);
        d.visitMaxs(0, 0);
        d.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /**
     * g6.Screen in one release. The new one is the real class the test runs: render logs base, back logs back, key logs
     * basekey and returns true, and run(screen) renders with a new Graphics and 7 and logs what key(new Key(65)) says.
     * tick() calls old() and kept() in the old release and only kept() in the new one.
     */
    static byte[] screen(boolean old) {
        ClassWriter w = writer("g6/Screen", "java/lang/Object");
        String p = old ? POSE : GFX;
        JarPortTest.traced(w, "render", "(" + p + "I)V", "base;", m -> { });
        JarPortTest.traced(w, "back", "(" + p + ")V", "back;", m -> { });
        JarPortTest.traced(w, "key", old ? "(III)Z" : "(" + KEY + ")Z", "basekey;", m -> { });
        JarPortTest.traced(w, "old", "()V", "old;", m -> { });
        JarPortTest.traced(w, "kept", "()V", "kept;", m -> { });
        JarPortTest.traced(w, "hover", "(" + p + ")V", "basehover;", m -> { });
        JarPortTest.traced(w, "chr", old ? "(CI)Z" : "(" + KEY + ")Z", "basechr;", m -> { });
        JarPortTest.traced(w, "drag", old ? "(IIII)Z" : "(" + KEY + "II)Z", "basedrag;", m -> { });
        JarPortTest.traced(w, "tick", "()V", "tick;", m -> {
            // An int local stored twice in the old tick, once in the new one.
            for (int i = 0; i < (old ? 2 : 1); i++) {
                m.visitInsn(Opcodes.ICONST_1);
                m.visitVarInsn(Opcodes.ISTORE, 1);
            }
            if (old) {
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Screen", "old", "()V", false);
            }
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Screen", "kept", "()V", false);
        });
        if (!old) {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(Lg6/Screen;)V", null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitTypeInsn(Opcodes.NEW, "g6/Graphics");
            m.visitInsn(Opcodes.DUP);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "g6/Graphics", "<init>", "()V", false);
            m.visitIntInsn(Opcodes.BIPUSH, 7);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Screen", "render", "(" + GFX + "I)V", false);
            m.visitFieldInsn(Opcodes.GETSTATIC, "g2/Log", "out", "Ljava/lang/StringBuilder;");
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitTypeInsn(Opcodes.NEW, "g6/Key");
            m.visitInsn(Opcodes.DUP);
            m.visitIntInsn(Opcodes.BIPUSH, 65);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, "g6/Key", "<init>", "(I)V", false);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Screen", "key", "(" + KEY + ")Z", false);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Z)Ljava/lang/StringBuilder;", false);
            m.visitInsn(Opcodes.POP);
            JarPortTest.append(m, ";");
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
            // run2(screen): hover with a new Graphics, then what chr(new Key(97)) and drag(new Key(1), 5, 6) say.
            MethodVisitor r = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run2", "(Lg6/Screen;)V", null, null);
            r.visitCode();
            r.visitVarInsn(Opcodes.ALOAD, 0);
            r.visitTypeInsn(Opcodes.NEW, "g6/Graphics");
            r.visitInsn(Opcodes.DUP);
            r.visitMethodInsn(Opcodes.INVOKESPECIAL, "g6/Graphics", "<init>", "()V", false);
            r.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Screen", "hover", "(" + GFX + ")V", false);
            for (String call : List.of("chr", "drag")) {
                r.visitFieldInsn(Opcodes.GETSTATIC, "g2/Log", "out", "Ljava/lang/StringBuilder;");
                r.visitVarInsn(Opcodes.ALOAD, 0);
                r.visitTypeInsn(Opcodes.NEW, "g6/Key");
                r.visitInsn(Opcodes.DUP);
                r.visitIntInsn(Opcodes.BIPUSH, call.equals("chr") ? 97 : 1);
                r.visitMethodInsn(Opcodes.INVOKESPECIAL, "g6/Key", "<init>", "(I)V", false);
                if (call.equals("drag")) {
                    r.visitInsn(Opcodes.ICONST_5);
                    r.visitIntInsn(Opcodes.BIPUSH, 6);
                }
                r.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "g6/Screen", call, call.equals("chr") ? "(" + KEY + ")Z" : "(" + KEY + "II)Z", false);
                r.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Z)Ljava/lang/StringBuilder;", false);
                r.visitInsn(Opcodes.POP);
                JarPortTest.append(r, ";");
            }
            r.visitInsn(Opcodes.RETURN);
            r.visitMaxs(0, 0);
            r.visitEnd();
        }
        w.visitEnd();
        return w.toByteArray();
    }

    /** g6.Graphics: its own Pose from pose(), and fill(x) logging fill and x. */
    static byte[] graphics() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "g6/Graphics", null, "java/lang/Object", null);
        w.visitField(Opcodes.ACC_FINAL, "pose", POSE, null, null).visitEnd();
        MethodVisitor init = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitTypeInsn(Opcodes.NEW, "g6/Pose");
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "g6/Pose", "<init>", "()V", false);
        init.visitFieldInsn(Opcodes.PUTFIELD, "g6/Graphics", "pose", POSE);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor pose = w.visitMethod(Opcodes.ACC_PUBLIC, "pose", "()" + POSE, null, null);
        pose.visitCode();
        pose.visitVarInsn(Opcodes.ALOAD, 0);
        pose.visitFieldInsn(Opcodes.GETFIELD, "g6/Graphics", "pose", POSE);
        pose.visitInsn(Opcodes.ARETURN);
        pose.visitMaxs(0, 0);
        pose.visitEnd();
        MethodVisitor fill = w.visitMethod(Opcodes.ACC_PUBLIC, "fill", "(I)V", null, null);
        fill.visitCode();
        JarPortTest.append(fill, "fill");
        fill.visitFieldInsn(Opcodes.GETSTATIC, "g2/Log", "out", "Ljava/lang/StringBuilder;");
        fill.visitVarInsn(Opcodes.ILOAD, 1);
        fill.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
        fill.visitInsn(Opcodes.POP);
        JarPortTest.append(fill, ";");
        fill.visitInsn(Opcodes.RETURN);
        fill.visitMaxs(0, 0);
        fill.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** g6.Key(code): code() gives it back, scan() gives 0. */
    static byte[] key() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "g6/Key", null, "java/lang/Object", null);
        w.visitField(Opcodes.ACC_FINAL, "code", "I", null, null).visitEnd();
        MethodVisitor init = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ILOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, "g6/Key", "code", "I");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor code = w.visitMethod(Opcodes.ACC_PUBLIC, "code", "()I", null, null);
        code.visitCode();
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitFieldInsn(Opcodes.GETFIELD, "g6/Key", "code", "I");
        code.visitInsn(Opcodes.IRETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
        MethodVisitor scan = w.visitMethod(Opcodes.ACC_PUBLIC, "scan", "()I", null, null);
        scan.visitCode();
        scan.visitInsn(Opcodes.ICONST_0);
        scan.visitInsn(Opcodes.IRETURN);
        scan.visitMaxs(0, 0);
        scan.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }
}
