"""The progress board now lives on the bench page (docs/index.html, made by bench/bench_page.py).

This keeps the old command and the old link working: it renders the bench page and writes
docs/progress.html as a pointer to it.

    python bench/progress.py
"""
import runpy
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
runpy.run_path(str(ROOT / "bench" / "bench_page.py"), run_name="__main__")
(ROOT / "docs" / "progress.html").write_text("""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Moved to the bench page</title><meta http-equiv="refresh" content="0; url=index.html#runs">
<style>body{font:17px/1.5 system-ui,sans-serif;margin:40px 16px;background:#fff;color:#111}@media (prefers-color-scheme:dark){body{background:#121212;color:#eee}a{color:#7fb0ff}}</style>
</head><body><p>The progress board is now part of the <a href="index.html#runs">bench page</a>. The route between versions is on the <a href="porting-map.html">porting map</a>.</p></body></html>
""", encoding="utf-8")
print("wrote", ROOT / "docs" / "progress.html")
