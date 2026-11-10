"""Give a port rule its test from real mod source, so no one writes {error, before, after} by hand.

    python bench/rule_test.py                 every rule in knowledge/fixes.json that has no test: say what would be written
    python bench/rule_test.py --only ID,ID    only these rules
    python bench/rule_test.py --write         write the tests into knowledge/fixes.json
    python bench/rule_test.py --source DIR:yarn:"My mod 1.20.1 at 1a2b3c4d"    search this checkout too (yarn or mojmap names)
    python bench/rule_test.py --only ID --after OTHER_ID    for a rule of a later round: OTHER_ID rewrites each file first

For each rule it searches the pinned mods of bench/port-cases.json (each at its pinned commit, whatever the
clone's working tree holds) for the first code the rule's find matches, cuts out the matched lines with one line
either side, and runs the porter's own rewrite on that. The test is real code before and what the porter makes
of it after, with "from" naming the mod, commit, file and line. The compiler error is worded from the rule's
match, not taken from a build. PortableTests then holds the rule to it on every self-test.

A rule nothing matches is listed with the reason. Pass the checkout of the mod it was written for with --source,
and put the commit in its label so the test can be traced. A rule that only applies once another rule has run
(its "when" looks for what that rule writes) needs --after naming that rule. A rule with no test fails self-test.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIXES = ROOT / "knowledge" / "fixes.json"
CASES = ROOT / "bench" / "port-cases.json"
CACHE = ROOT / ".runs" / "port-board"


def sh(cmd, cwd=None, **kw):
    return subprocess.run([str(c) for c in cmd], cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace", **kw)


# ---- an example of what a regex matches -----------------------------------------------------------
class Sampler:
    """One string a (Java) regex matches, for the simple patterns rules use on compiler errors. Raises ValueError on anything else."""

    ESC = {"s": " ", "S": "x", "w": "x", "W": " ", "d": "1", "D": "x", "n": "\n", "t": "\t", "r": "", "b": "", "B": "", "R": "\n", "h": " "}

    def __init__(self, pattern):
        self.p, self.i = pattern, 0

    def sample(self):
        out = self.alternation()
        if self.i != len(self.p):
            raise ValueError(f"stopped at {self.i}")
        return out

    def alternation(self):
        first = self.sequence()
        while self.i < len(self.p) and self.p[self.i] == "|":
            self.i += 1
            self.sequence()     # the first branch is the example; the others are only read past
        return first

    def sequence(self):
        out = []
        while self.i < len(self.p) and self.p[self.i] not in "|)":
            out.append(self.quantified())
        return "".join(out)

    def quantified(self):
        atom = self.atom()
        if self.i < len(self.p) and self.p[self.i] in "*+?":
            q = self.p[self.i]
            self.i += 1
            if self.i < len(self.p) and self.p[self.i] in "?+":
                self.i += 1
            return "" if q == "*" else atom
        if self.i < len(self.p) and self.p[self.i] == "{":
            end = self.p.index("}", self.i)
            low = int(self.p[self.i + 1:end].split(",")[0] or 0)
            self.i = end + 1
            return atom * low
        return atom

    def atom(self):
        c = self.p[self.i]
        if c == "(":
            self.i += 1
            look = False
            if self.p.startswith("?", self.i):
                if self.p.startswith(("?=", "?!", "?<=", "?<!"), self.i):
                    look = True
                    self.i += 3 if self.p.startswith(("?<=", "?<!"), self.i) else 2
                elif self.p.startswith("?<", self.i):
                    self.i = self.p.index(">", self.i) + 1
                elif self.p.startswith("?:", self.i):
                    self.i += 2
                else:                       # inline flags: (?s) or (?m)
                    end = self.p.index(")", self.i)
                    self.i = end + 1
                    return ""
            inner = self.alternation()
            if self.i >= len(self.p) or self.p[self.i] != ")":
                raise ValueError("unclosed group")
            self.i += 1
            return "" if look else inner
        if c == "[":
            end = self.i + 1
            if self.p.startswith("^", end):
                end += 1
            if self.p.startswith("]", end):
                end += 1
            while self.p[end] != "]":
                end += 2 if self.p[end] == "\\" else 1
            body = self.p[self.i + 1:end]
            self.i = end + 1
            if body.startswith("^"):
                return "x" if "x" not in body else "y"
            return self.ESC.get(body[1], body[1]) if body.startswith("\\") else body[0]
        if c == "\\":
            n = self.p[self.i + 1]
            self.i += 2
            return self.ESC.get(n, n)
        self.i += 1
        return "" if c in "^$" else "x" if c == "." else c


def sample(pattern):
    try:
        return " ".join(Sampler(pattern).sample().split(" ")) if pattern else ""
    except (ValueError, IndexError):
        return ""


# ---- the pinned sources -----------------------------------------------------------------------------
def pinned_sources(tmp, cache=CACHE):
    """Each case's source exactly as pinned, unpacked from its clone's history (the working tree may hold a port)."""
    out, seen = [], set()
    for c in json.loads(CASES.read_text(encoding="utf-8"))["cases"]:
        if c["commit"] in seen:
            continue
        seen.add(c["commit"])
        clone = cache / c["id"]
        if not (clone / ".git").exists():
            clone.mkdir(parents=True, exist_ok=True)
            sh(["git", "init", "-q"], cwd=clone)
        if sh(["git", "cat-file", "-e", c["commit"] + "^{commit}"], cwd=clone).returncode:
            r = sh(["git", "fetch", "-q", "--depth", "1", c["repo"], c["commit"]], cwd=clone)
            if r.returncode:
                print(f"skipping {c['id']}: git fetch failed ({r.stderr.strip()[-120:]})", file=sys.stderr)
                continue
        dest = tmp / c["id"]
        dest.mkdir(parents=True)
        tar = subprocess.run(["git", "archive", "--format=tar", c["commit"]], cwd=clone, capture_output=True)
        if tar.returncode or subprocess.run(["tar", "-x", "-C", str(dest)], input=tar.stdout).returncode:
            print(f"skipping {c['id']}: could not unpack {c['commit'][:8]}", file=sys.stderr)
            continue
        out.append({"dir": str(dest), "names": "yarn" if c["loader"] == "fabric" else "mojmap",
                    "label": f"{c['mod']} {c['loader']} {c['from']} at {c['commit'][:8]}"})
    return out


# ---- the porter's own rewrite ---------------------------------------------------------------------
def run_examples(request, tmp):
    """Package this checkout's modaccel, compile tools/tests/RuleExamples.java against it and run it on the request."""
    boot = sh(["java", ROOT / "tools" / "cli" / "Bootstrap.java", ROOT, "package", "--json"])
    jar = ROOT / "build" / "distributions" / "modaccel.jar"
    if boot.returncode or not jar.is_file():
        raise RuntimeError("could not package modaccel: " + " ".join((boot.stderr or boot.stdout).split())[-300:])
    classes = tmp / "classes"
    classes.mkdir()
    c = sh(["javac", "-encoding", "UTF-8", "-cp", jar, "-d", classes, ROOT / "tools" / "tests" / "RuleExamples.java"])
    if c.returncode:
        raise RuntimeError("RuleExamples.java does not compile: " + c.stderr[-600:])
    req, res = tmp / "request.json", tmp / "response.json"
    req.write_text(json.dumps(request, ensure_ascii=False), encoding="utf-8")
    run = sh(["java", "-cp", f"{jar}{os.pathsep}{classes}", "RuleExamples", req, res])
    if run.returncode:
        raise RuntimeError("RuleExamples failed: " + run.stderr[-600:])
    return json.loads(res.read_text(encoding="utf-8"))


