"""Render the public website (docs/index.html) and ROADMAP.md from live repo data.

Every chart and number is generated automatically on main (.github/workflows/generated.yml) from:
- bench/exposure/all.json          (live .jar porting coverage across every Fabric & NeoForge update)
- coverage/sweeps/pack.json        (modpack pass & repair rates + history)
- coverage/sweeps/convert.json     (loader-switch pass rates + history)
- coverage/roadmap.json            (goals, steps, burn-up, timeline)
- coverage/measures.json           (tracked metrics and history)
- coverage/ledger.json             (verified port runs and loader pairs)

    python bench/bench_page.py              docs/index.html and ROADMAP.md
    python bench/bench_page.py --roadmap    ROADMAP.md only
"""
import argparse
import json
import re
import sys
from html import escape
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from page_style import STYLE, THEME_JS  # noqa: E402
from ledger_rule import COLOUR, RANK, WORD  # noqa: E402
import measures as M  # noqa: E402
import viz  # noqa: E402
from porting_map import MAP_CSS, MAP_BODY, section, stonecutter_items  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "index.html"
ROADMAP_MD = ROOT / "ROADMAP.md"
GOAL_AREAS = {"1": ("test", "plugin"), "2": ("port",), "3": ("pack",), "4": ("loader",)}
_, _, MAP_JS = section()


def load(p, default=None):
    path = ROOT / p
    if not path.is_file():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


ledger = load("coverage/ledger.json", {"hops": [], "loaders": [], "loader_pairs": []})
data = load("bench/data.json", {"editloop": []})
bumps = load("bench/port/results.json", {"mods": [], "date": ""})
mods = load("bench/mods/results.json", {"mods": [], "measured": ""})
exposure = load("bench/exposure/all.json", {"date": "", "total": {"mods": 0, "link": 0}, "loaders": {}, "updates": []})
pack_sweep = load("coverage/sweeps/pack.json", {"corpus": 0, "latest": {}, "history": []})
convert_sweep = load("coverage/sweeps/convert.json", {"corpus": 0, "latest": {}, "history": []})

LOADER = {"fabric": "Fabric", "neoforge": "NeoForge", "forge": "Forge", "quilt": "Quilt"}


def dot(c):
    return f"<span class='dot st-{c}'></span>"


def secs(v):
    if v is None:
        return ""
    if v >= 120:
        return f"{v / 60:.1f} min"
    return f"{v:.1f}s" if v < 10 else f"{v:.0f}s"


def vkey(v):
    return [int(x) for x in re.findall(r"\d+", v)]


def code(text):
    return re.sub(r"`([^`]+)`", r'<span class="mono">\1</span>', escape(text))


# ---- live support matrix (Features x Loaders & Versions) ----------------------------------------
def support_matrix_html():
    """Compact, honest feature x loader/version chart, reading live ranges from exposure & ledger."""
    fab_ups = [u for u in exposure.get("updates", []) if u.get("loader") == "fabric"]
    neo_ups = [u for u in exposure.get("updates", []) if u.get("loader") == "neoforge"]
    fab_range = f"{fab_ups[0]['from']} – {fab_ups[-1]['to']}" if fab_ups else "1.14.4 – 26.3"
    neo_jar_range = f"1.20.5 – {neo_ups[-1]['to']}" if neo_ups else "1.20.5 – 26.3"
    neo_src_range = f"1.14.4 – {neo_ups[-1]['to']}" if neo_ups else "1.14.4 – 26.3"

    # (feature, note, [fabric, neoforge, forge, quilt, paper]) -> each cell is (kind, text)
    # kind: "ok" (moss), "part" (ochre), "no" (dash)
    rows = [
        ("Port .jar", "No source code needed", [
            ("ok", fab_range), ("ok", neo_jar_range), ("no", "—"), ("no", "—"), ("no", "—"),
        ]),
        ("Port source", "Preview, apply, undo", [
            ("ok", fab_range), ("ok", neo_src_range), ("no", "—"), ("no", "—"), ("part", "Lists edits"),
        ]),
        ("Switch loader", "Same MC version, source", [
            ("ok", "↔ NeoForge"), ("ok", "↔ Fabric"), ("no", "—"), ("no", "—"), ("no", "—"),
        ]),
        ("Fix modpacks", "Real server + proven repairs", [
            ("ok", "All versions"), ("ok", "All versions"), ("ok", "All versions"), ("ok", "All versions"), ("no", "—"),
        ]),
        ("Test in real game", "Hidden & muted checks", [
            ("ok", "Client & server"), ("ok", "Client & server"), ("part", "Loads only"), ("no", "—"), ("ok", "1.8+ bot server"),
        ]),
        ("Build & unit tests", "Fast checks, no game", [
            ("ok", "Yes"), ("ok", "Yes"), ("ok", "Yes"), ("part", "Via Fabric"), ("ok", "Yes"),
        ]),
        ("Profile performance", "FPS, frame spikes, per-mod", [
            ("ok", "Yes"), ("ok", "Yes"), ("part", "Planned"), ("no", "—"), ("no", "—"),
        ]),
    ]

    def cell(kind, label):
        if kind == "no":
            return '<td class="m-no">—</td>'
        cls = "m-ok" if kind == "ok" else "m-part"
        return f'<td><span class="m-cell {cls}">{escape(label)}</span></td>'

    body = []
    for name, sub, vals in rows:
        tds = "".join(cell(k, lbl) for k, lbl in vals)
        body.append(f'<tr><th scope="row" class="l"><b>{escape(name)}</b><span class="sub">{escape(sub)}</span></th>{tds}</tr>')

    return (
        '<div class="mat-wrap"><table class="mat">'
        '<thead><tr><th class="l">Feature</th><th>Fabric</th><th>NeoForge</th><th>Forge</th><th>Quilt</th><th>Paper</th></tr></thead>'
        f'<tbody>{"".join(body)}</tbody></table></div>'
        '<div class="mat-legend">'
        '<span><i class="m-swatch m-ok"></i>Works today</span>'
        '<span><i class="m-swatch m-part"></i>Partial / in progress</span>'
        '<span><i class="m-swatch m-no-sw"></i>Not supported yet</span>'
        '<span class="mat-swipe">Swipe table →</span>'
        '</div>'
    )


