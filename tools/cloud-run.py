#!/usr/bin/env python3
"""Run a full sweep (every pack, the whole port corpus, wide A/B timing) on GitHub's runners, wait, and download it.

    python tools/cloud-run.py packs [--ref BRANCH] [-f packs=atm10-neoforge-1.21.1] [-f focus=MODID] [-f fix=true]
    python tools/cloud-run.py ports [-f cases=jade-neoforge-1.21.1-1.21.6]
    python tools/cloud-run.py perf  [-f patch=bench/diorama/column-reuse.patch] [-f runs=4]
    python tools/cloud-run.py --run RUN_ID        wait for and download a run already started
    tools/cloud-run ... (POSIX) and tools\\cloud-run.cmd ... (Windows) are the same

packs is .github/workflows/cloud-packs.yml (pack-check, one job per pack), ports is cloud-ports.yml (the port board,
one job per case), perf is cloud-perf.yml (perf-optimize, baseline and candidate in turn on one runner); any other
name is taken as a workflow file. The workflow runs the commit the branch (--ref, default the current one) has on
GitHub, so push first: a branch whose local commit is not on origin is refused (--allow-unpushed runs origin's).

It dispatches with a request id the workflow puts in its run name, finds that run, waits with a hard --timeout
(minutes, default 120; the run is cancelled then, unless --keep-running), prints each job's result and time, and
downloads every artifact to .runs/cloud/WORKFLOW-RUNID/ (--out). A summary artifact's summary.md is printed.
Exit 0 when the run succeeded, 1 when it failed, 124 on the timeout. Needs the GitHub CLI (gh), logged in.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORKFLOWS = {"packs": "cloud-packs.yml", "ports": "cloud-ports.yml", "perf": "cloud-perf.yml"}


def gh_path():
    found = os.environ.get("GH") or shutil.which("gh")
    if found:
        return found
    for candidate in (r"C:\Program Files\GitHub CLI\gh.exe", "/usr/local/bin/gh", "/opt/homebrew/bin/gh"):
        if Path(candidate).is_file():
            return candidate
    raise SystemExit("cloud-run needs the GitHub CLI (gh): https://cli.github.com, then gh auth login")


def gh(*args, check=True):
    done = subprocess.run([gh_path(), *args], cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and done.returncode:
        raise SystemExit(f"gh {' '.join(args[:3])} failed: {(done.stderr or done.stdout).strip()[-800:]}")
    return done.stdout


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True).stdout.strip()


def ref_on_origin(ref, allow_unpushed):
    """The commit the workflow will run; refuses a branch whose local commit GitHub does not have."""
    remote = git("ls-remote", "origin", f"refs/heads/{ref}").split()
    if not remote:
        raise SystemExit(f"origin has no branch {ref}: push it first (git push -u origin {ref})")
    local = git("rev-parse", "HEAD") if git("rev-parse", "--abbrev-ref", "HEAD") == ref else remote[0]
    if local != remote[0] and not allow_unpushed:
        raise SystemExit(f"{ref} is {local[:10]} here but {remote[0][:10]} on origin: push first, or pass --allow-unpushed to run origin's")
    return remote[0]


def find_run(workflow, request, started, timeout=180):
    """The run whose name carries this request id; GitHub lists a dispatched run a few seconds after the dispatch."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        runs = json.loads(gh("run", "list", "--workflow", workflow, "--event", "workflow_dispatch", "--limit", "30",
                             "--json", "databaseId,displayTitle,createdAt,url"))
        match = pick(runs, request)
        if match:
            return match
        time.sleep(5)
    raise SystemExit(f"dispatched {workflow} at {started}, but no run named with request {request} appeared in {timeout} s")


def pick(runs, request):
    """The listed run whose title ends with the request id, or None."""
    return next((r for r in runs if r.get("displayTitle", "").strip().endswith(request)), None)


def seconds(start, end):
    if not start or not end or start.startswith("0001"):
        return None
    from datetime import datetime
    parse = lambda t: datetime.fromisoformat(t.replace("Z", "+00:00"))  # noqa: E731
    return round((parse(end) - parse(start)).total_seconds())


