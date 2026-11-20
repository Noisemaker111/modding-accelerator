import java.nio.file.*;
import java.util.*;

/** Modern Java lowered to the legacy level: records, patterns, switches, text blocks, sealed, var. */
public final class DesugarTests {
    static int count;

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    static Path scratch;

    static String lower(String name, String source) throws Exception {
        Path file = scratch.resolve(name);
        Files.writeString(file, source);
        return Desugar.desugarFile(file, List.of());
    }

    static void recordChecks() throws Exception {
        String simple = lower(
            "Point.java",
            "public record Point(int x, int y) {\n}\n"
        );
        check(
            simple.equals(
                "public final class Point{private final int x;private final int y;public Point(int x, int y){this.x = x;this.y = y;}" +
                "public int x(){return x;}public int y(){return y;}" +
                "public boolean equals(Object o){if(!(o instanceof Point))return false;Point other=(Point)o;return java.util.Objects.equals(x,other.x)&&java.util.Objects.equals(y,other.y);}" +
                "public int hashCode(){return java.util.Objects.hash(x,y);}" +
                "public String toString(){return \"Point(x=\"+x+\",y=\"+y+\")\";}\n}\n"
            ),
            "a plain record becomes a final class with fields, constructor, accessors, and value semantics"
        );
        check(
            lower("Point.java", simple).equals(simple),
            "a lowered record is stable: a second pass changes nothing"
        );
        String generic = lower(
            "Pair.java",
            "public record Pair<K, V>(K first, V second) implements java.util.Map.Entry<K, V> {\n" +
            "    public K getKey() { return first; }\n" +
            "    public V getValue() { return second; }\n" +
            "    public V setValue(V value) { throw new UnsupportedOperationException(); }\n}\n"
        );
        check(
            generic.contains("public final class Pair<K,V> implements java.util.Map.Entry<K, V>{") &&
            generic.contains("Pair<?,?> other=(Pair<?,?>)o;"),
            "a generic record keeps its type parameters and implements clause"
        );
        String compact = lower(
            "Clamp.java",
            "public record Clamp(int min, int max) {\n    public Clamp {\n        if (min > max) throw new IllegalArgumentException();\n    }\n}\n"
        );
        check(
            compact.contains("public Clamp(int min, int max){") &&
            compact.contains("if (min > max) throw new IllegalArgumentException();") &&
            compact.contains("this.min = min;this.max = max;}") &&
            !compact.contains("public Clamp {"),
            "a compact constructor becomes the canonical constructor with its checks first"
        );
        String explicit = lower(
            "Expl.java",
            "public record Expl(int a) {\n    public Expl(int a) {\n        this.a = a * 2;\n    }\n}\n"
        );
        check(
            explicit.contains("public final class Expl{private final int a;") &&
            explicit.contains("public Expl(int a) {") &&
            explicit.contains("this.a = a * 2;") &&
            !explicit.contains("record Expl"),
            "an explicit canonical constructor is kept while the record still lowers"
        );
        check(
            lower("Expl.java", explicit).equals(explicit),
            "a lowered explicit-canonical record is stable"
        );
        String overload = lower(
            "Over.java",
            "public record Over(String s) {\n    public Over(String s, int n) {\n        this(s + n);\n    }\n}\n"
        );
        check(
            overload.contains("public final class Over{") &&
            overload.contains("public Over(String s){this.s = s;}") &&
            overload.contains("public Over(String s, int n) {"),
            "an overload stays while the canonical constructor is synthesized"
        );
        check(
            lower("Over.java", overload).equals(overload),
            "a lowered record with an overload is stable"
        );
        String statics = lower(
            "Codec.java",
            "public record Codec(String id) {\n    public static final Codec UNIT = new Codec(\"unit\");\n}\n"
        );
        check(
            statics.contains("public Codec(String id){this.id = id;}") &&
            statics.contains("public static final Codec UNIT = new Codec(\"unit\");") &&
            !statics.contains("Codec UNIT){"),
            "a static field stays a member instead of becoming a component"
        );
        String pkg = lower(
            "Pkg.java",
            "record Pkg(String id) {\n    void run() {\n    }\n}\n"
        );
        check(
            pkg.contains("final class Pkg{private final String id;") &&
            pkg.contains("Pkg(String id){this.id = id;}") &&
            !pkg.contains("record Pkg"),
            "a package-private record lowers with a package-private constructor"
        );
    }

