package com.framenest.feature.listen_translate.asr

/** Pure mapping shared by the native adapter and reproducible segmentation experiments. */
internal fun sherpaTokensToWords(
    tokens: Array<String>?,
    timestamps: FloatArray?,
): List<AsrWord> {
    if (tokens == null || timestamps == null) return emptyList()
    val starts = mutableListOf<Pair<String, Long>>()
    var pendingSpace = false
    tokens.forEachIndexed { index, token ->
        val piece = token.replace('▁', ' ')
        if (piece.isBlank()) {
            pendingSpace = true
            return@forEachIndexed
        }
        val startSec = timestamps.getOrNull(index) ?: return@forEachIndexed
        if (!startSec.isFinite() || startSec < 0f) return@forEachIndexed
        val text = (if (pendingSpace && !piece.startsWith(' ')) " " else "") + piece
        pendingSpace = false
        val previous = starts.lastOrNull()
        // Preserve whole Latin words when a window cuts through BPE pieces.
        fun String.isLatinPiece() = isNotEmpty() && all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '\'' || it == '-'
        }
        if (previous != null && text.isLatinPiece() && previous.first.trim().isLatinPiece()) {
            starts[starts.lastIndex] = previous.first + text to previous.second
        } else {
            starts += text to (startSec * 1000f).toLong().coerceAtLeast(0L)
        }
    }
    return starts.mapIndexed { index, (text, startMs) ->
        val endMs = starts.getOrNull(index + 1)?.second ?: startMs
        AsrWord(text = text, startMs = startMs, endMs = endMs)
    }
}
