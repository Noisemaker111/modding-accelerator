#!/usr/bin/env python3
"""A/B comparison of two real-mods runs (for example main against a PR branch).

  python bench/real-mods/compare.py results/main.json results/pr9.json [--timing results/main-2.json] --out results/AB.md

Per mod and step: pass/fail on each side, a REGRESSION flag where A passed and B did not, and
seconds. The evaluated model (.modaccel/portable/model.json, saved by run.py right after setup) is
compared with the clone path, home folder and run ids taken out, project by project. --timing
gives a second run of side A made after B, so A and B timings come from the same warm caches.
"""
import argparse, json
from pathlib import Path

def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def normalize(value, root, home):
    if isinstance(value, dict):
        return {k: normalize(v, root, home) for k, v in value.items() if k not in ("id", "generatedAt", "seconds")}
    if isinstance(value, list):
        return [normalize(v, root, home) for v in value]
    if isinstance(value, str):
        for needle, token in ((root, "<ROOT>"), (home, "<HOME>")):
            for form in (needle, needle.replace("\\", "/"), "/" + needle.replace("\\", "/"), needle.replace("\\", "\\\\")):
                value = value.replace(form, token)
        return value
    return value


def model_of(record):
    path = record.get("model")
    if not path or not Path(path).exists():
        return None
    model = load(path)
    return normalize(model, model.get("root", ""), str(Path.home()))


def describe(value):
    if isinstance(value, list):
        return f"{len(value)} entries"
    if isinstance(value, dict):
        return f"{len(value)} keys"
    return json.dumps(value)[:80]


def model_diff(a, b):
    if a is None or b is None:
        return ["model missing on " + ("A" if a is None else "B")]
    out = []
    for key in sorted(set(a) | set(b)):
        if key == "projects":
            continue
        if a.get(key) != b.get(key):
            out.append(f"`{key}`: {describe(a.get(key))} vs {describe(b.get(key))}")
    pa = {p["path"]: p for p in a.get("projects", [])}
    pb = {p["path"]: p for p in b.get("projects", [])}
    if set(pa) != set(pb):
        only_a, only_b = sorted(set(pa) - set(pb)), sorted(set(pb) - set(pa))
        out.append(f"projects: {len(pa)} vs {len(pb)}" + (f"; only A: {', '.join(only_a[:8])}" if only_a else "") + (f"; only B: {', '.join(only_b[:8])}" if only_b else ""))
    for path in sorted(set(pa) & set(pb)):
        for key in sorted(set(pa[path]) | set(pb[path])):
            x, y = pa[path].get(key), pb[path].get(key)
            if x == y:
                continue
            if isinstance(x, list) and isinstance(y, list):
                sx = {json.dumps(i, sort_keys=True) for i in x}
                sy = {json.dumps(i, sort_keys=True) for i in y}
                if sx == sy:
                    out.append(f"`{path}` {key}: same entries, different order")
                    continue
                extra = sorted(sy - sx)[:2]
                missing = sorted(sx - sy)[:2]
                out.append(f"`{path}` {key}: {len(x)} vs {len(y)}; only A {len(sx - sy)}, only B {len(sy - sx)}"
                           + (f"; e.g. A has {missing[0][:160]}" if missing else "") + (f"; B has {extra[0][:160]}" if extra else ""))
            else:
                out.append(f"`{path}` {key}: {describe(x)} vs {describe(y)}")
    return out


def cell(step):
    if not step:
        return "-"
    status = step.get("status")
    return f"{status} {step.get('seconds', 0):.1f}s" if status in ("pass", "fail") else status


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("a")
    parser.add_argument("b")
    parser.add_argument("--timing", help="a second run of side A made after B, for timings from the same cache state")
    parser.add_argument("--names", default="A,B")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    a, b = load(args.a), load(args.b)
    a2 = load(args.timing) if args.timing else None
    na, nb = args.names.split(",")
    steps = list(a["mods"][0]["steps"].keys())
    lines = [f"# A/B: {na} `{a.get('modaccelCommit')}` vs {nb} `{b.get('modaccelCommit')}`", "",
             f"Fresh clones on both sides, same machine, same cache folder; global Gradle cache warm at start: "
             f"{na} {a.get('gradleCacheWarm')}, {nb} {b.get('gradleCacheWarm')}" + (f", {na} again {a2.get('gradleCacheWarm')}" if a2 else "") + ".", ""]
    regressions, fixes, models = [], [], []
    bmods = {m["id"]: m for m in b["mods"]}
    a2mods = {m["id"]: m for m in a2["mods"]} if a2 else {}
    header = "| mod | step | " + na + " | " + nb + " |" + (f" {na} again |" if a2 else "")
    lines += [header, "|---|---|---|---|" + ("---|" if a2 else "")]
    for mod in a["mods"]:
        other = bmods.get(mod["id"])
        again = a2mods.get(mod["id"])
        for name in steps:
            x = mod["steps"].get(name)
            y = other["steps"].get(name) if other else None
            z = again["steps"].get(name) if again else None
            if all((s or {}).get("status") in ("n/a", None) for s in (x, y, z)):
                continue
            flag = ""
            if x and y and x["status"] == "pass" and y["status"] != "pass":
                flag = " **REGRESSION**"
                regressions.append((mod["name"], name, y.get("error", "")))
            elif x and y and x["status"] != "pass" and y["status"] == "pass":
                flag = " (fixed in " + nb + ")"
                fixes.append((mod["name"], name))
            lines.append(f"| {mod['name']} | {name} | {cell(x)} | {cell(y)}{flag} |" + (f" {cell(z)} |" if a2 else ""))
        if other:
            diff = model_diff(model_of(mod), model_of(other))
            models.append((mod["name"], diff))
    lines += ["", "## Steps that pass on " + na + " and fail on " + nb, ""]
    lines += [f"- **{m} / {s}**: {e.splitlines()[0][:500] if e else '(no error text)'}" for m, s, e in regressions] or ["None."]
    lines += ["", "## Steps that fail on " + na + " and pass on " + nb, ""]
    lines += [f"- {m} / {s}" for m, s in fixes] or ["None."]
    lines += ["", "## Evaluated model differences (paths and ids normalized)", ""]
    for name, diff in models:
        lines.append(f"- **{name}**: " + ("identical" if not diff else "; ".join(diff[:6]) + (f"; and {len(diff) - 6} more" if len(diff) > 6 else "")))
    text = "\n".join(lines) + "\n"
    if args.out:
        args.out.write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
