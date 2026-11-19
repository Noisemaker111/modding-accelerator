"""What real mods reference of the game, ported as jars and linked against the next release: the porter's scoreboard and its to-do list.

    python bench/exposure.py jars --loader neoforge|fabric --from 1.21.1 --to 1.21.2 [--save NAME] [--out FILE]
    python bench/exposure.py all [--loader neoforge|fabric] [--only fabric:1.20.1] [--jobs 4] [--save]
    python bench/exposure.py join DIR [--save]
    python bench/exposure.py fetch --loader neoforge|fabric --version 1.21.1 [--repin]
    python bench/exposure.py pin [--loader neoforge|fabric] [--version 1.21.1] [--repin]
    python bench/exposure.py load --loader neoforge --from 1.21.1 --to 1.21.2 [--save NAME]
    python bench/exposure.py steps [--from 1.14.4] [--to 26.3]

The mods are the 150 most downloaded of one loader and release on Modrinth, each in its newest file for that release,
listed once in bench/exposure/mods/LOADER-VERSION.json so every run reads the same jars, here or in the cloud (pin
lists them for every release a loader is read for, without downloading; --repin lists them again). They are downloaded into ~/.mmt/exposure/jars/ and only read, never built or shipped: rules still come only from the game and
the loaders.

jars ports one release's mods to the next with tools/JarPort.java (what mmt port --jar runs) and links every reference
their classes, mixins and access transformers make to the game against the new release, as the JVM and Mixin will. It
prints the mods that link and the references that hold the most back, each with what the game's own code did about it
(tools/Exposure.java: replaced, template, split, linked, redesigned, unused). Fabric mods ship in intermediary names
before 26.1; tools/ModNames.java reads them in Mojang's names first, through the game's own mappings.

all runs jars for every update a loader's jars can be read across (Fabric from 1.14.4, NeoForge from 1.20.5, where it
moved to Mojang's names) and joins them: per update the share of mods that link, in total, and the references that
hold the most mods back across every update. join does the joining for summaries made elsewhere (the cloud run,
tools/cloud-run exposure). --save keeps the table in bench/exposure/all.json.

load is the proof that the list predicts the game: the mods jars says link that need no other mod are ported and
started together on a real server of the new release (mmt pack-check); a failed start drops the mod its log names and
starts again. It reports how many load, and whether any mod failed that jars said links.

steps measures the porter on every update at once, with no mods: GameDiff links each release to the next from the
game's own code, and for every update it counts the classes, methods and fields with no counterpart in the next release (renames,
moves and new signatures are linked and carried), and how many of them have a replacement learned from how the game's
own code changed (call rewrites, call chains, class replacements). A new kind of change the learner understands shows up in every
update here, not only in the one being worked on. Kept in bench/exposure/steps.json.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HOME = Path(os.path.expanduser("~")) / ".mmt" / "exposure"
AGENT = {"User-Agent": "modding-accelerator-exposure/1.0"}
INDEX = ROOT / "coverage" / "changes" / "index.json"
PINS = ROOT / "bench" / "exposure" / "mods"
COUNT = 150
# The first release whose mod jars are read in Mojang's names: Fabric's through intermediary, NeoForge's as shipped
# (before 1.20.5 NeoForge mods name members by SRG ids).
FIRST = {"fabric": "1.14.4", "neoforge": "1.20.5"}
UNOBFUSCATED = "26.1"


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


def project_versions(project):
    """A Modrinth project's versions, newest first, with what choosing a file needs; kept a day, so pinning every release asks once a project."""
    cache = HOME / "modrinth" / f"{project}.json"
    if cache.is_file() and time.time() - cache.stat().st_mtime < 86400:
        try:
            return json.loads(cache.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            pass  # another run is writing it: ask again
    versions = [{"id": v["id"], "published": v["date_published"], "game": v["game_versions"], "loaders": v["loaders"],
                 "files": [{"url": f["url"], "file": f["filename"], "primary": f.get("primary"), "sha1": f["hashes"].get("sha1")}
                           for f in v.get("files", [])]}
                for v in api(f"project/{project}/version", {"include_changelog": "false"})]
    versions.sort(key=lambda v: v["published"], reverse=True)
    cache.parent.mkdir(parents=True, exist_ok=True)
    part = cache.with_name(f"{project}.{os.getpid()}.{id(versions)}.part")
    part.write_text(json.dumps(versions), encoding="utf-8")
    try:
        part.replace(cache)
    except OSError:
        part.unlink(missing_ok=True)  # another run just wrote the same list
    return versions


def pin(loader, version, count=COUNT):
    """The most-downloaded mods of one loader and release, each with its newest primary jar for that release, kept in PINS."""
    mods, offset = [], 0
    while len(mods) < count:
        facets = json.dumps([["project_type:mod"], [f"categories:{loader}"], [f'versions:"{version}"']])  # unquoted, 1.20 is read as a number and matches nothing
        hits = api("search", {"facets": facets, "index": "downloads", "limit": 100, "offset": offset}).get("hits", [])
        if not hits:
            break
        offset += len(hits)
        for hit in hits:
            if len(mods) >= count:
                break
            for v in project_versions(hit["project_id"]):
                if loader not in v["loaders"] or version not in v["game"]:
                    continue
                f = next((f for f in v["files"] if f["primary"] and f["file"].endswith(".jar")), None)
                if f:
                    mods.append({"slug": hit["slug"], "project": hit["project_id"], "version": v["id"], **{k: f[k] for k in ("file", "url", "sha1")}})
                    break
    path = PINS / f"{loader}-{version}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    write_pin(path, {"loader": loader, "version": version, "date": date.today().isoformat(), "mods": mods})
    print(f"pinned {len(mods)} {loader} mods for {version} in {path.relative_to(ROOT)}")
    return path


def write_pin(path, pin):
    """One mod a line, so a repinned release reads as the mods that changed."""
    head = json.dumps({k: v for k, v in pin.items() if k != "mods"})[:-1]
    path.write_text(head + ', "mods": [\n' + ",\n".join(json.dumps(m) for m in pin["mods"]) + "\n]}\n", encoding="utf-8")


def fetch(loader, version, repin=False):
    """The pinned mods of one loader and release, downloaded once into ~/.mmt/exposure/jars/LOADER-VERSION/ (pinned first if they are not)."""
    path = PINS / f"{loader}-{version}.json"
    if repin or not path.is_file():
        pin(loader, version)
    mods = json.loads(path.read_text(encoding="utf-8"))["mods"]
    folder = HOME / "jars" / f"{loader}-{version}"
    folder.mkdir(parents=True, exist_ok=True)

    def one(m):
        target = folder / f"{m['slug']}--{m['file']}"
        if target.is_file():
            return target
        for attempt in range(3):
            try:
                with urllib.request.urlopen(urllib.request.Request(m["url"], headers=AGENT), timeout=300) as r:
                    data = r.read()
                break
            except Exception as error:
                if attempt == 2:
                    print(f"  {m['slug']}: {error}", file=sys.stderr)
                    return None
                time.sleep(5 * (attempt + 1))
        if m.get("sha1") and hashlib.sha1(data).hexdigest() != m["sha1"]:
            print(f"  {m['slug']}: the download is not the pinned file", file=sys.stderr)
            return None
        part = target.with_suffix(".part")
        part.write_bytes(data)
        part.replace(target)
        return target

    with ThreadPoolExecutor(8) as pool:
        jars = [j for j in pool.map(one, mods) if j]
    return jars


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


def updates(loader):
    """Every update from one indexed release to the next whose mod jars are read for this loader."""
    every = [v for v in sorted(releases(), key=vkey) if vkey(v) >= vkey(FIRST[loader])]
    return list(zip(every, every[1:]))


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
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL)
    return target


