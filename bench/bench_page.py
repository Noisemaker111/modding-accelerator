"""Render docs/index.html, the bench page: what we test, what ran, and how fast it was.

Reads coverage/ledger.json (every port that was run), bench/data.json (loop timings),
bench/port/results.json (2026-10-21 version bumps on six real mods) and bench/mods/results.json
(2026-10-22 baselines). .runs/index.jsonl holds every run but is not committed, so the timing
history here is only what those files carry.

    python bench/bench_page.py
"""
import json
import re
import statistics
import sys
from html import escape
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from page_style import STYLE, THEME_JS  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "index.html"


def load(p):
    return json.loads((ROOT / p).read_text(encoding="utf-8"))


ledger = load("coverage/ledger.json")
data = load("bench/data.json")
bumps = load("bench/port/results.json")
mods = load("bench/mods/results.json")

RANK = {"behaviour": 4, "load": 3, "build": 2, "stop": 1}
WORD = {"behaviour": "Behaviour checked in the game", "load": "Loads in the real game", "build": "Builds, mixins checked", "stop": "Stops with named errors"}
COLOUR = {"behaviour": "green", "load": "green", "build": "yellow", "stop": "red"}
LOADER = {"fabric": "Fabric", "neoforge": "NeoForge", "forge": "Forge", "quilt": "Quilt"}


def dot(c):
    return f"<span class='dot st-{c}'></span>"


def secs(v):
    if v is None:
        return ""
    if v >= 120:
        return f"{v / 60:.1f} min"
    return f"{v:.1f}s" if v < 10 else f"{v:.0f}s"


# ---- mods and versions we test ---------------------------------------------------------------
def tested():
    rows = {}
    for h in ledger["hops"]:
        r = rows.setdefault(h["mod"], {"loaders": set(), "versions": set(), "best": None, "date": "", "what": "ported by modaccel"})
        r["loaders"].add(LOADER.get(h["loader"], h["loader"]))
        r["versions"].update([h["from"], h["to"]])
        if not r["best"] or RANK[h["status"]] > RANK[r["best"]["status"]]:
            r["best"] = h
        r["date"] = max(r["date"], h["date"])
    for m in bumps["mods"]:
        r = rows.setdefault(m["name"], {"loaders": set(), "versions": set(), "best": None, "date": "", "what": "version bump by hand, errors counted"})
        r["loaders"].add("Fabric")
        r["versions"].update([m["current_mc"], m["target_mc"]])
        r["date"] = max(r["date"], bumps["date"])
        r.setdefault("errors", m["errors"]["total"])
    for m in mods["mods"]:
        name = {"diorama": "Diorama", "minecolonies": "MineColonies"}.get(m["name"], m["name"])
        r = rows.setdefault(name, {"loaders": set(), "versions": set(), "best": None, "date": "", "what": "their own build, then modaccel fast"})
        r["loaders"].update(LOADER.get(x, x) for x in m.get("loaders", []))
        mc = m.get("minecraft", {})
        if mc.get("active"):
            r["versions"].add(mc["active"])
        r["date"] = max(r["date"], mods["measured"])
    for e in data["editloop"]:
        r = rows.get(e["mod"])
        if r:
            r["versions"].add(e["mc"])
            r["loaders"].add(e["loader"])
    return rows


def vkey(v):
    return [int(x) for x in re.findall(r"\d+", v)]


