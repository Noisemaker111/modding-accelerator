"""The one way modaccel draws a number: measure cards, compact rows, sparklines, the roadmap timeline
and the burn-up chart. Every function takes a measures.Book and returns HTML; the look comes from
page_style.STYLE tokens plus VIZ_CSS here, and VIZ_JS adds tooltips, today's date and "late".

The page is deterministic (the release job regenerates it and diffs): anything that depends on today,
such as the today line or a target that has slipped, is worked out in the viewer's browser.
"""
from datetime import date
from html import escape

import measures as M

STATUS_WORD = {"done": "Done", "doing": "Doing", "next": "Next", "later": "Later"}


def esc(s):
    return escape(str(s), quote=True)


def ordinal(d):
    return date.fromisoformat(d).toordinal()


def num_text(book, m, v):
    return M.num(v, m["unit"], M.minutes(book, m))


def unit_text(book, m, v):
    return M.unit_word(v, m["unit"], M.minutes(book, m))


def fmt(book, m, v):
    return M.fmt(v, m["unit"], M.minutes(book, m))


def tip_for(book, m, p):
    bits = [f"{M.day(p['date'])}: {fmt(book, m, p['value'])}"]
    if p.get("n"):
        bits.append(f"n {p['n']}")
    if p.get("note"):
        bits.append(p["note"])
    return " · ".join(bits)


# ---- sparkline ----------------------------------------------------------------------------------
def spark(book, m, w=228, h=76, mini=False):
    """Points as a 2px line with ringed dots, and a dashed path from the last point to the target ring."""
    pts, t = book.points(m), book.target(m)
    tval = t.get("value") if t and isinstance(t.get("value"), (int, float)) else None
    if mini:
        w, h = 96, 26
    padl, padr, padt, padb = (4, 6, 4, 4) if mini else (8, 10, 10, 24)
    xs = [ordinal(p["date"]) for p in pts]
    if t and t.get("by"):
        xs_all = xs + [ordinal(t["by"])]
    else:
        xs_all = xs
    if not xs_all:
        return ""
    x0, x1 = min(xs_all), max(xs_all)
    if x0 == x1:
        x0, x1 = x0 - 7, x1 + 7
    vals = [p["value"] for p in pts] + ([tval] if tval is not None else []) + ([m["of"]] if m.get("of") else [])
    ymax = max(vals) * 1.12 if vals and max(vals) > 0 else 1
    X = lambda o: padl + (o - x0) / (x1 - x0) * (w - padl - padr)
    Y = lambda v: padt + (1 - v / ymax) * (h - padt - padb)
    out = [f'<svg class="v-spark{" mini" if mini else ""}" viewBox="0 0 {w} {h}" role="img" aria-label="{esc(M.label(m))}: history and target">']
    out.append(f'<line class="v-base" x1="{padl}" x2="{w - padr}" y1="{Y(0):.1f}" y2="{Y(0):.1f}"/>')
    # same-day points step right a little so a drop on one day still reads as a drop
    seen, coords = {}, []
    for p in pts:
        k = seen.get(p["date"], 0)
        seen[p["date"]] = k + 1
        coords.append((X(ordinal(p["date"])) + k * (3 if mini else 6), Y(p["value"]), p))
    if t and tval is not None and t.get("by"):
        tx, ty = X(ordinal(t["by"])), Y(tval)
        if coords:
            lx, ly, _ = coords[-1]
            out.append(f'<path class="v-proj" d="M{lx:.1f} {ly:.1f} L{tx:.1f} {ty:.1f}"/>')
        out.append(f'<circle class="v-tring" cx="{tx:.1f}" cy="{ty:.1f}" r="{3.5 if mini else 5}"/>')
        if not mini:
            out.append(f'<circle class="v-hit" cx="{tx:.1f}" cy="{ty:.1f}" r="11" data-tip="{esc("Target: " + M.target_words(book, m))}"/>')
    if len(coords) > 1:
        d = " ".join(f"{'M' if i == 0 else 'L'}{x:.1f} {y:.1f}" for i, (x, y, _) in enumerate(coords))
        out.append(f'<path class="v-line" d="{d}"/>')
    for i, (x, y, p) in enumerate(coords):
        last = i == len(coords) - 1
        r = (3 if last else 2) if mini else 4
        out.append(f'<circle class="v-dot{" last" if last else ""}" cx="{x:.1f}" cy="{y:.1f}" r="{r}"/>')
        if not mini:
            out.append(f'<circle class="v-hit" cx="{x:.1f}" cy="{y:.1f}" r="11" data-tip="{esc(tip_for(book, m, p))}"/>')
    if not mini:
        if pts:
            out.append(f'<text class="v-axis-t" x="{padl}" y="{h - 4}">{esc(M.day(pts[0]["date"]))}</text>')
        if t and t.get("by"):
            out.append(f'<text class="v-axis-t" x="{w - padr}" y="{h - 4}" text-anchor="end">{esc(M.day(t["by"]))}</text>')
    out.append("</svg>")
    return "".join(out)


