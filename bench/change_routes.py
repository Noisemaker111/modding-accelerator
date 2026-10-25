"""Families, hubs, rule coverage and ledger runs for the change index.

Reads what tools/ChangeIndex.java wrote (coverage/changes/versions.json and one <from>__<to>.json
per neighbouring release pair), plus knowledge/*.json and coverage/ledger.json, and writes
coverage/changes/index.json: the data a port router (and docs/porting-map.html) reads.

    python bench/change_routes.py [--refresh-mods]

Mod counts per version come from Modrinth's public search API and are kept in index.json, so a
re-run without --refresh-mods is offline and repeatable.
"""
import json
import re
import sys
import urllib.parse
import urllib.request
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHANGES = ROOT / "coverage" / "changes"
KNOWLEDGE = ROOT / "knowledge"
OUT = CHANGES / "index.json"

# A step that breaks at least this share of the old release's names (classes plus non-private
# methods and fields that were removed, renamed or changed signature) starts a new family.
THRESHOLD = 0.01
THRESHOLD_PLAIN = (
    "A new family starts at any update that breaks at least 1 in 100 of the game's names "
    "(classes, methods and fields a mod can call that were removed, renamed or given a new signature). "
    "Updates inside a family break fewer than 1 in 200. Measured over every release from 1.14.4, no update "
    "falls between 0.5% and 1.1%, so the line sits in a natural gap rather than at a chosen round number."
)
HUB_PLAIN = (
    "Each family's hub is the release in it with the most mods on Modrinth (counted on the date shown), "
    "because a port that starts or ends there helps the most people. A family of one release is its own hub."
)
MATCH_PLAIN = [
    "A change counts as handled by the mappings when the two releases are linked by Fabric's intermediary ids "
    "(every release up to 1.21.11) and the change is a rename or a move: port --fix rewrites those imports and "
    "call sites from the generated map. A change into 26.1 or later has no shared id, so nothing there is "
    "counted as mapped.",
    "A change counts as handled by a rule when a rule in knowledge/ applies to that update (its jump range "
    "covers it) and names the change: for a rename the rule's old side names the old name and its new side the "
    "new name; for a removal or a new signature the rule's old side names the member (names shorter than six "
    "letters also need the class name). This is matching by name and is approximate: a rule can name a "
    "method without fixing every use of it.",
    "Automatic rules are knowledge/fixes.json (applied by port --fix) and knowledge/renames.json. A written how-to "
    "is a note in knowledge/version-changes.json or knowledge/structural.json that names the change: it explains "
    "what to do and shows in the port report, but nothing is edited for you.",
    "Rules written in yarn names (knowledge/learned and fixes marked yarn) are not compared: the index uses "
    "Mojang's names, and the two only sometimes agree.",
]
POPULAR_NOTE = "Mods per release on Modrinth (project_type:mod, any loader)."
COMMON = set("""get set is has add remove create make build apply accept run init update render tick test of
from to with value values name type key id size map list copy equals hashCode toString close open start stop
read write load save reset clear draw put find parse codec CODEC STREAM_CODEC""".split())
RANK = {"behaviour": 4, "load": 3, "build": 2, "stop": 1}


def load(p):
    return json.loads(Path(p).read_text(encoding="utf-8"))


def simple(fqn):
    return fqn.rsplit(".", 1)[-1].split("$")[-1]


def member_name(sig):
    s = sig[8:] if sig.startswith("private ") else sig
    return re.split(r"[(:]", s, 1)[0]


TOKEN = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


def tokens(*texts):
    out = set()
    for t in texts:
        if not t:
            continue
        # Regex escapes (\. \( \s) are not part of a name.
        t = re.sub(r"\\[A-Za-z]", " ", str(t))
        out.update(x for x in TOKEN.findall(t) if len(x) > 2)
    return out