def tested_table():
    rows = tested()
    order = sorted(rows.items(), key=lambda kv: (-(RANK[kv[1]["best"]["status"]] if kv[1]["best"] else 0), kv[0].lower()))
    out = []
    for name, r in order:
        b = r["best"]
        if b:
            reach = f"{dot(COLOUR[b['status']])}{WORD[b['status']]}<span class='sub'>{b['from']} → {b['to']}</span>"
        elif "errors" in r:
            reach = f"{dot('grey')}Bump measured<span class='sub'>{r['errors']} compile errors, repaired by hand</span>"
        else:
            reach = f"{dot('grey')}Edit loop measured<span class='sub'>no port run yet</span>"
        out.append(f"<tr><td class='l'><b>{escape(name)}</b><span class='sub'>{escape(r['what'])}</span></td>"
                   f"<td data-k='Loader'>{', '.join(sorted(r['loaders']))}</td>"
                   f"<td data-k='Versions'>{', '.join(sorted(r['versions'], key=vkey))}</td>"
                   f"<td class='hi l' data-k='Furthest it got'>{reach}</td>"
                   f"<td data-k='Last run' class='nw'>{r['date']}</td></tr>")
    vers = sorted({v for r in rows.values() for v in r["versions"]}, key=vkey)
    return "".join(out), vers, len(rows)


# ---- runs --------------------------------------------------------------------------------------
def latest_runs():
    hops = sorted(ledger["hops"], key=lambda h: (h["date"], RANK[h["status"]]), reverse=True)
    out = []
    for h in hops:
        back = vkey(h["to"]) < vkey(h["from"])
        out.append(f"<tr><td class='l'><b>{escape(h['mod'])}</b><span class='sub'>{LOADER.get(h['loader'], h['loader'])}</span></td>"
                   f"<td data-k='Port' class='nw'>{h['from']} → {h['to']}{' <span class=sub>backport</span>' if back else ''}</td>"
                   f"<td class='hi l' data-k='Reached'>{dot(COLOUR[h['status']])}{WORD[h['status']]}</td>"
                   f"<td class='l what' data-k='What happened'>{escape(h['detail'])}</td>"
                   f"<td data-k='Date' class='nw'>{h['date']}</td></tr>")
    return "".join(out)


def earlier_runs():
    out = []
    for m in bumps["mods"]:
        b, z = m["baseline"], m["bumped"]
        out.append(f"<tr><td class='l'><b>{escape(m['name'])}</b><span class='sub'>Fabric, version pins bumped by hand</span></td>"
                   f"<td data-k='Port' class='nw'>{m['current_mc']} → {m['target_mc']}</td>"
                   f"<td class='hi' data-k='Compile errors'>{m['errors']['total']}</td>"
                   f"<td data-k='Their first build'>{secs(b.get('seconds'))}</td>"
                   f"<td data-k='Build after bump'>{secs(z.get('seconds'))}</td>"
                   f"<td data-k='Date' class='nw'>{bumps['date']}</td></tr>")
    for m in mods["mods"]:
        name = {"diorama": "Diorama", "minecolonies": "MineColonies"}.get(m["name"], m["name"])
        bump = m.get("bump") or {}
        out.append(f"<tr><td class='l'><b>{escape(name)}</b><span class='sub'>{', '.join(LOADER.get(x, x) for x in m.get('loaders', []))}, their build as it is</span></td>"
                   f"<td data-k='Port' class='nw'>{escape((m.get('minecraft') or {}).get('active', ''))}</td>"
                   f"<td class='hi' data-k='Compile errors'>{m.get('errors') if m.get('errors') is not None else '—'}</td>"
                   f"<td data-k='Their first build'>{secs(m['baseline'].get('seconds'))}</td>"
                   f"<td data-k='Build after bump'>{secs(bump.get('seconds')) or '—'}</td>"
                   f"<td data-k='Date' class='nw'>{mods['measured']}</td></tr>")
    return "".join(out)


# ---- speed over time ---------------------------------------------------------------------------
def by(key, name):
    return next(x["seconds"] for x in data[key] if x["name"] == name)


def ledger_secs(mod, frm, to):
    h = next(h for h in ledger["hops"] if h["mod"] == mod and h["from"] == frm and h["to"] == to)
    return float(re.search(r"in (\d+(?:\.\d+)?)s\b", h["detail"]).group(1)), h["date"]


