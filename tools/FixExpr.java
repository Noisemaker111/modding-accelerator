import java.util.*;

/**
 * FixExpr - the replacement a port rule gives for one game member, as a Java expression JarPort compiles into a class.
 *
 * A rule's "inClass" maps the old member ("a.b.Owner.name", optionally with its argument types "(FFFF)") to what
 * replaces it, written in the new release's names. $0 is the old call's receiver and $1, $2, ... its arguments:
 *
 *   "net.minecraft.client.gui.GuiGraphics.setColor": "com.mojang.blaze3d.systems.RenderSystem.setShaderColor($1, $2, $3, $4)"
 *
 * The expression is a small part of Java: static and instance calls, field reads, new, casts, literals and static
 * method references (a.b.C::m) passed where a functional interface is wanted. A name in the member may be a regex;
 * {1} in the expression is its first group, and {^1} that group in UPPER_SNAKE case (getPositionTexShader matched by
 * get(\w+)Shader gives CoreShaders.{^1} = CoreShaders.POSITION_TEX). This class only parses; JarPort resolves each
 * name against the new release, so a replacement that does not link there is never written.
 */
final class FixExpr {

    sealed interface Node permits Arg, Lit, Path, Get, Invoke, New, Cast, Ref {}

    /** $N: the receiver (0) or an argument of the old call. */
    record Arg(int index) implements Node {}

    /** A literal; type is a descriptor ("I", "Ljava/lang/String;"), or null for null. */
    record Lit(Object value, String type) implements Node {}

    /** A dotted name not yet split into class and fields (a.b.C or a.b.C.FIELD). */
    record Path(String dotted) implements Node {}

    record Get(Node target, String name) implements Node {}

    /** target is a Path (a class, for a static call) or any expression. */
    record Invoke(Node target, String name, List<Node> args) implements Node {}

    record New(String type, List<Node> args) implements Node {}

    /** type is dotted, or a primitive keyword. */
    record Cast(String type, Node expr) implements Node {}

    record Ref(String type, String name) implements Node {}

    static Node parse(String text) {
        FixExpr p = new FixExpr(text);
        Node n = p.expr();
        p.space();
        if (p.at < text.length()) {
            throw p.error("unexpected text");
        }
        return n;
    }

