import java.util.*;

/**
 * The SRG lane: pre-1.14.4 tables keyed on Forge's stable ids, chained like intermediary tables.
 * The first table's rows are verbatim from mcp 1.7.10's joined.srg and mcp_stable 12-1.7.10's
 * CSVs, trimmed to the members under test; the second table is a synthetic later name set, built
 * to exercise a rename plus a move through the same SRG id.
 */
public final class LegacyMapTest {
    static int count;
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); count++; System.out.println("PASS " + message); }

    static final String SRG = """
            CL: aaf net/minecraft/inventory/ContainerWorkbench
            CL: ahb net/minecraft/world/World
            CL: aji net/minecraft/block/Block
            FD: ahb/A net/minecraft/world/World/field_72982_D
            MD: aaf/a (Ladd;Laay;)Z net/minecraft/inventory/ContainerWorkbench/func_94530_a (Lnet/minecraft/item/ItemStack;Lnet/minecraft/inventory/Slot;)Z
            MD: ahb/B ()V net/minecraft/world/World/func_72966_v ()V
            """;
    static final String TSRG = "aaf net/minecraft/inventory/ContainerWorkbench\n" +
            "\ta (Ladd;Laay;)Z func_94530_a\n" +
            "ahb net/minecraft/world/World\n" +
            "\tA field_72982_D\n" +
            "\tB ()V func_72966_v\n" +
            "aji net/minecraft/block/Block\n";
    // Only the searge and name columns may be read; the second row's javadoc contains a comma.
    // func_94530_a has no row at all: mcp_stable 12 never named it.
    static final String METHODS = "searge,name,side,desc\n" +
            "func_72966_v,calculateInitialSkylight,0,Called on construction of the World class to setup the initial skylight values\n" +
            "func_72827_u,getProviderName,0,Returns the name of the current chunk provider, by calling chunkprovider.makeString()\n";
    static final String FIELDS = "searge,name,side,desc\nfield_72982_D,villageCollectionObj,0,\n";

    static VersionMap.Table table(String version, String srg, String methods, String fields) {
        return VersionMap.Table.fromSrg(version, VersionMap.Srg.srg(srg), VersionMap.Srg.csv(fields, methods));
    }

    public static void main(String[] args) throws Exception {
        VersionMap.Tiny a = VersionMap.Srg.srg(SRG), b = VersionMap.Srg.tsrg(TSRG);
        check(a.classes.equals(b.classes) && new HashSet<>(a.memberRows).equals(new HashSet<>(b.memberRows)),
                "joined.srg and joined.tsrg spellings parse to the same rows");
        check("getProviderName".equals(VersionMap.Srg.csv(METHODS).get("func_72827_u")),
                "a comma inside the javadoc column cannot shift a name");
        check("func_72966_v".equals(a.methods.get("ahb.B()V")) && "field_72982_D".equals(a.fields.get("ahb.A")),
                "SRG rows fill the same lookup maps a tiny file fills");
        VersionMap.Proguard pg = VersionMap.Srg.proguardOf(a, VersionMap.Srg.csv(FIELDS, METHODS));
        check("net/minecraft/world/World".equals(pg.classes.get("ahb"))
                        && "calculateInitialSkylight".equals(pg.methods.get("ahb.B()V"))
                        && "villageCollectionObj".equals(pg.fields.get("ahb.A"))
                        && "func_94530_a".equals(pg.methods.get("aaf.a(Ladd;Laay;)Z")),
                "the Proguard layout of the same data names a jar's members from their obfuscated names");

        VersionMap.Table t = table("1.7.10", SRG, METHODS, FIELDS);
        check("net/minecraft/world/World".equals(t.classes.get("net/minecraft/world/World")),
                "an SRG class is its own named side (MCP never renames a class)");
        VersionMap.Member named = t.methods.get("net/minecraft/world/World.func_72966_v");
        check(named != null && named.name().equals("calculateInitialSkylight") && named.owner().equals("net/minecraft/world/World"),
                "a method keyed by its SRG id alone takes its MCP name from the CSV");
        VersionMap.Member bare = t.methods.get("net/minecraft/inventory/ContainerWorkbench.func_94530_a");
        check(bare != null && bare.name().equals("func_94530_a"),
                "a member MCP never named keeps its SRG id, the name legacy sources call it by");
        VersionMap.Member field = t.fields.get("net/minecraft/world/World.field_72982_D");
        check(field != null && field.name().equals("villageCollectionObj") && field.desc().isEmpty(),
                "a field takes its MCP name; SRG rows carry no field type");
        check(t.methodsByInterName.containsKey("func_72966_v"), "SRG method ids are indexed for move detection");

        // A later name set renames the method and it has moved to another class, under the same id.
        String srg2 = """
                CL: ahb net/minecraft/world/World
                CL: aji net/minecraft/block/Block
                MD: aji/B ()V net/minecraft/block/Block/func_72966_v ()V
                """;
        String methods2 = "searge,name,side,desc\nfunc_72966_v,computeSkylight,0,\n";
        VersionMap.Chain chain = VersionMap.Chain.of(List.of("1.7.10", "1.12.2"),
                v -> v.equals("1.7.10") ? t : table("1.12.2", srg2, methods2, FIELDS));
        VersionMap.Diff d = chain.diff;
        check(d.methodMoved.size() == 1 && d.methodMoved.get(0).from().equals("calculateInitialSkylight")
                        && d.methodMoved.get(0).to().equals("computeSkylight")
                        && d.methodMoved.get(0).newOwner().equals("net/minecraft/block/Block"),
                "an MCP rename plus a move resolves through the SRG id to the final owner and name");
        check(d.fieldRemoved.size() == 1 && d.fieldRemoved.get(0).from().equals("villageCollectionObj")
                        && chain.history.stream().anyMatch(e -> e.name().equals("villageCollectionObj") && e.step().equals("1.7.10 -> 1.12.2")),
                "a member gone by the later table is reported removed, with its step");
        check(d.methodRemoved.stream().anyMatch(c -> c.from().equals("func_94530_a")),
                "an unnamed member is reported under its SRG id, never dropped");
        String map = chain.json("mcp:stable_39");
        check(map.contains("\"from\": \"1.7.10\"") && map.contains("\"to\": \"1.12.2\"")
                        && map.contains("\"names\": \"mcp:stable_39\"")
                        && map.contains("\"methodsMoved\"")
                        && map.contains("\"from\": \"calculateInitialSkylight\"")
                        && map.contains("\"newOwner\": \"net.minecraft.block.Block\"")
                        && map.contains("\"chain\": [\"1.7.10\", \"1.12.2\"]"),
                "the chain map port hands the typed fixer keeps its MCP identity and the moved member, pinned channel included");
        System.out.println(count + " legacy map checks passed");
    }
}
