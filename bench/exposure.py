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

all runs jars for every update a loader's jars can be read across (Fabric from 1.14.4, NeoForge from its first release,
1.20.2; its mods before 1.20.5 are read through NeoForm's SRG names) and joins them: per update the share of mods that link, in total, and the references that
hold the most mods back across every update. join does the joining for summaries made elsewhere (the cloud run,
tools/cloud-run exposure). --save keeps the table in bench/exposure/all.json.

load is the proof that the list predicts the game: the mods jars says link that need no other mod are ported and
started together on a real server of the new release (mmt pack-check), then in a real client of it (pack-check --side
client: the title screen, a world, the inventory and every creative tab), since a server never runs client code; a
failed start drops the mod its log names and starts again. It reports each mod's server and client verdicts side by
side, and any mod that loaded on the server and failed in the client.

jars also checks each mod's data files against the new release: a built-in game id (an enchantment effect type, a loot
function, a data component) that the old release registers and the new one does not, read from each release's own
data generator report. A server refuses such a file, which the link check cannot see; modsThatLinkAndData counts the
mods that pass both.

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
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HOME = Path(os.path.expanduser("~")) / ".mmt" / "exposure"
AGENT = {"User-Agent": "modding-accelerator-exposure/1.0"}
INDEX = ROOT / "coverage" / "changes" / "index.json"
PINS = ROOT / "bench" / "exposure" / "mods"
COUNT = 150
# The first release each loader's mods are read for, in Mojang's names: Fabric's through intermediary before 26.1,
# NeoForge's (its first release is 1.20.2) through NeoForm's SRG ids before 1.20.5, as shipped after.
FIRST = {"fabric": "1.14.4", "neoforge": "1.20.2"}
UNOBFUSCATED = "26.1"
MOJANG_NEOFORGE = "1.20.5"


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
        part = part_of(target)
        part.write_bytes(data)
        return publish(part, target)

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


def families():
    """Each indexed release's family: releases between which the game changes almost nothing (coverage/changes/index.json)."""
    data = json.loads(INDEX.read_text(encoding="utf-8"))
    return {v: f for f in data["families"]["list"] for v in f["versions"]}


def big(frm, to):
    """An update into another family: the game broke at least 1 in 100 of its names. Half the updates are not, and nearly every mod links across those untouched."""
    family = families()
    return family[frm]["id"] != family[to]["id"]


def jumps(loader):
    """The ports people ask for, which cross several updates: from each family's hub (its release with the most mods)
    to the next family's hub, and from the release with the most mods of all to the newest one. A mod has to get
    through every update on the way, so these are far lower than any one update's count. One update apart, the pair
    is already in updates. Every hub to the newest release was measured once (2026-11-20, run 44821929843): 33 more
    jobs that each read every release on the way, 60% of the run's time, and all but the nearest few under 15 of 147."""
    single = updates(loader)
    read = [frm for frm, _ in single]
    family = families()
    hubs = sorted({f["hub"] for f in family.values() if f["hub"] in read}, key=vkey)
    most = max(hubs, key=lambda hub: family[hub].get("mods", {}).get(hub, 0))
    pairs = list(zip(hubs, hubs[1:])) + [(most, single[-1][1])]
    return [p for p in dict.fromkeys(pairs) if p not in single]


def java():
    jdk = Path(os.path.expanduser("~")) / ".mmt" / "jdks"
    for candidate in sorted(jdk.glob("temurin-25*"), reverse=True) + sorted(jdk.glob("temurin-2[5-9]*"), reverse=True):
        exe = candidate / "bin" / ("java.exe" if os.name == "nt" else "java")
        if exe.is_file():
            return str(exe)
    return "java"


def compile_tools():
    """The class path GameDiff and Exposure run from: the snapshot reads loader jars in Mojang names through ModNames (ASM)."""
    return jar_tools()


# What a route map is built from: it changes with GameDiff or VersionMap, not with Exposure itself.
MAP_SOURCES = ("GameDiff.java", "VersionMap.java", "Exposure.java", "ModNames.java", "cli/Json.java")


def tools_key(*names):
    """The content of the tools that build a cache entry, hashed. Several worktrees share ~/.mmt/exposure and run at once;
    keyed by file times, one worktree's newer tools wiped what another was reading (#126). Keyed by content, worktrees
    with the same tools share entries and worktrees with other tools never touch each other's."""
    h = hashlib.sha256()
    for name in names:
        h.update(name.encode() + b"\0" + (ROOT / "tools" / name).read_bytes() + b"\0")
    return h.hexdigest()[:16]


KEY = re.compile(r"^[0-9a-f]{16}$")


def keyed(kind, *names):
    """~/.mmt/exposure/KIND/KEY/ for the tools named; a key no run has used for a day is removed when a new one is made."""
    base = HOME / kind
    folder = base / tools_key(*names)
    fresh = not folder.is_dir()
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "used").write_text(str(os.getpid()))
    if fresh:
        for old in base.iterdir():
            used = old / "used"
            if old != folder and KEY.match(old.name) and used.is_file() and time.time() - used.stat().st_mtime > 86400:
                shutil.rmtree(old, ignore_errors=True)
    return folder


def publish(part, target):
    """Moves a finished file into a shared cache entry. If a run beside this one finished it first, its copy is kept: the same
    tools on the same inputs wrote the same thing, and replacing a file another run is reading can break that run."""
    # A hard link never replaces, on any system (a move replaces on POSIX); a file system without hard links gets the move.
    try:
        os.link(part, target)
    except FileExistsError:
        pass
    except OSError:
        try:
            os.rename(part, target)
        except FileExistsError:
            pass
    part.unlink(missing_ok=True)
    return target


def part_of(target):
    """A name for a file being written into a shared cache, unique to this run."""
    return target.with_name(f"{target.name}.{os.getpid()}.{uuid.uuid4().hex[:12]}.part")


def route_map(frm, to, loader, classes):
    target = keyed("maps", *MAP_SOURCES) / f"{loader}-{frm}-{to}.json"
    if target.is_file():
        return target
    part = part_of(target)
    args = [java(), "-Xmx10g", "-cp", str(classes), "GameDiff", "--chain", ",".join(chain(frm, to)), "--out", str(part)]
    if loader:
        args += ["--loader", loader]
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL)
    return publish(part, target)


ASM = [ROOT / "tools" / "perf" / "lib" / "asm-9.9.jar", ROOT / "tools" / "perf" / "lib" / "asm-commons-9.9.jar"]
JAR_SOURCES = ("JarPort.java", "FixExpr.java", "PortChains.java", "Exposure.java", "GameDiff.java", "VersionMap.java", "ModNames.java")