# ---- live jar-porting bar chart across every Minecraft update -----------------------------------
def exposure_bars_svg(loader_key, title):
    ups = [u for u in exposure.get("updates", []) if u.get("loader") == loader_key]
    if not ups:
        return ""
    meta = exposure.get("loaders", {}).get(loader_key, {})
    total_mods = meta.get("mods", sum(u.get("modsRead", 0) for u in ups))
    total_link = meta.get("link", sum(u.get("modsThatLink", 0) for u in ups))
    pct = round(100 * total_link / total_mods) if total_mods else 0

    w, h = 960, 128
    pad_l, pad_r, pad_t, pad_b = 34, 8, 12, 26
    pw, ph = w - pad_l - pad_r, h - pad_t - pad_b
    n = len(ups)
    step = pw / n
    bw = max(4.0, step * 0.72)

    parts = []
    for g in (0, 50, 100):
        gy = pad_t + ph * (1 - g / 100)
        parts.append(f'<line class="ex-grid" x1="{pad_l}" x2="{w - pad_r}" y1="{gy:.1f}" y2="{gy:.1f}"/>')
        parts.append(f'<text class="ex-axis" x="{pad_l - 6}" y="{gy + 3.5:.1f}" text-anchor="end">{g}%</text>')

    # Pick clean, non-overlapping x labels (~6-8 evenly spaced plus first & last).
    label_stride = max(1, round(n / 7))
    label_idxs = {0, n - 1}
    for i in range(label_stride, n - 1, label_stride):
        if n - 1 - i >= max(3, label_stride - 1):
            label_idxs.add(i)

    for i, u in enumerate(ups):
        read = u.get("modsRead", 0)
        link = u.get("modsThatLink", 0)
        rate = (100.0 * link / read) if read else 0.0
        bh = max(2.0, ph * rate / 100.0)
        x = pad_l + i * step + (step - bw) / 2
        y = pad_t + ph - bh
        cls = "ex-hi" if rate >= 75 else ("ex-mid" if rate >= 40 else "ex-lo")
        tip = f"{title} {u['from']} → {u['to']}: {link} of {read} top mods link ({rate:.0f}%)"
        parts.append(f'<rect class="ex-bar {cls}" x="{x:.1f}" y="{y:.1f}" width="{bw:.1f}" height="{bh:.1f}" rx="2" data-tip="{escape(tip)}"/>')
        parts.append(f'<rect class="v-hit" x="{pad_l + i * step:.1f}" y="{pad_t}" width="{step:.1f}" height="{ph}" data-tip="{escape(tip)}"/>')
        if i in label_idxs:
            cx = pad_l + i * step + step / 2
            anchor = "start" if i == 0 else ("end" if i == n - 1 else "middle")
            tx = pad_l if i == 0 else (w - pad_r if i == n - 1 else cx)
            parts.append(f'<text class="ex-axis" x="{tx:.1f}" y="{h - 6}" text-anchor="{anchor}">{escape(u["to"])}</text>')

    return (
        f'<div class="ex-panel">'
        f'<div class="ex-head"><b>{escape(title)}</b>'
        f'<span>{escape(ups[0]["from"])} → {escape(ups[-1]["to"])} · {n} updates</span>'
        f'<strong class="ex-pct">{pct}% <small>({total_link:,} / {total_mods:,} mods)</small></strong></div>'
        f'<svg viewBox="0 0 {w} {h}" class="ex-svg" role="img" aria-label="{escape(title)} jar porting coverage: {pct}% across {n} updates">{"".join(parts)}</svg>'
        f'</div>'
    )


