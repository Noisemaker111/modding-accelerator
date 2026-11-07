"""What one game session costs: memory, CPU and time for a real client, dedicated server or any modaccel
command, measured from the operating system while the command runs. Step 33's baseline, and the meter
later steps are judged with.

    python bench/session_cost.py client --root PROJECT [--runs 3] [--idle 20] [--record]
    python bench/session_cost.py parallel --root PROJECT --count 8 [--idle 20] [--jvm FLAGS] [--record]
    python bench/session_cost.py warm --root PROJECT --world SAVE [--runs 5] [--record]
    python bench/session_cost.py projects --root PROJECT --count 8 [--record]
    python bench/session_cost.py run --label NAME -- COMMAND...

client: stops any game for PROJECT and its Gradle daemons, then per run: ui-check from cold (no game, no
daemon), the hidden game left idle for --idle seconds, ui-check again warm, game-stop and ui-check from the
saved launch (the fast path every later start takes), and mp-check (a dedicated server started for it).
After each start it counts the game's windows that are visible on screen (Windows; unknown elsewhere). Every Java process that appears while a command runs is sampled twice a second:
the game (the pid in .modaccel/portable/agent.json), the dedicated server (the pid mp-check reports) and
the rest (Gradle daemons and tooling). Memory is the working set (resident set on Linux and macOS), with
the operating system's own peak where it keeps one. GPU memory is not included.

warm: the checks a loaded hidden game answers, each --runs times: a screen opened and captured at 1280x720
(ui-check --screen), a save loaded and captured (world-check --world), and a scenario: a scripted step in that
world (eval) then the frame captured (ui-check). Each
check's time and the game's windows on screen after it. Run it with modaccel preferences --background on.

projects: COUNT projects checked at the same time, as when many mods are worked on at once. Each is a copy of
--root's mod (src, Gradle files) in .runs/session-cost/projects/, prepared one after another (Gradle records its
launch, then nothing is kept). Then every project's data-check runs at once from no game (each starts its own),
then, once their spare games are ready, at once again with their games loaded. Reports each check's wall time,
the CPU of everything that started, and the memory of every loaded and spare game together.

parallel: starts COUNT hidden clients at once from the project's verified saved launch
(.modaccel/portable/launch.json, written by a ui-check), each with its own game folder, state folder and
token under .modaccel/sessions/, drives each through the real agent to a 1280x720 framebuffer capture,
measures them idle together, then quits them. It answers how many sessions this machine holds.

Results go to .runs/session-cost/<time>.json. --record keeps a copy without local paths in
bench/session-cost/ and adds the medians to coverage/measures.json with that copy as the source.
No packages needed: Windows is read through kernel32, Linux and macOS through ps and /proc.
"""
import argparse
import shutil
import socket
import uuid
import ctypes
import json
import os
import statistics
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

HARNESS = Path(__file__).resolve().parent.parent
WINDOWS = os.name == "nt"
JAVA = {"java.exe", "javaw.exe", "java"}


# ---- reading processes -------------------------------------------------------------------------

