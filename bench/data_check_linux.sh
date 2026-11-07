#!/usr/bin/env bash
# data-check on the Prism Lab demo mod on a clean Ubuntu machine (boat.dev): clean, then a broken recipe and the fix,
# both read by the game data-check keeps loaded.
# From the repo root: boat run --timeout 2400 --push . -- bash bench/data_check_linux.sh   (exit 0 = clean and fixed passed, broken failed)
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
echo '{"type":"minecraft:crafting_shaped","pattern":["#"],"key":{"#":"prismlab:no_such_item"},"result":{"id":"prismlab:prism_shard"}}' > build/resources/main/data/prismlab/recipe/broken_probe.json
java -jar build/distributions/modaccel.jar data-check --root . --json >/tmp/broken.json 2>/tmp/broken.err; broken=$?
echo "== broken recipe, exit $broken"; summary /tmp/broken.json || tail -40 /tmp/broken.err
rm build/resources/main/data/prismlab/recipe/broken_probe.json
java -jar build/distributions/modaccel.jar data-check --root . --json >/tmp/fixed.json 2>/tmp/fixed.err; fixed=$?
echo "== recipe removed, exit $fixed"; summary /tmp/fixed.json || tail -40 /tmp/fixed.err
java -jar build/distributions/modaccel.jar game-stop --root . --json >/dev/null 2>&1
[ "$clean" = 0 ] && [ "$broken" = 1 ] && [ "$fixed" = 0 ]
