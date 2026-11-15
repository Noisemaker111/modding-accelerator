#!/usr/bin/env python3
"""Writes the NeoForge bridge's Tags class from NeoForge's own published Tags.

    python bench/bridge_tags.py            write knowledge/bridges/neoforge-on-fabric/Tags.java and 1.21.1/Tags.java
    python bench/bridge_tags.py --check    fail when the written files differ from what NeoForge publishes

NeoForge's net.neoforged.neoforge.common.Tags names the convention tags (c:...) and NeoForge's own (neoforge:...) a mod
uses as TagKey constants. On Fabric the converted mod needs the same constants: the same names, the same tag ids. This
reads the sources jar of the NeoForge releases knowledge/loaders.json checks the bridge against ("neoforge" and
"neoforge-21.1" in its published table), takes every public TagKey constant of Tags' nested classes with the tag id it
is made from, and writes a class that makes the same TagKey for each. Only names and ids are taken; whether a tag has
entries on Fabric is up to the data packs (Fabric API's convention tags fill most c: tags the same way).
"""
import argparse
import io
import json
import re
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RULES = ROOT / "knowledge" / "loaders.json"
CACHE = ROOT / ".runs" / "loader-api"
OUT = {"neoforge": ROOT / "knowledge/bridges/neoforge-on-fabric/Tags.java",
       "neoforge-21.1": ROOT / "knowledge/bridges/neoforge-on-fabric/1.21.1/Tags.java"}
REGISTRY = {"Blocks": ("BLOCK", "Block", "net.minecraft.world.level.block.Block"),
            "EntityTypes": ("ENTITY_TYPE", "EntityType<?>", "net.minecraft.world.entity.EntityType"),
            "Items": ("ITEM", "Item", "net.minecraft.world.item.Item"),
            "Fluids": ("FLUID", "Fluid", "net.minecraft.world.level.material.Fluid"),
            "Enchantments": ("ENCHANTMENT", "Enchantment", "net.minecraft.world.item.enchantment.Enchantment"),
            "Potions": ("POTION", "Potion", "net.minecraft.world.item.alchemy.Potion"),
            "Biomes": ("BIOME", "Biome", "net.minecraft.world.level.biome.Biome"),
            "Structures": ("STRUCTURE", "Structure", "net.minecraft.world.level.levelgen.structure.Structure"),
            "DamageTypes": ("DAMAGE_TYPE", "DamageType", "net.minecraft.world.damagesource.DamageType")}


def sources(key):
    entry = json.loads(RULES.read_text(encoding="utf-8"))["published"][key]
    group, artifact, version = entry["coordinate"].split(":")[:3]
    url = f"{entry['maven']}/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}-sources.jar"
    CACHE.mkdir(parents=True, exist_ok=True)
    file = CACHE / f"{key}-{artifact}-{version}-sources.jar"
    if not file.is_file():
        request = urllib.request.Request(url, headers={"User-Agent": "mmt-bridge-tags"})
        with urllib.request.urlopen(request, timeout=300) as response:
            file.write_bytes(response.read())
    with zipfile.ZipFile(io.BytesIO(file.read_bytes())) as jar:
        return jar.read("net/neoforged/neoforge/common/Tags.java").decode("utf-8"), version


def nested(text):
    """{class name: body} for each nested public static class of Tags."""
    out = {}
    for m in re.finditer(r"(?m)^    public static class (\w+) \{", text):
        depth, i = 1, m.end()
        while depth:
            depth += {"{": 1, "}": -1}.get(text[i], 0)
            i += 1
        out[m.group(1)] = text[m.end():i - 1]
    return out


def helpers(body):
    """{helper method: namespace} from a nested class's private tag makers."""
    out = {}
    for m in re.finditer(r"private static \S+ (\w+)\(String name\) \{\s*return [^;]*?fromNamespaceAndPath\((?:\"(\w+)\"|NeoForgeMod\.MOD_ID), name\)", body):
        out[m.group(1)] = m.group(2) or "neoforge"
    return out


