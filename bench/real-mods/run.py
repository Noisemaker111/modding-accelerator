#!/usr/bin/env python3
"""Real-mods test: put real mods through the whole modaccel loop on fresh clones.

For each mod in manifest.json: fresh clone at the pinned commit (or the branch head with
--latest) into a cache folder outside the repo, then through modaccel only: doctor, setup,
port (when the manifest asks), compile, test (when the checkout has tests), ui-check, a second
unchanged ui-check, a stale-game check (append a comment to one source file: ui-check must
refuse; revert: it must pass), eval, world-check on a shipped flat save, eval --until, reload of
a real class change (then ui-check must accept the reloaded game, then reload the revert),
game-stop, a restart of the unchanged project, ui-check --java on the game's own runtime,
game-stop, compile on every listed node, and a scan for game or Gradle daemon processes left behind.

A failed step never stops the run: the next mod still runs, and the exact error goes in the
report. Results: results/latest.json and results/REPORT.md. Exit code 1 if any step failed.
"""
import argparse, datetime, json, os, re, shutil, stat, subprocess, sys, time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
WINDOWS = os.name == "nt"
TOOL = REPO  # the modaccel checkout under test; --modaccel points it at another worktree for an A/B run
MODACCEL = [str(REPO / "modaccel.cmd")] if WINDOWS else [str(REPO / "modaccel")]
MARK = b"\n// modaccel real-mods stale-game check: this line must make ui-check refuse the running game\n"
GRADLE_HOME = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
TIMEOUTS = {"clone": 1800, "doctor": 600, "setup": 3600, "compile": 3600, "test": 3600, "ui-check": 1500}
GAME_STEPS = ["port", "compile", "test", "ui-check-1", "ui-check-2", "stale-refused", "revert-passes", "eval",
              "world-check", "eval-until", "reload", "reload-current", "reload-revert", "game-stop",
              "restart-unchanged", "java-ui-check", "game-stop-final"]
STEPS = ["clone", "doctor", "setup"] + GAME_STEPS + ["nodes-compile", "no-leftovers"]
SAVE = HERE / "saves/lab"  # a flat 1.20.1 save (level.dat only); 1.20.1 to 1.21.x games upgrade it on open
# 26.x asks to back up a world from an older version before opening it, so 26.x games get a save
# made by 26.3 itself (level and dimension data, no chunks; chunks regenerate from the seed).
SAVE_26 = HERE / "saves/lab-26.3"

# Known failure text -> the next action a person can take. Matching is a hint, not a verdict.
HINTS = [
    (r"Unsupported class file major version", "The mod's Gradle is older than the JDK it was started on; modaccel has to pick a JDK that Gradle version supports."),
    (r"Could not resolve|Could not find .*:.*:", "A dependency did not resolve; check the named coordinate and its repository."),
    (r"different build", "The running game was refused as a different build; if nothing changed, the fingerprint is unstable."),
    (r"configure-on-demand|configureondemand|on-demand", "The build uses configure-on-demand; modaccel's model or task run must work with it."),
    (r"No running game", "No game was running, so the check had nothing to talk to (an earlier ui-check failed)."),
    (r"Client startup failed|Client stopped before", "The client did not reach its first frame; read the game log named in the error."),
    (r"Choose --project and/or --task", "More than one client run task; set project in the manifest."),
    (r"timed out after", "The step ran past its time limit; read its log for where it stopped."),
]


def now():
    return time.monotonic()


def default_cache():
    base = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData/Local")) if WINDOWS else Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache"))
    return base / "modaccel-real-mods"


def kill_tree(pid):
    if WINDOWS:
        subprocess.run(["taskkill", "/T", "/F", "/PID", str(pid)], capture_output=True)
    else:
        try:
            os.killpg(os.getpgid(pid), 9)
        except Exception:
            pass


