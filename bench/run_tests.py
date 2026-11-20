"""Every offline check, in one list: what CI runs on each pull request and what to run before pushing.

    python bench/run_tests.py                  all of them (builds the jar first)
    python bench/run_tests.py --only NAME,...  some of them (package runs first when one needs the jar)
    python bench/run_tests.py --list           the names

The self-test runs from the packaged jar, as users get it, not from the checkout. On Linux it runs with LC_ALL=C
so a child's non-ASCII output is read through a non-UTF-8 console; Windows gives cp1252. No game, no network
beyond Gradle's own downloads. Each check prints its time; the run exits 1 when any check failed.
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAR = "build/distributions/mmt.jar"
ZIP = "build/distributions/mmt-portable.zip"
FIXTURE = "fixtures/prism-lab"  # Prism Lab, the demo mod the mod-project checks run on
WINDOWS = os.name == "nt"
SEP = ";" if WINDOWS else ":"

PYTHON = ["bench/test_measures.py", "bench/test_record.py", "bench/test_ledger_rule.py", "bench/test_java_lines.py",
          "bench/test_loader_api.py", "bench/test_sweeps.py", "bench/test_session_cost.py", "bench/test_perf_sessions.py",
          "bench/test_performance.py", "bench/test_perf_verdicts.py", "bench/test_cloud_run.py", "bench/test_budgets.py",
          "bench/diorama/test_budget.py", "bench/diorama/test_prepare_comparison.py", "bench/diorama/test_prepare_world.py",
          "bench/test_scoreboard.py", "bench/test_convert_coverage.py", "bench/test_exposure.py"]


def java(*args):
    return ["java", *args]


def checkout_data():
    out = ROOT / ".runs" / "checkout-tests"
    out.mkdir(parents=True, exist_ok=True)
    yield ["javac", "-encoding", "UTF-8", "-cp", JAR, "-d", str(out), "tools/tests/CheckoutDataTest.java"]
    yield java("-cp", JAR + SEP + str(out), "CheckoutDataTest", ".")


def port_preflight():
    out = ROOT / ".runs" / "preflight-tests"
    yield ["javac", "--release", "21", "-encoding", "UTF-8", "-d", str(out), "tools/VersionMap.java",
           "tools/cli/Route.java", "tools/cli/Json.java", "tools/cli/Versions.java",
           "tools/tests/PortPreflightTest.java", "tools/tests/ChainMapTest.java", "tools/tests/LegacyMapTest.java"]
    yield java("-cp", str(out), "PortPreflightTest")
    yield java("-cp", str(out), "ChainMapTest")
    yield java("-cp", str(out), "LegacyMapTest")


def jar_port():
    out = ROOT / ".runs" / "jarport-tests"
    asm = SEP.join(["tools/perf/lib/asm-9.9.jar", "tools/perf/lib/asm-commons-9.9.jar"])
    yield ["javac", "--release", "25", "-encoding", "UTF-8", "-cp", asm, "-d", str(out), "tools/JarPort.java", "tools/FixExpr.java", "tools/Exposure.java",
           "tools/GameDiff.java", "tools/VersionMap.java", "tools/ModNames.java", "tools/cli/Json.java", "tools/tests/JarPortTest.java", "tools/tests/JarPortRulesTest.java",
           "tools/tests/JarPortFixesTest.java", "tools/tests/JarPortFabricTest.java", "tools/tests/ModNamesTest.java", "tools/tests/GameLearnTest.java",
           "tools/tests/GameMeaningTest.java", "tools/tests/JarPortLoaderTest.java"]
    yield java("-cp", str(out) + SEP + asm, "JarPortTest", str(out / "scratch"))
    yield java("-cp", str(out) + SEP + asm, "ModNamesTest")
    yield java("-cp", str(out) + SEP + asm, "GameLearnTest", str(out / "scratch"))
    yield java("-cp", str(out) + SEP + asm, "GameMeaningTest", str(out / "scratch"))
    yield java("-cp", str(out) + SEP + asm, "JarPortLoaderTest", str(out / "scratch"))


def legacy_agent():
    """Windows: the legacy launch agent in JAVA_TOOL_OPTIONS keeps system properties strings, and Gradle still runs."""
    prepared = subprocess.run(java("-jar", JAR, "legacy-agent", "--root", FIXTURE, "--json"), cwd=ROOT,
                              capture_output=True, text=True)
    agent = json.loads(prepared.stdout.strip().splitlines()[-1])["result"]["agent"]
    env = {"JAVA_TOOL_OPTIONS": f'"-javaagent:{agent}=legacy" -Dmmt.background=true -Dmmt.quiet=true'}
    yield java("tools/tests/LegacyAgentPropertiesTest.java"), env
    yield java("-jar", JAR, "fast", "--root", FIXTURE, "--refresh", "--json"), env


# name -> (needs the jar, systems or None for all, commands or a generator of them)
CHECKS = {
    "python": (False, None, [[sys.executable, p] for p in PYTHON]),
    "package": (False, None, [java("tools/cli/Bootstrap.java", ".", "package", "--json")]),
    "self-test": (True, None, [(java("-jar", JAR, "self-test", "--root", "."), {} if WINDOWS else {"LC_ALL": "C"})]),
    "checkout-data": (True, None, checkout_data),
    "fingerprint": (True, None, [[sys.executable, "bench/test_fingerprint.py", JAR]]),
    "port-preflight": (False, None, port_preflight),
    "jar-port": (False, None, jar_port),
    "zip": (True, None, [java("tools/tests/PackageAcceptanceTest.java", ZIP, ZIP + ".sha256", tempfile.gettempdir())]),
    "fast": (True, None, [java("-jar", JAR, "fast", "--root", FIXTURE, "--json")]),
    "port-plan": (True, None, [java("tools/tests/PortPlanCacheTest.java", FIXTURE, JAR)]),
    "hidden-window": (True, None, [java("tools/tests/BackgroundWindowsTest.java", FIXTURE, JAR)]),
    "fastlane-isolation": (True, None, [java("tools/tests/FastLaneIsolationTest.java", ".", JAR)]),
    "runtime-natives": (True, ("posix",), [java("tools/tests/RuntimeNativesTest.java", ".", JAR)]),
    "launcher": (False, ("nt",), [[sys.executable, "tools/ci/launcher_experiments.py"]]),
    "legacy-agent": (True, ("nt",), legacy_agent),
}


def run(name):
    """Runs every command of one check, reporting each; False when any failed."""
    _, _, commands = CHECKS[name]
    passed = True
    for item in (commands() if callable(commands) else commands):
        command, env = item if isinstance(item, tuple) else (item, {})
        started = time.monotonic()
        done = subprocess.run(command, cwd=ROOT, env={**os.environ, **env}, capture_output=True, text=True,
                              encoding="utf-8", errors="replace")
        took = time.monotonic() - started
        shown = " ".join(str(c) for c in command)
        if done.returncode:
            print(f"FAIL {name} ({took:.0f} s): {shown}\n{done.stdout[-4000:]}{done.stderr[-4000:]}", flush=True)
            passed = False
            continue
        last = (done.stdout.strip().splitlines() or [""])[-1][:160]
        print(f"ok   {name} ({took:.0f} s): {shown[-90:]}  {last if not last.startswith('{') else ''}", flush=True)
    return passed


def main(argv=None):
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", help="comma-separated check names")
    ap.add_argument("--list", action="store_true")
    args = ap.parse_args(argv)
    here = "nt" if WINDOWS else "posix"
    names = [n for n, (_, systems, _) in CHECKS.items() if systems is None or here in systems]
    if args.list:
        print("\n".join(names))
        return 0
    if args.only:
        wanted = [x.strip() for x in args.only.split(",")]
        unknown = [x for x in wanted if x not in CHECKS]
        if unknown:
            print(f"No check {', '.join(unknown)}. Checks: {', '.join(CHECKS)}", file=sys.stderr)
            return 2
        if any(CHECKS[x][0] for x in wanted) and "package" not in wanted:
            wanted.insert(0, "package")
        names = [n for n in names if n in wanted]
    started = time.monotonic()
    failed = [n for n in names if not run(n)]
    print(f"{len(names) - len(failed)} of {len(names)} checks passed in {time.monotonic() - started:.0f} s"
          + (f"; failed: {', '.join(failed)}" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