# ---- state chip ---------------------------------------------------------------------------------
ICON = {"met": "✓", "open": "→", "unmeasured": "○", "context": "·", "info": "·"}


def state_chip(book, m):
    st, prog = book.state(m)
    return f'<span class="v-state v-st-{st}"><span aria-hidden="true">{ICON[st]}</span> {esc(M.state_words(book, m))}</span>'


def delta_text(book, m):
    pts = book.points(m)
    if not pts:
        return "Not measured yet"
    if len(pts) == 1:
        return f"Measured {M.day(pts[0]['date'])}" + (f" · n {pts[0]['n']}" if pts[0].get("n") else "")
    a, b = pts[0]["value"], pts[-1]["value"]
    if a == b:
        return f"Same as {M.day(pts[0]['date'])} · {len(pts)} points"
    good = (b < a) if m["better"] == "lower" else (b > a)
    arrow = "↓" if b < a else "↑"
    cls = "v-good" if good else "v-bad"
    return f'<span class="{cls}">{arrow} from {esc(fmt(book, m, a))}</span> on {M.day(pts[0]["date"])} · {len(pts)} points'


# ---- card ---------------------------------------------------------------------------------------
def card(book, m, anchor=True, wide=False):
    pts, t = book.points(m), book.target(m)
    st, prog = book.state(m)
    now = pts[-1]["value"] if pts else None
    by = t.get("by") if t else ""
    attrs = f' id="m-{esc(m["id"])}"' if anchor else ""
    of = f' <span class="v-of">of {m["of"]}</span>' if m.get("of") else ""
    cond = f'<span class="v-cond">{esc(m["condition"])}</span>' if m.get("condition") else ""
    big = (f'<b>{esc(num_text(book, m, now))}</b> <span class="v-unit">{esc(unit_text(book, m, now))}</span>{of}'
           if now is not None else '<b class="v-none">—</b>')
    goal = (f'<span class="v-tgt">Target {esc(M.target_words(book, m))}</span>' if t else
            ('<span class="v-tgt">Other people\'s number, for comparison</span>' if m.get("context") else ""))
    meter = f'<div class="v-meter" aria-hidden="true"><i style="width:{prog * 100:.0f}%"></i></div>' if st == "open" and prog is not None and len(pts) > 1 else ""
    src = sorted({p.get("source", "") for p in pts if p.get("source")})
    foot_bits = []
    if m.get("machine"):
        foot_bits.append(m["machine"])
    if m.get("computed"):
        foot_bits.append("from " + ("coverage/roadmap.json" if m["computed"].startswith("roadmap") else "coverage/ledger.json"))
    elif src:
        foot_bits.append(src[-1] if len(src) == 1 else f"{len(src)} sources")
    explain = f'<p class="v-explain">{esc(m["explain"])}</p>' if m.get("explain") and wide else ""
    return (f'<article class="v-card{" wide" if wide else ""}"{attrs} data-state="{st}" data-by="{esc(by)}">'
            f'<div class="v-top"><span class="v-area">{esc(book.area_name(m))}</span>{cond}</div>'
            f'<h4 class="v-name">{esc(m["name"])}</h4>'
            f'<div class="v-now">{big}</div>'
            f'<div class="v-delta">{delta_text(book, m)}</div>'
            f'{spark(book, m)}'
            f'<div class="v-goal">{state_chip(book, m)}{goal}</div>{meter}{explain}'
            f'<div class="v-foot">{esc(" · ".join(foot_bits))}</div></article>')


def cards(book, ms, anchor=True):
    return '<div class="v-cards">' + "".join(card(book, m, anchor) for m in ms) + "</div>"