def jar_tools():
    out = ROOT / ".runs" / "exposure" / "jarport-classes"
    sources = [ROOT / "tools" / n for n in JAR_SOURCES] + [ROOT / "tools" / "cli" / "Json.java"]
    stamp = out / "JarPort.class"
    if not stamp.is_file() or stamp.stat().st_mtime < max(s.stat().st_mtime for s in sources):
        javac = java().replace("java.exe", "javac.exe") if java().endswith("java.exe") else java() + "c"
        subprocess.run([javac, "-encoding", "UTF-8", "-cp", os.pathsep.join(map(str, ASM)), "-d", str(out)] + [str(s) for s in sources], check=True)
    return os.pathsep.join([str(out)] + [str(a) for a in ASM])


# Each process that reads jars loads two releases of the game first and starts cold, so more piles stop paying:
# on 24 processors 150 NeoForge mods took 144 s in one pile, 66 s in four and 75 s in nine.
PILE = 36
# A pile only pays with processors to spare for its start: on GitHub's 4-processor runners four piles took 104 s
# where one had taken about 90.
SPARE = 4
# Processors this run may use; all runs every update side by side and gives each its share.
CORES = os.cpu_count() or 1


def piles(jars):
    """The jars dealt into piles that take about as long, one per processor: every next-biggest jar goes on the lightest pile."""
    count = max(1, min(CORES // SPARE, len(jars) // PILE))
    dealt = [[0, []] for _ in range(count)]
    for jar in sorted(jars, key=lambda j: (-j.stat().st_size, j.name)):
        pile = min(dealt, key=lambda d: d[0])
        pile[0] += jar.stat().st_size
        pile[1].append(jar)
    return [pile for _, pile in dealt]


def side_by_side(command, jars, report):
    """Runs a tool that reads jars one at a time (JarPort, ModNames) on every processor at once: a jar's result does not
    depend on the jars beside it, and one process used one of this machine's processors (150 mods took 140 s on one of
    24). Returns the reports' rows in the order the jars were given."""
    dealt = piles(jars)
    if len(dealt) > 1:
        # The piles share the memory one process had.
        command = [f"-Xmx{max(3, 10 // len(dealt))}g" if c.startswith("-Xmx") else c for c in command]
    parts = [report.with_name(f"{report.stem}.{i}{report.suffix}") for i in range(len(dealt))]
    running = [subprocess.Popen(command + ["--report", str(part)] + [str(j) for j in pile], stdout=subprocess.DEVNULL) for part, pile in zip(parts, dealt)]
    codes = [process.wait() for process in running]
    if any(codes):
        raise SystemExit(f"{' '.join(command[:1] + [c for c in command if c in ('JarPort', 'ModNames')])} failed on a pile of jars (exit {max(codes, key=abs)}); its output is above")
    rows = {}
    for part in parts:
        for row in json.loads(part.read_text(encoding="utf-8"))["mods"]:
            rows[row["mod"]] = row
        part.unlink()
    return [rows[j.name] for j in jars if j.name in rows]


def mojang_names(loader, version, jars):
    """The jars in Mojang's names: Fabric's before 26.1 read through intermediary by tools/ModNames.java (kept in
    ~/.mmt/exposure/mojang/ until ModNames or VersionMap changes), every other one as it is. A jar it cannot read is
    left out, with why."""
    if not shipped_obfuscated(loader, version):
        return jars, {}
    out = keyed("mojang", *NAME_SOURCES) / f"{loader}-{version}"
    out.mkdir(parents=True, exist_ok=True)
    missing = [j for j in jars if not (out / j.name).is_file()]
    failed = {}
    if missing:
        # ModNames writes each jar under a name of its own and moves it into place, so a run beside this one never reads half a jar.
        report = ROOT / ".runs" / "exposure" / f"names-{loader}-{version}-{os.getpid()}.json"
        report.parent.mkdir(parents=True, exist_ok=True)
        rows = side_by_side([java(), "-Xmx4g", "-cp", jar_tools(), "ModNames", "--version", version, "--loader", loader, "--out", str(out)], missing, report)
        failed = {m["mod"]: m["error"] for m in rows if "error" in m}
    return [out / j.name for j in jars if (out / j.name).is_file()], failed


# What reading a jar in Mojang's names is built from.
NAME_SOURCES = ("ModNames.java", "Exposure.java", "VersionMap.java", "cli/Json.java")


def shipped_obfuscated(loader, version):
    """Whether a loader's mods for a release name the game in other names than Mojang's: Fabric's in intermediary before 26.1,
    NeoForge's in SRG ids (m_N_, f_N_) before 1.20.5."""
    return vkey(version) < vkey(UNOBFUSCATED) if loader == "fabric" else vkey(version) < vkey(MOJANG_NEOFORGE)


def jar_port(loader, frm, to, jars, out_dir, report, runtime=False):
    """Ports jars with tools/JarPort.java into out_dir and returns its report. runtime: in the names the loader runs
    (a Fabric jar handed back in the target release's intermediary), for a game to start them."""
    classpath = jar_tools()
    route = route_map(frm, to, loader, compile_tools())
    out_dir.mkdir(parents=True, exist_ok=True)
    args = [java(), "-Xmx10g", "-cp", classpath, "JarPort", "--from", frm, "--to", to, "--map", str(route), "--fixes", str(ROOT / "knowledge" / "fixes.json"), "--out", str(out_dir),
            "--loader", loader] + (["--runtime-names"] if runtime else [])
    rows = side_by_side(args, jars, report)
    counts = {}
    for m in rows:
        for member in left(m):
            counts[member] = counts.get(member, 0) + 1
    merged = {"from": frm, "to": to, "loader": loader, "linked": sum(1 for m in rows if "error" not in m and not m["unlinked"]), "mods": rows,
              "unlinkedByMods": [{"member": k, "mods": v} for k, v in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))]}
    report.write_text(json.dumps(merged, indent=1), encoding="utf-8")
    return merged


def game_says(loader, frm, to, report):
    """What the game's own code did about each reference JarPort's report ranks (tools/Exposure.java)."""
    classes = compile_tools()
    out = report.with_name("game.json")
    subprocess.run([java(), "-Xmx10g", "-cp", str(classes), "Exposure", "--from", frm, "--to", to, "--report", str(report), "--out", str(out),
                    "--loader", loader], check=True, stdout=subprocess.DEVNULL)
    return json.loads(out.read_text(encoding="utf-8"))