    static void patternChecks() throws Exception {
        String guarded = lower(
            "Guard.java",
            "class Guard {\n    int f(Object o) {\n        if (o instanceof String s) {\n            return s.length();\n        }\n        return -1;\n    }\n}\n"
        );
        check(
            guarded.contains("if (o instanceof String) {") && guarded.contains("return ((String)o).length();"),
            "a guarded pattern instanceof becomes a test plus a cast"
        );
        check(
            lower("Guard.java", guarded).equals(guarded),
            "a lowered instanceof is stable"
        );
        String disjunction = "class Disj {\n    boolean f(Object o) {\n        return o instanceof String s || s.isEmpty();\n    }\n}\n";
        check(
            lower("Disj.java", disjunction).equals(disjunction),
            "an || chain is left alone: the binding does not cover every arm"
        );
        String shadow = "class Shadow {\n    int f(Object o) {\n        if (o instanceof String s) {\n            String s = \"x\";\n            return s.length();\n        }\n        return -1;\n    }\n}\n";
        check(
            lower("Shadow.java", shadow).equals(shadow),
            "a scope that redeclares the name is left alone instead of miscasting"
        );
        String chained = lower(
            "Chain.java",
            "class Chain {\n    int f(Object a, Object b) {\n        if (a instanceof String s && s.length() > 0 && b instanceof Integer n) {\n            return s.length() + n;\n        }\n        return -1;\n    }\n}\n"
        );
        check(
            chained.contains("if (a instanceof String && ((String)a).length() > 0 && b instanceof Integer) {") &&
            chained.contains("return ((String)a).length() + ((Integer)b);"),
            "an && chain binds each pattern from its own test onward"
        );
        String bare = lower(
            "Bare.java",
            "class Bare {\n    Object f(Object ctx) {\n        if (ctx instanceof String p) return p;\n        return null;\n    }\n}\n"
        );
        check(
            bare.contains("if (ctx instanceof String) return ((String)ctx);"),
            "an unbraced return of the binding becomes a return of the cast"
        );
        String orHeaded = lower(
            "OrHead.java",
            "class OrHead {\n    boolean f(Object other) {\n        return this == other || other instanceof String s && s.isEmpty();\n    }\n}\n"
        );
        check(
            orHeaded.contains("other instanceof String && ((String)other).isEmpty();"),
            "an ||-headed && chain lowers the guarded remainder"
        );
        check(
            lower("OrHead.java", orHeaded).equals(orHeaded),
            "a lowered ||-headed chain is stable"
        );
        String field = lower(
            "Field.java",
            "class Field {\n    int target;\n    int f(Object o) {\n        if (o instanceof String target) {\n            return this.target;\n        }\n        return -1;\n    }\n}\n"
        );
        check(
            field.contains("if (o instanceof String) {") && field.contains("return this.target;"),
            "a field select keeps its qualifier instead of taking the cast"
        );
    }

