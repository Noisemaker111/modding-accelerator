#!/usr/bin/env bash
# data-check on the Prism Lab demo mod on a clean Ubuntu machine (boat.dev): clean; a broken recipe under src and the
# fix, copied without Gradle and read by the loaded game; then a new class, taken by the spare game.
# From the repo root (Git Bash on Jon's PC):
#   fairshare lease boat -- ~/.ascii/bin/boat.exe run --no-env --timeout 2400 --push . -- bash bench/data_check_linux.sh
# Exit 0 = clean and fixed passed, broken failed, and the spare took the changed code.
set -u
S=; command -v sudo >/dev/null && S=sudo
$S apt-get update -qq >/tmp/apt.log 2>&1 && $S apt-get install -y -qq openjdk-21-jdk-headless >>/tmp/apt.log 2>&1 || { tail -20 /tmp/apt.log; exit 9; }
java tools/cli/Bootstrap.java . package --json >/tmp/package.log 2>&1 || { tail -40 /tmp/package.log; exit 8; }
summary() {
  python3 - "$1" <<'EOF'
import json, sys
d = json.load(open(sys.argv[1])); r = d.get("result", d)
print("ok:", d.get("ok"), "| seconds:", round(d.get("seconds", 0)), "| CPU-s:", r.get("cpuSeconds"), "| first in JVM:", r.get("firstInJvm"), "|", r.get("game"))
print(r.get("details") or r.get("reason") or json.dumps(d)[:3000])
if not d.get("ok") and not r.get("details"):
    print(json.dumps(d)[:3000])
EOF
}
java -jar build/distributions/modaccel.jar data-check --root . --json >/tmp/clean.json 2>/tmp/clean.err; clean=$?
echo "== clean run, exit $clean"; summary /tmp/clean.json || tail -40 /tmp/clean.err
R=src/main/resources/data/prismlab/recipe/broken_probe.json
echo '{"type":"minecraft:crafting_shaped","pattern":["#"],"key":{"#":"prismlab:no_such_item"},"result":{"id":"prismlab:prism_shard"}}' > $R
java -jar build/distributions/modaccel.jar data-check --root . --json >/tmp/broken.json 2>/tmp/broken.err; broken=$?
echo "== broken recipe under src, exit $broken"; summary /tmp/broken.json || tail -40 /tmp/broken.err
python3 -c "import json;print('stages:', [s['name'] for s in json.load(open('/tmp/broken.json'))['stages']])"
rm $R
java -jar build/distributions/modaccel.jar data-check --root . --json >/tmp/fixed.json 2>/tmp/fixed.err; fixed=$?
echo "== recipe removed, exit $fixed"; summary /tmp/fixed.json || tail -40 /tmp/fixed.err
# The spare starts behind the loaded game; a class it has not read yet is code it can take.
for i in $(seq 1 120); do [ -f .modaccel/portable/data-check-spare.json ] && break; sleep 1; done
echo "== spare ready after about $i s: $(cat .modaccel/portable/data-check-spare.json 2>/dev/null)"
mkdir -p /tmp/junk && printf 'package com.prismlab;\nfinal class ModaccelJunk { }\n' > /tmp/junk/ModaccelJunk.java
javac --release 21 -d build/classes/java/main /tmp/junk/ModaccelJunk.java
java -jar build/distributions/modaccel.jar data-check --root . --json >/tmp/code.json 2>/tmp/code.err; code=$?
echo "== new class, exit $code"; summary /tmp/code.json || tail -40 /tmp/code.err
grep -q 'loaded in advance' /tmp/code.json; spare=$?
rm -f build/classes/java/main/com/prismlab/ModaccelJunk.class
java -jar build/distributions/modaccel.jar game-stop --root . --json >/dev/null 2>&1
[ "$clean" = 0 ] && [ "$broken" = 1 ] && [ "$fixed" = 0 ] && [ "$code" = 0 ] && [ "$spare" = 0 ]
