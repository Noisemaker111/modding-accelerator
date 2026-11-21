import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.objectweb.asm.Opcodes;

/**
 * A source port writes a jar port's answers at the places javac resolved: a call to a member the new release dropped
 * gets the route map's learned replacement with the call's own receiver and arguments (inside another such call too),
 * a member the new release kept is left alone, and an answer that does not compile against the new release, as JarPort
 * judges it, is never written.
 *
 *   java -cp OUT:asm SourceAnswersTest SCRATCH
 */
public class SourceAnswersTest {

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of(args[0]).resolve("source-answers"));
        GameDiff.Snap a = new GameDiff.Snap("1.0"), b = new GameDiff.Snap("2.0");
        for (GameDiff.Snap s : List.of(a, b)) {
            GameDiff.Cls tag = new GameDiff.Cls("g/Tag", Opcodes.ACC_PUBLIC, "java/lang/Object", List.of(), false);
            tag.fields.put("SIZE", new GameDiff.Fld(tag, "SIZE", "I", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
            String getter = s == a ? "getInt(Ljava/lang/String;)I" : "getIntOr(Ljava/lang/String;I)I";
            tag.methods.put(getter, new GameDiff.Meth(tag, getter.substring(0, getter.indexOf('(')), getter.substring(getter.indexOf('(')), Opcodes.ACC_PUBLIC));
            GameDiff.Cls old = new GameDiff.Cls("g/Old", Opcodes.ACC_PUBLIC, "java/lang/Object", List.of(), false);
            if (s == a) {
                old.methods.put("make()V", new GameDiff.Meth(old, "make", "()V", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
            }
            s.classes.put(tag.name, tag);
            s.classes.put(old.name, old);
        }
        Path map = scratch.resolve("route.json");
        Files.writeString(map, """
            {"classes": [], "methods": [], "fields": [], "rewrites": [],
             "methodsRemoved": [{"owner": "g.Tag", "from": "getInt", "desc": "(Ljava/lang/String;)I"}, {"owner": "g.Old", "from": "make", "desc": "()V"}],
             "learned": [{"step": "1.0 -> 2.0", "owner": "g.Tag", "from": "getInt", "desc": "(Ljava/lang/String;)I", "field": false, "linked": false, "expr": "$0.getIntOr($1, 0)", "votes": 3, "of": 3},
                         {"step": "1.0 -> 2.0", "owner": "g.Old", "from": "make", "desc": "()V", "field": false, "linked": false, "expr": "g.Missing.make()", "votes": 2, "of": 2}]}
            """);
        // The old release's classes, which the mod's own compile was given.
        Path game = scratch.resolve("game-src"), classes = scratch.resolve("game-classes");
        Files.createDirectories(game.resolve("g"));
        Files.writeString(game.resolve("g/Tag.java"), "package g; public class Tag { public static int SIZE; public int getInt(String k) { return 0; } }");
        Files.writeString(game.resolve("g/Old.java"), "package g; public class Old { public static void make() {} }");
        expect(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), game.resolve("g/Tag.java").toString(), game.resolve("g/Old.java").toString()) == 0,
            "the stand-in old release compiles");
        Path mod = scratch.resolve("mod/m/User.java");
        Files.createDirectories(mod.getParent());
        Files.writeString(mod, """
            package m;
            class User {
                int read(g.Tag tag) { return tag.getInt("count") + g.Tag.SIZE; }
                int twice(g.Tag tag) { return Math.max(tag.getInt("a" + tag.getInt("b")), 1); }
                void build() { g.Old.make(); }
            }
            """);
        SourceAnswers answers = new SourceAnswers();
        answers.port = new JarPort(a, b, map);
        answers.load(map, null, "1.0", "2.0", "");
        Map<String, Object> report = answers.apply(List.of(mod), List.of(classes), false);
        String ported = Files.readString(mod, StandardCharsets.UTF_8);
        expect(ported.contains("return tag.getIntOr(\"count\", 0) + g.Tag.SIZE;"), "a call to a dropped member gets the learned answer with its own receiver and argument; a kept field stays: " + ported);
        expect(ported.contains("Math.max(tag.getIntOr(\"a\" + tag.getIntOr(\"b\", 0), 0), 1)"), "a use inside another use is written as its answer there too: " + ported);
        expect(ported.contains("g.Old.make();"), "an answer that does not compile against the new release is not written: " + ported);
        expect(Integer.valueOf(3).equals(report.get("replaced")), "three uses replaced: " + report);
        System.out.println("source-answers: 4 checks passed");
    }

    static void expect(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("FAILED: " + what);
        }
    }
}