if WINDOWS:
    from ctypes import wintypes

    class PROCESSENTRY32W(ctypes.Structure):
        _fields_ = [("dwSize", wintypes.DWORD), ("cntUsage", wintypes.DWORD), ("th32ProcessID", wintypes.DWORD),
                    ("th32DefaultHeapID", ctypes.c_size_t), ("th32ModuleID", wintypes.DWORD), ("cntThreads", wintypes.DWORD),
                    ("th32ParentProcessID", wintypes.DWORD), ("pcPriClassBase", ctypes.c_long), ("dwFlags", wintypes.DWORD),
                    ("szExeFile", ctypes.c_wchar * 260)]

    class MEMORY(ctypes.Structure):
        _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD), ("PeakWorkingSetSize", ctypes.c_size_t),
                    ("WorkingSetSize", ctypes.c_size_t), ("QuotaPeakPagedPoolUsage", ctypes.c_size_t), ("QuotaPagedPoolUsage", ctypes.c_size_t),
                    ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t), ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                    ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t), ("PrivateUsage", ctypes.c_size_t)]

    K32 = ctypes.WinDLL("kernel32", use_last_error=True)
    K32.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
    K32.OpenProcess.restype = wintypes.HANDLE
    K32.Process32FirstW.argtypes = K32.Process32NextW.argtypes = [wintypes.HANDLE, ctypes.POINTER(PROCESSENTRY32W)]
    K32.K32GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(MEMORY), wintypes.DWORD]
    K32.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
    K32.CloseHandle.argtypes = [wintypes.HANDLE]

    def _ft(f):
        return (f.dwHighDateTime << 32 | f.dwLowDateTime) / 1e7

    def processes():
        """{pid: {name, ppid, threads, rss, peak, private, cpu}} for every Java process."""
        snap = K32.CreateToolhelp32Snapshot(2, 0)
        entry = PROCESSENTRY32W(); entry.dwSize = ctypes.sizeof(entry)
        found = {}
        ok = K32.Process32FirstW(snap, ctypes.byref(entry))
        while ok:
            if entry.szExeFile.lower() in JAVA:
                found[entry.th32ProcessID] = {"name": entry.szExeFile, "ppid": entry.th32ParentProcessID, "threads": entry.cntThreads}
            ok = K32.Process32NextW(snap, ctypes.byref(entry))
        K32.CloseHandle(snap)
        for pid, p in list(found.items()):
            h = K32.OpenProcess(0x1000 | 0x0010, False, pid)
            if not h:
                del found[pid]
                continue
            m = MEMORY(); m.cb = ctypes.sizeof(m)
            K32.K32GetProcessMemoryInfo(h, ctypes.byref(m), m.cb)
            t = [wintypes.FILETIME() for _ in range(4)]
            K32.GetProcessTimes(h, *[ctypes.byref(x) for x in t])
            K32.CloseHandle(h)
            p.update(rss=m.WorkingSetSize, peak=m.PeakWorkingSetSize, private=m.PrivateUsage, cpu=_ft(t[2]) + _ft(t[3]))
        return found

    K32.GetSystemTimes.argtypes = [ctypes.POINTER(wintypes.FILETIME)] * 3

    class THREADENTRY32(ctypes.Structure):
        _fields_ = [("dwSize", wintypes.DWORD), ("cntUsage", wintypes.DWORD), ("th32ThreadID", wintypes.DWORD),
                    ("th32OwnerProcessID", wintypes.DWORD), ("tpBasePri", ctypes.c_long), ("tpDeltaPri", ctypes.c_long),
                    ("dwFlags", wintypes.DWORD)]

    K32.Thread32First.argtypes = K32.Thread32Next.argtypes = [wintypes.HANDLE, ctypes.POINTER(THREADENTRY32)]
    K32.OpenThread.restype = wintypes.HANDLE
    K32.GetThreadTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
    K32.GetThreadDescription.argtypes = [wintypes.HANDLE, ctypes.POINTER(ctypes.c_wchar_p)]
    K32.LocalFree.argtypes = [ctypes.c_void_p]

    def threads(pid):
        """[(name, cpu seconds)] for every live thread of a process, JVM-internal and native ones included."""
        snap = K32.CreateToolhelp32Snapshot(4, 0)
        entry = THREADENTRY32(); entry.dwSize = ctypes.sizeof(entry)
        out, ok = [], K32.Thread32First(snap, ctypes.byref(entry))
        while ok:
            if entry.th32OwnerProcessID == pid:
                h = K32.OpenThread(0x0800, False, entry.th32ThreadID)  # THREAD_QUERY_LIMITED_INFORMATION
                if h:
                    t = [wintypes.FILETIME() for _ in range(4)]
                    K32.GetThreadTimes(h, *[ctypes.byref(x) for x in t])
                    desc = ctypes.c_wchar_p()
                    name = ""
                    if K32.GetThreadDescription(h, ctypes.byref(desc)) >= 0 and desc.value:
                        name = desc.value
                        K32.LocalFree(desc)
                    K32.CloseHandle(h)
                    out.append((name or "(unnamed native)", _ft(t[2]) + _ft(t[3])))
            ok = K32.Thread32Next(snap, ctypes.byref(entry))
        K32.CloseHandle(snap)
        return out

    U32 = ctypes.WinDLL("user32", use_last_error=True)
    _ENUM = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    U32.EnumWindows.argtypes = [_ENUM, wintypes.LPARAM]
    U32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
    U32.IsWindowVisible.argtypes = [wintypes.HWND]
    U32.GetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]

    def visible_windows(pids):
        """{pid: [titles]} of visible top-level windows owned by these processes: what is on screen."""
        pids, found = set(pids), {}

        def each(hwnd, _):
            owner = wintypes.DWORD()
            U32.GetWindowThreadProcessId(hwnd, ctypes.byref(owner))
            if owner.value in pids and U32.IsWindowVisible(hwnd):
                title = ctypes.create_unicode_buffer(256)
                U32.GetWindowTextW(hwnd, title, 256)
                found.setdefault(owner.value, []).append(title.value)
            return True
        U32.EnumWindows(_ENUM(each), 0)
        return found

    def machine_times():
        """(busy, total) CPU seconds summed over every core since boot."""
        idle_t, kern, user = (wintypes.FILETIME() for _ in range(3))
        K32.GetSystemTimes(ctypes.byref(idle_t), ctypes.byref(kern), ctypes.byref(user))
        total = _ft(kern) + _ft(user)  # kernel time includes idle time
        return total - _ft(idle_t), total
