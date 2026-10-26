"""One rule for what a ledger run means, shared by the bench page and the porting map.

A run in coverage/ledger.json reached one of four statuses. behaviour and load are green (it
works in the game), build is yellow (packaged, not yet loaded), stop is red (named errors left).
behaviour needs an assertion on the mod's own state; a frame change or a reloaded save is "load".

A run proves only its own loader and the updates it crossed. So every update between two
neighbouring releases gets a colour per loader and per direction:
  green  - a run on that loader, in that direction, that crossed the update loaded or passed a
           behaviour check;
  yellow - such a run reached a packaged build, or a ROADMAP "doing" step for that loader covers it;
  red    - such a run stopped with named errors, nothing was tried on that loader, or it is blocked
           (no official mappings before 1.14.4).
A longer hop (hub to hub) takes the worst colour of its updates, and says which part is which.
"""

RANK = {"behaviour": 4, "load": 3, "build": 2, "stop": 1}
COLOUR = {"behaviour": "green", "load": "green", "build": "yellow", "stop": "red"}
WORD = {"behaviour": "Behaviour checked in the game", "load": "Loads in the real game",
        "build": "Builds, mixins checked", "stop": "Stops with named errors"}
SAID = {"behaviour": "behaviour checked in the game", "load": "loads in the real game",
        "build": "packaged build, not loaded yet", "stop": "stopped with named errors"}
BLOCKED = "No official mappings before 1.14.4, so nothing here is measured and modaccel cannot port it yet."
WORST = {"red": 0, "yellow": 1, "green": 2}
LOADERS = ["fabric", "neoforge", "forge", "quilt"]
NAME = {"fabric": "Fabric", "neoforge": "NeoForge", "forge": "Forge", "quilt": "Quilt"}


def doing_loader(step, runs):
    """The loaders a ROADMAP "doing" step is about: named in its text, else its ledger runs' loaders, else none."""
    if "loaders" in step:
        return set(step["loaders"])
    low = step.get("text", "").lower()
    named = {l for l in ("fabric", "neoforge", "quilt") if l in low}
    if "forge" in low.replace("neoforge", ""):
        named.add("forge")
    if named:
        return named
    return {r["loader"] for r in runs if r["from"] == step["from"] and r["to"] == step["to"]}


def step_statuses(versions, blocked, runs, doing, loader):
    """{"a__b": {"fwd": status, "back": status}} on one loader, for every neighbouring pair, a older than b."""
    pos = {v: i for i, v in enumerate(versions)}
    runs = [r for r in runs if r.get("loader") == loader]
    doing = [d for d in doing if loader in doing_loader(d, runs)]
    name = NAME.get(loader, loader)

    def covers(frm, to, lo, fwd):
        if frm not in pos or to not in pos or (pos[frm] < pos[to]) != fwd:
            return False
        return min(pos[frm], pos[to]) <= lo and lo + 1 <= max(pos[frm], pos[to])

    def one(lo, fwd):
        a, b = versions[lo], versions[lo + 1]
        if a in blocked or b in blocked:
            return {"c": "red", "word": "Blocked", "why": BLOCKED}
        hit = sorted((r for r in runs if covers(r["from"], r["to"], lo, fwd)), key=lambda r: -RANK[r["status"]])
        best = hit[0] if hit else None

        def tag(r):
            same = sorted({h["mod"] for h in hit if COLOUR[h["status"]] == COLOUR[r["status"]]})
            n = len(same)
            return (f"{n} {name} mod{'s' if n != 1 else ''} ported across ({', '.join(same)}). Best: "
                    f"{r['mod']} {r['from']} → {r['to']}: {SAID[r['status']]}")
        if best and COLOUR[best["status"]] == "green":
            return {"c": "green", "word": "Works", "why": tag(best) + ".", "run": best["status"]}
        if best and best["status"] == "build":
            return {"c": "yellow", "word": "Builds", "why": tag(best) + ".", "run": "build"}
        work = next((d for d in doing if covers(d["from"], d["to"], lo, fwd)), None)
        if work:
            out = {"c": "yellow", "word": "Being worked on", "why": f"ROADMAP step {work['step']} ({name}): {work['from']} → {work['to']}."}
            if best:
                out["why"] += " So far: " + tag(best) + "."
                out["run"] = best["status"]
            return out
        if best:
            return {"c": "red", "word": "Not working yet", "why": tag(best) + ".", "run": best["status"]}
        return {"c": "red", "word": "Not tried", "why": f"No {name} port has been run here yet."}

    return {f"{versions[i]}__{versions[i + 1]}": {"fwd": one(i, True), "back": one(i, False)} for i in range(len(versions) - 1)}


def all_statuses(versions, blocked, runs, doing):
    """{loader: step_statuses(...)} for every loader the ledger knows."""
    return {l: step_statuses(versions, blocked, runs, doing, l) for l in LOADERS}


def hop_status(versions, steps, a, b):
    """Worst colour of the updates between a and b (either direction), with each part named. steps: one loader's step_statuses."""
    pos = {v: i for i, v in enumerate(versions)}
    fwd = pos[a] < pos[b]
    lo, hi = min(pos[a], pos[b]), max(pos[a], pos[b])
    parts = []  # runs of consecutive updates with the same status
    for i in range(lo, hi):
        s = steps[f"{versions[i]}__{versions[i + 1]}"]["fwd" if fwd else "back"]
        if parts and parts[-1]["s"]["word"] == s["word"] and parts[-1]["s"]["why"] == s["why"]:
            parts[-1]["to"] = i + 1
        else:
            parts.append({"from": i, "to": i + 1, "s": s})
    if not fwd:
        parts.reverse()
    worst = min(parts, key=lambda p: WORST[p["s"]["c"]])["s"]
    if len(parts) == 1:
        return dict(worst)
    name = lambda p: f"{versions[p['from']]} → {versions[p['to']]}" if fwd else f"{versions[p['to']]} → {versions[p['from']]}"
    why = " ".join(f"{name(p)}: {p['s']['word'].lower()} ({p['s']['why'].rstrip('.')})." for p in parts)
    word = worst["word"] if all(p["s"]["c"] == worst["c"] for p in parts) else {"red": "Not working yet", "yellow": "Partly working", "green": "Works"}[worst["c"]]
    if worst["c"] == "red" and all(p["s"]["word"] == "Not tried" for p in parts if p["s"]["c"] == "red") and any(p["s"]["c"] != "red" for p in parts):
        word = "Partly tried"
    return {"c": worst["c"], "word": word, "why": why}
