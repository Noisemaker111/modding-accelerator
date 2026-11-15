#!/usr/bin/env bash
# DIAG: raw JFR ExecutionSample yield per JDK/flags/load, no modaccel involved. Expected = 100 samples/s per busy thread at 10 ms.
set -u
cd diag
J17="${JAVA_HOME_17_arm64:-${JAVA_HOME_17_X64:-}}"; J21="${JAVA_HOME_21_arm64:-${JAVA_HOME_21_X64:-}}"; J25="$JAVA_HOME"
cat > period10.jfc <<'X'
<?xml version="1.0" encoding="UTF-8"?>
<configuration version="2.0">
  <event name="jdk.ExecutionSample"><setting name="enabled">true</setting><setting name="period">10 ms</setting></event>
  <event name="jdk.NativeMethodSample"><setting name="enabled">true</setting><setting name="period">20 ms</setting></event>
  <event name="jdk.ThreadCPULoad"><setting name="enabled">true</setting><setting name="period">1 s</setting></event>
</configuration>
X
"$J25/bin/javac" -d . Spin.java
echo "uname=$(uname -sm) cpus=$(getconf _NPROCESSORS_ONLN)"
run() { # label jdk threads busy sleep opts...
  local label=$1 jdk=$2 th=$3 busy=$4 sl=$5; shift 5
  for rep in 1 2 3; do
    rm -f y.jfr
    "$jdk/bin/java" "$@" -XX:StartFlightRecording=filename=y.jfr,settings=$PWD/period10.jfc -cp . Spin $th $busy $sl 4 ${CLOCK:-} >/dev/null 2>&1
    n=$("$J25/bin/jfr" summary y.jfr 2>/dev/null | awk '$1=="jdk.ExecutionSample"{print $2}')
    echo "YIELD $label threads=$th busy=$busy sleep=$sl opts='$*' clock=${CLOCK:-no} rep=$rep exec=${n:-NA} expected~$(( th * 400 * busy / (busy + sl) ))"
  done
}
for v in 17 21 25; do
  eval jdk=\$J$v
  run jdk$v "$jdk" 1 1000 0
  run jdk$v "$jdk" 2 1000 0
  run jdk$v "$jdk" 1 20 27
  run jdk$v "$jdk" 1 1000 0 -Xint
  run jdk$v "$jdk" 1 1000 0 -XX:TieredStopAtLevel=1
  run jdk$v "$jdk" 1 1000 0 -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints
  run jdk$v "$jdk" 1 1000 0 -XX:+PreserveFramePointer
  CLOCK=clock run jdk$v "$jdk" 1 1000 0
done
