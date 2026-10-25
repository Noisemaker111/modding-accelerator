"""Mine Stonecutter version switches from real multi-version mods.

Stonecutter mods keep one shared src/ with comment switches such as

    //? if >=1.21.2 {
    code for 1.21.2 and newer
    //?} else {
    /*code for older releases*/
    //?}

Each switch is a worked example from a human: this code on version A, that code on version B. This
script records every switch as data and places its version boundary on the neighbouring updates of
the change index (coverage/changes/).

    python bench/stonecutter_mine.py fetch      # check every pinned mod out under .runs/stonecutter-src
    python bench/stonecutter_mine.py pin        # pin unpinned manifest rows to their branch head (network)
    python bench/stonecutter_mine.py extract    # offline: write coverage/stonecutter/switches/*.json,
                                                # pairs.json (repeated pairs) and index.json

The checkouts are untrusted data: the script only reads their text, never builds or runs them.
"""
import json
import re
import subprocess
import sys
from collections import defaultdict
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "coverage" / "stonecutter"
MANIFEST = OUT / "mods.json"
SRC = ROOT / ".runs" / "stonecutter-src"
CHANGES = ROOT / "coverage" / "changes"


def load(p):
    return json.loads(Path(p).read_text(encoding="utf-8"))


def write(p, data):
    Path(p).parent.mkdir(parents=True, exist_ok=True)
    Path(p).write_text(json.dumps(data, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")


def slug(repo):
    return repo.replace("/", "__")


def git(*args, cwd=None):
    p = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True)
    if p.returncode:
        raise RuntimeError(f"git {' '.join(args)}: {p.stderr.strip()[:300]}")
    return p.stdout.strip()


# ---- fetch / pin ------------------------------------------------------------------------------
def pin():
    manifest = load(MANIFEST)
    for mod in manifest["mods"]:
        if mod.get("commit"):
            continue
        line = git("ls-remote", f"https://github.com/{mod['repo']}", f"refs/heads/{mod['branch']}")
        mod["commit"] = line.split()[0]
        mod["pinned"] = date.today().isoformat()
        print(mod["repo"], mod["commit"][:10])
    write(MANIFEST, manifest)


def fetch():
    SRC.mkdir(parents=True, exist_ok=True)
    for mod in load(MANIFEST)["mods"]:
        if not mod.get("commit"):
            print("unpinned, run pin first:", mod["repo"])
            continue
        dest = SRC / slug(mod["repo"])
        if (dest / ".git").is_dir() and git("rev-parse", "HEAD", cwd=dest) == mod["commit"]:
            continue
        if not (dest / ".git").is_dir():
            dest.mkdir(parents=True, exist_ok=True)
            git("init", "-q", cwd=dest)
            git("remote", "add", "origin", f"https://github.com/{mod['repo']}", cwd=dest)
        git("fetch", "-q", "--depth", "1", "origin", mod["commit"], cwd=dest)
        git("checkout", "-q", "--force", "FETCH_HEAD", cwd=dest)
        print("checked out", mod["repo"], mod["commit"][:10])


# ---- versions ---------------------------------------------------------------------------------
INDEX = load(CHANGES / "index.json")
RELEASES = [v["id"] for v in INDEX["versions"]]
# Releases before the index (no official mappings) that multi-version mods still build for.
LEGACY = {"1.7.10", "1.8.9", "1.9.4", "1.10.2", "1.11.2", "1.12.2", "1.13.2"}


def vkey(v):
    parts = [int(x) for x in re.findall(r"\d+", v)[:3]]
    while len(parts) < 3:
        parts.append(0)
    return tuple(parts)


def boundary_steps(op, v):
    """The neighbouring update(s) a comparison flips on: >=V and <V flip entering V; >V and <=V leaving V."""
    keys = [vkey(r) for r in RELEASES]
    k = vkey(v)
    # first release at or above V, and first above V
    at = next((i for i, x in enumerate(keys) if x >= k), None)
    above = next((i for i, x in enumerate(keys) if x > k), None)
    def step(i):
        if i is None or i == 0:
            return None
        return f"{RELEASES[i - 1]} -> {RELEASES[i]}"
    if op in (">=", "<"):
        return [s for s in [step(at)] if s]
    if op in (">", "<="):
        return [s for s in [step(above)] if s]
    return [s for s in [step(at), step(above)] if s]


def mod_versions(root):
    """Minecraft versions the mod builds for, read from versions/<name> and the Stonecutter settings."""
    found = set()
    vdir = root / "versions"
    names = [p.name for p in vdir.iterdir() if p.is_dir()] if vdir.is_dir() else []
    texts = []
    for name in ("settings.gradle.kts", "settings.gradle", "stonecutter.settings.toml", "stonecutter.json", "versions/versions.json"):
        p = root / name
        if p.is_file():
            texts.append(p.read_text(encoding="utf-8", errors="replace"))
    for t in texts:
        # versions("1.21.1", ...) / version("1.21-neoforge", "1.21") / vers("x", "1.21") / toml lists
        names += re.findall(r'"([0-9][0-9a-z.\-+_]*)"', t)
    for n in names:
        m = re.match(r"(\d+\.\d+(?:\.\d+)?)", n)
        if m and (m.group(1) in RELEASES or m.group(1) in LEGACY or re.match(r"2\d\.\d+(\.\d+)?$", m.group(1))):
            found.add(m.group(1))
    return sorted(found, key=vkey)


def window_steps(steps, versions):
    """Every update between the mod's nearest supported releases on each side of a boundary step."""
    out = []
    keys = [vkey(v) for v in versions]
    for s in steps:
        a, b = s.split(" -> ")
        below = [v for v in versions if vkey(v) <= vkey(a)]
        over = [v for v in versions if vkey(v) >= vkey(b)]
        lo = below[-1] if below else a
        hi = over[0] if over else b
        i = next((n for n, r in enumerate(RELEASES) if vkey(r) >= vkey(lo)), None)
        j = next((n for n, r in enumerate(RELEASES) if vkey(r) >= vkey(hi)), None)
        if i is None or j is None:
            out.append(s)
            continue
        for n in range(i, j):
            st = f"{RELEASES[n]} -> {RELEASES[n + 1]}"
            if st not in out:
                out.append(st)
    del keys
    return out


# ---- conditions -------------------------------------------------------------------------------
CMP = re.compile(r"(>=|<=|>|<|=|~|\^)?\s*(\d+\.\d+(?:\.\d+)?(?:[-+][\w.\-]+)?)")


def parse_condition(text):
    """Version comparisons and named constants in a Stonecutter condition (loader names, flags)."""
    comparisons, constants = [], []
    for m in re.finditer(r"(>=|<=|>|<|=|~|\^)?\s*(\d+\.\d+(?:\.\d+)?)\b|(!?)\b([A-Za-z_][\w:]*)\b", text):
        if m.group(2):
            comparisons.append({"op": m.group(1) or "=", "version": m.group(2)})
        elif m.group(4) and m.group(4) not in ("if", "else", "elif"):
            name = m.group(4)
            if ":" in name:
                continue
            constants.append(("!" if m.group(3) else "") + name)
    return comparisons, constants


# ---- parsing ----------------------------------------------------------------------------------
MARK = re.compile(r"//\?(?P<line>[^\n]*)|/\*\?(?P<block>.*?)\*/", re.S)


def directive(body):
    """('open', cond) | ('else', cond or None) | ('close',) | ('single', cond) | None."""
    b = body.strip()
    if not b:
        return None
    m = re.match(r"^\}\s*else\s+if\s+(.*?)\s*\{\s*$", b) or re.match(r"^\}\s*elif\s+(.*?)\s*\{\s*$", b)
    if m:
        return ("else", m.group(1))
    if re.match(r"^\}\s*else\s*\{\s*$", b):
        return ("else", None)
    if re.match(r"^\}\s*$", b):
        return ("close",)
    m = re.match(r"^(?:if\s+)?(.*?)\s*\{\s*$", b)
    if m and m.group(1):
        return ("open", m.group(1))
    m = re.match(r"^(?:if\s+)?(.+?)\s*$", b)
    if m and CMP.search(m.group(1)) or (m and re.match(r"^if\s", b)):
        return ("single", m.group(1))
    return None


def clean_branch(code):
    """Branch text as the human wrote it: inactive code loses Stonecutter's comment wrapping."""
    t = code.strip("\n")
    s = t.strip()
    if s.startswith("/*") and s.endswith("*/"):
        s = s[2:-2]
    elif s.startswith("/*") and not s.count("*/"):
        s = s[2:]
    s = s.replace("/^", "/*").replace("^/", "*/")
    lines = s.split("\n")
    # strip common indentation
    ind = [len(l) - len(l.lstrip()) for l in lines if l.strip()]
    cut = min(ind) if ind else 0
    return "\n".join(l[cut:] if len(l) >= cut else l.strip() for l in lines).strip()


STRIP = re.compile(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\\n])*"', re.S)
DECL = re.compile(r"(\w+)\s*\(")
NOT_METHOD = {"if", "for", "while", "switch", "catch", "synchronized", "return", "new", "else", "try", "super", "this", "when", "throw"}
TYPE = re.compile(r"(?m)^[ \t]*(?:(?:public|private|protected|static|final|abstract|sealed|open|data|internal)\s+)*(?:class|interface|enum|record|object)\s")


