"""Render the one public page, docs/index.html, and ROADMAP.md (the same roadmap as text for GitHub).

The page opens with what modaccel is and how to get it, then the four goals, the roadmap (timeline,
every step), the porting map, every number and every port run. It reads coverage/roadmap.json (the
four goals, their steps, dates, targets) and coverage/measures.json (every number, with its history
and target) through bench/measures.py, draws them with bench/viz.py, and embeds the porting map from
bench/porting_map.py. The evidence tables read coverage/ledger.json, bench/port/results.json
(2026-10-21 version bumps on six real mods) and bench/mods/results.json (2026-10-22 baselines).

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
PORT_GOAL, LOADER_GOAL = "2", "4"   # the porting map follows the first, the loader grid sits in the second
GOAL_AREAS = {"1": ("test", "plugin"), "2": ("port",), "3": ("pack",), "4": ("loader",)}   # the measure areas each goal owns
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
def goal_counts(g):
    counts = {k: 0 for k in M.STATUSES}
    for s in g["steps"]:
        counts[s["status"]] += 1
    return counts


def goal_end(g):
    return max(s.get("target") or s["done"] for s in g["steps"])


def parts(g):
    """A goal's steps in order, grouped by their part: [(part, [step, ...]), ...]."""
    out = []
    for s in g["steps"]:
        if not out or out[-1][0] != s["part"]:
            out.append((s["part"], []))
        out[-1][1].append(s)
    return out


def step_html(book, s):
    ms = [book.by_id[i] for i in s.get("measures", [])]
    more = f"<p>{code(s['text'])}</p>"
    if s.get("result"):
        more += f"<p><b>So far:</b> {code(s['result'])}</p>"
    if s.get("points"):
        more += "<ul>" + "".join(f"<li>{code(x)}</li>" for x in s["points"]) + "</ul>"
    return (f'<li class="v-step" id="step-{escape(s["id"])}" data-status="{s["status"]}" data-target="{escape(s.get("target", ""))}">'
            f'<details><summary class="v-sl"><span class="v-sid">{escape(s["id"])}</span><b>{escape(s["title"])}</b>'
            f'<span class="v-latechip">Late</span><span class="v-dates">{escape(viz.step_dates(s))}</span>{viz.step_chip(s["status"])}</summary>'
            f'<div class="v-sbody">{more}{viz.rows(book, ms) if ms else ""}</div></details></li>')


def code(text):
    """Escaped text with `backticks` as <code>."""
    return re.sub(r"`([^`]+)`", r'<span class="mono">\1</span>', escape(text))


def goal_html(book, g):
    counts = goal_counts(g)
    done = counts["done"]
    end = goal_end(g)
    extra = ""
    if g["id"] == LOADER_GOAL:
        l_head, l_body = loader_grid()
        extra = (f'<h3>Loader pairs</h3><table class="tbl stack"><thead><tr><th class="l">From</th>{l_head}</tr></thead>'
                 f"<tbody>{l_body}</tbody></table>")
    steps = "".join(f'<h3 class="v-part">{escape(part)}</h3><ol class="v-steps">{"".join(step_html(book, s) for s in ss)}</ol>' for part, ss in parts(g))
    return (f'<section class="v-phase" id="goal-{escape(g["id"])}"><div class="v-phead"><h2>Goal {escape(g["id"])} · {escape(g["title"])}</h2>'
            f'<span>{done} of {len(g["steps"])} done · {"finished" if done == len(g["steps"]) else "last target " + M.day(end)}'
            f' <b class="v-plate" hidden></b></span></div><p>{code(g["intro"])}</p>{viz.segbar(counts, len(g["steps"]))}'
            f'{steps}{extra}</section>')


