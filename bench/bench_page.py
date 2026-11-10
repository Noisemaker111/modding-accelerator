"""Render docs/index.html: current usage links and the interactive porting map.

Plans, dated status and run history belong in PRs/issues, not generated documentation.
The coverage records remain inputs to the porting tool and offline bench checks.

    python bench/bench_page.py
"""
import argparse
import re
import sys
from html import escape
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from page_style import STYLE, THEME_JS  # noqa: E402
import measures as M  # noqa: E402
import viz  # noqa: E402
from porting_map import MAP_CSS, MAP_BODY, section, stonecutter_items  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "index.html"
_, _, MAP_JS = section()


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


def page(book):
    """Current reference and porting tool; plans and dated reports belong in PRs/issues."""
    chart = viz.columns(stonecutter_items(), "Stonecutter switches",
                        '<span><i class="k-col"></i>A rule was learned from these switches</span><span><i class="k-col off"></i>No rule yet</span>')
    html = TEMPLATE
    for key, value in {
        "/*STYLE*/": STYLE + viz.VIZ_CSS + MAP_CSS + PAGE_CSS,
        "/*THEME*/": THEME_JS, "/*VIZ*/": viz.VIZ_JS, "/*MAP*/": MAP_JS,
        "{VERSION}": escape(version()), "{TERMINAL}": terminal(book),
        "{PORTING}": MAP_BODY.replace("{SC_CHART}", chart),
    }.items():
        html = html.replace(key, value)
    return html


def main(argv=None):
    ap = argparse.ArgumentParser(description="Write docs/index.html (current reference and porting map).")
    ap.parse_args(argv)
    html = page(M.Book())
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
<meta name="description" content="modaccel tests a Minecraft mod in seconds, ports it to any version and makes modpacks work together. Current usage and the interactive porting map.">
<link rel="icon" type="image/svg+xml" href="logo.svg">
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&family=JetBrains+Mono:wght@400;500&family=Pixelify+Sans:wght@500;600&family=Space+Grotesk:wght@500;600;700&display=swap">
<style>
/*STYLE*/
</style></head><body>
<main id="top">
<header class="top">
  <a class="brand" href="#top"><span class="logo" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i></span>modaccel</a>
  <nav><a href="#porting">Porting map</a><a href="https://github.com/Noisemaker111/modding-accelerator#readme">README</a><a href="https://github.com/Noisemaker111/modding-accelerator/issues">Issues</a><a href="https://github.com/Noisemaker111/modding-accelerator">GitHub</a><button class="theme" id="theme" type="button">Dark</button></nav>
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

<section id="porting"><h2>Porting map</h2>
<p>Choose versions to inspect the route and known API changes. See the <a href="https://github.com/Noisemaker111/modding-accelerator#readme">README</a> for current capabilities and limits. Plans, status and findings live in <a href="https://github.com/Noisemaker111/modding-accelerator/pulls">PRs</a> and <a href="https://github.com/Noisemaker111/modding-accelerator/issues">issues</a>.</p>
{PORTING}</section>
<p class="foot">Generated from the coverage records by <span class="mono">bench/bench_page.py</span> and <span class="mono">bench/porting_map.py</span>.</p>
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