def blank(text):
    """The text with comments and strings replaced by spaces (newlines kept), so offsets still line up."""
    return STRIP.sub(lambda m: re.sub(r"[^\n]", " ", m.group()), text)


def method_spans(text):
    """(start, end, name) of every method body in one file, found once per file."""
    code = blank(text)
    spans, opens = [], []
    pending = None  # (name, pos) of a declaration whose '{' has not been reached yet
    i, n = 0, len(code)
    line_start = 0
    for lineno, line in enumerate(code.split("\n")):
        stripped = line.strip()
        m = DECL.search(stripped)
        if m and m.group(1) not in NOT_METHOD and "=" not in stripped[:m.start()] and "." not in stripped[max(0, m.start() - 1):m.start()] \
                and not stripped.startswith(("return", "new ", "throw", "@")) and not stripped.endswith(";") \
                and (re.match(r"^(?:[\w<>\[\],.?@]+\s+)+\w+\s*\(", stripped) or stripped.startswith("fun ")):
            pending = (m.group(1), line_start)
        for k, ch in enumerate(line):
            if ch == "{":
                opens.append((line_start + k, pending[0] if pending else None))
                pending = None
            elif ch == "}" and opens:
                start, name = opens.pop()
                if name:
                    spans.append((start, line_start + k, name))
            elif ch == ";":
                pending = None
        line_start += len(line) + 1
    return spans, code


