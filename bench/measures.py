"""Every number modaccel shows, in one format: coverage/measures.json, tied to coverage/roadmap.json.

A measure is one thing measured the same way each time (same work, same cache condition), with all
its points and the target it is heading for. The roadmap page, ROADMAP.md and PR tables all read
measures through this module, so a number looks the same and means the same everywhere.

    python bench/measures.py table [ID ...] [--area AREA] [--step STEP]   Markdown table for a PR or report
    python bench/measures.py add ID VALUE --source S [--date D] [--n N] [--note T]   also rewrites ROADMAP.md
    python bench/measures.py check                                         validate both files
"""
import argparse
import json
import re
import sys
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ledger_rule  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
MEASURES = ROOT / "coverage" / "measures.json"
ROADMAP = ROOT / "coverage" / "roadmap.json"
LEDGER = ROOT / "coverage" / "ledger.json"
INDEX = ROOT / "coverage" / "changes" / "index.json"

STATUSES = ("done", "doing", "next", "later")
UNITS = {"s", "min", "GB", "errors", "files", "rules", "mods", "updates", "steps", "versions", "packs", "repairs",
         "items", "sessions", "cores", "CPU-s", "pairs"}
ISO = re.compile(r"^\d{4}-\d{2}-\d{2}$")
MONTHS = "Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec".split()


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def dump(data):
    """JSON with every flat object (a point, a target) on one line, so a new point is a one-line diff."""
    text = json.dumps(data, indent=1, ensure_ascii=False)
    flat = re.compile(r"\{\n\s+([^{}\[\]]*?)\n\s*\}")
    return flat.sub(lambda m: "{" + " ".join(x.strip() for x in m.group(1).split("\n")) + "}", text) + "\n"


class Book:
    """Measures and roadmap loaded together, with computed points filled in."""

    def __init__(self, measures=None, roadmap=None, ledger=None, index=None):
        self.data = measures if measures is not None else load(MEASURES)
        self.roadmap = roadmap if roadmap is not None else load(ROADMAP)
        self.ledger = ledger if ledger is not None else load(LEDGER)
        self.index = index if index is not None else (load(INDEX) if INDEX.is_file() else None)
        self.by_id = {m["id"]: m for m in self.data["measures"]}
        self.steps = {s["id"]: dict(s, goal=g["id"]) for g in self.roadmap["goals"] for s in g["steps"]}

    # ---- points ------------------------------------------------------------------------------
    def points(self, m):
        how = m.get("computed")
        if not how:
            return list(m.get("points", []))
        if how == "roadmap.steps-done":
            done = sorted(s["done"] for s in self.steps.values() if s.get("done"))
            return [{"date": d, "value": done.index(d) + done.count(d), "source": "coverage/roadmap.json"} for d in sorted(set(done))]
        if how.startswith("ledger.updates-working:"):
            _, loader, lo, hi = how.split(":")
            every = [v["id"] for v in self.index["allVersions"]]
            blocked = {v["id"] for v in self.index["allVersions"] if v["status"] == "blocked"}
            keys = {f"{every[i]}__{every[i + 1]}" for i in range(every.index(lo), every.index(hi))}
            out = []
            for d in sorted({h["date"] for h in self.ledger["hops"]}):
                hops = [h for h in self.ledger["hops"] if h["date"] <= d]
                st = ledger_rule.step_statuses(every, blocked, hops, [], loader)
                n = sum((st[k]["fwd"]["c"] == "green") + (st[k]["back"]["c"] == "green") for k in keys)
                if not out or out[-1]["value"] != n:
                    out.append({"date": d, "value": n, "source": "coverage/ledger.json"})
            return out
        if how == "ledger.mods-working":
            out = []
            for d in sorted({h["date"] for h in self.ledger["hops"]}):
                mods = sorted({h["mod"] for h in self.ledger["hops"] if h["date"] <= d and h["status"] in ("load", "behaviour")})
                if not out or out[-1]["value"] != len(mods):
                    out.append({"date": d, "value": len(mods), "note": ", ".join(mods), "source": "coverage/ledger.json"})
            return out
        raise ValueError(f"{m['id']}: unknown computed source {how}")

    def target(self, m):
        t = m.get("target")
        if not t:
            return None
        t = dict(t)
        if t.get("value") == "all":
            t["value"] = len(self.steps)
        if t.get("by") == "last-target":
            t["by"] = max(s.get("target") or s.get("done") for s in self.steps.values())
        return t

    # ---- state -------------------------------------------------------------------------------
    def state(self, m):
        """met | open | unmeasured | context | info, plus progress 0..1 from the first point to the target."""
        pts, t = self.points(m), self.target(m)
        if m.get("context"):
            return "context", None
        if m.get("info"):
            return "info", None
        if not pts:
            return "unmeasured", None
        if not t or t.get("value") is None:
            return "open", None
        now, first = pts[-1]["value"], pts[0]["value"]
        lower = m["better"] == "lower"
        if (now <= t["value"]) if lower else (now >= t["value"]):
            return "met", 1.0
        span = (first - t["value"]) if lower else (t["value"] - first)
        gone = (first - now) if lower else (now - first)
        return "open", max(0.0, min(1.0, gone / span)) if span > 0 else 0.0

    def area_name(self, m):
        return self.data["areas"].get(m["area"], m["area"])

    def step_status(self, sid):
        return self.steps[sid]["status"] if sid in self.steps else None


