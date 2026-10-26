"""Export the website's data from the repo into packages/core/src/generated/site-data.json.

Reads the same files, with the same rules, as the generated pages in docs/:
  coverage/ledger.json            every port that was run (bench/ledger_rule.py gives its colour)
  coverage/changes/index.json     every release, families, hubs and step sizes
  bench/data.json, bench/port/results.json, bench/mods/results.json   the bench timings
  SCOPE.md                        platforms, sides and the support ladder
Step colours are recomputed here from the current ledger with bench/ledger_rule.step_statuses,
so the map turns green as soon as a ledger row says so, even before change_routes.py reruns.

    python scripts/export_data.py
"""
import json
import re
import statistics
import subprocess
import sys
from pathlib import Path

SITE = Path(__file__).resolve().parent.parent
ROOT = SITE.parent
BENCH = ROOT / "bench"
sys.path.insert(0, str(BENCH))

import bench_page as bp  # noqa: E402
import ledger_rule  # noqa: E402

OUT = SITE / "packages" / "core" / "src" / "generated" / "site-data.json"


def load(rel):
    return json.loads((ROOT / rel).read_text(encoding="utf-8"))


ledger = load("coverage/ledger.json")
index = load("coverage/changes/index.json")
LOADER = bp.LOADER


def secs_text(v):
    return bp.secs(v)


# ---- bench ------------------------------------------------------------------------------------
def tested():
    rows = bp.tested()
    order = sorted(rows.items(), key=lambda kv: (-(ledger_rule.RANK[kv[1]["best"]["status"]] if kv[1]["best"] else 0), kv[0].lower()))
    out = []
    for name, r in order:
        b = r["best"]
        out.append({
            "mod": name, "what": r["what"], "loaders": sorted(r["loaders"]),
            "versions": sorted(r["versions"], key=bp.vkey), "date": r["date"],
            "best": {"status": b["status"], "from": b["from"], "to": b["to"]} if b else None,
            "errors": r.get("errors"),
        })
    return out


def runs():
    hops = sorted(ledger["hops"], key=lambda h: (h["date"], ledger_rule.RANK[h["status"]]), reverse=True)
    return [{"mod": h["mod"], "loader": LOADER.get(h["loader"], h["loader"]), "from": h["from"], "to": h["to"],
             "status": h["status"], "detail": h["detail"], "date": h["date"],
             "backport": bp.vkey(h["to"]) < bp.vkey(h["from"])} for h in hops]


def earlier():
    out = []
    for m in bp.bumps["mods"]:
        out.append({"mod": m["name"], "how": "Fabric, version pins bumped by hand", "port": f"{m['current_mc']} → {m['target_mc']}",
                    "errors": m["errors"]["total"], "firstBuild": secs_text(m["baseline"].get("seconds")),
                    "afterBump": secs_text(m["bumped"].get("seconds")), "date": bp.bumps["date"]})
    for m in bp.mods["mods"]:
        name = {"diorama": "Diorama", "minecolonies": "MineColonies"}.get(m["name"], m["name"])
        bump = m.get("bump") or {}
        out.append({"mod": name, "how": ", ".join(LOADER.get(x, x) for x in m.get("loaders", [])) + ", their build as it is",
                    "port": (m.get("minecraft") or {}).get("active", ""), "errors": m.get("errors"),
                    "firstBuild": secs_text(m["baseline"].get("seconds")), "afterBump": secs_text(bump.get("seconds")) or None,
                    "date": bp.mods["measured"]})
    return out


