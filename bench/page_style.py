"""Shared look for the generated pages (docs/index.html bench page and docs/porting-map.html).

Plain white table, big clear type, thin row lines, one highlighted column; light and dark.
"""

STYLE = r"""
:root {
  --bg:#ffffff; --ink:#111111; --ink2:#4a4a4a; --ink3:#8a8a8a; --rule:#dcdcdc; --head:#f5f6f8; --chip:#ffffff;
  --hi:#a8d4ff; --hi-edge:#3d8bf0; --hi-ink:#06213f; --best:#e3e5e8;
  --green:#1f9d55; --yellow:#e2b200; --red:#d64545; --grey:#b9b9b9; --link:#1f63d6;
}
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) {
  --bg:#121212; --ink:#f1f1f1; --ink2:#c4c4c4; --ink3:#8c8c8c; --rule:#333333; --head:#1b1c1e; --chip:#1d1d1d;
  --hi:#173a63; --hi-edge:#5aa2ff; --hi-ink:#e6f1ff; --best:#2a2c30;
  --green:#3cc17a; --yellow:#f0c534; --red:#ef6b6b; --grey:#555555; --link:#7fb0ff;
} }
:root[data-theme="dark"] {
  --bg:#121212; --ink:#f1f1f1; --ink2:#c4c4c4; --ink3:#8c8c8c; --rule:#333333; --head:#1b1c1e; --chip:#1d1d1d;
  --hi:#173a63; --hi-edge:#5aa2ff; --hi-ink:#e6f1ff; --best:#2a2c30;
  --green:#3cc17a; --yellow:#f0c534; --red:#ef6b6b; --grey:#555555; --link:#7fb0ff;
}
* { box-sizing:border-box }
html { -webkit-text-size-adjust:100% }
body { margin:0; background:var(--bg); color:var(--ink); font:17px/1.45 "Segoe UI",system-ui,-apple-system,Roboto,sans-serif }
main { max-width:1120px; margin:0 auto; padding:28px 16px 48px }
a { color:var(--link) }
h1 { font-size:34px; margin:0; letter-spacing:-.01em; font-weight:600 }
h2 { font-size:24px; margin:40px 0 8px; font-weight:600 }
p { margin:6px 0; color:var(--ink2) }
.top { display:flex; gap:12px; align-items:center; justify-content:space-between; flex-wrap:wrap }
.top nav { display:flex; gap:16px; align-items:center; font-size:15px }
.lede { font-size:18px; max-width:780px; margin-top:10px }
.small { font-size:13.5px; color:var(--ink3) }
.mono { font-family:"Cascadia Mono",Consolas,ui-monospace,monospace; font-size:14px }
button { font:inherit; cursor:pointer; border:1px solid var(--rule); background:var(--chip); color:var(--ink); border-radius:8px; padding:7px 12px }
button:hover { border-color:var(--ink3) }
.theme { font-size:14px; padding:4px 10px }
/* the benchmark table */
.tbl { width:100%; border-collapse:separate; border-spacing:0; margin-top:12px; font-size:18px }
.tbl th { font-weight:500; font-size:18px; text-align:center; padding:14px 10px; border-bottom:1px solid var(--rule); vertical-align:bottom }
.tbl th.l, .tbl td.l { text-align:left }
.tbl td { padding:14px 10px; border-bottom:1px solid var(--rule); text-align:center; vertical-align:middle }
.tbl td.l b { font-weight:500 }
.tbl td .sub, .tbl th .sub { display:block; font-size:14px; color:var(--ink3); font-weight:400 }
.tbl tr.headrow td { background:var(--head) }
.tbl .hi { background:var(--hi); color:var(--hi-ink); border-left:3px solid var(--hi-edge); border-right:3px solid var(--hi-edge); font-weight:600 }
.tbl th.hi { border-top:3px solid var(--hi-edge); border-radius:8px 8px 0 0; background:transparent; color:var(--ink) }
.tbl tr:last-child td.hi { border-bottom:3px solid var(--hi-edge) }
.tbl .hi .sub { color:var(--hi-ink); opacity:.75 }
.tbl .best { background:var(--best); font-weight:600 }
.dot { display:inline-block; width:12px; height:12px; border-radius:50%; margin-right:7px; vertical-align:-1px }
.st-green { background:var(--green) } .st-yellow { background:var(--yellow) } .st-red { background:var(--red) } .st-grey { background:var(--grey) }
.foot { margin-top:40px; padding-top:14px; border-top:1px solid var(--rule); font-size:13.5px; color:var(--ink3) }
@media (max-width: 720px) {
  body { font-size:16px }
  main { padding:18px 16px 40px }
  h1 { font-size:28px }
  h2 { font-size:21px }
  .tbl, .tbl th { font-size:16px }
  /* tables become one card per row on a phone */
  .tbl.stack thead { display:none }
  .tbl.stack, .tbl.stack tbody, .tbl.stack tr, .tbl.stack td { display:block; width:100% }
  .tbl.stack tr { border-bottom:1px solid var(--rule); padding:8px 0 }
  .tbl.stack td { border:0; padding:3px 0; text-align:left }
  .tbl.stack td[data-k]::before { content:attr(data-k) ": "; color:var(--ink3); font-weight:400 }
  .tbl.stack td.hi { border:0; border-left:4px solid var(--hi-edge); padding-left:8px; border-radius:0 }
  .tbl.stack tr:last-child td.hi { border-bottom:0 }
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
