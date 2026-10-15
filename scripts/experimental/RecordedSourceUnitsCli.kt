package com.framenest.feature.listen_translate.recorded

import com.framenest.feature.listen_translate.RecordedHypothesis
import com.framenest.feature.listen_translate.RecordedToken
import java.util.Base64

private fun q(s: String?): String = s?.let { value -> buildString {
    append('"'); value.forEach { c -> when(c) {
        '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
        else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
    } }; append('"')
} } ?: "null"
private fun unitJson(u: RecordedSourceUnit): String = buildString {
    append("{\"generation\":${u.id.generation},\"sequence\":${u.id.sequence},\"text\":${q(u.text)},")
    append("\"status\":${q(u.status.name)},\"reason\":${q(u.reason.name)},")
    append("\"displayStartMs\":${u.displayStartMs},\"displayEndMs\":${u.displayEndMs},")
    append("\"coverageStartMs\":${u.coverageStartMs},\"coverageEndMs\":${u.coverageEndMs},\"timingIsEstimated\":true,")
    append("\"supersedes\":${u.supersedes?.sequence ?: "null"},\"anchors\":[")
    append(u.anchors.joinToString(",") { "[${it.unitRawStart},${it.unitRawEnd},${it.hypothesisStartMs},${it.hypothesisEndMs},${it.hypothesisRawStart},${it.hypothesisRawEnd},${it.estimatedTokenStartMs}]" })
    append("]}")
}
private fun emit(r: RecordedCoordinatorResult) {
    val action = when(val a = r.nextAction) {
        is RecordedNextAction.DecodeWindow -> "{\"kind\":\"DECODE_WINDOW\",\"startMs\":${a.startMs},\"durationMs\":${a.durationMs}}"
        is RecordedNextAction.DecodeUnion -> "{\"kind\":\"DECODE_UNION\",\"startMs\":${a.request.startMs},\"endMs\":${a.request.endMs}}"
        is RecordedNextAction.Translate -> "{\"kind\":\"TRANSLATE\",\"sequence\":${a.ticket.unit.id.sequence},\"attempt\":${a.ticket.attempt}}"
        RecordedNextAction.Finished -> "{\"kind\":\"FINISHED\"}"
    }
    val units = r.readyUnits.joinToString(",") { unitJson(it.unit) }
    val records = r.records.joinToString(",") { "{\"state\":${q(it.state.name)},\"source\":${unitJson(it.unit)},\"translatedText\":${q(it.translatedText)},\"error\":${q(it.error)},\"supersededBy\":${it.supersededBy?.sequence ?: "null"},\"deferredReason\":${q(it.deferredReason)}}" }
    val coverage = r.completedCoverage?.let { "{\"startMs\":${it.startMs},\"endMs\":${it.endMs},\"sequence\":${it.unitId.sequence}}" } ?: "null"
    println("{\"accepted\":${r.accepted},\"readThroughMs\":${r.readThroughMs},\"nextWindowStartMs\":${r.nextWindowStartMs},\"pendingText\":${q(r.pendingText)},\"readyUnits\":[$units],\"records\":[$records],\"completedCoverage\":$coverage,\"nextAction\":$action,\"error\":${q(r.error)}}")
}

/** No ASR/MT execution. H/X + T/Q + END protocol accepts independently captured real input. */
fun main() {
    val c = RecordedSourceUnitCoordinator(KuromojiJapaneseMorphology())
    var header: List<String>? = null
    val tokens = mutableListOf<RecordedToken>()
    val quiet = mutableListOf<RecordedQuietSpan>()
    val tickets = mutableMapOf<Long, RecordedTranslationTicket>()
    fun publish(r: RecordedCoordinatorResult) {
        r.records.filter { it.state == RecordedResultState.SUPERSEDED }.forEach { tickets.remove(it.unit.id.sequence) }
        r.readyUnits.forEach { tickets[it.unit.id.sequence] = it }
        emit(r)
    }
    generateSequence(::readlnOrNull).forEach { line ->
        if (line.isBlank() || line.startsWith("#")) return@forEach
        val p = line.split('\t')
        when(p[0]) {
            "H", "X" -> { require(header == null && p.size == 4); header = p; tokens.clear(); quiet.clear() }
            "T" -> { require(header != null && p.size == 3); tokens += RecordedToken(String(Base64.getDecoder().decode(p[2]), Charsets.UTF_8), p[1].toLong()) }
            "Q" -> { require(header != null && p.size == 3); quiet += RecordedQuietSpan(p[1].toLong(), p[2].toLong()) }
            "END" -> {
                val h = requireNotNull(header)
                val input = RecordedWindowInput(RecordedHypothesis(h[1].toLong(), h[2].toLong(), h[3].toLong(), tokens.toList()), quiet.toList())
                publish(if (h[0] == "X") c.recoverUnion(input) else c.push(input))
                header = null
            }
            "EOF" -> { require(header == null); publish(c.finish(p[1].toLong(), p.getOrNull(2)?.toLong() ?: c.snapshot().readThroughMs)) }
            "RESET" -> { require(header == null); tickets.clear(); publish(c.reset(p[1].toLong(), p.getOrNull(2)?.toLong() ?: 0)) }
            "FAIL_UNION" -> publish(c.failUnion(p[1].toLong()))
            "DEFER" -> {
                require(header == null && p.size == 2)
                val ticket = requireNotNull(tickets[p[1].toLong()])
                val result = c.deferUnconfirmed(ticket)
                if (result.accepted) tickets.remove(p[1].toLong())
                publish(result)
            }
            "MT_OK", "MT_FAIL" -> {
                val ticket = requireNotNull(tickets.remove(p[1].toLong()))
                val value = String(Base64.getDecoder().decode(p[2]), Charsets.UTF_8)
                publish(if (p[0] == "MT_OK") c.translationSucceeded(ticket, value) else c.translationFailed(ticket, value))
            }
            else -> error("Unknown command: ${p[0]}")
        }
    }
    require(header == null) { "Unfinished hypothesis" }
}