# ---- compact row --------------------------------------------------------------------------------
def rows(book, ms):
    """The same measures as cards, one line each: for lists under a roadmap step."""
    out = ['<div class="v-rows">']
    for m in ms:
        pts, t = book.points(m), book.target(m)
        now = fmt(book, m, pts[-1]["value"]) if pts else "not measured"
        st, _ = book.state(m)
        out.append(f'<a class="v-row" href="#m-{esc(m["id"])}" data-state="{st}" data-by="{esc(t.get("by", "") if t else "")}">'
                   f'<span class="v-rname">{esc(M.label(m))}</span>{spark(book, m, mini=True)}'
                   f'<span class="v-rnow">{esc(now)}</span>'
                   f'<span class="v-rtgt">{esc("→ " + M.target_words(book, m)) if t else ""}</span>{state_chip(book, m)}</a>')
    out.append("</div>")
    return "".join(out)


# ---- step status --------------------------------------------------------------------------------
def step_chip(status):
    return f'<span class="v-sstat v-s-{status}"><i aria-hidden="true"></i>{STATUS_WORD[status]}</span>'


def step_dates(s):
    if s.get("done"):
        return f"Done {M.day(s['done'])}" if s["start"] == s["done"] else f"{M.day(s['start'])} → done {M.day(s['done'])}"
    return f"{'Started' if s['status'] == 'doing' else 'Starts'} {M.day(s['start'])} · target {M.day(s['target'])}"


def segbar(counts, total):
    """Thin segmented bar: one whole (the goal), split by step status. No text inside; the legend line names it."""
    parts = "".join(f'<i class="v-s-{k}" style="flex:{counts[k]}"></i>' for k in M.STATUSES if counts.get(k))
    words = " · ".join(f"{counts[k]} {k}" for k in M.STATUSES if counts.get(k))
    return f'<div class="v-seg" role="img" aria-label="{esc(words)}">{parts}</div><div class="v-seglegend">{esc(words)} of {total}</div>'


# ---- timeline -----------------------------------------------------------------------------------
def span(book):
    starts = [s["start"] for s in book.steps.values()]
    ends = [s.get("done") or s.get("target") for s in book.steps.values()]
    return ordinal(min(starts)) - 3, ordinal(max(ends)) + 10


def months(x0, x1):
    d = date.fromordinal(x0).replace(day=1)
    out = []
    while d.toordinal() <= x1:
        if d.toordinal() >= x0:
            out.append(d)
        d = date(d.year + (d.month == 12), d.month % 12 + 1, 1)
    return out


def pct(o, x0, x1):
    return (o - x0) / (x1 - x0) * 100


def gantt(book):
    x0, x1 = span(book)
    ticks = "".join(f'<span style="left:{pct(d.toordinal(), x0, x1):.2f}%">{M.MONTHS[d.month - 1]}{"" if d.month != 1 else "<em> " + str(d.year) + "</em>"}</span>'
                    for d in months(x0, x1))
    out = [f'<div class="v-gantt" data-x0="{date.fromordinal(x0).isoformat()}" data-x1="{date.fromordinal(x1).isoformat()}">',
           f'<div class="v-grow v-gaxis"><div class="v-glab"></div><div class="v-gtrack">{ticks}<b class="v-today-l">Today</b></div></div>']
    for p in book.roadmap["goals"]:
        ss = p["steps"]
        done = sum(s["status"] == "done" for s in ss)
        out.append(f'<a class="v-grow v-gphase" href="#goal-{esc(p["id"])}"><div class="v-glab"><b>Goal {esc(p["id"])}</b> {esc(p["title"])}</div>'
                   f'<div class="v-gtrack"><span class="v-gcount">{done} of {len(ss)} done</span></div></a>')
        for s in ss:
            end = s.get("done") or s["target"]
            a, b = ordinal(s["start"]), ordinal(end)
            left, width = pct(a, x0, x1), max(pct(b + 1, x0, x1) - pct(a, x0, x1), 0.8)
            tip = f"Step {s['id']} · {STATUS_WORD[s['status']]} · {step_dates(s)} · {s['title']}"
            out.append(f'<a class="v-grow v-gstep" href="#step-{esc(s["id"])}" data-status="{s["status"]}" data-target="{esc(s.get("target", ""))}" data-tip="{esc(tip)}">'
                       f'<div class="v-glab"><span class="v-sid">{esc(s["id"])}</span>{esc(s["title"])}</div>'
                       f'<div class="v-gtrack"><i class="v-gbar v-s-{s["status"]}" style="left:{left:.2f}%;width:{width:.2f}%"></i></div></a>')
    out.append("</div>")
    return "".join(out)


