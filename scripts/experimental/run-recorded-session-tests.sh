#!/usr/bin/env bash
# Pure JVM orchestration/Repository tests. Annotation stubs do not validate Room SQL.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
K="${KOTLIN_TEST_LIB_DIR:-/tmp/fn70-kotlin}"
CP="$K/kotlin-stdlib-2.1.20.jar:$K/junit-4.13.2.jar:$K/hamcrest-core-1.3.jar:$K/kotlinx-coroutines-core-jvm-1.8.0.jar:$K/kotlinx-coroutines-test-jvm-1.8.0.jar"
OUT="$(mktemp -d /tmp/recorded-session-tests.XXXXXX)"
trap 'rm -rf "$OUT"' EXIT
cat > "$OUT/RoomAnnotations.kt" <<'KOTLIN'
package androidx.room
import kotlin.reflect.KClass
annotation class Dao
annotation class Upsert
annotation class Transaction
annotation class Query(val value: String)
annotation class Insert(val onConflict: Int = 0)
object OnConflictStrategy { const val REPLACE = 1 }
annotation class ColumnInfo(val name: String)
annotation class PrimaryKey(val autoGenerate: Boolean = false)
annotation class Index(val value: Array<String>, val unique: Boolean = false)
annotation class ForeignKey(val entity: KClass<*>, val parentColumns: Array<String>,
    val childColumns: Array<String>, val onDelete: Int = 0) { companion object { const val CASCADE = 5 } }
annotation class Entity(val tableName: String, val primaryKeys: Array<String> = [],
    val indices: Array<Index> = [], val foreignKeys: Array<ForeignKey> = [])
KOTLIN
# Copy the Android-co-located exception declaration verbatim, without model implementations.
grep '^class ModelsNotReadyException' "$ROOT/app/src/main/java/com/framenest/feature/listen_translate/ModelAwareListenTranslateEngine.kt" > "$OUT/ExceptionDeclaration.txt"
{ echo 'package com.framenest.feature.listen_translate'; cat "$OUT/ExceptionDeclaration.txt"; } > "$OUT/ModelsNotReadyException.kt"
P="$ROOT/app/src/main/java/com/framenest"
FILES=("$OUT"/*.kt "$P/core/model/PlaybackIdentity.kt" "$P"/data/listen_translate/*.kt
 "$P/smb/SmbRandomAccess.kt" "$P/feature/listen_translate/audio/BlockCachingRandomAccess.kt")
for name in ListenTranslateEngine EmptyTranslationRecoveryEngine RecordedListenTranslateEngine ListenTranslateSession ListenTranslateUiModels ListenTranslateWindows ListenCompletedIntervals ListenPrefetchPolicy ListenPipelineDiagnostics ListenStartupTiming; do
 FILES+=("$P/feature/listen_translate/$name.kt")
done
TESTS=(EmptyTranslationRecoverySessionTest ListenRecordedSessionTest ListenTranslateSessionTest ListenAdaptiveSessionTest ListenStartupSessionTest ListenTranslateProgressTest ListenCompletedIntervalsTest ListenPrefetchPolicyTest ListenTranslateWindowsTest ListenWindowRetryPolicyTest)
FILES+=("$ROOT/app/src/test/java/com/framenest/data/listen_translate/ListenTranslateRepositoryTest.kt")
for name in "${TESTS[@]}"; do FILES+=("$ROOT/app/src/test/java/com/framenest/feature/listen_translate/$name.kt"); done
java -cp "$K/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$CP" -d "$OUT/tests.jar" "${FILES[@]}"
CLASSES=(com.framenest.data.listen_translate.ListenTranslateRepositoryTest)
for name in "${TESTS[@]}"; do CLASSES+=("com.framenest.feature.listen_translate.$name"); done
java -Xmx512m -cp "$OUT/tests.jar:$CP" org.junit.runner.JUnitCore "${CLASSES[@]}"
