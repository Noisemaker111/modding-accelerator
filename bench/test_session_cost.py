"""bench/session_cost.py reads processes the same way on Windows, Linux and macOS: a Java process that
starts while it samples is seen, with memory and CPU, and one that was running before is not counted.
Each check is shown failing once, so a pass means the reader saw something.

    python bench/test_session_cost.py
"""
import json
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import session_cost as C  # noqa: E402

checks = 0


def check(ok, label):
    global checks
    if not ok:
        raise AssertionError(label)
    checks += 1


def busy_java(seconds):
    """A Java process that allocates and spins for a while, so it has memory and CPU to read."""
    src = Path(tempfile.mkdtemp()) / "Busy.java"
    src.write_text("public class Busy { public static void main(String[] a) { long end = System.nanoTime() + %d_000_000_000L; "
                   "java.util.List<byte[]> keep = new java.util.ArrayList<>(); long x = 0; "
                   "while (System.nanoTime() < end) { x += keep.size(); if (keep.size() < 64) keep.add(new byte[1 << 20]); } "
                   "System.out.println(x); } }" % seconds)
    return subprocess.Popen(["java", str(src)], stdout=subprocess.DEVNULL)


# a Java process that was already running is not counted
before = busy_java(6)
time.sleep(2)
with C.Sampler() as s:
    time.sleep(1.5)
check(before.pid not in s.seen, "a process running before sampling began must not be counted")

# one that starts while sampling is seen, with memory and CPU
with C.Sampler() as s:
    child = busy_java(3)
    child.wait()
before.wait()
mine = [p for pid, p in s.seen.items() if pid == child.pid or p["ppid"] == child.pid]
check(mine, f"the Java process started while sampling was not seen; saw {json.dumps(s.seen)}")
peak = max(p["peak"] for p in mine)
check(peak > 64 * 2**20, f"its peak memory should be above 64 MB, read {peak}")
check(max(p["cpu"] for p in mine) > 0.5, f"its CPU time should be above 0.5 s, read {[p['cpu'] for p in mine]}")

game = max((pid for pid, p in s.seen.items() if p in mine), key=lambda pid: s.seen[pid]["peak"])
split = C.split(s, game=game)
check(split["game"] and split["game"]["peakGB"] > 0, "split must report the named process as the game")
check(split["other"]["count"] == len(s.seen) - 1, "every other new process counts as other")

# machine load is a percentage where the system keeps one (Windows, Linux)
busy = s.busy
check(busy is None and sys.platform == "darwin" or busy is not None and 0 <= busy <= 100, f"machine busy should be 0-100, read {busy}")

# windows on screen: a dict on Windows (none for a console Java), None (unknown) elsewhere
shown = C.visible_windows([child.pid])
check(shown == {} if C.WINDOWS else shown is None, f"visible windows of a console process: {shown}")

# the result kept as a source has no local paths
kept = C.clean({"root": "C:\\Users\\x\\p", "a": {"capture": "C:\\Users\\x\\p\\shot.png"}, "b": "/Users/x/p/log.txt", "n": 3})
check(kept == {"a": {"capture": "shot.png"}, "b": "log.txt", "n": 3}, f"clean() must drop local paths, got {kept}")

print(f"{checks} session cost checks passed")