def registry_ids(version):
    """Every id the release registers in its built-in registries, by registry, from its own data generator's report
    (the vanilla server run with --reports), kept in ~/.mmt/exposure/registries/; None when that release's generator does
    not run here."""
    cache = HOME / "registries" / f"{version}.json"
    if cache.is_file():
        return json.loads(cache.read_text(encoding="utf-8"))
    manifest = json.load(urllib.request.urlopen(urllib.request.Request("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", headers=AGENT), timeout=60))
    url = next(v["url"] for v in manifest["versions"] if v["id"] == version)
    meta = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=AGENT), timeout=60))
    work = ROOT / ".runs" / "exposure" / "registries" / version
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    server = work / "server.jar"
    with urllib.request.urlopen(urllib.request.Request(meta["downloads"]["server"]["url"], headers=AGENT), timeout=300) as r:
        server.write_bytes(r.read())
    import zipfile
    with zipfile.ZipFile(server) as z:
        bundled = "META-INF/versions.list" in z.namelist()
    # From 1.18 the server jar is a bundler that names the class to run; before, the generator is on its class path.
    command = ([java(), "-DbundlerMainClass=net.minecraft.data.Main", "-jar", str(server)] if bundled
               else [java(), "-cp", str(server), "net.minecraft.data.Main"]) + ["--reports", "--output", str(work / "generated")]
    done = subprocess.run(command, cwd=work, capture_output=True, text=True, encoding="utf-8", errors="replace")
    report = work / "generated" / "reports" / "registries.json"
    if done.returncode or not report.is_file():
        return None
    ids = {name: sorted(entry["entries"]) for name, entry in json.loads(report.read_text(encoding="utf-8")).items()}
    cache.parent.mkdir(parents=True, exist_ok=True)
    part = part_of(cache)
    part.write_text(json.dumps(ids), encoding="utf-8")
    publish(part, cache)
    return ids


GAME_ID = re.compile(r"^minecraft:[a-z0-9_./-]+$")
# Keys whose value a codec looks up in a built-in registry, by the game's own data format: the dispatch keys of every
# typed object (an enchantment effect, a feature, a placement), a loot function and a loot or predicate condition. Any
# other string may name something data-driven or of another kind, so it is not judged.
DECODED_BY = {"type", "function", "condition"}


def data_check(frm, to, mods):
    """What each mod's data files (and its nested mods') name of the game that the new release no longer registers: a
    built-in id of the old release (an enchantment effect type, a data component, a loot function ...) that is in no
    registry of the new one. The server refuses such a file when it loads the mod's data, which the link check cannot see.
    None when either release's registry report is not available."""
    old, new = registry_ids(frm), registry_ids(to)
    if old is None or new is None:
        return None
    gone = {}
    present = {i for ids in new.values() for i in ids}
    for registry, ids in old.items():
        for i in ids:
            if i not in present:
                gone.setdefault(i, registry)
    import io
    import zipfile

    def scan(data, name, found):
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            for entry in z.namelist():
                if entry.endswith(".jar") and entry.startswith(("META-INF/jarjar/", "META-INF/jars/")):
                    scan(z.read(entry), name, found)
                elif entry.startswith("data/") and entry.endswith(".json") and "/tags/" not in entry:
                    # A tag naming a missing entry is skipped with a warning; the files below are refused.
                    try:
                        value = json.loads(z.read(entry).decode("utf-8-sig"))
                    except (ValueError, UnicodeDecodeError):
                        continue
                    stack = [(None, value)]
                    while stack:
                        key, v = stack.pop()
                        if isinstance(v, dict):
                            for k, item in v.items():
                                # A data component is named by its key ("minecraft:food" in an item's components).
                                if key == "components" and k.lstrip("!") in gone:
                                    found.setdefault(f"data id gone: {gone[k.lstrip('!')]} {k.lstrip('!')}", entry)
                                stack.append((k, item))
                        elif isinstance(v, list):
                            stack.extend((key, item) for item in v)
                        elif key in DECODED_BY and isinstance(v, str) and GAME_ID.match(v) and v in gone:
                            found.setdefault(f"data id gone: {gone[v]} {v}", entry)

    out = {}
    for jar in mods:
        found = {}
        try:
            scan(jar.read_bytes(), jar.name, found)
        except (OSError, zipfile.BadZipFile):
            continue
        out[jar.name] = sorted(f"{ref} (in {where})" for ref, where in found.items())
    return out


LOADER_REF = re.compile(r": (?:net\.neoforged|net\.fabricmc)\.")


def platform_ids(loader, to):
    """The mod ids the platform a pack runs on provides for the new release: Fabric API's modules. Fabric Loader keeps the
    newest copy of each mod id, so a copy of one nested in a mod is not what runs; NeoForge has no such modules."""
    if loader != "fabric":
        return set()
    api = fabric_api(to)
    return set(nested_ids(api.read_bytes())) if api else set()


def nested_ids(data):
    """Every fabric.mod.json id in a jar and the jars nested in it, by the nested jar's file name (the jar's own as "")."""
    import io
    import zipfile
    out = {}
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for entry in z.namelist():
            if entry == "fabric.mod.json":
                meta = json.loads(z.read(entry).decode("utf-8", "replace"), strict=False)
                out[meta.get("id")] = ""
                for p in meta.get("provides") or []:
                    out[p] = ""
            elif entry.startswith("META-INF/jars/") and entry.endswith(".jar"):
                for i in nested_ids(z.read(entry)):
                    out[i] = entry.rsplit("/", 1)[1]
    return out


def hidden_code(data):
    """The name of a jar inside a mod that holds game code and no loader metadata lists as nested (fabric.mod.json "jars",
    NeoForge's jarjar list): code the mod's own loader unpacks at start (Essential), which the port never reads. None if none."""
    import io
    import zipfile
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        names = z.namelist()
        listed = set()
        if "fabric.mod.json" in names:
            try:
                listed |= {j.get("file") for j in json.loads(z.read("fabric.mod.json").decode("utf-8", "replace"), strict=False).get("jars") or [] if isinstance(j, dict)}
            except ValueError:
                pass
        if "META-INF/jarjar/metadata.json" in names:
            try:
                listed |= {j.get("path") for j in json.loads(z.read("META-INF/jarjar/metadata.json")).get("jars") or [] if isinstance(j, dict)}
            except ValueError:
                pass
        for entry in names:
            if not entry.endswith(".jar"):
                continue
            inner = z.read(entry)
            if entry not in listed:
                with zipfile.ZipFile(io.BytesIO(inner)) as i:
                    if any(n.endswith(".class") and b"net/minecraft/" in i.read(n) for n in i.namelist()):
                        return entry
            else:
                deeper = hidden_code(inner)
                if deeper:
                    return entry + "!" + deeper
    return None