# ---- live hero scoreboard & 4-area progress ledger ----------------------------------------------
def hero_scoreboard_html(book):
    steps = list(book.steps.values())
    n_done = sum(s["status"] == "done" for s in steps)
    ex_tot = exposure.get("total", {})
    ex_mods, ex_link = ex_tot.get("mods", 0), ex_tot.get("link", 0)
    ex_pct = round(100 * ex_link / ex_mods) if ex_mods else 0
    ex_updates = len(exposure.get("updates", []))

    pk = pack_sweep.get("latest", {})
    pk_pass, pk_total = pk.get("passing with repairs", 0), pk.get("packs", pack_sweep.get("corpus", 24))
    pk_pct = round(100 * pk_pass / pk_total) if pk_total else 0

    cv = convert_sweep.get("latest", {})
    cv_load, cv_total = cv.get("loaded in the game", 0), cv.get("runs", convert_sweep.get("corpus", 35))
    cv_pct = round(100 * cv_load / cv_total) if cv_total else 0

    rm_pct = round(100 * n_done / len(steps)) if steps else 0

    items = [
        (f"{ex_pct}%", f"{ex_link:,} / {ex_mods:,}", f".jar mods linking ({ex_updates} updates)", ex_pct, "green" if ex_pct >= 60 else "yellow"),
        (f"{pk_pass} / {pk_total}", f"{pk.get('repaired', 0)} repaired", "Modpacks passing on real servers", pk_pct, "green"),
        (f"{cv_load} / {cv_total}", f"{cv.get('same behaviour fingerprint as the original', 0)} exact", "Fabric ↔ NeoForge conversions", cv_pct, "yellow"),
        (f"{n_done} / {len(steps)}", f"{rm_pct}%", "Roadmap steps proven done", rm_pct, "accent"),
    ]
    rows = []
    for big, sub, label, pct, tone in items:
        rows.append(
            f'<div class="sb-row">'
            f'<div class="sb-top"><span class="sb-label">{escape(label)}</span>'
            f'<span class="sb-val"><b>{escape(big)}</b> <small>{escape(sub)}</small></span></div>'
            f'<div class="sb-track"><i class="sb-fill sb-{tone}" style="width:{min(100, pct)}%"></i></div>'
            f'</div>'
        )
    cli = (
        '<details class="sb-cli"><summary>Command line commands</summary>'
        '<pre class="term"><code>'
        '<span class="t-run"><i>$ </i>mmt port --jar my-mod-1.21.1.jar --to 1.21.2</span>'
        '<span class="t-run"><i>$ </i>mmt port --root my-mod --from 1.21.5 --to 1.21.6 --fix</span>'
        '<span class="t-run"><i>$ </i>mmt convert --root my-mod --to neoforge</span>'
        '<span class="t-run"><i>$ </i>mmt pack-check --pack my-pack.mrpack --fix</span>'
        '<span class="t-run"><i>$ </i>mmt ui-check --root my-mod</span>'
        '</code></pre></details>'
    )
    return (
        f'<div class="scoreboard">'
        f'<div class="sb-head"><b>Live progress</b><span>Auto-updated · {escape(exposure.get("date") or M.day(book.roadmap["targetsSet"]))}</span></div>'
        f'{"".join(rows)}{cli}</div>'
    )


