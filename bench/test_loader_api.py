"""The loader-change rules and their record of published-jar symbols agree. Run: python bench/test_loader_api.py

Offline: reads knowledge/loaders.json and coverage/loader/api.json, and parses a class file built here, so it
needs no checkout, no jar and no network. The rules' before/after tests themselves run in Java: mmt self-test.
"""
import hashlib
import io
import json
import struct
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import loader_api as L  # noqa: E402

checks = 0


def check(ok, label):
    global checks
    if not ok:
        raise AssertionError(label)
    checks += 1


# ---- reading class files --------------------------------------------------------------------------

def class_file(name, fields, methods):
    """A minimal class file: a constant pool of the names, no code."""
    pool = []

    def utf8(text):
        pool.append(b"\x01" + struct.pack(">H", len(text.encode())) + text.encode())
        return len(pool)

    this_name = utf8(name.replace(".", "/"))
    pool.append(b"\x07" + struct.pack(">H", this_name))
    this_class = len(pool)
    descriptor = utf8("I")
    members = [(utf8(f), descriptor) for f in fields], [(utf8(m), utf8("()V")) for m in methods]
    out = b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, 65, len(pool) + 1) + b"".join(pool)
    out += struct.pack(">HHHH", 0x21, this_class, 0, 0)
    for group in members:
        out += struct.pack(">H", len(group))
        for n, d in group:
            out += struct.pack(">HHHH", 1, n, d, 0)
    return out + struct.pack(">H", 0)


name, members = L.class_members(class_file("a.b.Thing$Inner", ["FIELD"], ["run", "<init>"]))
check(name == "a.b.Thing$Inner" and members == {"FIELD", "run", "<init>"}, f"a class file's name and members are read: {name} {members}")

inner = io.BytesIO()
with zipfile.ZipFile(inner, "w") as z:
    z.writestr("x/Nested.class", class_file("x.Nested", [], ["go"]))
outer = io.BytesIO()
with zipfile.ZipFile(outer, "w") as z:
    z.writestr("x/Top.class", class_file("x.Top", ["A"], []))
    z.writestr("META-INF/jars/module.jar", inner.getvalue())
index = L.index_jar(outer.getvalue(), {})
check(index == {"x.Top": {"A"}, "x.Nested": {"go"}}, f"nested module jars (how Fabric API ships) are read too: {index}")
check(L.split("neoforge|a.B$C#d") == ("neoforge", "a.B$C", "d") and L.split("fml|a.B") == ("fml", "a.B", ""), "a symbol is artifact|class#member")
check(L.jar_url({"maven": "https://m", "coordinate": "net.x:y:1.0+26.3:universal"}) == "https://m/net/x/y/1.0%2B26.3/y-1.0+26.3-universal.jar", "a Maven coordinate becomes its jar's URL")

mappings = b"""# {"id":"com.mojang"}
net.minecraft.core.Holder -> jm:
# {"fileName":"Holder.java","id":"sourceFile"}
    java.lang.Object value() -> a
    12:14:boolean is(net.minecraft.resources.ResourceLocation) -> a
net.minecraft.resources.ResourceKey -> akq:
    net.minecraft.resources.ResourceLocation location -> c
"""
named = L.index_mappings(mappings, {})
check(named == {"net.minecraft.core.Holder": {"value", "is"}, "net.minecraft.resources.ResourceKey": {"location"}}, f"Mojang's mappings give an obfuscated release's class and member names: {named}")

# ---- the committed rules ----------------------------------------------------------------------------

rules = L.load_rules()
ids = [r["id"] for _, r in L.rules_of(rules)]
check(len(ids) == len(set(ids)), f"rule ids are unique: {ids}")
for kind, rule in L.rules_of(rules):
    syms = [s for side in ("fabric", "neoforge") for s in rule.get("symbols", {}).get(side, [])]
    if not syms or any(L.split(s)[0] not in rules["published"] for s in syms):
        raise AssertionError(f"{rule['id']} names its symbols, each in a jar knowledge/loaders.json publishes")
    if kind != "entrypoints":
        test = rule.get("test", {})
        if not test.get("fabric") or not test.get("neoforge"):
            raise AssertionError(f"{rule['id']} has a before/after test on both loaders")
    text = json.dumps(rule).lower()
    for borrowed in ("architectury", "stonecutter", "github.com/"):
        if borrowed in text:
            raise AssertionError(f"{rule['id']} cites {borrowed}: rules come from the loaders' own APIs, not other mods")
checks += 3
for key, entry in rules["published"].items():
    check(entry.get("manifest") or entry.get("maven", "").startswith("https://maven."), f"{key} is read from its owner's own Maven repository or Mojang's manifest")

# ---- the record verify wrote ------------------------------------------------------------------------

record = json.loads((L.OUT / "api.json").read_text(encoding="utf-8"))
current = hashlib.sha256(L.RULES.read_bytes().replace(b"\r\n", b"\n")).hexdigest()
check(record["rules_sha256"] == current, "coverage/loader/api.json was written from knowledge/loaders.json as it is now: run python bench/loader_api.py verify")
want = L.symbols(rules)
check(set(record["rules"]) == set(want), "the record covers every rule and only them")
for rule, syms in want.items():
    got = record["rules"][rule]
    if [s["symbol"] for s in got] != syms or not all(s["found"] for s in got):
        raise AssertionError(f"{rule}: every symbol was found in a published jar")
checks += 1
for key in {L.split(s)[0] for syms in want.values() for s in syms}:
    art = record["artifacts"].get(key, {})
    pinned = rules["published"][key]
    check(len(art.get("sha256", "")) == 64 and (pinned.get("manifest") or art.get("coordinate") == pinned["coordinate"]), f"{key} was read at the pinned release, with its SHA-256")
check(not (L.OUT / "sources.json").exists(), "no list of other people's mods is read for loader rules")
print(f"{checks} loader rule checks passed")
