"""Which CI plans a change can reach: core, server, stonecutter.

    python tools/ci/reach.py BASE HEAD      # prints core=true/false etc. for $GITHUB_OUTPUT
    python tools/ci/reach.py --files a b c  # the same for a list of paths

A path skips a plan only when this file says that plan cannot read it; anything not named here reaches every
plan. So a new folder runs everything until someone proves it reaches less. Selection only narrows pull request
feedback: main and the merge queue always run every plan.
"""
import subprocess, sys
from fnmatch import fnmatch

PLANS = ("core", "server", "stonecutter")

# Read by no plan. Generated pages (written on main from coverage/), prose, the desktop app (no CI builds it, and the
# package takes only a built binary), and the other workflows. PERFORMANCE.md is not here: the package carries it.
NOTHING = [
    "docs/*", "ROADMAP.md", "README.md", "AGENTS.md", "CLAUDE.md", "SCOPE.md", "KNOWN_ISSUES.md",
    "app/*", "skills/*.md", ".github/ISSUE_TEMPLATE/*", ".github/pull_request_template.md",
    ".github/workflows/generated.yml", ".github/workflows/release.yml", ".github/workflows/stonecutter-nodes.yml",
    ".git-blame-ignore-revs",
]

# Read only by the core plan: the bench scripts and their data, and the Prism Lab fixture the core runs `fast` on.
CORE_ONLY = [
    "bench/*", "coverage/*", "src/*", "versions/*", "scenarios/*", "build.gradle", "settings.gradle", "gradle.properties",
]

# Within CORE_ONLY, these are also read by another plan (first match wins).
ALSO = [
    ("bench/server-plugins/*", {"core", "server"}),
    ("bench/real-mods/*", {"core", "stonecutter"}),
    ("coverage/changes/*", set(PLANS)),  # packaged: coverage/changes/index.json
]


def reach(path):
    for pattern, plans in ALSO:
        if fnmatch(path, pattern):
            return plans
    if any(fnmatch(path, p) for p in NOTHING):
        return set()
    if any(fnmatch(path, p) for p in CORE_ONLY):
        return {"core"}
    return set(PLANS)


def select(paths):
    chosen = set()
    for path in paths:
        chosen |= reach(path)
    return chosen


def changed(base, head):
    out = subprocess.run(["git", "diff", "--name-only", "--no-renames", f"{base}...{head}"], capture_output=True, text=True)
    if out.returncode != 0:
        # No merge base (or a history too shallow for one): select everything rather than guess.
        print(out.stderr, file=sys.stderr)
        return None
    return [line for line in out.stdout.splitlines() if line]


def main(argv):
    if argv[:1] == ["--files"]:
        paths = argv[1:]
    else:
        paths = changed(argv[0], argv[1])
    chosen = set(PLANS) if paths is None else select(paths)
    for plan in PLANS:
        print(f"{plan}={'true' if plan in chosen else 'false'}")
    if paths is not None:
        for path in paths:
            print(f"  {path}: {', '.join(sorted(reach(path))) or 'nothing'}", file=sys.stderr)


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")
    main(sys.argv[1:])