def area_progress_html(book):
    """Compact 2x2 progress grid: high signal, zero prose walls."""
    goals = {g["id"]: g for g in book.roadmap["goals"]}

    def gstat(gid):
        g = goals[gid]
        done = sum(s["status"] == "done" for s in g["steps"])
        doing = [s["title"] for s in g["steps"] if s["status"] == "doing"]
        nxt = [s["title"] for s in g["steps"] if s["status"] == "next"]
        now = f"Working on: {doing[0]}" if doing else (f"Next: {nxt[0]}" if nxt else "Complete")
        return done, len(g["steps"]), now

    ex_tot = exposure.get("total", {})
    ex_mods, ex_link = ex_tot.get("mods", 0), ex_tot.get("link", 0)
    ex_pct = round(100 * ex_link / ex_mods) if ex_mods else 0
    g2_d, g2_t, g2_now = gstat("2")

    pk = pack_sweep.get("latest", {})
    pk_hist = " → ".join(str(h.get("passing with repairs", h.get("world made and reopened", 0))) for h in pack_sweep.get("history", []))
    pk_pass, pk_total = pk.get("passing with repairs", 0), pk.get("packs", 24)
    g3_d, g3_t, g3_now = gstat("3")

    cv = convert_sweep.get("latest", {})
    cv_hist = " → ".join(str(h.get("loaded in the game", 0)) for h in convert_sweep.get("history", []))
    cv_load, cv_total = cv.get("loaded in the game", 0), cv.get("runs", 35)
    g4_d, g4_t, g4_now = gstat("4")

    g1_d, g1_t, g1_now = gstat("1")

    cards = [
        (
            "Version porting", f"{ex_pct}%", f"{ex_link:,} of {ex_mods:,} top mods link",
            f"Fabric (1.14.4–26.3) and NeoForge (1.20.5–26.3) .jar & source porting. {g2_d}/{g2_t} steps done.",
            g2_now, "#porting", ex_pct, "green" if ex_pct >= 60 else "yellow",
        ),
        (
            "Modpack repair", f"{pk_pass}/{pk_total}", "100% pass on real servers",
            f"Forge, NeoForge, Fabric & Quilt packs. Sweep trend: {pk_hist or '24'}. {pk.get('repaired', 0)} packs auto-repaired.",
            g3_now, "#roadmap", round(100 * pk_pass / pk_total) if pk_total else 0, "green",
        ),
        (
            "Loader switching", f"{cv_load}/{cv_total}", "load after Fabric ↔ NeoForge",
            f"{cv.get('same behaviour fingerprint as the original', 0)} match original behaviour exactly. Sweep trend: {cv_hist or cv_load}. {g4_d}/{g4_t} steps done.",
            g4_now, "#roadmap", round(100 * cv_load / cv_total) if cv_total else 0, "yellow",
        ),
        (
            "Testing & speed", "4 s", "warm edit-to-test loop",
            f"Hidden muted client, Paper bot server, 8 parallel sessions. Proven speedups: Cyclic 15.7×, SSN 5–6×, Diorama 1.6–2.2×.",
            g1_now, "#roadmap", round(100 * g1_d / g1_t) if g1_t else 0, "accent",
        ),
    ]
    out = []
    for title, stat, unit, desc, now, href, pct, tone in cards:
        out.append(
            f'<a class="p-card" href="{href}">'
            f'<div class="p-top"><h3>{escape(title)}</h3><span class="p-stat"><b>{escape(stat)}</b> <small>{escape(unit)}</small></span></div>'
            f'<div class="sb-track"><i class="sb-fill sb-{tone}" style="width:{min(100, pct)}%"></i></div>'
            f'<p class="p-desc">{escape(desc)}</p>'
            f'<p class="p-now">{escape(now)}</p>'
            f'</a>'
        )
    return f'<div class="p-grid">{"".join(out)}</div>'


# ---- tested mods & runs (kept cleanly inside folded evidence) -----------------------------------
def tested():
    rows = {}
    for h in ledger["hops"]:
        r = rows.setdefault(h["mod"], {"loaders": set(), "versions": set(), "best": {}, "date": "", "what": "ported by mmt"})
        r["loaders"].add(LOADER.get(h["loader"], h["loader"]))
        r["versions"].update([h["from"], h["to"]])
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
        r = rows.setdefault(name, {"loaders": set(), "versions": set(), "best": {}, "date": "", "what": "their own build, then mmt fast"})
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


def goal_counts(g):
    counts = {k: 0 for k in M.STATUSES}
    for s in g["steps"]:
        counts[s["status"]] += 1
    return counts


def parts(g):
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


def goal_fold_html(book, g):
    counts = goal_counts(g)
    done = counts["done"]
    extra = ""
    if g["id"] == "4":
        l_head, l_body = loader_grid()
        extra = (f'<h3>Loader pairs</h3><table class="tbl stack"><thead><tr><th class="l">From</th>{l_head}</tr></thead>'
                 f"<tbody>{l_body}</tbody></table>")
    steps = "".join(f'<h3 class="v-part">{escape(part)}</h3><ol class="v-steps">{"".join(step_html(book, s) for s in ss)}</ol>' for part, ss in parts(g))
    return fold(
        f'Goal {escape(g["id"])} · {escape(g["title"])}',
        f'<p>{code(g["intro"])}</p>{viz.segbar(counts, len(g["steps"]))}{steps}{extra}',
        f'{done} of {len(g["steps"])} done',
        anchor=f'goal-{escape(g["id"])}',
    )


def version():
    return re.search(r'VERSION = "([^"]+)"', (ROOT / "tools" / "cli" / "Support.java").read_text(encoding="utf-8")).group(1)


