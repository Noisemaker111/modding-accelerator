import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Shared wire format for the command, receipts and the Java 8-compatible game agent. */
public final class Json {
    private final String source;
    private int at;
    private Json(String source) { this.source = source; }
    public static Object parse(String source) {
        Json parser = new Json(source);
        Object value = parser.value();
        parser.space();
        if (parser.at != source.length()) throw new IllegalArgumentException("Trailing JSON at " + parser.at);
        return value;
    }
    /** As parse, but a syntax error says where, as "line L, column C: what was expected". */
    public static Object parseReporting(String source) {
        Json parser = new Json(source);
        try {
            Object value = parser.value();
            parser.space();
            if (parser.at != source.length()) throw new IllegalArgumentException("more text after the end of the JSON value");
            return value;
        } catch (IllegalArgumentException invalid) {
            int line = 1, column = 1, end = Math.min(parser.at, source.length());
            for (int i = 0; i < end; i++) { if (source.charAt(i) == '\n') { line++; column = 1; } else column++; }
            String message = String.valueOf(invalid.getMessage()).replaceFirst(" at \\d+$", "");
            throw new IllegalArgumentException("line " + line + ", column " + column + ": " + Character.toLowerCase(message.charAt(0)) + message.substring(1), invalid);
        }
    }
    @SuppressWarnings("unchecked") public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) value;
    }
    @SuppressWarnings("unchecked") public static List<Object> array(Object value) {
        if (!(value instanceof List)) throw new IllegalArgumentException("Expected a JSON array");
        return (List<Object>) value;
    }
    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return result;
    }
    public static String text(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key); return value == null ? fallback : String.valueOf(value);
    }
    public static String write(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map) {
            List<String> entries = new ArrayList<String>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) entries.add(write(String.valueOf(entry.getKey())) + ":" + write(entry.getValue()));
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof Iterable) {
            List<String> entries = new ArrayList<String>();
            for (Object item : (Iterable<?>) value) entries.add(write(item));
            return "[" + String.join(",", entries) + "]";
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c);
            }
        }
        return out.append('"').toString();
    }
    public static Map<String, Object> read(Path path) throws IOException {
        return object(parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)));
    }
    public static void save(Path path, Object value) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(path.getParent(), "json-", ".tmp");
        Files.write(temporary, (write(value) + "\n").getBytes(StandardCharsets.UTF_8));
        try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
    }
    private void space() { while (at < source.length() && Character.isWhitespace(source.charAt(at))) at++; }
    private Object value() {
        space(); if (at >= source.length()) throw new IllegalArgumentException("Unexpected end of JSON");
        char c = source.charAt(at++);
        if (c == '"') return string();
        if (c == '{') {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            space(); if (take('}')) return map;
            do {
                space(); if (!take('"')) throw new IllegalArgumentException("Expected object key");
                String key = string(); space(); require(':');
                if (map.containsKey(key)) throw new IllegalArgumentException("Duplicate JSON key: " + key);
                map.put(key, value()); space(); if (take('}')) return map;
                if (!take(',')) { throw new IllegalArgumentException("Expected ',' or '}' after the value of \"" + key + "\""); }
            } while (true);
        }
        if (c == '[') {
            List<Object> list = new ArrayList<Object>();
            space(); if (take(']')) return list;
            do { list.add(value()); space(); if (take(']')) return list; if (!take(',')) { throw new IllegalArgumentException("Expected ',' or ']' in a list"); } } while (true);
        }
        at--;
        for (String literal : Arrays.asList("true", "false", "null")) if (source.startsWith(literal, at)) {
            at += literal.length(); return literal.equals("null") ? null : Boolean.valueOf(literal);
        }
        int start = at;
        while (at < source.length() && "-+0123456789.eE".indexOf(source.charAt(at)) >= 0) at++;
        String number = source.substring(start, at);
        if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw new IllegalArgumentException("Invalid JSON at " + start);
        return number.indexOf('.') >= 0 || number.indexOf('e') >= 0 || number.indexOf('E') >= 0 ? (Number)Double.valueOf(number) : Long.valueOf(number);
    }
    private String string() {
        StringBuilder out = new StringBuilder();
        while (at < source.length()) {
            char c = source.charAt(at++);
            if (c == '"') return out.toString();
            if (c == '\\') {
                if (at >= source.length()) break;
                c = source.charAt(at++);
                switch (c) {
                    case '"': case '\\': case '/': out.append(c); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u': if (at + 4 > source.length()) throw new IllegalArgumentException("Short Unicode escape");
                        out.append((char)Integer.parseInt(source.substring(at, at + 4), 16)); at += 4; break;
                    default: throw new IllegalArgumentException("Invalid JSON escape");
                }
            } else { if (c < 32) throw new IllegalArgumentException("Control character in JSON string"); out.append(c); }
        }
        throw new IllegalArgumentException("Unterminated JSON string");
    }
    private boolean take(char c) { if (at < source.length() && source.charAt(at) == c) { at++; return true; } return false; }
    private void require(char c) { if (!take(c)) throw new IllegalArgumentException("Expected '" + c + "' at " + at); }
}
