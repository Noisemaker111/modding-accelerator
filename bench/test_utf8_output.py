"""Every Python helper writes UTF-8, whatever the console or PYTHONIOENCODING says.

Helpers print arrows, dots and mod names; a Windows console (cp1252) or PYTHONIOENCODING=ascii would make print raise
UnicodeEncodeError half way through a table. Each script with a __main__ block reconfigures stdout to UTF-8 first.
"""
import os
import pathlib
import re
import subprocess
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent


def helpers():
    tracked = subprocess.check_output(["git", "ls-files", "*.py"], cwd=ROOT, text=True).split()
    for name in tracked:
        path = ROOT / name
        if path.name.startswith("test_") or not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        if re.search(r'^if __name__ == ["\']__main__["\']:', text, re.M):
            yield name, text


class Utf8Output(unittest.TestCase):
    def test_every_helper_reconfigures_stdout(self):
        missing = [name for name, text in helpers() if 'reconfigure(encoding="utf-8")' not in text]
        self.assertEqual(missing, [], "helpers that print in the console's encoding")

    def test_reconfigure_wins_over_pythonioencoding(self):
        code = ('import sys\nsys.stdout.reconfigure(encoding="utf-8")\nprint("a → b · c")\n')
        env = dict(os.environ, PYTHONIOENCODING="ascii")
        out = subprocess.run([sys.executable, "-c", code], env=env, capture_output=True, check=True).stdout
        self.assertEqual(out.decode("utf-8").strip(), "a → b · c")


if __name__ == "__main__":
    unittest.main()