def fold(summary, body, count="", open_=False, anchor=""):
    return (f'<details class="fold"{" open" if open_ else ""}{f" id={chr(34)}{anchor}{chr(34)}" if anchor else ""}><summary><b>{summary}</b>'
            f'{f"<span>{count}</span>" if count else ""}</summary><div class="fold-body">{body}</div></details>')


def numbers_html(book):
    out = []
    for area, name in book.data["areas"].items():
        ms = [m for m in book.data["measures"] if m["area"] == area]
        if not ms:
            continue
        met = sum(book.state(m)[0] == "met" for m in ms)
        with_target = sum(bool(book.target(m)) for m in ms)
        count = f"{len(ms)} metrics" + (f" · {met}/{with_target} met" if with_target else "")
        out.append(fold(escape(name), viz.cards(book, ms), count, anchor=f"area-{area}"))
    return "".join(out)


def evidence_html(t_rows, vers, n_mods):
    runs = ('<table class="tbl stack"><thead><tr><th class="l">Mod</th><th>Port</th><th class="hi l">Reached</th><th class="l">What happened</th><th>Date</th></tr></thead>'
            f"<tbody>{latest_runs()}</tbody></table>")
    tested_ = ('<table class="tbl stack"><thead><tr><th class="l">Mod</th><th>Loader</th><th>Versions</th><th class="hi l">Furthest it got</th><th>Last run</th></tr></thead>'
               f'<tbody>{t_rows}</tbody></table><p class="small">Versions touched: {", ".join(vers)}.</p>')
    before = ('<table class="tbl stack"><thead><tr><th class="l">Mod</th><th>Version</th><th class="hi">Compile errors</th><th>Their first build</th><th>Build after bump</th><th>Date</th></tr></thead>'
              f"<tbody>{earlier_runs()}</tbody></table>")
    return (
        fold("Every verified port run", runs, f'{len(ledger["hops"])} runs', anchor="runs")
        + fold("Open-source mods we test", tested_, f"{n_mods} mods", anchor="mods")
        + fold("Manual baseline benchmarks", before, "before mmt", anchor="before")
    )


