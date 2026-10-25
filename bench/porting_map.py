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
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHANGES = ROOT / "coverage" / "changes"
DOCS = ROOT / "docs"
STEPS = DOCS / "porting-map" / "steps"


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
        out.write_text(f'PM.loaded("{key}","{b64}");\n', encoding="ascii")
        total += out.stat().st_size
        cov = {k: v for k, v in s["coverage"].items() if k not in ("matched",)}
        slim_steps.append({**{k: v for k, v in s.items() if k != "coverage"}, "coverage": cov})
    slim = {k: index[k] for k in ("generated", "names", "linked", "families", "matching", "mods", "versions", "blocked", "hubHops", "routing", "counting", "runs")}
    slim["steps"] = slim_steps
    payload = json.dumps(slim, separators=(",", ":"), ensure_ascii=False).replace("</", "<\\/")
    html = TEMPLATE.replace("/*INDEX*/null", payload)
    (DOCS / "porting-map.html").write_text(html, encoding="utf-8")
    print("wrote", DOCS / "porting-map.html", len(html), "bytes;", len(slim_steps), "step files,", total, "bytes")


TEMPLATE = r"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Porting map</title>
<meta name="description" content="What changed in Minecraft between every pair of neighbouring releases, the route between any two versions, and how much Modding Accelerator already handles.">
<style>
:root {
  --bg:#ffffff; --panel:#f6f5f2; --panel2:#efede8; --ink:#141414; --ink2:#4f4e4a; --ink3:#86847d; --rule:#e5e3dd;
  --accent:#2a63c7; --mapped:#1f8a4c; --rule-c:#2a78d6; --note:#c98a12; --left:#c4c1b8; --bad:#b03a2e; --chip:#ffffff;
  --tiny:#1f8a4c; --small:#6a9a1f; --medium:#c98a12; --big:#b03a2e;
}
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) {
  --bg:#141413; --panel:#1f1f1d; --panel2:#2a2a27; --ink:#efeee9; --ink2:#c2c0b8; --ink3:#8d8b84; --rule:#33332f;
  --accent:#7aa7f0; --mapped:#3fb36f; --rule-c:#5b9cf0; --note:#e0a83a; --left:#55534d; --bad:#e2685b; --chip:#262624;
  --tiny:#3fb36f; --small:#9ac046; --medium:#e0a83a; --big:#e2685b;
} }
:root[data-theme="dark"] {
  --bg:#141413; --panel:#1f1f1d; --panel2:#2a2a27; --ink:#efeee9; --ink2:#c2c0b8; --ink3:#8d8b84; --rule:#33332f;
  --accent:#7aa7f0; --mapped:#3fb36f; --rule-c:#5b9cf0; --note:#e0a83a; --left:#55534d; --bad:#e2685b; --chip:#262624;
  --tiny:#3fb36f; --small:#9ac046; --medium:#e0a83a; --big:#e2685b;
}
* { box-sizing:border-box }
html { -webkit-text-size-adjust:100% }
body { margin:0; background:var(--bg); color:var(--ink); font:15px/1.5 "Segoe UI",system-ui,-apple-system,sans-serif }
main { max-width:1080px; margin:0 auto; padding:28px 16px 48px }
h1 { font-size:30px; margin:0; letter-spacing:-.01em }
h2 { font-size:20px; margin:34px 0 6px }
h3 { font-size:16px; margin:18px 0 6px }
p { margin:6px 0; color:var(--ink2) }
a { color:var(--accent) }
.lede { font-size:16px; max-width:760px }
.top { display:flex; gap:12px; align-items:baseline; justify-content:space-between; flex-wrap:wrap }
.top nav { display:flex; gap:14px; align-items:center; font-size:14px }
.mono { font-family:"Cascadia Mono",Consolas,ui-monospace,monospace; font-size:13px }
.small { font-size:12.5px; color:var(--ink3) }
.card { background:var(--panel); border-radius:12px; padding:16px; margin-top:14px }
.pick { display:grid; grid-template-columns:1fr auto 1fr; gap:10px; align-items:end }
.pick label { display:block; font-size:13px; color:var(--ink2); margin-bottom:4px; font-weight:600 }
select, input[type=search] { width:100%; font:inherit; font-size:16px; padding:9px 10px; border:1px solid var(--rule); border-radius:8px; background:var(--chip); color:var(--ink) }
button { font:inherit; cursor:pointer; border:1px solid var(--rule); background:var(--chip); color:var(--ink); border-radius:8px; padding:8px 12px }
button:hover { border-color:var(--ink3) }
button.swap { height:44px; width:44px; padding:0; font-size:18px }
.quick { margin-top:10px; display:flex; gap:8px; flex-wrap:wrap }
.quick button { font-size:13px; padding:5px 10px; border-radius:999px }
.tiles { display:grid; grid-template-columns:repeat(5,1fr); gap:10px; margin-top:14px }
.tile { background:var(--panel); border-radius:10px; padding:12px 14px }
.tile b { display:block; font-size:24px; line-height:1.2 }
.tile span { font-size:12.5px; color:var(--ink2) }
.legend { display:flex; gap:16px; flex-wrap:wrap; font-size:13px; color:var(--ink2); margin-top:10px }
.sw { display:inline-block; width:11px; height:11px; border-radius:3px; margin-right:6px; vertical-align:-1px }
.leg { margin-top:16px }
.leg h3 { display:flex; gap:8px; align-items:baseline; flex-wrap:wrap }
.leg h3 .small { font-weight:400 }
.step { display:grid; grid-template-columns:150px 92px 1fr 150px; gap:12px; align-items:center; padding:10px 12px; border-top:1px solid var(--rule) }
.step:first-of-type { border-top:0 }
.step .jump { font-weight:600; white-space:nowrap }
.size { font-size:12.5px; font-weight:600; padding:2px 8px; border-radius:999px; color:#fff; text-align:center; white-space:nowrap }
.bar { display:flex; height:12px; border-radius:6px; overflow:hidden; background:var(--panel2) }
.bar i { display:block; height:100% }
.barwrap .small { margin-top:3px }
.proof { font-size:12.5px; color:var(--ink2); line-height:1.3 }
.dot { display:inline-block; width:9px; height:9px; border-radius:50%; margin-right:5px; vertical-align:0 }
.notice { border-left:4px solid var(--note); background:var(--panel); padding:12px 14px; border-radius:8px; margin-top:14px; color:var(--ink2) }
.notice.bad { border-color:var(--bad) }
table { width:100%; border-collapse:collapse; font-size:14px; margin-top:8px }
th { text-align:left; font-size:12px; color:var(--ink3); font-weight:600; padding:6px 8px; border-bottom:1px solid var(--rule) }
td { padding:8px; border-bottom:1px solid var(--rule); vertical-align:top }
.hub { font-weight:700 }
.vers span { display:inline-block; margin:0 6px 2px 0 }
.vers .h { font-weight:700; color:var(--ink) }
.vers .o { color:var(--ink2) }
#detail { scroll-margin-top:12px }
.filters { display:flex; gap:6px; flex-wrap:wrap; margin-top:10px }
.filters button { font-size:13px; padding:5px 10px; border-radius:999px }
.filters button.on { background:var(--ink); color:var(--bg); border-color:var(--ink) }
.owner { margin-top:12px; border-top:1px solid var(--rule); padding-top:8px }
.owner .cn { font-weight:600; word-break:break-word }
.owner .pk { color:var(--ink3); font-weight:400 }
.item { display:flex; gap:8px; align-items:baseline; padding:2px 0; word-break:break-word }
.tag { flex:none; font-size:11px; font-weight:700; padding:1px 6px; border-radius:4px; background:var(--panel2); color:var(--ink2); text-transform:lowercase }
.tag.removed { color:var(--bad) } .tag.added { color:var(--mapped) } .tag.renamed { color:var(--rule-c) } .tag.changed { color:var(--note) }
.st { flex:none; font-size:11px; padding:1px 6px; border-radius:4px; color:#fff }
.st.mappings { background:var(--mapped) } .st.rule { background:var(--rule-c) } .st.note { background:var(--note) }
.more { margin-top:12px }
details.hop { border-top:1px solid var(--rule) }
details.hop:first-of-type { border-top:0 }
details.hop > summary { list-style:none; cursor:pointer; border-top:0 }
details.hop > summary::-webkit-details-marker { display:none }
details.hop .open { display:inline-block; white-space:nowrap; font-size:13px; padding:7px 10px; border:1px solid var(--rule); border-radius:8px; background:var(--chip); text-align:center }
details.hop .open::after { content:" \25BE" }
details.hop[open] .open::after { content:" \25B4" }
details.hop .inner { margin:0 0 8px 18px; border-left:3px solid var(--rule); background:var(--bg); border-radius:0 8px 8px 0 }
.proof { margin-top:4px }
.privbox { display:flex; gap:8px; align-items:center; margin-top:10px; font-size:13px; color:var(--ink2) }
.how li { margin:6px 0; color:var(--ink2) }
.foot { margin-top:34px; padding-top:14px; border-top:1px solid var(--rule); font-size:12.5px; color:var(--ink3) }
.theme { font-size:13px; padding:4px 10px }
@media (max-width: 760px) {
  main { padding:18px 16px 40px }
  h1 { font-size:25px }
  .pick { grid-template-columns:1fr auto 1fr; }
  .tiles { grid-template-columns:repeat(2,1fr) }
  .tiles .tile:first-child { grid-column:1 / -1 }
  .step { grid-template-columns:1fr auto; gap:6px 10px }
  .step .barwrap { grid-column:1 / -1 }
  .step .go { grid-column:1 / -1 }
  .step .go button, .step .go .open { width:100% }
  details.hop .inner { margin-left:8px }
  .hide-sm { display:none }
  .item { flex-wrap:wrap; gap:4px 6px; padding:5px 0 }
  .item .mono { flex:1 1 100%; order:3 }
  table.fam td, table.fam th { padding:6px 4px }
}
</style></head><body>
<main>
<div class="top">
  <h1>Porting map</h1>
  <nav><a href="progress.html">Ports that were run</a><a href="https://github.com/Noisemaker111/modding-accelerator">GitHub</a> <button class="theme" id="theme" type="button">Dark</button></nav>
</div>
<p class="lede">Pick the version your mod is on and the version you want. You get the route, how big each step is, what changed in the game, how much Modding Accelerator already handles, and what is left for you.</p>

<div class="card">
  <div class="pick">
    <div><label for="from">My mod is on</label><select id="from"></select></div>
    <button class="swap" id="swap" type="button" title="Swap" aria-label="Swap the two versions">⇄</button>
    <div><label for="to">I want it on <span class="small" style="font-weight:400">★ = hub</span></label><select id="to"></select></div>
  </div>
  <div class="quick" id="quick"></div>
</div>

<div id="route"></div>
<div id="detail"></div>

<h2>Families and hubs</h2>
<p>Releases that barely differ are one family: a mod moves between them with almost no work. Each family has a hub, the release most mods use. Long ports go through the hubs. <span id="famrule"></span></p>
<div class="card" style="padding:6px 10px 10px"><table class="fam" id="families"></table></div>

<h2>How this was made</h2>
<ul class="how" id="how"></ul>

<p class="foot">Data: <span class="mono">coverage/changes/</span> (one file per update, plus <span class="mono">index.json</span>), made by <span class="mono">tools/ChangeIndex.java</span> and <span class="mono">bench/change_routes.py</span>; this page by <span class="mono">bench/porting_map.py</span>. Port runs come from <span class="mono">coverage/ledger.json</span>. Generated <span id="gen"></span>.</p>
</main>
<script>
const IX = /*INDEX*/null;
const $ = s => document.querySelector(s);
const esc = s => String(s).replace(/[&<>"]/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
const fmt = n => n.toLocaleString("en-US");
const V = IX.versions.map(v => v.id);
const POS = Object.fromEntries(V.map((v, i) => [v, i]));
const FAM = {}; IX.families.list.forEach(f => f.versions.forEach(v => FAM[v] = f));
const STEP = {}; IX.steps.forEach(s => STEP[s.from + "__" + s.to] = s);
const BLOCKED = Object.fromEntries(IX.blocked.map(b => [b.id, b]));
const STATUS = { behaviour: "behaviour checked in the game", load: "loads in the real game", build: "builds, mixins checked", stop: "stopped with named errors" };
const SCOL = { behaviour: "var(--mapped)", load: "var(--rule-c)", build: "var(--note)", stop: "var(--bad)" };

// ---- theme ---------------------------------------------------------------------------------
(function () {
  const btn = $("#theme"), root = document.documentElement;
  let saved = null; try { saved = localStorage.getItem("pm-theme"); } catch (e) {}
  if (saved) root.dataset.theme = saved;
  const dark = () => root.dataset.theme ? root.dataset.theme === "dark" : matchMedia("(prefers-color-scheme: dark)").matches;
  const label = () => btn.textContent = dark() ? "Light" : "Dark";
  btn.onclick = () => { root.dataset.theme = dark() ? "light" : "dark"; try { localStorage.setItem("pm-theme", root.dataset.theme); } catch (e) {} label(); };
  label();
})();

// ---- sizes ---------------------------------------------------------------------------------
function sizeWord(share) {
  if (share < 0.005) return ["tiny", "var(--tiny)"];
  if (share < 0.02) return ["small", "var(--small)"];
  if (share < 0.04) return ["medium", "var(--medium)"];
  return ["big", "var(--big)"];
}

// One step in the direction travelled. A backport reads the same file the other way round.
function leg(a, b) {
  const fwd = POS[a] < POS[b];
  const s = fwd ? STEP[a + "__" + b] : STEP[b + "__" + a];
  const cov = s.coverage, bk = cov.back;
  if (fwd) return { a, b, s, fwd, broken: cov.listed, mapped: cov.mappings, rule: cov.rule, note: cov.note, left: cov.left };
  // Going back: what the newer game added is missing in the older one; renames run backwards.
  return { a, b, s, fwd, broken: bk.listed, mapped: bk.mappings, rule: 0, note: 0, left: bk.left };
}

function walk(a, b) {
  const out = [];
  const d = POS[a] < POS[b] ? 1 : -1;
  for (let i = POS[a]; i !== POS[b]; i += d) out.push(leg(V[i], V[i + d]));
  return out;
}

// Hub and spoke: X to its family's hub, hub to hub, then out to Y. Only hubs between X and Y are
// used, so a route never doubles back past where it started or ended.
function hop(a, b) {
  const steps = walk(a, b);
  const sum = k => steps.reduce((n, l) => n + l[k], 0);
  return { a, b, steps, broken: sum("broken"), mapped: sum("mapped"), rule: sum("rule"), note: sum("note"), left: sum("left") };
}
function route(x, y) {
  const fx = FAM[x], fy = FAM[y], dir = Math.sign(POS[y] - POS[x]);
  if (fx === fy) return [{ title: "Inside one family", note: "releases " + fx.id + " barely differ", hops: [hop(x, y)] }];
  const between = h => (POS[h] - POS[x]) * dir > 0 && (POS[y] - POS[h]) * dir > 0;
  const hubs = IX.families.list.map(f => f.hub).filter(between);
  if (dir < 0) hubs.reverse();
  const legs = [];
  let at = x;
  if (hubs.length && hubs[0] === fx.hub) { legs.push({ title: "To your family's hub", note: fx.hub, hops: [hop(x, fx.hub)] }); at = fx.hub; }
  const rest = hubs.filter(h => h !== at);
  if (rest.length) {
    const fromHub = FAM[at].hub === at;
    const hops = [];
    for (const h of rest) { hops.push(hop(at, h)); at = h; }
    legs.push({ title: fromHub ? "Hub to hub" : "Through the hubs", note: hops.length + " hop" + (hops.length === 1 ? "" : "s") + ", one row per hop", hops });
  }
  if (at !== y) legs.push({ title: legs.length ? "Out to your version" : "Straight there", note: y, hops: [hop(at, y)] });
  return legs;
}

function bar(l) {
  const t = Math.max(1, l.broken);
  const seg = (n, col) => n ? `<i style="width:${(100 * n / t).toFixed(2)}%;background:${col}"></i>` : "";
  return `<div class="bar" role="img" aria-label="${l.mapped + l.rule} handled, ${l.note} with a how-to, ${l.left} left">${seg(l.mapped, "var(--mapped)")}${seg(l.rule, "var(--rule-c)")}${seg(l.note, "var(--note)")}${seg(l.left, "var(--left)")}</div>`;
}
const numbers = l => `${fmt(l.broken)} names break · ${fmt(l.mapped + l.rule)} handled · ${fmt(l.note)} how-to · ${fmt(l.left)} left`;

// A port run shows on a row only when it ran exactly that jump; longer runs are listed once per route.
function runsExactly(a, b) {
  return IX.runs.filter(r => r.from === a && r.to === b).map(r => `<div class="proof"><span class="dot" style="background:${SCOL[r.status]}"></span>Port run: <b>${esc(r.mod)}</b> ${esc(r.loader)}, ${STATUS[r.status]}</div>`).join("");
}

function stepRow(l) {
  const [w, col] = sizeWord(l.fwd ? l.s.breakingShare : l.broken / Math.max(1, l.s.oldSize));
  return `<div class="step">
    <div class="jump">${esc(l.a)} → ${esc(l.b)}${l.fwd ? "" : ' <span class="small">back</span>'}</div>
    <div><span class="size" style="background:${col}">${w}</span></div>
    <div class="barwrap">${bar(l)}<div class="small">${numbers(l)}${l.s.linked === "exact" ? "" : " · names matched by guess"}</div>${runsExactly(l.a, l.b)}</div>
    <div class="go"><button type="button" data-step="${esc(l.s.from + "__" + l.s.to)}" data-fwd="${l.fwd ? 1 : 0}">What changed</button></div>
  </div>`;
}

function hopRow(h) {
  if (h.steps.length === 1) return stepRow(h.steps[0]);
  const order = ["tiny", "small", "medium", "big"];
  const sizes = h.steps.map(l => sizeWord(l.fwd ? l.s.breakingShare : l.broken / Math.max(1, l.s.oldSize)));
  const [w, col] = sizes.reduce((m, z) => order.indexOf(z[0]) > order.indexOf(m[0]) ? z : m);
  return `<details class="hop"><summary class="step">
    <div class="jump">${esc(h.a)} → ${esc(h.b)}<div class="small" style="font-weight:400">${h.steps.length} updates</div></div>
    <div><span class="size" style="background:${col}" title="its biggest update">${w}</span></div>
    <div class="barwrap">${bar(h)}<div class="small">${numbers(h)}</div>${runsExactly(h.a, h.b)}</div>
    <div class="go"><span class="open">Show ${h.steps.length} updates</span></div>
  </summary><div class="inner">${h.steps.map(stepRow).join("")}</div></details>`;
}

function render() {
  $("#detail").innerHTML = "";
  const x = $("#from").value, y = $("#to").value;
  try { history.replaceState(null, "", "#from=" + encodeURIComponent(x) + "&to=" + encodeURIComponent(y)); } catch (e) {}
  const out = $("#route");
  const blockedSide = BLOCKED[x] || BLOCKED[y];
  if (blockedSide) {
    out.innerHTML = `<div class="notice bad"><b>Blocked: no official mappings for ${esc(blockedSide.id)} yet.</b> Mojang's own names start at 1.14.4, so ${esc(blockedSide.id)} is not in the index and no route can be shown. It would need MCP or other legacy community mappings, which the index does not use. Pick 1.14.4 or later.</div>`;
    return;
  }
  if (x === y) { out.innerHTML = `<div class="notice">Pick two different versions.</div>`; return; }
  const legs = route(x, y);
  const all = legs.flatMap(g => g.hops.flatMap(h => h.steps));
  const sum = k => all.reduce((n, l) => n + l[k], 0);
  const fams = new Set(all.map(l => FAM[l.b].id)); fams.add(FAM[x].id);
  const back = POS[y] < POS[x];
  const bigs = all.filter(l => sizeWord(l.fwd ? l.s.breakingShare : l.broken / Math.max(1, l.s.oldSize))[0] === "big").map(l => `${l.a} → ${l.b}`);
  const lo = Math.min(POS[x], POS[y]), hi = Math.max(POS[x], POS[y]);
  const runs = IX.runs.filter(r => (POS[r.to] > POS[r.from]) === !back && Math.min(POS[r.from], POS[r.to]) < hi && Math.max(POS[r.from], POS[r.to]) > lo);
  let html = `<h2>${esc(x)} → ${esc(y)}${back ? " (backport)" : ""}</h2>
  <p>${all.length} update${all.length === 1 ? "" : "s"} across ${fams.size} famil${fams.size === 1 ? "y" : "ies"}. ${bigs.length ? "The hardest: " + esc(bigs.join(", ")) + "." : "No big updates on this route."}</p>
  <div class="tiles">
    <div class="tile"><b>${fmt(sum("broken"))}</b><span>game names a mod could use that break on the way</span></div>
    <div class="tile"><b style="color:var(--mapped)">${fmt(sum("mapped"))}</b><span>renames and moves rewritten for you</span></div>
    <div class="tile"><b style="color:var(--rule-c)">${fmt(sum("rule"))}</b><span>fixed by a written rule</span></div>
    <div class="tile"><b style="color:var(--note)">${fmt(sum("note"))}</b><span>have a written how-to</span></div>
    <div class="tile"><b>${fmt(sum("left"))}</b><span>left to do if your mod uses them</span></div>
  </div>
  <div class="legend"><span><span class="sw" style="background:var(--mapped)"></span>renamed for you</span><span><span class="sw" style="background:var(--rule-c)"></span>fixed by a rule</span><span><span class="sw" style="background:var(--note)"></span>how-to written</span><span><span class="sw" style="background:var(--left)"></span>left to do</span></div>
  ${back ? `<div class="notice">Backports: the rules in this repo are written for forward ports, so only mapping renames count as handled here. Anything the newer game added is missing on the way back.</div>` : ""}
  <p class="small" style="margin-top:10px">“Handled” is renamed for you plus fixed by a rule. Counts leave out private members. Your mod only meets the changes it actually uses, usually a small slice of these: open an update and search for the classes your mod calls.</p>`;
  for (const g of legs) {
    html += `<div class="leg"><h3>${esc(g.title)} <span class="small">${esc(g.note)}</span></h3><div class="card" style="padding:4px 4px">${g.hops.map(hopRow).join("")}</div></div>`;
  }
  html += `<div class="leg"><h3>Ports run on this route <span class="small">from coverage/ledger.json; only ports that were really run</span></h3><div class="card">` +
    (runs.length ? runs.map(r => `<div class="proof" style="margin:4px 0"><span class="dot" style="background:${SCOL[r.status]}"></span><b>${esc(r.mod)}</b> ${esc(r.loader)} ${esc(r.from)} → ${esc(r.to)}: ${STATUS[r.status]} <span class="small">${esc(r.date || "")}</span></div>`).join("")
      : `<p>No port has been run on any part of this route yet.</p>`) + `</div></div>`;
  out.innerHTML = html;
  out.querySelectorAll("button[data-step]").forEach(b => b.onclick = () => openStep(b.dataset.step, b.dataset.fwd === "1"));
}

// ---- step detail ---------------------------------------------------------------------------
const CACHE = {}, WAIT = {};
window.PM = { loaded(key, b64) {
  const bytes = Uint8Array.from(atob(b64), c => c.charCodeAt(0));
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip"));
  new Response(stream).text().then(t => { CACHE[key] = JSON.parse(t); (WAIT[key] || []).forEach(f => f(CACHE[key])); delete WAIT[key]; });
} };
function loadStep(key) {
  return new Promise((ok, fail) => {
    if (CACHE[key]) return ok(CACHE[key]);
    if (typeof DecompressionStream === "undefined") return fail(new Error("This browser cannot unpack the change list (it needs DecompressionStream)."));
    (WAIT[key] = WAIT[key] || []).push(ok);
    if (WAIT[key].length > 1) return;
    const s = document.createElement("script");
    s.src = "porting-map/steps/" + key + ".js";
    s.onerror = () => fail(new Error("Could not load " + s.src));
    document.head.appendChild(s);
  });
}

const KIND = {
  classesRenamed: ["renamed", "class"], classesRemoved: ["removed", "class"], classesAdded: ["added", "class"],
  methodsRenamed: ["renamed", "method"], methodsChanged: ["changed", "method"], methodsRemoved: ["removed", "method"], methodsAdded: ["added", "method"],
  fieldsRenamed: ["renamed", "field"], fieldsChanged: ["changed", "field"], fieldsRemoved: ["removed", "field"], fieldsAdded: ["added", "field"],
};
const FLIP = { removed: "added", added: "removed", renamed: "renamed", changed: "changed" };

function flatten(d, fwd) {
  const st = {};
  const KN = { "class renamed": "classesRenamed", "class removed": "classesRemoved" };
  for (const [kind, owner, text, status, rule] of d.matched) st[(KN[kind] || kind) + "|" + owner + "|" + text] = [status, rule];
  const rows = [];
  const push = (kind, owner, text) => {
    let [what, type] = KIND[kind];
    let shown = text;
    if (!fwd) { what = FLIP[what]; if (text.includes(" -> ")) shown = text.split(" -> ").reverse().join(" -> "); }
    let s = fwd ? st[kind + "|" + owner + "|" + text] : null;
    if (!s && what === "renamed" && d.linked === "exact") s = ["mappings", null];
    rows.push({ what, type, owner, text: shown, status: s ? s[0] : (what === "added" ? "" : "left"), rule: s ? s[1] : null, priv: text.startsWith("private ") });
  };
  for (const [o, n] of d.classesRenamed) push("classesRenamed", o, o + " -> " + n);
  for (const c of d.classesRemoved) push("classesRemoved", c, c);
  for (const c of d.classesAdded) push("classesAdded", c, c);
  for (const [owner, lists] of Object.entries(d.members)) for (const [k, items] of Object.entries(lists)) if (KIND[k]) for (const it of items) push(k, owner, it);
  return rows;
}

let VIEW = null;
async function openStep(key, fwd) {
  const box = $("#detail");
  const s = STEP[key];
  const a = fwd ? s.from : s.to, b = fwd ? s.to : s.from;
  box.innerHTML = `<h2>What changed: ${esc(a)} → ${esc(b)}</h2><p>Loading…</p>`;
  try { history.replaceState(null, "", "#from=" + encodeURIComponent($("#from").value) + "&to=" + encodeURIComponent($("#to").value) + "&step=" + encodeURIComponent(key) + (fwd ? "" : "&back=1")); } catch (e) {}
  box.scrollIntoView({ behavior: "smooth", block: "start" });
  let d;
  try { d = await loadStep(key); } catch (e) { box.innerHTML += `<div class="notice bad">${esc(e.message)}</div>`; return; }
  VIEW = { rows: flatten(d, fwd), filter: "break", q: "", limit: 150, key, fwd, a, b, d };
  const cov = s.coverage;
  box.innerHTML = `<h2>What changed: ${esc(a)} → ${esc(b)}</h2>
    <p>${s.linked === "exact" ? "Both releases are linked by Fabric's intermediary ids, so every rename here is proven, not guessed." : "One side ships without obfuscation, so there is no shared id: renames and moves here are matched by name and are a best guess."}
    ${fwd ? "" : " Shown as a backport: what the newer game added is missing on the way back, and renames run backwards."}</p>
    ${fwd && cov.ruleHits.length ? `<p class="small">Rules that name changes here: ${cov.ruleHits.slice(0, 8).map(r => esc(r.rule) + " (" + r.changes + ")").join(" · ")}${cov.ruleHits.length > 8 ? " …" : ""}</p>` : ""}
    <div class="card">
      <input type="search" id="q" placeholder="Search a class or method your mod uses, e.g. ItemStack or getTag" aria-label="Search the changes">
      <div class="filters" id="filters"></div>
      <label class="privbox"><input type="checkbox" id="priv"> Also show private members (${fmt(VIEW.rows.filter(r => r.priv).length)}). Mixins sometimes target them; the counts on the route leave them out.</label>
      <div id="list"></div>
    </div>`;
  $("#priv").onchange = e => { VIEW.priv = e.target.checked; VIEW.limit = 150; draw(); };
  $("#q").oninput = e => { VIEW.q = e.target.value.trim().toLowerCase(); VIEW.limit = 150; draw(); };
  draw();
}

const simpleName = o => o.slice(o.lastIndexOf(".") + 1).toLowerCase();
const FILTERS = [["break", "Breaks a mod"], ["handled", "Handled"], ["howto", "How-to"], ["left", "Left to do"], ["renamed", "Renamed or moved"], ["removed", "Removed"], ["changed", "New signature"], ["added", "Added"], ["all", "Everything"]];
function keep(r, f) {
  if (f === "all") return true;
  if (f === "break") return r.what !== "added";
  if (f === "left") return r.status === "left";
  if (f === "handled") return r.status === "mappings" || r.status === "rule";
  if (f === "howto") return r.status === "note";
  return r.what === f;
}

function draw() {
  const v = VIEW;
  const q = v.q;
  const match = r => (v.priv || !r.priv) && (!q || r.owner.toLowerCase().includes(q) || r.text.toLowerCase().includes(q));
  const base = v.rows.filter(match);
  $("#filters").innerHTML = FILTERS.map(([k, label]) => `<button type="button" class="${v.filter === k ? "on" : ""}" data-f="${k}">${label} <span class="small">${fmt(base.filter(r => keep(r, k)).length)}</span></button>`).join("");
  $("#filters").querySelectorAll("button").forEach(b => b.onclick = () => { v.filter = b.dataset.f; v.limit = 150; draw(); });
  const rows = base.filter(r => keep(r, v.filter));
  const groups = new Map();
  if (q) groups.set("Classes", []);
  // Classes whose own name matches the search come first.
  if (q) for (const r of rows) if (r.type !== "class" && simpleName(r.owner).includes(q) && !groups.has(r.owner)) groups.set(r.owner, []);
  for (const r of rows) { const g = r.type === "class" ? "Classes" : r.owner; if (!groups.has(g)) groups.set(g, []); groups.get(g).push(r); }
  let shown = 0, html = "";
  const STL = { mappings: "renamed for you", rule: "rule", note: "how-to" };
  for (const [g, list] of groups) {
    if (shown >= v.limit) break;
    if (!list.length) continue;
    let head;
    if (g === "Classes") head = `<div class="cn">Classes</div>`;
    else { const i = g.lastIndexOf("."); head = `<div class="cn"><span class="pk">${esc(g.slice(0, i + 1))}</span>${esc(g.slice(i + 1))}</div>`; }
    html += `<div class="owner">${head}`;
    for (const r of list) {
      if (shown++ >= v.limit) break;
      const st = STL[r.status] ? `<span class="st ${r.status}" title="${esc(r.rule || "rewritten from the mappings by port --fix")}">${STL[r.status]}</span>` : "";
      html += `<div class="item"><span class="tag ${r.what}">${r.what}${r.type === "class" ? "" : " " + r.type}</span><span class="mono">${esc(r.text)}</span>${st}</div>`;
    }
    html += `</div>`;
  }
  if (!rows.length) html = `<p>Nothing here${q ? " matches “" + esc(q) + "”" : ""}.</p>`;
  if (rows.length > v.limit) html += `<div class="more"><button type="button" id="more">Show more (${fmt(rows.length - v.limit)} left)</button></div>`;
  $("#list").innerHTML = html;
  const m = $("#more"); if (m) m.onclick = () => { v.limit += 400; draw(); };
}

// ---- families ------------------------------------------------------------------------------
function families() {
  const rows = IX.families.list.map((f, i) => {
    const last = f.versions[f.versions.length - 1];
    const next = STEP[last + "__" + (V[POS[last] + 1] || "")];
    const vers = f.versions.map(v => `<span class="${v === f.hub ? "h" : "o"}">${esc(v)}${v === f.hub ? " ★" : ""}</span>`).join("");
    let into = "<span class='small'>latest release</span>";
    if (next) { const [w, col] = sizeWord(next.breakingShare); into = `<span class="size" style="background:${col}">${w}</span> <span class="small">${(100 * next.breakingShare).toFixed(1)}% of names break going to ${esc(next.to)}</span>`; }
    return `<tr><td class="vers">${vers}</td><td class="hub">${esc(f.hub)}<div class="small">${fmt(f.mods[f.hub] || 0)} mods</div></td><td class="hide-sm">${into}</td><td><button type="button" data-hub="${esc(f.hub)}">Route from here</button></td></tr>`;
  }).join("");
  $("#families").innerHTML = `<tr><th>Releases (★ hub)</th><th>Hub</th><th class="hide-sm">Update into the next family</th><th></th></tr>` + rows +
    `<tr><td class="vers" colspan="4"><span class="o">${IX.blocked.length} releases before 1.14.4</span> <span class="small">not indexed: ${esc(IX.blocked[0].reason)}</span></td></tr>`;
  $("#families").querySelectorAll("button[data-hub]").forEach(b => b.onclick = () => { $("#from").value = b.dataset.hub; if ($("#to").value === b.dataset.hub) $("#to").value = V[V.length - 1]; render(); window.scrollTo({ top: 0, behavior: "smooth" }); });
  $("#famrule").textContent = IX.families.rule + " " + IX.families.hubRule;
}

function how() {
  const items = [
    `Every release from 1.14.4 to ${esc(V[V.length - 1])} (${V.length} releases, ${IX.steps.length} updates) was diffed against the next one, in Mojang's own names. Releases up to 1.21.11 are read from the game jar plus Mojang's published mappings; 26.1 and later ship with readable names, so the jar is read as is.`,
    `A “name that breaks” is a class, method or field a mod can reach (not private) that was removed, renamed, moved or given a new signature. Private members are in the data too, and counted separately, because mixins sometimes target them.`,
    ...IX.matching.map(esc),
    `“Port run” is the best entry in <span class="mono">coverage/ledger.json</span> covering that step: only ports that were actually run count. A run listed “as part of” covered a longer jump that includes the step.`,
    `${IX.blocked.length} older releases (1.0 to 1.14.3) are not indexed: ${esc(IX.blocked[0].reason)}`,
    `Mod counts: ${esc(IX.mods.note)} Counted ${esc(IX.mods.date)}.`,
  ];
  $("#how").innerHTML = items.map(t => `<li>${t}</li>`).join("");
}

// ---- pickers -------------------------------------------------------------------------------
function pickers() {
  const opts = [...V].reverse().map(v => `<option value="${esc(v)}">${esc(v)}${FAM[v].hub === v ? " ★" : ""}</option>`).join("") +
    `<optgroup label="Before 1.14.4: no official names">` + IX.blocked.slice().reverse().map(b => `<option value="${esc(b.id)}">${esc(b.id)}</option>`).join("") + `</optgroup>`;
  $("#from").innerHTML = opts; $("#to").innerHTML = opts;
  const h = new URLSearchParams(location.hash.slice(1));
  const ok = v => v && (POS[v] !== undefined || BLOCKED[v]);
  $("#from").value = ok(h.get("from")) ? h.get("from") : "1.20.1";
  $("#to").value = ok(h.get("to")) ? h.get("to") : "1.21.1";
  $("#from").onchange = $("#to").onchange = render;
  window.addEventListener("hashchange", () => {
    const n = new URLSearchParams(location.hash.slice(1));
    if (ok(n.get("from")) && ok(n.get("to")) && (n.get("from") !== $("#from").value || n.get("to") !== $("#to").value)) { $("#from").value = n.get("from"); $("#to").value = n.get("to"); render(); }
  });
  $("#swap").onclick = () => { const f = $("#from").value; $("#from").value = $("#to").value; $("#to").value = f; render(); };
  const quick = [["1.20.1", "1.21.1"], ["1.21.1", V[V.length - 1]], ["1.16.5", "1.20.1"], ["1.21.5", "1.21.6"], ["1.21.6", "1.21.5"]];
  $("#quick").innerHTML = `<span class="small" style="align-self:center">Try:</span>` + quick.map(([a, b]) => `<button type="button" data-a="${a}" data-b="${b}">${a} → ${b}</button>`).join("");
  $("#quick").querySelectorAll("button").forEach(b => b.onclick = () => { $("#from").value = b.dataset.a; $("#to").value = b.dataset.b; render(); });
}

const START = new URLSearchParams(location.hash.slice(1));
pickers(); families(); how(); render();
if (START.get("step") && STEP[START.get("step")]) openStep(START.get("step"), START.get("back") !== "1");
$("#gen").textContent = IX.generated;
</script>
</body></html>
"""

if __name__ == "__main__":
    main()