def speed():
    # The same rows as bench/bench_page.py speed(); keep the two lists in step.
    data, by, med = bp.data, bp.by, statistics.median
    d21, d22, d23 = "2026-10-21", "2026-10-22", data["editloop_measured"][:10]
    port263, d24 = bp.ledger_secs("AppleSkin", "1.21.6", "26.3")
    own263, _ = bp.ledger_secs("AppleSkin", "26.3", "26.3")
    base = [m["baseline"]["seconds"] for m in bp.bumps["mods"]]
    bumped = [m["bumped"]["seconds"] for m in bp.bumps["mods"]]
    fast = [med(e["fast"]) for e in data["editloop"]]
    warm = [med(e["warm"]) for e in data["editloop"]]
    groups = [
        ("Setup", [
            ("A mod's own first build, cold", {d21: (med(base), f"median of {len(base)} mods"), d22: (by("forgeloop", "their gradlew build -x test"), "MineColonies, Forge")}),
            ("Toolchain check before building", {d22: (by("toolchain", "Diorama: READY"), "Diorama, ready")}),
        ]),
        ("Compile", [
            ("Build after a version bump, warm caches", {d21: (med(bumped), f"median of {len(bumped)} mods")}),
            ("Port to a packaged build, mixins checked", {d24: (port263, "AppleSkin 1.21.6 → 26.3")}),
            ("A mod's own build through modaccel", {d24: (own263, "AppleSkin, 26.3")}),
            ("One-file edit, the mod's own build", {d23: (med(warm), f"median of {len(warm)} mods")}),
            ("One-file edit, modaccel fast", {d22: (by("forgeloop", "one-file edit, .\\modaccel fast"), "MineColonies"), d23: (med(fast), f"median of {len(fast)} mods")}),
        ]),
        ("Game start", [
            ("Client start, cold", {d21: (by("loop", "cold client boot + one check"), "Fabric 26.3, with one check"), d22: (by("neoforgeloop", "cold start, client listening"), "NeoForge 1.21.1")}),
            ("Dedicated server start", {d22: (by("multiplayer", "server boot"), "")}),
        ]),
        ("Checks", [
            ("Logic tests, no Minecraft", {d21: (by("loop", "logic tests, no Minecraft"), "")}),
            ("Screen change and a screenshot", {d21: (by("loop", "screen change + screenshot"), "Fabric 26.3"), d22: (by("hotreload", "NeoForge 1.21.1: screen edit, same JVM"), "NeoForge 1.21.1")}),
            ("Block entity in a live world", {d21: (by("loop", "block entity in a live world"), "")}),
            ("Scripted scenario and a screenshot", {d21: (by("loop", "scripted scenario + screenshot"), ""), d22: (by("multiplayer", "scenario, warm client"), "multiplayer, warm client")}),
        ]),
    ]
    dates = sorted({d for _, rows in groups for _, cells in rows for d in cells})
    return {
        "dates": dates,
        "machine": data["machine"],
        "groups": [{"name": g, "rows": [{"name": n, "cells": [{"date": d, "seconds": round(v, 2), "text": secs_text(v), "note": note}
                                                              for d, (v, note) in sorted(cells.items())]} for n, cells in rows]} for g, rows in groups],
    }


def loader_pairs():
    return [{"from": p["from"], "to": p["to"], "status": p["status"], "detail": p.get("detail", "")} for p in ledger["loader_pairs"]]


# ---- porting map ------------------------------------------------------------------------------
def porting_map():
    every = [v["id"] for v in index["allVersions"]]
    early = {v["id"] for v in index["allVersions"] if v["status"] == "blocked"}
    steps = ledger_rule.step_statuses(every, early, ledger["hops"], index["doing"])
    slim_steps = []
    for s in index["steps"]:
        c = s["coverage"]
        slim_steps.append({"from": s["from"], "to": s["to"], "linked": s["linked"], "breakingShare": s["breakingShare"], "oldSize": s["oldSize"],
                           "listed": c["listed"], "mappings": c["mappings"], "rule": c["rule"], "note": c["note"], "left": c["left"],
                           "backListed": c["back"]["listed"], "backMappings": c["back"]["mappings"], "backLeft": c["back"]["left"]})
    pos = {v: i for i, v in enumerate(every)}
    hubs = [f["hub"] for f in index["families"]["list"]]
    pairs = [(a, b) for a, b in zip(hubs, hubs[1:])] + [(every[i], every[j]) for i in range(0, len(every), 5) for j in range(i + 3, len(every), 9)]
    check = [{"from": a, "to": b, "status": ledger_rule.hop_status(every, steps, a, b)} for x, y in pairs for a, b in ((x, y), (y, x))]
    return {
        "check": check,
        "generated": index["generated"],
        "versions": [{"id": v["id"], "released": v["released"], "measured": v["status"] == "indexed", "family": v["family"]} for v in index["allVersions"]],
        "hubs": [f["hub"] for f in index["families"]["list"]],
        "steps": slim_steps,
        "stepStatus": [{"from": every[i], "to": every[i + 1], "fwd": steps[f"{every[i]}__{every[i + 1]}"]["fwd"], "back": steps[f"{every[i]}__{every[i + 1]}"]["back"]}
                       for i in range(len(every) - 1)],
        "runs": [{"mod": h["mod"], "loader": h["loader"], "from": h["from"], "to": h["to"], "status": h["status"], "date": h["date"]}
                 for h in ledger["hops"] if h["from"] in pos and h["to"] in pos],
    }