def with_hidden_code_unread(report, jars):
    """A mod whose game code is in a jar its own loader unpacks is reported as not read, not as linking: the port never
    saw that code (Essential links with none of its code read, and fails on 1.21.2 on a removed class)."""
    for m in report["mods"]:
        if "error" in m or m["mod"] not in jars:
            continue
        try:
            inner = hidden_code(jars[m["mod"]].read_bytes())
        except Exception:  # a jar zipfile cannot open: JarPort judged it already
            continue
        if inner:
            m["error"] = f"its game code is in {inner}, a jar no loader metadata lists (its own loader unpacks it at start), which the port does not read"
    report["linked"] = sum(1 for m in report["mods"] if "error" not in m and not m["unlinked"])
    return report


def without_platform_copies(report, provided, jars):
    """JarPort's report without what mods' nested copies of platform modules reference (a nested jar named
    "MOD.jar!INNER.jar: ..."): that copy never runs. Each mod's counts are made again from what is left."""
    if not provided:
        return report
    for m in report["mods"]:
        if "error" in m or not any("!" in u.split(": ", 1)[0] for u in m["unlinked"]):
            continue
        names = {}
        try:
            import zipfile
            with zipfile.ZipFile(jars[m["mod"]]) as z:
                for entry in z.namelist():
                    if entry.startswith("META-INF/jars/") and entry.endswith(".jar"):
                        for i, inner in nested_ids(z.read(entry)).items():
                            names.setdefault(inner or entry.rsplit("/", 1)[1], set()).add(i)
        except (OSError, KeyError, ValueError):
            continue
        m["unlinked"] = [u for u in m["unlinked"]
                         if not any(names.get(part, set()) & provided for part in u.split(": ", 1)[0].split("!")[1:])]
    counts = {}
    for m in report["mods"]:
        for member in left(m):
            counts[member] = counts.get(member, 0) + 1
    report["unlinkedByMods"] = [{"member": k, "mods": v} for k, v in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))]
    report["linked"] = sum(1 for m in report["mods"] if "error" not in m and not m["unlinked"])
    return report


WHERE = re.compile(r" \(in [^)]*\)$")


def left(row, data=None):
    """What one mod still needs: each reference once (a mod and its nested jars name it once a class), and the game ids its data lost."""
    return {WHERE.sub("", u.split(": ", 1)[1]) for u in row.get("unlinked", [])} | {WHERE.sub("", ref) for ref in (data or {}).get(row["mod"], [])}


def nearest(rows, data, said):
    """The to-do list: the mods in the order they finish when every turn completes the one with the fewest references
    left, each with the references that turn adds and what the game's own code did about them. The references the
    most mods share are the wrong order: a mod held back has 15 at the median, so on NeoForge 1.21.1 -> 1.21.2 the
    100 most shared freed 2 mods, and the first 33 in this order freed 21."""
    todo = {m["mod"]: left(m, data) for m in rows}
    todo = {mod: refs for mod, refs in todo.items() if refs}
    fixed, done, order = set(), 0, []
    while todo:
        # Fewest left first; among those, the ones the game's own code answers.
        mod = min(todo, key=lambda k: (len(todo[k] - fixed), sum(1 for ref in todo[k] - fixed if said.get(ref, {}).get("game") not in ANSWERED), k))
        new = sorted(todo[mod] - fixed)
        fixed.update(new)
        freed = sorted(k for k, refs in todo.items() if refs <= fixed)
        for k in freed:
            del todo[k]
        done += len(freed)
        order.append({"mod": mod, "alsoFrees": [k for k in freed if k != mod], "fixes": len(fixed), "modsDone": done,
                      "members": [{"member": ref, **said.get(ref, {})} for ref in new]})
    return order


ANSWERED = {"replaced", "split", "linked", "template"}


def kind_of(member, said):
    """The type of break a reference is: what it is (a call, a mixin target whose signature changed, an abstract method
    not written ...) and what the game's own code did about it. A way to handle a type frees every mod held by it, in
    every update; a rule for one member frees the mods that use that member."""
    return f"{member.split(': ', 1)[0]}, game: {said.get(member, {}).get('game') or 'not read'}"


def by_type(rows, data, said):
    """Each type of break with the mods that have one, the mods it alone holds back (handled, they are done), and
    its most-used references as examples; the types that finish the most mods first."""
    types = {}
    for m in rows:
        refs = left(m, data)
        kinds = {kind_of(ref, said) for ref in refs}
        for ref in refs:
            t = types.setdefault(kind_of(ref, said), {"mods": set(), "finishes": 0, "uses": {}})
            t["mods"].add(m["mod"])
            t["uses"][ref] = t["uses"].get(ref, 0) + 1
        if len(kinds) == 1:
            types[next(iter(kinds))]["finishes"] += 1
    return sorted(({"type": k, "finishes": t["finishes"], "mods": len(t["mods"]), "references": len(t["uses"]),
                    "examples": [ref for ref, _ in sorted(t["uses"].items(), key=lambda kv: (-kv[1], kv[0]))[:3]]} for k, t in types.items()),
                  key=lambda t: (-t["finishes"], -t["mods"], t["type"]))


def shown(member):
    return f"{member['member']}  ({member.get('part', 'game')}: {member.get('game', '?')})"