def run(command, cwd, log, timeout):
    """Run a command, write its output to log, return (exit, stdout, seconds). Kills the tree on timeout."""
    start = now()
    log.parent.mkdir(parents=True, exist_ok=True)
    with open(log, "wb") as out:
        out.write(("$ " + " ".join(command) + "\n").encode())
        out.flush()
        process = subprocess.Popen(command, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=not WINDOWS)
        try:
            data, _ = process.communicate(timeout=timeout)
            out.write(data)
            return process.returncode, data.decode("utf-8", "replace"), now() - start
        except subprocess.TimeoutExpired:
            kill_tree(process.pid)
            data, _ = process.communicate()
            out.write(data or b"")
            return 124, f"timed out after {timeout}s\n" + (data or b"").decode("utf-8", "replace"), now() - start


def last_json(text):
    for line in reversed(text.splitlines()):
        line = line.strip()
        if line.startswith("{"):
            try:
                return json.loads(line)
            except ValueError:
                continue
    return None


def short(text, limit=900):
    text = (text or "").strip()
    return text if len(text) <= limit else text[:limit] + " ..."


def hint(text):
    for pattern, advice in HINTS:
        if re.search(pattern, text or "", re.I):
            return advice
    return "Read the step log; the error above is the first thing to fix."


def find_png(value):
    if isinstance(value, str):
        return value if value.lower().endswith(".png") else None
    if isinstance(value, dict):
        value = list(value.values())
    if isinstance(value, list):
        for item in value:
            found = find_png(item)
            if found:
                return found
    return None


def known(mod, node, error):
    """An upstream failure the manifest records with its exact error text; anything else is a real failure."""
    entry = (mod.get("known_failures") or {}).get(node)
    return entry if entry and entry["signature"] in (error or "") else None


def classify(mod, results):
    """Marks recorded upstream failures; returns the nodes that really failed."""
    failed = []
    for node, entry in results.items():
        for record in entry.values() if "status" not in entry else [entry]:
            if record["status"] == "fail":
                reason = known(mod, node, record.get("fullError", record.get("error", "")))
                if reason:
                    record["status"] = "known-failure"
                    record["upstream"] = reason["reason"]
                elif node not in failed:
                    failed.append(node)
            elif record["status"] == "pass" and node in (mod.get("known_failures") or {}):
                record["knownFailureNowPasses"] = "remove this node from known_failures in manifest.json"
            record.pop("fullError", None)
    return failed