ASM = [ROOT / "tools" / "perf" / "lib" / "asm-9.9.jar", ROOT / "tools" / "perf" / "lib" / "asm-commons-9.9.jar"]
JAR_SOURCES = ("JarPort.java", "FixExpr.java", "Exposure.java", "GameDiff.java", "VersionMap.java", "ModNames.java")


def jar_tools():
    out = ROOT / ".runs" / "exposure" / "jarport-classes"
    sources = [ROOT / "tools" / n for n in JAR_SOURCES] + [ROOT / "tools" / "cli" / "Json.java"]
    stamp = out / "JarPort.class"
    if not stamp.is_file() or stamp.stat().st_mtime < max(s.stat().st_mtime for s in sources):
        javac = java().replace("java.exe", "javac.exe") if java().endswith("java.exe") else java() + "c"
        subprocess.run([javac, "-encoding", "UTF-8", "-cp", os.pathsep.join(map(str, ASM)), "-d", str(out)] + [str(s) for s in sources], check=True)
    return os.pathsep.join([str(out)] + [str(a) for a in ASM])


def mojang_names(loader, version, jars):
    """The jars in Mojang's names: Fabric's before 26.1 read through intermediary by tools/ModNames.java (kept in
    ~/.mmt/exposure/mojang/ until ModNames or VersionMap changes), every other one as it is. A jar it cannot read is
    left out, with why."""
    if loader != "fabric" or vkey(version) >= vkey(UNOBFUSCATED):
        return jars, {}
    out = HOME / "mojang" / f"{loader}-{version}"
    stamp = out / "tools.txt"
    tools = str(max((ROOT / "tools" / n).stat().st_mtime for n in ("ModNames.java", "Exposure.java", "VersionMap.java")))
    if out.is_dir() and (not stamp.is_file() or stamp.read_text() != tools):
        shutil.rmtree(out)
    out.mkdir(parents=True, exist_ok=True)
    missing = [j for j in jars if not (out / j.name).is_file()]
    failed = {}
    if missing:
        report = out / "report.json"
        subprocess.run([java(), "-Xmx4g", "-cp", jar_tools(), "ModNames", "--version", version, "--out", str(out), "--report", str(report)]
                       + [str(j) for j in missing], check=True, stdout=subprocess.DEVNULL)
        failed = {m["mod"]: m["error"] for m in json.loads(report.read_text(encoding="utf-8"))["mods"] if "error" in m}
        stamp.write_text(tools)
    return [out / j.name for j in jars if (out / j.name).is_file()], failed


