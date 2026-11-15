"""prepare_world.py makes every file the play experiment copies out of its save. No game, no network.

    python bench/diorama/test_prepare_world.py
"""
import json
import sys
from pathlib import Path

PLAY = Path(__file__).resolve().parent / "play"
sys.path.insert(0, str(PLAY))
import prepare_world  # noqa: E402

failures = []


def check(cond, what):
    if not cond:
        failures.append(what)


need = prepare_world.required(json.loads((PLAY / "experiment.json").read_text(encoding="utf-8")), PLAY)
check(len(need) == 9 and "region/r.-1.1.mca" in need and "region/r.0.0.mca" in need, f"the regions the experiment copies: {need}")
check(prepare_world.region_chunk("region/r.-1.1.mca") == (-1, 32), "r.-1.1, which the flight never reaches, gets its chunk nearest the origin")
check(prepare_world.region_chunk("region/r.1.-1.mca") == (32, -1), "nearest-origin chunk of r.1.-1")
check(prepare_world.region_chunk("region/r.0.0.mca") == (0, 0), "nearest-origin chunk of r.0.0")
check(prepare_world.region_chunk("DIM-1/region/r.0.0.mca") is None, "only overworld regions are generated")

if failures:
    print("FAILED:")
    for f in failures:
        print(" -", f)
    sys.exit(1)
print("prepare_world: every region the play experiment copies is made")
