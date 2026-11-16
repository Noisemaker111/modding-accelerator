"""The source launcher (mmt.cmd on Windows, ./mmt elsewhere) checks every shipped perf experiment offline.

Windows broke perf-optimize twice in ways only a verifier running it there saw (#76, 877ec25): this runs the real
launcher from a checkout whose path has a space, with the experiment path in the system's own separators. Without the
prepared worlds and modpacks every experiment reports only its missing worldSource or pack.input; any other problem fails.

    python tools/ci/launcher_experiments.py
"""
import json, os, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main():
    launcher = [str(ROOT / "mmt.cmd")] if os.name == "nt" else [str(ROOT / "mmt")]
    failures, checked = [], 0
    for experiment in sorted(ROOT.glob("bench/*/experiment*.json")):
        path = str(experiment.relative_to(ROOT)).replace("/", os.sep)
        out = subprocess.run(launcher + ["perf-optimize", "--check-only", "--experiment", path, "--json"], cwd=ROOT,
                             capture_output=True, text=True, encoding="utf-8", errors="replace")
        try:
            answer = json.loads(out.stdout.strip().splitlines()[-1])
        except (ValueError, IndexError):
            failures.append(f"{path}: no JSON answer (exit {out.returncode})\n{out.stdout[-2000:]}{out.stderr[-2000:]}")
            continue
        problems = (answer.get("result") or {}).get("problems")
        if not isinstance(problems, list):
            failures.append(f"{path}: {answer.get('error') or answer}")
            continue
        # A save and a modpack are made once per machine (bench/MOD/README.md), never committed.
        other = [p for p in problems if not str(p).startswith(("worldSource ", "pack.input "))]
        if other:
            failures.append(f"{path}: {other}")
        checked += 1
    print("\n".join(failures) or f"{checked} experiments checked through {Path(launcher[0]).name}: only their unprepared worlds and packs are missing")
    return 1 if failures or not checked else 0


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")
    sys.exit(main())
