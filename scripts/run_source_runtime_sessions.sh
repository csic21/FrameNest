#!/usr/bin/env bash
# Invoked only by the disabled, separately approved source-runtime workflow.
# No builds, model preparation, remote writes, or system-wide KVM permissions.
set -euo pipefail
RESULTS="${FN74_RESULTS:?Set FN74_RESULTS to the absolute evidence directory}"
INPUT="$RESULTS/input"
SERIAL=emulator-5554
PID_FILE="$RESULTS/emulator.pid"
ACL="$RESULTS/kvm-original.acl"

stop_emulator() {
  if test -f "$PID_FILE"; then
    local pid
    pid=$(cat "$PID_FILE")
    if [[ "$pid" =~ ^[0-9]+$ ]] && test -r "/proc/$pid/cmdline" && grep -Fq "$ANDROID_HOME/emulator/" "/proc/$pid/cmdline"; then
      timeout --kill-after=5s 15 adb -s "$SERIAL" emu kill >> "$RESULTS/emulator-stop.log" 2>&1 || true
      kill -TERM "$pid" 2>/dev/null || true
      timeout --kill-after=5s 10 bash -c 'while kill -0 "$1" 2>/dev/null; do sleep 1; done' bash "$pid" || kill -KILL "$pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
  fi
}
restore_acl() {
  if test -f "$ACL"; then
    sudo -n setfacl --restore="$ACL" || return $?
    getfacl -p /dev/kvm > "$RESULTS/kvm-restored.acl" || return $?
    diff -u "$ACL" "$RESULTS/kvm-restored.acl" > "$RESULTS/kvm-restore.diff" || return $?
    printf 'Original ACL restored exactly.\n' > "$RESULTS/kvm-restore.txt"
  fi
}
cleanup() {
  stop_emulator
  restore_acl || return $?
}
collect_session() {
  local dir="$RESULTS/session-$1"
  mkdir -p "$dir"
  timeout --kill-after=5s 15 adb -s "$SERIAL" logcat -d > "$dir/logcat.txt" 2>&1 || true
  timeout --kill-after=5s 15 adb -s "$SERIAL" exec-out run-as com.framenest cat files/sensevoice-runtime-comparison.json > "$dir/result.json" 2> "$dir/result-pull-error.txt" || true
  timeout --kill-after=5s 15 adb -s "$SERIAL" shell dumpsys meminfo com.framenest > "$dir/final-meminfo.txt" 2>&1 || true
}

force_stop_apps() {
  timeout --kill-after=5s 15 adb -s "$SERIAL" shell am force-stop com.framenest.test >> "$RESULTS/force-stop.log" 2>&1 || true
  timeout --kill-after=5s 15 adb -s "$SERIAL" shell am force-stop com.framenest >> "$RESULTS/force-stop.log" 2>&1 || true
}
start_emulator() {
  mkdir -p "$RESULTS/device"
  export ANDROID_AVD_HOME="$RUNNER_TEMP/fn74-source-avd"
  test ! -e "$ANDROID_AVD_HOME"
  mkdir -p "$ANDROID_AVD_HOME"
  printf 'no\n' | timeout --kill-after=5s 120 avdmanager create avd --name fn74-source --path "$ANDROID_AVD_HOME/device.avd" --package 'system-images;android-35;google_apis;x86_64' --device pixel_2 > "$RESULTS/device/avd-create.log" 2>&1
  "$ANDROID_HOME/emulator/emulator" -avd fn74-source -port 5554 -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data -gpu swiftshader_indirect -accel on -memory 4096 -partition-size 8192 -cores 2 > "$RESULTS/device/emulator.log" 2>&1 &
  PID=$!
  echo "$PID" > "$PID_FILE"
  export PID
  timeout --kill-after=5s 600 bash -c 'until timeout --kill-after=5s 10 adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | grep -q 1; do kill -0 "$PID" || exit 1; sleep 5; done'
  timeout --kill-after=5s 15 adb -s "$SERIAL" shell input keyevent 82
  test "$(timeout --kill-after=5s 15 adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')" = 35
  test "$(timeout --kill-after=5s 15 adb -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')" = x86_64
  timeout --kill-after=5s 15 adb -s "$SERIAL" shell getprop > "$RESULTS/device/device-properties.txt"
  timeout --kill-after=5s 15 adb -s "$SERIAL" shell getconf PAGE_SIZE > "$RESULTS/device/page-size.txt"
  timeout --kill-after=5s 15 adb -s "$SERIAL" shell wm size > "$RESULTS/device/display-size.txt"
  timeout --kill-after=5s 15 adb -s "$SERIAL" shell wm density > "$RESULTS/device/display-density.txt"
}