else:
    def _secs(text):
        days, _, rest = text.rpartition("-")
        parts = [float(x) for x in rest.split(":")]
        while len(parts) < 3:
            parts.insert(0, 0.0)
        return (int(days) if days else 0) * 86400 + parts[0] * 3600 + parts[1] * 60 + parts[2]

    def processes():
        out = subprocess.run(["ps", "-A", "-o", "pid=,ppid=,rss=,time=,comm="], capture_output=True, text=True).stdout
        found = {}
        for line in out.splitlines():
            bits = line.split(None, 4)
            if len(bits) < 5 or os.path.basename(bits[4].strip()) not in JAVA:
                continue
            pid = int(bits[0])
            p = {"name": bits[4].strip(), "ppid": int(bits[1]), "rss": int(bits[2]) * 1024, "cpu": _secs(bits[3]), "peak": 0, "private": 0, "threads": 0}
            try:  # Linux keeps the high-water mark itself
                for row in Path(f"/proc/{pid}/status").read_text().splitlines():
                    if row.startswith("VmHWM:"):
                        p["peak"] = int(row.split()[1]) * 1024
                    elif row.startswith("Threads:"):
                        p["threads"] = int(row.split()[1])
            except OSError:
                pass
            found[pid] = p
        return found

    def machine_times():
        try:
            fields = [float(x) for x in Path("/proc/stat").read_text().splitlines()[0].split()[1:]]
            return sum(fields) - fields[3] - fields[4], sum(fields)
        except OSError:
            return None


if not WINDOWS:
    def threads(pid):
        out = []
        for task in Path(f"/proc/{pid}/task").glob("*"):
            try:
                stat = (task / "stat").read_text()
                fields = stat[stat.rindex(")") + 2:].split()
                tick = os.sysconf("SC_CLK_TCK")
                out.append(((task / "comm").read_text().strip(), (int(fields[11]) + int(fields[12])) / tick))
            except (OSError, ValueError):
                pass
        return out

    def visible_windows(pids):
        """Not read on Linux and macOS yet; None means unknown, not hidden."""
        return None


def machine_busy(before, after):
    """Percent of the whole machine's CPU that was busy between two machine_times() readings."""
    if not before or not after or after[1] <= before[1]:
        return None
    return round(100 * (after[0] - before[0]) / (after[1] - before[1]), 1)


class Sampler:
    """Samples every Java process that was not running when it started, until stopped."""

    def __init__(self, every=0.5):
        self.before = set(processes())
        self.every, self.seen, self.stop_flag = every, {}, threading.Event()
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.machine = machine_times()
        self.busy = None

    def _loop(self):
        while not self.stop_flag.is_set():
            self.sample()
            self.stop_flag.wait(self.every)

    def sample(self):
        now = processes()
        for pid, p in now.items():
            if pid in self.before:
                continue
            s = self.seen.setdefault(pid, {"name": p["name"], "ppid": p["ppid"], "peak": 0, "private": 0, "threads": 0, "cpu": 0.0, "rss": 0})
            s["peak"] = max(s["peak"], p["rss"], p.get("peak") or 0)
            s["private"] = max(s["private"], p.get("private") or 0)
            s["threads"] = max(s["threads"], p.get("threads") or 0)
            s["cpu"], s["rss"] = p["cpu"], p["rss"]
        return now

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.stop_flag.set()
        self.thread.join()
        self.sample()
        self.busy = machine_busy(self.machine, machine_times())


# ---- running modaccel --------------------------------------------------------------------------