# ---- formatting ---------------------------------------------------------------------------------
def num(v, unit, minutes=False):
    """The number alone, rounded the same way everywhere. minutes: show seconds as minutes."""
    if v is None:
        return "—"
    if unit == "s" and minutes:
        return f"{v / 60:.1f}"
    if unit == "s":
        if v >= 100 or float(v).is_integer():
            return f"{int(v + 0.5)}"
        return f"{v:.2f}".rstrip("0").rstrip(".") if v < 1 else f"{v:.1f}"
    if unit in ("min", "GB"):
        return f"{v:.1f}"
    if unit == "cores":
        return f"{v:.2f}"
    if unit == "CPU-s":
        return f"{int(v + 0.5)}" if v >= 100 or float(v).is_integer() else f"{v:.1f}"
    return f"{int(round(v)):,}"


SINGULAR = {"errors": "error", "files": "file", "rules": "rule", "mods": "mod", "updates": "update", "steps": "step",
            "versions": "version", "packs": "pack", "repairs": "repair", "items": "item", "sessions": "session", "cores": "core", "pairs": "pair"}


def unit_word(v, unit, minutes=False):
    if unit == "s":
        return "min" if minutes else "s"
    if unit in ("min", "GB", "CPU-s"):
        return unit
    return SINGULAR.get(unit, unit) if v == 1 else unit


def fmt(v, unit, minutes=False):
    return "—" if v is None else f"{num(v, unit, minutes)} {unit_word(v, unit, minutes)}"


def minutes(book, m):
    """A seconds measure reads in minutes when any of its numbers reaches two minutes."""
    if m["unit"] != "s":
        return False
    t = book.target(m) or {}
    vals = [p["value"] for p in book.points(m)] + ([t["value"]] if isinstance(t.get("value"), (int, float)) else [])
    return bool(vals) and max(vals) >= 120


def label(m):
    """The measure's name, with its cache condition when the name does not already say it."""
    c = m.get("condition")
    return m["name"] if not c or c in m["name"].lower() else f"{m['name']}, {c}"


def day(d):
    """2026-10-24 -> Oct 24 (and the year when it is not 2026)."""
    y, mo, dd = d.split("-")
    return f"{MONTHS[int(mo) - 1]} {int(dd)}" + ("" if y == "2026" else f", {y}")


def state_words(book, m):
    st, prog = book.state(m)
    t = book.target(m)
    if st == "met":
        return "Met"
    if st == "context":
        return "Context, not ours to move"
    if st == "info":
        return "Fact"
    if st == "unmeasured":
        return "Not measured yet"
    if t is None or t.get("value") is None:
        return f"Target set at step {t['step']}" if t and t.get("step") else "No target"
    if len(book.points(m)) == 1:
        return "Baseline"
    return f"{round(prog * 100)}% of the way"