# ---- SCOPE.md ---------------------------------------------------------------------------------
def md_tables(text):
    """Every Markdown table in SCOPE.md, keyed by the heading above it, in order."""
    out, heading, rows = [], "", []
    for line in text.splitlines() + [""]:
        if line.startswith("#"):
            heading = line.lstrip("#").strip()
        if line.startswith("|"):
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if not all(re.fullmatch(r":?-+:?", c) for c in cells):
                rows.append(cells)
        elif rows:
            out.append({"heading": heading, "head": rows[0], "rows": rows[1:]})
            rows = []
    return out


def scope():
    text = (ROOT / "SCOPE.md").read_text(encoding="utf-8")
    tables = md_tables(text)
    status = re.search(r"^Status: (.+)$", text, re.M)
    platforms, sides = [t for t in tables if t["heading"].startswith("1.")]
    ladder = next(t for t in tables if t["heading"].startswith("2."))
    policy = re.search(r"## 3\. Policy\n(.*?)\n## ", text, re.S).group(1)
    who = re.search(r"\*\*Who we're for\.\*\* (.+)", policy).group(1)
    return {"status": status.group(1) if status else "", "who": who, "platforms": platforms, "sides": sides, "ladder": ladder}


def numbered(section):
    """A SCOPE.md numbered list of **Title.** text, with optional - points under each item."""
    text = (ROOT / "SCOPE.md").read_text(encoding="utf-8")
    sec = re.search(rf"## {re.escape(section)}[^\n]*\n(.*?)\n## ", text + "\n## ", re.S).group(1)
    out = []
    for m in re.finditer(r"^\d+\. \*\*(.+?)\*\*(.*?)(?=^\d+\. |\Z)", sec, re.S | re.M):
        lines = [l.strip() for l in m.group(2).strip().splitlines() if l.strip()]
        out.append({"title": m.group(1).rstrip("."), "text": lines[0] if lines and not lines[0].startswith("- ") else "",
                    "points": [l[2:] for l in lines if l.startswith("- ")]})
    return out


def intake():
    return numbered("4. Intake")


def policy():
    return numbered("3. Policy")


# ---- README.md, ROADMAP.md, AGENTS.md, the CLI help -----------------------------------------
def readme():
    text = (ROOT / "README.md").read_text(encoding="utf-8")
    use = re.search(r"\*\*Use this to:\*\*\n\n(.*?)\n\n", text, re.S).group(1)
    bullets = []
    for line in use.splitlines():
        m = re.match(r"- \*\*(.+?)\*\* (.+)", line)
        if m:
            bullets.append({"title": m.group(1).rstrip("."), "text": m.group(2)})
    limits = []
    for line in re.search(r"## Limits\n\n(.*?)\n\n", text + "\n\n", re.S).group(1).splitlines():
        m = re.match(r"- \*\*(.+?)\*\* (.+)", line)
        if m:
            limits.append({"title": m.group(1).rstrip("."), "text": m.group(2)})
    beta = re.search(r"^Beta ([^·\n]+)·", text, re.M)
    release = re.search(r"\[download\]\((https://[^)]+)\)", text)
    goal = re.search(r"## Goal\n\n(.*?)\n\n", text, re.S).group(1)
    quick = re.search(r"## Quick start\n\n(.*?)\n```sh\n(.*?)```\n\n(.*?)\n\n## ", text, re.S)
    return {"use": bullets, "limits": limits, "beta": beta.group(1).strip() if beta else "", "release": release.group(1) if release else "",
            "goal": " ".join(goal.split()), "quickIntro": " ".join(quick.group(1).split()), "quickCommands": quick.group(2).rstrip().splitlines(),
            "quickAfter": " ".join(quick.group(3).split())}


