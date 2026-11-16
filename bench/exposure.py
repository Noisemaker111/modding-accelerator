"""What real mods use of the game, and what of it a port would carry over: the porter's scoreboard and its to-do list.

    python bench/exposure.py fetch --loader neoforge --version 1.21.1 [--count 200]
    python bench/exposure.py run --loader neoforge --from 1.21.1 --to 1.21.2 [--count 200] [--save NAME]

fetch downloads the most downloaded mods of one loader and game version from Modrinth (the newest release of each
for that version) into ~/.mmt/exposure/jars/LOADER-VERSION/. They are read, never built or shipped, and only to count
which parts of the game mods use: rules still come only from the game and the loaders.

run reads them with tools/Exposure.java against GameDiff's route map between the two releases (made once, kept in
~/.mmt/exposure/maps/): for every game class, method and field the mods use, whether a port carries it over
(unchanged, renamed by the map, or rewritten from the game's own code) or not yet (another signature, or removed).
It prints the share of mods with nothing left to carry and the members that break the most mods, and writes the full
report to .runs/exposure/. --save NAME also keeps the summary in bench/exposure/NAME.json, so a fix is measured by
how many mods it frees.

Only jars in the old release's Mojang names are read: NeoForge from 1.20.5, and any loader from 26.1.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HOME = Path(os.path.expanduser("~")) / ".mmt" / "exposure"
AGENT = {"User-Agent": "modding-accelerator-exposure/1.0"}
INDEX = ROOT / "coverage" / "changes" / "index.json"


def api(path, params=None):
    url = "https://api.modrinth.com/v2/" + path + ("?" + urllib.parse.urlencode(params) if params else "")
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=AGENT), timeout=60) as r:
                return json.load(r)
        except Exception as error:  # a 429 or a dropped connection: back off and try again
            if attempt == 3:
                raise
            time.sleep(5 * (attempt + 1))


def fetch(loader, version, count):
    folder = HOME / "jars" / f"{loader}-{version}"
    folder.mkdir(parents=True, exist_ok=True)
    have, offset = 0, 0
    while have < count:
        facets = json.dumps([["project_type:mod"], [f"categories:{loader}"], [f"versions:{version}"]])
        hits = api("search", {"facets": facets, "index": "downloads", "limit": 100, "offset": offset}).get("hits", [])
        if not hits:
            break
        offset += len(hits)
        for hit in hits:
            if have >= count:
                break
            slug = hit["slug"]
            existing = list(folder.glob(slug + "--*.jar"))
            if existing:
                have += 1
                continue
            versions = api(f"project/{hit['project_id']}/version",
                           {"loaders": json.dumps([loader]), "game_versions": json.dumps([version])})
            files = [f for v in versions for f in v.get("files", []) if f.get("primary") and f["filename"].endswith(".jar")]
            if not files:
                continue
            target = folder / f"{slug}--{files[0]['filename']}"
            try:
                with urllib.request.urlopen(urllib.request.Request(files[0]["url"], headers=AGENT), timeout=120) as r:
                    target.write_bytes(r.read())
                have += 1
            except Exception as error:
                print(f"  {slug}: {error}", file=sys.stderr)
            time.sleep(0.2)
    print(f"{have} {loader} mods for {version} in {folder}")
    return folder


def releases():
    data = json.loads(INDEX.read_text(encoding="utf-8"))
    return [v["id"] for v in data["allVersions"] if v.get("status") == "indexed"]


def vkey(v):
    return tuple(int(x) for x in re.findall(r"\d+", v))


def chain(frm, to):
    """Every indexed release from one to the other, in the direction of the port."""
    all_versions = sorted(releases(), key=vkey)
    lo, hi = sorted([frm, to], key=vkey)
    span = [v for v in all_versions if vkey(lo) <= vkey(v) <= vkey(hi)]
    return span if vkey(frm) <= vkey(to) else list(reversed(span))


def java():
    jdk = Path(os.path.expanduser("~")) / ".mmt" / "jdks"
    for candidate in sorted(jdk.glob("temurin-25*"), reverse=True) + sorted(jdk.glob("temurin-2[5-9]*"), reverse=True):
        exe = candidate / "bin" / ("java.exe" if os.name == "nt" else "java")
        if exe.is_file():
            return str(exe)
    return "java"


def compile_tools():
    out = ROOT / ".runs" / "exposure" / "classes"
    sources = [ROOT / "tools" / n for n in ("Exposure.java", "GameDiff.java", "VersionMap.java")] + [ROOT / "tools" / "cli" / "Json.java"]
    newest = max(s.stat().st_mtime for s in sources)
    stamp = out / "Exposure.class"
    if not stamp.is_file() or stamp.stat().st_mtime < newest:
        javac = java().replace("java.exe", "javac.exe") if java().endswith("java.exe") else java() + "c"
        subprocess.run([javac, "-encoding", "UTF-8", "-d", str(out)] + [str(s) for s in sources], check=True)
    # The route map only changes with GameDiff or VersionMap, not with Exposure itself.
    differ = max((ROOT / "tools" / n).stat().st_mtime for n in ("GameDiff.java", "VersionMap.java"))
    return out, differ


def route_map(frm, to, loader, classes, tools_time):
    maps = HOME / "maps"
    maps.mkdir(parents=True, exist_ok=True)
    target = maps / f"{loader}-{frm}-{to}.json"
    if target.is_file() and target.stat().st_mtime >= tools_time:
        return target
    args = [java(), "-Xmx10g", "-cp", str(classes), "GameDiff", "--chain", ",".join(chain(frm, to)), "--out", str(target)]
    if loader == "neoforge":
        args += ["--loader", "neoforge"]
    subprocess.run(args, check=True)
    return target


def run(loader, frm, to, count, save):
    folder = HOME / "jars" / f"{loader}-{frm}"
    jars = sorted(folder.glob("*.jar"))[: count or None] if folder.is_dir() else []
    if not jars:
        folder = fetch(loader, frm, count or 200)
        jars = sorted(folder.glob("*.jar"))
    classes, tools_time = compile_tools()
    route = route_map(frm, to, loader, classes, tools_time)
    out = ROOT / ".runs" / "exposure" / f"{loader}-{frm}-{to}.json"
    args = [java(), "-Xmx10g", "-cp", str(classes), "Exposure", "--from", frm, "--to", to, "--map", str(route), "--out", str(out),
            "--rules", str(ROOT / "knowledge" / "fixes.json")]
    if loader == "neoforge":
        args += ["--loader", "neoforge"]
    subprocess.run(args + [str(j) for j in jars], check=True)
    report = json.loads(out.read_text(encoding="utf-8"))
    total = sum(r.get("gameUses", 0) for r in report["mods"])
    carried = sum(r.get("gameUses", 0) - len(r.get("broken", [])) for r in report["mods"])
    summary = {
        "loader": loader, "from": frm, "to": to,
        "modsRead": report["modsRead"], "modsWithNothingBroken": report["modsWithNothingBroken"],
        "usesCarried": round(carried / total, 4) if total else 1.0,
        "top": report["brokenByMods"][:40],
    }
    print(f"{summary['modsWithNothingBroken']}/{summary['modsRead']} mods have nothing left to carry; "
          f"{summary['usesCarried']:.1%} of their game uses carry over")
    if save:
        path = ROOT / "bench" / "exposure" / f"{save}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(summary, indent=1) + "\n", encoding="utf-8")
        print(f"saved {path}")
    return summary


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    f = sub.add_parser("fetch")
    f.add_argument("--loader", required=True)
    f.add_argument("--version", required=True)
    f.add_argument("--count", type=int, default=200)
    r = sub.add_parser("run")
    r.add_argument("--loader", required=True)
    r.add_argument("--from", dest="frm", required=True)
    r.add_argument("--to", required=True)
    r.add_argument("--count", type=int, default=0)
    r.add_argument("--save")
    a = p.parse_args()
    if a.command == "fetch":
        fetch(a.loader, a.version, a.count)
    else:
        run(a.loader, a.frm, a.to, a.count, a.save)


if __name__ == "__main__":
    main()