def goal_cards(book):
    """The four goals as cards: what works today, how far its steps are, and the number that says most."""
    out = []
    for g in book.roadmap["goals"]:
        counts = goal_counts(g)
        m = book.by_id[g["headline"][0]]
        pts = book.points(m)
        now = (f'<b>{escape(viz.num_text(book, m, pts[-1]["value"]))}</b> {escape(viz.unit_text(book, m, pts[-1]["value"]))}' if pts else "<b>—</b>")
        cmds = "".join(f'<span class="mono">{escape(c)}</span>' for c in re.findall(r"`([^`]+)`", g["intro"]))
        working = [s for s in g["steps"] if s["status"] == "doing"] or [s for s in g["steps"] if s["status"] == "next"][:1]
        on = (("Working on: " if working[0]["status"] == "doing" else "Next: ") + "; ".join(s["title"] for s in working[:2])
              + (f" and {len(working) - 2} more" if len(working) > 2 else "")) if working else "Not started"
        out.append(f'<a class="g-card" href="#goal-{escape(g["id"])}" data-built="{"yes" if counts["done"] else "no"}">'
                   f'<span class="g-num" aria-hidden="true">{escape(g["id"])}</span><h3>{escape(g["title"])}</h3>'
                   f'<p class="g-today">{code(g["today"])}</p>'
                   f'<div class="g-cmds">{cmds or "<span>no command yet</span>"}</div>'
                   f'{viz.segbar(counts, len(g["steps"]))}'
                   f'<p class="g-kpi">{now}<span>{escape(M.label(m))}{", target " + escape(M.target_words(book, m)) if M.target_words(book, m) else ""}</span></p>'
                   f'<p class="g-on">{escape(on)}</p></a>')
    return "".join(out)


def terminal(book):
    """The commands each goal says to try, as one block."""
    lines = []
    for g in book.roadmap["goals"]:
        for x in g.get("try", []):
            if x.get("say"):
                lines.append(f'<span class="t-say"># {escape(x["say"])}</span>')
            lines.append(f'<span class="t-run"><i aria-hidden="true">$ </i>{escape(x["run"])}</span>')
    return "".join(lines)


def version():
    return re.search(r'VERSION = "([^"]+)"', (ROOT / "tools" / "cli" / "Support.java").read_text(encoding="utf-8")).group(1)


def fold(summary, body, count="", open_=False, anchor=""):
    """A section the reader opens: used for the long lists (every number, every run)."""
    return (f'<details class="fold"{" open" if open_ else ""}{f" id={chr(34)}{anchor}{chr(34)}" if anchor else ""}><summary><b>{summary}</b>'
            f'{f"<span>{count}</span>" if count else ""}</summary>{body}</details>')


def numbers_html(book):
    out = []
    for area, name in book.data["areas"].items():
        ms = [m for m in book.data["measures"] if m["area"] == area]
        if not ms:
            continue
        note = "<p>Other people's numbers on the same machine, so ours have something to be compared with.</p>" if area == "context" else ""
        met = sum(book.state(m)[0] == "met" for m in ms)
        with_target = sum(bool(book.target(m)) for m in ms)
        count = f"{len(ms)} number{'s' if len(ms) != 1 else ''}" + (f" · {met} of {with_target} target{'s' if with_target != 1 else ''} met" if with_target else "")
        out.append(fold(escape(name), note + viz.cards(book, ms), count, anchor=f"area-{area}"))
    return "".join(out)


def evidence_html(t_rows, vers, n_mods):
    runs = ('<p>Newest first, from <span class="mono">coverage/ledger.json</span>.</p>'
            '<table class="tbl stack"><thead><tr><th class="l">Mod</th><th>Port</th><th class="hi l">Reached</th><th class="l">What happened</th><th>Date</th></tr></thead>'
            f"<tbody>{latest_runs()}</tbody></table>")
    tested_ = ('<p>Untouched open-source mods. The highlighted column is the furthest a port of that mod has got.</p>'
               '<table class="tbl stack"><thead><tr><th class="l">Mod</th><th>Loader</th><th>Versions</th><th class="hi l">Furthest it got</th><th>Last run</th></tr></thead>'
               f'<tbody>{t_rows}</tbody></table><p class="small">Versions touched: {", ".join(vers)}.</p>')
    before = ("<p>Each mod's version pins bumped by hand to see how much breaks, and how long their own build takes.</p>"
              '<table class="tbl stack"><thead><tr><th class="l">Mod</th><th>Version</th><th class="hi">Compile errors</th><th>Their first build</th><th>Build after bump</th><th>Date</th></tr></thead>'
              f"<tbody>{earlier_runs()}</tbody></table>")
    return (fold("Every port run", runs, f'{len(ledger["hops"])} runs', anchor="runs") + fold("Mods we test", tested_, f"{n_mods} mods", anchor="mods")
            + fold("Before modaccel", before, "version bumps by hand", anchor="before"))


