#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
K="${KOTLIN_TEST_LIB_DIR:-/tmp/fn70-kotlin}"
OUT=/tmp/recorded-stitcher-cli.jar
if [[ ! -f "$OUT" || "$ROOT/app/src/main/java/com/framenest/feature/listen_translate/RecordedTranscriptStitcher.kt" -nt "$OUT" || "$ROOT/scripts/experimental/RecordedStitcherCli.kt" -nt "$OUT" ]]; then
 java -cp "$K/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$K/kotlin-stdlib-2.4.0.jar" -d "$OUT" "$ROOT/app/src/main/java/com/framenest/feature/listen_translate/RecordedTranscriptStitcher.kt" "$ROOT/scripts/experimental/RecordedStitcherCli.kt"
fi
exec java -cp "$OUT:$K/kotlin-stdlib-2.4.0.jar" com.framenest.feature.listen_translate.RecordedStitcherCliKt