def speed():
    # data.json["measured"] says the Fabric loop is from 2026-10-21 and the Forge/NeoForge/multiplayer
    # numbers from 2026-10-22; the edit-loop table carries its own date.
    d21, d22, d23 = "2026-10-21", "2026-10-22", data["editloop_measured"][:10]
    port263, d24 = ledger_secs("AppleSkin", "1.21.6", "26.3")
    own263, _ = ledger_secs("AppleSkin", "26.3", "26.3")
    med = statistics.median
    base = [m["baseline"]["seconds"] for m in bumps["mods"]]
    bumped = [m["bumped"]["seconds"] for m in bumps["mods"]]
    fast = [med(e["fast"]) for e in data["editloop"]]
    warm = [med(e["warm"]) for e in data["editloop"]]
    rows = [
        ("Setup", None),
        ("A mod's own first build, cold", {d21: (med(base), f"median of {len(base)} mods"), d22: (by("forgeloop", "their gradlew build -x test"), "MineColonies, Forge")}),
        ("Toolchain check before building", {d22: (by("toolchain", "Diorama: READY"), "Diorama, ready")}),
        ("Compile", None),
        ("Build after a version bump, warm caches", {d21: (med(bumped), f"median of {len(bumped)} mods")}),
        ("Port to a packaged build, mixins checked", {d24: (port263, "AppleSkin 1.21.6 → 26.3")}),
        ("A mod's own build through modaccel", {d24: (own263, "AppleSkin, 26.3")}),
        ("One-file edit, the mod's own build", {d23: (med(warm), f"median of {len(warm)} mods")}),
        ("One-file edit, modaccel fast", {d22: (by("forgeloop", "one-file edit, .\\modaccel fast"), "MineColonies"), d23: (med(fast), f"median of {len(fast)} mods")}),
        ("Game start", None),
        ("Client start, cold", {d21: (by("loop", "cold client boot + one check"), "Fabric 26.3, with one check"), d22: (by("neoforgeloop", "cold start, client listening"), "NeoForge 1.21.1")}),
        ("Dedicated server start", {d22: (by("multiplayer", "server boot"), "")}),
        ("Checks", None),
        ("Logic tests, no Minecraft", {d21: (by("loop", "logic tests, no Minecraft"), "")}),
        ("Screen change and a screenshot", {d21: (by("loop", "screen change + screenshot"), "Fabric 26.3"), d22: (by("hotreload", "NeoForge 1.21.1: screen edit, same JVM"), "NeoForge 1.21.1")}),
        ("Block entity in a live world", {d21: (by("loop", "block entity in a live world"), "")}),
        ("Scripted scenario and a screenshot", {d21: (by("loop", "scripted scenario + screenshot"), ""), d22: (by("multiplayer", "scenario, warm client"), "multiplayer, warm client")}),
    ]
    dates = sorted({d for _, cells in rows if cells for d in cells})
    short = lambda d: d[5:].replace("-", "/")
    head = "".join(f"<th>{short(d)}</th>" for d in dates) + "<th class='hi'>Latest</th>"
    body = []
    for name, cells in rows:
        if cells is None:
            body.append(f"<tr class='headrow'><td class='l' colspan='{len(dates) + 1}'><b>{name}</b></td><td class='hi'></td></tr>")
            continue
        # One column per measurement day, then the latest value highlighted.
        tds = []
        for d in dates:
            v = cells.get(d)
            if v:
                tds.append(f"<td class='old' data-k='{short(d)}'>{secs(v[0])}{f'<span class=sub>{escape(v[1])}</span>' if v[1] else ''}</td>")
            else:
                tds.append("<td class='none'><span class='sub'>—</span></td>")
        last = max(cells)
        v = cells[last]
        tds.append(f"<td class='hi' data-k='Latest'>{secs(v[0])}<span class='sub'>{short(last)}{', ' + escape(v[1]) if v[1] else ''}</span></td>")
        body.append(f"<tr><td class='l'>{name}</td>{''.join(tds)}</tr>")
    return head, "".join(body), dates