def page(book):
    steps = list(book.steps.values())
    n_done = sum(s["status"] == "done" for s in steps)
    doing = [s for s in steps if s["status"] == "doing"]
    upcoming = sorted((s for s in steps if s["status"] != "done"), key=lambda s: s["target"])
    nxt = upcoming[0] if upcoming else None
    goals = book.roadmap["goals"]
    body = []
    for p in goals:
        body.append(goal_html(book, p))
        if p["id"] == PORT_GOAL:
            pm = [m for m in book.data["measures"] if m["id"].startswith("port.updates-working.")]
            chart = viz.columns(stonecutter_items(), "Stonecutter switches",
                                '<span><i class="k-col"></i>A rule was learned from these switches</span><span><i class="k-col off"></i>No rule yet</span>')
            body.append(f'<section id="porting"><h2>Porting map</h2><p>How much of the map works today, against the target:</p>{viz.rows(book, pm)}'
                        f'{MAP_BODY.replace("{SC_CHART}", chart)}</section>')
    t_rows, vers, n_mods = tested_table()
    nav = "".join(f'<a href="#{a}">{w}</a>' for a, w in (("goals", "Goals"), ("roadmap", "Roadmap"), ("porting", "Porting map"), ("numbers", "Numbers"), ("evidence", "Evidence")))
    html = TEMPLATE
    for k, v in {
        "/*STYLE*/": STYLE + viz.VIZ_CSS + MAP_CSS + PAGE_CSS, "/*THEME*/": THEME_JS, "/*VIZ*/": viz.VIZ_JS, "/*MAP*/": MAP_JS,
        "{NAV}": nav, "{VERSION}": escape(version()), "{GOALCARDS}": goal_cards(book), "{TERMINAL}": terminal(book),
        "{KPIS}": (f"<div><b>{n_done} of {len(steps)}</b>steps done</div><div><b>{len(doing)}</b>being worked on</div>"
                   + (f"<div><b>{M.day(nxt['target'])}</b>next target (step {escape(nxt['id'])})</div>" if nxt else "")),
        "{BURNUP}": viz.burnup(book), "{NRUNS}": str(len(ledger["hops"])), "{NMODS}": str(n_mods), "{NNUM}": str(len(book.data["measures"])),
        "{GANTT}": viz.gantt(book), "{PHASES}": "".join(body), "{NUMBERS}": numbers_html(book),
        "{EVIDENCE}": evidence_html(t_rows, vers, n_mods),
        "{SET}": M.day(book.roadmap["targetsSet"]), "{MACHINE}": escape(book.data["machines"]["jon-pc"]),
    }.items():
        html = html.replace(k, v)
    return html


# ---- ROADMAP.md ---------------------------------------------------------------------------------
MARK = {"done": "🟢 done", "doing": "🔵 doing", "next": "⚪ next", "later": "⚫ later"}


