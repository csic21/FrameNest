#!/usr/bin/env bash
# No model/tool downloads, Android emulator, or network access. Requires local Kotlin jars.
set -euo pipefail
if [[ $# -lt 3 || $# -gt 4 ]]; then
  echo "Usage: $0 JNI_LIBRARY_DIR VERIFIED_MODEL_FILE KOTLIN_JARS_DIR [perf|smoke]" >&2
  exit 2
fi
root="$(cd "$(dirname "$0")/../.." && pwd)"
lib="$(cd "$1" && pwd)"
model="$(realpath "$2")"
kotlin="$(cd "$3" && pwd)"
mode="${4:-perf}"
case "$mode" in
  perf) source_name=NativePerfProbe; main_name=NativePerfProbeKt ;;
  smoke) source_name=NativeSmoke; main_name=NativeSmokeKt ;;
  *) echo "Unknown probe mode" >&2; exit 2 ;;
esac
test -f "$lib/libframenest_hymt.so"
test "$(wc -c < "$model")" -eq 1133080448
echo "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699  $model" | sha256sum -c - >&2
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
# AndroidX Keep has no host runtime behavior; the production engine/bridge is unchanged.
printf 'package androidx.annotation\nannotation class Keep\n' > "$temp/Keep.kt"
classpath="$(find "$kotlin" -maxdepth 1 -name '*.jar' -print | sort | paste -sd : -)"
mt="$root/app/src/main/java/com/framenest/feature/listen_translate/mt"
java -cp "$classpath" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$classpath" -d "$temp/probe.jar" \
  "$temp/Keep.kt" "$mt/ListenMtEngine.kt" "$mt/HyMtEngine.kt" \
  "$mt/HyMtNative.kt" "$mt/HyMtPrompt.kt" "$mt/HyMtModelUseGuard.kt" \
  "$root/app/src/main/cpp/tests/$source_name.kt"
java -Djava.library.path="$lib" -cp "$temp/probe.jar:$classpath" \
  "com.framenest.feature.listen_translate.mt.$main_name" "$model"