def modaccel(args, root):
    exe = [str(HARNESS / "modaccel.cmd")] if WINDOWS else [str(HARNESS / "modaccel")]
    start = time.perf_counter()
    done = subprocess.run(exe + args + ["--root", str(root), "--json"], capture_output=True, text=True, encoding="utf-8", errors="replace")
    wall = time.perf_counter() - start
    try:
        report = json.loads(done.stdout.strip().splitlines()[-1])
    except (ValueError, IndexError):
        report = {"ok": False, "error": (done.stdout + done.stderr)[-2000:]}
    report["_wall"] = wall
    return report


def stage(report, name):
    return next((s for s in report.get("stages", []) if s.get("name") == name), {})


def game_pid(root):
    f = Path(root) / ".modaccel" / "portable" / "agent.json"
    try:
        return json.loads(f.read_text(encoding="utf-8"))["pid"]
    except (OSError, ValueError, KeyError):
        return None


def gb(n):
    return round(n / 2**30, 2)


def split(sampler, game=None, server=None):
    """Peak memory and CPU of the game, the server and everything else that appeared."""
    out = {"game": None, "server": None, "other": {"count": 0, "peakGB": 0.0, "cpu": 0.0}, "machineBusyPercent": sampler.busy}
    for pid, s in sampler.seen.items():
        row = {"pid": pid, "peakGB": gb(s["peak"]), "privateGB": gb(s["private"]), "threads": s["threads"], "cpu": round(s["cpu"], 1)}
        if pid == game:
            out["game"] = row
        elif pid == server:
            out["server"] = row
        else:
            o = out["other"]
            o["count"] += 1
            o["peakGB"] = round(o["peakGB"] + gb(s["peak"]), 2)
            o["cpu"] = round(o["cpu"] + s["cpu"], 1)
    return out


def idle(pid, seconds):
    """CPU the hidden game uses while nobody asks it anything: share of one core, and of the machine."""
    first, m0 = processes().get(pid), machine_times()
    start = time.perf_counter()
    time.sleep(seconds)
    last, m1 = processes().get(pid), machine_times()
    if not first or not last:
        return None
    used = (last["cpu"] - first["cpu"]) / (time.perf_counter() - start)
    return {"seconds": seconds, "cores": round(used, 2), "machinePercent": round(100 * used / os.cpu_count(), 1), "rssGB": gb(last["rss"]), "machineBusyPercent": machine_busy(m0, m1)}


def need(report, what):
    if not report.get("ok"):
        raise SystemExit(f"{what} failed: {report.get('error') or json.dumps(report.get('result'))[:1500]}")


def client(args):
    root = Path(args.root).resolve()
    runs = []
    for n in range(args.runs):
        print(f"run {n + 1}/{args.runs}: stopping any game and its Gradle daemons", flush=True)
        modaccel(["game-stop"], root)
        time.sleep(3)
        with Sampler() as s:
            cold = modaccel(["ui-check"], root)
        need(cold, "cold ui-check")
        pid = game_pid(root)
        shown = visible_windows([pid])
        row = {"cold": {"wall": round(cold["_wall"], 1), "gameStart": round(stage(cold, "game-start").get("seconds", 0), 1),
                        "mode": stage(cold, "game-start").get("mode"), "visibleWindows": None if shown is None else len(shown.get(pid, [])), **split(s, pid)}}
        print(f"  cold ui-check {row['cold']['wall']} s, game {row['cold']['game']}", flush=True)
        row["idle"] = idle(pid, args.idle)
        print(f"  idle {row['idle']}", flush=True)
        warm = modaccel(["ui-check"], root)
        need(warm, "warm ui-check")
        row["warm"] = {"wall": round(warm["_wall"], 1), "check": round(warm.get("seconds", 0), 2)}
        # the fast path after the first launch: game-stop, then start again from the saved launch
        modaccel(["game-stop"], root)
        with Sampler() as s3:
            saved = modaccel(["ui-check"], root)
        need(saved, "saved-launch ui-check")
        spid = game_pid(root)
        shown = visible_windows([spid])
        row["saved"] = {"wall": round(saved["_wall"], 1), "gameStart": round(stage(saved, "game-start").get("seconds", 0), 1),
                        "mode": stage(saved, "game-start").get("mode"), "visibleWindows": None if shown is None else len(shown.get(spid, [])), **split(s3, spid)}
        print(f"  saved-launch ui-check {row['saved']['wall']} s, windows on screen {row['saved']['visibleWindows']}", flush=True)
        print(f"  warm ui-check {row['warm']['wall']} s", flush=True)
        if not args.no_server:
            with Sampler() as s2:
                mp = modaccel(["mp-check"], root)
            need(mp, "mp-check")
            # the result's server pid is the Gradle launcher; the server is the JVM listening on the port
            spid = stage(mp, "server-start").get("pid")
            row["mp"] = {"wall": round(mp["_wall"], 1), **split(s2, game_pid(root), spid)}
            print(f"  mp-check {row['mp']['wall']} s, server {row['mp']['server']}", flush=True)
        modaccel(["game-stop"], root)
        runs.append(row)
    return {"root": str(root), "runs": runs}


