#!/usr/bin/env bash
# The real-game behaviour checks on Linux with no screen: AppleSkin's HUD estimate (Fabric 1.21.6), Zombie
# Apocalypse's AI (NeoForge, ported 1.21.1 -> 1.21.6 by modaccel port) and Notes' pinned note (NeoForge, ported
# 1.21 -> 1.21.6). Each check passes on the mod as it is and must fail on a copy broken on purpose. The client runs hidden and quiet on a virtual display (Xvfb) with Mesa's software
# OpenGL, so it needs no GPU. Run it when a port reaches the game or the game-check code changes; it is not a
# scheduled job, since nothing it tests changes between runs. From the repo root on any Linux machine (the boat T3
# host runs it directly in about 15 minutes): bash bench/behaviour_checks.sh. From Jon's PC, on a boat machine:
#   ~/.ascii/bin/boat.exe run --no-env --timeout 3600 --push . -- bash bench/behaviour_checks.sh
# Exit 0 = every check passed and every break failed. Prints one "behaviour-checks: N mods" line for the measure.
set -u
S=; command -v sudo >/dev/null && S=sudo
need=""
javac -version 2>&1 | grep -Eq 'javac (1[7-9]|[2-9][0-9])' || need="$need openjdk-21-jdk-headless"
command -v xvfb-run >/dev/null || need="$need xvfb xauth"
[ -e /usr/lib/x86_64-linux-gnu/dri/swrast_dri.so ] || [ -e /usr/lib/x86_64-linux-gnu/libGLX_mesa.so.0 ] || need="$need libgl1-mesa-dri libglx-mesa0"
if [ -n "$need" ]; then
  $S apt-get update -qq >/tmp/apt.log 2>&1 && $S apt-get install -y -qq $need >>/tmp/apt.log 2>&1 || { tail -20 /tmp/apt.log; exit 9; }
fi
start=$(date +%s)
[ -d /usr/lib/jvm/java-21-openjdk-amd64 ] && ! javac -version 2>&1 | grep -Eq "javac (1[7-9]|[2-9][0-9])" && export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH
java tools/cli/Bootstrap.java . package --json >/tmp/package.log 2>&1 || { tail -40 /tmp/package.log; exit 8; }
JAR=$PWD/build/distributions/modaccel.jar
java -jar "$JAR" preferences --background on --quiet on >/dev/null
W=${BEHAVIOUR_WORK:-/tmp/behaviour}
mkdir -p "$W"

clone() {  # clone REPO BRANCH COMMIT DIR
  [ -d "$4/.git" ] || git clone -q --filter=blob:none -b "$2" "$1" "$4" || return 1
  git -C "$4" checkout -q "$3"
}
run() {  # run NAME COMMAND...: under one virtual display at the capture size
  local name=$1; shift
  local t=$(date +%s)
  xvfb-run -a -s "-screen 0 1280x720x24" "$@" >"$W/$name.log" 2>&1; local exit=$?
  echo "== $name: exit $exit in $(( $(date +%s) - t )) s"
  grep -E '^(PASS|FAIL)|AssertionError|Exception' "$W/$name.log" | head -12
  [ $exit = 0 ] || tail -40 "$W/$name.log"
  return $exit
}

passed=0
clone https://github.com/squeek502/AppleSkin 1.21.6-fabric a1c60fccba094969ec2de200ab4a9408164d1c93 "$W/appleskin" \
  && run appleskin java tools/tests/AppleSkinHudCheck.java . "$JAR" "$W/appleskin" && passed=$((passed + 1))
java -jar "$JAR" game-stop --root "$W/appleskin" >/dev/null 2>&1

ported() {  # ported NAME REPO BRANCH COMMIT FROM TO CHECK: modaccel port --fix, then the mod's own check
  local name=$1 dir="$W/$1"
  clone "$2" "$3" "$4" "$dir" || return 1
  local t=$(date +%s)
  java -jar "$JAR" port --root "$dir" --from "$5" --to "$6" --fix --json >"$W/$name-port.json" 2>"$W/$name-port.err"; local port=$?
  echo "== $name port $5 -> $6: exit $port in $(( $(date +%s) - t )) s"
  [ $port = 0 ] || { tail -c 3000 "$W/$name-port.json"; tail -20 "$W/$name-port.err"; }
  [ $port = 0 ] && run "$name" java "$7" . "$JAR" "$dir"; local ok=$?
  java -jar "$JAR" game-stop --root "$dir" >/dev/null 2>&1
  [ $port = 0 ] && [ $ok = 0 ]
}
# Notes ports first: it sets up NeoForge 1.21.6 once, and the zombie port reuses that setup (bench/neoform_steps.py).
ported notes https://github.com/MattCzyr/Notes neoforge-1.21.1 d1e9c391adf9249feb059b87499e226ab2c7882d 1.21 1.21.6 tools/tests/NotesPinCheck.java && passed=$((passed + 1))
ported zombie https://github.com/Riqqqque/ZombieApocalypseAddon main 1bb67d4a487f57c3f005c73f35ebf60b1370c684 1.21.1 1.21.6 tools/tests/ZombieAiCheck.java && passed=$((passed + 1))
echo "behaviour-checks: $passed mods, $(( ($(date +%s) - start) / 60 )) min"
[ $passed = 3 ]