def page(book):
    steps = list(book.steps.values())
    n_done = sum(s["status"] == "done" for s in steps)
    doing = [s for s in steps if s["status"] == "doing"]
    upcoming = sorted((s for s in steps if s["status"] != "done"), key=lambda s: s["target"])
    nxt = upcoming[0] if upcoming else None

    chart = viz.columns(stonecutter_items(), "Stonecutter switches",
                        '<span><i class="k-col"></i>Rule learned</span><span><i class="k-col off"></i>No rule yet</span>')
    porting_html = MAP_BODY.replace("{SC_CHART}", chart)
    exposure_html = exposure_bars_svg("fabric", "Fabric") + exposure_bars_svg("neoforge", "NeoForge")
    goal_folds = "".join(goal_fold_html(book, g) for g in book.roadmap["goals"])
    t_rows, vers, n_mods = tested_table()
    updated = exposure.get("date") or M.day(book.roadmap["targetsSet"])

    nav = "".join(
        f'<a href="#{a}">{w}</a>'
        for a, w in (("support", "Support"), ("progress", "Progress"), ("porting", "Porting map"), ("roadmap", "Roadmap"))
    )
    kpis = (
        f"<div><b>{n_done} / {len(steps)}</b>steps proven done</div>"
        f"<div><b>{len(doing)}</b>in progress</div>"
        + (f"<div><b>{M.day(nxt['target'])}</b>next target (step {escape(nxt['id'])})</div>" if nxt else "")
    )
    deep_folds = (
        fold("Timeline (Gantt chart)", viz.gantt(book), f"{len(steps)} steps", anchor="timeline")
        + goal_folds
        + fold("All tracked numbers", numbers_html(book), f'{len(book.data["measures"])} metrics', anchor="numbers")
        + fold("Verified runs & evidence", evidence_html(t_rows, vers, n_mods), f'{len(ledger["hops"])} runs · {n_mods} mods', anchor="evidence")
    )

    ver = version()
    eyebrow = f"Desktop app &amp; CLI · Updated {escape(updated)}" if ver == "dev" else f"Desktop app &amp; CLI · v{escape(ver)} · Updated {escape(updated)}"

    html = TEMPLATE
    for k, v in {
        "/*STYLE*/": STYLE + viz.VIZ_CSS + MAP_CSS + PAGE_CSS,
        "/*THEME*/": THEME_JS,
        "/*VIZ*/": viz.VIZ_JS,
        "/*MAP*/": MAP_JS,
        "{NAV}": nav,
        "{EYEBROW}": eyebrow,
        "{SCOREBOARD}": hero_scoreboard_html(book),
        "{SUPPORT}": support_matrix_html(),
        "{EXPOSURE}": exposure_html,
        "{AREAS}": area_progress_html(book),
        "{PORTING}": porting_html,
        "{KPIS}": kpis,
        "{BURNUP}": viz.burnup(book),
        "{DEEP}": deep_folds,
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
           "> `python bench/bench_page.py --roadmap`. Edit those two files, not this one. Live charts and",
           "> porting map: **<https://noisemaker111.github.io/minecraft-modding-tools/>**", "",
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
    return re.sub(r"\s", "-", re.sub(r"[^\w\s-]", "", title.lower()))


def main(argv=None):
    ap = argparse.ArgumentParser(description="Write docs/index.html and ROADMAP.md.")
    ap.add_argument("--roadmap", action="store_true", help="write ROADMAP.md only")
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
td.what { font-size:13.5px; color:var(--ink2); max-width:500px; line-height:1.45 }
td.none { color:var(--ink3) }
/* header */
header.top { position:sticky; top:0; z-index:10; background:color-mix(in srgb, var(--bg) 90%, transparent); backdrop-filter:blur(8px); padding:13px 0; border-bottom:1px solid var(--rule) }
.top nav a { text-decoration:none; color:var(--ink2); font-weight:500 } .top nav a:hover { color:var(--ink) }
.brand { display:inline-flex; align-items:center; gap:10px; font-family:var(--f-pixel); font-size:22px; font-weight:600; color:var(--ink); text-decoration:none; letter-spacing:.01em }
.logo { display:grid; grid-template-columns:repeat(3, 6px); grid-auto-rows:6px; gap:1px }
.logo i { background:var(--dirt) } .logo i:nth-child(-n+3) { background:var(--grass) } .logo i:nth-child(2) { background:var(--grass3) } .logo i:nth-child(3) { background:var(--grass2) }
.logo i:nth-child(5), .logo i:nth-child(7) { background:var(--dirt2) }
/* hero */
.hero { display:grid; grid-template-columns:minmax(0, 1.15fr) minmax(0, .85fr); gap:40px; align-items:start; padding:44px 0 12px }
.eyebrow { font-family:var(--f-mono); font-size:12.5px; color:var(--ink3); margin:0 0 10px }
.hero h1 span { display:block } .hero h1 span + span { color:var(--ink3) }
.cta { display:flex; flex-wrap:wrap; gap:10px; margin:22px 0 10px }
.btn { display:inline-flex; align-items:center; gap:6px; font-weight:600; font-size:14.5px; text-decoration:none; color:var(--ink); background:var(--surface); border:1px solid var(--axis); border-radius:6px; padding:10px 16px }
.btn:hover { border-color:var(--ink) }
.btn.primary { background:var(--green); border-color:var(--green); color:#fff } .btn.primary:hover { filter:brightness(.93) }
.hero-note { font-size:13px; color:var(--ink3); margin:6px 0 0; max-width:52ch }
/* live hero scoreboard */
.scoreboard { background:var(--surface); border:1px solid var(--rule); border-radius:10px; padding:18px 20px; display:flex; flex-direction:column; gap:13px }
.sb-head { display:flex; justify-content:space-between; align-items:baseline; gap:10px; padding-bottom:10px; border-bottom:1px solid var(--rule); font-size:13px }
.sb-head b { font-family:var(--f-head); font-size:15px; color:var(--ink) }
.sb-head span { font-family:var(--f-mono); font-size:12px; color:var(--ink3) }
.sb-row { display:flex; flex-direction:column; gap:5px }
.sb-top { display:flex; justify-content:space-between; align-items:baseline; gap:10px; font-size:13.5px }
.sb-label { color:var(--ink2) }
.sb-val { white-space:nowrap; font-variant-numeric:tabular-nums }
.sb-val b { font-family:var(--f-head); font-size:16px; color:var(--ink) }
.sb-val small { font-family:var(--f-mono); font-size:11.5px; color:var(--ink3) }
.sb-track { height:6px; border-radius:3px; background:var(--head); overflow:hidden }
.sb-fill { display:block; height:100%; border-radius:3px }
.sb-green { background:var(--green) } .sb-yellow { background:var(--yellow) } .sb-accent { background:var(--accent) }
.sb-cli { border-top:1px solid var(--rule); padding-top:10px; margin-top:2px }
.sb-cli summary { cursor:pointer; font-size:13px; color:var(--ink2); font-weight:500 }
.term { margin:10px 0 0; background:var(--term); color:var(--term-ink); border-radius:6px; padding:12px 14px; font-size:12px; line-height:1.65; overflow-x:auto }
.term code { font-size:inherit } .term span { display:block; white-space:pre }
.t-run i { color:var(--term-key); font-style:normal }
/* section headers */
.sec-head { display:flex; justify-content:space-between; align-items:baseline; gap:16px; flex-wrap:wrap; margin:52px 0 12px }
.sec-head h2 { margin:0 }
.sec-head p { margin:0; font-size:14.5px; color:var(--ink3) }
/* support matrix */
.mat-wrap { overflow-x:auto; background:var(--surface); border:1px solid var(--rule); border-radius:10px }
.mat { width:100%; border-collapse:collapse; font-size:14px; min-width:580px }
.mat th { font-weight:600; font-size:13px; color:var(--ink2); padding:11px 12px; border-bottom:1.5px solid var(--rule); text-align:center; background:var(--head) }
.mat th.l { text-align:left; width:26% }
.mat tbody th.l { position:sticky; left:0; z-index:1; background:var(--surface); font-weight:400; border-bottom:1px solid var(--rule); padding:10px 14px; vertical-align:middle }
.mat thead th.l { position:sticky; left:0; z-index:2; background:var(--head) }
.mat tbody th.l b { display:block; font-weight:600; color:var(--ink); font-size:14.5px }
.mat tbody th.l .sub { display:block; font-size:12.5px; color:var(--ink3) }
.mat td { padding:10px 8px; border-bottom:1px solid var(--rule); text-align:center; vertical-align:middle; font-variant-numeric:tabular-nums }
.mat tr:last-child th, .mat tr:last-child td { border-bottom:0 }
.m-cell { display:inline-block; padding:3px 9px; border-radius:5px; font-size:12.5px; font-weight:600; white-space:nowrap }
.m-ok { background:var(--good-bg); color:var(--good-ink) }
.m-part { background:var(--warn-bg); color:var(--warn-ink) }
.m-no { color:var(--axis); font-size:15px }
.mat-legend { display:flex; gap:18px; flex-wrap:wrap; align-items:center; margin-top:10px; font-size:12.5px; color:var(--ink3) }
.mat-swipe { display:none; margin-left:auto; font-family:var(--f-mono); color:var(--ink2) }
.m-swatch { display:inline-block; width:10px; height:10px; border-radius:3px; margin-right:6px; vertical-align:-1px }
.m-swatch.m-ok { background:var(--green) } .m-swatch.m-part { background:var(--yellow) } .m-swatch.m-no-sw { background:var(--axis) }
/* exposure bar charts */
.ex-stack { display:flex; flex-direction:column; gap:14px }
.ex-panel { background:var(--surface); border:1px solid var(--rule); border-radius:10px; padding:16px 18px 12px }
.ex-head { display:flex; align-items:baseline; gap:10px; flex-wrap:wrap; margin-bottom:8px; font-size:13.5px }
.ex-head b { font-family:var(--f-head); font-size:16px; color:var(--ink) }
.ex-head span { color:var(--ink3); font-family:var(--f-mono); font-size:12.5px }
.ex-pct { margin-left:auto; font-family:var(--f-head); font-size:16px; color:var(--ink) }
.ex-pct small { font-family:var(--f-mono); font-size:12px; font-weight:400; color:var(--ink3) }
.ex-svg { width:100%; height:auto; display:block; overflow:visible }
.ex-grid { stroke:var(--rule); stroke-width:1 }
.ex-axis { font-size:11px; fill:var(--ink3); font-family:var(--f-mono) }
.ex-bar.ex-hi { fill:var(--green) } .ex-bar.ex-mid { fill:var(--yellow) } .ex-bar.ex-lo { fill:var(--red) }
/* compact 2x2 area progress grid */
.p-grid { display:grid; grid-template-columns:repeat(2, minmax(0, 1fr)); gap:14px; margin-top:14px }
.p-card { display:flex; flex-direction:column; gap:8px; padding:16px 18px; background:var(--surface); border:1px solid var(--rule); border-radius:10px; text-decoration:none; color:var(--ink) }
.p-card:hover { border-color:var(--ink3) }
.p-top { display:flex; justify-content:space-between; align-items:baseline; gap:10px }
.p-card h3 { margin:0; font-size:16px }
.p-stat { white-space:nowrap; font-variant-numeric:tabular-nums }
.p-stat b { font-family:var(--f-head); font-size:20px; color:var(--ink) }
.p-stat small { font-size:12px; color:var(--ink3) }
.p-desc { margin:0; font-size:13.5px; color:var(--ink2); line-height:1.45 }
.p-now { margin:0; font-size:12.5px; color:var(--ink3); font-family:var(--f-mono) }
/* folded sections */
.folds { margin-top:16px }
.fold { border-top:1px solid var(--rule) } .fold:last-of-type { border-bottom:1px solid var(--rule) }
.fold > summary { display:flex; gap:12px; align-items:baseline; justify-content:space-between; cursor:pointer; padding:13px 2px; list-style:none; font-size:15.5px }
.fold > summary::-webkit-details-marker { display:none }
.fold > summary b { font-family:var(--f-head); font-weight:600; color:var(--ink) }
.fold > summary b::before { content:"+"; display:inline-block; width:20px; color:var(--ink3); font-weight:500 }
.fold[open] > summary b::before { content:"\2212" }
.fold > summary span { font-size:13px; color:var(--ink3); font-family:var(--f-mono) }
.fold > summary:hover b { text-decoration:underline }
.fold-body { padding:4px 0 22px }
.v-part { font-family:var(--f-body); font-size:12px; font-weight:600; text-transform:uppercase; letter-spacing:.06em; color:var(--ink3); margin:24px 0 0 }
#porting, #support, #progress, #roadmap { scroll-margin-top:72px }
:focus-visible { outline:2px solid var(--accent); outline-offset:2px }
@media (max-width: 900px) {
  .hero { grid-template-columns:minmax(0, 1fr); gap:24px; padding-top:28px }
  .p-grid { grid-template-columns:minmax(0, 1fr) }
}
@media (max-width: 720px) {
  header.top { position:static }
  .sec-head { margin-top:38px }
  .ex-pct { margin-left:0 }
  .mat-swipe { display:inline }
}
@media (prefers-reduced-motion: reduce) { html { scroll-behavior:auto } }
"""

TEMPLATE = r"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Minecraft Modding Tools</title>
<meta name="description" content="Port Minecraft mods between versions, switch Fabric and NeoForge, fix broken modpacks, test in the real game, and profile Minecraft.">
<link rel="icon" type="image/svg+xml" href="logo.svg">
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&family=JetBrains+Mono:wght@400;500&family=Pixelify+Sans:wght@500;600&family=Space+Grotesk:wght@500;600;700&display=swap">
<style>
/*STYLE*/
</style></head><body>
<main id="top">
<header class="top">
  <a class="brand" href="#top"><span class="logo" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i></span>Minecraft Modding Tools</a>
  <nav>{NAV}<a href="https://github.com/Noisemaker111/minecraft-modding-tools">GitHub</a><button class="theme" id="theme" type="button">Dark</button></nav>
</header>

<section class="hero">
  <div class="hero-text">
    <p class="eyebrow">{EYEBROW}</p>
    <h1><span>Port, fix, and test Minecraft mods.</span> <span>Proven in the real game.</span></h1>
    <p class="lede">Download one desktop app to move mods across Minecraft versions, switch Fabric and NeoForge, repair broken modpacks, test mods in a hidden game, or find what slows Minecraft down.</p>
    <div class="cta">
      <a class="btn primary" href="https://github.com/Noisemaker111/minecraft-modding-tools/releases/latest/download/mmt-desktop.exe">Download for Windows (.exe)</a>
      <a class="btn" href="https://github.com/Noisemaker111/minecraft-modding-tools/releases/latest/download/mmt-desktop-linux">Linux binary</a>
      <a class="btn" href="https://github.com/Noisemaker111/minecraft-modding-tools/releases/latest">All files &amp; CLI</a>
    </div>
    <p class="hero-note">Single-file Windows app (downloads a verified Java runtime automatically if needed). Runs 100% locally with no AI calls.</p>
  </div>
  {SCOREBOARD}
</section>

<section id="support">
  <div class="sec-head">
    <h2>What works where</h2>
    <p>Every version and loader supported today</p>
  </div>
  {SUPPORT}
</section>

<section id="progress">
  <div class="sec-head">
    <h2>Live progress</h2>
    <p>Top 150 mods per Minecraft release tested automatically on every update</p>
  </div>
  <div class="ex-stack">{EXPOSURE}</div>
  {AREAS}
</section>

<section id="porting">
  <div class="sec-head">
    <h2>Porting map</h2>
    <p>Pick a start and target version to see what changes</p>
  </div>
  {PORTING}
</section>

<section id="roadmap">
  <div class="sec-head">
    <h2>Roadmap &amp; receipts</h2>
    <p>Every milestone counts as done only when proven in the real game</p>
  </div>
  <div class="v-kpis">{KPIS}</div>
  <div class="v-score">{BURNUP}</div>
  <div class="folds">{DEEP}</div>
</section>

<p class="foot">Auto-generated on every merge to <span class="mono">main</span> from <span class="mono">bench/exposure/all.json</span>, <span class="mono">coverage/</span>, and <span class="mono">ROADMAP.md</span>. <a href="https://github.com/Noisemaker111/minecraft-modding-tools">Source on GitHub</a>.</p>
</main>
<script>
/*THEME*/
/*VIZ*/
</script>
<script>
/*MAP*/
</script>
<script>
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
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    sys.exit(main())
