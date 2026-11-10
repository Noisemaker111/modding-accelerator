"""bench/loader_api.py pairs loader calls only where the code proves it. Run: python bench/test_loader_api.py

Works on a made-up mod in a temporary folder, so it needs no checkout and no network.
"""
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import loader_api as L  # noqa: E402

checks = 0


def check(ok, label):
    global checks
    if not ok:
        raise AssertionError(label)
    checks += 1


F, N, G = "net.fabricmc.", "net.neoforged.", "net.minecraftforge."

# ---- reading one file ---------------------------------------------------------------------------
names = L.imports("import net.fabricmc.loader.api.FabricLoader;\n/*import net.neoforged.fml.ModList;\nimport net.minecraftforge.fml.common.Mod.EventBusSubscriber;*/\n"
                  "import net.minecraft.world.level.Level;\nimport net.neoforged.fml.loading.FMLPaths as Paths\nimport static net.neoforged.fml.loading.FMLEnvironment.dist;\n")
check(names == {"FabricLoader": F + "loader.api.FabricLoader", "ModList": N + "fml.ModList", "EventBusSubscriber": G + "fml.common.Mod.EventBusSubscriber",
                "Paths": N + "fml.loading.FMLPaths", "FMLEnvironment": N + "fml.loading.FMLEnvironment"},
      f"imports reads loader classes only, switched-off ones, nested classes, Kotlin aliases and static imports: {names}")
for code, want in (("FabricLoader.getInstance().isModLoaded(id)", "#isModLoaded"), ("FabricLoader.getInstance()\n    .getConfigDir().resolve(x)", "#getConfigDir"),
                   ("FMLPaths.CONFIGDIR.get()", "#CONFIGDIR"), ("ModList.get()", ""), ("Mod.EventBusSubscriber.Bus.MOD", ".EventBusSubscriber.Bus#MOD"),
                   ("IConfigScreenFactory.class", ""), ("FluidStack stack", "")):
    start = code.index(".") if "." in code and not code.startswith("FluidStack") else len("FluidStack")
    check(L.member(code, start) == want, f"the member of {code!r} is {want!r}, got {L.member(code, start)!r}")
got = L.calls('return FabricLoader.getInstance().isModLoaded("x") && other.FabricLoader.nope();', names)
check(got == {F + "loader.api.FabricLoader#isModLoaded"}, f"a call is the class plus its member, and a.FabricLoader is someone else's field: {got}")
check(L.calls("FabricLoader loader = FabricLoader.getInstance(); loader.x();", names) == {F + "loader.api.FabricLoader"}, "a class that is only named is recorded once, as itself")

# ---- when two sides prove a pair ----------------------------------------------------------------
a, b = F + "loader.api.FabricLoader#isModLoaded", N + "fml.ModList#isLoaded"
check(L.matches([{a}, {b}]) == [(a, b, "one")], "one loader call on each side is a pair")
check(L.matches([{a, F + "api.EnvType#CLIENT"}, {b}]) == [], "two calls on one side prove nothing about which answers which")
check(L.matches([{a}, {F + "loader.api.FabricLoader#getConfigDir"}]) == [], "two calls of the same loader are not a pair")
fg, ng, other = G + "fml.loading.FMLPaths#CONFIGDIR", N + "fml.loading.FMLPaths#CONFIGDIR", N + "fml.ModList#isLoaded"
check(L.matches([{fg, G + "fml.ModList#isLoaded"}, {ng, other}]) == [(fg, ng, "name"), (G + "fml.ModList#isLoaded", other, "name")] or
      sorted(L.matches([{fg, G + "fml.ModList#isLoaded"}, {ng, other}])) == sorted([(fg, ng, "name"), (G + "fml.ModList#isLoaded", other, "name")]),
      "among several calls, the two that share a class name and member are a pair")
check(L.matches([{fg, G + "x.FMLPaths#CONFIGDIR"}, {ng}]) == [], "a name two calls on one side share picks neither")

