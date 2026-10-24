# Benchmarks

- `data.json` holds every number quoted in the README picture, with `n` where it was repeated.
- `charts.ps1` renders `benchmarks.html` (self-contained, inline SVG, no CDN) and the README
  screenshot comes from headless Chrome:

```
pwsh -NoProfile -File bench/charts.ps1
& "C:\Program Files\Google\Chrome\Application\chrome.exe" --headless=new --disable-gpu `
  --hide-scrollbars --window-size=1000,2100 `
  --screenshot="$PWD\bench\benchmarks.png" "file:///$($PWD.Path -replace '\\','/')/bench/benchmarks.html"
```

Rules for changing a number:

1. Run the tier (`.runs/bench.jsonl` for repeated runs, `.runs/index.jsonl` for one-offs).
2. Put the measured value in `data.json` with `n`.
3. Regenerate the page and the PNG, and commit both.
4. Never mix warm and cold in one row; say which is which.

`port/` is the real-mod version-bump measurement (own `results.json` and `SUMMARY.md`).
The cloned mods and their build logs are ignored on purpose; only the results belong in the repo.