class ModRun:
    def __init__(self, mod, cache, logs, latest):
        self.mod, self.latest = mod, latest
        self.root = cache / mod["id"]
        self.logs = logs / mod["id"]
        self.steps = {}
        self.record = {k: mod.get(k, "") for k in ("id", "name", "repo", "branch", "commit", "project", "loader", "minecraft", "shape")}
        self.record["pinned"] = not latest

    def modaccel(self, name, verb, extra=(), timeout=None, project=None):
        args = MODACCEL + [verb, "--root", str(self.root)]
        project = self.mod.get("project") if project is None else project
        if project and verb != "doctor":
            args += ["--project", project]
        args += list(extra) + ["--json"]
        exit, out, seconds = run(args, TOOL, self.logs / f"{name}.log", timeout or TIMEOUTS.get(verb, 900))
        data = last_json(out) or {}
        result = data.get("result") if isinstance(data.get("result"), dict) else {}
        error = result.get("error") or result.get("reason") or ("" if exit == 0 else (result.get("details") or short(out, 1500)))
        return {"exit": exit, "seconds": round(seconds, 1), "data": data, "result": result, "error": error,
                "receipt": data.get("receipt", ""), "log": str(self.logs / f"{name}.log")}

    def snippet(self, name, code):
        path = self.logs / f"{name}.java"
        path.write_text(code, encoding="utf-8")
        return ["--file", str(path)]

    def put(self, name, status, seconds=0.0, error="", **extra):
        step = {"status": status, "seconds": round(seconds, 1)}
        if error:
            step["error"] = short(error)
            if status == "fail":
                step["next"] = hint(error)
        step.update({k: v for k, v in extra.items() if v not in (None, "")})
        self.steps[name] = step
        return step

    def from_call(self, name, call, ok=None, error=None, **extra):
        passed = call["exit"] == 0 if ok is None else ok
        return self.put(name, "pass" if passed else "fail", call["seconds"], "" if passed and error is None else (error or call["error"] or f"exit {call['exit']}"),
                        receipt=call["receipt"], log=call["log"], **extra)

    def ui(self, name, extra=()):
        call = self.modaccel(name, "ui-check", extra)
        return self.from_call(name, call, capture=find_png(call["result"].get("capture")) or find_png(call["result"]))

    def block(self, names, reason):
        for name in names:
            if name not in self.steps:
                self.put(name, "blocked", error=reason)

    def go(self):
        start_wall = time.time()
        self.record["startedAt"] = datetime.datetime.now().astimezone().isoformat(timespec="seconds")
        self.record["cache"] = {"freshClone": True, "gradleUserHome": str(GRADLE_HOME), "gradleCacheWarm": gradle_cache_warm()}
        self.clone()
        if self.steps["clone"]["status"] != "pass":
            self.block(STEPS, "no checkout: clone failed")
            return self.finish()
        self.record["cache"]["wrapperDistCached"] = wrapper_cached(self.root)
        if ARGS.compile_nodes:
            return self.compile_nodes(start_wall)
        for name in ("doctor", "setup"):
            self.from_call(name, self.modaccel(name, name))
        model = self.root / ".modaccel/portable/model.json"
        if model.exists():
            # Kept for A/B comparison: the evaluated model this modaccel produced for a fresh clone.
            shutil.copyfile(model, self.logs / "model.json")
            self.record["model"] = str(self.logs / "model.json")
        if self.mod.get("setup_only"):
            reason = "setup-only entry: " + self.mod["setup_only"]
            for name in GAME_STEPS:
                self.put(name, "n/a", error=reason)
        else:
            self.game_flow()
        self.nodes()
        if "game-stop-final" not in self.steps or self.steps["game-stop-final"]["status"] == "n/a":
            # Setup and node compiles start Gradle daemons too; game-stop is what stops them.
            self.from_call("game-stop-final", self.modaccel("game-stop-final", "game-stop", timeout=300))
        self.leftovers(start_wall)
        return self.finish()

    def game_flow(self):
        port = self.mod.get("port")
        if port:
            self.from_call("port", self.modaccel("port", "port", ["--from", port["from"], "--to", port["to"], "--fix"], timeout=3600),
                           hop=f"{port['from']} -> {port['to']}")
        else:
            self.put("port", "n/a", error="manifest asks for no port; the checkout runs as published")
        self.from_call("compile", self.modaccel("compile", "compile"))
        tests = test_sources(self.root)
        if tests:
            self.from_call("test", self.modaccel("test", "test"), tests=len(tests))
        else:
            self.put("test", "n/a", error="no test sources (no src/test files in this checkout)")
        self.ui("ui-check-1")
        self.ui("ui-check-2")
        if not self.running():
            self.block(GAME_STEPS[GAME_STEPS.index("stale-refused"):GAME_STEPS.index("game-stop")],
                       "no running game (ui-check did not leave one running), so there is nothing to check against")
        else:
            self.stale_check()
            self.eval_steps()
            self.world_check()
            self.reload_check()
        stop = self.modaccel("game-stop", "game-stop", timeout=300)
        stopped = stop["exit"] == 0 and stop["result"].get("gradleDaemonsStopped", True) is not False
        self.from_call("game-stop", stop, ok=stopped, error=None if stopped else (stop["error"] or "gradleDaemonsStopped=false"))
        # An unchanged project after game-stop: the next start may replay the recorded launch.
        self.ui("restart-unchanged")
        self.java_check()
        stop = self.modaccel("game-stop-final", "game-stop", timeout=300)
        self.from_call("game-stop-final", stop)

    def clone(self):
        start = now()
        self.logs.mkdir(parents=True, exist_ok=True)
        if self.root.exists():
            # A previous run's game must not keep files locked or memory held.
            run(MODACCEL + ["game-stop", "--root", str(self.root), "--json"], TOOL, self.logs / "pre-clean-game-stop.log", 300)
            remove(self.root)
        self.root.parent.mkdir(parents=True, exist_ok=True)
        git = ["git", "-c", "core.longpaths=true", "-c", "advice.detachedHead=false"]
        log = self.logs / "clone.log"
        exit, out, _ = run(git + ["clone", "--filter=blob:none", "--no-checkout", "--branch", self.mod["branch"], self.mod["repo"], str(self.root)], REPO, log, TIMEOUTS["clone"])
        commit = self.mod["commit"]
        if exit == 0:
            target = "origin/" + self.mod["branch"] if self.latest else commit
            exit, out2, _ = run(git + ["-C", str(self.root), "checkout", "--detach", target], REPO, self.logs / "checkout.log", TIMEOUTS["clone"])
            out += out2
        if exit == 0:
            commit = subprocess.run(["git", "-C", str(self.root), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
        self.record["commitUsed"] = commit
        self.put("clone", "pass" if exit == 0 else "fail", now() - start, "" if exit == 0 else short(out), log=str(log))

    def running(self):
        call = self.modaccel("game-status", "game-status", timeout=120)
        return call["exit"] == 0 and call["result"].get("running", True) is not False and bool(call["result"])

    def stale_check(self):
        path = edit_file(self.root, self.mod.get("edit_file", ""))
        if path is None:
            self.put("stale-refused", "fail", error="no Java source in the checkout to edit")
            self.put("revert-passes", "blocked", error="no edit was made")
            return
        self.edit = path
        original = path.read_bytes()
        relative = str(path.relative_to(self.root)).replace("\\", "/")
        try:
            path.write_bytes(original + MARK)
            call = self.modaccel("stale-refused", "ui-check")
            refused = call["exit"] != 0 and "different build" in str(call["error"])
            if call["exit"] == 0:
                why = "ui-check passed against a game started before the source changed: the stale game was not refused"
            elif not refused:
                why = "ui-check failed, but not with the stale-build refusal: " + str(call["error"])
            else:
                why = None
            self.from_call("stale-refused", call, ok=refused, error=why, file=relative,
                           refusal=short(call["error"], 200) if refused else None)
        finally:
            path.write_bytes(original)
        self.ui("revert-passes")

    def eval_steps(self):
        call = self.modaccel("eval", "eval", ["--code", "1 + 1"], timeout=300)
        value = call["result"].get("value")
        ok = call["exit"] == 0 and str(value).strip() in ("2", "2.0")
        self.from_call("eval", call, ok=ok, error=None if ok else (call["error"] or f"expected 2, got {value!r}"), value=value)

    def game_directory(self):
        # Where the running game really keeps its saves: Mojang names call it gameDirectory, Yarn runDirectory.
        # Reflection only, so this helper works whether or not eval offers mc on this mapping.
        code = ("Class<?> type;\n"
                "try { type = Class.forName(\"net.minecraft.client.Minecraft\", true, getClass().getClassLoader()); }\n"
                "catch (ClassNotFoundException yarn) { type = Class.forName(\"net.minecraft.client.MinecraftClient\", true, getClass().getClassLoader()); }\n"
                "Object game = call(type, \"getInstance\");\n"
                "Object d;\ntry { d = field(game, \"gameDirectory\"); } catch (NoSuchFieldException yarn) { d = field(game, \"runDirectory\"); }\n"
                "return ((java.io.File) d).getAbsolutePath();\n")
        call = self.modaccel("game-directory", "eval", self.snippet("game-directory", code), timeout=300)
        value = call["result"].get("value")
        return Path(str(value)) if call["exit"] == 0 and value else None

    def world_check(self):
        directory = self.game_directory()
        if directory is None:
            self.put("world-check", "fail", error="could not ask the running game for its game directory (eval failed)")
        else:
            target = directory / "saves" / "lab"
            save = SAVE_26 if str(self.mod.get("minecraft", "")).startswith("26") else SAVE
            if not target.exists():
                shutil.copytree(save, target)
            call = self.modaccel("world-check", "world-check", ["--world", "lab"])
            self.from_call("world-check", call, gameDirectory=str(directory),
                           capture=find_png(call["result"].get("capture")) or find_png(call["result"]))
        call = self.modaccel("eval-until", "eval", ["--until", "mc.player != null", "--timeout", "60"], timeout=300)
        self.from_call("eval-until", call, waited=call["result"].get("waitedSeconds"))

    def reload_check(self):
        path = getattr(self, "edit", None)
        if path is None:
            self.block(["reload", "reload-current", "reload-revert"], "no source file to change")
            return
        original = path.read_bytes()
        relative = str(path.relative_to(self.root)).replace("\\", "/")
        try:
            # A leading comment moves every line, so the class bytes really change (line numbers)
            # without changing the class shape: a body-only redefinition on any JVM.
            path.write_bytes(b"// modaccel real-mods reload check\n" + original)
            call = self.modaccel("reload", "reload", timeout=900)
            restart = call["result"].get("restartNeeded")
            self.from_call("reload", call, ok=call["exit"] == 0 and not restart, file=relative,
                           error=None if call["exit"] == 0 and not restart else (call["error"] or call["result"].get("details") or "restart needed"),
                           details=short(call["result"].get("details", ""), 300))
            self.ui("reload-current")
        finally:
            path.write_bytes(original)
        call = self.modaccel("reload-revert", "reload", timeout=900)
        self.from_call("reload-revert", call, ok=call["exit"] == 0 and not call["result"].get("restartNeeded"))

    def java_check(self):
        launch = self.root / ".modaccel/portable/launch.json"
        executable = None
        try:
            executable = json.loads(launch.read_text(encoding="utf-8")).get("executable")
        except Exception:
            pass
        if not executable:
            self.put("java-ui-check", "blocked", error="no recorded launch to take the game's own Java from")
            return
        java_home = Path(executable).parent.parent
        self.modaccel("java-stop-first", "game-stop", timeout=300)
        self.ui("java-ui-check", ["--java", str(java_home)])
        self.steps["java-ui-check"]["java"] = str(java_home)

    def nodes(self):
        nodes = self.mod.get("nodes") or []
        if not nodes:
            self.put("nodes-compile", "n/a", error="manifest lists no per-node compile")
            return
        results, start = {}, now()
        for node in nodes:
            call = self.modaccel("compile-" + node.strip(":").replace(":", "_"), "compile", project=node)
            results[node] = {"status": "pass" if call["exit"] == 0 else "fail", "seconds": call["seconds"]}
            if call["exit"] != 0:
                results[node]["error"] = short(call["error"], 400)
                results[node]["fullError"] = str(call["error"])
        self.modaccel("nodes-stop", "game-stop", timeout=300)  # the node compiles' Gradle daemons
        failed = classify(self.mod, results)
        self.put("nodes-compile", "fail" if failed else "pass", now() - start,
                 ("; ".join(f"{n}: {results[n].get('error', '')[:200]}" for n in failed)) if failed else "",
                 nodes=results, passed=f"{len(nodes) - len(failed)}/{len(nodes)}")

    def compile_nodes(self, start_wall):
        """CI mode: no game. Every listed node gets its own setup and compile, on a fresh clone."""
        self.from_call("doctor", self.modaccel("doctor", "doctor"))
        results, start = {}, now()
        for node in self.mod.get("nodes") or [self.mod.get("project", "")]:
            name = node.strip(":").replace(":", "_") or "root"
            entry = {}
            for verb in ("setup", "compile"):
                call = self.modaccel(f"{verb}-{name}", verb, project=node)
                entry[verb] = {"status": "pass" if call["exit"] == 0 else "fail", "seconds": call["seconds"]}
                if call["exit"] != 0:
                    entry[verb]["error"] = short(call["error"], 600)
                    entry[verb]["fullError"] = str(call["error"])
                    break
            results[node] = entry
            print(f"   {node:22} " + "  ".join(f"{v} {r['status']} {r['seconds']:.1f}s" for v, r in entry.items()), flush=True)
        self.modaccel("nodes-stop", "game-stop", timeout=300)
        failed = classify(self.mod, results)
        self.put("nodes-compile", "fail" if failed else "pass", now() - start,
                 "; ".join(f"{n}: " + next(r.get("error", "") for r in results[n].values() if r["status"] == "fail")[:300] for n in failed),
                 nodes=results, passed=f"{len(results) - len(failed)}/{len(results)}")
        for name in STEPS:
            if name not in self.steps and name != "no-leftovers":
                self.put(name, "n/a", error="compile-only run (--compile-nodes): no game")
        self.leftovers(start_wall)
        return self.finish()

    def leftovers(self, start_wall):
        start = now()
        time.sleep(3)  # let a quitting JVM finish exiting before calling it left over
        found = scan_leftovers(self.root, start_wall)
        killed = []
        if found["attributed"] and not ARGS.keep_leftovers:
            for item in found["attributed"]:
                kill_tree(item["pid"])
                killed.append(item["pid"])
        error = "; ".join(f"{i['kind']} pid {i['pid']} ({i['why']})" for i in found["attributed"])
        self.put("no-leftovers", "fail" if found["attributed"] else "pass", now() - start, error,
                 leftovers=found["attributed"] or None, killedByRunner=killed or None,
                 unattributed=found["unattributed"] or None)

    def finish(self):
        self.record["steps"] = {name: self.steps.get(name, {"status": "missing"}) for name in STEPS}
        failed = [n for n, s in self.record["steps"].items() if s["status"] == "fail"]
        self.record["ok"] = not failed
        self.record["failedSteps"] = failed
        return self.record


def remove(path):
    def unlock(function, target, _):
        os.chmod(target, stat.S_IWRITE)
        function(target)
    # Loom's cache nests past Windows' 260-character limit; the \\?\ form has none.
    target = "\\\\?\\" + str(Path(path).resolve()) if WINDOWS else path
    shutil.rmtree(target, onerror=unlock)


def gradle_cache_warm():
    files = GRADLE_HOME / "caches/modules-2/files-2.1"
    return files.is_dir() and any(files.iterdir())


def wrapper_cached(root):
    props = root / "gradle/wrapper/gradle-wrapper.properties"
    if not props.exists():
        return None
    match = re.search(r"distributionUrl=.*/([^/\s]+)\.zip", props.read_text(errors="replace"))
    return bool(match) and (GRADLE_HOME / "wrapper/dists" / match.group(1)).is_dir()


SKIP = {".git", ".gradle", "build", ".modaccel", ".runs", "run", "out", "node_modules"}


def walk(root):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP]
        for name in filenames:
            yield Path(dirpath) / name


def test_sources(root):
    return [p for p in walk(root) if "src" in p.relative_to(root).parts and "test" in p.relative_to(root).parts
            and p.suffix in (".java", ".kt") and p.relative_to(root).parts.index("src") + 1 == p.relative_to(root).parts.index("test")]


def edit_file(root, given):
    if given:
        return root / given
    # A tracked main source of the root build: not a test, not inside a nested build of its own.
    listed = subprocess.run(["git", "-C", str(root), "ls-files", "*.java"], capture_output=True, text=True).stdout.split()
    nested = {str(Path(p).parent).replace("\\", "/") for p in subprocess.run(["git", "-C", str(root), "ls-files", "*settings.gradle", "*settings.gradle.kts"],
              capture_output=True, text=True).stdout.split() if "/" in p}
    files = [f for f in sorted(listed) if "/test/" not in f and not f.startswith("test/") and not any(f.startswith(n + "/") for n in nested)]
    files.sort(key=lambda f: (not f.startswith("src/main/java/"), "/main/" not in f, f))
    return root / files[0] if files else None


def processes():
    """Java processes as dicts with pid, created (epoch seconds or None) and command line."""
    if WINDOWS:
        script = ("Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^javaw?\\.exe$' } | "
                  "ForEach-Object { [pscustomobject]@{pid=$_.ProcessId; created=([DateTimeOffset]$_.CreationDate).ToUnixTimeSeconds(); cmd=$_.CommandLine} } | ConvertTo-Json -Compress")
        out = subprocess.run(["powershell", "-NoProfile", "-Command", script], capture_output=True, text=True).stdout.strip()
        if not out:
            return []
        data = json.loads(out)
        return data if isinstance(data, list) else [data]
    out = subprocess.run(["ps", "-eo", "pid=,args="], capture_output=True, text=True).stdout
    result = []
    for line in out.splitlines():
        pid, _, cmd = line.strip().partition(" ")
        if "java" in cmd:
            result.append({"pid": int(pid), "created": None, "cmd": cmd})
    return result


def log_mentions(log, needles):
    try:
        with open(log, "rb") as data:
            tail = b""
            while True:
                chunk = data.read(1 << 20)
                if not chunk:
                    return False
                block = (tail + chunk).lower()
                if any(n in block for n in needles):
                    return True
                tail = block[-512:]
    except OSError:
        return False


def scan_leftovers(root, since):
    root_text = str(root).lower()
    needles = [root_text.encode(), root_text.replace("\\", "/").encode(), root_text.replace("\\", "\\\\").encode()]
    private = root / ".modaccel/portable/gradle-daemons"
    agent = root / ".modaccel/portable/agent.json"
    game_pid = None
    if agent.exists():
        try:
            game_pid = int(json.loads(agent.read_text()).get("pid"))
        except Exception:
            pass
    attributed, unattributed = [], []
    for p in processes():
        cmd = p.get("cmd") or ""
        pid = int(p["pid"])
        daemon = "GradleDaemon" in cmd
        kotlin = "KotlinCompileDaemon" in cmd
        if pid == game_pid and not daemon:
            attributed.append({"pid": pid, "kind": "game", "why": "pid recorded in agent.json is still alive"})
        elif not daemon and not kotlin and root_text in cmd.lower():
            attributed.append({"pid": pid, "kind": "game", "why": "java command line points into the clone"})
        elif daemon:
            if list(private.glob(f"*/daemon-{pid}.out.log")):
                attributed.append({"pid": pid, "kind": "gradle-daemon", "why": "registered in modaccel's private daemon registry for this clone"})
            elif any(log_mentions(log, needles) for log in GRADLE_HOME.glob(f"daemon/*/daemon-{pid}.out.log")):
                attributed.append({"pid": pid, "kind": "gradle-daemon", "why": "user-registry daemon whose log names this clone (started outside modaccel's private registry)"})
            elif p.get("created") and p["created"] >= since:
                unattributed.append({"pid": pid, "kind": "gradle-daemon", "why": "started during this mod's run; log does not name the clone (may be another agent)"})
        elif kotlin and p.get("created") and p["created"] >= since:
            unattributed.append({"pid": pid, "kind": "kotlin-daemon", "why": "started during this mod's run; Kotlin daemons are shared, so ownership is unknown"})
    return {"attributed": attributed, "unattributed": unattributed}


def cell(step):
    status = step["status"]
    seconds = f"{step['seconds']:.0f}s" if step["seconds"] >= 10 else f"{step['seconds']:.1f}s"
    if status == "pass":
        return seconds + (f" ({step['passed']})" if step.get("passed") else "")
    if status == "fail":
        return "**FAIL** " + seconds + (f" ({step['passed']})" if step.get("passed") else "")
    return {"n/a": "–", "blocked": "blocked"}.get(status, status)


def report(summary, path):
    lines = ["# Real mods through the modaccel loop", "",
             f"Run {summary['startedAt']} on {summary['platform']}, modaccel at `{summary['modaccelCommit']}`, "
             f"{'pinned commits' if summary['pinned'] else 'latest upstream heads'}. Every mod was cloned fresh; "
             f"global Gradle cache {'warm' if summary['gradleCacheWarm'] else 'cold'} at start ({summary['gradleUserHome']}).",
             "", "| mod | " + " | ".join(STEPS) + " |", "|---|" + "---|" * len(STEPS)]
    for mod in summary["mods"]:
        lines.append(f"| {mod['name']} `{mod.get('commitUsed', '')[:7]}` | " + " | ".join(cell(mod["steps"][s]) for s in STEPS) + " |")
    lines += ["", "Times are wall-clock seconds per command. `–` means the entry has nothing for that step; "
              "`blocked` means an earlier failure left nothing to check. Captures need a look by a person or an agent; "
              "nothing here compares pixels.", "", "## Failures and next actions", ""]
    any_fail = False
    for mod in summary["mods"]:
        for name in STEPS:
            step = mod["steps"][name]
            if step["status"] in ("fail", "blocked") and step.get("error"):
                any_fail = any_fail or step["status"] == "fail"
                lines.append(f"- **{mod['name']} / {name}** ({step['status']}): {step['error'].splitlines()[0][:400]}")
                if step.get("next"):
                    lines.append(f"  - next: {step['next']}")
                if step.get("log"):
                    lines.append(f"  - log: `{step['log']}`")
    if not any_fail:
        lines.append("None.")
    lines += ["", "## Wrapper distribution already cached", ""]
    for mod in summary["mods"]:
        lines.append(f"- {mod['name']}: {mod.get('cache', {}).get('wrapperDistCached')}")
    lines += ["", "## Captures", ""]
    for mod in summary["mods"]:
        for name in ("ui-check-1", "ui-check-2", "revert-passes", "world-check", "reload-current", "restart-unchanged", "java-ui-check"):
            if mod["steps"][name].get("capture"):
                lines.append(f"- {mod['name']} {name}: `{mod['steps'][name]['capture']}`")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    global ARGS, TOOL, MODACCEL
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--modaccel", type=Path, default=REPO, help="modaccel checkout to test (default: this one); for A/B runs against another branch's worktree")
    parser.add_argument("--label", default="latest", help="name of the result files: <label>.json and <label>.md (default latest.json / REPORT.md)")
    parser.add_argument("--only", help="comma-separated mod ids from the manifest")
    parser.add_argument("--latest", action="store_true", help="use each branch head instead of the pinned commit")
    parser.add_argument("--cache", type=Path, default=default_cache(), help="where fresh clones go (outside the repo)")
    parser.add_argument("--out", type=Path, default=HERE / "results", help="where latest.json and REPORT.md go")
    parser.add_argument("--compile-nodes", action="store_true", help="CI mode: no game; setup and compile every node the entry lists")
    parser.add_argument("--keep-leftovers", action="store_true", help="report left-over processes without killing them")
    ARGS = parser.parse_args()
    TOOL = ARGS.modaccel.resolve()
    MODACCEL = [str(TOOL / "modaccel.cmd")] if WINDOWS else [str(TOOL / "modaccel")]
    manifest = json.loads((HERE / "manifest.json").read_text(encoding="utf-8"))
    mods = manifest["mods"]
    if ARGS.only:
        wanted = ARGS.only.split(",")
        mods = [m for m in mods if m["id"] in wanted]
        missing = set(wanted) - {m["id"] for m in mods}
        if missing:
            sys.exit(f"unknown mod ids: {', '.join(sorted(missing))}")
    cache = ARGS.cache.resolve()
    if cache.is_relative_to(REPO):
        sys.exit("--cache must be outside the repo so clones never mix with modaccel's own files")
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    logs = cache / "logs" / stamp
    summary = {"schema": 1, "startedAt": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
               "platform": sys.platform, "pinned": not ARGS.latest, "cache": str(cache), "logs": str(logs),
               "gradleUserHome": str(GRADLE_HOME), "gradleCacheWarm": gradle_cache_warm(),
               "modaccel": str(TOOL), "label": ARGS.label,
               "modaccelCommit": subprocess.run(["git", "-C", str(TOOL), "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip(),
               "mods": []}
    ARGS.out.mkdir(parents=True, exist_ok=True)
    for mod in mods:
        print(f"== {mod['name']}", flush=True)
        try:
            record = ModRun(mod, cache, logs, ARGS.latest).go()
        except Exception as error:  # the runner itself broke; say so and keep going
            record = {k: mod.get(k, "") for k in ("id", "name", "repo", "branch", "commit", "project")}
            record["steps"] = {s: {"status": "fail", "seconds": 0, "error": f"runner error: {error!r}"} for s in STEPS}
            record["ok"] = False
            record["failedSteps"] = list(STEPS)
        for name in STEPS:
            step = record["steps"][name]
            print(f"   {name:14} {step['status']:8} {step.get('seconds', 0):7.1f}s  {short(step.get('error', ''), 160)}", flush=True)
        summary["mods"].append(record)
        summary["finishedAt"] = datetime.datetime.now().astimezone().isoformat(timespec="seconds")
        (ARGS.out / f"{ARGS.label}.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        report(summary, ARGS.out / ("REPORT.md" if ARGS.label == "latest" else f"{ARGS.label}.md"))
    (logs / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(f"report: {ARGS.out}")
    sys.exit(0 if all(m["ok"] for m in summary["mods"]) else 1)


if __name__ == "__main__":
    main()
