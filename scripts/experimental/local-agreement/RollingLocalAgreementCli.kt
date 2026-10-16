package com.framenest.experiment.localagreement

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

private fun json(value: Any?): String = when (value) {
    null -> "null"
    is Boolean, is Number -> value.toString()
    is Enum<*> -> json(value.name)
    is String -> buildString {
        append('"')
        for (c in value) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\")
            '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
    is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { json(it.key) + ":" + json(it.value) }
    is List<*> -> value.joinToString(prefix = "[", postfix = "]") { json(it) }
    else -> error("Unsupported JSON type")
}

private fun request(r: RollingLocalAgreement.Request?) = r?.let { mapOf(
    "id" to it.id, "generation" to it.generation, "kind" to it.kind,
    "startSample" to it.startSample, "endSample" to it.endSample, "numSamples" to it.numSamples) }
private fun pending(p: RollingLocalAgreement.Pending?) = p?.let { mapOf(
    "contextStartSample" to it.contextStartSample, "observedEndSample" to it.observedEndSample,
    "fullRaw" to it.fullRaw, "pendingRawStart" to it.pendingRawStart, "text" to it.text,
    "retainedEligibilityRawStart" to it.retainedEligibilityRawStart,
    "retainedEligibilityKeyStart" to it.retainedEligibilityKeyStart,
    "retainedOriginSample" to it.retainedOriginSample) }
private fun snapshot(s: RollingLocalAgreement.Snapshot?) = s?.let { mapOf(
    "generation" to it.generation, "sourceStartSample" to it.sourceStartSample,
    "sourceEndSample" to it.sourceEndSample, "acceptedHypothesisCount" to it.acceptedHypothesisCount,
    "pending" to pending(it.pending), "candidate" to pending(it.candidate),
    "stableKeyPrefixCodePoints" to it.stableKeyPrefixCodePoints,
    "singleTranscriptAvailable" to it.singleTranscriptAvailable, "hardRestartCount" to it.hardRestartCount,
    "terminal" to it.terminal, "terminalReason" to it.terminalReason,
    "eofObserved" to it.eofObserved, "retainedRawCodePoints" to it.retainedRawCodePoints) }
private fun closed(c: RollingLocalAgreement.ClosedBlock?) = c?.let { mapOf(
    "status" to it.status, "reason" to it.reason, "contextStartSample" to it.contextStartSample,
    "observedEndSample" to it.observedEndSample, "remainder" to it.remainder,
    "oldPending" to pending(it.oldPending), "recoveryFull" to pending(it.recoveryFull),
    "mtUnit" to false, "finalCoverage" to false) }
private fun splice(a: RollingLocalAgreement.SpliceAudit?) = a?.let { mapOf(
    "generation" to it.generation,
    "oldContextStartSample" to it.oldContextStartSample, "oldObservedEndSample" to it.oldObservedEndSample,
    "oldPendingRawStart" to it.oldPendingRawStart, "oldStableKeyEndCodePoint" to it.oldStableKeyEndCodePoint,
    "oldRetainedEligibilityRawStart" to it.oldRetainedEligibilityRawStart,
    "oldRetainedEligibilityKeyStart" to it.oldRetainedEligibilityKeyStart,
    "oldRetainedOriginSample" to it.oldRetainedOriginSample,
    "anchorKey" to it.anchorKey, "anchorLetterDigitCount" to it.anchorLetterDigitCount,
    "oldAnchorKeyStartCodePoint" to it.oldAnchorKeyStartCodePoint, "oldAnchorKeyEndCodePoint" to it.oldAnchorKeyEndCodePoint,
    "oldAnchorRawStart" to it.oldAnchorRawStart, "oldAnchorRawEnd" to it.oldAnchorRawEnd,
    "oldDeltaRawStart" to it.oldDeltaRawStart, "oldDeltaRawEnd" to it.oldDeltaRawEnd,
    "oldReplacedTailRawStart" to it.oldReplacedTailRawStart, "oldReplacedTailRawEnd" to it.oldReplacedTailRawEnd,
    "oldReplacedTail" to it.oldReplacedTail,
    "candidateContextStartSample" to it.candidateContextStartSample,
    "candidateFirstEndSample" to it.candidateFirstEndSample, "candidateSecondEndSample" to it.candidateSecondEndSample,
    "candidateStableKeyEndCodePoint" to it.candidateStableKeyEndCodePoint,
    "candidateAnchorKeyStartCodePoint" to it.candidateAnchorKeyStartCodePoint,
    "candidateAnchorKeyEndCodePoint" to it.candidateAnchorKeyEndCodePoint,
    "candidateFirstAnchorRawStart" to it.candidateFirstAnchorRawStart,
    "candidateFirstAnchorRawEnd" to it.candidateFirstAnchorRawEnd,
    "candidateSecondAnchorRawStart" to it.candidateSecondAnchorRawStart,
    "candidateSecondAnchorRawEnd" to it.candidateSecondAnchorRawEnd,
    "candidateFirstRetainedEligibilityRawStart" to it.candidateFirstRetainedEligibilityRawStart,
    "candidateFirstRetainedEligibilityKeyStart" to it.candidateFirstRetainedEligibilityKeyStart,
    "candidateFirstRetainedOriginSample" to it.candidateFirstRetainedOriginSample,
    "candidateSecondRetainedEligibilityRawStart" to it.candidateSecondRetainedEligibilityRawStart,
    "candidateSecondRetainedEligibilityKeyStart" to it.candidateSecondRetainedEligibilityKeyStart,
    "candidateSecondRetainedOriginSample" to it.candidateSecondRetainedOriginSample,
    "newOmittedPrefixRawStart" to it.newOmittedPrefixRawStart,
    "newOmittedPrefixRawEnd" to it.newOmittedPrefixRawEnd,
    "newOmittedPrefix" to it.newOmittedPrefix, "newPendingRawStart" to it.newPendingRawStart) }
private fun recovery(a: RollingLocalAgreement.RecoveryAudit?) = a?.let { mapOf(
    "generation" to it.generation, "trigger" to it.trigger,
    "contextStartSample" to it.contextStartSample, "observedEndSample" to it.observedEndSample,
    "witnessRole" to it.witnessRole, "witnessRaw" to it.witnessRaw, "witnessKey" to it.witnessKey,
    "witnessRawEnd" to it.witnessRawEnd, "matched" to it.matched, "failure" to it.failure,
    "recoveryOmittedRawStart" to it.recoveryOmittedRawStart,
    "recoveryOmittedRawEnd" to it.recoveryOmittedRawEnd, "recoveryOmittedRaw" to it.recoveryOmittedRaw) }
private fun reset(a: RollingLocalAgreement.ResetAudit?) = a?.let { mapOf(
    "discardedGeneration" to it.discardedGeneration,
    "discardedAcceptedHypotheses" to it.discardedAcceptedHypotheses,
    "discardedRetainedRawCodePoints" to it.discardedRetainedRawCodePoints,
    "discardedRequestId" to it.discardedRequestId,
    "discardedSingleTranscriptAvailable" to it.discardedSingleTranscriptAvailable) }
private fun output(r: RollingLocalAgreement.Result) = mapOf(
    "protocolVersion" to 2,
    "status" to r.status, "error" to r.error, "request" to request(r.request),
    "snapshot" to snapshot(r.snapshot), "prefixDelta" to r.prefixDelta,
    "closedBlock" to closed(r.closedBlock), "spliceAudit" to splice(r.spliceAudit),
    "recoveryAudit" to recovery(r.recoveryAudit), "matchFailure" to r.matchFailure,
    "hardRestartAtSample" to r.hardRestartAtSample, "resetAudit" to reset(r.resetAudit),
    "textDisposition" to r.textDisposition, "mtUnit" to false, "finalCoverage" to false)

/** Explicit V2 commands require the eligibility cursor; no V1 fallback/default. */
fun main() {
    val component = RollingLocalAgreement()
    var lineNumber = 0L
    generateSequence(::readlnOrNull).forEach { line ->
        lineNumber++
        val result: Map<String, Any?> = try {
            require(line.length <= 32_768)
            val p = line.split('\t')
            val r = when (p[0]) {
                "RESET_V2" -> {
                    require(p.size == 4)
                    component.reset(p[1].toLong(), p[2].toLong(), p[3].toLong())
                }
                "H_V2" -> {
                    require(p.size == 8)
                    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(Base64.getDecoder().decode(p[6]))).toString()
                    component.observe(p[1].toLong(), p[2].toLong(), p[3].toLong(),
                        p[4].toLong(), p[5].toLong(), text, p[7].toInt())
                }
                "EOF_V2" -> { require(p.size == 2); component.finish(p[1].toLong()) }
                else -> throw IllegalArgumentException("Unknown operation")
            }
            output(r)
        } catch (_: IllegalArgumentException) {
            inputError(component)
        } catch (_: java.nio.charset.CharacterCodingException) {
            inputError(component)
        }
        println(json(result + ("line" to lineNumber)))
        System.out.flush()
    }
}

private fun inputError(c: RollingLocalAgreement) = mapOf(
    "protocolVersion" to 2,
    "status" to "INPUT_ERROR", "error" to "INVALID_TSV_OR_ENCODING",
    "request" to request(c.currentRequest()), "snapshot" to snapshot(c.snapshot()),
    "prefixDelta" to null, "closedBlock" to null, "spliceAudit" to null, "recoveryAudit" to null,
    "hardRestartAtSample" to null, "textDisposition" to "NOT_MT_UNIT", "mtUnit" to false, "finalCoverage" to false)
