#!/usr/bin/env bash
# Prepared only: workflow and current-UID ACL grant stay disabled until approval.
# One retained pair, one KVM AVD, no rebuild/TCG; each diagnostic class runs once.
set -euo pipefail
RESULTS="${FN76_RESULTS:?Set FN76_RESULTS to the absolute evidence directory}"
INPUT="$RESULTS/input"
SERIAL=emulator-5554
PID_FILE="$RESULTS/emulator.pid"
ACL="$RESULTS/kvm-original.acl"
UI_CLASSES=com.framenest.feature.listen_translate.ListenTranslateOverlayTest,com.framenest.feature.listen_translate.ListenTranslateDiagnosticsControlsTest,com.framenest.feature.listen_translate.CaptionDiagnosisFixtureTest

adb_short() { timeout --kill-after=5s 15 adb -s "$SERIAL" "$@"; }
force_stop_apps() {
  adb_short shell am force-stop com.framenest.test >> "$RESULTS/force-stop.log" 2>&1 || true
  adb_short shell am force-stop com.framenest >> "$RESULTS/force-stop.log" 2>&1 || true
}
stop_emulator() {
  if test -f "$PID_FILE"; then
    local pid
    pid=$(cat "$PID_FILE")
    if [[ "$pid" =~ ^[0-9]+$ ]] && test -r "/proc/$pid/cmdline" && grep -Fq "$ANDROID_HOME/emulator/" "/proc/$pid/cmdline"; then
      adb_short emu kill >> "$RESULTS/emulator-stop.log" 2>&1 || true
      kill -TERM "$pid" 2>/dev/null || true
      timeout --kill-after=5s 10 bash -c 'while kill -0 "$1" 2>/dev/null; do sleep 1; done' bash "$pid" || kill -KILL "$pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
  fi
}
cleanup() {
  stop_emulator
  if test -f "$ACL"; then
    sudo -n setfacl --restore="$ACL" || return $?
    getfacl -p /dev/kvm > "$RESULTS/kvm-restored.acl" || return $?
    diff -u "$ACL" "$RESULTS/kvm-restored.acl" > "$RESULTS/kvm-restore.diff" || return $?
    printf 'Original ACL restored exactly.\n' > "$RESULTS/kvm-restore.txt"
  fi
}
collect() {
  local name="$1" dir="$RESULTS/$1"
  mkdir -p "$dir"
  adb_short logcat -d > "$dir/logcat.txt" 2>&1 || true
  adb_short exec-out screencap -p > "$dir/final-screen.png" 2> "$dir/screenshot-error.txt" || true
  adb_short shell dumpsys meminfo com.framenest > "$dir/final-meminfo.txt" 2>&1 || true
  if [[ "$name" = diagnosis ]]; then
    adb_short exec-out run-as com.framenest cat files/caption-diagnosis.json > "$dir/caption-diagnosis.json" 2> "$dir/result-pull-error.txt" || true
  elif [[ "$name" = comparison ]]; then
    adb_short exec-out run-as com.framenest cat files/fixed-caption-window-comparison.json > "$dir/fixed-caption-window-comparison.json" 2> "$dir/result-pull-error.txt" || true
  else
    for width in 360 840; do
      adb_short exec-out run-as com.framenest cat "cache/fn67-listen-$width.png" > "$dir/controls-$width.png" 2> "$dir/controls-$width-error.txt" || true
    done
  fi
}
start_emulator() {
  mkdir -p "$RESULTS/device"
  export ANDROID_AVD_HOME="$RUNNER_TEMP/fn76-caption-avd"
  test ! -e "$ANDROID_AVD_HOME"
  mkdir -p "$ANDROID_AVD_HOME"
  printf 'no\n' | timeout --kill-after=5s 120 avdmanager create avd --name fn76-caption --path "$ANDROID_AVD_HOME/device.avd" --package 'system-images;android-35;google_apis;x86_64' --device pixel_2 > "$RESULTS/device/avd-create.log" 2>&1
  "$ANDROID_HOME/emulator/emulator" -avd fn76-caption -port 5554 -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data -gpu swiftshader_indirect -accel on -memory 4096 -partition-size 8192 -cores 2 > "$RESULTS/device/emulator.log" 2>&1 &
  PID=$!
  echo "$PID" > "$PID_FILE"
  export PID
  timeout --kill-after=5s 600 bash -c 'until timeout --kill-after=5s 10 adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | grep -q 1; do kill -0 "$PID" || exit 1; sleep 5; done'
  adb_short shell input keyevent 82
  test "$(adb_short shell getprop ro.build.version.sdk | tr -d '\r')" = 35
  test "$(adb_short shell getprop ro.product.cpu.abi | tr -d '\r')" = x86_64
  adb_short shell getprop > "$RESULTS/device/device-properties.txt"
  adb_short shell getconf PAGE_SIZE > "$RESULTS/device/page-size.txt"
}
set_viewport() {
  local name="$1" dir="$2"
  force_stop_apps
  # Resize only this disposable AVD; these are viewport checks, not physical tablets.
  if [[ "$name" = tablet ]]; then
    adb_short shell wm size 1600x2560
    adb_short shell wm density 240
  else
    adb_short shell wm size 1080x1920
    adb_short shell wm density 480
  fi
  adb_short shell wm size > "$dir/display-size.txt"
  adb_short shell wm density > "$dir/display-density.txt"
  adb_short shell dumpsys display > "$dir/display.txt"
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
  name="${2:?Missing session name}"
  [[ "$name" = phone || "$name" = tablet || "$name" = diagnosis || "$name" = comparison ]]
  dir="$RESULTS/$name"
  mkdir -p "$dir"
  trap 'status=$?; trap - EXIT; collect "$name"; force_stop_apps; exit "$status"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  set_viewport "$name" "$dir" > "$dir/viewport-change.txt" 2>&1
  adb_short logcat -c
  if [[ "$name" = phone || "$name" = tablet ]]; then
    # Never label a screenshot from the earlier viewport as this session's UI.
    adb_short shell run-as com.framenest rm -f cache/fn67-listen-360.png cache/fn67-listen-840.png
  fi
  if [[ "$name" = diagnosis || "$name" = comparison ]]; then
    # Fail closed on accidental retries within the same job/results directory.
    (set -o noclobber; printf 'One attempt only.\n' > "$RESULTS/$name-attempted.txt")
    APP_SHA=$(awk '$2 == "app.apk" {print $1}' "$INPUT/APK-SHA256SUMS")
    TEST_SHA=$(awk '$2 == "test.apk" {print $1}' "$INPUT/APK-SHA256SUMS")
    [[ "$APP_SHA" =~ ^[0-9a-f]{64}$ && "$TEST_SHA" =~ ^[0-9a-f]{64}$ ]]
    if [[ "$name" = diagnosis ]]; then
      test_class=CaptionDiagnosisTest
      opt_in=captionDiagnosis
      instrumentation_limit=1620
    else
      test_class=FixedCaptionWindowComparisonTest
      opt_in=fixedCaptionWindowComparison
      instrumentation_limit=1185 # Plus the 15-second kill grace: 20-minute hard bound.
    fi
    timeout --signal=TERM --kill-after=15s "$instrumentation_limit" adb -s "$SERIAL" shell am instrument -w -r \
      -e class "com.framenest.feature.listen_translate.$test_class" \
      -e "$opt_in" true -e captionDiagnosisNetwork true \
      -e expectedAppApkSha256 "$APP_SHA" -e expectedTestApkSha256 "$TEST_SHA" \
      com.framenest.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$dir/instrumentation.txt"
  else
    timeout --signal=TERM --kill-after=15s 600 adb -s "$SERIAL" shell am instrument -w -r \
      -e class "$UI_CLASSES" \
      com.framenest.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$dir/instrumentation.txt"
  fi
  ;;
run)
  test "${GITHUB_ACTIONS:-}" = true
  test -s "$ACL" # No emulator start unless this job saved its original ACL.
  trap 'status=$?; trap - EXIT; cleanup || status=1; exit "$status"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  (cd "$INPUT" && sha256sum -c APK-SHA256SUMS) | tee "$RESULTS/preinstall-hash-check.txt"
  start_emulator
  # Fresh -wipe-data AVD; install the exact retained pair just once.
  timeout --kill-after=5s 120 adb -s "$SERIAL" install "$INPUT/app.apk" | tee "$RESULTS/install-app.txt"
  timeout --kill-after=5s 120 adb -s "$SERIAL" install "$INPUT/test.apk" | tee "$RESULTS/install-test.txt"
  result=0
  for name in phone tablet diagnosis comparison; do
    mkdir -p "$RESULTS/$name"
    limit=780
    [[ "$name" != diagnosis ]] || limit=1800
    [[ "$name" != comparison ]] || limit=1500
    if timeout --signal=TERM --kill-after=60s "$limit" bash "$0" session "$name"; then
      printf '0\n' > "$RESULTS/$name/exit-code.txt"
    else
      status=$?
      printf '%s\n' "$status" > "$RESULTS/$name/exit-code.txt"
      result=1
      force_stop_apps
    fi
  done
  python3 scripts/check_caption_runtime_results.py "$RESULTS" --fixed-window-comparison || result=1
  exit "$result"
  ;;
*) echo 'Usage: run_caption_runtime_sessions.sh run|session phone|session tablet|session diagnosis|session comparison|cleanup' >&2; exit 2 ;;
esac
