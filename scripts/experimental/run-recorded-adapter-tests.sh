#!/usr/bin/env bash
# Compiles real orchestration/morphology; extracts Android-co-located interfaces verbatim.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
K="${KOTLIN_TEST_LIB_DIR:-/tmp/fn70-kotlin}"
M="${KUROMOJI_TEST_LIB_DIR:-/tmp/fn70-kuromoji}"
CP="$(find "$K" "$M" -maxdepth 1 -name '*.jar' | paste -sd:)"
OUT="$(mktemp -d /tmp/recorded-adapter-tests.XXXXXX)"
trap 'rm -rf "$OUT"' EXIT
P="$ROOT/app/src/main/java/com/framenest"
python3 - "$P" "$OUT" <<'PY'
from pathlib import Path
import sys
root, out = map(Path, sys.argv[1:])
def interface(path, name):
    text = (root / path).read_text()
    begin = text.index('interface ' + name)
    brace = text.index('{', begin)
    depth = 1
    end = brace + 1
    while depth:
        if text[end] == '{': depth += 1
        if text[end] == '}': depth -= 1
        end += 1
    return text[begin:end]
(out / 'AudioContract.kt').write_text('package com.framenest.feature.listen_translate.audio\nimport java.io.Closeable\nimport com.framenest.player.audio.PcmWindowResult\n' + interface('feature/listen_translate/audio/ListenAudioSource.kt', 'ListenAudioSource'))
(out / 'ModelContract.kt').write_text('package com.framenest.feature.listen_translate.asr\nimport java.io.File\nimport com.framenest.data.settings.AsrEngineChoice\n' + interface('feature/listen_translate/asr/AsrModelSupport.kt', 'AsrModelSupport'))
(out / 'ModelException.kt').write_text('package com.framenest.feature.listen_translate\nclass ModelsNotReadyException(message: String) : IllegalStateException(message)\n')
PY
java -cp "$K/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$CP" -d "$OUT/tests.jar" \
  "$OUT"/*.kt \
  "$P/data/settings/AsrEngineChoice.kt" "$P/smb/SmbRandomAccess.kt" \
  "$P/feature/listen_translate/audio/BlockCachingRandomAccess.kt" \
  "$P/player/audio/PcmWindowResult.kt" \
  "$P/feature/listen_translate/asr/AsrEngine.kt" \
  "$P/feature/listen_translate/mt/ListenMtEngine.kt" \
  "$P/feature/listen_translate/ListenPipelineDiagnostics.kt" \
  "$P/feature/listen_translate/ListenTranslateEngine.kt" \
  "$P/feature/listen_translate/RecordedListenTranslateEngine.kt" \
  "$P/feature/listen_translate/RecordedFileListenTranslateEngine.kt" \
  "$P/feature/listen_translate/RecordedTranscriptStitcher.kt" \
  "$P/feature/listen_translate/JapanesePcmPauses.kt" \
  "$P/feature/listen_translate/JapaneseOwnedPrefixPolicy.kt" \
  "$P"/feature/listen_translate/recorded/*.kt \
  "$ROOT/app/src/test/java/com/framenest/feature/listen_translate/RecordedFileListenTranslateEngineTest.kt"
java -Xmx768m -cp "$OUT/tests.jar:$CP" org.junit.runner.JUnitCore \
  com.framenest.feature.listen_translate.RecordedFileListenTranslateEngineTest