# ---- burn-up ------------------------------------------------------------------------------------
def burnup(book, w=1080, h=300):
    """Steps done against steps planned, over time. Planned counts each step on its target (or done) date."""
    x0, x1 = span(book)
    steps = list(book.steps.values())
    total = len(steps)
    planned = sorted(s.get("target") or s["done"] for s in steps)
    done = sorted(s["done"] for s in steps if s.get("done"))
    pl, pr, pt, pb = 34, 118, 14, 26
    X = lambda o: pl + (o - x0) / (x1 - x0) * (w - pl - pr)
    Y = lambda v: pt + (1 - v / total) * (h - pt - pb)

    def stairs(dates):
        pts, n = [], 0
        for d in sorted(set(dates)):
            n = sum(1 for x in dates if x <= d)
            pts.append((ordinal(d), n))
        return pts

    def path(pts, start):
        d, prev = f"M{X(start):.1f} {Y(0):.1f}", 0
        for o, n in pts:
            d += f" H{X(o):.1f} V{Y(n):.1f}"
            prev = n
        return d, prev

    pp, dp = stairs(planned), stairs(done)
    pd, _ = path(pp, x0)
    dd, nd = path(dp, x0)
    grid = ""
    for v in range(0, total + 1, 10):
        grid += f'<line class="v-grid" x1="{pl}" x2="{w - pr}" y1="{Y(v):.1f}" y2="{Y(v):.1f}"/><text class="v-axis-t" x="{pl - 6}" y="{Y(v) + 4:.1f}" text-anchor="end">{v}</text>'
    for d in months(x0, x1):
        grid += f'<text class="v-axis-t" x="{X(d.toordinal()):.1f}" y="{h - 6}" text-anchor="middle">{M.MONTHS[d.month - 1]}</text>'
    hits = "".join(f'<circle class="v-dot" cx="{X(o):.1f}" cy="{Y(n):.1f}" r="4"/><circle class="v-hit" cx="{X(o):.1f}" cy="{Y(n):.1f}" r="11" data-tip="{esc(f"{M.day(date.fromordinal(o).isoformat())}: {n} of {total} steps done")}"/>' for o, n in dp)
    phits = "".join(f'<circle class="v-hit" cx="{X(o):.1f}" cy="{Y(n):.1f}" r="9" data-tip="{esc(f"Planned by {M.day(date.fromordinal(o).isoformat())}: {n} of {total} steps")}"/>' for o, n in pp)
    lo, ln = dp[-1] if dp else (x0, 0)
    po, pn = pp[-1]
    labels = (f'<text class="v-dlabel" x="{X(lo) - 8:.1f}" y="{Y(ln) - 8:.1f}" text-anchor="end">{ln} done</text>'
              f'<text class="v-dlabel muted" x="{X(po) + 8:.1f}" y="{Y(pn) + 4:.1f}">{pn} planned by {esc(M.day(date.fromordinal(po).isoformat()))}</text>')
    return (f'<figure class="v-burn"><div class="v-legend"><span><i class="k-done"></i>Steps done</span><span><i class="k-plan"></i>Steps planned (by target date)</span></div>'
            f'<svg viewBox="0 0 {w} {h}" role="img" aria-label="Roadmap: {nd} of {total} steps done; plan reaches {total} by {M.day(planned[-1])}" '
            f'data-x0="{date.fromordinal(x0).isoformat()}" data-x1="{date.fromordinal(x1).isoformat()}" data-pl="{pl}" data-pr="{pr}" data-w="{w}">'
            f'{grid}<line class="v-base" x1="{pl}" x2="{w - pr}" y1="{Y(0):.1f}" y2="{Y(0):.1f}"/>'
            f'<path class="v-plan" d="{pd}"/><path class="v-line" d="{dd}"/>{phits}{hits}{labels}'
            f'<line class="v-todayline" x1="0" x2="0" y1="{pt}" y2="{h - pb}"/></svg></figure>')


