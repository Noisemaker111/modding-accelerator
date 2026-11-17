"""Print where the work stands, from the files that outlive a conversation: the roadmap's latest decision for each goal
with steps in progress, those steps, and the newest point of each headline number. A Claude Code SessionStart hook
runs it (.claude/settings.json), so a new, resumed or summarized session starts from the files, not from memory.

    python bench/session_state.py
"""

import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent


def main():
    try:
        roadmap = json.loads((ROOT / "coverage/roadmap.json").read_text(encoding="utf-8"))
        measures = json.loads((ROOT / "coverage/measures.json").read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        print(f"session state unavailable: {e}")
        return
    by_id = {m["id"]: m for m in measures.get("measures", [])}
    out = ["Where the work stands (coverage/roadmap.json, coverage/measures.json). Work on a 'doing' step; if an",
           "instruction pulls against the latest decision, say so to Jon before following either.", ""]
    decision = (roadmap.get("decisions") or [None])[-1]
    if decision:
        out.append(f"Latest decision ({decision.get('date', '?')}): {decision.get('question', '')}")
        out.append(f"  {decision.get('choice', '')}")
        out.append("")
    for goal in roadmap.get("goals", []):
        active = [s for s in goal.get("steps", []) if s.get("status") in ("doing", "next")]
        if not active:
            continue
        out.append(f"Goal {goal['id']}: {goal['title']}")
        for s in active:
            out.append(f"  [{s['status']}] step {s['id']}: {s['title']}")
        for mid in goal.get("headline", []):
            m = by_id.get(mid)
            points = (m or {}).get("points") or []
            if points:
                p = points[-1]
                out.append(f"  number {mid}: {p.get('value')} ({p.get('date', '?')}, {p.get('source', '')[:90]})")
        out.append("")
    corpus = by_id.get("port.corpus-builds")
    if corpus and corpus.get("points"):
        p = corpus["points"][-1]
        out.append(f"Porting, whole corpus: {p.get('value')} of {p.get('n', '?')} ports build ({p.get('date')}).")
    sys.stdout.write("\n".join(out).rstrip() + "\n")


if __name__ == "__main__":
    main()
