"""What real mods use of the game, and what of it a port would carry over: the porter's scoreboard and its to-do list.

    python bench/exposure.py fetch --loader neoforge --version 1.21.1 [--count 200]
    python bench/exposure.py run --loader neoforge --from 1.21.1 --to 1.21.2 [--count 200] [--save NAME]
    python bench/exposure.py jars --loader neoforge --from 1.21.1 --to 1.21.2 [--save NAME]
    python bench/exposure.py load --loader neoforge --from 1.21.1 --to 1.21.2 [--save NAME]
    python bench/exposure.py steps [--from 1.14.4] [--to 26.3]

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

jars ports the same jars with tools/JarPort.java (what mmt port --jar runs) and links every reference their classes,
mixins and access transformers make to the game against the new release, as the JVM and Mixin will: a field whose type
changed, a mixin's @Shadow field that is gone, an injection whose target took other parameters all count, which run
cannot see. It prints the mods that link and the references that hold the most mods back. It is the stricter number.

load is the proof that a list predicts the game: the mods run clears that need no other mod are ported as jars and
started together on a real server of the new release (mmt pack-check); a failed start drops the mod its log names and
starts again. It reports how many load, and whether any mod failed that jars said links.

steps measures the porter on every update at once, with no mods: GameDiff links each release to the next from the
game's own code, and for every update it counts the classes, methods and fields with no counterpart in the next release (renames,
moves and new signatures are linked and carried), and how many of them have a replacement learned from how the game's
own code changed (call rewrites, call chains, class replacements). A new kind of change the learner understands shows up in every
update here, not only in the one being worked on. Kept in bench/exposure/steps.json.
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
    done = subprocess.run(args + [str(j) for j in jars])
    if done.returncode:
        raise SystemExit(f"Exposure failed (exit {done.returncode}); its output is above")
    report = json.loads(out.read_text(encoding="utf-8"))
    total = sum(r.get("gameUses", 0) for r in report["mods"])
    carried = sum(r.get("gameUses", 0) - len(r.get("broken", [])) for r in report["mods"])
    summary = {
        "loader": loader, "from": frm, "to": to,
        "modsRead": report["modsRead"], "modsWithNothingBroken": report["modsWithNothingBroken"],
        "usesCarried": round(carried / total, 4) if total else 1.0,
        "gameEvidence": report.get("gameEvidence", {}),
        "signatureChanges": dict(list(report.get("signatureChanges", {}).items())[:30]),
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


ASM = [ROOT / "tools" / "perf" / "lib" / "asm-9.9.jar", ROOT / "tools" / "perf" / "lib" / "asm-commons-9.9.jar"]


def jar_tools():
    out = ROOT / ".runs" / "exposure" / "jarport-classes"
    sources = [ROOT / "tools" / n for n in ("JarPort.java", "FixExpr.java", "Exposure.java", "GameDiff.java", "VersionMap.java")] + [ROOT / "tools" / "cli" / "Json.java"]
    stamp = out / "JarPort.class"
    if not stamp.is_file() or stamp.stat().st_mtime < max(s.stat().st_mtime for s in sources):
        javac = java().replace("java.exe", "javac.exe") if java().endswith("java.exe") else java() + "c"
        subprocess.run([javac, "-encoding", "UTF-8", "-cp", os.pathsep.join(map(str, ASM)), "-d", str(out)] + [str(s) for s in sources], check=True)
    return os.pathsep.join([str(out)] + [str(a) for a in ASM])


def jar_port(loader, frm, to, jars, out_dir, report):
    """Ports jars with tools/JarPort.java into out_dir and returns its report."""
    classpath = jar_tools()
    classes, tools_time = compile_tools()
    route = route_map(frm, to, loader, classes, tools_time)
    out_dir.mkdir(parents=True, exist_ok=True)
    args = [java(), "-Xmx10g", "-cp", classpath, "JarPort", "--from", frm, "--to", to, "--map", str(route), "--fixes", str(ROOT / "knowledge" / "fixes.json"), "--out", str(out_dir),
            "--report", str(report)] + (["--loader", loader] if loader == "neoforge" else [])
    done = subprocess.run(args + [str(j) for j in jars], stdout=subprocess.DEVNULL)
    if done.returncode:
        raise SystemExit(f"JarPort failed (exit {done.returncode}); its output is above")
    return json.loads(report.read_text(encoding="utf-8"))


def jars(loader, frm, to, save):
    """The jar scoreboard: what every top mod's classes and mixins reference, linked against the new release as the JVM and Mixin do."""
    folder = HOME / "jars" / f"{loader}-{frm}"
    mods = sorted(folder.glob("*.jar")) if folder.is_dir() else []
    if not mods:
        mods = sorted(fetch(loader, frm, 200).glob("*.jar"))
    out = ROOT / ".runs" / "exposure" / f"jars-{loader}-{frm}-{to}"
    report = jar_port(loader, frm, to, mods, out / "mods", out / "report.json")
    rows = [m for m in report["mods"] if "error" not in m]
    summary = {
        "loader": loader, "from": frm, "to": to, "modsRead": len(rows), "modsThatLink": report["linked"],
        "silentOnly": sum(1 for m in rows if not m["unlinked"] and m["silent"]),
        "top": report["unlinkedByMods"][:40],
    }
    print(f"{summary['modsThatLink']}/{summary['modsRead']} mods link against {to} after the port "
          f"({summary['silentOnly']} of them keep an override the game no longer calls)")
    for row in summary["top"][:20]:
        print(f"  {row['mods']:>4} mods  {row['member']}")
    if save:
        path = ROOT / "bench" / "exposure" / f"{save}.json"
        path.write_text(json.dumps(summary, indent=1) + "\n", encoding="utf-8")
        print(f"saved {path}")
    return summary