def constants(text):
    classes = nested(text)
    block_items = {}
    if "BlockItems" in classes:
        for m in re.finditer(r"public static final BlockItemTagId (\w+) = tag\(\"([^\"]+)\"\)", classes["BlockItems"]):
            block_items[m.group(1)] = "c:" + m.group(2)
    out = {}
    for name, body in classes.items():
        if name not in REGISTRY:
            continue
        makers = helpers(body)
        fields = []
        for m in re.finditer(r"(?s)((?:\s*@Deprecated[^\n]*\n)?)\s*public static final TagKey<[^>]+>?>? (\w+) = (.+?);", body):
            init = m.group(3).strip()
            call = re.fullmatch(r"(\w+)\(\"([^\"]+)\"\)", init)
            if call and call.group(1) in makers:
                tag = makers[call.group(1)] + ":" + call.group(2)
            elif re.fullmatch(r"BlockItems\.(\w+)\.(?:block|item)\(\)", init):
                tag = block_items[re.fullmatch(r"BlockItems\.(\w+)\.\w+\(\)", init).group(1)]
            elif re.fullmatch(r"DyeColor\.(\w+)\.getTag\(\)", init):
                tag = "c:dyes/" + re.fullmatch(r"DyeColor\.(\w+)\.getTag\(\)", init).group(1).lower()
            else:
                raise SystemExit(f"Tags.{name}.{m.group(2)}: no tag id in {init!r}")
            fields.append((m.group(2), tag, bool(m.group(1).strip())))
        out[name] = fields
    return out


def render(fields, version, identifier):
    lines = ["package ${package};", "", "import net.minecraft.core.registries.Registries;",
             f"import net.minecraft.resources.{identifier};", "import net.minecraft.tags.TagKey;"]
    imports = sorted({REGISTRY[c][2] for c in fields})
    lines += [f"import {i};" for i in imports]
    lines += ["", "/**",
              f" * NeoForge's net.neoforged.neoforge.common.Tags (NeoForge {version}): every TagKey constant with the tag id",
              " * NeoForge makes it from, for a converted mod on Fabric. Written by python bench/bridge_tags.py from NeoForge's",
              " * published sources; the tags' entries come from data packs (Fabric API's convention tags fill the c: ones).",
              " */", "public class Tags {"]
    make = "fromNamespaceAndPath"
    for name, items in fields.items():
        registry, type_, _ = REGISTRY[name]
        lines += ["", f"    public static class {name} {{"]
        for field, tag, deprecated in items:
            if deprecated:
                lines.append("        @Deprecated")
            lines.append(f"        public static final TagKey<{type_}> {field} = tag(\"{tag}\");")
        lines += ["", f"        private static TagKey<{type_}> tag(String id) {{",
                  f"            return TagKey.create(Registries.{registry}, {identifier}.{make}(id.substring(0, id.indexOf(':')), id.substring(id.indexOf(':') + 1)));",
                  "        }", "    }"]
    lines += ["}", ""]
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    stale = []
    for key, out in OUT.items():
        text, version = sources(key)
        written = render(constants(text), version, "ResourceLocation" if key.endswith("21.1") else "Identifier")
        if args.check:
            if not out.is_file() or out.read_bytes().decode("utf-8").replace("\r\n", "\n") != written:
                stale.append(str(out.relative_to(ROOT)))
        else:
            out.write_text(written, encoding="utf-8", newline="\n")
            print(f"{out.relative_to(ROOT)}: {sum(len(v) for v in constants(text).values())} tags from NeoForge {version}")
    if stale:
        raise SystemExit("not what NeoForge publishes: " + ", ".join(stale))
    return 0


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):  # UTF-8 whatever the console or PYTHONIOENCODING says
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    sys.exit(main())
