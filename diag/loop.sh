#!/usr/bin/env bash
# DIAG ONLY: loop the perf suite for a fixed wall time and print per-capture JFR sample counts.
set -u
variant="$1"   # 17 | 21 | 25
minutes="${2:-20}"
case "$variant" in
  17) unset DIAG_FIXTURE_JDK ;;
  21) export DIAG_FIXTURE_JDK="${JAVA_HOME_21_arm64:-${JAVA_HOME_21_X64:-}}" ;;
  25) export DIAG_FIXTURE_JDK="$JAVA_HOME" ;;
esac
export DIAG_EXTRA=1
echo "os=$(uname -sm) cpus=$(getconf _NPROCESSORS_ONLN) fixture override=${DIAG_FIXTURE_JDK:-none} opts=${DIAG_FIXTURE_OPTS:-none}"
java tools/cli/Bootstrap.java . package --json > package.json 2>&1 || { tail -30 package.json; exit 1; }
deadline=$(( $(date +%s) + minutes * 60 ))
pass=0; fail=0; i=0
while [ "$(date +%s)" -lt "$deadline" ]; do
  i=$((i+1)); start=$(date +%s)
  if java -jar build/distributions/modaccel.jar self-test --root . --only perf --json > out.json 2>&1; then r=PASS; pass=$((pass+1)); else r=FAIL; fail=$((fail+1)); fi
  log=$(ls -t .runs/portable/*/*perf-tests.log | head -1)
  echo "REP $i $r $(( $(date +%s) - start ))s"
  grep -h "DIAG\|AssertionError\|Exception\|performance checks passed" "$log" | head -12 | sed 's/^/  /'
done
echo "SUMMARY variant=$variant opts=${DIAG_FIXTURE_OPTS:-none} pass=$pass fail=$fail"
