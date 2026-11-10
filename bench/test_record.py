"""bench/record.py and bench/port_board.py do what their help says, on made-up receipts and a made-up mod.

No game, no Gradle, no network: the board runs against a stand-in for modaccel (a Python file that prints a
port result) and a mod repository made in a temporary folder.
"""
import contextlib
import copy
import io
import json
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import measures as M  # noqa: E402
import port_board  # noqa: E402
import record  # noqa: E402

checks = 0


def check(ok, what):
    global checks
    checks += 1
    if not ok:
        raise AssertionError(what)


def raises(fn, text):
    try:
        fn()
    except ValueError as e:
        return text in str(e)
    return False


ERR = lambda sym, hop: {"file": "A.java", "line": 3, "message": "cannot find symbol", "symbol": sym, "owner": "FoodData", "changedIn": [{"step": "x", "hop": hop}] if hop else []}
STOP = {"command": "port", "root": "/m", "seconds": 61.5, "receipt": "/m/.runs/portable/r1/result.json",
        "stages": [{"name": "route", "waypoints": ["1.20.1", "1.20.4", "1.21.1", "1.21.6"]}],
        "result": {"ok": False, "compiled": False, "built": False, "unresolved": [ERR("getExhaustion", "1.20.4 -> 1.20.5"), ERR("getExhaustion", "1.20.4 -> 1.20.5"), ERR("blit", None)],
                   "support": {"projects": [{"path": ":", "loaders": ["fabric"]}]}}}
BUILT = {"command": "port", "seconds": 40, "receipt": "/m/r2/result.json", "stages": [{"name": "route", "waypoints": ["1.21.5", "1.21.6"]}],
         "result": {"ok": True, "compiled": True, "built": True, "details": "Built."}}
BLOCKED = {"command": "port", "stages": [], "result": {"ok": False, "tier": "dependency-blockers", "reason": "No 1.21.6 build of jei"}}

# ---- reading a receipt
run = record.read_port(STOP)
check((run["errors"], run["built"], run["from"], run["to"], run["loader"]) == (3, False, "1.20.1", "1.21.6", "fabric"), f"a stopped port: {run}")
check(record.read_port(BUILT)["errors"] == 0 and record.read_port(BUILT)["built"], "a built port has no errors left")
check(record.read_port(BLOCKED)["errors"] is None, "a port stopped before compiling has no error count, not zero")
check(record.read_port(STOP["result"])["errors"] == 3, "the bare result is read the same way as the whole receipt")

# ---- the ledger row
row = record.ledger_row(run, "AppleSkin", None)
check(row["status"] == "stop" and row["loader"] == "fabric" and "stops at 3 errors" in row["detail"] and "2 from 1.20.4 -> 1.20.5 (FoodData.getExhaustion)" in row["detail"]
      and "1 from not placed" in row["detail"], f"a stop row names its errors by cause: {row['detail']}")
check(record.ledger_row(record.read_port(BUILT), "F3Nope", "fabric")["status"] == "build", "a built port is recorded as build, never more")
check(raises(lambda: record.ledger_row(record.read_port(BUILT), "F3Nope", "fabric", status="behaviour"), "claim about the game"), "behaviour without --detail must be refused")
check(record.ledger_row(record.read_port(BUILT), "F3Nope", "fabric", status="load", detail="ui-check listed the mod")["status"] == "load", "load with a detail is accepted")
check(raises(lambda: record.ledger_row(run, "AppleSkin", None, status="build"), "did not build"), "a port with errors cannot be recorded as build")
check(raises(lambda: record.ledger_row(record.read_port(BLOCKED), "X", None), "--from, --to, --loader"), "a receipt with no route asks for what is missing")

