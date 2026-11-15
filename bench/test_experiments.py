"""Every file a shipped perf-optimize experiment names is in git, so a fresh clone passes preflight. No game, no network.

    python bench/test_experiments.py

An experiment (bench/**/experiment.json) names files to copy into each launch ("files"), setup and
before-capture snippets, its probes and its gate snippets. All of them must be committed, except
sources under .runs/, which a prepare step generates (the Diorama save: bench/diorama/play/prepare_world.py,
which must then promise to produce each of them).
"""
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "bench" / "diorama" / "play"))
import prepare_world  # noqa: E402

failures = []


def check(cond, what):
    if not cond:
        failures.append(what)


def named(experiment):
    """Every file path the experiment names, relative to its own folder."""
    paths = list((experiment.get("files") or {}).values())
    for step in experiment.get("setup") or []:
        paths.append(step.get("file"))
    measure = experiment.get("measure") or {}
    paths.append(measure.get("probes"))
    for steps in (measure.get("before") or {}).values():
        paths.extend(step.get("file") for step in steps)
    paths.extend(gate.get("file") for gate in experiment.get("gates") or [])
    return [p for p in paths if p]


def tracked():
    out = subprocess.run(["git", "-C", str(ROOT), "ls-files", "-z"], capture_output=True, check=True).stdout
    return {name.decode("utf-8") for name in out.split(b"\0") if name}


in_git = tracked()
experiments = sorted(p for p in in_git if p.startswith("bench/") and p.endswith("/experiment.json"))
check(len(experiments) >= 3, f"found the shipped experiments: {experiments}")
runs = (ROOT / ".runs").resolve()
for name in experiments:
    folder = (ROOT / name).parent
    experiment = json.loads((ROOT / name).read_text(encoding="utf-8"))
    for item in named(experiment):
        path = (folder / item).resolve()
        if path.is_relative_to(runs):
            continue
        rel = path.relative_to(ROOT).as_posix()
        check(rel in in_git, f"{name} names {item}, which is not committed (ignored by .gitignore?)")

# The Diorama save is generated: prepare_world must produce every file its experiment copies out of it.
diorama = ROOT / "bench" / "diorama" / "play" / "experiment.json"
need = prepare_world.required(json.loads(diorama.read_text(encoding="utf-8")), diorama.parent)
check("region/r.-1.1.mca" in need and "region/r.0.0.mca" in need, f"prepare_world reads the regions the experiment copies: {need}")
check(prepare_world.region_chunk("region/r.-1.1.mca") == (-1, 32), "a missing region gets the chunk nearest the origin generated")
check(prepare_world.region_chunk("region/r.1.-1.mca") == (32, -1), "nearest-origin chunk of r.1.-1")
check(prepare_world.region_chunk("DIM-1/region/r.0.0.mca") is None, "only overworld regions are generated")

if failures:
    print("FAILED:")
    for f in failures:
        print(" -", f)
    sys.exit(1)
print(f"experiments: {len(experiments)} shipped experiments name only committed or prepared files")
