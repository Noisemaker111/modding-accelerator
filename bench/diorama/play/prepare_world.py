"""Create the save the Diorama play experiment copies into every launch.

    python bench/diorama/play/prepare_world.py --root DIORAMA_SOURCE [--project :1.21.1] [--out .runs/diorama-play]

Starts a hidden client (your window and sound preferences apply), creates the world "diorama-play"
(create-world.java: fixed seed, creative, no mobs, frozen time and weather), flies the experiment's whole
path once (fly.java) so every chunk it maps is generated and saved, leaves the world so it is written,
stops the game and copies the save, without its session lock, to OUT/diorama-play. experiment.json's
worldSource points there. Diorama's own map folder is not copied: each launch starts with an empty map.

The experiment copies region files out of that save ("files"). Before leaving the world, every
overworld region it names gets its chunk nearest the origin generated, so regions the flight never
reached (r.-1.1) exist too; the save is checked for every named file after the copy.
"""
import argparse
import json
import os
import re
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


def required(experiment, folder):
    """Files the experiment copies out of its worldSource, as paths inside the save."""
    source = (Path(folder) / experiment["worldSource"]).resolve()
    need = []
    for item in (experiment.get("files") or {}).values():
        path = (Path(folder) / item).resolve()
        if path.is_relative_to(source):
            need.append(path.relative_to(source).as_posix())
    return need


def region_chunk(name):
    """The chunk of an overworld region file nearest the origin, or None for any other file."""
    match = re.fullmatch(r"region/r\.(-?\d+)\.(-?\d+)\.mca", name)
    if not match:
        return None
    rx, rz = int(match.group(1)), int(match.group(2))
    return (min(max(0, rx * 32), rx * 32 + 31), min(max(0, rz * 32), rz * 32 + 31))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", required=True)
    ap.add_argument("--project", default=":1.21.1")
    ap.add_argument("--out", default=str(ROOT / ".runs" / "diorama-play"))
    args = ap.parse_args(argv)
    need = required(json.loads((HERE / "experiment.json").read_text(encoding="utf-8")), HERE)
    chunks = sorted({c for c in map(region_chunk, need) if c is not None})
    root = Path(args.root).resolve()
    game = root / "versions" / args.project.strip(":") / "run"
    save = game / "saves" / "diorama-play"
    if save.exists():
        raise SystemExit(f"{save} exists; remove it to create the world again")
    try:
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
        # Generate one chunk in every region the experiment copies; leaving the world saves them.
        load = "; ".join(f"level.getChunk({x}, {z})" for x, z in chunks)
        modaccel(["eval", "--thread", "control", "--timeout", "120", "--code",
                  "var server = mc.getSingleplayerServer(); var level = server.overworld(); "
                  f"server.submit(() -> {{ {load}; }}).join(); return \"{len(chunks)} region chunks\";"], root, args.project)
        modaccel(["eval", "--code", 'mc.level.disconnect(); mc.disconnect(new net.minecraft.client.gui.screens.TitleScreen()); return "left";'], root, args.project)
        modaccel(["eval", "--thread", "control", "--until", "mc.level == null && mc.getSingleplayerServer() == null", "--timeout", "120", "--code", "true"], root, args.project)
    finally:
        # A failed step must not leave a hidden game running.
        modaccel(["game-stop"], root, args.project)
    target = Path(args.out).resolve() / "diorama-play"
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(save, target, ignore=shutil.ignore_patterns("session.lock"))
    missing = [name for name in need if not (target / name).is_file()]
    if missing:
        raise SystemExit(f"{target} lacks files experiment.json copies: {', '.join(missing)}")
    print(f"World ready: {target} ({len(need)} experiment files present)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
