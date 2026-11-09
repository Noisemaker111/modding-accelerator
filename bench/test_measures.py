"""coverage/measures.json and coverage/roadmap.json are valid, agree with each other, and ROADMAP.md is
generated from them. Each check is shown failing once on a broken copy, so a pass means something.

    python bench/test_measures.py
"""
import copy
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bench_page  # noqa: E402
import measures as M  # noqa: E402

checks = 0


def check(ok, label):
    global checks
    if not ok:
        raise AssertionError(label)
    checks += 1


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

book = M.Book()
found = M.problems(book)
check(not found, "coverage files have problems:\n  " + "\n  ".join(found))

# ROADMAP.md and the page render from the two files. Neither is compared with the copy in the checkout:
# main rewrites both after every merge (.github/workflows/generated.yml), so a branch never has to.
have = bench_page.roadmap_md(book)
check(have.startswith("# Roadmap") and all(f"## Goal {g['id']} — " in have for g in book.roadmap["goals"]), "ROADMAP.md renders every goal")
check("<title>Modding Accelerator</title>" in bench_page.page(book), "the page renders")


def broken(edit):
    """Problems found after one edit to copies of both files."""
    data, road = copy.deepcopy(book.data), copy.deepcopy(book.roadmap)
    edit(data, road)
    return M.problems(M.Book(data, road, book.ledger, book.index))


first = lambda d: next(m for m in d["measures"] if m.get("points"))
check(any("no source" in p for p in broken(lambda d, r: first(d)["points"][0].pop("source"))), "a point without a source must fail")
check(any("date order" in p for p in broken(lambda d, r: first(d)["points"].insert(0, {"date": "2099-01-01", "value": 1, "source": "x"}))), "points out of order must fail")
check(any("no target" in p for p in broken(lambda d, r: next(m for m in d["measures"] if m.get("target") and not m.get("computed")).pop("target"))), "a measure without a target must fail")
check(any("not in the roadmap" in p for p in broken(lambda d, r: next(m for m in d["measures"] if (m.get("target") or {}).get("step")).__setitem__("target", {"value": 1, "by": "2026-12-01", "step": "999"}))), "a target on a missing step must fail")
check(any("not in coverage/measures.json" in p for p in broken(lambda d, r: r["goals"][0]["steps"][0].setdefault("measures", []).append("no.such.measure"))), "a step naming a missing measure must fail")
check(any("no target date" in p for p in broken(lambda d, r: next(s for p in r["goals"] for s in p["steps"] if s["status"] != "done").pop("target"))), "an open step without a target date must fail")
check(any("no done date" in p for p in broken(lambda d, r: next(s for p in r["goals"] for s in p["steps"] if s["status"] == "done").pop("done"))), "a done step without a done date must fail")
check(any("used twice" in p for p in broken(lambda d, r: d["measures"].append(copy.deepcopy(d["measures"][-1])))), "a repeated id must fail")
check(any("headline no.such.measure" in p for p in broken(lambda d, r: r["goals"][0]["headline"].append("no.such.measure"))), "a goal headline naming a missing measure must fail")
check(any("no headline" in p for p in broken(lambda d, r: r["goals"][0].pop("headline"))), "a goal without a headline measure must fail")
check(any("no part" in p for p in broken(lambda d, r: r["goals"][0]["steps"][0].pop("part"))), "a step without a part must fail")

# the render follows the data: change one step's text and ROADMAP.md changes
road = copy.deepcopy(book.roadmap)
road["goals"][0]["steps"][0]["text"] += " (edited)"
check(bench_page.roadmap_md(M.Book(book.data, road, book.ledger, book.index)) != have, "an edited roadmap.json must change what ROADMAP.md renders to")

# state and format: one rule, the same everywhere
m = {"id": "t", "area": "test", "name": "t", "unit": "errors", "better": "lower", "target": {"value": 0, "by": "2026-12-01"},
     "points": [{"date": "2026-10-01", "value": 40, "source": "x"}, {"date": "2026-10-02", "value": 10, "source": "x"}]}
tb = M.Book({**book.data, "measures": [m]}, book.roadmap, book.ledger, book.index)
check(tb.state(m) == ("open", 0.75), f"40 -> 10 of a target 0 is 75% of the way, got {tb.state(m)}")
m["points"].append({"date": "2026-10-03", "value": 0, "source": "x"})
check(tb.state(m)[0] == "met", "reaching the target is met")
check(M.fmt(36.5, "s") == "36.5 s" and M.fmt(165, "s", True) == "2.8 min" and M.fmt(1, "files") == "1 file", "number format")
check(json.loads(M.dump(book.data)) == book.data, "dump round-trips")
check(M.MEASURES.read_text(encoding="utf-8") == M.dump(M.load(M.MEASURES)), "coverage/measures.json is in the dump layout (one line per point)")

print(f"{checks} measure checks passed")