# ---- columns ------------------------------------------------------------------------------------
def columns(items, unit, legend, w=1080, h=220, every=4):
    """Magnitude per category, one hue: items are dicts with label, value, tip and an optional
    'on' flag that draws the column in the accent (the rest stay muted), so one fact stands out.
    Columns grow from one baseline with a 4px rounded top; every few labels are printed, the
    tooltip carries all of them."""
    n = len(items)
    pl, pr, pt, pb = 40, 8, 12, 44
    slot = (w - pl - pr) / n
    bw = min(24, slot * 0.62)
    top = max(i["value"] for i in items) or 1
    step = next(s for s in (1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000) if top / s <= 5)
    ymax = step * (int(top / step) + 1)
    Y = lambda v: pt + (1 - v / ymax) * (h - pt - pb)
    out = [f'<figure class="v-cols"><div class="v-legend">{legend}</div><svg viewBox="0 0 {w} {h}" role="img" aria-label="{esc(unit)} per category; the table below has every value">']
    for v in range(0, ymax + 1, step):
        out.append(f'<line class="v-grid" x1="{pl}" x2="{w - pr}" y1="{Y(v):.1f}" y2="{Y(v):.1f}"/><text class="v-axis-t" x="{pl - 6}" y="{Y(v) + 4:.1f}" text-anchor="end">{v:,}</text>')
    for k, it in enumerate(items):
        x = pl + slot * k + (slot - bw) / 2
        y, base = Y(it["value"]), Y(0)
        hgt = base - y
        if hgt > 0:
            r = min(4, hgt, bw / 2)
            d = f"M{x:.1f} {base:.1f} V{y + r:.1f} Q{x:.1f} {y:.1f} {x + r:.1f} {y:.1f} H{x + bw - r:.1f} Q{x + bw:.1f} {y:.1f} {x + bw:.1f} {y + r:.1f} V{base:.1f} Z"
            out.append(f'<path class="v-col{" on" if it.get("on") else ""}" d="{d}"/>')
        out.append(f'<rect class="v-hit" x="{pl + slot * k:.1f}" y="{pt}" width="{slot:.1f}" height="{h - pt - pb + 4}" data-tip="{esc(it["tip"])}"/>')
        if k % every == 0 or k == n - 1:
            cx = pl + slot * k + slot / 2
            out.append(f'<text class="v-axis-t" x="{cx:.1f}" y="{h - pb + 16}" text-anchor="middle">{esc(it["label"])}</text>')
    out.append(f'<line class="v-base" x1="{pl}" x2="{w - pr}" y1="{Y(0):.1f}" y2="{Y(0):.1f}"/></svg></figure>')
    return "".join(out)


