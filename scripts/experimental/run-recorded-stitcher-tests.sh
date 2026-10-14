#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
K="${KOTLIN_TEST_LIB_DIR:-/tmp/fn70-kotlin}"
CP="$K/kotlin-stdlib-2.4.0.jar:$K/junit-4.13.2.jar:$K/hamcrest-core-1.3.jar"
java -cp "$K/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$CP" -d /tmp/recorded-stitcher-tests.jar "$ROOT/app/src/main/java/com/framenest/feature/listen_translate/RecordedTranscriptStitcher.kt" "$ROOT/app/src/test/java/com/framenest/feature/listen_translate/RecordedTranscriptStitcherTest.kt"
java -cp "/tmp/recorded-stitcher-tests.jar:$CP" org.junit.runner.JUnitCore com.framenest.feature.listen_translate.RecordedTranscriptStitcherTest
