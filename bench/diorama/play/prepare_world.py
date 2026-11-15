"""Create the save the Diorama play experiment copies into every launch.

    python bench/diorama/play/prepare_world.py --root DIORAMA_SOURCE [--project :1.21.1] [--out .runs/diorama-play]

Starts a hidden client (your window and sound preferences apply), creates the world "diorama-play"
(create-world.java: fixed seed, creative, no mobs, frozen time and weather), flies the experiment's whole
path once (fly.java) so every chunk it maps is generated and saved, leaves the world so it is written,
stops the game and copies the save, without its session lock, to OUT/diorama-play. experiment.json's
worldSource points there. Diorama's own map folder is not copied: each launch starts with an empty map.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent.parent


def modaccel(args, root, project, timeout=900):
    launcher = str(ROOT / ("modaccel.cmd" if os.name == "nt" else "modaccel"))
    done = subprocess.run([launcher, *args, "--root", str(root), "--project", project, "--json"],
                          capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    text = done.stdout.strip()
    try:
        out = json.loads(text[text.rfind("\n{") + 1:] if "\n{" in text else text)
    except ValueError:
        out = {"ok": False, "result": {"error": (done.stderr or text)[-2000:]}}
    if not out.get("ok"):
        raise SystemExit(f"modaccel {args[0]} failed: {(out.get('result') or {}).get('error')}")
    return out.get("result") or {}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", required=True)
    ap.add_argument("--project", default=":1.21.1")
    ap.add_argument("--out", default=str(ROOT / ".runs" / "diorama-play"))
    args = ap.parse_args(argv)
    root = Path(args.root).resolve()
    game = root / "versions" / args.project.strip(":") / "run"
    save = game / "saves" / "diorama-play"
    if save.exists():
        raise SystemExit(f"{save} exists; remove it to create the world again")
    modaccel(["ui-check"], root, args.project)
    modaccel(["eval", "--file", str(HERE / "create-world.java")], root, args.project)
    modaccel(["eval", "--thread", "control", "--until", "mc.level != null && mc.player != null", "--timeout", "120", "--code", "true"], root, args.project)
    modaccel(["eval", "--file", str(HERE / "fly.java")], root, args.project)
    # The whole path (fly.segment unset) takes 60 s and fly.done follows 20 s later; wait in bounded steps.
    for _ in range(2):
        try:
            modaccel(["eval", "--thread", "control", "--until", 'Boolean.TRUE.equals(vars.get("fly.done"))', "--timeout", "120", "--code", "true"], root, args.project)
            break
        except SystemExit:
            continue
    modaccel(["eval", "--code", 'mc.level.disconnect(); mc.disconnect(new net.minecraft.client.gui.screens.TitleScreen()); return "left";'], root, args.project)
    modaccel(["eval", "--thread", "control", "--until", "mc.level == null && mc.getSingleplayerServer() == null", "--timeout", "120", "--code", "true"], root, args.project)
    modaccel(["game-stop"], root, args.project)
    target = Path(args.out).resolve() / "diorama-play"
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(save, target, ignore=shutil.ignore_patterns("session.lock"))
    print(f"World ready: {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
