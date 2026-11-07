"""Shared look for every generated page (docs/index.html, the roadmap with the porting map inside it).

Colours are tokens on :root, light and dark, from the validated chart palette: one accent (blue) for
"ours, now", muted grey for plans and context, and the fixed status colours (good, warning, critical)
only for states, always with a word beside them. bench/viz.py draws every number with these tokens.
"""

STYLE = r"""
:root {
  color-scheme: light;
  --bg:#f9f9f7; --surface:#fcfcfb; --ink:#0b0b0b; --ink2:#52514e; --ink3:#898781; --rule:#e1e0d9; --axis:#c3c2b7;
  --head:#f0efec; --chip:#fcfcfb; --ring:rgba(11,11,11,.10);
  --accent:#2a78d6; --accent-wash:rgba(42,120,214,.12); --accent-ink:#1c5cab;
  --hi:rgba(42,120,214,.12); --hi-edge:#2a78d6; --hi-ink:#0b0b0b; --best:#ecebe6;
  --green:#0ca30c; --good-ink:#006300; --yellow:#fab219; --red:#d03b3b; --bad-ink:#b02a2a; --grey:#c3c2b7; --link:#1c5cab;
}
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) {
  color-scheme: dark;
  --bg:#0d0d0d; --surface:#1a1a19; --ink:#f0efec; --ink2:#c3c2b7; --ink3:#898781; --rule:#2c2c2a; --axis:#383835;
  --head:#232321; --chip:#1a1a19; --ring:rgba(255,255,255,.10);
  --accent:#3987e5; --accent-wash:rgba(57,135,229,.18); --accent-ink:#86b6ef;
  --hi:rgba(57,135,229,.18); --hi-edge:#3987e5; --hi-ink:#f0efec; --best:#2a2a28;
  --green:#0ca30c; --good-ink:#0ca30c; --yellow:#fab219; --red:#d03b3b; --bad-ink:#e66767; --grey:#4a4a47; --link:#6da7ec;
} }
:root[data-theme="dark"] {
  color-scheme: dark;
  --bg:#0d0d0d; --surface:#1a1a19; --ink:#f0efec; --ink2:#c3c2b7; --ink3:#898781; --rule:#2c2c2a; --axis:#383835;
  --head:#232321; --chip:#1a1a19; --ring:rgba(255,255,255,.10);
  --accent:#3987e5; --accent-wash:rgba(57,135,229,.18); --accent-ink:#86b6ef;
  --hi:rgba(57,135,229,.18); --hi-edge:#3987e5; --hi-ink:#f0efec; --best:#2a2a28;
  --green:#0ca30c; --good-ink:#0ca30c; --yellow:#fab219; --red:#d03b3b; --bad-ink:#e66767; --grey:#4a4a47; --link:#6da7ec;
}
* { box-sizing:border-box }
html { -webkit-text-size-adjust:100%; scroll-behavior:smooth }
body { margin:0; background:var(--bg); color:var(--ink); font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif }
main { max-width:1160px; margin:0 auto; padding:24px 16px 56px }
a { color:var(--link) }
h1 { font-size:32px; margin:0; letter-spacing:-.01em; font-weight:650 }
h2 { font-size:24px; margin:48px 0 6px; font-weight:650; scroll-margin-top:64px }
h3 { font-size:19px; margin:28px 0 6px; font-weight:600 }
p { margin:6px 0; color:var(--ink2) }
code { font-family:"Cascadia Mono",Consolas,ui-monospace,monospace; font-size:.9em }
.top { display:flex; gap:12px; align-items:center; justify-content:space-between; flex-wrap:wrap }
.top nav { display:flex; gap:6px 14px; align-items:center; flex-wrap:wrap; font-size:14.5px }
.lede { font-size:17.5px; max-width:820px; margin-top:10px; color:var(--ink2) }
.small { font-size:13.5px; color:var(--ink3) }
.mono { font-family:"Cascadia Mono",Consolas,ui-monospace,monospace; font-size:13.5px }
button { font:inherit; cursor:pointer; border:1px solid var(--rule); background:var(--chip); color:var(--ink); border-radius:8px; padding:7px 12px }
button:hover { border-color:var(--ink3) }
.theme { font-size:14px; padding:4px 10px }
/* tables */
.tbl { width:100%; border-collapse:separate; border-spacing:0; margin-top:12px; font-size:16px }
.tbl th { font-weight:500; font-size:14px; color:var(--ink2); text-align:center; padding:10px 10px; border-bottom:1px solid var(--axis); vertical-align:bottom }
.tbl th.l, .tbl td.l { text-align:left }
.tbl td { padding:12px 10px; border-bottom:1px solid var(--rule); text-align:center; vertical-align:middle; font-variant-numeric:tabular-nums }
.tbl td.l b { font-weight:550 }
.tbl td .sub, .tbl th .sub { display:block; font-size:13.5px; color:var(--ink3); font-weight:400 }
.tbl tr.headrow td { background:var(--head) }
.tbl .hi { background:var(--hi); color:var(--hi-ink); font-weight:550 }
.tbl th.hi { background:transparent; color:var(--ink2); box-shadow:inset 0 -3px 0 var(--hi-edge) }
.tbl .hi .sub { color:var(--ink2) }
.tbl .best { background:var(--best); font-weight:600 }
.dot { display:inline-block; width:10px; height:10px; border-radius:50%; margin-right:7px; vertical-align:0 }
.st-green { background:var(--green) } .st-yellow { background:var(--yellow) } .st-red { background:var(--red) } .st-grey { background:var(--grey) }
.foot { margin-top:48px; padding-top:14px; border-top:1px solid var(--rule); font-size:13.5px; color:var(--ink3) }
@media (max-width: 720px) {
  body { font-size:15.5px }
  main { padding:16px 16px 40px }
  h1 { font-size:26px }
  h2 { font-size:21px }
  .tbl { font-size:15px }
  /* tables become one card per row on a phone */
  .tbl.stack thead { display:none }
  .tbl.stack, .tbl.stack tbody, .tbl.stack tr, .tbl.stack td { display:block; width:100% }
  .tbl.stack tr { border-bottom:1px solid var(--rule); padding:8px 0 }
  .tbl.stack td { border:0; padding:3px 0; text-align:left }
  .tbl.stack td[data-k]::before { content:attr(data-k) ": "; color:var(--ink3); font-weight:400 }
  .tbl.stack td.hi { background:none; border-left:3px solid var(--hi-edge); padding-left:8px }
}
"""

THEME_JS = r"""
(function () {
  const btn = document.getElementById("theme"), root = document.documentElement;
  let saved = null; try { saved = localStorage.getItem("ma-theme"); } catch (e) {}
  if (saved) root.dataset.theme = saved;
  const dark = () => root.dataset.theme ? root.dataset.theme === "dark" : matchMedia("(prefers-color-scheme: dark)").matches;
  const label = () => btn.textContent = dark() ? "Light" : "Dark";
  btn.onclick = () => { root.dataset.theme = dark() ? "light" : "dark"; try { localStorage.setItem("ma-theme", root.dataset.theme); } catch (e) {} label(); };
  label();
})();
"""
