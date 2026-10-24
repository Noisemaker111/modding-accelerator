"""Render docs/progress.html from coverage/ledger.json.

The ledger is the source of truth: one entry per port that was actually run, with the status it
reached. This script only draws it; it never invents a hop.
"""
import json
from html import escape
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LEDGER = json.loads((ROOT / "coverage" / "ledger.json").read_text(encoding="utf-8"))
OUT = ROOT / "docs" / "progress.html"

COLOUR = {"behaviour": "#1f7a3a", "load": "#2a78d6", "build": "#8a6a00", "stop": "#b03a2e", "unbuilt": "#8a8983"}
LABEL = {"behaviour": "behaviour checked", "load": "loads in the real game", "build": "builds + mixin audit", "stop": "named stop", "unbuilt": "not built"}

versions = LEDGER["versions"]
loaders = LEDGER["loaders"]
hops = LEDGER["hops"]
pairs = LEDGER["loader_pairs"]

counts = {k: sum(1 for h in hops if h["status"] == k) for k in ("behaviour", "load", "build", "stop")}

# ---- version map -----------------------------------------------------------
W, LABEL_W = 1100, 70
step = (W - 2 * LABEL_W) / (len(versions) - 1)
x = {v: LABEL_W + i * step for i, v in enumerate(versions)}

def arc(a, b, height):
    x1, x2 = x[a], x[b]
    mid = (x1 + x2) / 2
    y0 = 170
    return f"M {x1:.1f} {y0} Q {mid:.1f} {y0 - height:.1f} {x2:.1f} {y0}"

rows = []
used = {}
for hop in hops:
    key = (hop["from"], hop["to"])
    lane = used.get(key, 0)
    used[key] = lane + 1
    h = 54 + lane * 22
    colour = COLOUR[hop["status"]]
    d = arc(hop["from"], hop["to"], h)
    midx = (x[hop["from"]] + x[hop["to"]]) / 2
    midy = 170 - h / 2 - 4
    rows.append(f'<path d="{d}" fill="none" stroke="{colour}" stroke-width="3" opacity="0.9"/>')
    rows.append(f'<path d="{d}" fill="none" stroke="{colour}" stroke-width="10" opacity="0.12"/>')
    rows.append(f'<text x="{midx:.1f}" y="{midy:.1f}" font-size="11.5" fill="{colour}" text-anchor="middle" font-weight="600">{escape(hop["mod"])} · {hop["loader"]}</text>')

for i, v in enumerate(versions):
    xx = x[v]
    rows.append(f'<line x1="{xx:.1f}" y1="164" x2="{xx:.1f}" y2="176" stroke="#b9b7b0" stroke-width="2"/>')
    rows.append(f'<circle cx="{xx:.1f}" cy="170" r="4.5" fill="#0b0b0b"/>')
    rows.append(f'<text x="{xx:.1f}" y="196" font-size="12" fill="#52514e" text-anchor="middle">{escape(v)}</text>')

svg = f'<svg viewBox="0 0 {W} 212" width="100%" role="img" aria-label="Minecraft versions connected by ports that were actually run">' + "".join(rows) + "</svg>"

# ---- tables ----------------------------------------------------------------
def hop_rows():
    out = []
    for h in sorted(hops, key=lambda h: (h["from"], h["to"], h["mod"])):
        c = COLOUR[h["status"]]
        out.append(
            f"<tr><td><b>{escape(h['mod'])}</b><div class='small'>{h['loader']}</div></td>"
            f"<td class='mono'>{h['from']} → {h['to']}</td>"
            f"<td><span class='dot' style='background:{c}'></span>{LABEL[h['status']]}</td>"
            f"<td>{escape(h['detail'])}</td><td class='small n'>{h['date']}</td></tr>")
    return "".join(out)

def loader_grid():
    head = "".join(f"<th class='c'>{l}</th>" for l in loaders)
    body = []
    for a in loaders:
        cells = []
        for b in loaders:
            if a == b:
                cells.append("<td class='c no'>·</td>")
                continue
            pair = next((p for p in pairs if p["from"] == a and p["to"] == b), None)
            if pair:
                c = COLOUR[pair["status"]]
                cells.append(f"<td class='c'><span class='dot' style='background:{c}'></span><span class='small'>{LABEL[pair['status']]}</span></td>")
            else:
                cells.append("<td class='c no'>not started</td>")
        body.append(f"<tr><td><b>{a}</b></td>{''.join(cells)}</tr>")
    return f"<tr><th>from \\ to</th>{head}</tr>" + "".join(body)

html = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Porting coverage</title>
<style>
  :root {{ --ink:#0b0b0b; --ink2:#52514e; --ink3:#8a8983; --rule:#ecebe7; --tile:#f6f5f2; }}
  * {{ box-sizing:border-box; margin:0; padding:0 }}
  body {{ background:#fff; color:var(--ink); width:1200px; font-family:"Segoe UI",system-ui,sans-serif; padding:44px 56px 40px }}
  h1 {{ font-size:32px; letter-spacing:-.01em }}
  h2 {{ font-size:20px; margin-top:28px }}
  p {{ color:var(--ink2); font-size:15px; line-height:1.45; margin-top:6px }}
  .small {{ font-size:12px; color:var(--ink3) }}
  .mono {{ font-family:"Cascadia Mono",Consolas,monospace; font-size:13px; white-space:nowrap }}
  .tiles {{ display:grid; grid-template-columns:repeat(4,1fr); gap:12px; margin-top:20px }}
  .tile {{ background:var(--tile); border-radius:10px; padding:14px 16px }}
  .tile b {{ display:block; font-size:28px }}
  .tile span {{ font-size:12.5px; color:var(--ink2) }}
  .legend {{ display:flex; gap:20px; margin-top:10px; font-size:13px; color:var(--ink2) }}
  .dot {{ display:inline-block; width:10px; height:10px; border-radius:50%; margin-right:7px; vertical-align:-1px }}
  table {{ width:100%; border-collapse:collapse; margin-top:12px; font-size:14px }}
  th {{ text-align:left; font-size:12px; color:var(--ink3); font-weight:600; padding:0 8px 8px; border-bottom:1px solid var(--rule) }}
  td {{ padding:9px 8px; border-bottom:1px solid var(--rule); vertical-align:top; line-height:1.4 }}
  td.c, th.c {{ text-align:center }}
  td.n {{ text-align:right }}
  .no {{ color:var(--ink3) }}
  .foot {{ margin-top:26px; padding-top:14px; border-top:1px solid var(--rule); font-size:12.5px; color:var(--ink3) }}
  a {{ color:#2a78d6 }}
</style></head><body>
<h1>Porting coverage</h1>
<p>Every line below is a port that was actually run. Nothing is a plan drawn as if it worked. Goal: any Minecraft version to any other, and any loader to any other.</p>

<div class="tiles">
  <div class="tile"><b>{counts['behaviour']}</b><span>hops with behaviour checked in the game</span></div>
  <div class="tile"><b>{counts['load']}</b><span>hops that load in the real client</span></div>
  <div class="tile"><b>{counts['stop']}</b><span>hops that stop with named errors</span></div>
  <div class="tile"><b>{len(hops)}</b><span>ports run in total</span></div>
</div>

<h2>The version map</h2>
<div class="legend">
  <span><span class="dot" style="background:{COLOUR['behaviour']}"></span>behaviour checked</span>
  <span><span class="dot" style="background:{COLOUR['load']}"></span>loads in the real game</span>
  <span><span class="dot" style="background:{COLOUR['build']}"></span>builds + mixin audit</span>
  <span><span class="dot" style="background:{COLOUR['stop']}"></span>named stop</span>
</div>
{svg}
<p class="small">Dots are versions; a line is a hop that was run. A gap with no line has not been attempted. 1.12.2, 1.16.5 and 1.18.2 are on the axis as targets, not because they work.</p>

<h2>What was run</h2>
<table>
<tr><th>Mod</th><th>Jump</th><th>Reached</th><th>What that means</th><th class="n">Date</th></tr>
{hop_rows()}
</table>

<h2>Loader changes</h2>
<table>
{loader_grid()}
</table>
<p class="small">A loader change keeps the game version and changes the loader. Fabric ↔ Quilt and Forge ↔ NeoForge are the close pairs; Fabric ↔ NeoForge needs metadata, entrypoint, event, registry and mixin translation.</p>

<h2>What counts as green</h2>
<p><b>1. Builds.</b> The project compiles and the mixin audit passes. <b>2. Loads.</b> A real client or server starts with the jar and lists the mod. <b>3. Behaviour.</b> Something observable is asserted in the game — a screen changed, a command ran, a save reloaded. <b>Stop.</b> The port refuses to guess and lists the errors that remain. A hop is never called done at step 1.</p>

<p class="foot">Source of truth: <span class="mono">coverage/ledger.json</span>, rendered by <span class="mono">bench/progress.py</span>. Receipts live in <span class="mono">.runs/</span>. The plan is in <a href="https://github.com/Noisemaker111/modding-accelerator/blob/main/ROADMAP.md">ROADMAP.md</a>.</p>
</body></html>"""

OUT.write_text(html, encoding="utf-8")
print("wrote", OUT, len(html))
