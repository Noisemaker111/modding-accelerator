"""Render the one roadmap: docs/index.html (timeline, every number, the porting map, every port run)
and ROADMAP.md (the same roadmap as text).

Reads coverage/roadmap.json (phases, steps, dates, targets) and coverage/measures.json (every number,
with its history and target) through bench/measures.py, draws them with bench/viz.py, and embeds the
porting map from bench/porting_map.py. The evidence tables read coverage/ledger.json,
bench/port/results.json (2026-10-21 version bumps on six real mods) and bench/mods/results.json
(2026-10-22 baselines). site/scripts/export_data.py imports the helpers here (tested, secs, vkey, by,
ledger_secs, bumps, mods, data, LOADER); keep their names.

    python bench/bench_page.py              docs/index.html and ROADMAP.md
    python bench/bench_page.py --roadmap    ROADMAP.md only
"""
import argparse
import json
import re
import sys
import textwrap
from html import escape
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from page_style import STYLE, THEME_JS  # noqa: E402
from ledger_rule import COLOUR, RANK, WORD  # noqa: E402  (the porting map reads the same rule)
import measures as M  # noqa: E402
import viz  # noqa: E402
from porting_map import MAP_CSS, MAP_BODY, section, stonecutter_items  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "index.html"
ROADMAP_MD = ROOT / "ROADMAP.md"
_, _, MAP_JS = section()


def load(p):
    return json.loads((ROOT / p).read_text(encoding="utf-8"))


ledger = load("coverage/ledger.json")
data = load("bench/data.json")
bumps = load("bench/port/results.json")
mods = load("bench/mods/results.json")

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
        r = rows.setdefault(h["mod"], {"loaders": set(), "versions": set(), "best": {}, "date": "", "what": "ported by modaccel"})
        r["loaders"].add(LOADER.get(h["loader"], h["loader"]))
        r["versions"].update([h["from"], h["to"]])
        # A run proves only its own loader: keep the furthest run per loader.
        mine = r["best"].get(h["loader"])
        if not mine or RANK[h["status"]] > RANK[mine["status"]]:
            r["best"][h["loader"]] = h
        r["date"] = max(r["date"], h["date"])
    for m in bumps["mods"]:
        r = rows.setdefault(m["name"], {"loaders": set(), "versions": set(), "best": {}, "date": "", "what": "version bump by hand, errors counted"})
        r["loaders"].add("Fabric")
        r["versions"].update([m["current_mc"], m["target_mc"]])
        r["date"] = max(r["date"], bumps["date"])
        r.setdefault("errors", m["errors"]["total"])
    for m in mods["mods"]:
        name = {"diorama": "Diorama", "minecolonies": "MineColonies"}.get(m["name"], m["name"])
        r = rows.setdefault(name, {"loaders": set(), "versions": set(), "best": {}, "date": "", "what": "their own build, then modaccel fast"})
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
    top = lambda r: max((RANK[b["status"]] for b in r["best"].values()), default=0)
    order = sorted(rows.items(), key=lambda kv: (-top(kv[1]), kv[0].lower()))
    out = []
    for name, r in order:
        if r["best"]:
            reach = "".join(f"<div>{dot(COLOUR[b['status']])}{WORD[b['status']]}<span class='sub'>{LOADER.get(l, l)}, {b['from']} → {b['to']}</span></div>"
                            for l, b in sorted(r["best"].items(), key=lambda kv: -RANK[kv[1]["status"]]))
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


# ---- the one roadmap ----------------------------------------------------------------------------
def phase_counts(p):
    counts = {k: 0 for k in M.STATUSES}
    for s in p["steps"]:
        counts[s["status"]] += 1
    return counts


def phase_end(p):
    return max(s.get("target") or s["done"] for s in p["steps"])


def step_html(book, s):
    ms = [book.by_id[i] for i in s.get("measures", [])]
    more = ""
    if s.get("result"):
        more += f"<p><b>So far:</b> {escape(s['result'])}</p>"
    if s.get("points"):
        more += "<ul>" + "".join(f"<li>{escape(x)}</li>" for x in s["points"]) + "</ul>"
    return (f'<li class="v-step" id="step-{escape(s["id"])}" data-status="{s["status"]}" data-target="{escape(s.get("target", ""))}">'
            f'<div class="v-sl"><span class="v-sid">{escape(s["id"])}</span><b>{escape(s["title"])}</b>{viz.step_chip(s["status"])}'
            f'<span class="v-latechip">Late</span><span class="v-dates">{escape(viz.step_dates(s))}</span></div>'
            f'<details><summary>What this step is</summary><p>{escape(s["text"])}</p>{more}</details>'
            f'{viz.rows(book, ms) if ms else ""}</li>')


