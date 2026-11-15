"""tools/ci/reach.py: what each path reaches. Run: python tools/ci/test_select.py"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from reach import reach, select, PLANS  # noqa: E402

ALL = set(PLANS)
cases = {
    "docs/index.html": set(), "README.md": set(), "KNOWN_ISSUES.md": set(), "app/src/main.rs": set(), "skills/modding-accelerator/SKILL.md": set(),
    "PERFORMANCE.md": ALL,  # packaged
    "LICENSE": ALL, "NOTICE": ALL,  # packaged
    "tools/cli/Modaccel.java": ALL, "tools/packcheck/CONTRIBUTING.md": ALL, "knowledge/fixes.json": ALL,
    "coverage/ledger.json": {"core"}, "coverage/changes/index.json": ALL,
    "bench/test_record.py": {"core"}, "bench/server-plugins/manifest.json": {"core", "server"},
    "bench/real-mods/manifest.json": {"core", "stonecutter"},
    "src/main/java/X.java": {"core"}, "gradle/wrapper/gradle-wrapper.properties": ALL,
    ".github/workflows/portable.yml": ALL, "tools/ci/lanes.py": ALL, "something/new.txt": ALL,
}
bad = [f"{p}: {sorted(reach(p))} != {sorted(want)}" for p, want in cases.items() if reach(p) != want]
if select(["README.md", "docs/a.html"]) != set():
    bad.append("a docs-only change selects nothing")
if select(["README.md", "tools/cli/Run.java"]) != ALL:
    bad.append("one reaching file selects its plans")
print("\n".join(bad) or f"{len(cases)} selection cases pass")
sys.exit(1 if bad else 0)