def jar_port(loader, frm, to, jars, out_dir, report):
    """Ports jars with tools/JarPort.java into out_dir and returns its report."""
    classpath = jar_tools()
    classes, tools_time = compile_tools()
    route = route_map(frm, to, loader, classes, tools_time)
    out_dir.mkdir(parents=True, exist_ok=True)
    args = [java(), "-Xmx10g", "-cp", classpath, "JarPort", "--from", frm, "--to", to, "--map", str(route), "--fixes", str(ROOT / "knowledge" / "fixes.json"), "--out", str(out_dir),
            "--report", str(report), "--loader", loader]
    done = subprocess.run(args + [str(j) for j in jars], stdout=subprocess.DEVNULL)
    if done.returncode:
        raise SystemExit(f"JarPort failed (exit {done.returncode}); its output is above")
    return json.loads(report.read_text(encoding="utf-8"))


def game_says(loader, frm, to, report):
    """What the game's own code did about each reference JarPort's report ranks (tools/Exposure.java)."""
    classes, _ = compile_tools()
    out = report.with_name("game.json")
    subprocess.run([java(), "-Xmx10g", "-cp", str(classes), "Exposure", "--from", frm, "--to", to, "--report", str(report), "--out", str(out),
                    "--loader", loader], check=True, stdout=subprocess.DEVNULL)
    return json.loads(out.read_text(encoding="utf-8"))


def jars(loader, frm, to, save=None, out=None, quiet=False):
    """The jar scoreboard: what every top mod's classes and mixins reference, linked against the new release as the JVM and Mixin do."""
    mods, unread = mojang_names(loader, frm, fetch(loader, frm))
    work = ROOT / ".runs" / "exposure" / f"jars-{loader}-{frm}-{to}"
    report = jar_port(loader, frm, to, mods, work / "mods", work / "report.json") if mods else {"linked": 0, "mods": [], "unlinkedByMods": []}
    shutil.rmtree(work / "mods", ignore_errors=True)  # only the report is read; load ports the mods it starts itself
    said = game_says(loader, frm, to, work / "report.json") if report["unlinkedByMods"] else {}
    rows = [m for m in report["mods"] if "error" not in m]
    summary = {
        "loader": loader, "from": frm, "to": to, "modsRead": len(rows), "modsThatLink": report["linked"],
        "silentOnly": sum(1 for m in rows if not m["unlinked"] and m["silent"]),
        "brokenUses": sum(r["mods"] for r in report["unlinkedByMods"]),  # distinct references, a mod and its nested jars once
        "unreadable": {**unread, **{m["mod"]: m["error"] for m in report["mods"] if "error" in m}},
        "top": [{**r, **said.get(r["member"], {})} for r in report["unlinkedByMods"][:40]],
    }
    if not quiet:
        print(f"{summary['modsThatLink']}/{summary['modsRead']} {loader} mods link against {to} after the port "
              f"({summary['brokenUses']} broken uses; {summary['silentOnly']} keep an override the game no longer calls)")
        for row in summary["top"][:20]:
            print(f"  {row['mods']:>4} mods  {row['member']}  (game: {row.get('game', '?')})")
    for path in [p for p in (out, ROOT / "bench" / "exposure" / f"{save}.json" if save else None) if p]:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(summary, indent=1) + "\n", encoding="utf-8")
        if not quiet:
            print(f"saved {path}")
    return summary