    static void switchChecks() throws Exception {
        String assigned = lower(
            "Assigned.java",
            "class Assigned {\n    int f(int n) {\n        int x = switch (n) {\n            case 0 -> 1;\n            default -> 2;\n        };\n        return x;\n    }\n}\n"
        );
        check(
            assigned.contains("int x=0;switch(n){case 0:x=1;break;default:x=2;break;};"),
            "an assigned switch expression becomes a pre-initialized variable plus a statement switch"
        );
        check(
            lower("Assigned.java", assigned).equals(assigned),
            "a lowered switch is stable"
        );
        String returned = lower(
            "Returned.java",
            "class Returned {\n    int f(int n) {\n        return switch (n) {\n            case 0 -> 1;\n            default -> 2;\n        };\n    }\n}\n"
        );
        check(
            returned.contains("int _desugarsw0=0;switch(n){case 0:_desugarsw0=1;break;default:_desugarsw0=2;break;}return _desugarsw0;"),
            "a returned switch expression hoists a scratch variable before its own return"
        );
        String pattern = lower(
            "Patched.java",
            "class Patched {\n    String f(Object o) {\n        switch (o) {\n            case String s:\n                return s;\n            default:\n                return \"none\";\n        }\n    }\n}\n"
        );
        check(
            pattern.contains("if(_desugarsel0 instanceof String){String s=(String)_desugarsel0;return s;}else{return \"none\";}"),
            "a switch over type patterns becomes an if-else chain with casts"
        );
        String fallthrough = "class Fall {\n    int f(Object o) {\n        switch (o) {\n            case String s:\n                System.out.println(s);\n            default:\n                return 0;\n        }\n    }\n}\n";
        check(
            lower("Fall.java", fallthrough).equals(fallthrough),
            "a pattern case that falls through is left alone instead of dropping its fallthrough"
        );
        String arrows = lower(
            "Menu.java",
            "class Menu {\n    void f(int n) {\n        switch (n) {\n            case 0 -> System.out.println(n);\n            case 1 -> {\n                System.out.println(n);\n            }\n            default -> System.out.println(-1);\n        }\n    }\n}\n"
        );
        check(
            arrows.contains("case 0 : System.out.println(n);break;") &&
            arrows.contains("case 1 : {") &&
            arrows.contains("}break;") &&
            arrows.contains("default : System.out.println(-1);break;") &&
            !arrows.contains("->"),
            "arrow cases over constants become colon cases with breaks"
        );
        check(
            lower("Menu.java", arrows).equals(arrows),
            "a lowered arrow switch is stable"
        );
    }

    static void smallChecks() throws Exception {
        String text = lower(
            "Text.java",
            "class Text {\n    String s = \"\"\"\n        hello\n        world\n        \"\"\";\n}\n"
        );
        check(
            text.contains("String s = \"hello\\nworld\\n\";"),
            "a text block becomes the quoted literal with its incidental indent removed"
        );
        String vars = lower(
            "Vars.java",
            "import java.util.ArrayList;\nclass Vars {\n    void f() {\n        var list = new ArrayList<String>();\n        var n = compute();\n    }\n    int compute() { return 1; }\n}\n"
        );
        check(
            vars.contains("ArrayList<String> list = new ArrayList<String>();") && vars.contains("int n = compute();"),
            "var becomes the constructed type or the inferred call type"
        );
        String sealed = lower(
            "Shape.java",
            "public sealed class Shape permits Circle {\n}\nclass Circle extends Shape {\n}\n"
        );
        check(
            sealed.contains("public  class Shape  {") && !sealed.contains("sealed") && !sealed.contains("permits"),
            "sealed and permits are stripped to a plain class"
        );
        check(
            Desugar.needsDesugar("error: records are not supported in -source 8") &&
            !Desugar.needsDesugar("error: cannot find symbol"),
            "the legacy-language gate fires on release rejections, not on ordinary errors"
        );
        check(
            Port.isLegacyRelease(8) && Port.isLegacyRelease("1.8") && !Port.isLegacyRelease("17") && !Port.isLegacyRelease(null) && !Port.isLegacyRelease("foo"),
            "releases at Java 8 or older read as legacy, newer and unknown ones do not"
        );
    }

    static void unattributedChecks() throws Exception {
        String unresolvable = lower(
            "Unresolvable.java",
            "class Unresolvable {\n    UnknownHolder holder;\n    String f() {\n        switch (holder.get()) {\n            case String s:\n                return s;\n            default:\n                return \"none\";\n        }\n    }\n}\n"
        );
        check(
            unresolvable.contains("java.lang.Object _desugarsel") && unresolvable.contains("instanceof String"),
            "a pattern switch over an unresolvable selector hoists it as an object"
        );
        check(
            lower("Unresolvable.java", unresolvable).equals(unresolvable),
            "a lowered object-hoisted switch is stable"
        );
        String returned = lower(
            "Unresolved.java",
            "class Unresolved {\n    String f(int n) {\n        return switch (n) {\n            case 0 -> Unknown.VALUE;\n            default -> \"d\";\n        };\n    }\n}\n"
        );
        check(
            returned.contains("String _desugarsw") && returned.contains("Unknown.VALUE"),
            "a returned switch with unresolvable values keeps the method return type"
        );
        check(
            lower("Unresolved.java", returned).equals(returned),
            "a lowered method-typed switch is stable"
        );
    }

