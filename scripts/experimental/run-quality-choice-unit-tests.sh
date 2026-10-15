#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
K="${KOTLIN_TEST_LIB_DIR:-/tmp/fn70-kotlin}"
S="$(find "$K" -maxdepth 1 -name 'kotlin-stdlib-*.jar' | sort | head -1)"
CP="$S:$K/junit-4.13.2.jar:$K/hamcrest-core-1.3.jar"
OUT="$(mktemp -d /tmp/quality-choice-tests.XXXXXX)"
trap 'rm -rf "$OUT"' EXIT
P="$ROOT/app/src/main/java/com/framenest"
java -cp "$K/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$CP" -d "$OUT/tests.jar" \
  "$P/core/model/PlaybackIdentity.kt" \
  "$P/data/listen_translate/ListenTranslateModels.kt" \
  "$P/smb/SmbRandomAccess.kt" \
  "$P/feature/listen_translate/audio/BlockCachingRandomAccess.kt" \
  "$P/feature/listen_translate/ListenPipelineDiagnostics.kt" \
  "$P/feature/listen_translate/ListenStartupTiming.kt" \
  "$P/feature/listen_translate/ListenTranslateEngine.kt" \
  "$P/feature/listen_translate/ListenTranslateUiModels.kt" \
  "$ROOT/app/src/test/java/com/framenest/feature/listen_translate/ListenTranslateQualityChoiceTest.kt"
java -cp "$OUT/tests.jar:$CP" org.junit.runner.JUnitCore \
  com.framenest.feature.listen_translate.ListenTranslateQualityChoiceTest