def every_update(loaders, only, jobs, save):
    """jars for every update of each loader, side by side; joined into one table."""
    todo = [(lo, a, b) for lo in loaders for a, b in updates(lo) if not only or f"{lo}:{a}" in only]
    jar_tools()
    compile_tools()
    out = ROOT / ".runs" / "exposure" / "all"
    out.mkdir(parents=True, exist_ok=True)

    def one(job):
        lo, a, b = job
        started = time.monotonic()
        try:
            s = jars(lo, a, b, out=out / f"{lo}-{a}-{b}.json", quiet=True)
            print(f"  {lo} {a} -> {b}: {s['modsThatLink']}/{s['modsRead']} link ({time.monotonic() - started:.0f} s)", flush=True)
        except (SystemExit, subprocess.CalledProcessError, OSError) as failed:
            print(f"  {lo} {a} -> {b}: failed: {failed}", flush=True)

    # Two updates next to each other share a release, and their first run downloads its files to the same cache: each
    # wave holds updates that share none, so no two runs write one file.
    for loader in loaders:
        for parity in (0, 1):
            wave = [t for t in todo if t[0] == loader and updates(loader).index((t[1], t[2])) % 2 == parity]
            with ThreadPoolExecutor(max(1, jobs)) as pool:
                list(pool.map(one, wave))
    return join(out, save)


def join(folder, save):
    """One table from per-update summaries: each update's share of mods that link, the total, and what holds the most back."""
    rows = []
    for f in sorted(Path(folder).rglob("*.json")):
        s = json.loads(f.read_text(encoding="utf-8"))
        if {"loader", "from", "to", "modsRead"} <= s.keys():
            rows.append(s)
    order = {(lo, *u): i for lo in FIRST for i, u in enumerate(updates(lo))}
    rows.sort(key=lambda s: (s["loader"], order.get((s["loader"], s["from"], s["to"]), 999)))
    total = {"mods": sum(s["modsRead"] for s in rows), "link": sum(s["modsThatLink"] for s in rows)}
    loaders = {}
    for s in rows:
        t = loaders.setdefault(s["loader"], {"updates": 0, "mods": 0, "link": 0})
        t["updates"] += 1
        t["mods"] += s["modsRead"]
        t["link"] += s["modsThatLink"]
    top = sorted(({"loader": s["loader"], "update": f"{s['from']} -> {s['to']}", **r} for s in rows for r in s["top"]),
                 key=lambda r: -r["mods"])[:60]
    missing = [f"{lo} {a} -> {b}" for lo in FIRST for a, b in updates(lo) if not any(s["loader"] == lo and s["from"] == a for s in rows)]
    table = {
        "date": date.today().isoformat(), "total": total, "loaders": loaders, "missing": missing,
        "notRead": "NeoForge 1.20.2 to 1.20.5: its mods name game members by SRG ids, not yet read in Mojang's names",
        "updates": [{k: s[k] for k in ("loader", "from", "to", "modsRead", "modsThatLink", "brokenUses")} for s in rows],
        "top": top,
    }
    share = total["link"] / total["mods"] if total["mods"] else 0
    print(f"| Loader | Update | Mods that link |\n|---|---|---|")
    for s in rows:
        print(f"| {s['loader']} | {s['from']} -> {s['to']} | {s['modsThatLink']}/{s['modsRead']} |")
    print(f"\n{total['link']} of {total['mods']} mod ports link ({share:.1%}) across {len(rows)} updates"
          + "".join(f"; {lo} {t['link']}/{t['mods']}" for lo, t in loaders.items()) + (f"; not run: {', '.join(missing)}" if missing else ""))
    print("\nWhat holds the most mods back, every update:")
    for r in top[:15]:
        print(f"  {r['mods']:>4} mods  {r['loader']} {r['update']}  {r['member']}  (game: {r.get('game', '?')})")
    if save:
        path = ROOT / "bench" / "exposure" / "all.json"
        path.write_text(json.dumps(table, indent=1) + "\n", encoding="utf-8")
        print(f"saved {path}")
    return table


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


