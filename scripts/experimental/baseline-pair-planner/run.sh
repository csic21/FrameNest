#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
S=$(find /tmp/fn70-kotlin -name 'kotlin-stdlib-*.jar'|head -1)
CP="$S:/tmp/fn70-kuromoji/kuromoji-core-0.9.0.jar:/tmp/fn70-kuromoji/kuromoji-ipadic-0.9.0.jar"
java -cp '/tmp/fn70-kotlin/*' org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$CP" -d /tmp/baseline-pair-planner.jar BaselinePairPlanner.kt PairPlannerCli.kt PairPlannerTests.kt
java -cp "/tmp/baseline-pair-planner.jar:$CP" com.framenest.feature.listen_translate.experimental.PairPlannerTestsKt
python replay.py
