#!/usr/bin/env bash
# DIAG: raw JFR ExecutionSample yield per JDK/flags/QoS, no modaccel involved. Expected = 100 samples/s per busy thread at 10 ms.
set -u
cd diag
J17="${JAVA_HOME_17_arm64:-${JAVA_HOME_17_X64:-}}"; J21="${JAVA_HOME_21_arm64:-${JAVA_HOME_21_X64:-}}"; J25="$JAVA_HOME"
"$J25/bin/javac" -d . Spin.java SleepJitter.java
echo "uname=$(uname -sm) cpus=$(getconf _NPROCESSORS_ONLN)"
MAC=0; [ "$(uname -s)" = Darwin ] && MAC=1
if [ $MAC = 1 ]; then
  echo "ps of runner:"; ps -o pid,pri,ni,command -p $$ -p $PPID | cat
  taskpolicy -G -p $$ 2>&1 | head -5 || true
fi
for v in 17 25; do eval jdk=\$J$v; "$jdk/bin/java" -cp . SleepJitter plain; done
if [ $MAC = 1 ]; then
  for v in 17 25; do eval jdk=\$J$v; taskpolicy -l 0 -t 0 "$jdk/bin/java" -cp . SleepJitter "taskpolicy-l0-t0"; done
fi
run() { # label prefixcmd jdk threads busy sleep opts...
  local label=$1 pre=$2 jdk=$3 th=$4 busy=$5 sl=$6; shift 6
  for rep in 1 2 3; do
    rm -f y.jfr err.txt
    $pre "$jdk/bin/java" "$@" -XX:StartFlightRecording=filename=y.jfr,settings=profile -cp . Spin $th $busy $sl 4 >/dev/null 2>err.txt
    n=$("$J25/bin/jfr" summary y.jfr 2>/dev/null | awk '$1=="jdk.ExecutionSample"{print $2}')
    echo "YIELD $label threads=$th busy=$busy sleep=$sl opts='$*' rep=$rep exec=${n:-NA} expected~$(( th * 400 * busy / (busy + sl) ))"
    [ -z "$n" ] && tail -3 err.txt
  done
}
for v in 17 21 25; do
  eval jdk=\$J$v
  run jdk$v "" "$jdk" 1 1000 0
  run jdk$v "" "$jdk" 1 1000 0 -Xint
  run jdk$v "" "$jdk" 1 20 27
  if [ $MAC = 1 ]; then
    run jdk$v-taskpolicy "taskpolicy -l 0 -t 0" "$jdk" 1 1000 0
    run jdk$v-taskpolicy "taskpolicy -l 0 -t 0" "$jdk" 1 20 27
  fi
done