def enclosing(spans, code, offset):
    """Name of the innermost method around offset, or (imports) / (class body)."""
    if not TYPE.search(code[:offset]):
        return "(imports)"
    best = None
    for s, e, name in spans:
        if s <= offset <= e and (best is None or s > best[0]):
            best = (s, name)
    return best[1] if best else "(class body)"


def switches_in(text):
    """Every switch in one file: start offset, branches [(condition or None, code)]."""
    marks = list(MARK.finditer(text))
    out, stack = [], []
    for i, m in enumerate(marks):
        body = m.group("line") if m.group("line") is not None else m.group("block")
        d = directive(body)
        if d is None:
            continue
        if d[0] == "open":
            stack.append({"start": m.start(), "branches": [[d[1], m.end()]], "line": text.count("\n", 0, m.start()) + 1})
        elif d[0] == "else" and stack:
            top = stack[-1]
            top["branches"][-1].append(m.start())
            top["branches"].append([d[1], m.end()])
        elif d[0] == "close" and stack:
            top = stack.pop()
            top["branches"][-1].append(m.start())
            out.append(top)
        elif d[0] == "single":
            # applies to the next line (or the next block comment when the code is inactive)
            rest = text[m.end():]
            nl = rest.find("\n")
            if nl < 0:
                continue
            after = rest[nl + 1:]
            if after.lstrip().startswith("/*"):
                close = after.find("*/")
                code = after[: close + 2] if close >= 0 else after.split("\n", 1)[0]
            else:
                code = after.split("\n", 1)[0]
            out.append({"start": m.start(), "line": text.count("\n", 0, m.start()) + 1,
                        "branches": [[d[1], m.end() + nl + 1, m.end() + nl + 1 + len(code)]], "single": True})
    result = []
    for s in out:
        branches = []
        for b in s["branches"]:
            if len(b) < 3:
                continue
            branches.append({"condition": b[0], "code": clean_branch(text[b[1]:b[2]])})
        if branches:
            result.append({"start": s["start"], "line": s["line"], "single": s.get("single", False), "branches": branches})
    return result


IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
KEYWORDS = set("""abstract boolean break byte case catch char class continue default do double else enum extends final
finally float for if implements import instanceof int interface long new package private protected public return short
static super switch this throw throws try void volatile while var val fun null true false override""".split())


def api_tokens(code):
    code = re.sub(r'"(?:\\.|[^"\\])*"|//[^\n]*', " ", code)
    return {t for t in IDENT.findall(code) if t not in KEYWORDS and len(t) > 2}


def namespace(root):
    yarn = mojmap = 0
    for p in list(root.rglob("*.java"))[:4000]:
        try:
            t = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        yarn += len(re.findall(r"import net\.minecraft\.(?:util\.Identifier|text\.Text|client\.gui\.DrawContext|util\.math\.|server\.network\.ServerPlayerEntity|entity\.player\.PlayerEntity)", t))
        mojmap += len(re.findall(r"import net\.minecraft\.(?:resources\.(?:ResourceLocation|Identifier)|network\.chat\.Component|client\.gui\.GuiGraphics|world\.entity\.player\.Player|server\.level\.ServerPlayer|core\.BlockPos)", t))
    if yarn == mojmap == 0:
        return "unknown"
    return "yarn" if yarn > mojmap else "mojmap"


def loaders(root):
    found = set()
    for p in [root / "settings.gradle.kts", root / "settings.gradle", root / "stonecutter.settings.toml", root / "build.gradle.kts", root / "gradle.properties"]:
        if p.is_file():
            t = p.read_text(encoding="utf-8", errors="replace").lower()
            for name in ("fabric", "neoforge", "forge", "quilt"):
                if re.search(rf"\b{name}\b", t) or (name == "forge" and "minecraftforge" in t):
                    found.add(name)
    vdir = root / "versions"
    if vdir.is_dir():
        for p in vdir.iterdir():
            for name in ("fabric", "neoforge", "forge", "quilt"):
                if re.search(rf"(^|[-_]){name}($|[-_])", p.name):
                    found.add(name)
    if "neoforge" in found and not any((root / d).is_dir() for d in ("forge",)) and "minecraftforge" not in "".join(
            p.read_text(encoding="utf-8", errors="replace") for p in root.glob("*.gradle*") if p.is_file()):
        found.discard("forge")
    return sorted(found)


def step_names():
    """For each neighbouring update, the simple names it changed: old side and new side."""
    names = {}
    for s in INDEX["steps"]:
        d = load(CHANGES / s["file"])
        old, new = set(), set()
        for c in d["classesRemoved"]:
            old.add(c.rsplit(".", 1)[-1].split("$")[-1])
        for c in d["classesAdded"]:
            new.add(c.rsplit(".", 1)[-1].split("$")[-1])
        for a, b in d["classesRenamed"]:
            old.add(a.rsplit(".", 1)[-1].split("$")[-1])
            new.add(b.rsplit(".", 1)[-1].split("$")[-1])
        for owner, lists in d["members"].items():
            for key, items in lists.items():
                for it in items:
                    if it.startswith("private "):
                        continue
                    o, _, n = it.partition(" -> ")
                    on = re.split(r"[(:]", o, 1)[0]
                    nn = re.split(r"[(:]", n, 1)[0] if n else None
                    if key.endswith("Added"):
                        new.add(on)
                    elif key.endswith("Removed"):
                        old.add(on)
                    else:
                        old.add(on)
                        if nn:
                            new.add(nn)
        names[f"{s['from']} -> {s['to']}"] = (old, new)
    return names


def split_sides(branches):
    """(newer code, older code, the comparison that splits them) for a two-sided version switch."""
    if len(branches) < 2:
        return None
    first = branches[0]["condition"] or ""
    comps, consts = parse_condition(first)
    if len(comps) != 1 or consts:
        return None
    op = comps[0]["op"]
    if op in (">=", ">"):
        return branches[0]["code"], branches[1]["code"], comps[0]
    if op in ("<", "<="):
        return branches[1]["code"], branches[0]["code"], comps[0]
    return None