# ---- loader changes (folded in from the old progress page) ---------------------------------------
def loader_grid():
    loaders = ledger["loaders"]
    pairs = ledger["loader_pairs"]
    head = "".join(f"<th>{LOADER.get(l, l)}</th>" for l in loaders)
    body = []
    for a in loaders:
        cells = []
        for b in loaders:
            if a == b:
                cells.append("<td class='none'><span class='sub'>·</span></td>")
                continue
            p = next((p for p in pairs if p["from"] == a and p["to"] == b), None)
            word = "not built yet" if not p or p["status"] == "unbuilt" else WORD.get(p["status"], p["status"])
            c = "grey" if not p or p["status"] == "unbuilt" else COLOUR.get(p["status"], "grey")
            cells.append(f"<td data-k='to {LOADER.get(b, b)}' title='{escape(p['detail']) if p else ''}'>{dot(c)}<span class='small'>{word}</span></td>")
        body.append(f"<tr><td class='l'><b>{LOADER.get(a, a)}</b></td>{''.join(cells)}</tr>")
    return head, "".join(body)


def main():
    t_rows, vers, n_mods = tested_table()
    s_head, s_body, dates = speed()
    l_head, l_body = loader_grid()
    hops = ledger["hops"]
    green = sum(1 for h in hops if h["status"] in ("behaviour", "load"))
    html = TEMPLATE
    for k, v in {
        "/*STYLE*/": STYLE, "/*THEME*/": THEME_JS,
        "{N_MODS}": str(n_mods), "{N_VERS}": str(len(vers)), "{VERS}": ", ".join(vers),
        "{N_RUNS}": str(len(hops)), "{N_GREEN}": str(green),
        "{TESTED}": t_rows, "{LATEST}": latest_runs(), "{EARLIER}": earlier_runs(),
        "{S_HEAD}": s_head, "{S_BODY}": s_body, "{L_HEAD}": l_head, "{L_BODY}": l_body,
        "{NEWEST}": dates[-1], "{MACHINE}": escape(data["machine"]),
    }.items():
        html = html.replace(k, v)
    OUT.write_text(html, encoding="utf-8")
    print("wrote", OUT, len(html), "bytes")


TEMPLATE = r"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Modding Accelerator bench</title>
<meta name="description" content="The Minecraft mods and versions Modding Accelerator is tested on, every port it ran, and how fast each part was.">
<style>
/*STYLE*/
.nw { white-space:nowrap }
td.what { font-size:15px; color:var(--ink2); max-width:460px; line-height:1.4 }
td.none { color:var(--ink3) }
.facts { display:flex; gap:28px; flex-wrap:wrap; margin-top:18px }
.facts div { font-size:15px; color:var(--ink2) }
.facts b { display:block; font-size:30px; color:var(--ink); font-weight:600 }
.wide { overflow-x:auto }
.room { border:1px dashed var(--rule); border-radius:10px; padding:14px 16px; margin-top:12px; color:var(--ink2) }
@media (max-width: 720px) {
  td.what { max-width:none }
  .tbl.speed td.none, .tbl.speed tr.headrow td.hi { display:none }
  .tbl.speed td.old { color:var(--ink3) }
  .tbl.speed tr.headrow td { background:none; padding-top:14px; font-size:18px }
  .tbl.speed td[data-k]::before { content:attr(data-k) ": " }
}
</style></head><body>
<main>
<div class="top">
  <h1>Bench</h1>
  <nav><a href="porting-map.html">Porting map</a><a href="https://github.com/Noisemaker111/modding-accelerator">GitHub</a><button class="theme" id="theme" type="button">Dark</button></nav>
</div>
<p class="lede">The mods and Minecraft versions Modding Accelerator is tested on, every port it has run, and how long each part took. Only runs with a receipt are here. To see the route between two versions, open the <a href="porting-map.html">porting map</a>.</p>
<div class="facts">
  <div><b>{N_MODS}</b>real mods</div>
  <div><b>{N_VERS}</b>Minecraft versions</div>
  <div><b>{N_RUNS}</b>ports run</div>
  <div><b>{N_GREEN}</b>load or work in the game</div>