def phase_html(book, p):
    counts = phase_counts(p)
    done = counts["done"]
    end = phase_end(p)
    intro = f"<p>{escape(p['intro'])}</p>" if p.get("intro") else ""
    extra = ""
    if p["id"] == "4":
        l_head, l_body = loader_grid()
        extra = (f'<h3>Loader pairs</h3><table class="tbl stack"><thead><tr><th class="l">From</th>{l_head}</tr></thead>'
                 f"<tbody>{l_body}</tbody></table>")
    return (f'<section class="v-phase" id="phase-{escape(p["id"])}"><div class="v-phead"><h2>Phase {escape(p["id"])} · {escape(p["title"])}</h2>'
            f'<span>{done} of {len(p["steps"])} done · {"finished" if done == len(p["steps"]) else "last target " + M.day(end)}'
            f' <b class="v-plate" hidden></b></span></div>{intro}{viz.segbar(counts, len(p["steps"]))}'
            f'<ol class="v-steps">{"".join(step_html(book, s) for s in p["steps"])}</ol>{extra}</section>')


def numbers_html(book):
    out = []
    for area, name in book.data["areas"].items():
        ms = [m for m in book.data["measures"] if m["area"] == area]
        if not ms:
            continue
        note = " Other people's numbers on the same machine, so ours have something to be compared with." if area == "context" else ""
        out.append(f'<h3 id="area-{escape(area)}">{escape(name)}</h3>{f"<p>{note.strip()}</p>" if note else ""}{viz.cards(book, ms)}')
    return "".join(out)


def page(book):
    steps = list(book.steps.values())
    n_done = sum(s["status"] == "done" for s in steps)
    doing = [s for s in steps if s["status"] == "doing"]
    upcoming = sorted((s for s in steps if s["status"] != "done"), key=lambda s: s["target"])
    nxt = upcoming[0] if upcoming else None
    phases = book.roadmap["phases"]
    body = []
    for p in phases:
        body.append(phase_html(book, p))
        if p["id"] == "4":
            pm = [m for m in book.data["measures"] if m["id"].startswith("port.updates-working.")]
            chart = viz.columns(stonecutter_items(), "Stonecutter switches",
                                '<span><i class="k-col"></i>A rule was learned from these switches</span><span><i class="k-col off"></i>No rule yet</span>')
            body.append(f'<section id="porting"><h2>Porting map</h2><p>How much of the map works today, against the target:</p>{viz.rows(book, pm)}'
                        f'{MAP_BODY.replace("{SC_CHART}", chart)}</section>')
    head = [book.by_id[i] for i in book.data.get("headline", [])]
    t_rows, vers, n_mods = tested_table()
    nav = "".join(f'<a href="#{a}">{w}</a>' for a, w in (("now", "Where we are"), ("timeline", "Timeline"), ("phase-1", "Porting"), ("porting", "Porting map"),
                                                          ("phase-5", "Proof"), ("phase-6", "Modpacks"), ("phase-7", "No game window"), ("numbers", "Every number"), ("evidence", "Evidence")))
    html = TEMPLATE
    for k, v in {
        "/*STYLE*/": STYLE + viz.VIZ_CSS + MAP_CSS + PAGE_CSS, "/*THEME*/": THEME_JS, "/*VIZ*/": viz.VIZ_JS, "/*MAP*/": MAP_JS,
        "{NAV}": nav, "{GOAL}": escape(book.roadmap["goal"][:1].upper() + book.roadmap["goal"][1:]),
        "{KPIS}": (f"<div><b>{n_done} of {len(steps)}</b>steps done</div><div><b>{len(doing)}</b>being worked on</div>"
                   + (f"<div><b>{M.day(nxt['target'])}</b>next target (step {escape(nxt['id'])})</div>" if nxt else "")
                   + f"<div><b>{len(book.data['measures'])}</b>numbers tracked</div>"),
        "{BURNUP}": viz.burnup(book), "{HEADLINE}": "".join(viz.card(book, m, anchor=False) for m in head),
        "{GANTT}": viz.gantt(book), "{PHASES}": "".join(body), "{NUMBERS}": numbers_html(book),
        "{TESTED}": t_rows, "{VERS}": ", ".join(vers), "{LATEST}": latest_runs(), "{EARLIER}": earlier_runs(),
        "{SET}": M.day(book.roadmap["targetsSet"]), "{MACHINE}": escape(book.data["machines"]["jon-pc"]),
    }.items():
        html = html.replace(k, v)
    return html