    /** The expression for one matched member name: {N} and {^N} filled from the match. */
    static String fill(String expr, java.util.regex.MatchResult name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{(\\^?)(\\d)}").matcher(expr);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String g = name.group(Integer.parseInt(m.group(2)));
            String v = g == null ? "" : m.group(1).isEmpty() ? g
                : g.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("([A-Z])([A-Z][a-z])", "$1_$2").toUpperCase(Locale.ROOT);
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(v));
        }
        return m.appendTail(out).toString();
    }

    /** The operands ($N) an expression uses. */
    static Set<Integer> operands(Node n) {
        Set<Integer> out = new TreeSet<>();
        Deque<Node> todo = new ArrayDeque<>(List.of(n));
        while (!todo.isEmpty()) {
            switch (todo.pop()) {
                case Arg a -> out.add(a.index());
                case Get g -> todo.push(g.target());
                case Invoke i -> {
                    todo.push(i.target());
                    i.args().forEach(todo::push);
                }
                case New w -> w.args().forEach(todo::push);
                case Cast c -> todo.push(c.expr());
                default -> {
                }
            }
        }
        return out;
    }

    static final Set<String> PRIMITIVES = Set.of("boolean", "byte", "char", "short", "int", "long", "float", "double");

    final String text;
    int at;

    FixExpr(String text) {
        this.text = text;
    }

    IllegalArgumentException error(String what) {
        return new IllegalArgumentException(what + " at " + at + " in: " + text);
    }

    void space() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }

    boolean eat(String token) {
        space();
        if (text.startsWith(token, at)) {
            at += token.length();
            return true;
        }
        return false;
    }

    void expect(String token) {
        if (!eat(token)) {
            throw error("expected " + token);
        }
    }

    String ident() {
        space();
        int start = at;
        while (at < text.length() && (Character.isJavaIdentifierPart(text.charAt(at)) && text.charAt(at) != '$' || text.charAt(at) == '$' && at > start)) {
            at++;
        }
        if (start == at || !Character.isJavaIdentifierStart(text.charAt(start))) {
            at = start;
            throw error("expected a name");
        }
        return text.substring(start, at);
    }

    /** a.b.C, with $ kept for nested classes. */
    String qualified() {
        StringBuilder s = new StringBuilder(ident());
        while (true) {
            int mark = at;
            if (!eat(".")) {
                return s.toString();
            }
            space();
            if (at >= text.length() || !Character.isJavaIdentifierStart(text.charAt(at))) {
                at = mark;
                return s.toString();
            }
            s.append('.').append(ident());
        }
    }

    List<Node> args() {
        expect("(");
        List<Node> out = new ArrayList<>();
        if (eat(")")) {
            return out;
        }
        do {
            out.add(expr());
        } while (eat(","));
        expect(")");
        return out;
    }

    Node expr() {
        return postfix(primary());
    }

    Node primary() {
        space();
        if (at >= text.length()) {
            throw error("expected an expression");
        }
        char c = text.charAt(at);
        if (c == '$' && at + 1 < text.length() && Character.isDigit(text.charAt(at + 1))) {
            int start = ++at;
            while (at < text.length() && Character.isDigit(text.charAt(at))) {
                at++;
            }
            return new Arg(Integer.parseInt(text.substring(start, at)));
        }
        if (c == '"') {
            int start = ++at;
            while (at < text.length() && text.charAt(at) != '"') {
                at += text.charAt(at) == '\\' ? 2 : 1;
            }
            String body = text.substring(start, at);
            expect("\"");
            return new Lit(body.replace("\\\"", "\"").replace("\\\\", "\\"), "Ljava/lang/String;");
        }
        if (Character.isDigit(c) || c == '-') {
            return number();
        }
        if (c == '(') {
            int mark = at;
            at++;
            String type = castType();
            if (type != null) {
                return new Cast(type, postfix(primary()));
            }
            at = mark + 1;
            Node inner = expr();
            expect(")");
            return inner;
        }
        int mark = at;
        String word = ident();
        switch (word) {
            case "true", "false" -> {
                return new Lit(Boolean.parseBoolean(word), "Z");
            }
            case "null" -> {
                return new Lit(null, null);
            }
            case "new" -> {
                String type = qualified();
                return new New(type, args());
            }
            default -> {
            }
        }
        at = mark;
        String q = qualified();
        if (eat("::")) {
            return new Ref(q, ident());
        }
        space();
        if (at < text.length() && text.charAt(at) == '(') {
            int dot = q.lastIndexOf('.');
            if (dot < 0) {
                throw error("a call names its class or receiver");
            }
            return new Invoke(new Path(q.substring(0, dot)), q.substring(dot + 1), args());
        }
        return new Path(q);
    }

    /** (T) x: T is a primitive or a class (its simple name capitalised) and an operand follows; else null, nothing read. */
    String castType() {
        int mark = at;
        try {
            String type = qualified();
            String simple = type.substring(type.lastIndexOf('.') + 1);
            if (eat(")") && (PRIMITIVES.contains(type) || Character.isUpperCase(simple.charAt(0)))) {
                space();
                char next = at < text.length() ? text.charAt(at) : ' ';
                if (Character.isJavaIdentifierStart(next) || next == '(' || next == '"' || Character.isDigit(next)) {
                    return type;
                }
            }
        } catch (IllegalArgumentException notACast) {
            // A parenthesised expression.
        }
        at = mark;
        return null;
    }

    /** Calls and field reads on n: n.a().b. */
    Node postfix(Node n) {
        while (eat(".")) {
            String name = ident();
            space();
            n = at < text.length() && text.charAt(at) == '(' ? new Invoke(n, name, args()) : new Get(n, name);
        }
        return n;
    }

    Node number() {
        int start = at;
        if (text.charAt(at) == '-') {
            at++;
        }
        while (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) {
            at++;
        }
        String digits = text.substring(start, at);
        char suffix = at < text.length() ? Character.toUpperCase(text.charAt(at)) : ' ';
        if (suffix == 'F') {
            at++;
            return new Lit(Float.parseFloat(digits), "F");
        }
        if (suffix == 'D') {
            at++;
            return new Lit(Double.parseDouble(digits), "D");
        }
        if (suffix == 'L') {
            at++;
            return new Lit(Long.parseLong(digits), "J");
        }
        return digits.contains(".") ? new Lit(Double.parseDouble(digits), "D") : new Lit(Integer.parseInt(digits), "I");
    }
}
