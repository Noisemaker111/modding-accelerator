"""Runs one CI plan's checks side by side on this machine, as many at once as it has cores.

    python tools/ci/lanes.py PLAN [--shard I/N] [--jobs N] [--seed S] [--list]

PLAN is core, server or stonecutter (see plan() below). Each check is a separate process with its own log;
checks that need the package wait for it. A shard takes its share of the checks by their measured durations
(tools/ci/durations.json, longest first), so the shards of a plan finish together. Start order among ready
checks is shuffled with a printed seed (--seed repeats it), so an order dependence shows up as a failure that
the same seed reproduces. Nothing is retried: a check that fails, fails the run.
"""
import argparse, glob, json, os, random, signal, subprocess, sys, tempfile, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WINDOWS = os.name == "nt"
JAR = "build/distributions/modaccel.jar"
DURATIONS = Path(__file__).with_name("durations.json")


class Check:
    def __init__(self, name, *commands, needs=(), env=None, only=None, lock=None):
        self.name, self.commands, self.needs, self.env, self.only = name, commands, tuple(needs), env or {}, only
        self.lock = lock  # checks with the same lock never overlap: modaccel's project lock on the checkout
        self.state, self.seconds, self.exit, self.process, self.log = "waiting", 0.0, None, None, None


def java(*args):
    return ["java", *args]


def plan(name, temp):
    """Every check of a plan. A new bench test or server-check unit joins on its own: both lists are read, not typed."""
    if name == "core":
        checks = [Check("package", java("tools/cli/Bootstrap.java", ".", "package", "--json"))]
        for test in sorted(glob.glob("bench/**/test_*.py", recursive=True)):
            checks.append(Check("py " + Path(test).as_posix().removeprefix("bench/"), [sys.executable, test]))
        checks.append(Check("py ci/test_reach.py", [sys.executable, "tools/ci/test_reach.py"]))
        launcher = ["cmd", "/c", str(ROOT / "modaccel.cmd")] if WINDOWS else ["./modaccel"]
        checks += [
            Check("source launcher", launcher + ["doctor", "--json"], needs=["package"]),
            Check("standalone self-test", java("-jar", JAR, "self-test", "--root", ".", "--json"), needs=["package"]),
            Check("portable zip acceptance", java("tools/tests/PackageAcceptanceTest.java", "build/distributions/modaccel-portable.zip",
                  "build/distributions/modaccel-portable.zip.sha256", tempfile.mkdtemp(dir=temp, prefix="zip-")), needs=["package"]),
            Check("fabric metadata and logic", java("-jar", JAR, "fast", "--json"), needs=["package"], lock="checkout"),
            Check("legacy launch agent", ["pwsh", "-NoProfile", "-File", "tools/ci/legacy-agent.ps1"], needs=["fabric metadata and logic"],
                  only="windows", lock="checkout"),
            Check("hidden window", java("tools/tests/BackgroundWindowsTest.java", ".", JAR), needs=["package"], lock="checkout"),
            Check("fastlane isolation", java("tools/tests/FastLaneIsolationTest.java", ".", JAR), needs=["package"]),
            Check("runtime-only natives", java("tools/tests/RuntimeNativesTest.java", ".", JAR), needs=["package"]),
            Check("port rewrites", ["javac", "--release", "21", "-d", ".runs/preflight-tests", "tools/VersionMap.java",
                  "tools/tests/PortPreflightTest.java", "tools/tests/ChainMapTest.java"],
                  java("-cp", ".runs/preflight-tests", "PortPreflightTest"), java("-cp", ".runs/preflight-tests", "ChainMapTest")),
        ]
        return checks
    if name == "server":
        checks = [Check("package", java("tools/cli/Bootstrap.java", ".", "package", "--json")),
                  Check("server plugins", java("tools/tests/ServerPluginFixtures.java", ".", JAR), needs=["package"])]
        for unit in server_units():
            checks.append(Check("server-check " + unit, java("tools/tests/ServerCheckFixtures.java", ".", JAR, unit), needs=["package"]))
        return checks
    if name == "stonecutter":
        mods = ["diorama-26.3", "diorama-1.21.1", "ias-26.3-fabric"]
        return [Check("node " + mod, [sys.executable, "bench/real-mods/run.py", "--compile-nodes", "--only", mod,
                      "--cache", str(Path(temp) / "real-mods" / mod), "--out", str(Path(temp) / "real-mods-results" / mod),
                      "--label", mod]) for mod in mods]
    raise SystemExit(f"Unknown plan {name}; use core, server or stonecutter")


def server_units():
    """One unit per server run in tools/tests/ServerCheckFixtures.java, from the manifest's own lists."""
    units = []
    for plugin in json.loads((ROOT / "bench/server-plugins/manifest.json").read_text(encoding="utf-8"))["plugins"]:
        check = plugin.get("serverCheck")
        if not check:
            continue
        units += ["fresh", "bot-secret", "bot-negatives"]
        units += [f"extra:{i}" for i in range(len(check.get("additionalScenarios", [])))]
        units += [f"break:{i}" for i in range(len(check.get("breaks", [])))]
    return sorted(set(units))


def system():
    return "windows" if WINDOWS else "macos" if sys.platform == "darwin" else "linux"


def estimate(check, measured):
    return measured.get(check.name, 30.0)


def shard(checks, index, count, measured):
    """Longest first onto the least loaded shard; the package and what a shard's checks need go to every shard."""
    if count == 1:
        return checks
    by_name = {c.name: c for c in checks}
    needed = {n for c in checks for n in c.needs}
    loads, owned = [0.0] * count, [[] for _ in range(count)]
    for check in sorted((c for c in checks if c.name not in needed), key=lambda c: (-estimate(c, measured), c.name)):
        target = loads.index(min(loads))
        loads[target] += estimate(check, measured)
        owned[target].append(check)
    keep, stack = [], list(owned[index])
    while stack:
        check = stack.pop()
        if check not in keep:
            keep.append(check)
            stack += [by_name[n] for n in check.needs if n in by_name]
    return [c for c in checks if c in keep]