VIZ_CSS = r"""
/* ---- measures (bench/viz.py) ---- */
.v-cards { display:grid; grid-template-columns:repeat(auto-fill, minmax(260px, 1fr)); gap:14px; margin-top:14px }
.v-card { background:var(--surface); border:1px solid var(--ring); border-radius:8px; padding:14px 16px 12px; display:flex; flex-direction:column; gap:4px; min-width:0; scroll-margin-top:72px }
.v-card:target { box-shadow:0 0 0 2px var(--accent) }
.v-top { display:flex; gap:8px; align-items:center; font-size:12.5px; color:var(--ink3) }
.v-area { text-transform:uppercase; letter-spacing:.05em; font-weight:600 }
.v-cond { border:1px solid var(--rule); border-radius:999px; padding:0 7px; color:var(--ink2) }
.v-name { margin:2px 0 0; font-size:15px; font-weight:550; line-height:1.3; color:var(--ink) }
.v-now { margin-top:4px; color:var(--ink) }
.v-now b { font-family:var(--f-head); font-size:34px; font-weight:700; letter-spacing:-.02em; line-height:1.05 }
.v-now .v-unit { font-size:15px; color:var(--ink2) }
.v-now .v-of { font-size:14px; color:var(--ink3) }
.v-now .v-none { color:var(--ink3) }
.v-delta { font-size:13px; color:var(--ink3) }
.v-good { color:var(--good-ink); font-weight:550 } .v-bad { color:var(--bad-ink); font-weight:550 }
.v-spark { width:100%; height:auto; display:block; margin:6px 0 2px; overflow:visible }
.v-spark.mini { width:96px; flex:none; margin:0 }
.v-base { stroke:var(--axis); stroke-width:1 }
.v-grid { stroke:var(--rule); stroke-width:1 }
.v-line { fill:none; stroke:var(--accent); stroke-width:2; stroke-linejoin:round; stroke-linecap:round }
.v-plan { fill:none; stroke:var(--ink3); stroke-width:2; stroke-linejoin:round }
.v-proj { fill:none; stroke:var(--ink3); stroke-width:1.5; stroke-dasharray:4 4 }
.v-dot { fill:var(--accent); stroke:var(--surface); stroke-width:2 }
.v-dot.last { fill:var(--accent) }
.v-tring { fill:var(--surface); stroke:var(--ink2); stroke-width:2 }
.late .v-tring { stroke:var(--red) }
.v-hit { fill:transparent; cursor:default }
.v-axis-t { font-size:10.5px; fill:var(--ink3); font-variant-numeric:tabular-nums }
.v-goal { display:flex; flex-wrap:wrap; gap:4px 10px; align-items:baseline; font-size:13px; color:var(--ink2) }
.v-state { font-weight:600; white-space:nowrap }
.v-st-met { color:var(--good-ink) } .v-st-open { color:var(--accent-ink) } .v-st-unmeasured, .v-st-context, .v-st-info { color:var(--ink3) }
.late .v-state, .v-row.late .v-state { color:var(--bad-ink) }
.v-meter { height:6px; border-radius:3px; background:var(--accent-wash); overflow:hidden; margin-top:4px }
.v-meter i { display:block; height:100%; background:var(--accent); border-radius:3px }
.v-explain { font-size:13px; margin:4px 0 0 }
.v-foot { font-size:12px; color:var(--ink3); margin-top:auto; padding-top:6px }
.v-rows { display:flex; flex-direction:column; margin:8px 0 2px; border-top:1px solid var(--rule) }
.v-row { display:grid; grid-template-columns:minmax(0,1fr) 96px 92px minmax(0,auto) auto; gap:4px 12px; align-items:center; padding:7px 0; border-bottom:1px solid var(--rule); color:var(--ink); text-decoration:none; font-size:14px }
.v-row:hover .v-rname { text-decoration:underline }
.v-rnow { font-weight:600; text-align:right; font-variant-numeric:tabular-nums; white-space:nowrap }
.v-rtgt { color:var(--ink3); font-size:13px }
/* steps */
.v-sstat { display:inline-flex; align-items:center; gap:6px; font-size:13px; font-weight:600; color:var(--ink2); white-space:nowrap }
.v-sstat i { width:10px; height:10px; border-radius:50%; display:inline-block }
.v-s-done { background:var(--green) } .v-s-doing { background:var(--accent) } .v-s-next { background:transparent; box-shadow:inset 0 0 0 2px var(--ink3) } .v-s-later { background:var(--grey) }
.v-sstat.v-s-done, .v-sstat.v-s-doing, .v-sstat.v-s-next, .v-sstat.v-s-later { background:none; box-shadow:none }
.v-sstat.v-s-done i { background:var(--green) } .v-sstat.v-s-doing i { background:var(--accent) } .v-sstat.v-s-next i { box-shadow:inset 0 0 0 2px var(--ink3) } .v-sstat.v-s-later i { background:var(--grey) }
.v-latechip { display:none; font-size:12px; font-weight:700; color:#fff; background:var(--red); border-radius:4px; padding:0 6px }
.late > .v-sl .v-latechip, .late .v-latechip { display:inline-block }
.v-seg { display:flex; gap:2px; height:8px; margin:12px 0 4px; max-width:520px }
.v-seg i { display:block; border-radius:4px; min-width:4px }
.v-seglegend { font-size:13px; color:var(--ink3) }
.v-steps { list-style:none; padding:0; margin:8px 0 0; border-bottom:1px solid var(--rule) }
.v-step { border-top:1px solid var(--rule); scroll-margin-top:76px }
.v-step:target { background:var(--accent-wash) }
.v-sl { display:grid; grid-template-columns:38px minmax(0,1fr) auto 72px; gap:4px 14px; align-items:baseline; padding:10px 2px; cursor:pointer; list-style:none }
.v-sl::-webkit-details-marker { display:none }
.v-sl:hover b { text-decoration:underline }
.v-sid { font-family:var(--f-mono); font-size:13px; font-weight:500; color:var(--ink3); font-variant-numeric:tabular-nums }
.v-sl b { font-weight:500; font-size:15.5px }
.v-step[data-status="doing"] .v-sl b, .v-step[data-status="next"] .v-sl b { font-weight:600 }
.v-step[data-status="done"] .v-sl b { color:var(--ink2) }
.v-dates { font-size:13px; color:var(--ink3); font-variant-numeric:tabular-nums; text-align:right; white-space:nowrap }
.v-sl .v-latechip { grid-column:3; justify-self:end } .late .v-sl .v-dates { display:none }
.v-sbody { padding:0 2px 16px 54px }
.v-sbody p, .v-sbody li { font-size:14.5px } .v-sbody ul { margin:6px 0; padding-left:18px; color:var(--ink2) }
.v-sbody .v-rows { margin-top:12px }
.v-phead { display:flex; flex-wrap:wrap; gap:4px 16px; align-items:baseline; justify-content:space-between }
.v-phead h2 { margin-bottom:0 }
.v-phead span { font-size:14px; color:var(--ink3) }
/* timeline */
.v-gantt { margin-top:14px; position:relative; --today:-10%; --lab:300px }
.v-grow { display:grid; grid-template-columns:var(--lab) minmax(0,1fr); align-items:center; text-decoration:none; color:var(--ink) }
.v-glab { font-size:13px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; padding-right:12px }
.v-gtrack { position:relative; height:22px; border-left:1px solid var(--axis) }
.v-gtrack::after { content:""; position:absolute; top:0; bottom:0; left:var(--today); width:0; border-left:1.5px solid var(--ink) }
.v-gaxis .v-gtrack { height:40px; border-left:0; border-bottom:1px solid var(--axis) }
.v-gaxis em { font-style:normal }
.v-gaxis .v-gtrack span { position:absolute; bottom:4px; font-size:11.5px; color:var(--ink3); transform:translateX(2px) }
.v-gaxis .v-gtrack span::before { content:""; position:absolute; left:-2px; bottom:-4px; height:6px; border-left:1px solid var(--axis) }
.v-today-l { position:absolute; top:0; line-height:16px; left:var(--today); transform:translateX(-50%); font-size:11px; font-weight:700; background:var(--bg); padding:0 3px }
.v-gphase { margin-top:10px }
.v-gphase .v-glab { font-size:13.5px; color:var(--ink2) } .v-gphase .v-glab b { color:var(--ink) }
.v-gphase .v-gtrack { height:24px }
.v-gcount { position:absolute; left:6px; top:3px; font-size:12px; color:var(--ink3) }
.v-gstep:hover .v-glab { text-decoration:underline }
.v-gstep .v-sid { display:inline-block; min-width:30px; font-size:11.5px }
.v-gbar { position:absolute; top:5px; height:12px; border-radius:4px; min-width:5px }
.v-gbar.v-s-doing { background:var(--accent-wash); box-shadow:inset 0 0 0 1.5px var(--accent) }
.v-gbar.v-s-next { background:transparent; box-shadow:inset 0 0 0 1.5px var(--ink3) }
.v-gbar.v-s-later { background:var(--grey); opacity:.7 }
.v-gstep.late .v-gbar { box-shadow:inset 0 0 0 2px var(--red) }
.v-gstep.late .v-glab { color:var(--bad-ink) }
.v-glegend { display:flex; flex-wrap:wrap; gap:6px 16px; font-size:13px; color:var(--ink2); margin-top:8px }
.v-glegend i { display:inline-block; width:22px; height:10px; border-radius:3px; margin-right:6px; vertical-align:-1px }
/* burn-up */
.v-burn { margin:0; background:var(--surface); border:1px solid var(--ring); border-radius:8px; padding:14px 16px }
.v-burn svg { width:100%; height:auto; display:block; overflow:visible }
.v-legend { display:flex; flex-wrap:wrap; gap:4px 18px; font-size:13px; color:var(--ink2); margin-bottom:6px }
.v-legend i { display:inline-block; width:18px; height:0; border-top:2px solid; margin-right:6px; vertical-align:4px }
.k-done { border-color:var(--accent) !important } .k-plan { border-color:var(--ink3) !important }
.v-dlabel { font-size:12.5px; fill:var(--ink); font-weight:600 } .v-dlabel.muted { fill:var(--ink2); font-weight:500 }
.v-todayline { stroke:var(--ink); stroke-width:1.5; visibility:hidden }
.v-cols { margin:12px 0 0; background:var(--surface); border:1px solid var(--ring); border-radius:8px; padding:14px 16px }
.v-cols svg { width:100%; height:auto; display:block; overflow:visible }
.v-col { fill:var(--ink3); opacity:.55 } .v-col.on { fill:var(--accent); opacity:1 }
.v-legend i.k-col { width:12px; height:12px; border:0; border-radius:3px; vertical-align:-1px; background:var(--accent) }
.v-legend i.k-col.off { background:var(--ink3); opacity:.55 }
.v-tablefold { margin-top:8px } .v-tablefold summary { cursor:pointer; font-size:14px; color:var(--ink2) }
.v-score { margin-top:14px }
.v-score .v-cards { grid-template-columns:repeat(auto-fill, minmax(240px, 1fr)) }
.v-kpis { display:flex; flex-wrap:wrap; gap:8px 28px; margin:14px 0 4px }
.v-kpis div { font-size:14px; color:var(--ink2) }
.v-kpis b { display:block; font-family:var(--f-head); font-size:28px; font-weight:700; letter-spacing:-.02em; color:var(--ink) }
#v-tip { position:fixed; z-index:50; pointer-events:none; max-width:320px; background:var(--ink); color:var(--bg); font-size:13px; line-height:1.35; padding:6px 9px; border-radius:6px; opacity:0; transition:opacity .08s }
#v-tip.on { opacity:1 }
@media (max-width: 720px) {
  .v-gantt { --lab:130px }
  .v-gstep .v-glab { font-size:12px }
  .v-gphase .v-glab b { display:block }
  .v-gcount, .v-gaxis em { display:none }
  .v-row { grid-template-columns:minmax(0,1fr) auto; }
  .v-row .v-spark.mini, .v-row .v-rtgt { display:none }
  .v-burn, .v-cols { overflow-x:auto }
  .v-burn svg, .v-cols svg { min-width:640px }
  .v-sl { grid-template-columns:30px minmax(0,1fr) auto } .v-sl .v-dates { display:none } .v-sbody { padding-left:2px }
}
"""

