"""A small modpack instance for driving the desktop app's Modpack page: 14 made-up mods with a
duplicate mod id, a missing dependency and two mods shipping the same block file.

python app/ui-tests/make_pack.py .runs/desktop/pack-fixture
"""
import json
import pathlib
import sys
import zipfile


def toml(mod_id, version, needs=()):
    deps = "".join(
        f'[[dependencies.{mod_id}]]\nmodId="{d}"\nmandatory=true\nversionRange="[1,)"\nordering="NONE"\nside="BOTH"\n'
        for d in needs
    )
    return f'modLoader="javafml"\nloaderVersion="[1,)"\nlicense="MIT"\n[[mods]]\nmodId="{mod_id}"\nversion="{version}"\n{deps}'


def jar(path, mod_id, version, needs=(), files=None):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("META-INF/neoforge.mods.toml", toml(mod_id, version, needs))
        for name, body in (files or {}).items():
            z.writestr(name, body)


def main(out):
    instance = pathlib.Path(out) / "instance"
    mods = instance / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    for old in mods.glob("*.jar"):
        old.unlink()
    shared = {"assets/minecraft/blockstates/oak_log.json": "{}"}
    plain = ["lanterns", "copperworks", "skyroads", "farmhand", "minimap", "backpacks", "waystones", "quarry", "biomes", "storage"]
    for i, mod_id in enumerate(plain):
        jar(mods / f"{mod_id}-1.{i}.jar", mod_id, f"1.{i}")
    jar(mods / "trees-2.0.jar", "trees", "2.0", files=shared)
    jar(mods / "logs-1.4.jar", "logs", "1.4", needs=["treelib"], files=shared)
    jar(mods / "sparkle-3.1.jar", "sparkle", "3.1")
    jar(mods / "sparkle-3.2.jar", "sparkle", "3.2")
    (instance / "minecraftinstance.json").write_text(
        json.dumps({"name": "Demo pack", "gameVersion": "1.21.1", "baseModLoader": {"name": "neoforge-21.1.77"}})
    )
    print(instance)


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):  # UTF-8 whatever the console or PYTHONIOENCODING says
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    main(sys.argv[1] if len(sys.argv) > 1 else ".runs/desktop/pack-fixture")
