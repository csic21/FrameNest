package com.framenest.feature.listen_translate
import com.framenest.feature.listen_translate.asr.AsrWord
internal fun selectWordsForWindow(
    words: List<AsrWord>,
    decodeStartMs: Long,
    windowStartMs: Long,
    windowEndMs: Long,
): List<AsrWord> = words.filter { word ->
    val absoluteMidpoint = decodeStartMs + (word.startMs + word.endMs) / 2L
    absoluteMidpoint in windowStartMs until windowEndMs
}