def target_words(book, m):
    t = book.target(m)
    if not t:
        return ""
    val = "set from the baseline" if t.get("value") is None else fmt(t["value"], m["unit"], minutes(book, m))
    hold = " (hold)" if (t.get("note") or "").startswith("hold") else ""
    return f"{val}{hold} by {day(t['by'])}" + (f", step {t['step']}" if t.get("step") else "")


# ---- markdown -----------------------------------------------------------------------------------
def table(book, ms):
    """The one Markdown table for numbers in a PR, report or ROADMAP.md."""
    rows = ["| Measure | First | Now | Target | Status |", "|---|---|---|---|---|"]
    for m in ms:
        pts, mins = book.points(m), minutes(book, m)
        first = f"{fmt(pts[0]['value'], m['unit'], mins)} ({day(pts[0]['date'])})" if pts else "—"
        now = f"{fmt(pts[-1]['value'], m['unit'], mins)} ({day(pts[-1]['date'])})" if pts else "not measured"
        rows.append(f"| {label(m)} | {first if len(pts) > 1 else '—'} | {now} | {target_words(book, m) or '—'} | {state_words(book, m)} |")
    return "\n".join(rows)


# ---- checks -------------------------------------------------------------------------------------
def problems(book):
    """Everything wrong with measures.json and roadmap.json, as plain sentences."""
    out = []
    seen = set()
    for m in book.data["measures"]:
        i = m.get("id", "?")
        if i in seen:
            out.append(f"{i}: id used twice")
        seen.add(i)
        for k in ("id", "area", "name", "unit", "better"):
            if k not in m:
                out.append(f"{i}: no {k}")
        if m.get("area") not in book.data["areas"]:
            out.append(f"{i}: area {m.get('area')} is not in areas")
        if m.get("unit") not in UNITS:
            out.append(f"{i}: unit {m.get('unit')} is not one of {sorted(UNITS)}")
        if m.get("better") not in ("lower", "higher"):
            out.append(f"{i}: better must be lower or higher")
        if m.get("machine") and m["machine"] not in book.data["machines"]:
            out.append(f"{i}: machine {m['machine']} is not in machines")
        if m.get("condition") not in (None, "warm", "cold"):
            out.append(f"{i}: condition must be warm or cold")
        if m.get("computed") and m.get("points"):
            out.append(f"{i}: computed measures take no points")
        last = ""
        for p in m.get("points", []):
            if not ISO.match(str(p.get("date", ""))):
                out.append(f"{i}: point date {p.get('date')} is not YYYY-MM-DD")
            elif p["date"] < last:
                out.append(f"{i}: points are not in date order ({p['date']} after {last})")
            else:
                last = p["date"]
            if not isinstance(p.get("value"), (int, float)):
                out.append(f"{i}: point on {p.get('date')} has no number")
            if not p.get("source"):
                out.append(f"{i}: point on {p.get('date')} has no source")
        t = m.get("target")
        free = m.get("context") or m.get("info")
        if free and t:
            out.append(f"{i}: context and info measures have no target")
        if not free and not t:
            out.append(f"{i}: no target (mark it context or info if it should have none)")
        if t:
            if t.get("by") != "last-target" and not ISO.match(str(t.get("by", ""))):
                out.append(f"{i}: target.by {t.get('by')} is not YYYY-MM-DD")
            if t.get("step") and t["step"] not in book.steps:
                out.append(f"{i}: target step {t['step']} is not in the roadmap")
        try:
            book.points(m)
        except (ValueError, KeyError, TypeError) as e:
            out.append(f"{i}: computed points failed: {e}")
    sids = set()
    for g in book.roadmap["goals"]:
        for k in ("id", "title", "intro", "today", "steps"):
            if not g.get(k):
                out.append(f"goal {g.get('id', '?')}: no {k}")
        if not g.get("headline"):
            out.append(f"goal {g.get('id', '?')}: no headline measure")
        for h in g.get("headline", []):
            if h not in book.by_id:
                out.append(f"goal {g.get('id', '?')}: headline {h} is not in coverage/measures.json")
        for s in g.get("steps", []):
            sid = s.get("id", "?")
            if sid in sids:
                out.append(f"step {sid}: id used twice")
            sids.add(sid)
            if s.get("status") not in STATUSES:
                out.append(f"step {sid}: status {s.get('status')} is not one of {STATUSES}")
            for k in ("title", "text", "start", "part"):
                if not s.get(k):
                    out.append(f"step {sid}: no {k}")
            for k in ("start", "target", "done"):
                if s.get(k) and not ISO.match(s[k]):
                    out.append(f"step {sid}: {k} {s[k]} is not YYYY-MM-DD")
            if s.get("status") == "done" and not s.get("done"):
                out.append(f"step {sid}: done but no done date")
            if s.get("status") != "done" and s.get("done"):
                out.append(f"step {sid}: has a done date but is {s.get('status')}")
            if s.get("status") != "done" and not s.get("target"):
                out.append(f"step {sid}: {s.get('status')} with no target date")
            if s.get("target") and s.get("start") and s["target"] < s["start"]:
                out.append(f"step {sid}: target {s['target']} is before start {s['start']}")
            for mid in s.get("measures", []):
                if mid not in book.by_id:
                    out.append(f"step {sid}: measure {mid} is not in coverage/measures.json")
    return out


