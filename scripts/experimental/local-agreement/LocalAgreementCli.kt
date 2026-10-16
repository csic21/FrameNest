package com.framenest.experiment.localagreement

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

private fun quote(value: Any?): String = value?.toString()?.let { text -> buildString {
    append('"')
    for (c in text) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
    }
    append('"')
} } ?: "null"

private fun evidenceJson(e: BoundedLocalAgreement.Evidence?): String = e?.let {
    "{" + listOf(
        "\"generation\":${e.generation}",
        "\"originSample\":${e.originSample}",
        "\"sampleRate\":${e.sampleRate}",
        "\"observedEndSample\":${e.observedEndSample}",
        "\"sampleCount\":${e.sampleCount}",
        "\"hypothesisCount\":${e.hypothesisCount}",
        "\"confirmedPrefix\":${quote(e.confirmedPrefix)}",
        "\"latestHypothesis\":${quote(e.latestHypothesis)}",
        "\"unconfirmedTail\":${quote(e.unconfirmedTail)}",
        "\"conflictObserved\":${e.conflictObserved}",
        "\"evidenceLabel\":${quote(e.evidenceLabel)}",
        "\"finalTranslationOrCoverageAllowed\":${e.finalTranslationOrCoverageAllowed}",
        "\"terminalReasons\":${e.terminalReasons.joinToString(prefix = "[", postfix = "]") { quote(it) }}",
        "\"eofObserved\":${e.eofObserved}",
        "\"retainedTextCodePoints\":${e.retainedTextCodePoints}",
        "\"retainedTextCodeUnits\":${e.retainedTextCodeUnits}",
    ).joinToString(",") + "}"
} ?: "null"

private fun auditJson(a: BoundedLocalAgreement.ResetAudit?): String = a?.let {
    "{" + listOf(
        "\"discardedGeneration\":${a.discardedGeneration}",
        "\"discardedHypothesisCount\":${a.discardedHypothesisCount}",
        "\"discardedSampleCount\":${a.discardedSampleCount}",
        "\"discardedLatestCodePoints\":${a.discardedLatestCodePoints}",
        "\"discardedConfirmedCodePoints\":${a.discardedConfirmedCodePoints}",
        "\"discardedConflictObserved\":${a.discardedConflictObserved}",
        "\"discardedTerminalReasons\":${a.discardedTerminalReasons.joinToString(prefix = "[", postfix = "]") { quote(it) }}",
        "\"discardedEofObserved\":${a.discardedEofObserved}",
    ).joinToString(",") + "}"
} ?: "null"

private fun decodeText(encoded: String): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(Base64.getDecoder().decode(encoded))).toString()

/**
 * RESET<TAB>generation<TAB>originSample<TAB>16000
 * H<TAB>generation<TAB>originSample<TAB>endSample<TAB>sampleCount<TAB>base64(UTF-8)
 * EOF<TAB>generation
 * One JSON result per line. Invalid syntax/encoding leaves the component untouched.
 */
fun main() {
    val component = BoundedLocalAgreement()
    var lineNumber = 0L
    generateSequence(::readlnOrNull).forEach { line ->
        lineNumber++
        try {
            require(line.length <= 32_768) { "Input line exceeds CLI limit" }
            val p = line.split('\t')
            val result = when (p[0]) {
                "RESET" -> {
                    require(p.size == 4) { "RESET requires generation, originSample, sampleRate" }
                    component.reset(p[1].toLong(), p[2].toLong(), p[3].toInt())
                }
                "H" -> {
                    require(p.size == 6) { "H requires generation, originSample, endSample, sampleCount, base64Text" }
                    // Decode every argument before invoking the component.
                    component.observe(p[1].toLong(), p[2].toLong(), p[3].toLong(), p[4].toLong(), decodeText(p[5]))
                }
                "EOF" -> {
                    require(p.size == 2) { "EOF requires generation" }
                    component.finish(p[1].toLong())
                }
                else -> throw IllegalArgumentException("Unknown operation")
            }
            println("{\"line\":$lineNumber,\"status\":${quote(result.status)},\"error\":${quote(result.error)}," +
                "\"evidence\":${evidenceJson(result.evidence)},\"resetAudit\":${auditJson(result.resetAudit)}}")
        } catch (e: IllegalArgumentException) {
            emitInputError(lineNumber, component)
        } catch (e: java.nio.charset.CharacterCodingException) {
            emitInputError(lineNumber, component)
        }
    }
}

private fun emitInputError(line: Long, component: BoundedLocalAgreement) {
    // No raw rejected text is logged here; the research harness must retain its input.
    println("{\"line\":$line,\"status\":\"INPUT_ERROR\",\"error\":\"INVALID_TSV_OR_ENCODING\"," +
        "\"evidence\":${evidenceJson(component.snapshot())},\"resetAudit\":null}")
}