# ---- switches -----------------------------------------------------------------------------------
row = lambda *branches: {"branches": [{"condition": c, "code": code} for c, code in branches]}
sides = L.switch_sides(row(("fabric", "a();"), (None, "/*b();*/")), ["fabric", "forge", "neoforge"])
check(sides == [({"fabric"}, "a();"), ({"forge", "neoforge"}, "b();")], f"else is every other loader the mod ships for, and its comment wrapping is taken off: {sides}")
check(L.switch_sides(row(("forgelike", "a"), ("fabric", "b")), ["fabric", "forge", "neoforge"])[0][0] == {"forge", "neoforge"}, "forgelike is Forge and NeoForge")
check(L.switch_sides(row(("!neoforge", "a"), (None, "b")), ["fabric", "neoforge"]) == [({"fabric"}, "a"), ({"neoforge"}, "b")], "!neoforge is the other loaders")
for cond in ("fabric && >=1.21", ">=1.21.2", "iris || forge"):
    check(L.switch_sides(row((cond, "a"), (None, "b")), ["fabric", "forge"]) is None, f"a switch on {cond} is not a loader switch")
check(L.switch_sides(row(("fabric", "a")), ["fabric", "forge"]) is None, "a switch with one side pairs nothing")

# ---- files that exist once per loader -----------------------------------------------------------
tw = L.twins(["src/main/java/m/platform/fabric/PlatformImpl.java", "src/main/java/m/platform/neoforge/PlatformImpl.java", "src/main/java/m/Common.java",
              "fabric/src/main/java/m/fabric/ModFabric.java", "neoforge/src/main/java/m/neoforge/ModNeoForge.java", "src/main/java/m/fabric/OnlyHere.java"])
check(sorted(sorted(v.items()) for v in tw.values()) == [
    [("fabric", "fabric/src/main/java/m/fabric/ModFabric.java"), ("neoforge", "neoforge/src/main/java/m/neoforge/ModNeoForge.java")],
    [("fabric", "src/main/java/m/platform/fabric/PlatformImpl.java"), ("neoforge", "src/main/java/m/platform/neoforge/PlatformImpl.java")]],
      f"twins are files whose paths differ only by the loader's name, in folders or in the file name: {tw}")

# ---- a whole made-up mod ------------------------------------------------------------------------
FILES = {
    "src/main/java/m/platform/fabric/PlatformImpl.java": """package m.platform.fabric;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
public class PlatformImpl {
    public boolean isModLoaded(String id) {
        // FabricLoader.getInstance().getModContainer(id) is not what this method does
        return FabricLoader.getInstance().isModLoaded(id);
    }
    public void send(Object player, Object payload) {
        PayloadTypeRegistry.playS2C();
        ServerPlayNetworking.send(player, payload);
    }
    public void onlyFabric() {
        FabricLoader.getInstance().getGameDir();
    }
}
""",
    "src/main/java/m/platform/neoforge/PlatformImpl.java": """package m.platform.neoforge;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
public class PlatformImpl {
    public boolean isModLoaded(String id) {
        return ModList.get().isLoaded(id);
    }
    public void send(Object player, Object payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }
}
""",
    "src/main/java/m/Shared.java": """package m;
//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?} else {
/*import net.neoforged.fml.loading.FMLPaths;
*///?}
public class Shared {
    Object dir() {
        return null;
    }
}
""",
}
ROWS = [{"file": "src/main/java/m/Shared.java", "line": 9, "branches": [{"condition": "fabric", "code": "return FabricLoader.getInstance().getConfigDir();"},
                                                                       {"condition": None, "code": "/*return FMLPaths.CONFIGDIR.get();*/"}]},
        {"file": "src/main/java/m/Shared.java", "line": 20, "branches": [{"condition": ">=1.21", "code": "FabricLoader.getInstance().getGameDir();"}, {"condition": None, "code": "FMLPaths.GAMEDIR.get();"}]}]
