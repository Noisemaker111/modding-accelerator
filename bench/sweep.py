"""The port sweep: port a broad corpus of real mods across common jumps and rank what blocks them.

    python bench/sweep.py plan [--only IDS]                    the corpus and the ports a sweep runs, running nothing
    python bench/sweep.py run --tag NAME [--only IDS] [--jobs 2] [--no-game] [--resume]
    python bench/sweep.py summary --tag NAME [--compare TAG,TAG] [--save]
    python bench/sweep.py classify RUN.json                    reclassify one recorded run (after a classifier change)

The corpus is bench/sweep/corpus.json: open-source mods pinned to a commit, used only as test subjects. Each mod
is ported from its pinned version over the jumps of plan(): the adjacent release, and the common long jumps
(older releases -> 1.20.1, 1.20.x -> 1.21.1, 1.21.x -> 1.21.6 and -> 26.3, 26.x one release either way). Every
port is `modaccel port --fix` on a fresh reset of the pinned source; nothing is written by hand.

Per run, .runs/sweep/runs/TAG/ID__FROM__TO.json records the build result, the errors before and after, every error
left with its class and the API it is about, wall times, and, when it built, a hidden real-game load check (ui-check,
then the loader's own mod list asked through eval) and a behaviour check: every block and item the mod's own assets
name must be in the running game's registries (and any per-mod check the corpus entry adds). Launches follow
~/.modaccel/preferences.json (hidden and muted); at most --jobs ports or games run at once.

Error classes: missing-mapping (the route's map knows a rename the port did not apply, or there is no map for the
route), signature-change, semantic-api-change (removed or redesigned), dependency-unavailable, build-tooling,
mixin-target-change, access-transformer, resource-format, tool-crash, other. summary ranks classes and APIs by how
many mods they block; --save writes the compact result to bench/sweep/results/TAG.json so the curve is kept.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import threading
import time
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CORPUS = ROOT / "bench" / "sweep" / "corpus.json"
RESULTS = ROOT / "bench" / "sweep" / "results"
WORK = ROOT / ".runs" / "sweep"
INDEX = ROOT / "coverage" / "changes" / "index.json"
WINDOWS = os.name == "nt"
MODACCEL = [str(ROOT / ("modaccel.cmd" if WINDOWS else "modaccel"))]
KEEP = (".gradle", "build", ".modaccel", ".runs", "run", "runs")
CLASSES = ("missing-mapping", "signature-change", "semantic-api-change", "dependency-unavailable", "build-tooling",
           "mixin-target-change", "access-transformer", "resource-format", "tool-crash", "other")
GAME_PACKAGES = ("net.minecraft", "com.mojang", "net.fabricmc", "net.neoforged", "net.minecraftforge", "org.lwjgl",
                 "com.google", "it.unimi", "org.joml", "io.netty", "org.slf4j", "org.apache", "java", "javax", "org.spongepowered",
                 "org.jetbrains", "org.jspecify", "com.llamalad7", "org.objectweb", "net.fabric")
FIRST_UNOBFUSCATED = (26, 1)


def vkey(v):
    return tuple(int(x) for x in re.findall(r"\d+", v))


def releases():
    data = json.loads(INDEX.read_text(encoding="utf-8"))
    return [v["id"] for v in data["allVersions"] if v.get("status") == "indexed"]


def plan(mod, versions=None):
    """The (to, kind) jumps a mod is ported over, unless the corpus entry lists its own."""
    if mod.get("jumps"):
        return [(j, "listed") for j in mod["jumps"]]
    versions = versions or releases()
    frm = mod["from"]
    if frm not in versions:
        return []
    i = versions.index(frm)
    out = []
    if i + 1 < len(versions):
        out.append((versions[i + 1], "adjacent"))
    if frm.startswith("26."):
        out.append((versions[i - 1], "adjacent-back"))
    elif vkey(frm) < (1, 20):
        out.append(("1.20.1", "to-1.20.1"))
    elif vkey(frm) < (1, 21):
        out.append(("1.21.1", "to-1.21.1"))
    else:
        if vkey(frm) < (1, 21, 6):
            out.append(("1.21.6", "to-1.21.6"))
        out.append(("26.3", "to-26.x"))
    seen, result = set(), []
    for to, kind in out:
        if to != frm and to not in seen:
            seen.add(to)
            result.append((to, kind))
    return result


def load_corpus(only=None):
    mods = json.loads(CORPUS.read_text(encoding="utf-8"))["mods"]
    if only:
        want = [x.strip() for x in only.split(",") if x.strip()]
        unknown = [w for w in want if w not in {m["id"] for m in mods}]
        if unknown:
            raise SystemExit(f"No corpus entry {', '.join(unknown)}")
        mods = [m for m in mods if m["id"] in want]
    return mods


# ---- running ------------------------------------------------------------------------------------
def sh(cmd, cwd=None, timeout=None):
    """Run a command; never raises on exit code. A timeout kills the whole tree."""
    p = subprocess.Popen([str(c) for c in cmd], cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                         start_new_session=not WINDOWS)
    try:
        out, err = p.communicate(timeout=timeout)
        return p.returncode, out.decode("utf-8", "replace"), err.decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        if WINDOWS:
            subprocess.run(["taskkill", "/T", "/F", "/PID", str(p.pid)], capture_output=True)
        else:
            os.killpg(os.getpgid(p.pid), 9)
        out, err = p.communicate()
        return None, out.decode("utf-8", "replace"), err.decode("utf-8", "replace")


def last_json(text):
    for line in reversed(text.splitlines()):
        line = line.strip()
        if line.startswith("{"):
            try:
                return json.loads(line)
            except json.JSONDecodeError:
                continue
    return None


def checkout(mod):
    """The pinned source, shallow-fetched once into .runs/sweep/clones/ID and reset before every port."""
    work = WORK / "clones" / mod["id"]
    if not (work / ".git").exists():
        work.mkdir(parents=True, exist_ok=True)
        sh(["git", "init", "-q"], cwd=work)
        sh(["git", "config", "core.longpaths", "true"], cwd=work)
        sh(["git", "remote", "add", "origin", mod["repo"]], cwd=work)
    else:
        sh(MODACCEL + ["port", "--root", work, "--undo"], timeout=600)
    if sh(["git", "cat-file", "-e", mod["commit"] + "^{commit}"], cwd=work)[0]:
        code, _, err = sh(["git", "fetch", "-q", "--depth", "1", "origin", mod["commit"]], cwd=work, timeout=1800)
        if code:
            raise RuntimeError(f"git fetch {mod['commit'][:10]} failed: {err.strip()[-300:]}")
    code, _, err = sh(["git", "checkout", "-q", "--force", "--detach", mod["commit"]], cwd=work)
    if code:
        raise RuntimeError(f"git checkout failed: {err.strip()[-300:]}")
    sh(["git", "clean", "-fdq"] + [x for k in KEEP for x in ("-e", k)], cwd=work)
    return work


def java_lines(work):
    files = lines = 0
    for p in work.rglob("*.java"):
        if any(part in ("build", ".gradle", ".modaccel") for part in p.relative_to(work).parts):
            continue
        try:
            lines += len(p.read_bytes().splitlines())
            files += 1
        except OSError:
            pass
    return files, lines


def baseline(mod, tag, timeout, jobs):
    """Does the pinned source build on its own version? A port of a source that never built is not a porting result.
    Kept per tag in .runs/sweep/runs/TAG/ID__baseline.json, so a resumed sweep does not build it again."""
    out_file = WORK / "runs" / tag / f"{mod['id']}__baseline.json"
    if out_file.is_file():
        return json.loads(out_file.read_text(encoding="utf-8"))
    t0 = time.time()
    rec = {"id": mod["id"], "from": mod["from"]}
    try:
        with jobs:
            work = checkout(mod)
            cmd = MODACCEL + ["compile", "--root", work, "--json"] + (["--project", mod["project"]] if mod.get("project") else [])
            code, out, err = sh(cmd, timeout=timeout)
        res = last_json(out) or {}
        r = res.get("result", res)
        rec["ok"] = code == 0
        if not rec["ok"]:
            text = " ".join(str(r.get("error") or r.get("details") or r.get("reason") or err[-600:] or "no answer").split())
            rec["class"] = "compile-errors" if re.search(r"error: |compiler error", text) else gradle_class(text)
            rec["detail"] = text[:400]
    except RuntimeError as e:
        rec.update(ok=False, **{"class": "build-tooling", "detail": "checkout: " + str(e)})
    rec["seconds"] = round(time.time() - t0, 1)
    out_file.parent.mkdir(parents=True, exist_ok=True)
    out_file.write_text(json.dumps(rec, indent=1, ensure_ascii=False), encoding="utf-8")
    return rec


def port_run(mod, to, kind, tag, timeout, game, jobs, base=None):
    out_file = WORK / "runs" / tag / f"{mod['id']}__{mod['from']}__{to}.json"
    rec = {"id": mod["id"], "mod": mod["mod"], "loader": mod["loader"], "names": mod["names"], "kinds": mod["kinds"],
           "commit": mod["commit"], "from": mod["from"], "to": to, "jump": kind, "tag": tag,
           "started": time.strftime("%Y-%m-%dT%H:%M:%S")}
    if base is not None:
        rec["baseline"] = {k: base.get(k) for k in ("ok", "class", "detail") if base.get(k) is not None}
    t0 = time.time()
    try:
        with jobs:
            work = checkout(mod)
            rec["javaFiles"], rec["javaLines"] = java_lines(work)
            cmd = MODACCEL + ["port", "--root", work, "--from", mod["from"], "--to", to, "--fix", "--json"]
            if mod.get("project"):
                cmd += ["--project", mod["project"]]
            code, out, err = sh(cmd, timeout=timeout)
            if "being used by another process" in out + err:
                # Two jobs set up the same game in Loom's shared cache at once (Windows file lock): not the port's result.
                rec["retried"] = "shared Gradle cache file locked by the other job"
                checkout(mod)
                code, out, err = sh(cmd, timeout=timeout)
            rec["seconds"] = {"port": round(time.time() - t0, 1)}
            receipt = last_json(out)
            if code is None:
                rec["crash"] = f"no answer in {timeout} s"
            elif receipt is None:
                rec["crash"] = "modaccel port printed no JSON: " + " ".join((err or out).split())[-600:]
            else:
                rec["receipt"] = receipt.get("receipt")
                rec["result"] = slim(receipt)
    except RuntimeError as e:
        rec["crash"] = "checkout: " + str(e)
        rec.setdefault("seconds", {"port": round(time.time() - t0, 1)})
    classify(rec)
    if game and rec["build"]:
        with jobs:
            try:
                game_checks(mod, rec, WORK / "clones" / mod["id"])
            except Exception as e:   # a sweep bug must not lose the port result
                rec["load"] = {"ok": False, "step": "sweep", "detail": f"the sweep's game check failed: {e!r}"[:400]}
    out_file.parent.mkdir(parents=True, exist_ok=True)
    out_file.write_text(json.dumps(rec, indent=1, ensure_ascii=False), encoding="utf-8")
    return rec


def slim(receipt):
    """The parts of a port receipt the sweep reads (the whole receipt stays where modaccel wrote it)."""
    r = receipt.get("result", receipt)
    keep = {k: r.get(k) for k in ("ok", "tier", "compiled", "built", "errorsBefore", "reason", "details", "error",
                                  "unresolved", "blocked", "mixinCheck", "modMetadata", "rulesApplied", "log", "stoppedSafely")
            if r.get(k) is not None}
    if receipt.get("error") and "error" not in keep:
        keep["error"] = receipt["error"]
    if isinstance(keep.get("mixinCheck"), dict):
        keep["mixinCheck"] = {k: keep["mixinCheck"].get(k) for k in ("ok", "checked", "tier", "details", "reason", "failures")
                              if keep["mixinCheck"].get(k) is not None}
    for k in ("details", "reason", "error"):
        if isinstance(keep.get(k), str):
            keep[k] = keep[k][-4000:]
    if isinstance(keep.get("rulesApplied"), dict):
        keep["rulesApplied"] = sorted(keep["rulesApplied"])
    return keep


# ---- classification -----------------------------------------------------------------------------
SIGNATURE = re.compile(r"cannot be applied to given types|no suitable (?:method|constructor)|incompatible types|"
                       r"does not override|is not abstract and does not override|cannot be converted|unreported exception|"
                       r"wrong number of type arguments|does not take parameters|cannot inherit|bad operand|"
                       r"cannot return a value|non-static|weaker access|might not have been initialized|"
                       r"cannot be dereferenced|is already defined|not a functional interface|cannot infer type|"
                       r"unexpected return value|missing return value|abstract; cannot be instantiated|"
                       r"cannot assign a value to final|is not compatible with|"
                       r"call to super must be first|is not applicable|enum switch case label|"
                       r"an enum switch|for-each not applicable|no interface expected|interface expected|"
                       r"cannot select from|annotation .* is missing|is not an annotation type|cannot be accessed from outside package")
NOT_FOUND = re.compile(r"cannot find symbol|does not exist|cannot access")
ACCESS = re.compile(r"has private access|is not public|has protected access|access widener|accesstransformer|access transformer", re.I)
MIXIN_MESSAGE = re.compile(r"Mixin|@Shadow|@Inject|@Redirect|@ModifyArg|@Accessor|@Invoker|@Overwrite|Unable to locate|refmap|"
                           r"Multi-target|target method|Cannot find target|Invalid descriptor|obfuscation mapping", re.I)
MIXIN_LINE = re.compile(r"@(?:Shadow|Accessor|Invoker|Inject|Redirect|ModifyArg|ModifyArgs|ModifyVariable|ModifyConstant|"
                        r"ModifyExpressionValue|ModifyReturnValue|WrapOperation|WrapWithCondition|WrapMethod|At|Mixin|Overwrite)\b")
_SOURCE_CACHE = {}


def source_lines(path):
    if path not in _SOURCE_CACHE:
        try:
            p = path if not WINDOWS or len(path) < 240 else "\\\\?\\" + path
            _SOURCE_CACHE[path] = Path(p).read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            _SOURCE_CACHE[path] = []
    return _SOURCE_CACHE[path]


def imported(lines, symbol):
    for line in lines:
        m = re.match(r"\s*import\s+(?:static\s+)?([\w.]+)\s*;", line)
        if m and (m.group(1).endswith("." + symbol) or m.group(1).split(".")[-1] == symbol):
            return m.group(1)
    return None


def package_of(lines):
    for line in lines:
        m = re.match(r"\s*package\s+([\w.]+)\s*;", line)
        if m:
            return m.group(1)
    return ""


def third_party(name, own):
    if not name:
        return False
    own_root = ".".join(own.split(".")[:2])
    if own_root and (name == own_root or name.startswith(own_root + ".")):
        return False
    return not name.startswith(GAME_PACKAGES)


def error_class(e, rec):
    """The class of one compiler error left, and the API it is about."""
    msg = str(e.get("message", ""))
    symbol, owner = e.get("symbol"), e.get("owner")
    lines = source_lines(str(e.get("file", "")))
    line_no = int(e.get("line") or 0)
    here = "\n".join(lines[max(0, line_no - 2):line_no]) if lines else ""
    own = package_of(lines)
    pkg = re.search(r"package ([\w.]+) does not exist", msg)
    api = (f"{owner}.{symbol}" if owner and symbol else symbol) or (pkg.group(1) if pkg else None)
    applied = re.search(r"(?:method|constructor) (\w+) in (?:class|interface|enum|record) ([\w.$<>,? ]+?)(?: cannot| is)", msg)
    if not api and applied:
        api = applied.group(2).split("<")[0].split(".")[-1] + "." + applied.group(1)
    if not api:
        api = re.sub(r"\s+", " ", msg)[:70]
    changes = [str(c.get("change", "")) for c in (e.get("changedIn") or []) if isinstance(c, dict)]
    first = changes[0] if changes else ""
    if MIXIN_MESSAGE.search(msg) or (MIXIN_LINE.search(here) and any("@Mixin" in l for l in lines[:80])):
        return "mixin-target-change", api
    if ACCESS.search(msg):
        return "access-transformer", api
    if pkg and third_party(pkg.group(1), own):
        return "dependency-unavailable", pkg.group(1)
    if NOT_FOUND.search(msg) and symbol:
        where = imported(lines, symbol) if lines else None
        if where and third_party(where, own):
            # The library resolved (its package is there) but this name is not: its own API changed.
            return "semantic-api-change", "lib " + where
    if SIGNATURE.search(msg):
        return ("signature-change" if not (" renamed " in first or " moved " in first) else "missing-mapping"), api
    if NOT_FOUND.search(msg):
        if " renamed " in first or " moved " in first:
            return "missing-mapping", api
        if rec.get("names") == "yarn" and vkey(rec["to"]) >= FIRST_UNOBFUSCATED:
            return "missing-mapping", api
        if " signature " in first:
            return "signature-change", api
        return "semantic-api-change", api
    return "other", api


def gradle_class(text):
    """The class of a failure Gradle reported without a compiler error."""
    t = text or ""
    if re.search(r"No such file or folder: .*\.modaccel|modaccel failed|Internal error", t):
        return "tool-crash"
    if re.search(r"Some of your mods are incompatible|Could not resolve|Could not find [\w.-]+:[\w.-]+|not found in any of the following|dependency-blockers|"
                 r"Could not GET|Could not HEAD|No matching variant|Received status code 40[134]", t):
        return "dependency-unavailable"
    if re.search(r"accesstransformer|access transformer|AccessTransformer|accessWidener|access widener", t):
        return "access-transformer"
    if re.search(r"Mixin|mixin", t) and re.search(r"apply|target|inject|transform", t, re.I):
        return "mixin-target-change"
    if re.search(r"processResources|JsonSyntaxException|MalformedJson|pack_format|mods\.toml|fabric\.mod\.json|"
                 r"neoforge\.mods\.toml|runData|datagen|Failed to parse", t):
        return "resource-format"
    if re.search(r"Exception in thread|NullPointerException|IndexOutOfBounds|IllegalStateException: |at tools\.|"
                 r"Typed repair stopped|Typed preflight stopped|ClassCastException|StackOverflowError", t):
        return "tool-crash"
    return "build-tooling"


def classify(rec):
    """Fills rec: build (bool), errorsBefore, errorsAfter, errors (each with class and api), blocker (class of a stop
    that has no compiler error), classes (count per class)."""
    r = rec.get("result") or {}
    rec["build"] = bool(r.get("ok")) and bool(r.get("built") or r.get("compiled"))
    rec["errorsBefore"] = r.get("errorsBefore")
    errors, blocker = [], None
    if rec.get("crash"):
        blocker = ("tool-crash" if "no answer" in rec["crash"] or "printed no JSON" in rec["crash"] else "build-tooling",
                   rec["crash"][:300])
    elif r.get("tier") == "dependency-blockers":
        blocker = ("dependency-unavailable", "; ".join(f"{b.get('artifact')} {b.get('version')}" for b in r.get("blocked") or [])[:300])
        for b in r.get("blocked") or []:
            errors.append({"class": "dependency-unavailable", "api": str(b.get("artifact")), "message": str(b.get("reason", ""))[:200]})
    elif r.get("error") and not r.get("tier"):
        text = str(r["error"])
        if re.search(r"No verified mapping", text):
            cls = "missing-mapping"
        elif re.search(r"does not match this project|Evaluated build did not select|Choose --project|--project", text):
            cls = "build-tooling"
        else:
            cls = gradle_class(text)
            if cls == "build-tooling" and re.search(r"Exception|\bat [\w.$]+\(", text) and "Gradle" not in text:
                cls = "tool-crash"
        blocker = (cls, " ".join(text.split())[:300])
    elif not rec["build"]:
        if r.get("compiled") and isinstance(r.get("mixinCheck"), dict) and not r["mixinCheck"].get("ok"):
            mc = r["mixinCheck"]
            blocker = ("mixin-target-change" if mc.get("checked") else "build-tooling", " ".join(str(mc.get("details") or mc.get("reason")).split())[:300])
            for line in str(mc.get("details") or "").splitlines():
                m = re.search(r"([\w.$]+Mixin\w*|[\w$]+\.java)", line)
                if m and ("fail" in line.lower() or "error" in line.lower() or "could not" in line.lower()):
                    errors.append({"class": "mixin-target-change", "api": m.group(1), "message": line.strip()[:200]})
        elif r.get("modMetadata") and r["modMetadata"].get("ok") is False:
            blocker = ("resource-format", " ".join(str(r["modMetadata"].get("details")).split())[:300])
        elif r.get("unresolved"):
            for e in r["unresolved"]:
                cls, api = error_class(e, rec)
                errors.append({"class": cls, "api": api, "message": str(e.get("message", ""))[:200],
                               "file": str(e.get("file", ""))[-120:], "line": e.get("line"),
                               "changed": ([c.get("change") for c in e.get("changedIn") or [] if isinstance(c, dict)][:1] or [None])[0]})
        else:
            text = str(r.get("reason") or r.get("details") or "")
            cls = gradle_class(text)
            # Gradle's summary only says a tool failed; the log says why (NeoForge's recompile of the game, an AT entry).
            log = Path(str(r.get("log") or ""))
            if cls == "build-tooling" and log.is_file():
                tail = log.read_text(encoding="utf-8", errors="replace")[-20000:]
                if re.search(r"weaker access privileges|AccessTransformer|accesstransformer|Access transformer", tail):
                    cls = "access-transformer"
                elif re.search(r"Mixin apply failed|InvalidMixinException|MixinTransformerError", tail):
                    cls = "mixin-target-change"
                else:
                    cls = gradle_class(tail[-4000:]) if gradle_class(tail[-4000:]) != "tool-crash" else cls
            blocker = (cls, " ".join(text.split())[:300])
    rec["errors"] = errors
    rec["errorsAfter"] = 0 if rec["build"] else (len([e for e in errors if "file" in e]) if r.get("unresolved") else None)
    rec["blocker"] = {"class": blocker[0], "detail": blocker[1]} if blocker else None
    classes = Counter(e["class"] for e in errors)
    if blocker and not errors:
        classes[blocker[0]] += 1
    rec["classes"] = dict(classes)
    return rec


# ---- game checks --------------------------------------------------------------------------------
REGISTRY_CHECK = r"""
Class<?> regs = null;
for (String n : new String[] {"net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.registry.Registries", "net.minecraft.core.Registry", "net.minecraft.util.registry.Registry"}) {
    try { regs = Class.forName(n, true, getClass().getClassLoader()); break; } catch (ClassNotFoundException next) {}
}
java.util.Set<String> have = new java.util.HashSet<>();
for (String kind : new String[] {"BLOCK", "ITEM"}) {
    Object reg = field(regs, kind);
    Object keys;
    try { keys = call(reg, "keySet"); } catch (Exception yarn) { keys = call(reg, "getIds"); }
    for (Object k : (Iterable<?>) keys) have.add(kind + " " + k);
}
java.util.List<String> missing = new java.util.ArrayList<>();
for (String want : WANTED) if (!have.contains(want)) missing.add(want);
return missing.isEmpty() ? "ok " + WANTED.length : "missing " + missing.size() + " of " + WANTED.length + ": " + missing.subList(0, Math.min(8, missing.size()));
"""


def mod_metadata(work, mod):
    """(mod id, blocks and items its own assets name) for the ported project, from its resources."""
    base = work / mod["project"].strip(":").replace(":", "/") if mod.get("project") and not mod["project"].strip(":")[0].isdigit() else work
    props = {}
    for f in (work / "gradle.properties", base / "gradle.properties"):
        if f.is_file():
            for line in f.read_text(encoding="utf-8", errors="replace").splitlines():
                k, _, v = line.partition("=")
                props[k.strip()] = v.strip()

    def expand(s):
        return re.sub(r"\$\{(\w+)\}", lambda m: props.get(m.group(1), m.group(0)), s)

    modid = None
    roots = [base, work] if base != work else [work]
    for r in roots:
        for p in list(r.rglob("fabric.mod.json")) + list(r.rglob("neoforge.mods.toml")) + list(r.rglob("mods.toml")):
            if any(x in ("build", ".gradle", "bin", "out") for x in p.relative_to(work).parts):
                continue
            text = p.read_text(encoding="utf-8", errors="replace")
            if mod["loader"] == "fabric" and p.name != "fabric.mod.json":
                continue
            if mod["loader"] != "fabric" and p.name == "fabric.mod.json":
                continue
            m = re.search(r'"id"\s*:\s*"([^"]+)"', text) if p.name.endswith(".json") else re.search(r'(?m)^\s*modId\s*=\s*"([^"]+)"', text)
            if m:
                modid = expand(m.group(1))
                break
        if modid:
            break
    if modid and "${" in modid:
        modid = props.get("mod_id") or props.get("modid") or props.get("archives_base_name")
    wanted = set()
    if modid:
        for kind, folder in (("BLOCK", "blockstates"), ("ITEM", "models/item")):
            for p in work.rglob(f"assets/{modid}/{folder}/*.json"):
                if any(x in ("build", ".gradle", "bin", "out") for x in p.relative_to(work).parts):
                    continue
                wanted.add(f"{kind} {modid}:{p.stem}")
    return modid, sorted(wanted)


def game_checks(mod, rec, work):
    """Hidden real-game load check and, where the mod has content, a registry behaviour check."""
    proj = ["--project", mod["project"]] if mod.get("project") else []
    t0 = time.time()
    code, out, err = sh(MODACCEL + ["ui-check", "--root", work, "--json"] + proj, timeout=1800)
    res = last_json(out) or {}
    if code != 0 and "Framebuffer was" in json.dumps(res):
        # The hidden window had not taken its 1280x720 size by the first capture (seen on 26.1.1); the game did start.
        rec["uiCheckRetried"] = " ".join(str((res.get("result") or res).get("error")).split())[:200]
        code, out, err = sh(MODACCEL + ["ui-check", "--root", work, "--json"] + proj, timeout=1800)
        res = last_json(out) or {}
    rec["load"] = {"ok": code == 0, "step": "ui-check", "detail": " ".join(str((res.get("result") or res).get("error") or (res.get("result") or res).get("details") or err[-400:] or "").split())[:400]}
    modid, wanted = mod_metadata(work, mod)
    rec["modid"] = modid
    if code == 0 and modid:
        snippet = (f'net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("{modid}")' if mod["loader"] == "fabric"
                   else f'net.neoforged.fml.ModList.get().isLoaded("{modid}")')
        code, out, err = sh(MODACCEL + ["eval", "--root", work, "--code", snippet, "--json"] + proj, timeout=600)
        res = last_json(out) or {}
        value = str((res.get("result") or res).get("value"))
        rec["load"] = {"ok": code == 0 and value.strip() == "true", "step": "mod list", "detail": f"{modid} loaded: {value}"}
    rec["seconds"]["load"] = round(time.time() - t0, 1)
    if rec["load"]["ok"]:
        t1 = time.time()
        checks = []
        if wanted:
            snippet = "String[] WANTED = {" + ",".join(json.dumps(w) for w in wanted) + "};\n" + REGISTRY_CHECK
            f = WORK / "snippets" / f"{mod['id']}-registry.java"
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(snippet, encoding="utf-8")
            code, out, err = sh(MODACCEL + ["eval", "--root", work, "--file", f, "--json"] + proj, timeout=600)
            res = last_json(out) or {}
            value = str((res.get("result") or res).get("value") or (res.get("result") or res).get("error") or err[-300:])
            checks.append({"check": f"{len(wanted)} blocks and items from the mod's assets are registered", "ok": code == 0 and value.startswith("ok"), "detail": value[:400]})
        for b in mod.get("behaviour") or []:
            code, out, err = sh(MODACCEL + ["eval", "--root", work, "--code", b["code"], "--json"] + proj, timeout=600)
            res = last_json(out) or {}
            value = str((res.get("result") or res).get("value"))
            checks.append({"check": b["what"], "ok": code == 0 and value.strip() == str(b["expect"]), "detail": value[:300]})
        rec["behaviour"] = {"ok": bool(checks) and all(c["ok"] for c in checks), "checks": checks} if checks else None
        rec["seconds"]["behaviour"] = round(time.time() - t1, 1)
    sh(MODACCEL + ["game-stop", "--root", work, "--json"] + proj, timeout=600)


# ---- summary ------------------------------------------------------------------------------------
def load_runs(tag):
    folder = WORK / "runs" / tag
    saved = RESULTS / f"{tag}.json"
    if folder.is_dir():
        return [json.loads(p.read_text(encoding="utf-8")) for p in sorted(folder.glob("*__*__*.json"))]
    if saved.is_file():
        return json.loads(saved.read_text(encoding="utf-8"))["runs"]
    raise SystemExit(f"No sweep {tag}: neither {folder} nor {saved}")


def compact(r):
    return {k: r.get(k) for k in ("id", "mod", "loader", "names", "kinds", "from", "to", "jump", "javaLines", "build",
                                  "errorsBefore", "errorsAfter", "classes", "blocker", "seconds", "modid")} | {
        "baseline": r.get("baseline") if isinstance(r.get("baseline"), (bool, type(None))) else r["baseline"].get("ok"),
        "load": r.get("load") if isinstance(r.get("load"), (bool, type(None))) else r["load"].get("ok"), "behaviour": r.get("behaviour") if isinstance(r.get("behaviour"), (bool, type(None))) else r["behaviour"].get("ok"),
        "apis": sorted({f"{e['class']}|{e['api']}" for e in r.get("errors") or []})}


def rates(runs):
    n = len(runs)
    b = sum(1 for r in runs if r.get("build"))
    l = sum(1 for r in runs if (r.get("load") if isinstance(r.get("load"), bool) else (r.get("load") or {}).get("ok")))
    beh = [r for r in runs if (r.get("behaviour") if isinstance(r.get("behaviour"), (bool, type(None))) else (r.get("behaviour") or {}).get("ok")) is not None]
    bok = sum(1 for r in runs if (r.get("behaviour") if isinstance(r.get("behaviour"), (bool, type(None))) else (r.get("behaviour") or {}).get("ok")))
    return n, b, l, len(beh), bok


def summary(runs, compare=None):
    lines = []
    kinds = ["adjacent", "adjacent-back", "to-1.20.1", "to-1.21.1", "to-1.21.6", "to-26.x", "listed"]
    def base_ok(r):
        b = r.get("baseline")
        return b if isinstance(b, (bool, type(None))) else b.get("ok")

    lines.append("| Jump | Runs | Source builds | Builds | Loads | Behaviour (of checked) | Builds, of sources that build |")
    lines.append("|---|---|---|---|---|---|---|")
    for k in kinds + ["all"]:
        rs = runs if k == "all" else [r for r in runs if r.get("jump") == k]
        if not rs:
            continue
        n, b, l, nb, bok = rates(rs)
        good = [r for r in rs if base_ok(r)]
        gb = sum(1 for r in good if r.get("build"))
        lines.append(f"| {'**all**' if k == 'all' else k} | {n} | {len(good)} | {b} ({100 * b // n}%) | {l} | {bok} of {nb} | "
                     f"{gb} of {len(good)}{f' ({100 * gb // len(good)}%)' if good else ''} |")
    lines.append("")
    # classes: runs blocked (a class present in a run that did not build), mods, errors
    by_class = defaultdict(lambda: {"runs": 0, "mods": set(), "errors": 0, "only": 0})
    for r in runs:
        if r.get("build"):
            continue
        cl = r.get("classes") or {}
        for c, n in cl.items():
            g = by_class[c]
            g["runs"] += 1
            g["mods"].add(r["mod"])
            g["errors"] += n
            if len(cl) == 1:
                g["only"] += 1
    lines.append("| Error class | Runs it blocks | Sole blocker | Mods | Errors left |")
    lines.append("|---|---|---|---|---|")
    for c, g in sorted(by_class.items(), key=lambda kv: (-len(kv[1]["mods"]), -kv[1]["runs"], -kv[1]["errors"])):
        lines.append(f"| {c} | {g['runs']} | {g['only']} | {len(g['mods'])} | {g['errors']} |")
    lines.append("")
    by_api = defaultdict(lambda: {"mods": set(), "errors": 0, "jumps": set()})
    for r in runs:
        for e in r.get("errors") or []:
            g = by_api[(e["class"], e["api"])]
            g["mods"].add(r["mod"])
            g["errors"] += 1
            g["jumps"].add(f"{r['from']}->{r['to']}")
        if r.get("blocker") and not r.get("errors"):
            key = (r["blocker"]["class"], re.sub(r"\s+", " ", r["blocker"]["detail"])[:90])
            g = by_api[key]
            g["mods"].add(r["mod"])
            g["errors"] += 1
            g["jumps"].add(f"{r['from']}->{r['to']}")
    lines.append("| Class | API or blocker | Mods | Errors | Example jumps |")
    lines.append("|---|---|---|---|---|")
    for (c, api), g in sorted(by_api.items(), key=lambda kv: (-len(kv[1]["mods"]), -kv[1]["errors"]))[:40]:
        lines.append(f"| {c} | {str(api).replace('|', '/')[:90]} | {len(g['mods'])} | {g['errors']} | {', '.join(sorted(g['jumps'])[:3])} |")
    if compare:
        lines.append("")
        lines.append("| Sweep | Runs | Builds | Loads | Behaviour | Errors left (runs that compiled to errors) |")
        lines.append("|---|---|---|---|---|---|")
        for tag, rs in compare:
            n, b, l, nb, bok = rates(rs)
            left = sum(r.get("errorsAfter") or 0 for r in rs)
            lines.append(f"| {tag} | {n} | {b} | {l} | {bok} of {nb} | {left} |")
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("plan")
    p.add_argument("--only")
    p = sub.add_parser("run")
    p.add_argument("--tag", required=True)
    p.add_argument("--only")
    p.add_argument("--to", help="only jumps to these versions (comma-separated)")
    p.add_argument("--jobs", type=int, default=2, help="ports or games at once (at most 2 on a shared machine)")
    p.add_argument("--no-game", action="store_true")
    p.add_argument("--resume", action="store_true", help="skip runs this tag already has")
    p.add_argument("--timeout", type=int, default=5400)
    p.add_argument("--modaccel", type=Path, help="the modaccel launcher to test (default: this checkout's); another worktree's to sweep a fixed tool while this one changes")
    p = sub.add_parser("summary")
    p.add_argument("--tag", required=True)
    p.add_argument("--compare", help="other tags to show beside it, oldest first")
    p.add_argument("--save", action="store_true", help="write bench/sweep/results/TAG.json")
    p = sub.add_parser("classify")
    p.add_argument("run", type=Path, nargs="+")
    args = ap.parse_args(argv)
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")

    if args.cmd == "plan":
        versions = releases()
        total = 0
        for m in load_corpus(args.only):
            jumps = plan(m, versions)
            total += len(jumps)
            print(f"{m['id']:26} {m['loader']:8} {m['names']:6} {m['from']:7} -> {', '.join(f'{t} ({k})' for t, k in jumps)}")
        print(f"{total} ports")
        return 0
    if args.cmd == "classify":
        for f in args.run:
            rec = classify(json.loads(f.read_text(encoding="utf-8")))
            f.write_text(json.dumps(rec, indent=1, ensure_ascii=False), encoding="utf-8")
            print(f.name, rec["build"], rec["classes"])
        return 0
    if args.cmd == "summary":
        runs = load_runs(args.tag)
        compare = [(t, load_runs(t)) for t in args.compare.split(",")] + [(args.tag, runs)] if args.compare else None
        print(summary(runs, compare))
        if args.save:
            RESULTS.mkdir(parents=True, exist_ok=True)
            n, b, l, nb, bok = rates(runs)
            doc = {"tag": args.tag, "runs_total": n, "builds": b, "loads": l, "behaviour": bok, "behaviour_checked": nb,
                   "corpus": str(CORPUS.relative_to(ROOT)).replace("\\", "/"), "runs": [compact(r) for r in runs]}
            (RESULTS / f"{args.tag}.json").write_text(json.dumps(doc, indent=1, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
        return 0

    if args.modaccel:
        MODACCEL[:] = [str(args.modaccel.resolve())]
    mods = load_corpus(args.only)
    versions = releases()
    want_to = set(args.to.split(",")) if args.to else None
    queue = []
    for m in mods:
        jumps = [(t, k) for t, k in plan(m, versions) if not want_to or t in want_to]
        if args.resume:
            jumps = [(t, k) for t, k in jumps if not (WORK / "runs" / args.tag / f"{m['id']}__{m['from']}__{t}.json").exists()]
        if jumps:
            queue.append((m, jumps))
    total = sum(len(j) for _, j in queue)
    print(f"{total} ports over {len(queue)} mods, {args.jobs} at a time", file=sys.stderr, flush=True)
    jobs = threading.Semaphore(max(1, min(2, args.jobs)))
    lock = threading.Lock()
    done = [0]

    def worker():
        while True:
            with lock:
                if not queue:
                    return
                m, jumps = queue.pop(0)
            try:
                run_mod(m, jumps)
            finally:
                # Done with this mod: its Gradle daemons and any game exit now, not after an idle timeout.
                stop(m)

    def stop(m):
        work = WORK / "clones" / m["id"]
        if (work / ".modaccel").is_dir():
            sh(MODACCEL + ["game-stop", "--root", work, "--json"], timeout=600)

    def run_mod(m, jumps):
        base = baseline(m, args.tag, args.timeout, jobs)
        for to, kind in jumps:   # one mod's jumps share its clone, so they run in turn
            try:
                rec = port_run(m, to, kind, args.tag, args.timeout, not args.no_game, jobs, base)
            except Exception as e:
                print(f"sweep error on {m['id']} -> {to}: {e!r}", file=sys.stderr, flush=True)
                continue
            with lock:
                done[0] += 1
                state = "builds" if rec["build"] else (f"{rec['errorsAfter']} errors" if rec.get("errorsAfter") is not None else f"stopped: {(rec.get('blocker') or {}).get('class')}")
                if rec.get("load"):
                    state += ", loads" if rec["load"]["ok"] else ", does not load"
                if rec.get("behaviour"):
                    state += ", behaviour ok" if rec["behaviour"]["ok"] else ", behaviour fails"
                print(f"[{done[0]}/{total}] {m['id']} {m['from']} -> {to}: {state} ({rec['seconds'].get('port', 0):.0f} s)", file=sys.stderr, flush=True)

    threads = [threading.Thread(target=worker) for _ in range(max(1, min(2, args.jobs)))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    print(summary(load_runs(args.tag)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
