package com.framenest.feature.listen_translate

import java.util.Base64

private fun quoted(value: String?): String = value?.let { text -> buildString {
    append('"')
    for (c in text) when (c) {
        '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
        else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
    }
    append('"')
} } ?: "null"

private fun emit(r: RecordedStitchResult) {
    val f = r.frozenText?.let { "{\"coarseLabelStartMs\":${it.coarseLabelStartMs},\"coarseLabelEndMs\":${it.coarseLabelEndMs},\"text\":${quoted(it.text)}}" } ?: "null"
    val p = r.pending?.let { "{\"windowStartMs\":${it.window.startMs},\"windowEndMs\":${it.window.endMs},\"rawStart\":${it.rawStart},\"coarseLabelStartMs\":${it.coarseLabelStartMs},\"text\":${quoted(it.text)}}" } ?: "null"
    val e = r.expansion?.let { "{\"generation\":${it.generation},\"startMs\":${it.startMs},\"endMs\":${it.endMs}}" } ?: "null"
    val a = r.audit?.let { val offsets = it.matchedOffsets.joinToString(prefix = "[", postfix = "]") { m -> "[${m.oldStart},${m.oldEnd},${m.newStart},${m.newEnd}]" }; "{\"oldWindowStartMs\":${it.oldWindowStartMs},\"newWindowStartMs\":${it.newWindowStartMs},\"oldRetainedStart\":${it.oldRetainedStart},\"oldRetainedEnd\":${it.oldRetainedEnd},\"oldReplacedStart\":${it.oldReplacedStart},\"oldReplacedEnd\":${it.oldReplacedEnd},\"newOmittedPrefixEnd\":${it.newOmittedPrefixEnd},\"newPendingStart\":${it.newPendingStart},\"matchedCodePoints\":${it.matchedCodePoints},\"editCount\":${it.editCount},\"matchedOffsets\":$offsets}" } ?: "null"
    val recovery = r.recoveryAudit?.let { "{\"frozenWindowStartMs\":${it.frozenWindowStartMs},\"frozenAnchorStart\":${it.frozenAnchorStart},\"frozenAnchorEnd\":${it.frozenAnchorEnd},\"replacementAnchorStart\":${it.replacementAnchorStart},\"replacementAnchorEnd\":${it.replacementAnchorEnd},\"replacementPendingStart\":${it.replacementPendingStart},\"matchedCodePoints\":${it.matchedCodePoints}}" } ?: "null"
    println("{\"frozenText\":$f,\"pending\":$p,\"conflict\":${quoted(r.conflict)},\"expansion\":$e,\"audit\":$a,\"recoveryAudit\":$recovery,\"eof\":${r.eof},\"abandonedPendingText\":${quoted(r.abandonedPending?.text)}}")
}

/** H/X gen start end; T absoluteStart base64(UTF8 text); END; RESET gen; EOF gen. */
fun main() {
    val stitcher = RecordedTranscriptStitcher()
    var header: List<String>? = null
    val tokens = mutableListOf<RecordedToken>()
    generateSequence(::readlnOrNull).forEach { line ->
        val parts = line.split('\t')
        when (parts[0]) {
            "H", "X" -> { require(header == null && parts.size == 4); header = parts; tokens.clear() }
            "T" -> { require(header != null && parts.size == 3); tokens += RecordedToken(
                String(Base64.getDecoder().decode(parts[2]), Charsets.UTF_8), parts[1].toLong()) }
            "END" -> {
                val h = requireNotNull(header)
                val hypothesis = RecordedHypothesis(h[1].toLong(), h[2].toLong(), h[3].toLong(), tokens.toList())
                emit(if (h[0] == "X") stitcher.replacePendingWithExpansion(hypothesis) else stitcher.push(hypothesis))
                header = null; tokens.clear()
            }
            "RESET" -> { require(header == null && parts.size == 2); emit(stitcher.reset(parts[1].toLong())) }
            "EOF" -> { require(header == null && parts.size == 2); emit(stitcher.finish(parts[1].toLong())) }
            else -> error("Unknown line type")
        }
    }
    require(header == null) { "Unfinished hypothesis" }
}