def warm(args):
    root = Path(args.root).resolve()
    first = modaccel(["ui-check"], root)
    need(first, "ui-check to load the game")
    pid = game_pid(root)
    # A scenario: a scripted step in the loaded world (the player turned and moved), then the frame captured.
    step = "mc.player.setYRot(mc.player.getYRot() + 90); mc.player.setPos(mc.player.getX() + 1, mc.player.getY(), mc.player.getZ()); mc.player.getYRot()"
    checks = [("screen", [["ui-check", "--screen", args.screen]]), ("world", [["world-check", "--world", args.world]]),
              ("scenario", [["eval", "--code", step], ["ui-check"]])]
    runs = []
    m0 = machine_times()
    for n in range(args.runs):
        row = {}
        for name, commands in checks:
            wall = check = 0.0
            for command in commands:
                report = modaccel(command, root)
                need(report, name + " (" + command[0] + ")")
                wall += report["_wall"]
                check += report.get("seconds", 0)
            shown = visible_windows([pid])
            row[name] = {"wall": round(wall, 2), "check": round(check, 2), "visibleWindows": None if shown is None else len(shown.get(pid, []))}
        print(f"  run {n + 1}/{args.runs}: " + ", ".join(f"{k} {v['wall']} s ({v['visibleWindows']} windows)" for k, v in row.items()), flush=True)
        runs.append(row)
    busy = machine_busy(m0, machine_times())
    modaccel(["game-stop"], root)
    medians = {name: median(runs, name, "wall") for name, _ in checks}
    windows = max((r[name]["visibleWindows"] or 0) for r in runs for name, _ in checks) if runs else None
    return {"root": str(root), "warm": runs, "medians": medians, "mostWindowsOnScreen": windows, "game": game_pid(root) or pid,
            "machineBusyPercent": busy}


MOD_FILES = ["src", "gradle", "build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat"]


def state_pid(root, name):
    try:
        return json.loads((Path(root) / ".modaccel" / "portable" / name).read_text(encoding="utf-8"))["pid"]
    except (OSError, ValueError, KeyError):
        return None


def together(roots, command):
    """One modaccel command on every root at the same moment; the reports in root order and what started meanwhile."""
    with Sampler() as sampler:
        threads, reports = [], [None] * len(roots)

        def one(i):
            reports[i] = modaccel(command, roots[i])
        for i in range(len(roots)):
            threads.append(threading.Thread(target=one, args=(i,)))
            threads[-1].start()
        for t in threads:
            t.join()
    return reports, sampler


def projects(args):
    source = Path(args.root).resolve()
    base = HARNESS / ".runs" / "session-cost" / "projects"
    roots = []
    for i in range(args.count):
        root = base / f"p{i + 1}"
        for name in MOD_FILES:
            src, dst = source / name, root / name
            if dst.exists():
                continue
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copytree(src, dst) if src.is_dir() else shutil.copy2(src, dst)
        roots.append(root)
    print(f"preparing {len(roots)} projects one after another", flush=True)
    for root in roots:
        need(modaccel(["data-check", "--once"], root), "preparing " + root.name)
    for root in roots:
        modaccel(["game-stop"], root)
    m0 = machine_times()
    fresh, s1 = together(roots, ["data-check"])
    for r, root in zip(fresh, roots):
        need(r, "data-check from no game in " + root.name)
    row = lambda r: {"wall": round(r["_wall"], 1), "cpu": (r.get("result") or {}).get("cpuSeconds")}
    result = {"source": str(source), "count": len(roots), "fresh": [row(r) for r in fresh], "freshAll": split(s1)["other"]}
    print(f"  {len(roots)} at once from no game: longest {max(x['wall'] for x in result['fresh'])} s, everything that started {result['freshAll']}", flush=True)
    deadline = time.time() + args.timeout
    while time.time() < deadline and not all(state_pid(r, "data-check-spare.json") for r in roots):
        time.sleep(1)
    warm, _ = together(roots, ["data-check"])
    for r, root in zip(warm, roots):
        need(r, "data-check with the game loaded in " + root.name)
    result["warm"] = [row(r) for r in warm]
    now = processes()
    games = [state_pid(r, "data-check.json") for r in roots]
    spares = [state_pid(r, "data-check-spare.json") for r in roots]
    result["loadedGB"] = gb(sum(now[pid]["rss"] for pid in games if pid in now))
    result["spareGB"] = gb(sum(now[pid]["rss"] for pid in spares if pid in now))
    result["spares"] = sum(1 for pid in spares if pid in now)
    result["machineBusyPercent"] = machine_busy(m0, machine_times())
    print(f"  at once with games loaded: longest {max(x['wall'] for x in result['warm'])} s; loaded games {result['loadedGB']} GB, spares {result['spareGB']} GB", flush=True)
    for root in roots:
        modaccel(["game-stop"], root)
    return result


