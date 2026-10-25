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