def mod_counts(versions, old):
    counts = dict(old.get("counts", {})) if old else {}
    for v in versions:
        facets = json.dumps([[f"versions:{v}"], ["project_type:mod"]])
        url = "https://api.modrinth.com/v2/search?limit=1&facets=" + urllib.parse.quote(facets)
        req = urllib.request.Request(url, headers={"User-Agent": "Noisemaker111/modding-accelerator change-index"})
        with urllib.request.urlopen(req, timeout=30) as r:
            counts[v] = json.load(r)["total_hits"]
    return {"source": "https://api.modrinth.com/v2/search", "note": POPULAR_NOTE, "date": date.today().isoformat(), "counts": counts}


def main():
    refresh = "--refresh-mods" in sys.argv
    meta = load(CHANGES / "versions.json")
    indexed = [v["id"] for v in meta["versions"] if v["status"] == "indexed"]
    order = {v["id"]: i for i, v in enumerate(meta["versions"])}
    old = load(OUT) if OUT.exists() else {}
    mods = old.get("mods")
    if refresh or not mods or set(indexed) - set(mods.get("counts", {})):
        mods = mod_counts(indexed, mods)

    # ---- steps -----------------------------------------------------------------------------
    steps = []
    for a, b in zip(indexed, indexed[1:]):
        d = load(CHANGES / f"{a}__{b}.json")
        steps.append({"from": a, "to": b, "file": f"{a}__{b}.json", "linked": d["linked"], "breakingShare": d["breakingShare"],
                      "broken": d["broken"], "oldSize": d["oldSize"], "counts": d["counts"], "_data": d})

    # ---- families and hubs -----------------------------------------------------------------
    families, cur = [], [indexed[0]]
    for s in steps:
        s["newFamily"] = s["breakingShare"] >= THRESHOLD
        if s["newFamily"]:
            families.append(cur)
            cur = [s["to"]]
        else:
            cur.append(s["to"])
    families.append(cur)
    fam_out, family_of = [], {}
    for vs in families:
        best = max(vs, key=lambda v: (mods["counts"].get(v, 0), order[v]))
        reason = "only release in the family" if len(vs) == 1 else "most mods on Modrinth in this family"
        fid = f"{vs[0]}" if len(vs) == 1 else f"{vs[0]} to {vs[-1]}"
        fam_out.append({"id": fid, "versions": vs, "hub": best, "hubReason": reason,
                        "mods": {v: mods["counts"].get(v, 0) for v in vs}})
        for v in vs:
            family_of[v] = fid

    # ---- rules -----------------------------------------------------------------------------
    def jump_range(j):
        if not j:
            return None
        m = re.match(r"\s*(\S+)\s*->\s*(\S+)", j)
        if m and m.group(1) in order and m.group(2) in order:
            return order[m.group(1)], order[m.group(2)]
        m = re.match(r"\s*(\S+)\+", j)
        if m and m.group(1) in order:
            return order[m.group(1)] - 1, 10**6
        return None

    rules, skipped = [], {"yarn": 0, "noRange": 0, "notApplied": 0}
    def add(source, rid, jump, frm, to, auto=True, match=None):
        if jump and "yarn" in jump:
            skipped["yarn"] += 1
            return
        r = jump_range(jump)
        if r is None:
            skipped["noRange"] += 1
            return
        rules.append({"source": source, "id": rid, "jump": jump, "range": r, "from": tokens(frm), "to": tokens(to), "auto": auto, "match": match})

    for r in load(KNOWLEDGE / "renames.json"):
        if r.get("skip"):
            skipped["notApplied"] += 1
            continue
        add("renames.json", f"{r['from']} -> {r['to']}", r.get("jump"), r["from"], r["to"])
    for i, r in enumerate(load(KNOWLEDGE / "fixes.json")["rules"]):
        ap = r.get("apply", {})
        add("fixes.json", r.get("id", f"rule {i}"), r.get("jump"),
            " ".join(str(x) for x in [r.get("match"), ap.get("find"), ap.get("literal")] if x),
            " ".join(str(x) for x in [ap.get("replace"), ap.get("with"), " ".join(ap.get("ensureImports", []) or [])] if x))
    for i, r in enumerate(load(KNOWLEDGE / "version-changes.json")):
        add("version-changes.json", f"{r.get('kind')}: {str(r.get('from'))[:60]}", r.get("jump"), r.get("from"), r.get("to"), auto=False)
    for r in load(KNOWLEDGE / "structural.json"):
        if r.get("jump") and "yarn" in r["jump"]:
            skipped["yarn"] += 1
            continue
        rng = jump_range(r.get("jump"))
        if rng is None:
            skipped["noRange"] += 1
            continue
        rules.append({"source": "structural.json", "id": r["id"], "jump": r["jump"], "range": rng, "from": set(), "to": set(), "auto": False,
                      "match": re.compile(r["match"])})

    def applies(rule, s):
        lo, hi = rule["range"]
        return lo <= order[s["from"]] and order[s["to"]] <= hi

    # ---- coverage --------------------------------------------------------------------------
    def items(d):
        """Every breaking change the page lists: (kind, owner, text, old name, new name, private)."""
        for o, n in d["classesRenamed"]:
            yield ("class renamed", o, f"{o} -> {n}", simple(o), simple(n), False)
        for c in d["classesRemoved"]:
            yield ("class removed", c, c, simple(c), None, False)
        for owner, lists in d["members"].items():
            for key in ("methodsRenamed", "fieldsRenamed", "methodsChanged", "fieldsChanged", "methodsRemoved", "fieldsRemoved"):
                for it in lists.get(key, []):
                    old_sig, _, new_sig = it.partition(" -> ")
                    yield (key, owner, it, member_name(old_sig), member_name(new_sig) if new_sig else None, it.startswith("private "))

    def status(s, rs, kind, owner, old, new):
        renamed = kind in ("class renamed", "methodsRenamed", "fieldsRenamed")
        osimple = simple(owner)
        def names(r):
            if r["source"] == "structural.json":
                return bool(r["match"].search(old) or r["match"].search(osimple))
            if renamed and new and old != new:
                return old in r["from"] and new in r["to"]
            return old in r["from"] and old not in COMMON and (len(old) >= 6 or osimple in r["from"] | r["to"])
        for r in rs:
            if r["auto"] and names(r):
                return "rule", r
        if renamed and s["linked"] == "exact":
            return "mappings", None
        for r in rs:
            if not r["auto"] and names(r):
                return "note", r
        return "left", None

    def cover(s):
        rs = [r for r in rules if applies(r, s)]
        tally = {"mappings": 0, "rule": 0, "note": 0, "left": 0}
        tally_all = dict(tally)
        hits, handled = {}, []
        for kind, owner, text, old, new, private in items(s["_data"]):
            st, r = status(s, rs, kind, owner, old, new)
            tally_all[st] += 1
            if not private:
                tally[st] += 1
            if r is not None:
                key = f"{r['source']}: {r['id']}"
                hits[key] = hits.get(key, 0) + 1
                handled.append([kind, owner, text, st, key])
        # A backport meets the same file reversed: what was added is missing, renames run backwards.
        d = s["_data"]
        back = {"mappings": 0, "left": 0}
        for o, n in d["classesRenamed"]:
            back["mappings" if s["linked"] == "exact" else "left"] += 1
        back["left"] += len(d["classesAdded"])
        for owner, lists in d["members"].items():
            for key in ("methodsRenamed", "fieldsRenamed", "methodsChanged", "fieldsChanged", "methodsAdded", "fieldsAdded"):
                for it in lists.get(key, []):
                    if it.startswith("private "):
                        continue
                    back["mappings" if key.endswith("Renamed") and s["linked"] == "exact" else "left"] += 1
        back["listed"] = back["mappings"] + back["left"]
        listed = sum(tally.values())
        return {"listed": listed, "listedWithPrivate": sum(tally_all.values()), **tally, "withPrivate": tally_all,
                "back": back, "rulesInRange": len(rs), "ruleHits": [{"rule": k, "changes": v} for k, v in sorted(hits.items(), key=lambda x: -x[1])],
                "matched": handled}

    ledger = load(ROOT / "coverage" / "ledger.json")
    def best_run(a, b):
        fwd = order[a] < order[b]
        best = None
        for h in ledger["hops"]:
            if h["from"] not in order or h["to"] not in order:
                continue
            lo, hi = order[h["from"]], order[h["to"]]
            if (lo < hi) != fwd:
                continue
            lo, hi = min(lo, hi), max(lo, hi)
            if lo <= min(order[a], order[b]) and max(order[a], order[b]) <= hi:
                exact = h["from"] == a and h["to"] == b
                score = (RANK.get(h["status"], 0), exact, -(hi - lo))
                if best is None or score > best[0]:
                    best = (score, {"mod": h["mod"], "loader": h["loader"], "from": h["from"], "to": h["to"], "status": h["status"],
                                    "exact": exact, "date": h.get("date")})
        return best[1] if best else None

    for s in steps:
        s["coverage"] = cover(s)
        s["bestRun"] = best_run(s["from"], s["to"])
        s["backportRun"] = best_run(s["to"], s["from"])

    hubs = [f["hub"] for f in fam_out]
    hub_hops = []
    for a, b in zip(hubs, hubs[1:]):
        span = [s for s in steps if order[a] <= order[s["from"]] and order[s["to"]] <= order[b]]
        tot = {k: sum(s["coverage"][k] for s in span) for k in ("listed", "mappings", "rule", "note", "left")}
        hub_hops.append({"from": a, "to": b, "steps": [s["file"] for s in span], "broken": sum(s["broken"] for s in span),
                         "coverage": tot, "bestRun": best_run(a, b), "backportRun": best_run(b, a)})

    blocked = [{"id": v["id"], "released": v["released"], "reason": v["reason"]} for v in meta["versions"] if v["status"] == "blocked"]
    out = {
        "about": "Change index: what changed in Minecraft between neighbouring releases, in Mojang's official names, with families, hubs, rule coverage and the best recorded port per step. Generated by tools/ChangeIndex.java and bench/change_routes.py.",
        "generated": date.today().isoformat(),
        "names": "official (Mojang)",
        "counting": "Counts in each step file are per listed change. 'Api' counts leave out private members. breakingShare = (classes removed or renamed + non-private methods and fields removed, renamed or with a new signature, including those of removed classes) / (classes + non-private methods and fields of the older release).",
        "linked": {"exact": "Both releases are obfuscated and joined through Fabric intermediary ids: a rename is proven by the shared id.",
                   "by-name": "At least one release ships unobfuscated (26.1+), so there is no shared id: classes and members are matched by name, and renames and moves are best guesses (same simple name, nested classes that moved together, half or more member names shared, or one member on each side with the same signature)."},
        "families": {"rule": THRESHOLD_PLAIN, "threshold": THRESHOLD, "hubRule": HUB_PLAIN, "list": fam_out},
        "routing": "Route from X to Y: neighbouring steps from X to its family's hub, then hub to hub, then neighbouring steps from the last hub to Y. A backport walks the same steps in reverse (added and removed swap, renames flip). A step inside a family breaks under 0.5% of names.",
        "matching": MATCH_PLAIN,
        "rulesSkipped": skipped,
        "mods": mods,
        "versions": [dict(v, family=family_of.get(v["id"])) for v in meta["versions"] if v["status"] == "indexed"],
        "blocked": blocked,
        "steps": [{k: v for k, v in s.items() if k != "_data"} for s in steps],
        "hubHops": hub_hops,
        "runs": [{k: h.get(k) for k in ("mod", "loader", "from", "to", "status", "date")} for h in ledger["hops"] if h["from"] in order and h["to"] in order],
    }
    OUT.write_text(json.dumps(out, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    print("wrote", OUT, OUT.stat().st_size, "bytes;", len(fam_out), "families; hubs:", ", ".join(hubs))
    for s in steps:
        c = s["coverage"]
        print(f"  {s['from']:>8} -> {s['to']:<8} {100*s['breakingShare']:5.1f}% {'NEW FAMILY' if s['newFamily'] else '          '} "
              f"listed {c['listed']:5} mapped {c['mappings']:4} rule {c['rule']:3} note {c['note']:3} left {c['left']:5}"
              f"  run: {s['bestRun']['mod'] + ' ' + s['bestRun']['status'] if s['bestRun'] else '-'}")


if __name__ == "__main__":
    main()
