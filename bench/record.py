"""Record what happened, so no one edits the truth files by hand.

    python bench/record.py port RECEIPT --mod NAME --loader fabric [--status S] [--detail TEXT] [--measure ID]
        A port run becomes a row in coverage/ledger.json. RECEIPT is the result.json that modaccel port
        printed (or its --json output saved to a file). The versions and the errors left are read from it.
        The status is build when the port built and stop when errors are left; load and behaviour are
        claims about the game, so they need --status and a --detail that says what was checked and how.
        --measure also records the errors left as a point on that measure.

    python bench/record.py step ID start|done|next|later [--result TEXT] [--target DATE]
        A roadmap step changes status in coverage/roadmap.json. start sets it to doing, done sets its done
        date. A new --target keeps the old date in the step's "moved" list.

Numbers that are not a port's error count: python bench/measures.py add ID VALUE --source WHERE.
ROADMAP.md and docs/ are not touched here; main rewrites them after every merge.
"""
import argparse
import datetime
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import measures as M  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
LEDGER = ROOT / "coverage" / "ledger.json"
STATUSES = ("stop", "build", "load", "behaviour")


def today():
    return datetime.date.today().isoformat()


def load_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def save_ledger(ledger, path=LEDGER):
    Path(path).write_text(json.dumps(ledger, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")


def save_roadmap(roadmap, path=M.ROADMAP):
    Path(path).write_text(json.dumps(roadmap, indent=1, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")


# ---- a port receipt -----------------------------------------------------------------------------
def read_port(receipt):
    """What a port run reached, from modaccel's receipt: versions, whether it built, and each error left."""
    out = receipt if isinstance(receipt, dict) else load_json(receipt)
    result = out.get("result", out)
    waypoints = next((s.get("waypoints") for s in out.get("stages", []) if s.get("name") == "route" and s.get("waypoints")), None)
    unresolved = result.get("unresolved") or []
    built = bool(result.get("ok")) and bool(result.get("built") or result.get("compiled"))
    errors = 0 if built else (len(unresolved) if unresolved else None)
    loaders = sorted({l for p in (result.get("support") or {}).get("projects", []) for l in (p.get("loaders") or ([p["loader"]] if p.get("loader") else []))})
    return {
        "ok": bool(result.get("ok")), "built": built, "errors": errors, "unresolved": unresolved,
        "from": waypoints[0] if waypoints else None, "to": waypoints[-1] if waypoints else None,
        "loader": loaders[0] if len(loaders) == 1 else None, "seconds": out.get("seconds"),
        "reason": result.get("reason") or result.get("error") or (None if built or unresolved else result.get("details")),
        "tier": result.get("tier"), "receipt": out.get("receipt"), "root": out.get("root"),
    }


def cause(error):
    """The update a left-over error is blamed on, in the port's own words; "not placed" when it named none."""
    changed = error.get("changedIn") or []
    first = changed[0] if changed else None
    return (first.get("hop") or first.get("step") or "not placed") if isinstance(first, dict) else (str(first) if first else "not placed")


def subject(error):
    """What the error is about: the missing name when the port found one, else the compiler's message."""
    if error.get("symbol"):
        return (f"{error['owner']}." if error.get("owner") else "") + str(error["symbol"])
    return " ".join(str(error.get("message", "")).split())[:80]


def port_detail(run):
    if run["built"]:
        return "Ported by modaccel port --fix with nothing written by hand: it builds and the mixin check passed. Not started in the game."
    if run["errors"] is None:
        return f"modaccel port --fix stopped before compiling: {' '.join(str(run['reason'] or 'no reason given').split())[:300]}"
    by = {}
    for e in run["unresolved"]:
        by.setdefault(cause(e), []).append(subject(e))
    parts = [f"{len(v)} from {k} ({', '.join(sorted(set(v))[:3])}{', ...' if len(set(v)) > 3 else ''})" for k, v in sorted(by.items(), key=lambda kv: -len(kv[1]))]
    return f"modaccel port --fix with nothing written by hand stops at {run['errors']} error{'s' if run['errors'] != 1 else ''}: " + "; ".join(parts) + "."


def ledger_row(run, mod, loader, status=None, detail=None, date=None, frm=None, to=None):
    """The ledger row for a port run. Raises ValueError with what is missing rather than guessing it."""
    frm, to, loader = frm or run["from"], to or run["to"], loader or run["loader"]
    missing = [name for name, v in (("--from", frm), ("--to", to), ("--loader", loader), ("--mod", mod)) if not v]
    if missing:
        raise ValueError(f"The receipt does not say {', '.join(missing)}; pass {'it' if len(missing) == 1 else 'them'}.")
    reached = "build" if run["built"] else "stop"
    status = status or reached
    if status not in STATUSES:
        raise ValueError(f"status {status} is not one of {', '.join(STATUSES)}")
    if status != "stop" and not run["built"]:
        raise ValueError(f"The port did not build ({run['errors'] if run['errors'] is not None else 'no'} errors left), so it cannot be recorded as {status}.")
    if status in ("load", "behaviour") and not detail:
        raise ValueError(f"A {status} row is a claim about the game, which a port receipt cannot show. Pass --detail saying what was checked, by which command, and what it compared.")
    return {"mod": mod, "loader": loader, "from": frm, "to": to, "status": status, "detail": detail or port_detail(run), "date": date or today()}


def add_port(receipt, mod, loader=None, status=None, detail=None, date=None, frm=None, to=None, measure=None, source=None, ledger_path=LEDGER, dry=False):
    run = read_port(receipt)
    row = ledger_row(run, mod, loader, status, detail, date, frm, to)
    ledger = load_json(ledger_path)
    if not dry:
        ledger["hops"].append(row)
        save_ledger(ledger, ledger_path)
    point = None
    if measure and run["errors"] is not None:
        point = add_point(measure, run["errors"], source or str(run["receipt"] or receipt), row["date"], dry=dry)
    return row, point


def add_point(measure, value, source, date=None, note=None, dry=False):
    data = M.load(M.MEASURES)
    m = next((x for x in data["measures"] if x["id"] == measure), None)
    if not m:
        raise ValueError(f"No measure {measure}. Add its entry to coverage/measures.json first.")
    p = {"date": date or today(), "value": value}
    if note:
        p["note"] = note
    p["source"] = source
    if not dry:
        m.setdefault("points", []).append(p)
        m["points"].sort(key=lambda x: x["date"])
        M.MEASURES.write_text(M.dump(data), encoding="utf-8", newline="\n")
    return p


# ---- a roadmap step -----------------------------------------------------------------------------
def set_step(roadmap, sid, action, result=None, target=None, date=None):
    """Move a step to its next status in place and return it. Raises ValueError on a move that would be a false claim."""
    step = next((s for g in roadmap["goals"] for s in g["steps"] if s["id"] == sid), None)
    if not step:
        raise ValueError(f"No step {sid} in coverage/roadmap.json.")
    date = date or today()
    if target and target != step.get("target"):
        if step.get("target"):
            step.setdefault("moved", []).append(step["target"])
        step["target"] = target
    if action == "start":
        if step["status"] == "done":
            raise ValueError(f"Step {sid} is done. To reopen it, say why in --result and use next or later first.")
        if step["status"] != "doing":
            step["start"] = date
        step["status"] = "doing"
    elif action == "done":
        if not result:
            raise ValueError(f"Step {sid} needs --result: what ran and what decided the pass. Done means it ran and was checked.")
        step["status"], step["done"] = "done", date
        if step["start"] > date:
            step["start"] = date
    else:
        step["status"] = action
        step.pop("done", None)
    if result:
        step["result"] = result
    if step["status"] != "done" and not step.get("target"):
        raise ValueError(f"Step {sid} is {step['status']} with no target date; pass --target YYYY-MM-DD.")
    return step


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("port", help="a port run becomes a ledger row")
    p.add_argument("receipt")
    p.add_argument("--mod", required=True, help="the mod's name as the ledger spells it")
    p.add_argument("--loader")
    p.add_argument("--from", dest="frm")
    p.add_argument("--to")
    p.add_argument("--status", choices=STATUSES)
    p.add_argument("--detail")
    p.add_argument("--measure", help="also record the errors left as a point on this measure")
    p.add_argument("--date", default=None)
    p.add_argument("--dry-run", action="store_true", help="print the row, write nothing")
    s = sub.add_parser("step", help="a roadmap step changes status")
    s.add_argument("id")
    s.add_argument("action", choices=("start", "done", "next", "later"))
    s.add_argument("--result")
    s.add_argument("--target")
    s.add_argument("--date", default=None)
    args = ap.parse_args(argv)
    for stream in (sys.stdout, sys.stderr):   # a Windows console is not UTF-8 by default, and the tables use arrows
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    try:
        if args.cmd == "port":
            row, point = add_port(args.receipt, args.mod, args.loader, args.status, args.detail, args.date, args.frm, args.to, args.measure, dry=args.dry_run)
            print(("Would add" if args.dry_run else "Added") + " to coverage/ledger.json:")
            print(json.dumps(row, indent=2, ensure_ascii=False))
            if point:
                print(f"{args.measure}: {json.dumps(point, ensure_ascii=False)}")
        else:
            roadmap = M.load(M.ROADMAP)
            step = set_step(roadmap, args.id, args.action, args.result, args.target, args.date)
            save_roadmap(roadmap)
            print(f"Step {step['id']} ({step['title']}) is {step['status']}"
                  + (f", done {step['done']}" if step.get("done") else f", started {step['start']}, target {step['target']}" if step["status"] == "doing" else f", target {step['target']}"))
        found = M.problems(M.Book())
        for line in found:
            print(line, file=sys.stderr)
        return 1 if found else 0
    except (ValueError, OSError) as e:
        print(f"record: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