install_pair() {
  (cd "$INPUT" && sha256sum -c APK-SHA256SUMS) | tee "$dir/installed-input-hash-check.txt"
  APP_SHA=$(awk '$2 == "app.apk" {print $1}' "$INPUT/APK-SHA256SUMS")
  TEST_SHA=$(awk '$2 == "test.apk" {print $1}' "$INPUT/APK-SHA256SUMS")
  [[ "$APP_SHA" =~ ^[0-9a-f]{64}$ && "$TEST_SHA" =~ ^[0-9a-f]{64}$ ]]
  force_stop_apps
  timeout --kill-after=5s 30 adb -s "$SERIAL" uninstall com.framenest.test > "$dir/uninstall-test.txt" 2>&1 || true
  timeout --kill-after=5s 30 adb -s "$SERIAL" uninstall com.framenest > "$dir/uninstall-app.txt" 2>&1 || true
  timeout --kill-after=5s 15 adb -s "$SERIAL" shell pm list packages com.framenest > "$dir/packages-after-uninstall.txt"
  if tr -d '\r' < "$dir/packages-after-uninstall.txt" | grep -Eq '^package:com\.framenest(\.test)?$'; then
    echo 'Previous app/test package was not removed' >&2
    exit 1
  fi
  timeout --kill-after=5s 120 adb -s "$SERIAL" install "$INPUT/app.apk" | tee "$dir/install-app.txt"
  timeout --kill-after=5s 120 adb -s "$SERIAL" install "$INPUT/test.apk" | tee "$dir/install-test.txt"
  timeout --kill-after=5s 15 adb -s "$SERIAL" logcat -c
}

case "${1:-}" in
cleanup)
  mkdir -p "$RESULTS"
  cleanup
  ;;
session)
  number="${2:?Missing session number}"
  [[ "$number" = 1 || "$number" = 2 ]]
  test -s "$ACL"
  test -r /dev/kvm && test -w /dev/kvm
  dir="$RESULTS/session-$number"
  mkdir -p "$dir"
  trap 'status=$?; trap - EXIT; collect_session "$number"; force_stop_apps; exit "$status"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  install_pair
  timeout --signal=TERM --kill-after=15s 420 adb -s "$SERIAL" shell am instrument -w -r \
    -e class com.framenest.feature.listen_translate.SenseVoiceRuntimeComparisonTest \
    -e runtimeComparison true -e sourceRuntimeComparison true -e expectedRuntime 1.13.8 \
    -e expectedAppApkSha256 "$APP_SHA" -e expectedTestApkSha256 "$TEST_SHA" \
    com.framenest.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$dir/instrumentation.txt"
  grep -Eq '^OK \(1 test\)' "$dir/instrumentation.txt"
  ;;
ui)
  test -s "$ACL"
  dir="$RESULTS/ui"
  mkdir -p "$dir"
  trap 'status=$?; trap - EXIT; timeout --kill-after=5s 15 adb -s "$SERIAL" logcat -d > "$dir/logcat.txt" 2>&1 || true; force_stop_apps; python3 scripts/check_source_runtime_ui_result.py "$RESULTS" "$status"; exit "$status"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  install_pair
  timeout --signal=TERM --kill-after=15s 300 adb -s "$SERIAL" shell am instrument -w -r \
    -e class com.framenest.ui.screens.SourceAsrLicensesDialogTest \
    com.framenest.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$dir/instrumentation.txt"
  grep -Eq '^OK \(7 tests\)' "$dir/instrumentation.txt"
  ;;
run)
  test "${GITHUB_ACTIONS:-}" = true
  test -s "$ACL" # No emulator start unless this job saved its original ACL.
  trap 'status=$?; trap - EXIT; cleanup || status=1; exit "$status"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  start_emulator
  result=0
  for number in 1 2; do
    # A separate shell keeps errexit effective inside each independent session.
    if timeout --signal=TERM --kill-after=60s 900 bash "$0" session "$number"; then
      printf 'passed\n' > "$RESULTS/session-$number/shell-status.txt"
    else
      status=$?
      printf 'failed exit=%s\n' "$status" > "$RESULTS/session-$number/shell-status.txt"
      result=1
      force_stop_apps
    fi
  done
  if timeout --signal=TERM --kill-after=60s 600 bash "$0" ui; then
    ui_status=0
    printf 'passed\n' > "$RESULTS/ui/shell-status.txt"
  else
    status=$?
    ui_status=$status
    printf 'failed exit=%s\n' "$status" > "$RESULTS/ui/shell-status.txt"
    result=1
    force_stop_apps
  fi
  python3 scripts/check_source_runtime_ui_result.py "$RESULTS" "$ui_status" || result=1
  python3 scripts/check_source_runtime_results.py "$RESULTS" || result=1
  exit "$result"
  ;;
*) echo 'Usage: run_source_runtime_sessions.sh run|session 1|session 2|ui|cleanup' >&2; exit 2 ;;
esac