def standalone(jar):
    """A NeoForge or Forge mod whose metadata asks for no other mod and no other language loader."""
    import tomllib
    import zipfile
    with zipfile.ZipFile(jar) as z:
        names = [n for n in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml") if n in z.namelist()]
        if not names:
            return False
        try:
            meta = tomllib.loads(z.read(names[0]).decode("utf-8", "replace"))
        except tomllib.TOMLDecodeError:
            return False
    if meta.get("modLoader", "javafml") not in ("javafml", "lowcodefml"):
        return False
    own = {m.get("modId") for m in meta.get("mods", [])}
    for blocks in meta.get("dependencies", {}).values():
        for d in blocks if isinstance(blocks, list) else []:
            kind = str(d.get("type", "required" if d.get("mandatory", True) else "optional")).lower()
            if kind == "required" and d.get("modId") not in ("neoforge", "forge", "minecraft", "java") and d.get("modId") not in own:
                return False
    return True


def load(loader, frm, to, save):
    """The mods the scoreboard clears that need no other mod, ported as jars and started on a real server of the new release.

    One start holds them all; a start that fails drops the jar its log names and starts again, until a start opens a world,
    places every block item and opens it again. Each mod ends loaded, failed (with why) or left out as client-only."""
    exposure = json.loads((ROOT / ".runs" / "exposure" / f"{loader}-{frm}-{to}.json").read_text(encoding="utf-8"))
    folder = HOME / "jars" / f"{loader}-{frm}"
    chosen = [folder / m["mod"] for m in exposure["mods"] if not m.get("broken") and m.get("gameUses") and standalone(folder / m["mod"])]
    pack = ROOT / ".runs" / "exposure" / f"load-{loader}-{frm}-{to}"
    if pack.exists():
        import shutil
        shutil.rmtree(pack)
    report = jar_port(loader, frm, to, chosen, pack / "mods", pack / "report.json")
    links = {m["mod"]: not m.get("unlinked") and "error" not in m for m in report["mods"]}
    (pack / "dropped").mkdir()
    mmt = str(ROOT / ("mmt.cmd" if os.name == "nt" else "mmt"))
    verdicts = {}
    for attempt in range(len(chosen) + 1):
        done = subprocess.run([mmt, "pack-check", "--pack", str(pack), "--minecraft", to, "--loader", loader, "--json"],
                              capture_output=True, text=True, encoding="utf-8", errors="replace")
        text = done.stdout[done.stdout.find("{"):]
        result = json.loads(text) if text else {}
        res = result.get("result", {})
        present = sorted(p.name for p in (pack / "mods").glob("*.jar"))
        for name, why in (res.get("game", {}).get("leftOut") or {}).items():
            verdicts[name] = "client-only: " + str(why)[:200]
        if result.get("ok"):
            for name in present:
                verdicts.setdefault(name, "loaded")
            break
        blamed = {}
        for finding in res.get("findings", []):
            if finding.get("severity") != "problem":
                continue
            for s in finding.get("suspects", []):
                top = s.get("jar", "").split("!")[0]
                if top in present:
                    blamed.setdefault(top, "; ".join(s.get("why", []))[:400])
        if not blamed:
            print(res.get("details", done.stdout[-2000:]))
            raise SystemExit(f"a start failed and named no mod; receipt {result.get('receipt')}")
        for name, why in blamed.items():
            verdicts[name] = "failed: " + why
            (pack / "mods" / name).rename(pack / "dropped" / name)
            print(f"  {name}: failed")
    loaded = [n for n, v in verdicts.items() if v == "loaded"]
    summary = {
        "loader": loader, "from": frm, "to": to, "mods": len(chosen), "loaded": len(loaded),
        "linkedAndLoaded": sum(1 for n in loaded if links.get(n)), "linked": sum(links.values()),
        "failedButLinked": sorted(n for n, v in verdicts.items() if v.startswith("failed") and links.get(n)),
        "verdicts": verdicts,
    }
    print(f"{summary['loaded']}/{summary['mods']} load on a real {to} server; {summary['linked']} link, "
          f"{summary['linkedAndLoaded']} of them load; failed although they link: {summary['failedButLinked'] or 'none'}")
    if save:
        path = ROOT / "bench" / "exposure" / f"{save}.json"
        path.write_text(json.dumps(summary, indent=1) + "\n", encoding="utf-8")
        print(f"saved {path}")
    return summary


HOP = re.compile(r"linked (\d+)/(\d+) classes, (\d+)/(\d+) methods, (\d+)/(\d+) fields")


def steps(frm, to):
    classes, tools_time = compile_tools()
    versions = chain(frm, to)
    target = ROOT / ".runs" / "exposure" / f"steps-{frm}-{to}.json"
    if not target.is_file() or target.stat().st_mtime < tools_time:
        target.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([java(), "-Xmx10g", "-cp", str(classes), "GameDiff", "--chain", ",".join(versions), "--out", str(target)], check=True)
    route = json.loads(target.read_text(encoding="utf-8"))
    learned = {}
    for section in ("rewrites", "chains", "classRewrites"):
        for r in route.get(section, []):
            learned.setdefault(r["step"], {}).setdefault(section, 0)
            learned[r["step"]][section] += 1
    rows, gone, taught = [], 0, 0
    for step, line in route.get("hops", {}).items():
        m = HOP.search(line)
        if not m:
            continue
        n = list(map(int, m.groups()))
        unlinked = (n[1] - n[0]) + (n[3] - n[2]) + (n[5] - n[4])
        learnt = sum(learned.get(step, {}).values())
        if unlinked:
            rows.append({"step": step, "gone": unlinked, "learned": learnt, **learned.get(step, {})})
        gone, taught = gone + unlinked, taught + learnt
    summary = {"from": frm, "to": to, "updates": len(route.get("hops", {})), "gone": gone, "learned": taught, "steps": rows}
    for r in rows:
        print(f"  {r['step']:<22} {r['gone']:>6} with no counterpart  {r['learned']:>4} replacements learned")
    print(f"{summary['updates']} updates: {gone} classes, methods and fields have no counterpart in the next release; "
          f"the game's own code gave {taught} of them a replacement the porter applies")
    out = ROOT / "bench" / "exposure" / "steps.json"
    out.write_text(json.dumps(summary, indent=1) + "\n", encoding="utf-8")
    print(f"saved {out}")
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
    for name in ("jars", "load"):
        j = sub.add_parser(name)
        j.add_argument("--loader", required=True)
        j.add_argument("--from", dest="frm", required=True)
        j.add_argument("--to", required=True)
        j.add_argument("--save")
    st = sub.add_parser("steps")
    st.add_argument("--from", dest="frm", default="1.14.4")
    st.add_argument("--to", default="26.3")
    a = p.parse_args()
    if a.command == "steps":
        steps(a.frm, a.to)
    elif a.command == "jars":
        jars(a.loader, a.frm, a.to, a.save)
    elif a.command == "load":
        load(a.loader, a.frm, a.to, a.save)
    elif a.command == "fetch":
        fetch(a.loader, a.version, a.count)
    else:
        run(a.loader, a.frm, a.to, a.count, a.save)


if __name__ == "__main__":
    main()