VIZ_JS = r"""
(function () {
  // today, in the viewer's own calendar: the page itself never changes with the date
  const now = new Date(), today = now.getFullYear() + "-" + String(now.getMonth() + 1).padStart(2, "0") + "-" + String(now.getDate()).padStart(2, "0");
  const days = s => Date.UTC(+s.slice(0, 4), +s.slice(5, 7) - 1, +s.slice(8, 10)) / 864e5;
  document.querySelectorAll(".v-gantt").forEach(g => {
    const a = days(g.dataset.x0), b = days(g.dataset.x1), t = days(today);
    if (t >= a && t <= b) g.style.setProperty("--today", ((t - a) / (b - a) * 100).toFixed(2) + "%");
    else g.querySelector(".v-today-l").hidden = true;
  });
  document.querySelectorAll(".v-burn svg").forEach(s => {
    const a = days(s.dataset.x0), b = days(s.dataset.x1), t = days(today), pl = +s.dataset.pl, pr = +s.dataset.pr, w = +s.dataset.w;
    if (t < a || t > b) return;
    const x = pl + (t - a) / (b - a) * (w - pl - pr), l = s.querySelector(".v-todayline");
    l.setAttribute("x1", x); l.setAttribute("x2", x); l.style.visibility = "visible";
  });
  // a target in the past that is not met, or a step past its target that is not done, is late
  document.querySelectorAll(".v-card[data-by], .v-row[data-by]").forEach(c => {
    if (c.dataset.by && c.dataset.by < today && (c.dataset.state === "open" || c.dataset.state === "unmeasured")) {
      c.classList.add("late");
      const s = c.querySelector(".v-state");
      if (s) s.innerHTML = '<span aria-hidden="true">!</span> Late: target was ' + new Date(c.dataset.by + "T12:00").toLocaleDateString("en-US", { month: "short", day: "numeric" });
    }
  });
  document.querySelectorAll("[data-status][data-target]").forEach(r => {
    if (r.dataset.status !== "done" && r.dataset.target && r.dataset.target < today) r.classList.add("late");
  });
  document.querySelectorAll(".v-phase").forEach(p => {
    const n = p.querySelectorAll(".v-step.late").length, el = p.querySelector(".v-plate");
    if (n && el) { el.textContent = n + " late"; el.hidden = false; }
  });
  // one tooltip for every [data-tip]
  const tip = document.createElement("div"); tip.id = "v-tip"; tip.setAttribute("role", "tooltip"); document.body.appendChild(tip);
  const show = (el, x, y) => { tip.textContent = el.dataset.tip; tip.classList.add("on");
    const r = tip.getBoundingClientRect(); tip.style.left = Math.max(8, Math.min(innerWidth - r.width - 8, x + 12)) + "px"; tip.style.top = Math.max(8, y - r.height - 10) + "px"; };
  document.addEventListener("pointerover", e => { const el = e.target.closest("[data-tip]"); if (el) show(el, e.clientX, e.clientY); });
  document.addEventListener("pointermove", e => { const el = e.target.closest("[data-tip]"); if (el) show(el, e.clientX, e.clientY); else tip.classList.remove("on"); });
  document.addEventListener("pointerout", e => { if (!e.relatedTarget || !e.relatedTarget.closest || !e.relatedTarget.closest("[data-tip]")) tip.classList.remove("on"); });
  document.addEventListener("focusin", e => { const el = e.target.closest("[data-tip]"); if (el) { const r = el.getBoundingClientRect(); show(el, r.left, r.top); } });
  document.addEventListener("focusout", () => tip.classList.remove("on"));
})();
"""
