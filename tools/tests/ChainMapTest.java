import java.nio.file.*;
import java.util.*;

/** One map across several updates: chains resolve to the final name, removals keep the update that removed them. */
public final class ChainMapTest {
    static int count;
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); count++; System.out.println("PASS " + message); }

    static VersionMap.Table table(String version, Map<String, String> classes, String... members) {
        VersionMap.Table t = new VersionMap.Table(version);
        t.classes.putAll(classes);
        // member: kind|interOwner|interName|interDesc|named owner|named name|named desc
        for (String row : members) {
            String[] p = row.split("\\|");
            String key = VersionMap.Table.key(p[1], p[2], p[3]);
            VersionMap.Member m = new VersionMap.Member(p[4], p[5], p[6], p[3]);
            (p[0].equals("m") ? t.methods : t.fields).put(key, m);
            if (p[0].equals("m") && p[2].startsWith("method_")) t.methodsByInterName.computeIfAbsent(p[2], x -> new ArrayList<>()).add(key);
        }
        return t;
    }

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createTempDirectory("chain-map");
        Map<String, VersionMap.Table> tables = new HashMap<>();
        tables.put("1", table("1", Map.of("class_1", "net/a/Alpha", "class_2", "net/a/Gone", "class_3", "net/a/Holder"),
                "m|class_1|method_10|(I)V|net/a/Alpha|tick|(I)V",
                "m|class_1|method_11|()V|net/a/Alpha|dropped|()V",
                "m|class_1|method_12|()V|net/a/Alpha|wander|()V",
                "f|class_1|field_20|I|net/a/Alpha|count|I",
                "f|class_1|field_21|I|net/a/Alpha|flicker|I"));
        tables.put("2", table("2", Map.of("class_1", "net/a/Beta", "class_2", "net/a/Gone", "class_3", "net/a/Holder"),
                "m|class_1|method_10|(I)V|net/a/Beta|update|(I)V",
                "m|class_1|method_12|()V|net/a/Beta|wander|()V",
                "f|class_1|field_20|I|net/a/Beta|amount|I"));
        tables.put("3", table("3", Map.of("class_1", "net/b/Gamma", "class_3", "net/a/Holder"),
                "m|class_3|method_10|(I)V|net/a/Holder|update|(I)V",
                "m|class_1|method_12|(J)V|net/b/Gamma|wander|(J)V",
                "f|class_1|field_20|I|net/b/Gamma|total|I",
                "f|class_1|field_21|I|net/b/Gamma|flicker|I"));
        VersionMap.Chain chain = VersionMap.Chain.of(List.of("1", "2", "3"), tables::get);
        VersionMap.Diff d = chain.diff;
        check(d.classRenamed.size() == 1 && d.classRenamed.get(0)[0].equals("net/a/Alpha") && d.classRenamed.get(0)[1].equals("net/b/Gamma"),
                "a class renamed twice (Alpha -> Beta -> Gamma) maps straight to its final name");
        check(d.classRemoved.equals(List.of("net/a/Gone")), "a class removed in the second update stays listed as removed");
        check(chain.history.stream().anyMatch(e -> e.kind().equals("class") && e.what().equals("removed") && e.name().equals("net.a.Gone") && e.step().equals("2 -> 3")),
                "the removal names the update where it happened");
        check(d.methodMoved.size() == 1 && d.methodMoved.get(0).from().equals("tick") && d.methodMoved.get(0).to().equals("update") && d.methodMoved.get(0).newOwner().equals("net/a/Holder"),
                "a method renamed in one update and moved in the next resolves to its final owner and name");
        check(d.methodRemoved.size() == 1 && d.methodRemoved.get(0).from().equals("dropped"), "a method removed along the way is reported, not dropped");
        check(chain.history.stream().anyMatch(e -> e.name().equals("dropped") && e.what().equals("removed") && e.step().equals("1 -> 2")), "the method removal names update 1 -> 2");
        check(d.methodSignature.size() == 1 && d.methodSignature.get(0).from().equals("wander") && d.methodSignature.get(0).newDesc().equals("(J)V")
                        && chain.history.stream().anyMatch(e -> e.name().equals("wander") && e.what().equals("signature") && e.step().equals("2 -> 3")),
                "a signature change is kept with its update");
        check(d.fieldRenamed.size() == 1 && d.fieldRenamed.get(0).to().equals("total"), "a field renamed twice maps to its final name");
        check(d.fieldRemoved.isEmpty(), "a field removed and back by the end (same id) is not reported as removed");
        Path map = scratch.resolve("chain.json");
        Files.writeString(map, chain.json("mojmap"));
        VersionMap.Known known = VersionMap.Known.load(map);
        check("net.b.Gamma".equals(known.classFqn.get("net.a.Alpha")) && known.methodGone.contains("dropped"), "the chained map loads with the existing map reader");
        String text = Files.readString(map);
        check(text.contains("\"chain\": [\"1\", \"2\", \"3\"]") && text.contains("\"step\": \"2 -> 3\""), "the map records its chain and per-update history");
        System.out.println(count + " chain map checks passed");
    }
}