def jars(loader, frm, to, save=None, out=None, quiet=False):
    """The jar scoreboard: what every top mod's classes and mixins reference, linked against the new release as the JVM and Mixin do."""
    seconds = {}

    def timed(stage, work, *args):
        started = time.monotonic()
        try:
            return work(*args)
        finally:
            seconds[stage] = round(seconds.get(stage, 0) + time.monotonic() - started, 1)

    pinned = timed("download", fetch, loader, frm)
    classes = compile_tools()
    # Reading the jars' names, the game's change map and the data reports need nothing from each other.
    with ThreadPoolExecutor(3) as pool:
        named = pool.submit(timed, "names", mojang_names, loader, frm, pinned)
        mapped = pool.submit(timed, "map", route_map, frm, to, loader, classes)
        checked = pool.submit(timed, "data", data_check, frm, to, pinned)
        (mods, unread), data = named.result(), checked.result()
        mapped.result()
    work = ROOT / ".runs" / "exposure" / f"jars-{loader}-{frm}-{to}"
    report = timed("port", jar_port, loader, frm, to, mods, work / "mods", work / "report.json") if mods else {"linked": 0, "mods": [], "unlinkedByMods": []}
    shutil.rmtree(work / "mods", ignore_errors=True)  # only the report is read; load ports the mods it starts itself
    if mods:
        report = without_platform_copies(report, platform_ids(loader, to), {m.name: m for m in mods})
        report = with_hidden_code_unread(report, {m.name: m for m in mods})
        (work / "report.json").write_text(json.dumps(report, indent=1), encoding="utf-8")
    said = timed("game", game_says, loader, frm, to, work / "report.json") if report["unlinkedByMods"] else {}
    rows = [m for m in report["mods"] if "error" not in m]
    data_by = {}
    for mod, refs in (data or {}).items():
        for ref in refs:
            data_by.setdefault(re.sub(r" \(in [^)]*\)$", "", ref), set()).add(mod)
    summary = {
        "loader": loader, "from": frm, "to": to, "modsRead": len(rows), "modsThatLink": report["linked"],
        # Linking and naming nothing in their data that the new release dropped: the stricter count, when the reports ran.
        "modsThatLinkAndData": None if data is None else sum(1 for m in rows if not m["unlinked"] and not data.get(m["mod"])),
        "dataTop": sorted(({"member": k, "mods": len(v)} for k, v in data_by.items()), key=lambda r: -r["mods"])[:20],
        "silentOnly": sum(1 for m in rows if not m["unlinked"] and m["silent"]),
        "brokenUses": sum(r["mods"] for r in report["unlinkedByMods"]),  # distinct references, a mod and its nested jars once
        # The loader's own API (NeoForge with FML and its event bus; Fabric Loader and Fabric API) is linked like the game:
        # the uses of it that break, and the mods that break on nothing else.
        "loaderUses": sum(r["mods"] for r in report["unlinkedByMods"] if LOADER_REF.search(r["member"])),
        "modsHeldOnlyByLoader": sum(1 for m in rows if m["unlinked"] and all(LOADER_REF.search(u) for u in m["unlinked"])),
        "unreadable": {**unread, **{m["mod"]: m["error"] for m in report["mods"] if "error" in m}},
        "top": [{**r, **said.get(r["member"], {})} for r in report["unlinkedByMods"][:40]],
        # Where this run's time went: download, names, map and data ran side by side; port and game after them.
        "seconds": seconds,
    }
    order = nearest(rows, data, said)
    summary["types"] = by_type(rows, data, said)
    summary["nearest"] = [{**turn, "members": turn["members"][:25]} for turn in order[:40]]
    summary["finishing"] = [[turn["fixes"], turn["modsDone"]] for turn in order]
    if not quiet:
        print(f"{summary['modsThatLink']}/{summary['modsRead']} {loader} mods link against {to} after the port "
              f"({summary['brokenUses']} broken uses; {summary['silentOnly']} keep an override the game no longer calls)"
              + ("" if data is None else f"; {summary['modsThatLinkAndData']} of them also name nothing in their data that {to} dropped")
              + f"; {summary['loaderUses']} broken uses are of the loader's API, and {summary['modsHeldOnlyByLoader']} mods break on nothing else")
        print("seconds: " + ", ".join(f"{stage} {took:g}" for stage, took in seconds.items()))
        print("\nTypes of break, by the mods each one alone holds back (a way to handle the type finishes them, here and in every update):")
        for t in summary["types"][:10]:
            print(f"  finishes {t['finishes']:>3}, in {t['mods']:>3} mods, {t['references']:>4} references  {t['type']}   e.g. {t['examples'][0]}")
        print("\nNearest to done (each mod with the references it still needs):")
        for turn in summary["nearest"][:12]:
            also = f", and frees {len(turn['alsoFrees'])} more" if turn["alsoFrees"] else ""
            print(f"  {turn['mod']}: {len(turn['members'])} left{also}; {turn['modsDone']} mods done after {turn['fixes']} fixes")
            for member in turn["members"][:6]:
                print(f"      {shown(member)}")
        print("\nShared by the most mods (a fix to how the porter learns reaches these in every update; alone they free few mods):")
        for row in summary["top"][:10]:
            print(f"  {row['mods']:>4} mods  {shown(row)}")
    for path in [p for p in (out, ROOT / "bench" / "exposure" / f"{save}.json" if save else None) if p]:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(summary, indent=1) + "\n", encoding="utf-8")
        if not quiet:
            print(f"saved {path}")
    return summary


