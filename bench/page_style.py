"""Shared look for docs/index.html: warm paper, deep ink, moss/ochre/rust status accents.

Light mode by default (matching the desktop app's warm workshop palette) with a clean dark mode toggle.
No neon glow, no gradient controls, no pill clusters — crisp tables, real charts, and short copy.
"""

STYLE = r"""
:root {
  color-scheme: light;
  --bg:#f6f4ef; --surface:#fdfcf9; --ink:#191816; --ink2:#57544d; --ink3:#858077; --rule:#e4dfd5; --axis:#c7c1b5;
  --head:#eeebe4; --chip:#fdfcf9; --ring:rgba(25,24,22,.09);
  --accent:#2b6cb0; --accent-wash:rgba(43,108,176,.11); --accent-ink:#1e4e8c;
  --hi:rgba(43,108,176,.10); --hi-edge:#2b6cb0; --hi-ink:#191816; --best:#eae6dd;
  --green:#3d7e39; --good-ink:#275c24; --good-bg:rgba(61,126,57,.11);
  --yellow:#c28318; --warn-ink:#8a5a08; --warn-bg:rgba(194,131,24,.12);
  --red:#b84231; --bad-ink:#942f20; --bad-bg:rgba(184,66,49,.11);
  --grey:#c7c1b5; --link:#1e5694;
  --grass:#5d9c3f; --grass2:#4a8232; --grass3:#7bb659; --dirt:#8a5a3b; --dirt2:#6f4630;
  --term:#18191c; --term-ink:#e9e6de; --term-dim:#939088; --term-key:#8fd06a;
  --f-head:"Space Grotesk",system-ui,-apple-system,"Segoe UI",sans-serif;
  --f-body:Inter,system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
  --f-mono:"JetBrains Mono","Cascadia Mono",Consolas,ui-monospace,monospace;
  --f-pixel:"Pixelify Sans","Space Grotesk",system-ui,sans-serif;
}
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) {
  color-scheme: dark;
  --bg:#131311; --surface:#1c1c19; --ink:#efede7; --ink2:#b9b5ab; --ink3:#827e74; --rule:#2d2c28; --axis:#3e3c36;
  --head:#242320; --chip:#1c1c19; --ring:rgba(255,255,255,.09);
  --accent:#4c95e8; --accent-wash:rgba(76,149,232,.16); --accent-ink:#8bc0fa;
  --hi:rgba(76,149,232,.16); --hi-edge:#4c95e8; --hi-ink:#efede7; --best:#292824;
  --green:#5aa854; --good-ink:#7cc976; --good-bg:rgba(90,168,84,.16);
  --yellow:#e0a130; --warn-ink:#f0bd5e; --warn-bg:rgba(224,161,48,.16);
  --red:#d95b49; --bad-ink:#ee8171; --bad-bg:rgba(217,91,73,.16);
  --grey:#4a4842; --link:#7cb4f2;
} }
:root[data-theme="dark"] {
  color-scheme: dark;
  --bg:#131311; --surface:#1c1c19; --ink:#efede7; --ink2:#b9b5ab; --ink3:#827e74; --rule:#2d2c28; --axis:#3e3c36;
  --head:#242320; --chip:#1c1c19; --ring:rgba(255,255,255,.09);
  --accent:#4c95e8; --accent-wash:rgba(76,149,232,.16); --accent-ink:#8bc0fa;
  --hi:rgba(76,149,232,.16); --hi-edge:#4c95e8; --hi-ink:#efede7; --best:#292824;
  --green:#5aa854; --good-ink:#7cc976; --good-bg:rgba(90,168,84,.16);
  --yellow:#e0a130; --warn-ink:#f0bd5e; --warn-bg:rgba(224,161,48,.16);
  --red:#d95b49; --bad-ink:#ee8171; --bad-bg:rgba(217,91,73,.16);
  --grey:#4a4842; --link:#7cb4f2;
}
* { box-sizing:border-box }
html { -webkit-text-size-adjust:100%; scroll-behavior:smooth }
body { margin:0; background:var(--bg); color:var(--ink); font:15.5px/1.55 var(--f-body); -webkit-font-smoothing:antialiased }
main { max-width:1080px; margin:0 auto; padding:0 24px 64px }
a { color:var(--link) }
h1, h2, h3 { font-family:var(--f-head); text-wrap:balance; color:var(--ink) }
h1 { font-size:clamp(32px, 4.4vw, 46px); line-height:1.06; margin:0; letter-spacing:-.028em; font-weight:700 }
h2 { font-size:24px; line-height:1.2; margin:56px 0 8px; letter-spacing:-.02em; font-weight:700; scroll-margin-top:76px }
h3 { font-size:17px; margin:24px 0 6px; font-weight:600; letter-spacing:-.01em; scroll-margin-top:76px }
p { margin:6px 0; color:var(--ink2); max-width:68ch }
code { font-family:var(--f-mono); font-size:.88em }
.top { display:flex; gap:12px; align-items:center; justify-content:space-between; flex-wrap:wrap }
.top nav { display:flex; gap:6px 18px; align-items:center; flex-wrap:wrap; font-size:14px }
.lede { font-size:17px; line-height:1.5; max-width:54ch; margin-top:12px; color:var(--ink2) }
.small { font-size:13px; color:var(--ink3) }
.mono { font-family:var(--f-mono); font-size:.86em }
button { font:inherit; cursor:pointer; border:1px solid var(--rule); background:var(--chip); color:var(--ink); border-radius:6px; padding:6px 12px }
button:hover { border-color:var(--ink3) }
.theme { font-size:13px; padding:4px 10px }
/* tables */
.tbl { width:100%; border-collapse:separate; border-spacing:0; margin-top:12px; font-size:14.5px }
.tbl th { font-weight:600; font-size:13px; color:var(--ink2); text-align:center; padding:9px 10px; border-bottom:1.5px solid var(--axis); vertical-align:bottom }
.tbl th.l, .tbl td.l { text-align:left }
.tbl td { padding:10px 10px; border-bottom:1px solid var(--rule); text-align:center; vertical-align:middle; font-variant-numeric:tabular-nums }
.tbl td.l b { font-weight:600; color:var(--ink) }
.tbl td .sub, .tbl th .sub { display:block; font-size:12.5px; color:var(--ink3); font-weight:400 }
.tbl tr.headrow td { background:var(--head) }
.tbl .hi { background:var(--hi); color:var(--hi-ink); font-weight:550 }
.tbl th.hi { background:transparent; color:var(--ink2); box-shadow:inset 0 -2px 0 var(--hi-edge) }
.tbl .hi .sub { color:var(--ink2) }
.tbl .best { background:var(--best); font-weight:600 }
.dot { display:inline-block; width:8px; height:8px; border-radius:50%; margin-right:6px; vertical-align:0 }
.st-green { background:var(--green) } .st-yellow { background:var(--yellow) } .st-red { background:var(--red) } .st-grey { background:var(--grey) }
.foot { margin-top:48px; padding-top:16px; border-top:1px solid var(--rule); font-size:13px; color:var(--ink3) }
@media (max-width: 720px) {
  body { font-size:15px }
  main { padding:0 16px 40px }
  h1 { font-size:28px }
  h2 { font-size:21px; margin-top:40px }
  .tbl { font-size:14px }
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
