package com.framenest.feature.listen_translate

import android.os.SystemClock
import com.framenest.BuildConfig
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.PinnedModels
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.PinnedPcmAudio
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.elapsed
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.now
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.pcmHash
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.sha256
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.stages
import com.framenest.feature.listen_translate.asr.AsrEngine
import com.framenest.feature.listen_translate.asr.AsrRecognition
import com.framenest.feature.listen_translate.asr.SherpaModelInstaller
import com.framenest.feature.listen_translate.audio.ListenAudioSource
import com.framenest.feature.listen_translate.mt.ListenMtEngine
import com.framenest.feature.listen_translate.recorded.KuromojiJapaneseMorphology
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Test-only actual engine observation. No retained baseline sources enter candidate inference. */
internal object EmptyRecoveryCaptionDiagnosticSupport {
    suspend fun collect(pcm: ShortArray, modelDir: File, actualAsr: AsrEngine, actualMt: ListenMtEngine,
        fixture: JSONObject, save: () -> Unit) {
        val baseline = fixture.getJSONObject("production_3s").getJSONArray("rows")
        val duration = fixture.getLong("duration_ms")
        val rows = JSONArray()
        val events = JSONArray()
        val report = JSONObject().put("status", "running").put("rows", rows).put("events", events)
            .put("empty_translation_recovery_enabled", true).put("generation", 1)
            .put("timing_scope", "MEASURED_CANDIDATE_PIPELINE_AFTER_SHARED_MODEL_PREPARATION_FULL_PCM_AVAILABLE")
            .put("first_source", JSONObject.NULL).put("first_target", JSONObject.NULL)
            .put("session_or_room_measured", false).put("rendering_measured", false)
            .put("stage_timings_are_not_end_to_end_wall_time", true)
            .put("process_rss_before_candidate_kib", processRssKiB())
            .put("memory_scope", "Best-effort whole-process /proc/self/status VmRSS snapshots in KiB before candidate and immediately after its measured pipeline, before independent morphology audit. These are not peak, causal or isolated tokenizer allocation measurements; null means unavailable.")
        fixture.put("empty_translation_recovery", report)
        val clock = now()
        val observation = Observation(clock, events, save)
        val audio = ObservedAudio(PinnedPcmAudio(pcm), observation)
        val asr = CandidateAsr(actualAsr, observation)
        val mt = CandidateMt(actualMt, observation)
        val engine = RealListenTranslateEngine(audio, asr, mt, PinnedModels(modelDir),
            asrModelLabel = { "${SherpaModelInstaller.MODEL_VERSION}|sherpa-${BuildConfig.SHERPA_RUNTIME_IDENTITY}" },
            monotonicTimeMs = { SystemClock.elapsedRealtimeNanos() / 1_000_000L },
            knownMediaDurationMs = { duration }, emptyTranslationRecoveryEnabled = true)
        val recoveryEngine: EmptyTranslationRecoveryEngine = engine
        val visited = mutableListOf<Int>()
        val completed = mutableListOf<Int>()
        val unresolved = mutableListOf<Int>()
        val outputSources = StringBuilder()
        var regressionPassed = true
        var pairCount = 0
        var index = 0
        var firstSource: JSONObject? = null
        var firstTarget: JSONObject? = null
        recoveryEngine.resetEmptyTranslationRecovery()
        check(recoveryEngine.supportsEmptyTranslationRecovery("ja", "zh"))
        save()
        try {
            while (index < baseline.length()) {
                val expected = baseline.getJSONObject(index)
                val startMs = expected.getLong("window_start_ms")
                val endMs = expected.getLong("window_end_ms")
                val row = JSONObject().put("source_unit_id", index).put("window_start_ms", startMs)
                    .put("window_end_ms", endMs).put("status", "running")
                    .put("request_started_wall_ms", elapsed(clock)).put("actual_asr_calls", JSONArray())
                    .put("actual_mt_calls", JSONArray()).put("actual_pcm_reads", JSONArray())
                    .put("source_events", JSONArray()).put("recovery_attempted", false)
                    .put("recovery_succeeded", false).put("recovery", JSONObject.NULL)
                rows.put(row)
                observation.select(row, index, startMs, endMs, "normal")
                val requestStart = now()
                val original = engine.processWindowWithProgress(startMs, endMs, "ja", "zh") { source ->
                    val sourceEvent = resultJson(source).put("event", "source_published")
                        .put("source_unit_id", index).put("observed_wall_ms", elapsed(clock))
                        .put("required_audio_horizon_ms", observation.horizon(index))
                        .put("required_window_horizon_ms", endMs)
                    row.getJSONArray("source_events").put(sourceEvent)
                    observation.event("source_published")
                    if (firstSource == null) firstSource = JSONObject(sourceEvent.toString())
                    report.put("first_source", firstSource ?: JSONObject.NULL)
                    save()
                }
                val normalReady = elapsed(clock)
                row.put("normal_request_wall_ms", elapsed(requestStart)).put("original_result", resultJson(original))
                    .put("normal_result_ready_wall_ms", normalReady)
                    .put("normal_required_audio_horizon_ms", observation.horizon(index))
                    .put("normal_required_window_horizon_ms", endMs)
                observation.event("normal_result_returned")
                val originalMt = callsInPhase(row.getJSONArray("actual_mt_calls"), "normal")
                val normalMatch = matchesBaseline(original, expected)
                val mtOutcomeMatch = outcomeList(originalMt) == outcomeList(expected.getJSONArray("actual_mt_calls"))
                val sourceEvents = row.getJSONArray("source_events")
                val sourceFirst = if (original.textSrc.isBlank()) sourceEvents.length() == 0 else {
                    sourceEvents.length() == 1 && sourceEvents.getJSONObject(0).let { source ->
                        source.getString("text_src") == original.textSrc && source.getString("text_tgt").isBlank() &&
                            sameNullable(source, resultJson(original), "cue_start_ms") &&
                            sameNullable(source, resultJson(original), "cue_end_ms") &&
                            source.getDouble("observed_wall_ms") <= normalReady &&
                            (originalMt.length() == 0 || source.getDouble("observed_wall_ms") <=
                                originalMt.getJSONObject(0).getDouble("started_wall_ms")) &&
                            (source.isNull("processed_through_ms") || source.getLong("processed_through_ms") == endMs)
                    }
                }
                val currentSpan = (original.processedThroughMs == null || original.processedThroughMs == endMs) &&
                    (original.textSrc.isBlank() || (original.cueStartMs != null && original.cueEndMs != null &&
                        original.cueStartMs >= startMs && original.cueEndMs <= endMs && original.cueEndMs > original.cueStartMs))
                val observedSource = original.textSrc.isBlank() || observation.horizon(index) > 0L
                val checks = JSONObject().put("normal_source_target_bounds_failure_equal_baseline", normalMatch)
                    .put("normal_mt_outcomes_equal_baseline", mtOutcomeMatch)
                    .put("source_first_current_span_preserved", sourceFirst)
                    .put("normal_current_span_preserved", currentSpan).put("normal_source_evidence_observed", observedSource)
                row.put("normal_uses_candidate_cached_source", original.textSrc.isNotBlank() &&
                    callsInPhase(row.getJSONArray("actual_asr_calls"), "normal").length() == 0)
                regressionPassed = regressionPassed && normalMatch && mtOutcomeMatch && sourceFirst && currentSpan && observedSource
                var output = original
                var outputEnd = endMs
                var outputReady = normalReady
                var outputHorizon = observation.horizon(index)
                val sourceIds = mutableListOf(index)
                // The actual call outcome is required; a failure/timeout with blank text is not empty MT.
                val actualEmptyMt = originalMt.length() == 1 &&
                    originalMt.getJSONObject(0).getString("status") == "empty_target"
                val eligible = original.textSrc.isNotBlank() && original.textTgt.isBlank() &&
                    original.retryableErrorMessage == null && original.blankReason == null && actualEmptyMt &&
                    endMs - startMs == 3_000L && index + 1 < baseline.length()
                row.put("actual_empty_mt", actualEmptyMt).put("eligible_for_one_adjacent_recovery", eligible)
                if (eligible) {
                    val nextExpected = baseline.getJSONObject(index + 1)
                    val nextEnd = minOf(endMs + 3_000L, duration)
                    check(nextExpected.getLong("window_start_ms") == endMs &&
                        nextExpected.getLong("window_end_ms") == nextEnd && index + 1 !in completed)
                    row.put("recovery_attempted", true).put("granted_next_source_unit_id", index + 1)
                        .put("granted_next_end_ms", nextEnd)
                    observation.select(row, index + 1, endMs, nextEnd, "recovery")
                    observation.event("recovery_requested")
                    val recoveryStart = now()
                    val recovered = recoveryEngine.recoverEmptyTranslation(startMs, endMs, nextEnd, "ja", "zh", original)
                    outputReady = elapsed(clock)
                    row.put("recovery_wall_ms", elapsed(recoveryStart)).put("recovery_returned_wall_ms", outputReady)
                    observation.event(if (recovered == null) "recovery_no_result" else "recovery_returned")
                    outputHorizon = maxOf(outputHorizon, observation.horizon(index + 1))
                    if (recovered != null) {
                        validateEmptyTranslationRecovery(startMs, endMs, nextEnd, original, recovered)
                        val nextSourceMatch = matchesBaselineSource(recovered.nextSource, nextExpected)
                        val canonical = expected.getString("text_src") + nextExpected.getString("text_src")
                        val exactBytes = canonical.toByteArray(Charsets.UTF_8)
                            .contentEquals(recovered.result.textSrc.toByteArray(Charsets.UTF_8))
                        val recoveryCalls = callsInPhase(row.getJSONArray("actual_mt_calls"), "recovery")
                        val exactlyOneMergedMt = recoveryCalls.length() == 1 && recoveryCalls.getJSONObject(0).let {
                            it.getString("input_text") == recovered.result.textSrc &&
                                it.getString("target_text") == recovered.result.textTgt && it.getString("status") == "returned"
                        }
                        row.put("recovery_succeeded", true).put("recovery", JSONObject()
                            .put("next_source", resultJson(recovered.nextSource)).put("result", resultJson(recovered.result))
                            .put("canonical_source_utf8_sha256", sha256(canonical.toByteArray(Charsets.UTF_8)))
                            .put("recovered_source_utf8_sha256", sha256(recovered.result.textSrc.toByteArray(Charsets.UTF_8)))
                            .put("canonical_source_utf8_bytes_equal", exactBytes))
                        checks.put("recovery_next_source_equals_baseline", nextSourceMatch)
                            .put("recovery_canonical_source_utf8_bytes_equal", exactBytes)
                            .put("exactly_one_actual_merged_mt", exactlyOneMergedMt)
                        regressionPassed = regressionPassed && nextSourceMatch && exactBytes && exactlyOneMergedMt
                        output = recovered.result; outputEnd = nextEnd; sourceIds += index + 1; pairCount++
                    } else {
                        // Failed attempts cannot complete either source or overwrite the original cue.
                        checks.put("null_recovery_preserves_original_result", output == original)
                        checks.put("null_recovery_does_not_consume_next", sourceIds == listOf(index))
                    }
                }
                val resolved = output.retryableErrorMessage == null &&
                    ((output.textSrc.isNotBlank() && output.textTgt.isNotBlank()) ||
                        output.blankReason == ListenBlankReason.NearSilence)
                visited += sourceIds
                if (resolved) completed += sourceIds else unresolved += sourceIds
                outputSources.append(output.textSrc)
                val recoveryMtCalls = callsInPhase(row.getJSONArray("actual_mt_calls"), "recovery")
                val boundedCalls = originalMt.length() <= 1 && recoveryMtCalls.length() <= 1 &&
                    callsInPhase(row.getJSONArray("actual_asr_calls"), "normal").length() <= 1 &&
                    callsInPhase(row.getJSONArray("actual_asr_calls"), "recovery").length() <= 1
                checks.put("bounded_actual_calls", boundedCalls)
                regressionPassed = regressionPassed && boundedCalls
                row.put("regression_checks", checks).put("output_result", resultJson(output))
                    .put("output_source_unit_ids", JSONArray(sourceIds)).put("output_window_end_ms", outputEnd)
                    .put("completed_source_unit_ids", JSONArray(if (resolved) sourceIds else emptyList<Int>()))
                    .put("unresolved_source_unit_ids", JSONArray(if (resolved) emptyList<Int>() else sourceIds))
                    .put("required_audio_horizon_ms", outputHorizon)
                    .put("required_window_horizon_ms", if (eligible) minOf(endMs + 3_000L, duration) else endMs)
                    .put("target_ready_wall_ms", if (output.textTgt.isNotBlank()) outputReady else JSONObject.NULL)
                    .put("request_wall_ms", elapsed(requestStart)).put("status", "collected")
                if (firstTarget == null && output.textTgt.isNotBlank()) {
                    firstTarget = JSONObject().put("source_unit_ids", JSONArray(sourceIds))
                        .put("text_src", output.textSrc).put("text_tgt", output.textTgt)
                        .put("observed_wall_ms", outputReady).put("required_audio_horizon_ms", outputHorizon)
                        .put("required_window_horizon_ms", row.getLong("required_window_horizon_ms"))
                }
                report.put("first_target", firstTarget ?: JSONObject.NULL)
                index += sourceIds.size
                save()
            }
            // Capture the true candidate interval before independent post-run audits.
            report.put("wall_ms", elapsed(clock)).put("process_rss_after_candidate_kib", processRssKiB())
                .put("asr_call_count", observation.asrCount)
                .put("mt_call_count", observation.mtCount).put("normal_request_count", rows.length())
                .put("recovered_pair_count", pairCount).put("visited_source_unit_ids", JSONArray(visited))
                .put("completed_source_unit_ids", JSONArray(completed)).put("unresolved_source_unit_ids", JSONArray(unresolved))
                .put("audio_grid_traversal_complete", visited == (0 until baseline.length()).toList())
                .put("maximum_observed_audio_horizon_ms", observation.readsBySource.values.maxOrNull() ?: 0L)
                .put("asr_calls_per_source_unit", JSONObject(observation.asrBySource.mapKeys { it.key.toString() }))
            val canonical = (0 until baseline.length()).joinToString("") { baseline.getJSONObject(it).getString("text_src") }
            val conserved = canonical.toByteArray(Charsets.UTF_8).contentEquals(outputSources.toString().toByteArray(Charsets.UTF_8))
            val exactOwnership = visited == (0 until baseline.length()).toList()
            report.put("source_conservation", JSONObject().put("exact_utf8_bytes_equal", conserved)
                .put("source_ids_exact_once", exactOwnership)
                .put("baseline_sha256", sha256(canonical.toByteArray(Charsets.UTF_8)))
                .put("candidate_sha256", sha256(outputSources.toString().toByteArray(Charsets.UTF_8))))
            regressionPassed = regressionPassed && conserved && exactOwnership
            // Evidence-only audit: no translation, no new source, excluded from candidate wall time.
            val auditStart = now()
            val morphology by lazy { KuromojiJapaneseMorphology() }
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                if (!row.getBoolean("recovery_succeeded")) continue
                val seam = row.getJSONObject("original_result").getString("text_src").length
                val text = row.getJSONObject("output_result").getString("text_src")
                val crossings = morphology.analyze(text).filter { it.rawStart < seam && seam < it.rawEnd }
                row.getJSONObject("recovery").put("post_timing_crossing_morphemes", JSONArray(crossings.map {
                    JSONObject().put("raw_start_utf16", it.rawStart).put("raw_end_utf16", it.rawEnd)
                        .put("surface", it.surface).put("known", it.known)
                }))
                row.getJSONObject("regression_checks").put("successful_recovery_has_crossing_morpheme", crossings.isNotEmpty())
                regressionPassed = regressionPassed && crossings.isNotEmpty()
            }
            report.put("post_timing_crossing_audit_ms", elapsed(auditStart))
                .put("post_timing_crossing_audit_excluded_from_wall", true)
                .put("regression_checks_passed", regressionPassed).put("status", "collected")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failure_class", failure.javaClass.name)
                .put("elapsed_before_failure_ms", elapsed(clock))
            throw failure
        } finally { engine.close(); save() }
    }

    private fun processRssKiB(): Any = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
        }
    }.getOrNull() ?: JSONObject.NULL

    private fun resultJson(result: ListenWindowResult) = JSONObject().put("text_src", result.textSrc)
        .put("text_tgt", result.textTgt).put("cue_start_ms", result.cueStartMs ?: JSONObject.NULL)
        .put("cue_end_ms", result.cueEndMs ?: JSONObject.NULL).put("blank_reason", result.blankReason?.name ?: JSONObject.NULL)
        .put("translation_retryable_failure", result.retryableErrorMessage != null)
        .put("retryable_error_message", result.retryableErrorMessage ?: JSONObject.NULL)
        .put("processed_through_ms", result.processedThroughMs ?: JSONObject.NULL).put("stage_timings", stages(result.stageTimings))

    private fun sameNullable(left: JSONObject, right: JSONObject, key: String) =
        if (left.isNull(key) || right.isNull(key)) left.isNull(key) && right.isNull(key)
        else left.get(key) == right.get(key)

    private fun matchesBaselineSource(actual: ListenWindowResult, expected: JSONObject): Boolean {
        val value = resultJson(actual)
        return value.getString("text_src") == expected.getString("text_src") &&
            sameNullable(value, expected, "cue_start_ms") && sameNullable(value, expected, "cue_end_ms") &&
            sameNullable(value, expected, "blank_reason")
    }

    private fun matchesBaseline(actual: ListenWindowResult, expected: JSONObject) =
        matchesBaselineSource(actual, expected) && actual.textTgt == expected.getString("text_tgt") &&
            (actual.retryableErrorMessage != null) == expected.getBoolean("translation_retryable_failure")

    private fun callsInPhase(calls: JSONArray, phase: String): JSONArray = JSONArray((0 until calls.length())
        .map { calls.getJSONObject(it) }.filter { it.getString("phase") == phase })

    private fun outcomeList(calls: JSONArray) = (0 until calls.length()).map {
        calls.getJSONObject(it).let { call -> listOf(call.getString("input_text"), call.optString("target_text"),
            call.getString("status"), call.optString("failure_class")) }
    }

    private class Observation(val clock: Long, val events: JSONArray, val save: () -> Unit) {
        lateinit var row: JSONObject
        var sourceId = 0
        var startMs = 0L
        var endMs = 0L
        var phase = "normal"
        var asrCount = 0
        var mtCount = 0
        val readsBySource = mutableMapOf<Int, Long>()
        val asrBySource = mutableMapOf<Int, Int>()
        fun select(row: JSONObject, sourceId: Int, startMs: Long, endMs: Long, phase: String) {
            this.row = row; this.sourceId = sourceId; this.startMs = startMs; this.endMs = endMs; this.phase = phase
        }
        fun call() = JSONObject().put("source_unit_id", sourceId).put("phase", phase)
            .put("window_start_ms", startMs).put("window_end_ms", endMs).put("started_wall_ms", elapsed(clock))
        fun horizon(sourceId: Int) = readsBySource[sourceId] ?: 0L
        fun event(type: String) {
            events.put(JSONObject().put("event", type).put("source_unit_id", sourceId).put("phase", phase)
                .put("observed_wall_ms", elapsed(clock)))
        }
    }

    private class ObservedAudio(private val delegate: PinnedPcmAudio, private val o: Observation) : ListenAudioSource by delegate {
        override suspend fun pcmWindow(startMs: Long, endMs: Long, preferredAudioTrackOrdinal: Int?): ShortArray {
            val call = o.call().put("status", "running")
            o.row.getJSONArray("actual_pcm_reads").put(call)
            val start = now()
            try {
                return delegate.pcmWindow(startMs, endMs, preferredAudioTrackOrdinal).also {
                    val read = requireNotNull(delegate.lastRead)
                    call.put("read", JSONObject(read.toString())).put("status", "returned")
                    o.readsBySource[o.sourceId] = read.getLong("actual_end_ms")
                    o.event("pcm_read_returned")
                }
            } catch (failure: Throwable) {
                call.put("status", "failed").put("failure_class", failure.javaClass.name); throw failure
            } finally { call.put("wall_ms", elapsed(start)); o.save() }
        }
    }

    private class CandidateAsr(private val delegate: AsrEngine, private val o: Observation) : AsrEngine by delegate {
        override fun close() = Unit
        override suspend fun recognize(pcm16kMono: ShortArray): AsrRecognition {
            val call = o.call().put("input_pcm_samples", pcm16kMono.size).put("input_pcm_s16le_sha256", pcmHash(pcm16kMono))
                .put("status", "running")
            o.row.getJSONArray("actual_asr_calls").put(call)
            o.asrCount++; o.asrBySource[o.sourceId] = (o.asrBySource[o.sourceId] ?: 0) + 1
            o.event("asr_requested"); o.save()
            val start = now()
            try {
                return delegate.recognize(pcm16kMono).also { result ->
                    val decodeStart = maxOf(0L, o.startMs - 750L)
                    val selected = selectWordsForWindow(result.words, decodeStart, o.startMs, o.endMs)
                    call.put("status", "returned").put("full_recognition_text", result.text).put("text_separator", result.textSeparator)
                        .put("mapped_tokens", JSONArray(result.words.map { word -> JSONObject().put("text", word.text)
                            .put("start_ms_relative_to_decode", word.startMs).put("end_ms_relative_to_decode", word.endMs)
                            .put("start_ms_clip", decodeStart + word.startMs).put("end_ms_clip", decodeStart + word.endMs)
                            .put("selected_by_existing_midpoint_rule", word in selected) }))
                    o.event("asr_returned")
                }
            } catch (failure: Throwable) {
                call.put("status", "failed").put("failure_class", failure.javaClass.name); throw failure
            } finally { call.put("wall_ms", elapsed(start)).put("finished_wall_ms", elapsed(o.clock)); o.save() }
        }
    }

    private class CandidateMt(private val delegate: ListenMtEngine, private val o: Observation) : ListenMtEngine by delegate {
        override fun close() = Unit
        override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
            val call = o.call().put("input_text", text).put("source_language", sourceLang).put("target_language", targetLang)
                .put("status", "running")
            o.row.getJSONArray("actual_mt_calls").put(call); o.mtCount++; o.event("mt_requested"); o.save()
            val start = now()
            try {
                return delegate.translate(text, sourceLang, targetLang).also {
                    call.put("target_text", it).put("status", if (it.isBlank()) "empty_target" else "returned")
                    o.event(if (it.isBlank()) "mt_empty_target" else "mt_returned")
                }
            } catch (failure: Throwable) {
                call.put("status", "failed").put("failure_class", failure.javaClass.name); o.event("mt_failed"); throw failure
            } finally { call.put("wall_ms", elapsed(start)).put("finished_wall_ms", elapsed(o.clock)); o.save() }
        }
    }
}
