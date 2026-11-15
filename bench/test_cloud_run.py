"""tools/cloud-run.py's parts that need no GitHub: which listed run is the one it started, and run times."""
import importlib.util
import sys
from pathlib import Path

spec = importlib.util.spec_from_file_location("cloud_run", Path(__file__).resolve().parent.parent / "tools" / "cloud-run.py")
C = importlib.util.module_from_spec(spec)
spec.loader.exec_module(C)
checks = 0


def check(ok, what):
    global checks
    if not ok:
        print("FAIL:", what)
        sys.exit(1)
    checks += 1


runs = [{"databaseId": 1, "displayTitle": "Cloud pack sweep: all req-aaaaaaaaaaaa"},
        {"databaseId": 2, "displayTitle": "Cloud pack sweep: all --fix req-bbbbbbbbbbbb "},
        {"databaseId": 3, "displayTitle": "Cloud pack sweep: all "}]
check(C.pick(runs, "req-bbbbbbbbbbbb")["databaseId"] == 2, "the run named with the request id is the one started")
check(C.pick(runs, "req-cccccccccccc") is None, "no run carries another request's id")
check(C.seconds("2026-11-15T18:00:00Z", "2026-11-15T18:17:05Z") == 1025, "run time from GitHub's timestamps")
check(C.seconds("0001-01-01T00:00:00Z", "2026-11-15T18:17:05Z") is None, "a job that never started has no time")
check(C.duration(1025) == "17m05s" and C.duration(None) == "—", "times print as minutes and seconds")
check(C.WORKFLOWS["packs"] == "cloud-packs.yml" and all((C.ROOT / ".github" / "workflows" / f).is_file() for f in C.WORKFLOWS.values()),
      "every short name is a workflow in .github/workflows")
print(f"{checks} cloud-run checks passed")
