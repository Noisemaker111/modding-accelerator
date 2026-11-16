"""The convert coverage check reads what a compiled mod uses and what the bridge provides. Run: python bench/test_convert_coverage.py

Offline: a class file is assembled here (its constant pool only), and the bridge and rules are the repo's own.
"""
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import convert_coverage as C  # noqa: E402
import loader_api  # noqa: E402

checks = 0


def check(ok, label):
    global checks
    if not ok:
        raise AssertionError(label)
    checks += 1


def class_file(this, refs):
    """A class file whose constant pool names this class and refs: (kind 'm'|'f', owner, name, desc)."""
    pool = []

    def utf8(s):
        pool.append(b"\x01" + struct.pack(">H", len(s.encode())) + s.encode())
        return len(pool)

    def cls(name):
        n = utf8(name)
        pool.append(b"\x07" + struct.pack(">H", n))
        return len(pool)

    me = cls(this)
    for kind, owner, name, desc in refs:
        o = cls(owner)
        n, d = utf8(name), utf8(desc)
        pool.append(b"\x0c" + struct.pack(">HH", n, d))
        nat = len(pool)
        pool.append((b"\x0a" if kind == "m" else b"\x09") + struct.pack(">HH", o, nat))
    body = b"".join(pool)
    return b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, 65, len(pool) + 1) + body + struct.pack(">HHHHHHH", 0x21, me, 0, 0, 0, 0, 0)


data = class_file("demo/Mod", [
    ("m", "net/fabricmc/fabric/api/event/Event", "register", "(Ljava/lang/Object;)V"),
    ("f", "net/fabricmc/fabric/api/event/lifecycle/v1/ServerLifecycleEvents", "SERVER_STARTED", "Lnet/fabricmc/fabric/api/event/Event;"),
    ("m", "java/lang/String", "length", "()I"),
])
classes, members = C.references(data)
check("net/fabricmc/fabric/api/event/Event" in classes and ("net/fabricmc/fabric/api/event/Event", "register", "m") in members, "a method a mod calls is read with its owner")
check(("net/fabricmc/fabric/api/event/lifecycle/v1/ServerLifecycleEvents", "SERVER_STARTED", "f") in members, "a field a mod reads is read with its owner")

rules = loader_api.load_rules()
corpus = {"mods": [{"id": "demo", "loader": "fabric"}, {"id": "old", "loader": "fabric"}]}
refs = {"mods": {
    "demo": {"minecraft": "26.3", "classes": ["net/fabricmc/fabric/api/event/Event", "net/fabricmc/fabric/api/nothing/v1/Gone"],
             "members": ["net/fabricmc/fabric/api/event/Event#register:m", "net/fabricmc/fabric/api/event/Event#noSuchMember:m"]},
    "old": {"minecraft": "1.20.1", "classes": ["net/fabricmc/fabric/api/event/Event"], "members": []},
}}
result = C.coverage(refs, rules, corpus)
missing = {e["symbol"]: e["mods"] for e in result["missing"]}
check("net.fabricmc.fabric.api.nothing.v1.Gone" in missing, "a loader class the bridge does not mirror is missing: " + str(missing))
check("net.fabricmc.fabric.api.event.Event#noSuchMember" in missing and "net.fabricmc.fabric.api.event.Event#register" not in missing,
      "a member the bridge class does not declare is missing; one it declares is covered: " + str(missing))
rows = {r["mod"]: r for r in result["mods"]}
check(rows["demo"]["bridge"] and not rows["old"]["bridge"] and rows["old"]["covered"] == 0,
      "a mod on a game version with no bridge layer has nothing covered: " + str(rows))
provides = C.bridge_provides(rules, "neoforge", "26.3")
check("get" in provides.get("net/neoforged/neoforge/registries/DeferredItem", set()), "a bridge class has the members of the bridge classes it extends (DeferredItem's get is DeferredHolder's)")
old = C.bridge_provides(rules, "neoforge", "1.21.1")
check("net/neoforged/neoforge/data/event/GatherDataEvent" in provides and "net/neoforged/neoforge/data/event/GatherDataEvent" not in old,
      "a class a version layer drops is not provided for that version")
game = {"net/minecraft/A": [["net/minecraft/B"], ["own"]], "net/minecraft/B": [["java/lang/Object"], ["inherited"]]}
check(C.declared_by_game(game, "net/minecraft/A", "inherited") and C.declared_by_game(game, "net/minecraft/A", "hashCode"),
      "a method the game declares on a supertype, or Object's, is the game's")
check(not C.declared_by_game(game, "net/minecraft/A", "getCapability"), "a method no game class in the chain declares was added by the loader")
sweep = {"runs": [{"id": "demo", "outcome": "loads, registers what the original does"}, {"id": "old", "outcome": "converted, does not compile"}]}
check("net.fabricmc.fabric.api.nothing.v1.Gone" in C.proven(refs, sweep) and not C.proven(refs, {"runs": [sweep["runs"][1]]}),
      "what a mod whose conversion compiled uses is handled; a failed one proves nothing")
print(f"{checks} convert coverage checks passed")
