#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
K="${KOTLIN_TEST_LIB_DIR:-/tmp/fn70-kotlin}"
M="${KUROMOJI_TEST_LIB_DIR:-/tmp/fn70-kuromoji}"
S="$(find "$K" -maxdepth 1 -name 'kotlin-stdlib-*.jar' | sort | head -1)"
CP="$S:$K/junit-4.13.2.jar:$K/hamcrest-core-1.3.jar:$M/kuromoji-core-0.9.0.jar:$M/kuromoji-ipadic-0.9.0.jar"
OUT="$(mktemp -d /tmp/recorded-source-tests.XXXXXX)"
trap 'rm -rf "$OUT"' EXIT
java -cp "$K/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$CP" -d "$OUT/tests.jar" \
  "$ROOT/app/src/main/java/com/framenest/feature/listen_translate/RecordedTranscriptStitcher.kt" \
  "$ROOT"/app/src/main/java/com/framenest/feature/listen_translate/recorded/*.kt \
  "$ROOT"/app/src/test/java/com/framenest/feature/listen_translate/recorded/*.kt
java -Xmx512m -cp "$OUT/tests.jar:$CP" org.junit.runner.JUnitCore \
  com.framenest.feature.listen_translate.recorded.RecordedSourceUnitCoordinatorTest \
  com.framenest.feature.listen_translate.recorded.JapaneseSourceBoundaryTest