def roadmap():
    text = (ROOT / "ROADMAP.md").read_text(encoding="utf-8")
    goal = re.search(r"^Goal: (.+?)\n\n", text, re.S | re.M).group(1)
    phases = []
    for m in re.finditer(r"^## (Phase \d+) — (.+?)\n(.*?)(?=^## |\Z)", text, re.S | re.M):
        steps = []
        for s in re.finditer(r"^(\d+[a-z]?)\. (.+?)(?=^\d+[a-z]?\. |\Z)", m.group(3), re.S | re.M):
            body = " ".join(s.group(2).split())
            status = re.search(r"\*\*(done|doing|next|later)\*\*", body)
            steps.append({"id": s.group(1), "status": status.group(1) if status else "later",
                          "text": re.sub(r"\s*\*\*(done|doing|next|later)\*\*\s*(—\s*)?", " ", body).strip().replace("`", "")})
        phases.append({"id": m.group(1), "title": m.group(2).strip(), "steps": steps})
    decisions = []
    dec = re.search(r"## Decisions\n(.*)", text, re.S)
    if dec:
        for line in dec.group(1).splitlines():
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if line.startswith("|") and len(cells) == 3 and cells[0] != "Question" and not re.fullmatch(r"-+", cells[0]):
                decisions.append({"question": cells[0], "choice": cells[1].replace("`", ""), "date": cells[2]})
    return {"goal": " ".join(goal.split()), "phases": phases, "decisions": decisions}


def java_strings(expr):
    out = []
    for m in re.finditer(r'"((?:[^"\\]|\\.)*)"', expr):
        out.append(m.group(1).encode("utf-8").decode("unicode_escape").encode("latin-1").decode("utf-8"))
    return "".join(out)


def cli_help():
    src = (ROOT / "tools" / "cli" / "Modaccel.java").read_text(encoding="utf-8")
    body = re.search(r"static String help\(String command\) \{\s*return switch \(command\) \{(.*?)\n        \};", src, re.S).group(1)
    topics = []
    for m in re.finditer(r'(case "([^"]+)"|default) -> (.*?");\s*\n', body + "\n", re.S):
        topics.append({"command": m.group(2) or "", "text": java_strings(m.group(3))})
    usage = next(t["text"] for t in topics if t["command"] == "")
    commands = re.search(r"modaccel <([^>]+)>", usage).group(1).split("|")
    return {"usage": usage, "commands": commands, "topics": [t for t in topics if t["command"]]}


def agents_rules():
    text = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    keep = []
    for start in ("Optimize the whole", "Expand through untouched", "Finish the pending final", "Run builds and game launches"):
        m = re.search(rf"^({re.escape(start)}.*?)\n\n", text, re.S | re.M)
        if m:
            keep.append(" ".join(m.group(1).split()))
    return keep


def facts():
    sc = load("coverage/stonecutter/index.json")["totals"]
    rules = load("knowledge/fixes.json")["rules"]
    fams = index["families"]["list"]
    return {
        "indexedReleases": len(index["versions"]), "allReleases": len(index["allVersions"]),
        "families": len(fams), "measuredFamilies": sum(1 for f in fams if not f.get("estimated")),
        "firstIndexed": index["versions"][0]["id"], "lastIndexed": index["versions"][-1]["id"],
        "stonecutterMods": sc["mods"], "stonecutterSwitches": sc["switches"],
        "rules": len(rules), "stonecutterRules": sum(1 for r in rules if r.get("source") == "stonecutter"),
        "blockedReason": index["blocked"][0]["reason"] if index["blocked"] else "",
    }


def commit():
    try:
        return subprocess.run(["git", "-C", str(ROOT), "rev-parse", "--short", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return ""


def main():
    hops = ledger["hops"]
    t = tested()
    vers = sorted({v for r in t for v in r["versions"]}, key=bp.vkey)
    out = {
        "commit": commit(),
        "rule": {"word": ledger_rule.WORD, "colour": ledger_rule.COLOUR, "said": ledger_rule.SAID, "rank": ledger_rule.RANK},
        "bench": {
            "facts": {"mods": len(t), "versions": len(vers), "runs": len(hops), "working": sum(1 for h in hops if h["status"] in ("behaviour", "load"))},
            "versionsTouched": vers,
            "tested": t, "runs": runs(), "earlier": earlier(), "speed": speed(),
            "loaders": [LOADER.get(x, x) for x in ledger["loaders"]],
            "loaderKeys": ledger["loaders"],
            "loaderPairs": loader_pairs(),
        },
        "map": porting_map(),
        "scope": {**scope(), "intake": intake(), "policy": policy()},
        "readme": readme(),
        "roadmap": roadmap(),
        "help": cli_help(),
        "rules": agents_rules(),
        "facts": facts(),
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    check = out["map"].pop("check")
    OUT.write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    # Python's colour for a sample of hops; packages/core/test checks the TypeScript rule against it.
    (OUT.parent / "hop-check.json").write_text(json.dumps(check, ensure_ascii=False), encoding="utf-8")
    print("wrote", OUT.relative_to(SITE), OUT.stat().st_size, "bytes")


if __name__ == "__main__":
    main()
