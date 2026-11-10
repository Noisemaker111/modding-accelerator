"""No line of tools/cli is over 200 characters, unless it holds a long string the formatter cannot break.

tools/cli was written with whole methods on one line (the longest was 781 characters), so two people who
touched the same line conflicted and a diff could not be read. python bench/format_java.py fixed that; this
keeps it fixed with no formatter installed: it only reads the files.

    python bench/test_java_lines.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LIMIT = 200
LONG_STRING = re.compile(r'"(?:[^"\\]|\\.){100,}"')   # help text and messages: one literal, however long

bad = []
files = sorted((ROOT / "tools" / "cli").glob("*.java"))
for f in files:
    for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
        if len(line) > LIMIT and not LONG_STRING.search(line):
            bad.append(f"{f.relative_to(ROOT).as_posix()}:{n} is {len(line)} characters")
if bad:
    print(f"{len(bad)} line(s) over {LIMIT} characters. Write one statement per line, or run python bench/format_java.py:\n  " + "\n  ".join(bad[:20]))
    sys.exit(1)
print(f"{len(files)} files in tools/cli: no line over {LIMIT} characters except long strings")