def ask(session, request, timeout=150):
    """One request to a running game's agent, the same line protocol modaccel uses."""
    with socket.create_connection(("127.0.0.1", session["port"]), timeout=5) as sock:
        sock.settimeout(timeout)
        sock.sendall((json.dumps({**request, "token": session["token"]}) + "\n").encode("utf-8"))
        data = b""
        while not data.endswith(b"\n"):
            chunk = sock.recv(65536)
            if not chunk:
                break
            data += chunk
    answer = json.loads(data.decode("utf-8"))
    if not answer.get("ok"):
        raise RuntimeError(f"session {session['name']}: {request['command']} failed: {answer.get('error')}")
    return answer


def parallel(args):
    root = Path(args.root).resolve()
    recipe = json.loads((root / ".modaccel" / "portable" / "launch.json").read_text(encoding="utf-8"))
    if not recipe.get("verified") or "-cp" not in recipe["arguments"]:
        raise SystemExit("No verified, replayable saved launch: run modaccel ui-check on the project once first.")
    if game_pid(root):
        print("note: a modaccel game is already running for this project; it is not counted", flush=True)
    base = root / ".modaccel" / "sessions"
    template = Path(recipe["directory"])
    sessions = []
    for i in range(args.count):
        name = f"s{i + 1}"
        folder = base / name
        shutil.rmtree(folder, ignore_errors=True)
        (folder / "run").mkdir(parents=True)
        if (template / "options.txt").is_file():
            shutil.copy2(template / "options.txt", folder / "run" / "options.txt")
        token = str(uuid.uuid4())
        argv, it = [], iter(recipe["arguments"])
        for a in it:
            if a.startswith("-Dmodaccel.token="):
                a = "-Dmodaccel.token=" + token
            elif a.startswith("-Dmodaccel.state="):
                a = "-Dmodaccel.state=" + str(folder)
            elif a == "-cp":  # Windows caps a command line at 32767 characters
                cp = next(it)
                (folder / "cp.args").write_text('-cp\n"' + cp.replace("\\", "\\\\") + '"\n', encoding="utf-8")
                a = "@" + str(folder / "cp.args")
            argv.append(a)
        sessions.append({"name": name, "folder": folder, "token": token, "command": [recipe["executable"]] + args.jvm.split() + argv})
    print(f"starting {args.count} hidden clients at once", flush=True)
    t0 = time.perf_counter()
    with Sampler() as s:
        for x in sessions:
            log = open(x["folder"] / "game.log", "wb")
            x["process"] = subprocess.Popen(x["command"], cwd=x["folder"] / "run", stdout=log, stderr=subprocess.STDOUT,
                                            env={**os.environ, **recipe.get("environment", {})})
        pending = list(sessions)
        while pending and time.perf_counter() - t0 < args.timeout:
            for x in list(pending):
                try:
                    info = json.loads((x["folder"] / "agent.json").read_text(encoding="utf-8"))
                except (OSError, ValueError):
                    info = {}
                if info.get("token") == x["token"]:
                    x.update(pid=info["pid"], port=info["port"], ready=round(time.perf_counter() - t0, 1))
                    pending.remove(x)
                elif x["process"].poll() is not None:
                    x["failed"] = f"exited {x['process'].returncode} before its first frame; log {x['folder'] / 'game.log'}"
                    pending.remove(x)
            time.sleep(0.5)
        for x in pending:
            x["failed"] = f"no first frame within {args.timeout} s"
        started = [x for x in sessions if "port" in x]
        shots = root / "build" / "modaccel" / "shots"
        for x in started:
            try:
                ask(x, {"command": "resize", "width": 1280, "height": 720})
                ask(x, {"command": "ready"})
                first = ask(x, {"command": "status"})
                if str(first.get("screen", "")).endswith("AccessibilityOnboardingScreen"):
                    ask(x, {"command": "click", "label": "Continue"})
                    ask(x, {"command": "ready"})
                x["capture"] = ask(x, {"command": "capture", "path": str(shots / f"parallel-{x['name']}.png"), "width": 1280, "height": 720})["path"]
                x["checked"] = round(time.perf_counter() - t0, 1)
            except Exception as error:  # noqa: BLE001 - reported per session
                x["failed"] = str(error)
        all_checked = round(time.perf_counter() - t0, 1)
        shown = visible_windows([x["pid"] for x in started])
        if args.threads:
            for x in started:
                x["threads"] = thread_groups(x["pid"])
    pids = {x["pid"]: x["name"] for x in started}
    games = [v for k, v in s.seen.items() if k in pids]
    load = idle_many(list(pids), args.idle)
    for x in started:
        try:
            ask(x, {"command": "quit"}, timeout=20)
        except Exception:  # noqa: BLE001 - it may already be closing
            pass
    for x in sessions:
        try:
            x["process"].wait(30)
        except subprocess.TimeoutExpired:
            x["process"].kill()
    ok = [x for x in sessions if "capture" in x]
    return {"root": str(root), "count": args.count, "jvm": args.jvm,
            "visibleWindows": None if shown is None else sum(len(v) for v in shown.values()), "checked": len(ok), "allCheckedSeconds": all_checked,
            "machineBusyPercent": s.busy,
            "peakGBTotal": round(sum(gb(g["peak"]) for g in games), 2), "privateGBTotal": round(sum(gb(g["private"]) for g in games), 2),
            "startCpuSeconds": round(sum(g["cpu"] for g in games), 1), "idle": load,
            "sessions": [{k: v for k, v in x.items() if k in ("name", "pid", "ready", "checked", "capture", "failed", "threads")} for x in sessions]}