def every_update(loaders, only, jobs, save):
    """jars for every update and every jump of each loader, side by side; joined into one table."""
    todo = [(lo, a, b) for lo in loaders for a, b in updates(lo) + jumps(lo) if not only or f"{lo}:{a}" in only]
    jar_tools()
    compile_tools()
    global CORES
    CORES = max(1, (os.cpu_count() or 1) // max(1, jobs))
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
        single = updates(loader)
        for parity in (0, 1):
            wave = [t for t in todo if t[0] == loader and t[1:] in single and single.index(t[1:]) % 2 == parity]
            with ThreadPoolExecutor(max(1, jobs)) as pool:
                list(pool.map(one, wave))
        # A jump reads every release on its way. The updates above fetched them, so jumps side by side write no file
        # twice; one that crosses an update that did not run (--only) goes alone.
        fetched = {v for t in todo if t[0] == loader and t[1:] in single for v in t[1:]}
        far = [t for t in todo if t[0] == loader and t[1:] not in single]
        warm = [t for t in far if set(chain(*t[1:])) <= fetched]
        with ThreadPoolExecutor(max(1, jobs)) as pool:
            list(pool.map(one, warm))
        for t in far:
            if t not in warm:
                one(t)
    return join(out, save)


def join(folder, save):
    """One table from per-update summaries: the mods that link across each update and each jump, the totals, and the mods nearest to done."""
    found = []
    for f in sorted(Path(folder).rglob("*.json")):
        s = json.loads(f.read_text(encoding="utf-8"))
        if {"loader", "from", "to", "modsRead"} <= s.keys():
            found.append(s)
    single = {(lo, *u): i for lo in FIRST for i, u in enumerate(updates(lo))}
    wanted = {(lo, *u): i for lo in FIRST for i, u in enumerate(jumps(lo))}
    pair = lambda s: (s["loader"], s["from"], s["to"])
    rows = sorted((s for s in found if pair(s) in single), key=lambda s: (s["loader"], single[pair(s)]))
    far = sorted((s for s in found if pair(s) in wanted), key=lambda s: (s["loader"], wanted[pair(s)]))

    def count(some):
        return {"updates": len(some), "mods": sum(s["modsRead"] for s in some), "link": sum(s["modsThatLink"] for s in some)}

    total = {"mods": sum(s["modsRead"] for s in rows), "link": sum(s["modsThatLink"] for s in rows)}
    # One update at a time is two different jobs: the updates that start a new family of releases, and the rest,
    # across which nearly every mod links untouched.
    total["big"] = count([s for s in rows if big(s["from"], s["to"])])
    total["small"] = count([s for s in rows if not big(s["from"], s["to"])])
    # What people ask for: a port over several updates.
    total["jumps"] = {"jumps": len(far), "mods": sum(s["modsRead"] for s in far), "link": sum(s["modsThatLink"] for s in far)}
    # The jump asked for most: the most-used release's mods on the newest game.
    newest = [s for s in far if s["to"] == updates(s["loader"])[-1][1]]
    total["newest"] = {"jumps": len(newest), "mods": sum(s["modsRead"] for s in newest), "link": sum(s["modsThatLink"] for s in newest)}
    checked = [s for s in rows if s.get("modsThatLinkAndData") is not None]
    # Over the updates whose registry reports ran: mods that link and also name nothing in their data that the new release dropped.
    total["data"] = {"updates": len(checked), "link": sum(s["modsThatLink"] for s in checked), "linkAndData": sum(s["modsThatLinkAndData"] for s in checked)}
    # Breaks in the loader's own API, which the link check reads like the game's.
    total["loader"] = {"uses": sum(s.get("loaderUses", 0) for s in rows), "modsHeldOnlyByLoader": sum(s.get("modsHeldOnlyByLoader", 0) for s in rows)}
    loaders = {}
    for s in rows:
        t = loaders.setdefault(s["loader"], {"updates": 0, "mods": 0, "link": 0})
        t["updates"] += 1
        t["mods"] += s["modsRead"]
        t["link"] += s["modsThatLink"]
    top = sorted(({"loader": s["loader"], "update": f"{s['from']} -> {s['to']}", **r} for s in rows for r in s["top"]),
                 key=lambda r: -r["mods"])[:60]
    # The to-do list over every update: the mods with the fewest references left, those the game's own code answers first.
    # Among equals, the update into the release with the most mods: that is where a finished port is wanted most.
    family = families()
    used = {v: f.get("mods", {}).get(v, 0) for v, f in family.items()}
    turns = [{"loader": s["loader"], "update": f"{s['from']} -> {s['to']}", "mod": t["mod"], "members": t["members"], "wanted": used.get(s["to"], 0)}
             for s in rows for t in s.get("nearest", [])]
    turns.sort(key=lambda t: (len(t["members"]), sum(1 for m in t["members"] if m.get("game") not in ANSWERED), -t["wanted"]))
    # The to-do list by type of break, over every update: what a way to handle the type would finish.
    kinds = {}
    for s in rows:
        for t in s.get("types", []):
            k = kinds.setdefault(t["type"], {"type": t["type"], "finishes": 0, "mods": 0, "references": 0, "updates": 0, "examples": []})
            k["finishes"] += t["finishes"]
            k["mods"] += t["mods"]
            k["references"] += t["references"]
            k["updates"] += 1
            k["examples"].append([t["finishes"], f"{s['loader']} {s['from']} -> {s['to']}: {t['examples'][0]}"])
    for k in kinds.values():
        k["examples"] = [text for _, text in sorted(k["examples"], key=lambda e: -e[0])[:4]]
    kinds = sorted(kinds.values(), key=lambda k: (-k["finishes"], -k["mods"], k["type"]))
    # A summary an older run made has no types: the ranking then covers fewer updates than the counts, and says so.
    untyped = [f"{s['loader']} {s['from']} -> {s['to']}" for s in rows if "types" not in s]
    missing = [f"{lo} {a} -> {b}" for lo, a, b in list(single) + list(wanted) if not any(pair(s) == (lo, a, b) for s in found)]
    keep = ("loader", "from", "to", "modsRead", "modsThatLink", "modsThatLinkAndData", "brokenUses", "loaderUses", "modsHeldOnlyByLoader")
    table = {
        "date": date.today().isoformat(), "total": total, "loaders": loaders, "missing": missing,
        "updates": [{**{k: s.get(k) for k in keep}, "big": big(s["from"], s["to"])} for s in rows],
        "jumps": [{**{k: s.get(k) for k in keep}, "updatesCrossed": len(chain(s["from"], s["to"])) - 1} for s in far],
        "types": kinds,
        "typesNotRead": untyped,
        "nearest": turns[:60],
        "top": top,
    }

    def share(part, whole):
        return f"{part} of {whole} ({part / whole:.1%})" if whole else "0 of 0"

    print(f"| Loader | Update | Mods that link | And their data |\n|---|---|---|---|")
    for s in rows:
        data = "-" if s.get("modsThatLinkAndData") is None else s["modsThatLinkAndData"]
        print(f"| {s['loader']} | {s['from']} -> {s['to']}{' (big)' if big(s['from'], s['to']) else ''} | {s['modsThatLink']}/{s['modsRead']} | {data} |")
    if far:
        print(f"\n| Loader | Jump | Updates crossed | Mods that link |\n|---|---|---|---|")
        for s in table["jumps"]:
            print(f"| {s['loader']} | {s['from']} -> {s['to']} | {s['updatesCrossed']} | {s['modsThatLink']}/{s['modsRead']} |")
    j, n = total["jumps"], total["newest"]
    print(f"\n{share(n['link'], n['mods'])} of the most-used release's mods link on the newest game; {share(j['link'], j['mods'])} mod ports link across all {j['jumps']} jumps of several updates")
    print(f"one update at a time: {share(total['big']['link'], total['big']['mods'])} across the {total['big']['updates']} big updates, "
          f"{share(total['small']['link'], total['small']['mods'])} across the {total['small']['updates']} small ones; {share(total['link'], total['mods'])} together"
          + "".join(f"; {lo} {t['link']}/{t['mods']}" for lo, t in loaders.items()) + (f"; not run: {', '.join(missing)}" if missing else ""))
    d = total["data"]
    print(f"over the {d['updates']} updates whose registry reports ran, {d['link'] - d['linkAndData']} of {d['link']} mods that link name a game id in their data that the new release dropped")
    print(f"{total['loader']['uses']} broken uses are of the loader's own API; {total['loader']['modsHeldOnlyByLoader']} mod ports break on nothing else")
    print("\nTypes of break, " + (f"{len(rows) - len(untyped)} of {len(rows)} updates (the others' summaries are older and list none)" if untyped else "every update")
          + ", by the mod ports each one alone holds back:")
    for k in kinds[:15]:
        print(f"  finishes {k['finishes']:>4}, in {k['mods']:>5} mod ports of {k['updates']:>2} updates, {k['references']:>5} references  {k['type']}")
        print(f"      e.g. {k['examples'][0]}")
    print("\nNearest to done, every update:")
    for t in table["nearest"][:15]:
        print(f"  {t['loader']} {t['update']}  {t['mod']}: {len(t['members'])} left")
        for member in t["members"][:4]:
            print(f"      {shown(member)}")
    print("\nShared by the most mods, every update:")
    for r in top[:10]:
        print(f"  {r['mods']:>4} mods  {r['loader']} {r['update']}  {shown(r)}")
    if save:
        path = ROOT / "bench" / "exposure" / "all.json"
        path.write_text(json.dumps(table, indent=1) + "\n", encoding="utf-8")
        print(f"saved {path}")
    return table


def fabric_meta(jar):
    """A jar's fabric.mod.json, or None."""
    import zipfile
    with zipfile.ZipFile(jar) as z:
        if "fabric.mod.json" not in z.namelist():
            return None
        try:
            return json.loads(z.read("fabric.mod.json").decode("utf-8", "replace"), strict=False)
        except ValueError:
            return None


def fabric_api(version):
    """Fabric API's newest release for a game version (kept in ~/.mmt/exposure/platform/), or None: the jar players install,
    from Fabric's own Modrinth project. Fabric's maven publishes fabric-api as metadata only, and its pom leaves out the
    deprecated modules the released jar carries (1.19.1: fabric-networking-v0 and eight more), so a pack built from it would
    start on another Fabric API than players have. A cached jar without its modules is not taken."""
    folder = HOME / "platform"
    have = [p for p in sorted(folder.glob(f"fabric-api-for-{version}--*.jar")) if len(nested_ids(p.read_bytes())) > 1]
    if have:
        return have[-1]
    versions = api("project/fabric-api/version", {"loaders": json.dumps(["fabric"]), "game_versions": json.dumps([version])}) or []
    files = [f for v in versions for f in v.get("files", []) if f.get("primary")]
    if not files:
        return None
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / f"fabric-api-for-{version}--{files[0]['filename']}"
    part = part_of(target)
    with urllib.request.urlopen(urllib.request.Request(files[0]["url"], headers=AGENT), timeout=120) as r:
        part.write_bytes(r.read())
    return publish(part, target)


def standalone(jar, platform=frozenset(), adapters=frozenset()):
    """A mod whose metadata asks for no other mod and no other language loader (NeoForge, Forge or Fabric) beyond the
    platform the pack runs on: for Fabric, Fabric API's modules (platform), which a pack starts with as it would for players,
    and the language adapters (adapters) the mods started with it provide."""
    import tomllib
    import zipfile
    meta = fabric_meta(jar)
    if meta is not None:
        own = {meta.get("id")} | set(meta.get("provides") or [])
        if any(e.get("adapter", "default") not in adapters | {"default"} for es in (meta.get("entrypoints") or {}).values() for e in es if isinstance(e, dict)):
            return False
        return all(d in ("minecraft", "fabricloader", "java") or d in own or d in platform for d in (meta.get("depends") or {}))
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
    """The mod ids a NeoForge, Forge or Fabric jar declares."""
    import tomllib
    import zipfile
    meta = fabric_meta(jar)
    if meta is not None:
        return {meta.get("id")} | set(meta.get("provides") or [])
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


def client_starts(loader, to, server_pack, names, platform=()):
    """The same ported jars in a real client of the new release (mmt pack-check --side client): the title screen, a world, the
    inventory and every creative tab. A start that fails drops the jar its log names and starts again; each mod ends loaded or
    failed (with why). A server never runs client code, so this is where rendering and screen code is proven. The platform
    (Fabric API) goes in as it did on the server and is not counted."""
    pack = server_pack.with_name(server_pack.name + "-client")
    if pack.exists():
        shutil.rmtree(pack)
    (pack / "mods").mkdir(parents=True)
    (pack / "dropped").mkdir()
    for name in list(names) + list(platform):
        source = next((d / name for d in (server_pack / "mods", server_pack / "dropped") if (d / name).is_file()), None)
        if source:
            shutil.copy2(source, pack / "mods" / name)
    mmt = str(ROOT / ("mmt.cmd" if os.name == "nt" else "mmt"))
    verdicts = {}

    def start():
        done = subprocess.run([mmt, "pack-check", "--pack", str(pack), "--minecraft", to, "--loader", loader, "--side", "client", "--json"],
                              capture_output=True, text=True, encoding="utf-8", errors="replace")
        at = done.stdout.find("{")
        return json.loads(done.stdout[at:]) if at >= 0 else {}, done

    for attempt in range(len(names) + 1):
        result, done = start()
        res = result.get("result", {})
        present = sorted(p.name for p in (pack / "mods").glob("*.jar") if p.name not in platform)
        if res.get("client", {}).get("opened"):
            for name in present:
                verdicts.setdefault(name, "loaded")
            break
        blamed = {}
        for finding in res.get("findings", []):
            if finding.get("severity") != "problem" or finding.get("kind") != "client-start":
                continue
            for s in finding.get("suspects", []):
                top = s.get("jar", "").split("!")[0] or next((n for n in present if s.get("mod") in mod_ids(pack / "mods" / n)), "")
                if top in present:
                    blamed.setdefault(top, "; ".join(s.get("why", []))[:400] + " | " + str(res.get("client", {}).get("failure", ""))[:300])
        if not blamed:
            # The client itself could not be driven (the start names no mod, say an older release the driver cannot open a
            # world in): the server's verdicts stand, and the client is reported as not run, with why.
            print(res.get("details", done.stdout[-2000:]))
            failure = str(res.get("client", {}).get("failure") or res.get("error") or done.stdout[-300:])[:400]
            print(f"a client start failed and named no mod; the client is not run: {failure}")
            return {"mods": len(names), "loaded": 0, "verdicts": {}, "notRun": failure, "receipt": result.get("receipt")}
        for name, why in blamed.items():
            verdicts[name] = "failed: " + why
            (pack / "mods" / name).rename(pack / "dropped" / name)
            print(f"  {name}: failed in the client")
    # As on the server, a dropped mod gets one more start with the mods that loaded: a log can name a bystander.
    for name in sorted(n for n, v in verdicts.items() if v.startswith("failed")):
        (pack / "dropped" / name).rename(pack / "mods" / name)
        again, _ = start()
        if again.get("result", {}).get("client", {}).get("opened"):
            verdicts[name] = "loaded"
            print(f"  {name}: loads in the client with the others on its own start")
        else:
            (pack / "mods" / name).rename(pack / "dropped" / name)
    loaded = sorted(n for n, v in verdicts.items() if v == "loaded")
    return {"mods": len(names), "loaded": len(loaded), "verdicts": verdicts}


def starts_alone(loader, to, pack, name, platform):
    """Whether a dropped mod starts a real server of the release on its own, with the platform the pack has."""
    alone = pack / "alone"
    shutil.rmtree(alone, ignore_errors=True)
    (alone / "mods").mkdir(parents=True)
    shutil.copy(pack / "dropped" / name, alone / "mods" / name)
    for jar in platform:
        shutil.copy(pack / "mods" / jar, alone / "mods" / jar)
    mmt = str(ROOT / ("mmt.cmd" if os.name == "nt" else "mmt"))
    done = subprocess.run([mmt, "pack-check", "--pack", str(alone), "--minecraft", to, "--loader", loader, "--json"],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    start = done.stdout.find("{")
    return bool(start >= 0 and json.loads(done.stdout[start:]).get("ok"))


def load(loader, frm, to, save):
    """The mods the jar scoreboard says link that need no other mod, ported as jars and started on a real server of the new release,
    then in a real client of it (client_starts), with both verdicts side by side.

    One start holds them all; a start that fails drops the jar its log names and starts again, until a start opens a world,
    places every block item and opens it again. Each mod ends loaded, failed (with why) or left out as client-only."""
    report = ROOT / ".runs" / "exposure" / f"jars-{loader}-{frm}-{to}" / "report.json"
    if not report.is_file():
        jars(loader, frm, to)
    linked = [m["mod"] for m in json.loads(report.read_text(encoding="utf-8"))["mods"] if "error" not in m and not m["unlinked"]]
    folder = HOME / "jars" / f"{loader}-{frm}"
    # A Fabric mod that needs Fabric API needs nothing beyond the platform the pack starts with; one that needs other
    # mods starts with them when they link too.
    provided, adapters, chosen = set(platform_ids(loader, to)), set(), []
    while True:
        more = [folder / name for name in linked if folder / name not in chosen and standalone(folder / name, provided, adapters)]
        if not more:
            break
        for jar in more:
            chosen.append(jar)
            provided |= mod_ids(jar)
            adapters |= set((fabric_meta(jar) or {}).get("languageAdapters") or {})
    pack = ROOT / ".runs" / "exposure" / f"load-{loader}-{frm}-{to}"
    if pack.exists():
        shutil.rmtree(pack)
    # A Fabric jar is ported in Mojang's names (read as jars does) and handed back in the names Fabric runs.
    read, _ = mojang_names(loader, frm, chosen)
    report = jar_port(loader, frm, to, read, pack / "mods", pack / "report.json", runtime=loader == "fabric")
    links = {m["mod"]: not m.get("unlinked") and "error" not in m for m in report["mods"]}
    (pack / "dropped").mkdir()
    platform = []
    if loader == "fabric":
        # Fabric API is the platform nearly every Fabric pack has, as NeoForge's own classes are to a NeoForge mod: mods
        # that call it optionally, or without listing it, start as they would for players. It is not counted.
        api_jar = fabric_api(to)
        if api_jar:
            shutil.copy(api_jar, pack / "mods" / api_jar.name)
            platform.append(api_jar.name)
    mmt = str(ROOT / ("mmt.cmd" if os.name == "nt" else "mmt"))
    verdicts = {}
    for attempt in range(len(chosen) + 1):
        done = subprocess.run([mmt, "pack-check", "--pack", str(pack), "--minecraft", to, "--loader", loader, "--json"],
                              capture_output=True, text=True, encoding="utf-8", errors="replace")
        text = done.stdout[done.stdout.find("{"):]
        result = json.loads(text) if text else {}
        res = result.get("result", {})
        present = sorted(p.name for p in (pack / "mods").glob("*.jar") if p.name not in platform)
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
                if starts_alone(loader, to, pack, name, platform):
                    # Two mods that patch the same game code can refuse each other on any release, ported or not; the
                    # port is judged by the mod starting alone.
                    verdicts[name] = "clash: loads alone, fails next to the other mods: " + verdicts[name][len("failed: "):]
                    print(f"  {name}: loads alone, fails next to the others")
    client = client_starts(loader, to, pack, [n for n, v in verdicts.items() if v == "loaded" or v.startswith("client-only")], platform)
    loaded = [n for n, v in verdicts.items() if v == "loaded"]
    summary = {
        "loader": loader, "from": frm, "to": to, "mods": len(chosen), "loaded": len(loaded),
        "linkedAndLoaded": sum(1 for n in loaded if links.get(n)), "linked": sum(links.values()),
        "failedButLinked": sorted(n for n, v in verdicts.items() if v.startswith("failed") and links.get(n) and has_code(folder / n)),
        "clashOnly": sorted(n for n, v in verdicts.items() if v.startswith("clash") and links.get(n)),
        # A mod with no game code links trivially; what fails it is its data, which the link check does not read.
        "failedDataOnly": sorted(n for n, v in verdicts.items() if v.startswith("failed") and links.get(n) and not has_code(folder / n)),
        "verdicts": verdicts,
        "client": client,
        # What the roadmap's step 51 says would prove the server proof wrong: a mod that loads on the server and links, and fails in the client.
        "loadedOnServerFailedInClient": sorted(n for n, v in client["verdicts"].items() if v.startswith("failed") and verdicts.get(n) == "loaded" and links.get(n)),
    }
    print(f"{'mod':<60} {'server':<12} client")
    for name in sorted(verdicts):
        print(f"{name[:60]:<60} {verdicts[name].split(':')[0]:<12} {client['verdicts'].get(name, 'not started').split(':')[0]}")
    print(f"client: {client['loaded']}/{client['mods']} load in a real {to} client; loaded on the server and failed in the client: "
          f"{summary['loadedOnServerFailedInClient'] or 'none'}")
    print(f"{summary['loaded']}/{summary['mods']} load on a real {to} server; {summary['linked']} link, "
          f"{summary['linkedAndLoaded']} of them load; failed although they link: {summary['failedButLinked'] or 'none'}"
          + (f"; failed on their data, with no game code: {summary['failedDataOnly']}" if summary["failedDataOnly"] else "")
          + (f"; load alone but fail next to the others: {summary['clashOnly']}" if summary["clashOnly"] else ""))
    if save:
        path = ROOT / "bench" / "exposure" / f"{save}.json"
        path.write_text(json.dumps(summary, indent=1) + "\n", encoding="utf-8")
        print(f"saved {path}")
    return summary


HOP = re.compile(r"linked (\d+)/(\d+) classes, (\d+)/(\d+) methods, (\d+)/(\d+) fields")


def steps(frm, to):
    classes = compile_tools()
    versions = chain(frm, to)
    target = ROOT / ".runs" / "exposure" / f"steps-{frm}-{to}-{tools_key(*MAP_SOURCES)}.json"
    if not target.is_file():
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
    ls = sub.add_parser("updates", help="every LOADER:FROM:TO all runs, updates and jumps, as JSON (the cloud run's matrix)")
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
        print(json.dumps([f"{lo}:{x}:{y}" for lo in ([a.loader] if a.loader else sorted(FIRST)) for x, y in updates(lo) + jumps(lo)
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