with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
    tmp = Path(tmp)
    ledger = tmp / "ledger.json"
    before = (record.LEDGER).read_text(encoding="utf-8")
    ledger.write_text(before, encoding="utf-8", newline="\n")
    (tmp / "r.json").write_text(json.dumps(STOP), encoding="utf-8")
    row, point = record.add_port(tmp / "r.json", "AppleSkin", ledger_path=ledger, date="2026-11-09")
    after = ledger.read_text(encoding="utf-8")
    check(json.loads(after)["hops"][-1] == row and json.loads(after)["hops"][:-1] == json.loads(before)["hops"], "a port is appended and no other row moves")
    undone = json.loads(after)
    undone["hops"].pop()
    check(json.dumps(undone, indent=2, ensure_ascii=False) + "\n" == before, "without the new row the ledger file is byte for byte what it was")
    record.add_port(tmp / "r.json", "AppleSkin", ledger_path=ledger, dry=True)
    check(ledger.read_text(encoding="utf-8") == after, "--dry-run writes nothing")

    # ---- a roadmap step
    road = M.load(M.ROADMAP)
    nxt = next(s for g in road["goals"] for s in g["steps"] if s["status"] in ("next", "later"))
    r = copy.deepcopy(road)
    s = record.set_step(r, nxt["id"], "start", date="2026-11-10")
    check((s["status"], s["start"]) == ("doing", "2026-11-10") and not M.problems(M.Book(roadmap=r)), "start makes a step doing from today, and the roadmap stays valid")
    check(raises(lambda: record.set_step(copy.deepcopy(road), nxt["id"], "done"), "needs --result"), "done without a result must be refused")
    s = record.set_step(r, nxt["id"], "done", result="ran X, Y decided the pass", date="2026-11-12")
    check((s["status"], s["done"]) == ("done", "2026-11-12") and not M.problems(M.Book(roadmap=r)), "done sets the done date")
    r = copy.deepcopy(road)
    s = record.set_step(r, nxt["id"], "next", target="2027-06-01")
    check(s["target"] == "2027-06-01" and s["moved"][-1] == nxt["target"], "a moved target keeps its old date")
    check(raises(lambda: record.set_step(copy.deepcopy(road), "no-such", "start"), "No step"), "an unknown step is refused")

    # ---- the board, against a stand-in modaccel and a mod made here
    mod = tmp / "upstream"
    mod.mkdir()
    git = lambda *a, cwd=mod: subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "-c", "commit.gpgsign=false", *a], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()
    git("init", "-q")
    (mod / "Mod.java").write_text("class Mod {}\n", encoding="utf-8")
    git("add", "-A")
    git("commit", "-q", "-m", "mod")
    commit = git("rev-parse", "HEAD")
    stub = tmp / "stub.py"
    # The stand-in "ports" by editing the source, and answers with the errors named in a file beside it.
    stub.write_text('''import json, sys, pathlib
args = sys.argv[1:]
if "--undo" in args: sys.exit(0)
root = pathlib.Path(args[args.index("--root") + 1])
(root / "Mod.java").write_text("class Mod { /* ported */ }\\n"); (root / "left-over.txt").write_text("x")
n = int((pathlib.Path(__file__).parent / "errors.txt").read_text())
err = lambda k: {"file": "Mod.java", "line": k, "message": "cannot find symbol", "symbol": "blit", "changedIn": [{"hop": "1.21.5 -> 1.21.6"}]}
print("Picked up noise")
print(json.dumps({"command": "port", "seconds": 1, "receipt": str(root / "result.json"),
                  "stages": [{"name": "route", "waypoints": [args[args.index("--from") + 1], args[args.index("--to") + 1]]}],
                  "result": {"ok": n == 0, "built": n == 0, "compiled": n == 0, "unresolved": [err(k) for k in range(n)]}}))
''', encoding="utf-8")
    cases = tmp / "cases.json"
    cases.write_text(json.dumps({"cases": [
        {"id": "known", "mod": "Known Mod", "repo": str(mod), "commit": commit, "loader": "fabric", "from": "1.20.1", "to": "1.21.6", "measure": "port.errors.known"},
        {"id": "fresh", "mod": "Made Up Mod", "repo": str(mod), "commit": commit, "loader": "fabric", "from": "1.21.5", "to": "1.21.6"}]}), encoding="utf-8")
    # What is "recorded" is made up here too, so the test does not change when the real numbers do.
    recorded_errors = 5
    data = copy.deepcopy(M.load(M.MEASURES))
    data["measures"].append({"id": "port.errors.known", "area": "port", "name": "Errors left: Known Mod", "unit": "errors", "better": "lower",
                             "target": {"value": 0, "by": "2027-01-01"}, "points": [{"date": "2026-11-01", "value": recorded_errors, "source": "test"}]})
    fake_book = M.Book(measures=data)
    fake_ledger = {"hops": [{"mod": "Known Mod", "loader": "fabric", "from": "1.20.1", "to": "1.21.6", "status": "stop", "detail": "5 errors", "date": "2026-11-01"}]}

    def board(errors, *extra):
        (tmp / "errors.txt").write_text(str(errors))
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = port_board.main(["--cases", str(cases), "--cache", str(tmp / "cache"), "--modaccel", str(stub), *extra], book=fake_book, ledger=fake_ledger)
        return code, out.getvalue()

    code, text = board(recorded_errors)
    check(code == 0 and f"| {recorded_errors} errors | — | {recorded_errors} errors | same |" in text and "| — | — |" in text and "| new |" in text, f"an unchanged port is same, a port never seen is new:\n{text}")
    check("| 1.21.5 -> 1.21.6 | blit |" in text and "Known Mod, Made Up Mod" in text, f"what is left is grouped by update across mods:\n{text}")
    check((tmp / "cache" / "known" / "Mod.java").read_text(encoding="utf-8") != "class Mod {}\n", "the port really edited the clone")
    code, text = board(recorded_errors + 2)
    check(code == 1 and "**worse**" in text and f"| {recorded_errors} errors | {recorded_errors + 2} errors |" in text, f"more errors than recorded is worse, exits 1, and shows the last run:\n{text}")
    code, text = board(0)
    check(code == 0 and "| builds | better |" in text, f"a port that now builds is better:\n{text}")
    code, text = board(0, "--only", "nope")
    check(code == 2, "an unknown case id is refused")
    (tmp / "errors.txt").write_text("not a number")
    code, text = board("oops")
    check(code == 1 and "**did not run**" in text and "Did not run:" in text, f"a port that cannot run is reported, not hidden:\n{text}")

    # a pin on another Minecraft version than the case says is reported, not ported
    (mod / "gradle.properties").write_text("minecraft_version=1.21.5\n", encoding="utf-8")
    git("add", "-A")
    git("commit", "-q", "-m", "says its version")
    wrong = json.loads(cases.read_text(encoding="utf-8"))
    for c in wrong["cases"]:
        c["commit"] = git("rev-parse", "HEAD")
    cases.write_text(json.dumps(wrong), encoding="utf-8")
    code, text = board(0, "--only", "known")
    check(code == 1 and "the pinned source is on Minecraft 1.21.5, but the case ports from 1.20.1" in text, f"a pin on the wrong version is refused:\n{text}")
    code, text = board(0, "--only", "fresh")
    check(code == 0 and "| builds |" in text, f"a pin on the right version runs:\n{text}")

    # each run starts from the pinned source: the last port's edits and stray files are gone before the next
    work = port_board.checkout({"id": "known", "repo": str(mod), "commit": commit}, tmp / "cache", [sys.executable, str(stub)])
    check((work / "Mod.java").read_text(encoding="utf-8") == "class Mod {}\n" and not (work / "left-over.txt").exists(), "a case is put back to its pinned commit before it runs")

check(record.LEDGER.read_text(encoding="utf-8") == before, "the real ledger was not touched")
print(f"{checks} record and board checks passed")
