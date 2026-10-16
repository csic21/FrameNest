#!/usr/bin/env bash
# Prepared only. New exact runtime/KVM approval is required before enabling the workflow.
# One retained APK pair, one disposable API35 AVD, one explicitly selected test method.
set -euo pipefail
RESULTS="${FN77_RESULTS:?Set FN77_RESULTS to the absolute evidence directory}"
[[ "$RESULTS" = /* ]] || { echo 'FN77_RESULTS must be absolute' >&2; exit 2; }
INPUT="$RESULTS/input"
SERIAL=emulator-5556
AVD_NAME=fn77-rolling-caption
PID_FILE="$RESULTS/rolling-emulator.pid"
ACL="$RESULTS/kvm-original.acl"
TEST_SELECTION=com.framenest.feature.listen_translate.RollingCaptionTranslationDiagnosisTest#compareRollingRequestsWithThreeSecondBaseline
REPORT_NAME=rolling-caption-translation-diagnosis.json

adb_short() { timeout --kill-after=5s 15 adb -s "$SERIAL" "$@"; }
force_stop_apps() {
  adb_short shell am force-stop com.framenest.test >> "$RESULTS/force-stop.log" 2>&1 || true
  adb_short shell am force-stop com.framenest >> "$RESULTS/force-stop.log" 2>&1 || true
}
stop_emulator() {
  if test -f "$PID_FILE"; then
    local pid
    pid=$(cat "$PID_FILE")
    if [[ "$pid" =~ ^[0-9]+$ ]] && test -r "/proc/$pid/cmdline" \
        && grep -Fq "$ANDROID_HOME/emulator/" "/proc/$pid/cmdline" \
        && grep -Fq "$AVD_NAME" "/proc/$pid/cmdline"; then
      adb_short emu kill >> "$RESULTS/emulator-stop.log" 2>&1 || true
      kill -TERM "$pid" 2>/dev/null || true
      timeout --kill-after=5s 10 bash -c 'while kill -0 "$1" 2>/dev/null; do sleep 1; done' bash "$pid" \
        || kill -KILL "$pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
  fi
}
cleanup() {
  local status=0
  stop_emulator || status=1
  # Always attempt restoration even if emulator cleanup failed. The workflow has
  # a second always() cleanup step in case the run trap was interrupted.
  if test -f "$ACL"; then
    timeout --kill-after=5s 15 sudo -n setfacl --restore="$ACL" || status=1
    timeout --kill-after=5s 10 getfacl -p /dev/kvm > "$RESULTS/kvm-restored.acl" || status=1
    diff -u "$ACL" "$RESULTS/kvm-restored.acl" > "$RESULTS/kvm-restore.diff" || status=1
    if [[ "$status" = 0 ]]; then
      printf 'Original ACL restored exactly.\n' > "$RESULTS/kvm-restore.txt"
    fi
  fi
  return "$status"
}
collect() {
  local dir="$RESULTS/diagnosis"
  mkdir -p "$dir"
  adb_short logcat -d > "$dir/logcat.txt" 2>&1 || true
  adb_short exec-out screencap -p > "$dir/final-screen.png" 2> "$dir/screenshot-error.txt" || true
  adb_short shell dumpsys meminfo com.framenest > "$dir/final-meminfo.txt" 2>&1 || true
  adb_short exec-out run-as com.framenest cat "files/$REPORT_NAME" \
    > "$dir/$REPORT_NAME" 2> "$dir/result-pull-error.txt" || true
}
start_emulator() {
  mkdir -p "$RESULTS/device"
  export ANDROID_AVD_HOME="$RUNNER_TEMP/fn77-rolling-caption-avd"
  test ! -e "$ANDROID_AVD_HOME"
  mkdir -p "$ANDROID_AVD_HOME"
  printf 'no\n' | timeout --kill-after=5s 120 avdmanager create avd --name "$AVD_NAME" \
    --path "$ANDROID_AVD_HOME/device.avd" --package 'system-images;android-35;google_apis;x86_64' \
    --device pixel_2 > "$RESULTS/device/avd-create.log" 2>&1
  "$ANDROID_HOME/emulator/emulator" -avd "$AVD_NAME" -port 5556 -no-window -no-audio -no-boot-anim \
    -no-snapshot -wipe-data -gpu swiftshader_indirect -accel on -memory 4096 -partition-size 8192 -cores 2 \
    > "$RESULTS/device/emulator.log" 2>&1 &
  PID=$!
  echo "$PID" > "$PID_FILE"
  export PID
  timeout --kill-after=5s 600 bash -c 'until timeout --kill-after=5s 10 adb -s emulator-5556 shell getprop sys.boot_completed 2>/dev/null | grep -q 1; do kill -0 "$PID" || exit 1; sleep 5; done'
  adb_short shell input keyevent 82
  test "$(adb_short shell getprop ro.build.version.sdk | tr -d '\r')" = 35
  test "$(adb_short shell getprop ro.product.cpu.abi | tr -d '\r')" = x86_64
  adb_short shell getprop > "$RESULTS/device/device-properties.txt"
  adb_short shell getconf PAGE_SIZE > "$RESULTS/device/page-size.txt"
}

check_collection() {
  # Require the exact unskipped, unrepeated method before handing the report to its
  # owner-maintained checker. Neither check accepts accuracy or release readiness.
  python3 - "$RESULTS" <<'PY' > "$RESULTS/instrumentation-summary.json" || return $?
import json
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
directory = root / 'diagnosis'
assert (directory / 'exit-code.txt').read_text().strip() == '0', 'Session failed'
text = (directory / 'instrumentation.txt').read_text()
current, events = {}, []
for line in text.splitlines():
    match = re.fullmatch(r'INSTRUMENTATION_STATUS: (class|test)=(.*)', line)
    if match:
        current[match[1]] = match[2]
    match = re.fullmatch(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
    if match:
        code = int(match[1])
        if code != 1:
            events.append((current.get('class'), current.get('test'), code))
        current = {}
assert events == [('com.framenest.feature.listen_translate.RollingCaptionTranslationDiagnosisTest',
                   'compareRollingRequestsWithThreeSecondBaseline', 0)], 'Skipped, repeated, or unexpected test'
assert re.search(r'^INSTRUMENTATION_CODE: -1$', text, re.MULTILINE), 'No successful runner completion'
assert not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED', text), 'Runner failure'
print(json.dumps({'status': 'one-test-passed', 'tests_passed': 1, 'tests_skipped': 0,
                  'semantic_accuracy_accepted': False, 'release_approved': False}, indent=2))
PY
  local app_sha test_sha
  app_sha=$(awk '$2 == "app.apk" {print $1}' "$INPUT/APK-SHA256SUMS")
  test_sha=$(awk '$2 == "test.apk" {print $1}' "$INPUT/APK-SHA256SUMS")
  [[ "$app_sha" =~ ^[0-9a-f]{64}$ && "$test_sha" =~ ^[0-9a-f]{64}$ ]] || return 1
  python3 scripts/check_rolling_caption_runtime_results.py \
    --report "$RESULTS/diagnosis/$REPORT_NAME" \
    --expected-app-sha256 "$app_sha" --expected-test-sha256 "$test_sha" \
    | tee "$RESULTS/collection-summary.json"
}

case "${1:-}" in
cleanup)
  mkdir -p "$RESULTS"
  cleanup
  ;;
session)
  test "${GITHUB_ACTIONS:-}" = true
  test -s "$ACL"
  test -r /dev/kvm && test -w /dev/kvm
  dir="$RESULTS/diagnosis"
  mkdir -p "$dir"
  trap 'status=$?; trap - EXIT; collect; force_stop_apps; exit "$status"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  (set -o noclobber; printf 'One instrumentation attempt only.\n' > "$RESULTS/instrumentation-attempted.txt")
  force_stop_apps
  adb_short logcat -c
  APP_SHA=$(awk '$2 == "app.apk" {print $1}' "$INPUT/APK-SHA256SUMS")
  TEST_SHA=$(awk '$2 == "test.apk" {print $1}' "$INPUT/APK-SHA256SUMS")
  [[ "$APP_SHA" =~ ^[0-9a-f]{64}$ && "$TEST_SHA" =~ ^[0-9a-f]{64}$ ]]
  # 33 minutes + 15-second kill grace. The outer 35-minute hard watchdog includes
  # session evidence collection/force-stop cleanup; there is no retry.
  timeout --signal=TERM --kill-after=15s 1980 adb -s "$SERIAL" shell am instrument -w -r \
    -e class "$TEST_SELECTION" \
    -e rollingCaptionDiagnosis true -e captionDiagnosisNetwork true \
    -e expectedAppApkSha256 "$APP_SHA" -e expectedTestApkSha256 "$TEST_SHA" \
    com.framenest.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$dir/instrumentation.txt"
  ;;
run)
  test "${GITHUB_ACTIONS:-}" = true
  test -s "$ACL"
  test -r /dev/kvm && test -w /dev/kvm
  trap 'status=$?; trap - EXIT; cleanup || status=1; exit "$status"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  (set -o noclobber; printf 'One retained-pair runtime attempt only.\n' > "$RESULTS/runtime-attempted.txt")
  (cd "$INPUT" && sha256sum -c APK-SHA256SUMS) | tee "$RESULTS/preinstall-hash-check.txt"
  start_emulator
  timeout --kill-after=5s 120 adb -s "$SERIAL" install "$INPUT/app.apk" | tee "$RESULTS/install-app.txt"
  timeout --kill-after=5s 120 adb -s "$SERIAL" install "$INPUT/test.apk" | tee "$RESULTS/install-test.txt"
  mkdir -p "$RESULTS/diagnosis"
  result=0
  if timeout --signal=TERM --kill-after=30s 2070 bash "$0" session; then
    printf '0\n' > "$RESULTS/diagnosis/exit-code.txt"
  else
    status=$?
    printf '%s\n' "$status" > "$RESULTS/diagnosis/exit-code.txt"
    result=1
    force_stop_apps
  fi
  check_collection || result=1
  exit "$result"
  ;;
*) echo 'Usage: run_rolling_caption_runtime_session.sh run|session|cleanup' >&2; exit 2 ;;
esac