def mod_ids(jar):
    """The mod ids a NeoForge or Forge jar declares."""
    import tomllib
    import zipfile
    with zipfile.ZipFile(jar) as z:
        for name in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
            if name in z.namelist():
                try:
                    return {m.get("modId") for m in tomllib.loads(z.read(name).decode("utf-8", "replace")).get("mods", [])}
                except tomllib.TOMLDecodeError:
                    return set()
    return set()


def has_code(jar):
    """Whether any class of the jar (nested jars aside) names the game."""
    import zipfile
    with zipfile.ZipFile(jar) as z:
        return any(n.endswith(".class") and b"net/minecraft/" in z.read(n) for n in z.namelist())


def load(loader, frm, to, save):
    """The mods the jar scoreboard says link that need no other mod, ported as jars and started on a real server of the new release.

    One start holds them all; a start that fails drops the jar its log names and starts again, until a start opens a world,
    places every block item and opens it again. Each mod ends loaded, failed (with why) or left out as client-only."""
    report = ROOT / ".runs" / "exposure" / f"jars-{loader}-{frm}-{to}" / "report.json"
    if not report.is_file():
        jars(loader, frm, to)
    linked = [m["mod"] for m in json.loads(report.read_text(encoding="utf-8"))["mods"] if "error" not in m and not m["unlinked"]]
    folder = HOME / "jars" / f"{loader}-{frm}"
    chosen = [folder / name for name in linked if standalone(folder / name)]
    pack = ROOT / ".runs" / "exposure" / f"load-{loader}-{frm}-{to}"
    if pack.exists():
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
                top = s.get("jar", "").split("!")[0] or next((n for n in present if s.get("mod") in mod_ids(pack / "mods" / n)), "")
                if top in present:
                    blamed.setdefault(top, "; ".join(s.get("why", []))[:400])
        if not blamed:
            print(res.get("details", done.stdout[-2000:]))
            raise SystemExit(f"a start failed and named no mod; receipt {result.get('receipt')}")
        for name, why in blamed.items():
            verdicts[name] = "failed: " + why
            (pack / "mods" / name).rename(pack / "dropped" / name)
            print(f"  {name}: failed")
    # A failed start can name a bystander along with the mod at fault, so each dropped mod that links gets one more start
    # with the mods that loaded; it counts as loaded only if that start opens a world too.
    if result.get("ok"):
        for name in sorted(n for n, v in verdicts.items() if v.startswith("failed") and links.get(n)):
            (pack / "dropped" / name).rename(pack / "mods" / name)
            done = subprocess.run([mmt, "pack-check", "--pack", str(pack), "--minecraft", to, "--loader", loader, "--json"],
                                  capture_output=True, text=True, encoding="utf-8", errors="replace")
            start = done.stdout.find("{")
            again = json.loads(done.stdout[start:]) if start >= 0 else {}
            left_out = again.get("result", {}).get("game", {}).get("leftOut") or {}
            if name in left_out:
                verdicts[name] = "client-only: " + str(left_out[name])[:200]
                (pack / "mods" / name).rename(pack / "dropped" / name)
            elif again.get("ok"):
                verdicts[name] = "loaded"
                print(f"  {name}: loads with the others on its own start")
            else:
                (pack / "mods" / name).rename(pack / "dropped" / name)
    loaded = [n for n, v in verdicts.items() if v == "loaded"]
    summary = {
        "loader": loader, "from": frm, "to": to, "mods": len(chosen), "loaded": len(loaded),
        "linkedAndLoaded": sum(1 for n in loaded if links.get(n)), "linked": sum(links.values()),
        "failedButLinked": sorted(n for n, v in verdicts.items() if v.startswith("failed") and links.get(n) and has_code(folder / n)),
        # A mod with no game code links trivially; what fails it is its data, which the link check does not read.
        "failedDataOnly": sorted(n for n, v in verdicts.items() if v.startswith("failed") and links.get(n) and not has_code(folder / n)),
        "verdicts": verdicts,
    }
    print(f"{summary['loaded']}/{summary['mods']} load on a real {to} server; {summary['linked']} link, "
          f"{summary['linkedAndLoaded']} of them load; failed although they link: {summary['failedButLinked'] or 'none'}"
          + (f"; failed on their data, with no game code: {summary['failedDataOnly']}" if summary["failedDataOnly"] else ""))
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
    # Rows per kind of replacement, and each member with no counterpart counted once whatever gave it one ("learned"
    # also lists members whose signature changed: those have a counterpart, so they are not in "gone").
    learned, members = {}, {}
    for section in ("rewrites", "chains", "classRewrites", "learned"):
        rows_key = "learnerRows" if section == "learned" else section  # "learned" is the per-update member total
        for r in route.get(section, []):
            learned.setdefault(r["step"], {}).setdefault(rows_key, 0)
            learned[r["step"]][rows_key] += 1
            if not r.get("linked"):
                members.setdefault(r["step"], set()).add((r.get("owner", ""), r["from"], r.get("desc", "")))
    rows, gone, taught = [], 0, 0
    for step, line in route.get("hops", {}).items():
        m = HOP.search(line)
        if not m:
            continue
        n = list(map(int, m.groups()))
        unlinked = (n[1] - n[0]) + (n[3] - n[2]) + (n[5] - n[4])
        learnt = len(members.get(step, ()))
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
    f.add_argument("--loader", required=True, choices=sorted(FIRST))
    f.add_argument("--version", required=True)
    f.add_argument("--repin", action="store_true", help="list the most-downloaded mods again instead of the pinned ones")
    for name in ("jars", "load"):
        j = sub.add_parser(name)
        j.add_argument("--loader", required=True, choices=sorted(FIRST))
        j.add_argument("--from", dest="frm", required=True)
        j.add_argument("--to", required=True)
        j.add_argument("--save")
        if name == "jars":
            j.add_argument("--out", type=Path, help="write the summary to this file")
    ls = sub.add_parser("updates", help="every LOADER:FROM:TO all runs, as JSON (the cloud run's matrix)")
    ls.add_argument("--loader", choices=sorted(FIRST))
    ls.add_argument("--only", help="LOADER:FROM,...")
    pn = sub.add_parser("pin")
    pn.add_argument("--loader", choices=sorted(FIRST), help="one loader (default both)")
    pn.add_argument("--version", help="one release (default every one the loader is read for)")
    pn.add_argument("--repin", action="store_true", help="list again releases already pinned")
    al = sub.add_parser("all")
    al.add_argument("--loader", choices=sorted(FIRST), help="one loader (default both)")
    al.add_argument("--only", help="LOADER:FROM,... the updates to run (default every one)")
    al.add_argument("--jobs", type=int, default=4, help="updates run side by side (each JarPort takes up to 10 GB)")
    al.add_argument("--save", action="store_true", help="keep the table in bench/exposure/all.json")
    jo = sub.add_parser("join")
    jo.add_argument("folder")
    jo.add_argument("--save", action="store_true")
    st = sub.add_parser("steps")
    st.add_argument("--from", dest="frm", default="1.14.4")
    st.add_argument("--to", default="26.3")
    a = p.parse_args()
    if a.command == "steps":
        steps(a.frm, a.to)
    elif a.command == "jars":
        jars(a.loader, a.frm, a.to, a.save, a.out)
    elif a.command == "updates":
        only = {x.strip() for x in a.only.split(",")} if a.only else None
        print(json.dumps([f"{lo}:{x}:{y}" for lo in ([a.loader] if a.loader else sorted(FIRST)) for x, y in updates(lo)
                          if not only or f"{lo}:{x}" in only]))
    elif a.command == "load":
        load(a.loader, a.frm, a.to, a.save)
    elif a.command == "all":
        only = {x.strip() for x in a.only.split(",")} if a.only else None
        every_update([a.loader] if a.loader else sorted(FIRST), only, a.jobs, a.save)
    elif a.command == "pin":
        for lo in [a.loader] if a.loader else sorted(FIRST):
            for v in [a.version] if a.version else [frm for frm, _ in updates(lo)]:
                if a.repin or not (PINS / f"{lo}-{v}.json").is_file():
                    pin(lo, v)
    elif a.command == "join":
        join(a.folder, a.save)
    else:
        print(f"{len(fetch(a.loader, a.version, a.repin))} {a.loader} mods for {a.version} in {HOME / 'jars' / f'{a.loader}-{a.version}'}")


if __name__ == "__main__":
    main()
