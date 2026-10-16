#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
K="${KOTLIN_TEST_LIB_DIR:-/tmp/fn70-kotlin}"
VERSION="${KOTLIN_TEST_VERSION:-2.1.20}"
STDLIB="$K/kotlin-stdlib-$VERSION.jar"
test -f "$STDLIB" || { echo "Missing offline Kotlin stdlib: $STDLIB" >&2; exit 1; }
BUILD="$(mktemp -d "${TMPDIR:-/tmp}/fn77-rolling-agreement-cli.XXXXXX")"
trap 'rm -rf "$BUILD"' EXIT
java -cp "$K/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
  -classpath "$STDLIB" -d "$BUILD/cli.jar" \
  "$HERE/ReversibleAgreementKey.kt" "$HERE/RollingLocalAgreement.kt" "$HERE/RollingLocalAgreementCli.kt"
java -cp "$BUILD/cli.jar:$STDLIB" com.framenest.experiment.localagreement.RollingLocalAgreementCliKt
