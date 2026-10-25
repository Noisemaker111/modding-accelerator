"""Render docs/porting-map.html from the change index.

Reads coverage/changes/index.json (bench/change_routes.py) and every <from>__<to>.json step file
(tools/ChangeIndex.java). The page carries the small index inline; each step's full change list
is written to docs/porting-map/steps/<from>__<to>.js as gzip + base64 and loaded only when a
step is opened, so the page works from a local file and on GitHub Pages without a server.

    python bench/porting_map.py
"""
import base64
import gzip
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from page_style import STYLE, THEME_JS  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
CHANGES = ROOT / "coverage" / "changes"
DOCS = ROOT / "docs"
STEPS = DOCS / "porting-map" / "steps"
DETAIL_JS = (Path(__file__).resolve().parent / "porting_map_detail.js").read_text(encoding="utf-8")


def main():
    index = json.loads((CHANGES / "index.json").read_text(encoding="utf-8"))
    STEPS.mkdir(parents=True, exist_ok=True)
    for old in STEPS.glob("*.js"):
        old.unlink()
    total = 0
    slim_steps = []
    for s in index["steps"]:
        data = json.loads((CHANGES / s["file"]).read_text(encoding="utf-8"))
        data["matched"] = s["coverage"]["matched"]
        raw = json.dumps(data, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        b64 = base64.b64encode(gzip.compress(raw, 9, mtime=0)).decode("ascii")
        key = s["file"][:-5]
        out = STEPS / f"{key}.js"
        out.write_bytes(f'PM.loaded("{key}","{b64}");\n'.encode("ascii"))
        total += out.stat().st_size
        cov = {k: v for k, v in s["coverage"].items() if k != "matched"}
        slim_steps.append({**{k: v for k, v in s.items() if k != "coverage"}, "coverage": cov})
    slim = {k: index[k] for k in ("generated", "allVersions", "blocked", "runs", "doing", "stepStatus")}
    slim["families"] = [{k: f[k] for k in ("id", "versions", "hub") if k in f} | {"estimated": f.get("estimated", False)} for f in index["families"]["list"]]
    slim["steps"] = slim_steps
    payload = json.dumps(slim, separators=(",", ":"), ensure_ascii=False).replace("</", "<\\/")
    html = TEMPLATE.replace("/*STYLE*/", STYLE).replace("/*THEME*/", THEME_JS).replace("/*DETAIL*/", DETAIL_JS).replace("/*INDEX*/null", payload)
    (DOCS / "porting-map.html").write_bytes(html.encode("utf-8"))
    print("wrote", DOCS / "porting-map.html", len(html), "bytes;", len(slim_steps), "step files,", total, "bytes")


TEMPLATE = r"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Porting map</title>
<meta name="description" content="Every Minecraft Java release, the hub-to-hub route between any two, and what has really been ported.">
<style>
/*STYLE*/
.card { border:1px solid var(--rule); border-radius:12px; padding:16px; margin-top:16px }
.pick { display:grid; grid-template-columns:1fr auto 1fr; gap:10px; align-items:end }
.pick label { display:block; font-size:15px; color:var(--ink2); margin-bottom:4px }
select, input[type=search] { width:100%; font:inherit; font-size:18px; padding:9px 10px; border:1px solid var(--rule); border-radius:8px; background:var(--chip); color:var(--ink) }
button.swap { height:46px; width:46px; padding:0; font-size:20px }
.notice { border-left:4px solid var(--red); padding:12px 14px; margin-top:16px; background:var(--head); border-radius:0 8px 8px 0; color:var(--ink) }
.summary { font-size:19px; color:var(--ink); margin-top:6px }
.status { text-align:left !important; font-size:16px }
.status .why { display:block; font-size:13.5px; color:var(--ink3); margin-top:2px }
.size { font-size:14px; font-weight:600; padding:2px 9px; border-radius:999px; color:#fff; white-space:nowrap }
.sz-tiny { background:#2f8f5b } .sz-small { background:#7a9a22 } .sz-medium { background:#c48a10 } .sz-big { background:#c4453a } .sz-none { background:var(--grey) }
tr.cmd td { padding-top:0; border-bottom:1px solid var(--rule); text-align:left }
tr.hop td { border-bottom:0 }
.cmdline { display:flex; gap:8px; align-items:center; flex-wrap:wrap }
.cmdline code { flex:1 1 320px; background:var(--head); border-radius:6px; padding:6px 10px; font-family:"Cascadia Mono",Consolas,ui-monospace,monospace; font-size:14px; word-break:break-all }
.cmdline button { font-size:14px; padding:5px 10px }
.inner { margin:8px 0 4px; border-left:3px solid var(--rule); padding-left:12px }
.inner .srow { display:flex; gap:10px; align-items:center; flex-wrap:wrap; padding:6px 0; border-bottom:1px solid var(--rule); font-size:16px }
.inner .srow:last-child { border-bottom:0 }
.inner .srow b { font-weight:600; min-width:150px }
.inner .srow .n { color:var(--ink2); font-size:14.5px; flex:1 1 260px }
.inner .srow button { font-size:14px; padding:5px 10px }
.plat { display:flex; gap:6px; margin:10px 0 }
.plat button { font-size:14px; padding:4px 10px; border-radius:999px }
.plat button.on, .filters button.on { background:var(--ink); color:var(--bg); border-color:var(--ink) }
pre.all { background:var(--head); border-radius:8px; padding:12px 14px; font-size:14px; overflow-x:auto; white-space:pre-wrap; word-break:break-all; margin:8px 0 0 }
/* every release */
.legend { display:flex; gap:18px; flex-wrap:wrap; font-size:15px; color:var(--ink2); margin-top:8px }
.keys { margin:8px 0 0; padding-left:18px } .keys li { margin:2px 0 }
.ln { display:inline-block; width:26px; height:5px; border-radius:3px; vertical-align:middle; margin-right:7px }
#hopinfo { min-height:2.6em; margin-top:10px; font-size:16px; color:var(--ink) }
.vrow { display:flex; gap:14px; align-items:flex-start; padding:10px 0; border-bottom:1px solid var(--rule) }
.vlabel { flex:none; width:64px; font-weight:700; font-size:18px; padding:4px 0 0; line-height:1.15 }
.vlabel .est { display:block; font-size:12px; font-weight:400; color:var(--ink3) }
.vchips { display:flex; flex-wrap:wrap; align-items:center; row-gap:8px }
.rel { display:inline-flex; align-items:center }
.chip { font:inherit; font-size:18px; font-weight:500; border:0; background:none; padding:3px 5px; border-radius:6px; color:var(--ink) }
.chip.hub, .hubmark { border:2px solid var(--ink); background:var(--chip); font-weight:700 }
.hubmark, .pickmark { border-radius:6px; padding:0 5px; font-weight:600 }
.chip { position:relative }
.chip[data-tag]::before { content:attr(data-tag); position:absolute; left:50%; top:-11px; transform:translateX(-50%); font-size:10.5px; font-weight:700; line-height:1; letter-spacing:.04em; text-transform:uppercase; padding:2px 4px; border-radius:4px; background:var(--hi-edge); color:#fff; white-space:nowrap }
.chip.sel, .pickmark { background:var(--hi); color:var(--hi-ink); border-color:var(--hi-edge) }
.chip.old { color:var(--ink3); font-weight:500 }
.chip.old.hub { border-color:var(--ink3) }
.seg { border:0; padding:10px 0; background:none; width:26px; cursor:pointer }
.seg i { display:block; height:3px; border-radius:2px }
#map { position:relative; padding-left:var(--gut, 0px) }
#jumps { position:absolute; left:0; top:0; overflow:visible }
#jumps path { fill:none; stroke-linejoin:round; stroke-linecap:round }
#jumps .g { stroke:var(--green) } #jumps .y { stroke:var(--yellow) } #jumps .r { stroke:var(--red) }
#jumps .base path { stroke-width:.7; opacity:.09 }
#jumps.route .base path { opacity:.04 }
#jumps .top path { stroke-width:2; opacity:.95; pointer-events:stroke; cursor:pointer }
#jumps .top path.route { stroke-width:5 }
#jumps .top path.ink { stroke:var(--ink2); pointer-events:none }
#jumps circle.stop { fill:var(--bg); stroke:var(--ink); stroke-width:2.5 }
#jumps .tick { stroke:var(--ink3); stroke-width:2 }
#jumplabel { position:absolute; z-index:2; font-size:12.5px; font-weight:600; line-height:1.25; background:var(--bg); border:1px solid var(--rule); border-radius:6px; padding:3px 6px; color:var(--ink); text-align:center }
#jumplabel span { font-weight:400; color:var(--ink3) }
.vlabel i { display:block; font-style:normal; font-size:11.5px; font-weight:500; color:var(--ink2); line-height:16px }

#map.route .seg:not(.in) { opacity:.25 }
#map.route .seg.in i { height:5px }
.ready { font-size:14px; font-weight:600; margin:2px 0 6px }
.ready.ready { color:var(--green) } .ready.warn { color:var(--red) } .ready.blocked { color:var(--ink3); font-weight:500 }
.seg.g i { background:var(--green) } .seg.y i { background:var(--yellow) } .seg.r i { background:var(--red) }
.filters { display:flex; gap:6px; flex-wrap:wrap; margin-top:10px }
.filters button { font-size:14px; padding:5px 10px; border-radius:999px }
.owner { margin-top:12px; border-top:1px solid var(--rule); padding-top:8px }
.owner .cn { font-weight:600; word-break:break-word }
.owner .pk { color:var(--ink3); font-weight:400 }
.item { display:flex; gap:8px; align-items:baseline; padding:2px 0; word-break:break-word }
.tag { flex:none; font-size:12px; font-weight:700; padding:1px 6px; border-radius:4px; background:var(--head); color:var(--ink2); text-transform:lowercase }
.tag.removed { color:var(--red) } .tag.added { color:var(--green) } .tag.renamed { color:var(--link) } .tag.changed { color:#b07a00 }
.st { flex:none; font-size:12px; padding:1px 6px; border-radius:4px; color:#fff }
.st.mappings { background:var(--green) } .st.rule { background:var(--link) } .st.note { background:#b07a00 }
.more { margin-top:12px }
.privbox { display:flex; gap:8px; align-items:center; margin-top:10px; font-size:14px; color:var(--ink2) }
#detail { scroll-margin-top:12px }
@media (max-width: 720px) {
  .pick { grid-template-columns:1fr auto 1fr }
  .vrow { gap:10px }
  .vlabel { width:48px; font-size:15px }
  .chip { font-size:16px }
  .seg { width:18px }
  .item { flex-wrap:wrap; gap:4px 6px; padding:5px 0 }
  .item .mono { flex:1 1 100%; order:3 }
  .tbl.stack tr.hop { border-bottom:0; padding-bottom:0 }
  .tbl.stack td.hopname { font-size:19px; font-weight:600 }
}
</style></head><body>
<main>
<div class="top">
  <h1>Porting map</h1>
  <nav><a href="index.html">Bench</a><a href="https://github.com/Noisemaker111/modding-accelerator">GitHub</a><button class="theme" id="theme" type="button">Dark</button></nav>
</div>
<p class="lede">Pick the version your mod is on and the one you want. Long ports go through the popular versions, and each line's colour says what has really been tried. <a href="https://github.com/Noisemaker111/modding-accelerator/blob/main/docs/details.md#porting-map">How it's measured</a></p>

<div class="card">
  <div class="pick">
    <div><label for="from">My mod is on</label><select id="from"></select></div>
    <button class="swap" id="swap" type="button" title="Swap" aria-label="Swap the two versions">⇄</button>
    <div><label for="to">I want it on</label><select id="to"></select></div>
  </div>
</div>

<div id="route"></div>
<div id="detail"></div>

<h2>Every release</h2>
<div class="legend">
  <span><b class="hubmark">1.20.1</b> boxed: a popular version, good to port through</span>
  <span><b class="pickmark">1.20.1</b> with a <b>from</b> or <b>to</b> tag: your two picks</span>
  <span><span class="ln st-green"></span>a mod was moved across and works in the game</span>
  <span><span class="ln st-yellow"></span>it builds, or we're working on it</span>
  <span><span class="ln st-red"></span>not tried yet, or not possible yet</span>
  <span><span style="color:var(--ink3)">grey versions</span>: before 1.14.4, our numbers are guesses</span>
</div>
<ul class="keys small">
  <li>Lines between versions in a row: one update at a time.</li>
  <li>Lines on the left: go straight from one boxed version to another. Hover a version to see where it can jump to.</li>
  <li><b>Thick line</b> = on your route. Thin line = everything else.</li>
  <li><b>A dot</b> = the route stops at that version. A line with no dots is one jump straight there, which isn't built yet.</li>
</ul>
<div id="hopinfo" class="small"></div>
<div id="map"></div>

<p class="foot">Made by <span class="mono">bench/porting_map.py</span> from <span class="mono">coverage/changes/index.json</span>, <span class="mono">coverage/ledger.json</span> and <span class="mono">ROADMAP.md</span> on <span id="gen"></span>. Before 1.14.4 Mojang published no official names, so those families are grouped by version number and nothing there is measured.</p>
</main>
<script>
const IX = /*INDEX*/null;
const $ = s => document.querySelector(s);
const esc = s => String(s).replace(/[&<>"]/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
const fmt = n => n.toLocaleString("en-US");
/*THEME*/
const ALL = IX.allVersions.map(v => v.id);
const POS = Object.fromEntries(ALL.map((v, i) => [v, i]));
const MEASURED = v => IX.allVersions[POS[v]].status === "indexed";
const FAM = {}; IX.families.forEach(f => f.versions.forEach(v => FAM[v] = f));
const HUBS = IX.families.map(f => f.hub);
const IS_HUB = new Set(HUBS);
const STEP = {}; IX.steps.forEach(s => STEP[s.from + "__" + s.to] = s);
const RUN_SAID = { behaviour: "behaviour checked in the game", load: "loads in the real game", build: "packaged build, not loaded yet", stop: "stopped with named errors" };
const WORST = { red: 0, yellow: 1, green: 2 };

// ---- colours: bench/ledger_rule.py gives every update a colour per direction from
// coverage/ledger.json and the ROADMAP's "doing" steps; a hop takes its worst update ----
const stepSt = (i, fwd) => IX.stepStatus[ALL[i] + "__" + ALL[i + 1]][fwd ? "fwd" : "back"];
function status(a, b) {
  const fwd = POS[a] < POS[b], lo = Math.min(POS[a], POS[b]), hi = Math.max(POS[a], POS[b]);
  const parts = [];
  for (let i = lo; i < hi; i++) {
    const s = stepSt(i, fwd), last = parts[parts.length - 1];
    if (last && last.s.word === s.word && last.s.why === s.why) last.to = i + 1; else parts.push({ from: i, to: i + 1, s });
  }
  if (!fwd) parts.reverse();
  const worst = parts.reduce((w, p) => WORST[p.s.c] < WORST[w.c] ? p.s : w, parts[0].s);
  if (parts.length === 1) return worst;
  const name = p => fwd ? `${ALL[p.from]} → ${ALL[p.to]}` : `${ALL[p.to]} → ${ALL[p.from]}`;
  const why = parts.map(p => `${name(p)}: ${p.s.word.toLowerCase()} (${p.s.why.replace(/\.$/, "")}).`).join(" ");
  let word = parts.every(p => p.s.c === worst.c) ? worst.word : { red: "Not working yet", yellow: "Partly working", green: "Works" }[worst.c];
  if (worst.c === "red" && parts.some(p => p.s.c !== "red") && parts.every(p => p.s.c !== "red" || p.s.word === "Not tried")) word = "Partly tried";
  return { c: worst.c, word, why };
}
// Is there a command worth running as-is? Only if a port across this whole hop, this direction, got past named errors.
function readiness(h) {
  if (!h.measured) return { k: "blocked", label: "Blocked" };
  const fwd = POS[h.a] < POS[h.b], lo = Math.min(POS[h.a], POS[h.b]), hi = Math.max(POS[h.a], POS[h.b]);
  const runs = IX.runs.filter(r => POS[r.from] !== undefined && POS[r.to] !== undefined && (POS[r.from] < POS[r.to]) === fwd);
  const span = r => [Math.min(POS[r.from], POS[r.to]), Math.max(POS[r.from], POS[r.to])];
  const full = runs.filter(r => span(r)[0] <= lo && hi <= span(r)[1]).sort((x, y) => "stop build load behaviour".indexOf(y.status) - "stop build load behaviour".indexOf(x.status));
  if (full.length && full[0].status !== "stop") return { k: "ready", label: `Ran before: ${full[0].mod} ${full[0].from} → ${full[0].to}, ${RUN_SAID[full[0].status]}` };
  if (full.length) return { k: "warn", label: `Tried: ${full[0].mod} ${full[0].from} → ${full[0].to} stopped with named errors. Expect errors` };
  const part = runs.find(r => span(r)[0] < hi && span(r)[1] > lo);
  if (part) return { k: "warn", label: `Not tried across the whole hop (${part.mod} ran ${part.from} → ${part.to}). Expect errors` };
  return { k: "warn", label: "Not tried yet: expect errors" };
}

// ---- sizes ---------------------------------------------------------------------------------
const ORDER = ["tiny", "small", "medium", "big"];
function sizeWord(share) { return share < 0.005 ? "tiny" : share < 0.02 ? "small" : share < 0.04 ? "medium" : "big"; }
function leg(a, b) {
  const fwd = POS[a] < POS[b];
  const s = fwd ? STEP[a + "__" + b] : STEP[b + "__" + a];
  const cov = s.coverage, bk = cov.back;
  const l = fwd ? { broken: cov.listed, mapped: cov.mappings, rule: cov.rule, note: cov.note, left: cov.left }
                : { broken: bk.listed, mapped: bk.mappings, rule: 0, note: 0, left: bk.left };
  return { a, b, s, fwd, ...l, size: sizeWord(fwd ? s.breakingShare : l.broken / Math.max(1, s.oldSize)) };
}
function walk(a, b) {
  const out = [], d = POS[a] < POS[b] ? 1 : -1;
  for (let i = POS[a]; i !== POS[b]; i += d) out.push(leg(ALL[i], ALL[i + d]));
  return out;
}
// Hub and spoke: from X through every hub between X and Y, then out to Y.
function route(x, y) {
  const dir = Math.sign(POS[y] - POS[x]);
  const mid = HUBS.filter(h => (POS[h] - POS[x]) * dir > 0 && (POS[y] - POS[h]) * dir > 0);
  if (dir < 0) mid.reverse();
  const pts = [x, ...mid, y], hops = [];
  for (let i = 0; i + 1 < pts.length; i++) {
    const a = pts[i], b = pts[i + 1], measured = MEASURED(a) && MEASURED(b);
    const steps = measured ? walk(a, b) : [];
    const sum = k => steps.reduce((n, l) => n + l[k], 0);
    hops.push({ a, b, measured, steps, st: status(a, b), broken: sum("broken"), handled: sum("mapped") + sum("rule"), note: sum("note"), left: sum("left"),
      size: steps.length ? steps.map(l => l.size).reduce((m, z) => ORDER.indexOf(z) > ORDER.indexOf(m) ? z : m) : null });
  }
  return hops;
}

// ---- commands ------------------------------------------------------------------------------
let PLAT = /Win/.test(navigator.platform || navigator.userAgent) ? "win" : "nix";
const cmd = h => `${PLAT === "win" ? "modaccel.cmd" : "./modaccel"} port --root <your mod folder> --from ${h.a} --to ${h.b} --fix`;

function render() {
  $("#detail").innerHTML = "";
  const x = $("#from").value, y = $("#to").value;
  if (!x || !y) { mark(x || null, y || null); $("#route").innerHTML = `<p class="summary">Pick ${x ? "where you want to go" : "both versions"} above, or tap two versions in the list below.</p>`; return; }
  try { history.replaceState(null, "", "#from=" + encodeURIComponent(x) + "&to=" + encodeURIComponent(y)); } catch (e) {}
  mark(x, y);
  const out = $("#route");
  if (x === y) { out.innerHTML = `<div class="notice">Pick two different versions.</div>`; return; }
  const hops = route(x, y);
  const n = c => hops.filter(h => h.st.c === c).length;
  const sum = k => hops.reduce((t, h) => t + h[k], 0);
  const blocked = hops.filter(h => !h.measured);
  const back = POS[y] < POS[x];
  let html = `<h2>${esc(x)} → ${esc(y)}${back ? " (backport)" : ""}</h2>
  <p class="summary">${hops.length} hop${hops.length === 1 ? "" : "s"}: ${[["green", "work"], ["yellow", "being worked on"], ["red", "not working yet or blocked"]].filter(([c]) => n(c)).map(([c, w]) => `<span class="dot st-${c}"></span>${n(c)} ${w}`).join(" &nbsp; ")}</p>
  ${blocked.length ? `<div class="notice"><b>Blocked: no official mappings for ${esc([...new Set(blocked.flatMap(h => [h.a, h.b]).filter(v => !MEASURED(v)))].slice(0, 4).join(", "))}${blocked.length > 0 ? "" : ""} yet.</b> Mojang's names start at 1.14.4. Those hops can't be measured or ported by modaccel today; they would need MCP or other legacy community mappings.</div>` : ""}
  ${(() => { const on = HUBS.filter(h => POS[h] >= Math.min(POS[x], POS[y]) && POS[h] <= Math.max(POS[x], POS[y])); return on.length > 2 ? `<p class="small">Jumping straight from ${esc(back ? on[on.length - 1] : on[0])} to ${esc(back ? on[0] : on[on.length - 1])} in one go isn't built yet (the thick line with no dots on the map), so today it's ${hops.length} hops, one at a time.</p>` : ""; })()}
  ${back ? `<p class="small">Backport: rules are written for forward ports, so only renames from the mappings count as handled. What the newer game added is missing on the way back.</p>` : ""}
  <div class="plat"><span class="small">Commands for</span><button type="button" data-plat="win" class="${PLAT === "win" ? "on" : ""}">Windows</button><button type="button" data-plat="nix" class="${PLAT === "nix" ? "on" : ""}">macOS / Linux</button></div>
  <p class="small">Run one hop at a time: port, check it builds and loads, then the next. A command is only marked as ready when a port across that hop has been run. Chaining hops in one command is not built yet (ROADMAP 11b).</p>
  <table class="tbl stack"><thead><tr><th class="l">Hop</th><th class="l">Status</th><th>Size</th><th>Names that break</th><th class="hi">modaccel handles</th><th>Left for you</th></tr></thead><tbody>`;
  hops.forEach((h, i) => {
    h.ready = readiness(h);
    const num = v => h.measured ? fmt(v) : `<span class="small">not measured</span>`;
    html += `<tr class="hop"><td class="l hopname"><b>${esc(h.a)} → ${esc(h.b)}</b><span class="sub">${h.measured ? h.steps.length + " update" + (h.steps.length === 1 ? "" : "s") : "before 1.14.4"}</span></td>
      <td class="status" data-k="Status"><span class="dot st-${h.st.c}"></span>${h.st.word}<span class="why">${esc(h.st.why)}</span></td>
      <td data-k="Size">${h.size ? `<span class="size sz-${h.size}">${h.size}</span>` : `<span class="size sz-none">unknown</span>`}</td>
      <td data-k="Names that break">${num(h.broken)}</td>
      <td class="hi" data-k="modaccel handles">${num(h.handled)}${h.measured && h.note ? `<span class="sub">+ ${fmt(h.note)} with a how-to</span>` : ""}</td>
      <td data-k="Left for you">${num(h.left)}</td></tr>
      <tr class="cmd"><td colspan="6">${h.measured ? `<div class="ready ${h.ready.k}">${esc(h.ready.label)}</div><div class="cmdline"><code>${esc(cmd(h))}</code><button type="button" data-copy="${i}">Copy</button>${h.steps.length ? `<button type="button" data-open="${i}">Show ${h.steps.length === 1 ? "the update" : h.steps.length + " updates"}</button>` : ""}</div><div class="inner" id="in${i}" hidden></div>` : `<div class="ready blocked">Blocked: no command until there are mappings for this hop.</div>`}</td></tr>`;
  });
  html += `</tbody></table>`;
  out.innerHTML = html;
  out.querySelectorAll("button[data-copy]").forEach(b => b.onclick = () => { try { navigator.clipboard.writeText(cmd(hops[+b.dataset.copy])); b.textContent = "Copied"; } catch (e) {} });
  out.querySelectorAll("button[data-plat]").forEach(b => b.onclick = () => { PLAT = b.dataset.plat; render(); });
  out.querySelectorAll("button[data-open]").forEach(b => b.onclick = () => {
    const i = +b.dataset.open, box = $("#in" + i), h = hops[i];
    if (!box.hidden) { box.hidden = true; return; }
    box.innerHTML = h.steps.map(l => `<div class="srow"><b>${esc(l.a)} → ${esc(l.b)}</b><span class="size sz-${l.size}">${l.size}</span><span class="n">${fmt(l.broken)} break · ${fmt(l.mapped + l.rule)} handled · ${fmt(l.left)} left${l.s.linked === "exact" ? "" : " · names matched by guess"}</span><button type="button" data-step="${esc(l.s.from + "__" + l.s.to)}" data-fwd="${l.fwd ? 1 : 0}">What changed</button></div>`).join("");
    box.hidden = false;
    box.querySelectorAll("button[data-step]").forEach(s => s.onclick = () => openStep(s.dataset.step, s.dataset.fwd === "1"));
  });
}

// ---- every release, top to bottom ----------------------------------------------------------
const lineOf = v => v.startsWith("1.") ? v.split(".").slice(0, 2).join(".") + ".X" : v.split(".")[0] + ".X";
function drawMap() {
  const rows = new Map();
  ALL.forEach((v, i) => { const k = lineOf(v); if (!rows.has(k)) rows.set(k, []); rows.get(k).push(i); });
  let html = "";
  for (const [k, idx] of rows) {
    const rowHubs = idx.map(i => ALL[i]).filter(v => IS_HUB.has(v));
    html += `<div class="vrow"><div class="vlabel">${esc(k.replace(/\.X$/, ""))}${MEASURED(ALL[idx[idx.length - 1]]) ? "" : `<span class="est">guess</span>`}${rowHubs.length > 1 ? rowHubs.map(h => `<i data-h="${esc(h)}">${esc(h)}</i>`).join("") : ""}</div><div class="vchips">`;
    idx.forEach((i, n) => {
      const v = ALL[i], f = FAM[v];
      const cls = ["chip", IS_HUB.has(v) ? "hub" : "", MEASURED(v) ? "" : "old"].join(" ");
      html += `<span class="rel"><button type="button" class="${cls}" data-v="${esc(v)}" title="${esc(v + (IS_HUB.has(v) ? ": a popular version, good to port through" : "") + (MEASURED(v) ? "" : ". Before 1.14.4: our numbers are guesses"))}">${esc(v)}</button>`;
      if (n + 1 < idx.length) { // rows stop at their last release; the hub links below join the rows
        const st = stepSt(i, true);
        html += `<button type="button" class="seg ${st.c[0]}" data-i="${i}" aria-label="${esc(v + " to " + ALL[i + 1] + ": " + st.word)}" title="${esc(v + " → " + ALL[i + 1] + ": " + st.word + ". " + st.why)}"><i></i></button>`;
      }
      html += `</span>`;
    });
    html += `</div></div>`;
  }
  $("#map").innerHTML = html + `<svg id="jumps"></svg>`;
  $("#map").querySelectorAll(".seg").forEach(b => b.onclick = () => {
    const i = +b.dataset.i, st = stepSt(i, true);
    info(`<span class="dot st-${st.c}"></span><b>${esc(ALL[i])} → ${esc(ALL[i + 1])}: ${st.word}.</b> ${esc(st.why)}`);
  });
  let pickNext = "from";
  $("#map").querySelectorAll(".chip").forEach(b => b.onclick = () => {
    $("#" + pickNext).value = b.dataset.v;
    if (pickNext === "from") { pickNext = "to"; mark(b.dataset.v, null); info(`Starting from <b>${esc(b.dataset.v)}</b>. Now tap where you want to go.`); return; }
    pickNext = "from"; render(); $("#route").scrollIntoView({ behavior: "smooth" });
  });
  $("#map").querySelectorAll(".chip").forEach(b => {
    b.onmouseenter = () => { HOVER = FAM[b.dataset.v].hub; drawLinks(); };
    b.onmouseleave = () => { HOVER = null; drawLinks(); };
  });
  window.addEventListener("resize", () => drawLinks());
}
function info(html) { $("#hopinfo").innerHTML = html; }
let ROUTE = null, PICK = [], HOVER = null;
// Every hub-to-hub jump, as a bracket in the gutter on the left: out of one hub's row, down a lane,
// into the other's. The lane is set by how many hubs the jump spans, so one hub's jumps nest and
// never cross each other. A jump is coloured by the worst update inside it; jumping in one go is not
// built yet, so a jump longer than one hub hop is never shown better than yellow.
function jumpStatus(a, b) {
  const st = status(a, b), span = Math.abs(HUBS.indexOf(b) - HUBS.indexOf(a));
  if (span < 2) return st;
  return { c: st.c === "green" ? "yellow" : st.c, word: st.c === "red" ? st.word : "One-step jump not built yet", why: "One-step jump not built yet: today you port hub by hub. Along the way: " + st.why };
}
function drawLinks() {
  const box = $("#map"), svg = $("#jumps");
  if (!svg) return;
  const phone = innerWidth < 720;
  // Bright jumps: the hovered version's hub, or the hub of a single pick. A picked route shows only itself.
  const focus = [...new Set([HOVER, !ROUTE && PICK.length === 1 ? FAM[PICK[0]].hub : null].filter(Boolean))];
  const chain = new Set(); let direct = null, ends = null;
  if (ROUTE) {
    const on = HUBS.filter(h => POS[h] >= ROUTE[0] && POS[h] <= ROUTE[1]);
    for (let n = 0; n + 1 < on.length; n++) chain.add(on[n] + "|" + on[n + 1]);
    if (on.length > 2) { direct = on[0] + "|" + on[on.length - 1]; ends = [on[0], on[on.length - 1]]; }
  }
  const span = (a, b) => Math.abs(HUBS.indexOf(b) - HUBS.indexOf(a));
  // On a phone only the picked route gets lanes, packed tight; every jump at once is a solid block there.
  const shown = (a, b) => { const k = a + "|" + b; return chain.has(k) || k === direct; };
  let laneOf = (a, b) => span(a, b) - 1, lanes = HUBS.length - 1;
  if (phone) {
    const spans = [];
    for (let i = 0; i < HUBS.length; i++) for (let j = i + 1; j < HUBS.length; j++) if (shown(HUBS[i], HUBS[j])) spans.push(j - i);
    const order = [...new Set(spans)].sort((x, y) => x - y);
    laneOf = (a, b) => order.indexOf(span(a, b)); lanes = order.length;
  }
  const step = phone ? (lanes > 12 ? 2 : 5) : 4, gut = lanes ? lanes * step + 18 : 0;
  box.style.setProperty("--gut", gut + "px");
  const at = box.getBoundingClientRect();
  svg.setAttribute("width", gut); svg.setAttribute("height", box.scrollHeight);
  svg.classList.toggle("phone", phone); svg.classList.toggle("route", !!ROUTE);
  // Each hub starts at its own tick: its name under the row name when the row has several hubs.
  const y = {};
  HUBS.forEach(h => {
    const el = box.querySelector(`.vlabel i[data-h="${CSS.escape(h)}"]`) || box.querySelector(`.chip[data-v="${CSS.escape(h)}"]`);
    const r = el.getBoundingClientRect(); y[h] = r.top + r.height / 2 - at.top;
  });
  const edge = gut - 4;
  const laneX = (a, b) => edge - 8 - laneOf(a, b) * step;
  const path = (a, b) => `M${edge} ${y[a]} H${laneX(a, b)} V${y[b]} H${edge}`;
  let base = "", top = "";
  for (let i = 0; i < HUBS.length; i++) for (let j = i + 1; j < HUBS.length; j++) {
    const a = HUBS[i], b = HUBS[j], key = a + "|" + b;
    const hot = focus.includes(a) || focus.includes(b), inChain = chain.has(key), isDirect = key === direct;
    if (phone && !shown(a, b)) continue;
    const st = jumpStatus(a, b), d = path(a, b);
    if (!phone) base += `<path class="${st.c[0]}" d="${d}"/>`;
    if (inChain) continue;
    if (hot || isDirect) top += `<path class="${st.c[0]}${isDirect ? " route" : ""}" data-a="${esc(a)}" data-b="${esc(b)}" d="${d}"><title>${esc(a + " → " + b + ": " + st.word + ". " + st.why)}</title></path>`;
  }
  if (chain.size) {
    const stops = [...chain].map(k => k.split("|")), first = stops[0][0], last = stops[stops.length - 1][1];
    const x = phone ? laneX(first, stops[0][1]) : edge - 8;
    top += `<path class="route ink" d="M${edge} ${y[first]} H${x}"/><path class="route ink" d="M${x} ${y[last]} H${edge}"/>`;
    for (const [a, b] of stops) {
      const st = status(a, b);
      top += `<path class="${st.c[0]} route" data-a="${esc(a)}" data-b="${esc(b)}" d="M${x} ${y[a]} V${y[b]}"><title>${esc(a + " → " + b + ": " + st.word + ". " + st.why)}</title></path>`;
    }
    top += [first, ...stops.map(s => s[1])].map(h => `<circle class="stop" cx="${x}" cy="${y[h]}" r="5"><title>${esc("Stops at " + h)}</title></circle>`).join("");
  }
  const ticks = gut ? HUBS.map(h => `<path class="tick" d="M${edge - 5} ${y[h]} H${edge + 4}"/>`).join("") : "";
  svg.innerHTML = `<g class="base">${base}</g>${ticks}<g class="top">${top}</g>`;
  svg.querySelectorAll(".top path[data-a]").forEach(p => p.onclick = () => {
    const st = jumpStatus(p.dataset.a, p.dataset.b);
    info(`<span class="dot st-${st.c}"></span><b>${esc(p.dataset.a)} → ${esc(p.dataset.b)}: ${st.word}.</b> ${esc(st.why)}`);
  });
  // A label at the middle of the route's one-jump line.
  let lab = $("#jumplabel");
  if (!lab) { lab = document.createElement("div"); lab.id = "jumplabel"; box.appendChild(lab); }
  lab.hidden = !ends;
  if (ends) {
    const back = POS[$("#to").value] < POS[$("#from").value], [a, b] = back ? [ends[1], ends[0]] : ends;
    lab.innerHTML = `${esc(a)} → ${esc(b)} in one jump <span>(not built yet)</span>`;
    const x = laneX(ends[0], ends[1]), mid = (y[ends[0]] + y[ends[1]]) / 2;
    lab.style.top = (mid - lab.offsetHeight / 2) + "px";
    if (x - 8 >= 110) { lab.style.left = ""; lab.style.right = `calc(100% - ${x - 8}px)`; lab.style.maxWidth = (x - 8) + "px"; }
    else { lab.style.right = ""; lab.style.left = (x + 8) + "px"; lab.style.maxWidth = "150px"; }
  }
}
function mark(x, y) {
  document.querySelectorAll(".chip").forEach(c => {
    c.classList.toggle("sel", c.dataset.v === x || c.dataset.v === y);
    if (c.dataset.v === x) c.dataset.tag = "from"; else if (c.dataset.v === y) c.dataset.tag = "to"; else delete c.dataset.tag;
  });
  ROUTE = x && y && x !== y ? [Math.min(POS[x], POS[y]), Math.max(POS[x], POS[y])] : null;
  PICK = [x, y].filter(Boolean);
  $("#map").classList.toggle("route", !!ROUTE);
  document.querySelectorAll(".seg").forEach(s => s.classList.toggle("in", !!ROUTE && +s.dataset.i >= ROUTE[0] && +s.dataset.i < ROUTE[1]));
  drawLinks();
}

/*DETAIL*/

// ---- pickers -------------------------------------------------------------------------------
function pickers() {
  const opts = `<option value="">Pick</option>` + [...ALL].reverse().map(v => `<option value="${esc(v)}">${esc(v)}</option>`).join("");
  $("#from").innerHTML = opts; $("#to").innerHTML = opts;
  const h = new URLSearchParams(location.hash.slice(1));
  $("#from").value = POS[h.get("from")] !== undefined ? h.get("from") : "";
  $("#to").value = POS[h.get("to")] !== undefined ? h.get("to") : "";
  $("#from").onchange = $("#to").onchange = render;
  $("#swap").onclick = () => { const f = $("#from").value; $("#from").value = $("#to").value; $("#to").value = f; render(); };
  window.addEventListener("hashchange", () => {
    const n = new URLSearchParams(location.hash.slice(1));
    if (POS[n.get("from")] !== undefined && POS[n.get("to")] !== undefined && (n.get("from") !== $("#from").value || n.get("to") !== $("#to").value)) { $("#from").value = n.get("from"); $("#to").value = n.get("to"); render(); }
  });
}

const START = new URLSearchParams(location.hash.slice(1));
drawMap(); pickers(); render();
if (START.get("step") && STEP[START.get("step")]) openStep(START.get("step"), START.get("back") !== "1");
$("#gen").textContent = IX.generated;
</script>
</body></html>
"""

if __name__ == "__main__":
    main()
