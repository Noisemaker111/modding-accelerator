"""DIAG ONLY (branch diag/mac-perf-flake): hijacked to loop the perf suite on stonecutter-nodes runners."""
import json, os, platform, subprocess, sys

VARIANTS = os.environ.get("DIAG_VARIANTS_JSON")
TARGETS = [{"mod": "diag", "node": n} for n in
           ["yield-a", "yield-b"]]

if "--matrix" in sys.argv:
    print(json.dumps(TARGETS))
    sys.exit(0)
node = os.environ.get("NODE", "")
system = platform.system()
if system == "Windows" or (system == "Linux" and not node.endswith("-a")):
    print("diag: skipped on", system, node)
    sys.exit(0)
if node.startswith("yield"):
    sys.exit(subprocess.call(["bash", "diag/yield.sh"]))
sys.exit(subprocess.call(["bash", "diag/loop.sh", node.split("-")[0], node.split("-")[1], "22"]))