    static void guardClauseChecks() throws Exception {
        String returned = lower(
            "Guarded.java",
            "class Guarded {\n    boolean f(Object o, Object user) {\n        return o != user && o instanceof String s && s.isEmpty();\n    }\n}\n"
        );
        check(
            returned.contains("return o != user && o instanceof String && ((String)o).isEmpty();"),
            "an instanceof chain in a return lowers its guarded uses"
        );
        check(
            lower("Guarded.java", returned).equals(returned),
            "a lowered return guard is stable"
        );
        String abort = lower(
            "Abort.java",
            "class Abort {\n    void f(Object x) {\n        if (!(x instanceof String s)) {\n            return;\n        }\n        s.trim();\n    }\n}\n"
        );
        check(
            abort.contains("!(x instanceof String)") && abort.contains("((String)x).trim();"),
            "an aborting negated guard lowers the code after the if"
        );
        check(
            lower("Abort.java", abort).equals(abort),
            "a lowered abort guard is stable"
        );
        String disjoined = lower(
            "Disjoined.java",
            "class Disjoined {\n    String f(Object x, boolean bound) {\n        if (!bound || !(x instanceof String s)) {\n            return \"d\";\n        }\n        return s.trim();\n    }\n}\n"
        );
        check(
            disjoined.contains("!bound || !(x instanceof String)") && disjoined.contains("return ((String)x).trim();"),
            "a disjoined abort guard lowers the code after the if"
        );
        String logged = lower(
            "Logged.java",
            "class Logged {\n    void log(Object x) {\n    }\n    void f(Object x) {\n        if (!(x instanceof String s)) {\n            log(x);\n            return;\n        }\n        s.trim();\n    }\n}\n"
        );
        check(
            logged.contains("((String)x).trim();"),
            "a multi-statement abort guard lowers the code after the if"
        );
    }

    static void nestedRecordChecks() throws Exception {
        String nested = lower(
            "Nested.java",
            "class Outer {\n    public record Nested(String id) {\n        public static final String KIND = \"nested\";\n    }\n}\n"
        );
        check(
            nested.contains("public static final class Nested{"),
            "a record nested in a class lowers to a static nested class"
        );
        check(
            lower("Nested.java", nested).equals(nested),
            "a lowered nested record is stable"
        );
    }

    static void ifaceChecks() throws Exception {
        String ifaces = lower(
            "Ifaces.java",
            "interface Ifaces {\n    void use();\n    private static void helper() {\n    }\n    private void more() {\n    }\n    void plain();\n}\n"
        );
        check(
            ifaces.contains("static void helper() {") && !ifaces.contains("private static void helper()") &&
            ifaces.contains("default void more() {") && !ifaces.contains("private void more()") &&
            ifaces.contains("void plain();"),
            "private interface helpers become static or default methods"
        );
        check(
            lower("Ifaces.java", ifaces).equals(ifaces),
            "a lowered interface is stable"
        );
        String klass = "class Klass {\n    private void m() {\n    }\n}\n";
        check(
            lower("Klass.java", klass).equals(klass),
            "a private class method is already legacy-legal and stays"
        );
    }

    public static void main(String[] args) throws Exception {
        Path home = Path.of(args[0]);
        scratch = Files.createTempDirectory(Files.createDirectories(Path.of(args[1])), "run-");
        recordChecks();
        patternChecks();
        switchChecks();
        unattributedChecks();
        nestedRecordChecks();
        guardClauseChecks();
        ifaceChecks();
        smallChecks();
        System.out.println(count + " desugar checks passed");
    }
}