def duration(s):
    return "—" if s is None else f"{s // 60}m{s % 60:02d}s"


def wait(run_id, timeout_minutes, keep_running):
    """Polls until the run completes or the hard timeout; prints each job once it finishes."""
    deadline = time.monotonic() + timeout_minutes * 60
    seen = set()
    while True:
        view = json.loads(gh("run", "view", str(run_id), "--json", "status,conclusion,jobs,url,createdAt,updatedAt,name,workflowName"))
        for job in view.get("jobs", []):
            if job.get("status") == "completed" and job["name"] not in seen:
                seen.add(job["name"])
                print(f"  {job.get('conclusion'):<9} {duration(seconds(job.get('startedAt'), job.get('completedAt'))):>8}  {job['name']}", flush=True)
        if view.get("status") == "completed":
            return view
        if time.monotonic() > deadline:
            if not keep_running:
                gh("run", "cancel", str(run_id), check=False)
            print(f"timed out after {timeout_minutes} min" + ("" if keep_running else "; the run was cancelled"), flush=True)
            view["conclusion"] = "timed out"
            return view
        time.sleep(30)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("workflow", nargs="?", help="packs, ports, perf, or a workflow file name")
    ap.add_argument("-f", "--field", action="append", default=[], metavar="KEY=VALUE", help="a workflow input; repeat for more")
    ap.add_argument("--ref", help="the branch to run (default: the current one)")
    ap.add_argument("--run", type=int, help="wait for and download this run instead of starting one")
    ap.add_argument("--timeout", type=float, default=120, help="minutes to wait before cancelling (default 120)")
    ap.add_argument("--keep-running", action="store_true", help="on the timeout, leave the run going")
    ap.add_argument("--no-wait", action="store_true", help="dispatch, print the run and return")
    ap.add_argument("--allow-unpushed", action="store_true", help="run origin's commit of the branch even when this one differs")
    ap.add_argument("--out", type=Path, help="where artifacts go (default .runs/cloud/WORKFLOW-RUNID)")
    args = ap.parse_args(argv)
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    if not args.run and not args.workflow:
        ap.error("name a workflow (packs, ports, perf) or --run RUN_ID")
    started = time.monotonic()
    if args.run:
        run_id = args.run
        workflow = json.loads(gh("run", "view", str(run_id), "--json", "workflowName"))["workflowName"]
    else:
        workflow = WORKFLOWS.get(args.workflow, args.workflow)
        ref = args.ref or git("rev-parse", "--abbrev-ref", "HEAD")
        commit = ref_on_origin(ref, args.allow_unpushed)
        request = "req-" + uuid.uuid4().hex[:12]
        fields = [x for f in args.field for x in ("-f", f)]
        gh("workflow", "run", workflow, "--ref", ref, *fields, "-f", f"request={request}")
        run = find_run(workflow, request, time.strftime("%H:%M:%S"))
        run_id = run["databaseId"]
        print(f"{workflow} on {ref} ({commit[:10]}): {run['url']}", flush=True)
        if args.no_wait:
            return 0
    view = wait(run_id, args.timeout, args.keep_running)
    name = Path(str(args.workflow or view.get("workflowName") or workflow)).stem.replace(" ", "-").lower()
    out = args.out or ROOT / ".runs" / "cloud" / f"{name}-{run_id}"
    out.mkdir(parents=True, exist_ok=True)
    gh("run", "download", str(run_id), "--dir", str(out), check=False)
    (out / "run.json").write_text(json.dumps(view, indent=1), encoding="utf-8")
    summary = next(iter(sorted(out.glob("summary/summary.md"))), None)
    if summary:
        print(summary.read_text(encoding="utf-8"))
    wall = seconds(view.get("createdAt"), view.get("updatedAt"))
    print(f"{view.get('conclusion')}: {view.get('url')} in {duration(wall)} (waited {duration(round(time.monotonic() - started))}); artifacts in {out}")
    return 0 if view.get("conclusion") == "success" else 124 if view.get("conclusion") == "timed out" else 1


if __name__ == "__main__":
    sys.exit(main())
