"""DIAG ONLY (branch diag/mac-perf-flake): hijacked to loop the perf suite on stonecutter-nodes runners."""
import json, os, platform, subprocess, sys

VARIANTS = os.environ.get("DIAG_VARIANTS_JSON")
TARGETS = [{"mod": "diag", "node": n} for n in
           ["17-a", "17-b", "17-c", "17-d", "25-a", "25-b", "25-c", "21-a", "21-b"]]

if "--matrix" in sys.argv:
    print(json.dumps(TARGETS))
    sys.exit(0)
node = os.environ.get("NODE", "")
system = platform.system()
if system == "Windows" or (system == "Linux" and not node.startswith("17-")):
    print("diag: skipped on", system, node)
    sys.exit(0)
sys.exit(subprocess.call(["bash", "diag/loop.sh", node.split("-")[0], "22"]))