def extract():
    manifest = load(MANIFEST)
    names = step_names()
    (OUT / "switches").mkdir(parents=True, exist_ok=True)
    summary, pairs, transitions = [], defaultdict(list), defaultdict(list)
    for mod in manifest["mods"]:
        root = SRC / slug(mod["repo"])
        if mod.get("skip"):
            (OUT / "switches" / f"{slug(mod['repo'])}.json").unlink(missing_ok=True)
            continue
        if not root.is_dir():
            print("missing checkout, run fetch:", mod["repo"])
            continue
        head = git("rev-parse", "HEAD", cwd=root)
        if mod.get("commit") and head != mod["commit"]:
            raise SystemExit(f"{mod['repo']} is at {head[:10]}, manifest pins {mod['commit'][:10]}; run fetch")
        versions = mod_versions(root)
        ns = namespace(root)
        mod["names"], mod["versions"], mod["loaders"] = ns, versions, loaders(root)
        rows, files, kt = [], 0, 0
        for path in sorted(list(root.rglob("*.java")) + list(root.rglob("*.kt"))):
            rel = path.relative_to(root).as_posix()
            # versions/<v>/src holds generated or per-version copies, not the shared switched source.
            parts = path.relative_to(root).parts
            if parts[0] == "versions" or any(part in ("build", ".gradle", "run") for part in parts):
                continue
            try:
                text = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            if "//?" not in text and "/*?" not in text:
                continue
            found = switches_in(text)
            if not found:
                continue
            spans, code = method_spans(text)
            files += 1
            kt += rel.endswith(".kt")
            for sw in found:
                conds = []
                steps = []
                for b in sw["branches"]:
                    comps, consts = parse_condition(b["condition"] or "")
                    b["versions"] = comps
                    b["constants"] = consts
                    for c in comps:
                        for s in boundary_steps(c["op"], c["version"]):
                            if s not in steps:
                                steps.append(s)
                    conds.append(b["condition"])
                window = window_steps(steps, versions) if steps else []
                row = {"file": rel, "line": sw["line"], "method": enclosing(spans, code, sw["start"]),
                       "condition": " | ".join(c if c is not None else "else" for c in conds),
                       "kind": "version" if any(b["versions"] for b in sw["branches"]) else "constant",
                       "single": sw["single"], "branches": [{"condition": b["condition"], "code": b["code"][:4000]} for b in sw["branches"]],
                       "boundary": steps, "window": window}
                sides = split_sides(sw["branches"])
                if sides and ns != "yarn":
                    newer, older, comp = sides
                    gone, came = api_tokens(older) - api_tokens(newer), api_tokens(newer) - api_tokens(older)
                    confirmed = []
                    for st in window:
                        if st not in names:
                            continue
                        old_names, new_names = names[st]
                        hit = sorted((gone & old_names) | (came & new_names))
                        if hit:
                            confirmed.append({"step": st, "names": hit[:12]})
                    row["confirmed"] = confirmed
                if sides:
                    newer, older, comp = sides
                    gone, came = api_tokens(older) - api_tokens(newer), api_tokens(newer) - api_tokens(older)
                    row["older"], row["newer"] = older[:2000], newer[:2000]
                    row["removed"], row["added"] = sorted(gone)[:40], sorted(came)[:40]
                    if gone and came and len(gone) <= 4 and len(came) <= 4:
                        for g in gone:
                            for a in came:
                                transitions[(ns, g, a)].append({"mod": mod["repo"], "file": rel, "line": sw["line"], "boundary": steps})
                    if (gone or came) and len(gone) <= 8 and len(came) <= 8:
                        key = (ns, " ".join(sorted(gone)), " ".join(sorted(came)))
                        pairs[key].append({"mod": mod["repo"], "file": rel, "line": sw["line"], "method": row["method"], "condition": conds[0],
                                           "boundary": steps, "older": older[:600], "newer": newer[:600]})
                rows.append(row)
        version_rows = sum(1 for r in rows if r["kind"] == "version")
        write(OUT / "switches" / f"{slug(mod['repo'])}.json", {
            "repo": mod["repo"], "commit": head, "names": ns, "loaders": mod["loaders"], "versions": versions,
            "files": files, "switches": len(rows), "versionSwitches": version_rows, "rows": rows})
        summary.append({"repo": mod["repo"], "commit": head[:12], "names": ns, "loaders": mod["loaders"], "versions": versions,
                        "files": files, "switches": len(rows), "versionSwitches": version_rows,
                        "twoSided": sum(1 for r in rows if "older" in r)})
        print(f"{mod['repo']}: {len(rows)} switches ({version_rows} on versions) in {files} files, {ns}")
    write(MANIFEST, manifest)
    # Repeated pairs: the same token change in two or more mods.
    repeated = []
    for (ns, gone, came), items in pairs.items():
        mods = sorted({i["mod"] for i in items})
        if len(mods) < 2:
            continue
        steps = defaultdict(int)
        for i in items:
            for s in i["boundary"]:
                steps[s] += 1
        repeated.append({"names": ns, "removed": gone.split(), "added": came.split(), "mods": mods, "sites": len(items),
                         "boundaries": dict(sorted(steps.items(), key=lambda x: -x[1])), "examples": items[:6]})
    repeated.sort(key=lambda r: (-len(r["mods"]), -r["sites"]))
    for n, r in enumerate(repeated):
        r["id"] = f"pair-{n + 1:03d}"
    # Looser: one identifier swapped for another inside small switches, counted by distinct mods.
    swaps = []
    for (ns, g, a), items in transitions.items():
        mods = sorted({i["mod"] for i in items})
        if len(mods) < 3:
            continue
        steps = defaultdict(int)
        for i in items:
            for s in i["boundary"]:
                steps[s] += 1
        swaps.append({"names": ns, "older": g, "newer": a, "mods": mods, "sites": len(items),
                      "boundaries": dict(sorted(steps.items(), key=lambda x: -x[1])[:4]), "examples": items[:4]})
    swaps.sort(key=lambda r: (-len(r["mods"]), -r["sites"]))
    write(OUT / "pairs.json", {"note": "pairs: two-sided version switches whose token change (identifiers only in the older branch -> only in the newer) repeats in two or more mods. swaps: one identifier replaced by another inside small switches (at most four changed on each side), in three or more mods; looser, so some are coincidences. Generated by bench/stonecutter_mine.py extract.",
                               "pairs": repeated, "swaps": swaps[:150]})
    per_step = defaultdict(lambda: {"switches": 0, "mods": set(), "confirmed": 0})
    for m in manifest["mods"]:
        p = OUT / "switches" / f"{slug(m['repo'])}.json"
        if not p.is_file():
            continue
        for r in load(p)["rows"]:
            for s in r["boundary"]:
                per_step[s]["switches"] += 1
                per_step[s]["mods"].add(m["repo"])
            for c in r.get("confirmed", []):
                per_step[c["step"]]["confirmed"] += 1
    write(OUT / "index.json", {
        "about": "Stonecutter switches mined from real multi-version mods (coverage/stonecutter/mods.json). Each switch is one human-written example: this code on one side of a version boundary, that code on the other.",
        "generated": date.today().isoformat(),
        "mods": summary,
        "totals": {"mods": len(summary), "switches": sum(s["switches"] for s in summary), "versionSwitches": sum(s["versionSwitches"] for s in summary),
                   "twoSided": sum(s["twoSided"] for s in summary), "repeatedPairs": len(repeated)},
        "boundary": "A comparison >=V or <V flips on the update into V; >V or <=V on the update out of V. window is every update between the mod's nearest supported releases either side, because a mod that skips releases cannot say which one changed the API. confirmed lists the updates in that window whose change file (coverage/changes) names an identifier that differs between the branches (Mojang-named mods only).",
        "steps": {s: {"switches": v["switches"], "mods": len(v["mods"]), "confirmed": v["confirmed"]} for s, v in sorted(per_step.items(), key=lambda x: [vkey(p) for p in x[0].split(" -> ")])},
    })


def report():
    """Print the totals and the repeated pairs, for reading the mined data at a glance."""
    print(load(OUT / "index.json")["totals"])
    for r in load(OUT / "pairs.json")["pairs"]:
        print(r["id"], len(r["mods"]), r["sites"], r["names"], "|", " ".join(r["removed"]), "=>", " ".join(r["added"]), "|", list(r["boundaries"])[:3])
    for r in load(OUT / "pairs.json")["swaps"]:
        print("swap", len(r["mods"]), r["sites"], r["names"], "|", r["older"], "=>", r["newer"], "|", list(r["boundaries"])[:3])


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "extract"
    {"pin": pin, "fetch": fetch, "extract": extract, "report": report}[cmd]()