# ---- CLI ----------------------------------------------------------------------------------------
def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    t = sub.add_parser("table", help="Markdown table of measures")
    t.add_argument("ids", nargs="*")
    t.add_argument("--area")
    t.add_argument("--step")
    a = sub.add_parser("add", help="record a point")
    a.add_argument("id")
    a.add_argument("value", type=float)
    a.add_argument("--date", default=date.today().isoformat())
    a.add_argument("--n", type=int)
    a.add_argument("--note")
    a.add_argument("--source", required=True, help="where the receipt is: a file, a log, a PR")
    sub.add_parser("check", help="validate coverage/measures.json and coverage/roadmap.json")
    args = ap.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    book = Book()
    if args.cmd == "table":
        ms = [book.by_id[i] for i in args.ids] if args.ids else list(book.data["measures"])
        if args.area:
            ms = [m for m in ms if m["area"] == args.area]
        if args.step:
            ids = book.steps[args.step].get("measures", [])
            ms = [m for m in ms if m["id"] in ids or (m.get("target") or {}).get("step") == args.step]
        print(table(book, ms))
        return 0
    if args.cmd == "add":
        m = book.by_id.get(args.id)
        if not m:
            print(f"No measure {args.id}. Add its entry to coverage/measures.json first.", file=sys.stderr)
            return 1
        if m.get("computed"):
            print(f"{args.id} is computed from {m['computed']}; record the run there instead.", file=sys.stderr)
            return 1
        p = {"date": args.date, "value": int(args.value) if args.value.is_integer() and m["unit"] != "s" else args.value}
        if args.n:
            p["n"] = args.n
        if args.note:
            p["note"] = args.note
        p["source"] = args.source
        m.setdefault("points", []).append(p)
        m["points"].sort(key=lambda x: x["date"])
        MEASURES.write_text(dump(book.data), encoding="utf-8", newline="\n")
        fresh = Book()
        import bench_page  # ROADMAP.md shows every number, so it follows the new point
        bench_page.ROADMAP_MD.write_text(bench_page.roadmap_md(fresh), encoding="utf-8", newline="\n")
        print(table(fresh, [fresh.by_id[args.id]]))
        return 0
    found = problems(book)
    for p in found:
        print(p)
    print(f"{len(book.data['measures'])} measures, {len(book.steps)} steps: {'OK' if not found else str(len(found)) + ' problem(s)'}")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
