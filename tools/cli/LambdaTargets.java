import java.util.HashMap;
import java.util.Map;

/**
 * A game lambda's name in Loom's development game, for a mixin that targets it by the compiler's name. Mojang's mappings
 * name the game's lambdas as the compiler did (lambda$registerTypes$0, the name NeoForge's development game keeps); Loom
 * names them by intermediary (method_59812), as Mojang's names leave them out of its mappings. The way from one to the
 * other is the obfuscated name: Mojang's mappings give the lambda's obfuscated name and descriptor, intermediary's
 * mappings the intermediary name of that.
 */
final class LambdaTargets {

    private LambdaTargets() {}

    /** The intermediary name of a lambda of a game class (owner in dots), or null when either mapping lacks it. */
    static String intermediary(String mojangMappings, String intermediaryTiny, String owner, String lambda) {
        Map<String, String> obfClasses = new HashMap<>();
        String obfOwner = null;
        String obfName = null;
        String obfDescriptor = null;
        String current = null;
        String[] found = null;
        for (String line : mojangMappings.split("\n")) {
            if (line.startsWith("#") || line.isBlank()) continue;
            if (!line.startsWith(" ")) {
                int arrow = line.indexOf(" -> ");
                if (arrow < 0) continue;
                current = line.substring(0, arrow);
                obfClasses.put(current, line.substring(arrow + 4, line.length() - 1));
                continue;
            }
            if (!owner.equals(current) || found != null) continue;
            String member = line.strip().replaceFirst("^\\d+:\\d+:", "");
            int paren = member.indexOf('(');
            int arrow = member.indexOf(" -> ");
            if (paren < 0 || arrow < 0) continue;
            String[] head = member.substring(0, paren).split(" ");
            if (head.length != 2 || !head[1].equals(lambda)) continue;
            found = new String[] { head[0], member.substring(paren + 1, member.indexOf(')')), member.substring(arrow + 4) };
        }
        if (found == null) return null;
        obfOwner = obfClasses.get(owner);
        obfName = found[2].strip();
        StringBuilder descriptor = new StringBuilder("(");
        if (!found[1].isEmpty()) for (String parameter : found[1].split(",")) descriptor.append(type(parameter.strip(), obfClasses));
        obfDescriptor = descriptor.append(')').append(type(found[0], obfClasses)).toString();
        boolean inOwner = false;
        for (String line : intermediaryTiny.split("\n")) {
            String[] cells = line.split("\t", -1);
            if (cells.length >= 3 && cells[0].equals("c")) inOwner = cells[1].equals(obfOwner);
            else if (inOwner && cells.length >= 5 && cells[1].equals("m") && cells[2].equals(obfDescriptor) && cells[3].equals(obfName)) return cells[4];
        }
        return null;
    }

    /** A type of Mojang's mappings (int, java.lang.String[], net.minecraft.X) as an obfuscated descriptor. */
    static String type(String name, Map<String, String> obfClasses) {
        if (name.endsWith("[]")) return "[" + type(name.substring(0, name.length() - 2), obfClasses);
        return switch (name) {
            case "void" -> "V";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> "L" + obfClasses.getOrDefault(name, name).replace('.', '/') + ";";
        };
    }
}