def roadmap_md(book):
    """ROADMAP.md, written for GitHub's renderer: tables to scan, and the long text folded away."""
    r = book.roadmap
    cell = lambda text: text.replace("|", "\\|").replace("\n", " ")
    bar = lambda done, total: "█" * round(10 * done / total) + "░" * (10 - round(10 * done / total))
    when = lambda s: (M.day(s["done"]) if s.get("done") else f"{M.day(s['start'])} → {M.day(s['target'])}")

    def number(m):
        pts, mins = book.points(m), M.minutes(book, m)
        now = M.fmt(pts[-1]["value"], m["unit"], mins) if pts else "not measured"
        tgt = M.target_words(book, m)
        return f"{M.label(m)} is **{now}**" + (f", target {tgt}" if tgt else "")

    steps = [s for g in r["goals"] for s in g["steps"]]
    out = ["# Roadmap", "",
           "> Generated from [`coverage/roadmap.json`](coverage/roadmap.json) and [`coverage/measures.json`](coverage/measures.json) by",
           "> `python bench/bench_page.py --roadmap`. Edit those two files, not this one. The same roadmap with its timeline, charts and",
           "> porting map: **<https://noisemaker111.github.io/modding-accelerator/>**", "",
           f"**The goal:** {r['goal']}", "",
           f"**{sum(s['status'] == 'done' for s in steps)} of {len(steps)} steps done.** A step is {MARK['done']} only when it ran and was checked; the others are "
           f"{MARK['doing']}, {MARK['next']} or {MARK['later']}, each with a target date. Targets were set on {M.day(r['targetsSet'])}, and one that moves keeps its old date.", "",
           "## Where each goal stands", "",
           "| Goal | Steps done | Now |", "|---|---|---|"]
    for g in r["goals"]:
        c = goal_counts(g)
        now = [f"{MARK[s['status']].split()[0]} {s['id']} · {cell(s['title'])}" for status in ("doing", "next") for s in g["steps"] if s["status"] == status]
        out.append(f"| **[{g['id']}. {g['title']}](#goal-{g['id']}--{slug(g['title'])})** | `{bar(c['done'], len(g['steps']))}`&nbsp;{c['done']}&nbsp;of&nbsp;{len(g['steps'])} | "
                   f"{'<br>'.join(now) or 'Not started'} |")
    out.append("")
    for g in r["goals"]:
        out += [f"## Goal {g['id']} — {g['title']}", "", g["intro"], "", f"- **Today:** {g['today']}"]
        out += [f"- **{'The number that says most' if k == 0 else 'Also'}:** {number(book.by_id[i])}" for k, i in enumerate(g["headline"])] + [""]
        for part, ss in parts(g):
            out += [f"### {part}", "", "| # | Step | Status | When |", "|---|---|---|---|"]
            out += [f"| {s['id']} | {cell(s['title'])} | {MARK[s['status']]} | {when(s)} |" for s in ss]
            out.append("")
        out += ["<details><summary>What each step is, and how far it got</summary>", ""]
        for s in g["steps"]:
            out += [f"**{s['id']} · {s['title']}** ({s['status']}). {s['text']}" + (f" *So far:* {s['result']}" if s.get("result") else ""), ""]
            if s.get("points"):
                out += [f"- {x}" for x in s["points"]] + [""]
        out += ["</details>", ""]
        ms = [m for m in book.data["measures"] if m["area"] in GOAL_AREAS[g["id"]] and not m.get("context")]
        out += [f"<details><summary>Every number for this goal ({len(ms)})</summary>", "", M.table(book, ms), "", "</details>", ""]
    rest = [m for m in book.data["measures"] if not any(m["area"] in a for a in GOAL_AREAS.values()) and not m.get("context")]
    out += ["## Other numbers", "",
            "The same table for a PR or a report: `python bench/measures.py table [ID ...] [--area AREA] [--step STEP]`.", "",
            f"<details><summary>CI and the roadmap itself ({len(rest)})</summary>", "", M.table(book, rest), "", "</details>", "",
            "## Decisions", "", f"<details><summary>{len(r['decisions'])} decisions, oldest first</summary>", "", "| Date | Question | Choice |", "|---|---|---|"]
    out += [f"| {d['date']} | {cell(d['question'])} | {cell(d['choice'])} |" for d in r["decisions"]]
    out += ["", "</details>"]
    return "\n".join(out) + "\n"