def thread_groups(pid):
    """CPU seconds per kind of thread: game, workers, JIT, GC, graphics driver and so on."""
    import re
    groups = {}
    for name, cpu in threads(pid):
        n = re.sub(r"[ #-]*\d+$", "", name).strip() or name
        if "Compiler" in n:
            n = "JIT compiler"
        elif n.startswith(("GC Thread", "G1 ", "VM Periodic", "Gang worker")) or "GC" in n.split()[0:1]:
            n = "garbage collector"
        groups[n] = round(groups.get(n, 0) + cpu, 1)
    return dict(sorted(groups.items(), key=lambda kv: -kv[1]))


def idle_many(pids, seconds):
    first, m0 = processes(), machine_times()
    start = time.perf_counter()
    time.sleep(seconds)
    last, m1 = processes(), machine_times()
    wall = time.perf_counter() - start
    used = sum(last[p]["cpu"] - first[p]["cpu"] for p in pids if p in first and p in last) / wall
    return {"seconds": seconds, "cores": round(used, 2), "rssGB": round(sum(gb(last[p]["rss"]) for p in pids if p in last), 2),
            "machineBusyPercent": machine_busy(m0, m1)}


def run(args):
    cmd = args.command[1:] if args.command[:1] == ["--"] else args.command
    # Windows cannot start a .cmd (modaccel.cmd, gradlew.bat) by its bare name; which() finds it the way a shell would
    cmd = [shutil.which(cmd[0]) or cmd[0]] + cmd[1:]
    start = time.perf_counter()
    with Sampler() as s:
        code = subprocess.run(cmd).returncode
    return {"label": args.label, "command": cmd, "exit": code, "wall": round(time.perf_counter() - start, 1), **split(s)}


def median(rows, *path):
    vals = []
    for r in rows:
        for k in path:
            r = (r or {}).get(k)
        if isinstance(r, (int, float)):
            vals.append(r)
    return round(statistics.median(vals), 2) if vals else None


def clean(o):
    """The result without this machine's paths, to keep in bench/session-cost/ as a measure's source."""
    if isinstance(o, dict):
        return {k: clean(v) for k, v in o.items() if k != "root"}
    if isinstance(o, list):
        return [clean(x) for x in o]
    if isinstance(o, str) and "Users" in o and (":" in o or o.startswith("/")):
        return Path(o.replace(chr(92), "/")).name
    return o