# ---- ROADMAP.md ---------------------------------------------------------------------------------
def roadmap_md(book):
    r = book.roadmap
    wrap = lambda text, first="", rest="    ": textwrap.fill(text, 100, initial_indent=first, subsequent_indent=rest, break_on_hyphens=False, break_long_words=False)
    out = ["# Roadmap", "",
           "Generated from `coverage/roadmap.json` (phases, steps, dates) and `coverage/measures.json` (every number, its",
           "history and its target) by `python bench/bench_page.py --roadmap`. Edit those two files, not this one;",
           "`python bench/test_measures.py` fails while this file is behind them. The page with the timeline, every number",
           "and the porting map: https://noisemaker111.github.io/modding-accelerator/", "",
           f"Goal: {r['goal']}", "",
           "Status words: **done** (ran and checked), **doing** (being worked on), **next**, **later**. Every step that is",
           f"not done has a target date. Targets were set on {r['targetsSet']}; a target that moves keeps its old date in `moved`.", "",
           "| Phase | Done | Doing | Next | Later | Last target |", "|---|---|---|---|---|---|"]
    for p in r["phases"]:
        c = phase_counts(p)
        out.append(f"| {p['id']}. {p['title']} | {c['done']} | {c['doing']} | {c['next']} | {c['later']} | {phase_end(p)} |")
    out.append("")
    for p in r["phases"]:
        out += [f"## Phase {p['id']} — {p['title']}", ""]
        if p.get("intro"):
            out += [wrap(p["intro"], "", ""), ""]
        for s in p["steps"]:
            line = f"{s['id']}. {s['text']} **{s['status']}**" + (f" — {s['result']}" if s.get("result") else "")
            out.append(wrap(line))
            for x in s.get("points", []):
                out.append(wrap(x, "    - ", "      "))
            if s.get("done"):
                out.append(f"    Done {s['done']}.")
            else:
                out.append(f"    {'Started' if s['status'] == 'doing' else 'Starts'} {s['start']}, target {s['target']}.")
        out.append("")
    out += ["## Numbers", "",
            "Every number with a target, from `coverage/measures.json`. The same table for a PR or report:",
            "`python bench/measures.py table [ID ...] [--area AREA] [--step STEP]`.", ""]
    for area, name in book.data["areas"].items():
        ms = [m for m in book.data["measures"] if m["area"] == area and not m.get("context")]
        if ms:
            out += [f"### {name}", "", M.table(book, ms), ""]
    out += ["## Decisions", "", "| Question | Choice | Date |", "|---|---|---|"]
    out += [f"| {d['question']} | {d['choice']} | {d['date']} |" for d in r["decisions"]]
    return "\n".join(out) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description="Write docs/index.html (the roadmap page) and ROADMAP.md.")
    ap.add_argument("--roadmap", action="store_true", help="write ROADMAP.md only (docs/ is regenerated only before a milestone merge)")
    args = ap.parse_args(argv)
    book = M.Book()
    found = M.problems(book)
    if found:
        print("coverage/measures.json or coverage/roadmap.json has problems:\n  " + "\n  ".join(found), file=sys.stderr)
        return 1
    ROADMAP_MD.write_text(roadmap_md(book), encoding="utf-8", newline="\n")
    print("wrote", ROADMAP_MD)
    if args.roadmap:
        return 0
    html = page(book)
    OUT.write_text(html, encoding="utf-8", newline="\n")
    print("wrote", OUT, len(html), "bytes")
    return 0


PAGE_CSS = r"""
.nw { white-space:nowrap }
td.what { font-size:14.5px; color:var(--ink2); max-width:460px; line-height:1.4 }
td.none { color:var(--ink3) }
.top nav a { text-decoration:none } .top nav a:hover { text-decoration:underline }
header.top { position:sticky; top:0; z-index:5; background:var(--bg); padding:10px 0; border-bottom:1px solid var(--rule) }
#porting { scroll-margin-top:64px }
@media (max-width: 720px) {
  header.top { position:static }
  td.what { max-width:none }
}
"""

TEMPLATE = r"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Modding Accelerator roadmap</title>
<meta name="description" content="Where Modding Accelerator is: every roadmap step with its dates and target, every number with its history and target, the porting map and every port that was run.">
<style>
/*STYLE*/
</style></head><body>
<main>
<header class="top">
  <h1>Roadmap</h1>
  <nav>{NAV}<a href="https://github.com/Noisemaker111/modding-accelerator">GitHub</a><button class="theme" id="theme" type="button">Dark</button></nav>