def kill(process):
    if process.poll() is not None:
        return
    if WINDOWS:
        subprocess.run(["taskkill", "/T", "/F", "/PID", str(process.pid)], capture_output=True)
    else:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass


def start(check, logs):
    check.log = logs / (check.name.replace(" ", "-").replace("/", "-").replace(":", "-") + ".log")
    script = [sys.executable, "-c", (
        "import subprocess,sys,json\n"
        "for c in json.loads(sys.argv[1]):\n"
        "    print('$ ' + ' '.join(c), flush=True)\n"
        "    r = subprocess.call(c)\n"
        "    if r: sys.exit(r)\n"), json.dumps([list(c) for c in check.commands])]
    out = open(check.log, "w", encoding="utf-8", errors="replace")
    check.process = subprocess.Popen(script, cwd=ROOT, stdout=out, stderr=subprocess.STDOUT, env={**os.environ, **check.env},
                                     start_new_session=not WINDOWS)
    check.out, check.began, check.state = out, time.monotonic(), "running"


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("plan")
    parser.add_argument("--shard", default="1/1")
    parser.add_argument("--jobs", type=int, help="checks at once; default the cores, half of them for server (a Paper server and its "
                        "bot keep two cores busy, and at four servers on four cores scenario steps miss their 5 s windows)")
    parser.add_argument("--seed", type=int, default=random.SystemRandom().randrange(1 << 30))
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--timings", help="write each check's seconds here as JSON")
    args = parser.parse_args()
    index, count = (int(x) for x in args.shard.split("/"))
    cores = os.cpu_count() or 2
    if args.jobs is None:
        args.jobs = max(1, cores // 2) if args.plan == "server" else cores
    temp = Path(os.environ.get("RUNNER_TEMP") or tempfile.gettempdir()) / "lanes"
    temp.mkdir(parents=True, exist_ok=True)
    measured = json.loads(DURATIONS.read_text(encoding="utf-8")).get(system(), {}) if DURATIONS.exists() else {}
    checks = [c for c in plan(args.plan, temp) if c.only in (None, system())]
    checks = shard(checks, index - 1, count, measured)
    if args.list:
        for c in checks:
            print(f"{estimate(c, measured):6.0f}s  {c.name}")
        return 0
    logs = ROOT / ".runs" / "lanes" / f"{args.plan}-{index}"
    logs.mkdir(parents=True, exist_ok=True)
    rng = random.Random(args.seed)
    print(f"{args.plan} shard {index}/{count}: {len(checks)} checks, {args.jobs} at once, seed {args.seed} "
          f"(python tools/ci/lanes.py {args.plan} --shard {args.shard} --seed {args.seed} repeats this order)", flush=True)
    began = time.monotonic()
    by_name = {c.name: c for c in checks}
    while any(c.state in ("waiting", "running") for c in checks):
        for c in checks:
            if c.state == "running" and c.process.poll() is not None:
                c.exit, c.seconds = c.process.returncode, time.monotonic() - c.began
                c.out.close()
                c.state = "passed" if c.exit == 0 else "failed"
                report(c)
        for c in checks:
            if c.state == "waiting" and any(by_name[n].state in ("failed", "not run") for n in c.needs if n in by_name):
                c.state = "not run"
                report(c)
        ready = [c for c in checks if c.state == "waiting" and all(by_name[n].state == "passed" for n in c.needs if n in by_name)]
        # Longest first keeps the run short; the seed shuffles the order within the same measured length class.
        rng.shuffle(ready)
        ready.sort(key=lambda c: -round(estimate(c, measured) / 10))
        free = args.jobs - sum(c.state == "running" for c in checks)
        held = {c.lock for c in checks if c.state == "running" and c.lock}
        for c in ready:
            if free <= 0:
                break
            if c.lock and c.lock in held:
                continue
            start(c, logs)
            free -= 1
            if c.lock:
                held.add(c.lock)
        time.sleep(0.2)
    wall = time.monotonic() - began
    print(f"\n{'check':40} {'state':8} {'seconds':>8}")
    for c in sorted(checks, key=lambda c: -c.seconds):
        print(f"{c.name:40} {c.state:8} {c.seconds:8.1f}")
    print(f"{'wall':40} {'':8} {wall:8.1f}   (sum {sum(c.seconds for c in checks):.0f}s over {args.jobs} lanes)")
    if args.timings:
        Path(args.timings).write_text(json.dumps({"plan": args.plan, "system": system(), "jobs": args.jobs, "wall": wall,
                                                  "checks": {c.name: round(c.seconds, 1) for c in checks}}, indent=1))
    bad = [c.name for c in checks if c.state != "passed"]
    if bad:
        print("FAILED: " + ", ".join(bad), flush=True)
        return 1
    return 0


def report(c):
    gh = os.environ.get("GITHUB_ACTIONS") == "true"
    text = c.log.read_text(encoding="utf-8", errors="replace") if c.log and c.log.exists() else ""
    title = f"{c.name}: {c.state} in {c.seconds:.1f}s" + (f" (exit {c.exit})" if c.exit else "")
    if c.state == "passed" and gh:
        print(f"::group::{title}\n{text}::endgroup::", flush=True)
    else:
        print(f"{'::error title=' + c.name + '::' if gh and c.state != 'passed' else '== '}{title}", flush=True)
        if c.state == "not run":
            print("   a check it needs failed", flush=True)
        else:
            print(text, flush=True)


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")
    sys.exit(main())