# ---- writing a test into the rule file, leaving every other byte alone ---------------------------
def insert_test(text, rule_id, test):
    """knowledge/fixes.json with "test" added as the last key of one rule. The file is edited as text so its layout stays."""
    start = text.index(f'\n      "id": {json.dumps(rule_id)},')
    end = text.index("\n    }", start)                   # a rule closes at this indent; nothing inside it does
    body = ",\n      \"test\": " + json.dumps(test, indent=2, ensure_ascii=False).replace("\n", "\n      ")
    return text[:end] + body + text[end:]


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", help="comma-separated rule ids")
    ap.add_argument("--write", action="store_true", help="write the tests; without it nothing is changed")
    ap.add_argument("--source", action="append", default=[], metavar="DIR:NAMES:LABEL", help="another checkout to search: its folder, yarn or mojmap, and what to call it")
    ap.add_argument("--after", help="comma-separated rule ids an earlier round runs first; each file is searched as they leave it")
    args = ap.parse_args(argv)
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")

    text = FIXES.read_text(encoding="utf-8")
    every = {r["id"]: r for r in json.loads(text)["rules"]}
    rules = [r for r in every.values() if not r.get("test") and not r.get("examples")]
    after = [x.strip() for x in (args.after or "").split(",") if x.strip()]
    if [x for x in after if x not in every]:
        print(f"--after names no rule: {', '.join(x for x in after if x not in every)}", file=sys.stderr)
        return 2
    if args.only:
        want = [x.strip() for x in args.only.split(",")]
        rules = [r for r in rules if r["id"] in want]
        gone = [x for x in want if x not in {r["id"] for r in rules}]
        if gone:
            print(f"Not a rule without a test: {', '.join(gone)}", file=sys.stderr)
            return 2
    if not rules:
        print("Every rule has a test.")
        return 0
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
        tmp = Path(tmp)
        sources = []
        for s in args.source:
            parts = s.rsplit(":", 2)
            if len(parts) != 3 or parts[1] not in ("yarn", "mojmap"):
                print(f"--source wants DIR:yarn|mojmap:LABEL, got {s}", file=sys.stderr)
                return 2
            sources.append({"dir": str(Path(parts[0]).resolve()), "names": parts[1], "label": parts[2]})
        sources += pinned_sources(tmp / "pinned")
        try:
            answer = run_examples({"rules": rules, "samples": {r["id"]: sample(r.get("match", "")) for r in rules}, "sources": sources,
                                   "after": [every[x] for x in after]}, tmp)
        except RuntimeError as e:
            print(f"rule_test: {e}", file=sys.stderr)
            return 1

    for hit in answer["found"]:
        test = {"error": hit["error"], "before": hit["before"], "after": hit["after"],
                "from": f"{hit['label']}, {hit['file']}:{hit['line']}" + (f", as {' and '.join(after)} left it" if after else "")}
        print(f"{hit['id']}: {test['from']}")
        if args.write:
            text = insert_test(text, hit["id"], test)
    for miss in answer["missed"]:
        print(f"{miss['id']}: no test, {miss['why']}")
    if args.write and answer["found"]:
        json.loads(text)                                # it must still be JSON before it replaces the file
        FIXES.write_text(text, encoding="utf-8", newline="\n")
    print(f"{len(answer['found'])} of {len(rules)} rules {'got' if args.write else 'would get'} a test from real source; {len(answer['missed'])} still need one written by hand."
          + ("" if args.write else " Nothing was written; pass --write."))
    return 0


if __name__ == "__main__":
    sys.exit(main())