def slug(title):
    """GitHub's anchor for a heading: lower case, punctuation dropped, spaces to hyphens."""
    return re.sub(r"\s", "-", re.sub(r"[^\w\s-]", "", title.lower()))


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
td.what { font-size:14px; color:var(--ink2); max-width:520px; line-height:1.45 }
td.none { color:var(--ink3) }
/* header */
header.top { position:sticky; top:0; z-index:5; background:color-mix(in srgb, var(--bg) 88%, transparent); backdrop-filter:blur(8px); padding:14px 0; border-bottom:1px solid var(--rule) }
.top nav a { text-decoration:none; color:var(--ink2) } .top nav a:hover { color:var(--ink) }
.brand { display:inline-flex; align-items:center; gap:10px; font-family:var(--f-pixel); font-size:24px; font-weight:600; color:var(--ink); text-decoration:none; letter-spacing:.01em }
.logo { display:grid; grid-template-columns:repeat(3, 7px); grid-auto-rows:7px; gap:1px }
.logo i { background:var(--dirt) } .logo i:nth-child(-n+3) { background:var(--grass) } .logo i:nth-child(2) { background:var(--grass3) } .logo i:nth-child(3) { background:var(--grass2) }
.logo i:nth-child(5), .logo i:nth-child(7) { background:var(--dirt2) }
/* hero */
.hero { display:grid; grid-template-columns:minmax(0, 1.1fr) minmax(0, .9fr); gap:44px; align-items:center; padding:72px 0 24px }
.eyebrow { font-family:var(--f-mono); font-size:13px; color:var(--ink3); letter-spacing:.02em; margin:0 0 14px }
.cta { display:flex; flex-wrap:wrap; gap:10px; margin:26px 0 12px }
.btn { display:inline-block; font-weight:600; font-size:15.5px; text-decoration:none; color:var(--ink); border:1.5px solid var(--axis); border-radius:6px; padding:10px 18px }
.btn:hover { border-color:var(--ink) }
.btn.primary { background:var(--ink); border-color:var(--ink); color:var(--bg) } .btn.primary:hover { opacity:.86 }
.term { margin:0; background:var(--term); color:var(--term-ink); border-radius:8px; padding:20px 22px; font-size:13.5px; line-height:1.75; overflow-x:auto; box-shadow:0 1px 0 var(--ring), 0 18px 40px -24px rgba(0,0,0,.45) }
.term code { font-size:inherit } .term span { display:block; white-space:pre-wrap; overflow-wrap:anywhere; padding-left:2ch; text-indent:-2ch }
.hero h1 { font-size:clamp(32px, 4.3vw, 46px) } .hero h1 span { display:block } .hero h1 span + span { color:var(--ink3) }
.t-say { color:var(--term-dim) } .t-say:not(:first-child) { margin-top:12px } .t-run i { color:var(--term-key); font-style:normal }
/* the four goals */
.g-cards { display:grid; grid-template-columns:repeat(4, minmax(0, 1fr)); gap:14px; margin-top:20px }
.g-card { position:relative; display:flex; flex-direction:column; gap:8px; min-width:0; padding:20px 18px 16px; background:var(--surface); border:1px solid var(--ring); border-radius:8px; color:var(--ink); text-decoration:none }
.g-card:hover { border-color:var(--ink3) }
.g-num { font-family:var(--f-pixel); font-size:40px; line-height:.9; font-weight:600; color:var(--grass) }
.g-card[data-built="no"] .g-num { color:var(--grey) }
.g-card h3 { margin:2px 0 0; font-size:19px; line-height:1.2 }
.g-today { font-size:14px; line-height:1.45; margin:0; flex:1 }
.g-cmds { display:flex; flex-wrap:wrap; gap:4px 5px } .g-cmds span { font-size:11.5px; color:var(--ink2); background:var(--head); border-radius:4px; padding:1px 6px }
.g-card .v-seg { margin:8px 0 0; max-width:none } .g-card .v-seglegend { font-size:12.5px }
.g-kpi { margin:6px 0 0; padding-top:10px; border-top:1px solid var(--rule); font-size:13px; line-height:1.35; color:var(--ink2) }
.g-kpi b { font-family:var(--f-head); font-size:26px; font-weight:700; color:var(--ink); letter-spacing:-.02em; margin-right:2px } .g-kpi span { display:block; color:var(--ink3); margin-top:2px }
.g-on { margin:0; font-size:12.5px; line-height:1.4; color:var(--ink3) }
/* folded sections */
.fold { border-top:1px solid var(--rule) } .fold:last-of-type { border-bottom:1px solid var(--rule) }
.fold > summary { display:flex; gap:12px; align-items:baseline; justify-content:space-between; cursor:pointer; padding:14px 2px; list-style:none; font-size:17px }
.fold > summary::-webkit-details-marker { display:none }
.fold > summary b { font-family:var(--f-head); font-weight:600 } .fold > summary b::before { content:"+"; display:inline-block; width:22px; color:var(--ink3); font-weight:500 }
.fold[open] > summary b::before { content:"\2212" }
.fold > summary span { font-size:13.5px; color:var(--ink3) } .fold > summary:hover b { text-decoration:underline }
.fold[open] { padding-bottom:22px }
.legendtbl { max-width:820px; font-size:14.5px; margin-bottom:24px } .legendtbl td { padding:8px 10px }
#porting, .v-phase { scroll-margin-top:76px }
.v-part { font-family:var(--f-body); font-size:12.5px; font-weight:600; text-transform:uppercase; letter-spacing:.07em; color:var(--ink3); margin:30px 0 0 }
:focus-visible { outline:2px solid var(--accent); outline-offset:2px }
@media (max-width: 980px) {
  .hero { grid-template-columns:minmax(0, 1fr); gap:28px; padding-top:44px }
  .g-cards { grid-template-columns:repeat(2, minmax(0, 1fr)) }
}
@media (max-width: 720px) {
  header.top { position:static } .top nav { font-size:14px }
  h2 { margin-top:60px; font-size:25px }
  td.what { max-width:none }
  .g-cards { grid-template-columns:minmax(0, 1fr) }
}
@media (prefers-reduced-motion: reduce) { html { scroll-behavior:auto } }
"""

TEMPLATE = r"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Modding Accelerator</title>
<meta name="description" content="modaccel tests a Minecraft mod in seconds, ports it to any version and makes modpacks work together. The roadmap, every number and every port run are on this page.">
<link rel="icon" type="image/svg+xml" href="logo.svg">
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&family=JetBrains+Mono:wght@400;500&family=Pixelify+Sans:wght@500;600&family=Space+Grotesk:wght@500;600;700&display=swap">
<style>
/*STYLE*/
</style></head><body>
<main id="top">
<header class="top">
  <a class="brand" href="#top"><span class="logo" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i></span>modaccel</a>
  <nav>{NAV}<a href="https://github.com/Noisemaker111/modding-accelerator">GitHub</a><button class="theme" id="theme" type="button">Dark</button></nav>
</header>

<section class="hero">
  <div class="hero-text">
    <p class="eyebrow">Modding Accelerator · beta {VERSION}</p>
    <h1><span>Test a Minecraft mod in seconds.</span> <span>Port it to any version.</span></h1>
    <p class="lede">One command for mod, plugin and modpack work. It runs your checks in the real game without you opening it, moves your source between Minecraft versions, and finds what breaks a modpack.</p>
    <div class="cta"><a class="btn primary" href="https://github.com/Noisemaker111/modding-accelerator/releases/latest">Download the beta</a><a class="btn" href="https://github.com/Noisemaker111/modding-accelerator#quick-start">Quick start</a></div>
    <p class="small">Needs Java 17 or newer. Windows, Linux and macOS. Fabric, NeoForge, Forge and Paper.</p>
  </div>
  <pre class="term" aria-label="Example commands"><code>{TERMINAL}</code></pre>
</section>

<h2 id="goals">Four goals</h2>
<p>Everything in the project serves one of these, in this order. Each card says what works today, how far its steps are, and the number that says most about it.</p>
<div class="g-cards">{GOALCARDS}</div>

<h2 id="roadmap">Roadmap</h2>
<p>Every step has a start and a target date. Targets were set on {SET} and move only with a note. A step is done only when it ran and was checked.</p>
<div class="v-kpis">{KPIS}</div>
<div class="v-score">{BURNUP}</div>

<h3 id="timeline">Timeline</h3>
<p>The vertical line is today; a step past its target that is not done turns red. Pick a step to read it.</p>
<div class="v-glegend"><span><i class="v-s-done"></i>Done</span><span><i style="background:var(--accent-wash);box-shadow:inset 0 0 0 1.5px var(--accent)"></i>Doing</span><span><i style="box-shadow:inset 0 0 0 1.5px var(--ink3)"></i>Next</span><span><i class="v-s-later"></i>Later</span><span><i style="box-shadow:inset 0 0 0 2px var(--red)"></i>Late</span></div>
{GANTT}

{PHASES}

<h2 id="numbers">Numbers</h2>
<p>{NNUM} numbers are tracked. Each is one measure: the same work, measured the same way each time, with its history and its target. Cold and warm are separate. Timings are from {MACHINE} unless the card says CI.</p>
{NUMBERS}

<h2 id="evidence">Evidence</h2>
<p>A claim on this page comes from a run that was recorded. A run that is not listed here did not happen.</p>
<table class="tbl stack legendtbl"><tbody>
<tr><td class="l"><span class="dot st-red"></span><b>Stops with named errors</b></td><td class="l">The port refuses to guess and lists what is left.</td></tr>
<tr><td class="l"><span class="dot st-yellow"></span><b>Builds, mixins checked</b></td><td class="l">The project compiles and the mixin audit passes. Not called working.</td></tr>
<tr><td class="l"><span class="dot st-green"></span><b>Loads in the real game</b></td><td class="l">A real client or server starts with the jar and lists the mod.</td></tr>
<tr><td class="l"><span class="dot st-green"></span><b>Behaviour checked in the game</b></td><td class="l">An assertion on the mod's own state passes in the game. A frame change or a reloaded save is not behaviour.</td></tr>
</tbody></table>
{EVIDENCE}

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
// a link to a step, a number or a folded section opens whatever holds it; old porting-map links land on the map
function reveal() {
  if (/(^|[#&])(from|to|step)=/.test(location.hash)) { document.getElementById("porting").scrollIntoView(); return; }
  let el = null; try { el = document.getElementById(decodeURIComponent(location.hash.slice(1))); } catch (e) {}
  if (!el) return;
  for (let d = el.closest("details"); d; d = d.parentElement && d.parentElement.closest("details")) d.open = true;
  const own = el.matches("details") ? el : el.querySelector(":scope > details"); if (own) own.open = true;
  el.scrollIntoView();
}
addEventListener("hashchange", reveal); reveal();
</script>
</body></html>
"""

if __name__ == "__main__":
    sys.exit(main())
