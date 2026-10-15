#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
K="${KOTLIN_TEST_LIB_DIR:-/tmp/fn70-kotlin}"
M="${KUROMOJI_TEST_LIB_DIR:-/tmp/fn70-kuromoji}"
S="$(find "$K" -maxdepth 1 -name 'kotlin-stdlib-*.jar' | sort | head -1)"
CP="$S:$M/kuromoji-core-0.9.0.jar:$M/kuromoji-ipadic-0.9.0.jar"
OUT="$(mktemp -d /tmp/recorded-source-cli.XXXXXX)"
trap 'rm -rf "$OUT"' EXIT
java -cp "$K/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$CP" -d "$OUT/cli.jar" \
  "$ROOT/app/src/main/java/com/framenest/feature/listen_translate/RecordedTranscriptStitcher.kt" \
  "$ROOT"/app/src/main/java/com/framenest/feature/listen_translate/recorded/*.kt \
  "$ROOT/scripts/experimental/RecordedSourceUnitsCli.kt"
java -Xmx512m -cp "$OUT/cli.jar:$CP" com.framenest.feature.listen_translate.recorded.RecordedSourceUnitsCliKt
