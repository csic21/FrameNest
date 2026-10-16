#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
K="${KOTLIN_TEST_LIB_DIR:-/tmp/fn70-kotlin}"
VERSION="${KOTLIN_TEST_VERSION:-2.1.20}"
CP="$K/kotlin-stdlib-$VERSION.jar:$K/junit-4.13.2.jar:$K/hamcrest-core-1.3.jar"
BUILD="$(mktemp -d "${TMPDIR:-/tmp}/fn77-local-agreement-tests.XXXXXX")"
trap 'rm -rf "$BUILD"' EXIT
java -cp "$K/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
  -classpath "$CP" -d "$BUILD/tests.jar" \
  "$HERE/BoundedLocalAgreement.kt" "$HERE/BoundedLocalAgreementTest.kt"
java -cp "$BUILD/tests.jar:$CP" org.junit.runner.JUnitCore \
  com.framenest.experiment.localagreement.BoundedLocalAgreementTest