</header>
<p class="lede">{GOAL}</p>
<div class="v-kpis">{KPIS}</div>

<h2 id="now">Where we are</h2>
<p>Steps done against the plan, and the numbers that say most about each goal. Every number on this page is a measure in <span class="mono">coverage/measures.json</span>: its points, its target and the date it should get there. A ring is the target; the dashed line is the way still to go.</p>
<div class="v-score">{BURNUP}<div class="v-cards">{HEADLINE}</div></div>

<h2 id="timeline">Timeline</h2>
<p>Every step from its start to its target, or to the day it was done. Targets were set on {SET} and move only with a note. The vertical line is today; a step past its target that is not done turns red.</p>
<div class="v-glegend"><span><i class="v-s-done"></i>Done</span><span><i style="background:var(--accent-wash);box-shadow:inset 0 0 0 1.5px var(--accent)"></i>Doing</span><span><i style="box-shadow:inset 0 0 0 1.5px var(--ink3)"></i>Next</span><span><i class="v-s-later"></i>Later</span><span><i style="box-shadow:inset 0 0 0 2px var(--red)"></i>Late</span></div>
{GANTT}

{PHASES}

<h2 id="numbers">Every number</h2>
<p>Each card is one measure: the same work, measured the same way each time. Hover a point for its date, run count and note. Cold and warm are separate cards. Timings are from {MACHINE} unless the card says CI. In a PR, print the same numbers with <span class="mono">python bench/measures.py table</span>.</p>
{NUMBERS}

<h2 id="evidence">Evidence</h2>
<h3>Every port run</h3>
<p>Newest first, from <span class="mono">coverage/ledger.json</span>. A run that is not here did not happen.</p>
<table class="tbl stack"><thead><tr><th class="l">Mod</th><th>Port</th><th class="hi l">Reached</th><th class="l">What happened</th><th>Date</th></tr></thead>
<tbody>{LATEST}</tbody></table>
<h3>Mods we test</h3>
<p>Untouched open-source mods. The highlighted column is the furthest a port of that mod has got.</p>
<table class="tbl stack"><thead><tr><th class="l">Mod</th><th>Loader</th><th>Versions</th><th class="hi l">Furthest it got</th><th>Last run</th></tr></thead>
<tbody>{TESTED}</tbody></table>
<p class="small">Versions touched: {VERS}.</p>
<h3>Before modaccel</h3>
<p>Each mod's version pins bumped by hand to see how much breaks, and how long their own build takes.</p>
<table class="tbl stack"><thead><tr><th class="l">Mod</th><th>Version</th><th class="hi">Compile errors</th><th>Their first build</th><th>Build after bump</th><th>Date</th></tr></thead>
<tbody>{EARLIER}</tbody></table>
<h3>What counts as working</h3>
<table class="tbl stack"><tbody>
<tr><td class="l"><span class="dot st-red"></span><b>Stops with named errors</b></td><td class="l">The port refuses to guess and lists what is left.</td></tr>
<tr><td class="l"><span class="dot st-yellow"></span><b>Builds, mixins checked</b></td><td class="l">The project compiles and the mixin audit passes. Not called working.</td></tr>
<tr><td class="l"><span class="dot st-green"></span><b>Loads in the real game</b></td><td class="l">A real client or server starts with the jar and lists the mod.</td></tr>
<tr><td class="l"><span class="dot st-green"></span><b>Behaviour checked in the game</b></td><td class="l">An assertion on the mod's own state passes in the game. A frame change or a reloaded save is not behaviour.</td></tr>
</tbody></table>

<p class="foot">Made by <span class="mono">bench/bench_page.py</span> from <span class="mono">coverage/roadmap.json</span>, <span class="mono">coverage/measures.json</span>, <span class="mono">coverage/ledger.json</span>, <span class="mono">coverage/changes/index.json</span>, <span class="mono">bench/port/results.json</span> and <span class="mono">bench/mods/results.json</span>. The same roadmap as text: <a href="https://github.com/Noisemaker111/modding-accelerator/blob/main/ROADMAP.md">ROADMAP.md</a>.</p>
</main>
<script>
/*THEME*/
/*VIZ*/
</script>
<script>
/*MAP*/
</script>
<script>
// old porting-map links (#from=…&to=…, #step=…) land on the map
if (/(^|[#&])(from|to|step)=/.test(location.hash)) document.getElementById("porting").scrollIntoView();
</script>
</body></html>
"""

if __name__ == "__main__":
    sys.exit(main())