with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    for rel, text in FILES.items():
        (root / rel).parent.mkdir(parents=True, exist_ok=True)
        (root / rel).write_text(text, encoding="utf-8")
    (root / "build" / "gen").mkdir(parents=True)
    (root / "build" / "gen" / "Junk.java").write_text("import net.fabricmc.api.EnvType;\nclass Junk { Object x = EnvType.CLIENT; }\n", encoding="utf-8")
    proven, used = L.mine("me/mod", root, ROWS, ["fabric", "neoforge"])
pairs = {(x, y): (how, site["where"]) for x, y, how, site in proven}
check(pairs == {(F + "loader.api.FabricLoader#isModLoaded", N + "fml.ModList#isLoaded"): ("one", "method isModLoaded"),
                (F + "loader.api.FabricLoader#getConfigDir", N + "fml.loading.FMLPaths#CONFIGDIR"): ("one", "switch at line 9")},
      f"the mod proves isModLoaded by its twin files and getConfigDir by its switch; send has two calls on one side, onlyFabric no twin, the version switch no loader: {pairs}")
check(F + "fabric.api.networking.v1.PayloadTypeRegistry#playS2C" in used and F + "loader.api.FabricLoader#getGameDir" in used and not any("EnvType" in u for u in used)
      and not any("getModContainer" in u for u in used) and N + "fml.loading.FMLPaths#CONFIGDIR" in used and N + "fml.loading.FMLPaths#GAMEDIR" in used,
      f"used holds every loader call the mod ships, the switched-off side of a switch included, but not build output and not comments: {sorted(used)}")

second = [(F + "loader.api.FabricLoader#isModLoaded", N + "fml.ModList#isLoaded", "one", {"repo": "you/mod", "file": "A.java", "where": "method has"})]
data = L.catalogue([("me/mod", "c1", proven, used), ("you/mod", "c2", second, {F + "loader.api.FabricLoader#isModLoaded", F + "loader.api.FabricLoader#getGameDir"})])
top = data["counterparts"][0]
check(top["a"].endswith("#isModLoaded") and top["mods"] == 2 and top["loaders"] == ["fabric", "neoforge"] and len(top["sites"]) == 2, f"a pair two mods prove comes first, with both sites: {top}")
check(data["used"]["fabric"] == [{"call": F + "loader.api.FabricLoader#getGameDir", "mods": 2, "counterpart_on": []},
                                 {"call": F + "loader.api.FabricLoader#isModLoaded", "mods": 2, "counterpart_on": ["neoforge"]}],
      f"used lists the calls two or more mods make, with the loaders a counterpart is known on: {data['used']}")
check(L.coverage(data, "fabric", "neoforge") == (2, 1) and L.coverage(data, "fabric", "forge") == (2, 0), "coverage counts the used calls that have a counterpart on that loader")

# ---- the committed catalogue --------------------------------------------------------------------
real = json.loads((L.OUT / "api.json").read_text(encoding="utf-8"))
pinned = {m["repo"]: m.get("commit") for m in L.SM.load(L.SM.MANIFEST)["mods"]} | {s["repo"]: s["commit"] for s in L.SM.load(L.EXTRA)["sources"]}
check({s["repo"]: s["commit"] for s in real["sources"]} == pinned, "coverage/loader/api.json was extracted from the sources as they are pinned now; run fetch and extract again")
known = {}
for c in real["counterparts"]:
    check([L.loader_of(c["a"]), L.loader_of(c["b"])] == c["loaders"] and c["loaders"][0] != c["loaders"][1] and c["mods"] >= 1 and c["sites"], f"a counterpart joins two loaders and names where it was seen: {c['a']}")
    checks -= 1
    known.setdefault(c["a"], set()).add(c["loaders"][1])
    known.setdefault(c["b"], set()).add(c["loaders"][0])
for loader, calls in real["used"].items():
    for u in calls:
        if L.loader_of(u["call"]) != loader or u["mods"] < 2 or set(u["counterpart_on"]) != known.get(u["call"], set()):
            raise AssertionError(f"used entry disagrees with the counterparts: {u}")
checks += 2
print(f"{checks} loader catalogue checks passed")