</div>

<h2 id="tested">What we test</h2>
<p>Untouched open-source mods. The highlighted column is the furthest a port of that mod has got.</p>
<table class="tbl stack"><thead><tr><th class="l">Mod</th><th>Loader</th><th>Versions</th><th class="hi l">Furthest it got</th><th>Last run</th></tr></thead>
<tbody>{TESTED}</tbody></table>
<p class="small">Versions touched: {VERS}.</p>

<h2 id="runs">Latest runs</h2>
<p>Every port modaccel has run, newest first, from <span class="mono">coverage/ledger.json</span>.</p>
<table class="tbl stack"><thead><tr><th class="l">Mod</th><th>Port</th><th class="hi l">Reached</th><th class="l">What happened</th><th>Date</th></tr></thead>
<tbody>{LATEST}</tbody></table>
<div class="room"><b>Real-mod runner.</b> A runner that takes six real mods through every step is being built. Its runs will show up here, in the table above, once they are recorded in the ledger.</div>

<h2 id="earlier">Earlier runs</h2>
<p>Before modaccel ported anything: each mod's version pins bumped by hand to see how much breaks, and how long their own build takes.</p>
<table class="tbl stack"><thead><tr><th class="l">Mod</th><th>Version</th><th class="hi">Compile errors</th><th>Their first build</th><th>Build after bump</th><th>Date</th></tr></thead>
<tbody>{EARLIER}</tbody></table>

<h2 id="speed">Speed over time</h2>
<p>Each column is a day something was measured, and the highlighted column is the latest number. Most numbers were measured once, so this history is still thin: the full run log (<span class="mono">.runs/index.jsonl</span>) is kept on the machine and not committed.</p>
<div class="wide"><table class="tbl stack speed"><thead><tr><th class="l">What</th>{S_HEAD}</tr></thead>
<tbody>{S_BODY}</tbody></table></div>
<p class="small">{MACHINE}. Cold includes downloads and first setup; compare an edit with warm numbers, never cold.</p>

<h2 id="loaders">Changing loader</h2>
<p>Keeping the game version and moving to another loader is a separate job. None of it is built yet.</p>
<table class="tbl stack"><thead><tr><th class="l">From</th>{L_HEAD}</tr></thead>
<tbody>{L_BODY}</tbody></table>

<h2 id="green">What counts as working</h2>
<table class="tbl stack"><tbody>
<tr><td class="l"><span class="dot st-red"></span><b>Stops with named errors</b></td><td class="l">The port refuses to guess and lists what is left.</td></tr>
<tr><td class="l"><span class="dot st-yellow"></span><b>Builds, mixins checked</b></td><td class="l">The project compiles and the mixin audit passes. Not called working.</td></tr>
<tr><td class="l"><span class="dot st-green"></span><b>Loads in the real game</b></td><td class="l">A real client or server starts with the jar and lists the mod.</td></tr>
<tr><td class="l"><span class="dot st-green"></span><b>Behaviour checked in the game</b></td><td class="l">Something you can see is asserted: a screen changed, a command ran, a save reloaded.</td></tr>
</tbody></table>

<p class="foot">Made by <span class="mono">bench/bench_page.py</span> from <span class="mono">coverage/ledger.json</span>, <span class="mono">bench/data.json</span>, <span class="mono">bench/port/results.json</span> and <span class="mono">bench/mods/results.json</span>. Newest measurement {NEWEST}. The plan is in <a href="https://github.com/Noisemaker111/modding-accelerator/blob/main/ROADMAP.md">ROADMAP.md</a>.</p>
</main>
<script>
/*THEME*/
</script>
</body></html>
"""

if __name__ == "__main__":
    main()