def record(result, name):
    """Medians become points; the cleaned result is kept in bench/session-cost/ as their source."""
    kept = HARNESS / "bench" / "session-cost" / name
    kept.parent.mkdir(exist_ok=True)
    kept.write_text(json.dumps(clean(result), indent=1) + chr(10), encoding="utf-8", newline=chr(10))
    source = "bench/session-cost/" + name
    add = []
    if "fresh" in result:
        n = result["count"]
        add.append(("test.parallel-sessions", n, 1, "data-check on %d projects at once, each starting its own game: longest %s s; %s GB loaded and %s GB spare games together"
                    % (n, max(x["wall"] for x in result["fresh"]), result["loadedGB"], result["spareGB"])))
    elif "warm" in result:
        n = len(result["warm"])
        note = "hidden game, %d windows on screen, the whole modaccel command; machine %s%% busy" % (result["mostWindowsOnScreen"], result["machineBusyPercent"])
        add.append(("test.screen-check.fabric", result["medians"]["screen"], n, "ui-check --screen, " + note))
        add.append(("test.scenario.fabric", result["medians"]["scenario"], n, "eval step then ui-check capture, " + note))
    elif "runs" in result:
        runs = result["runs"]
        m = lambda *p: median(runs, *p)
        add.append(("test.session-ram", m("saved", "game", "peakGB"), len(runs), "peak working set, saved launch"))
        add.append(("test.session-idle-cpu", m("idle", "cores"), len(runs), "title screen, %g s" % runs[0]["idle"]["seconds"]))
    else:
        n = result["checked"]
        if n and not result.get("jvm"):
            add.append(("test.session-start-cpu", round(result["startCpuSeconds"] / n), n, "%d clients at once, %s CPU-s in all" % (n, result["startCpuSeconds"])))
    for mid, value, n, note in add:
        if value is None:
            continue
        subprocess.run([sys.executable, str(HARNESS / "bench" / "measures.py"), "add", mid, str(value), "--source", source, "--n", str(n), "--note", note], check=True)


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="mode", required=True)
    c = sub.add_parser("client")
    c.add_argument("--root", required=True)
    c.add_argument("--runs", type=int, default=1)
    c.add_argument("--idle", type=float, default=20)
    c.add_argument("--no-server", action="store_true")
    c.add_argument("--record", action="store_true", help="add the medians to coverage/measures.json")
    pa = sub.add_parser("parallel")
    pa.add_argument("--root", required=True)
    pa.add_argument("--count", type=int, default=2)
    pa.add_argument("--idle", type=float, default=20)
    pa.add_argument("--timeout", type=float, default=600)
    pa.add_argument("--jvm", default="", help="extra JVM flags for every client, to compare launch profiles")
    pa.add_argument("--record", action="store_true", help="add the start CPU per client to coverage/measures.json")
    pa.add_argument("--threads", action="store_true", help="CPU per kind of thread in each client once it is checked")
    w = sub.add_parser("warm")
    w.add_argument("--root", required=True)
    w.add_argument("--world", required=True, help="a save in the project's game folder")
    w.add_argument("--screen", default="net.minecraft.client.gui.screens.TitleScreen")
    w.add_argument("--runs", type=int, default=5)
    w.add_argument("--record", action="store_true", help="add the medians to coverage/measures.json")
    pr = sub.add_parser("projects")
    pr.add_argument("--root", required=True, help="the mod to copy")
    pr.add_argument("--count", type=int, default=8)
    pr.add_argument("--timeout", type=float, default=180, help="seconds to wait for every spare game")
    pr.add_argument("--record", action="store_true", help="add the count to coverage/measures.json")
    r = sub.add_parser("run")
    r.add_argument("--label", required=True)
    r.add_argument("command", nargs=argparse.REMAINDER)
    args = ap.parse_args()
    result = {"client": client, "parallel": parallel, "warm": warm, "projects": projects, "run": run}[args.mode](args)
    result.update(machine={"cpus": os.cpu_count(), "os": sys.platform}, measured=datetime.now(timezone.utc).isoformat(timespec="seconds"))
    out = HARNESS / ".runs" / "session-cost" / (datetime.now().strftime("%Y-%m-%dT%H-%M-%S") + ".json")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, indent=1), encoding="utf-8")
    print("wrote", out)
    if getattr(args, "record", False):
        record(result, out.name)


if __name__ == "__main__":
    main()
