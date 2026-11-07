#!/usr/bin/env bash
# The real-game behaviour checks on Linux with no screen: AppleSkin's HUD estimate (Fabric 1.21.6) and Zombie
# Apocalypse's AI (NeoForge, ported 1.21.1 -> 1.21.4 by modaccel port). Each check passes on the mod as it is and must
# fail on a copy broken on purpose. The client runs hidden and quiet on a virtual display (Xvfb) with Mesa's software
# OpenGL, so it needs no GPU. Not in cloud CI until Jon approves that spending: the nightly job is
# bench/behaviour-nightly.patch. By hand, from the repo
# root (Git Bash on Jon's PC):
#   fairshare lease boat -- ~/.ascii/bin/boat.exe run --no-env --timeout 3600 --push . -- bash bench/behaviour_checks.sh
# Exit 0 = both checks passed and both breaks failed. Prints one "behaviour-checks: N mods" line for the measure.
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

if clone https://github.com/Riqqqque/ZombieApocalypseAddon main 1bb67d4a487f57c3f005c73f35ebf60b1370c684 "$W/zombie"; then
  t=$(date +%s)
  java -jar "$JAR" port --root "$W/zombie" --from 1.21.1 --to 1.21.4 --fix --json >"$W/zombie-port.json" 2>"$W/zombie-port.err"; port=$?
  echo "== zombie port 1.21.1 -> 1.21.4: exit $port in $(( $(date +%s) - t )) s"
  [ $port = 0 ] || { tail -c 3000 "$W/zombie-port.json"; tail -20 "$W/zombie-port.err"; }
  [ $port = 0 ] && run zombie java tools/tests/ZombieAiCheck.java . "$JAR" "$W/zombie" && passed=$((passed + 1))
  java -jar "$JAR" game-stop --root "$W/zombie" >/dev/null 2>&1
fi
echo "behaviour-checks: $passed mods, $(( ($(date +%s) - start) / 60 )) min"
[ $passed = 2 ]
