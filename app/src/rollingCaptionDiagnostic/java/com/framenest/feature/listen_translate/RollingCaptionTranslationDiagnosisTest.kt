package com.framenest.feature.listen_translate

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framenest.BuildConfig
import com.framenest.experiment.localagreement.RollingLocalAgreement
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.ObservedAsr
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.ObservedMt
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.PinnedModels
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.PinnedPcmAudio
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.ROOT
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.SOURCE_AAR_SHA256
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.copyVerifiedAsset
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.decodePcm
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.elapsed
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.jsonAsset
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.now
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.pcmHash
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.sha256
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.stages
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.verifiedAsset
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.verifyReference
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.verifySourceProfile
import com.framenest.feature.listen_translate.RollingCaptionDiagnosticSupport.writeReport
import com.framenest.feature.listen_translate.asr.SherpaAsrEngine
import com.framenest.feature.listen_translate.asr.SherpaModelInstaller
import com.framenest.feature.listen_translate.mt.MlKitMtEngine
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.VersionInfo
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FN-77 opt-in Android diagnostic, not a subtitle strategy or a release gate.
 * Build with -ProllingCaptionDiagnosis=true; run only with explicit network and
 * retained APK hash arguments. No device job, download, or runtime runs by default.
 * Raw evidence stays in the app-private report for separately authorized retrieval.
 */
@RunWith(AndroidJUnit4::class)
class RollingCaptionTranslationDiagnosisTest {
    @Test
    fun compareRollingRequestsWithThreeSecondBaseline(): Unit = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("rollingCaptionDiagnosis") == "true")
        require(arguments.getString("captionDiagnosisNetwork") == "true") { "Explicit ML Kit model-download opt-in required" }
        withTimeout(30 * 60_000L) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val assets = instrumentation.context.assets
            val file = File(context.filesDir, "rolling-caption-translation-diagnosis.json")
            val report = JSONObject().put("schema_version", 1).put("status", "verifying_inputs")
                .put("collection_complete", false).put("run_started_epoch_ms", System.currentTimeMillis())
            fun save() = writeReport(file, report)
            save()
            assertTrue(BuildConfig.SHERPA_ASR_SOURCE)
            assertFalse(BuildConfig.HY_MT_NATIVE)
            assertEquals(SOURCE_AAR_SHA256, BuildConfig.SHERPA_AAR_SHA256)
            assertEquals("1.13.8-asr-no-tts-sha256-$SOURCE_AAR_SHA256", BuildConfig.SHERPA_RUNTIME_IDENTITY)
            assertEquals("1.13.8", BuildConfig.SHERPA_RUNTIME_VERSION)
            assertEquals("1.13.8", VersionInfo.version.removePrefix("v"))
            assertEquals(3_000L, ListenTranslateWindows.DEFAULT_WINDOW_MS)
            val appApk = File(context.applicationInfo.sourceDir)
            val appHash = sha256(appApk)
            val testHash = sha256(File(instrumentation.context.applicationInfo.sourceDir))
            for ((name, actual) in listOf("expectedAppApkSha256" to appHash, "expectedTestApkSha256" to testHash)) {
                val expected = requireNotNull(arguments.getString(name)) { "Supply retained APK hash: $name" }
                require(expected.matches(Regex("[0-9a-f]{64}")))
                assertEquals("Installed APK must equal retained build", expected, actual)
            }
            val manifestBytes = assets.open("$ROOT/manifest.json").use { it.readBytes() }
            val fixture = CaptionDiagnosisFixture(JSONObject(manifestBytes.toString(Charsets.UTF_8)))
            val comparisonBytes = assets.open("$ROOT/fixed-window-comparison.json").use { it.readBytes() }
            val comparison = FixedCaptionWindowFixture(JSONObject(comparisonBytes.toString(Charsets.UTF_8)))
            val preparedBytes = assets.open("$ROOT/generated/prepared.json").use { it.readBytes() }
            val prepared = JSONObject(preparedBytes.toString(Charsets.UTF_8))
            val comparisonPrepared = jsonAsset(assets, "$ROOT/generated/comparison-prepared.json")
            assertTrue(prepared.getBoolean("all_inputs_verified"))
            assertTrue(comparisonPrepared.getBoolean("all_inputs_verified"))
            assertEquals(sha256(manifestBytes), prepared.getString("manifest_sha256"))
            assertEquals(sha256(manifestBytes), comparisonPrepared.getString("diagnosis_manifest_sha256"))
            assertEquals(sha256(preparedBytes), comparisonPrepared.getString("diagnosis_prepared_sha256"))
            assertEquals(sha256(comparisonBytes), comparisonPrepared.getString("comparison_manifest_sha256"))
            val profileBytes = assets.open("$ROOT/source-profile.json").use { it.readBytes() }
            assertEquals(sha256(profileBytes), prepared.getString("source_profile_sha256"))
            val profile = verifySourceProfile(JSONObject(profileBytes.toString(Charsets.UTF_8)), appApk)
            val referenceBytes = verifiedAsset(assets, fixture.json.getJSONObject("reference"))
            val reference = JSONObject(referenceBytes.toString(Charsets.UTF_8))
            verifyReference(fixture, reference)
            val inputs = listOf(fixture.json to fixture.decodePcm(verifiedAsset(assets, fixture.json.getJSONObject("pcm"))),
                comparison.emoji to decodePcm(verifiedAsset(assets, comparison.emoji.getJSONObject("pcm"))))
            val modelDir = File(context.cacheDir, "caption-diagnosis-model").apply { mkdirs() }
            val modelInputs = fixture.json.getJSONArray("models")
            for (i in 0 until modelInputs.length()) copyVerifiedAsset(assets, modelInputs.getJSONObject(i), modelDir)
            assertTrue(SherpaModelInstaller.verifyPack(modelDir))

            val referenceRows = JSONArray()
            val rawReferenceRows = JSONArray()
            val fixtureRows = JSONArray()
            report.put("status", "running").put("app_apk_sha256", appHash).put("test_apk_sha256", testHash)
                .put("source_aar_sha256", BuildConfig.SHERPA_AAR_SHA256).put("runtime_identity", BuildConfig.SHERPA_RUNTIME_IDENTITY)
                .put("native_version", VersionInfo.version).put("native_git_sha1", VersionInfo.gitSha1).put("native_git_date", VersionInfo.gitDate)
                .put("verified_source_profile", profile).put("diagnosis_manifest_sha256", sha256(manifestBytes))
                .put("comparison_manifest_sha256", sha256(comparisonBytes)).put("prepared_inputs", prepared)
                .put("comparison_prepared_inputs", comparisonPrepared).put("reference_sha256", sha256(referenceBytes))
                .put("reference_kind", reference.getString("reference_kind")).put("reference_is_verbatim_gold", false)
                .put("reference_independently_listened", false).put("reference_to_mt", referenceRows)
                .put("reference_span_native_asr_to_mt", rawReferenceRows).put("fixtures", fixtureRows)
                .put("expected_fixture_count", 2).put("expected_reference_unit_count", fixture.units.size)
                .put("api", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS.first())
                .put("device_model", Build.MODEL).put("device_manufacturer", Build.MANUFACTURER)
                .put("accuracy_accepted", false).put("hardware_performance_accepted", false)
                .put("semantic_acceptance_passed", JSONObject.NULL).put("final_coverage_claimed", false)
                .put("production_default_window_ms", ListenTranslateWindows.DEFAULT_WINDOW_MS)
                .put("preview_media_deadline_ms", 6_000).put("bounded_hypothesis_max_ms", 18_000)
                .put("scope", "Exact public PCM -> real source SenseVoice 1.13.8 -> rolling request planner -> real ML Kit ja/zh. Test-only; no NAS/codec/Room/player/source-boundary/renderer implementation or measurement.")
                .put("timing_scope", "Full PCM available ahead; no realtime pacing. Report clip observed-audio horizons separately from monotonic actual availability and per-call wall times. Six seconds is not a wall-time guarantee. Quality-ceiling MT runs after bounded requests, excluded from candidate latency.")
                .put("order_and_warm_state", "One ML Kit and one raw native recognizer prepared once; reference-text controls then reference-span controls, then MCDC production3s/rolling/quality-ceilings, then Emoji production3s/rolling/quality-ceilings. One shared production adapter prepared before controls. No cold/warm speedup inference.")
                .put("ownership_scope", "Every sentence candidate and full hypothesis preview may overlap across origins; source times describe ASR context only, not cue/word ownership. Preview supersession is proposal metadata, not an implemented renderer. No prefixDelta is independently translated. Closed assembled groups are QUALITY_CEILING_ONLY.")
            val mt = MlKitMtEngine(allowMeteredDownloads = true)
            val productionAsr = SherpaAsrEngine()
            var native: OfflineRecognizer? = null
            save()
            try {
                var preparation = now(); mt.ensureModel("ja", "zh")
                report.put("shared_mt_preparation_ms", elapsed(preparation))
                preparation = now(); productionAsr.ensureModel(modelDir, "ja")
                report.put("shared_production_asr_preparation_ms", elapsed(preparation))
                val config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(model = File(modelDir, SherpaModelInstaller.MODEL_FILE).absolutePath,
                        language = "ja", useInverseTextNormalization = true),
                    tokens = File(modelDir, SherpaModelInstaller.TOKENS_FILE).absolutePath,
                    numThreads = SherpaAsrEngine.DEFAULT_NUM_THREADS, debug = false, provider = "cpu"),
                    decodingMethod = "greedy_search", maxActivePaths = 4)
                preparation = now(); native = OfflineRecognizer(config = config)
                report.put("shared_raw_asr_preparation_ms", elapsed(preparation)).put("raw_asr_config", config.toString())
                val recognizer = native
                save()
                for (unit in fixture.units) {
                    val row = JSONObject().put("reference_unit_id", unit.id).put("input_text", unit.text)
                        .put("source_start_ms", unit.startMs).put("source_end_ms", unit.endMs)
                        .put("purpose", "REFERENCE_QUALITY_CONTROL_NOT_LATENCY_UNIT")
                    referenceRows.put(row); translate(mt, unit.text, row, ::save)
                }
                for (unit in fixture.units) {
                    val row = JSONObject().put("reference_unit_id", unit.id).put("source_start_ms", unit.startMs)
                        .put("source_end_ms", unit.endMs).put("purpose", "REFERENCE_SPAN_ASR_QUALITY_CONTROL_NOT_LATENCY_UNIT")
                    rawReferenceRows.put(row)
                    val decoded = decode(recognizer, inputs[0].second, unit.startMs * 16L, unit.endMs * 16L, row)
                    translate(mt, decoded.raw, row, ::save)
                }
                for ((identity, pcm) in inputs) {
                    val duration = identity.getLong("duration_ms")
                    assertEquals(duration * 16L, pcm.size.toLong())
                    val row = JSONObject().put("fixture_id", identity.getString("id"))
                        .put("duration_ms", duration).put("original_media_start_ms", identity.getLong("original_media_start_ms"))
                        .put("pcm", identity.getJSONObject("pcm")).put("source_url", identity.getString("source_url"))
                        .put("license", identity.getString("license")).put("license_url", identity.getString("license_url"))
                        .put("attribution", identity.getString("attribution"))
                        .put("reference_kind", if (identity === fixture.json) reference.getString("reference_kind") else identity.getString("reference_kind"))
                    fixtureRows.put(row); save()
                    baseline(pcm, duration, modelDir, productionAsr, mt, row, ::save)
                    rolling(pcm, recognizer, mt, row, ::save)
                }
                report.put("status", "collected").put("collection_complete", true)
                    .put("collected_fixture_count", fixtureRows.length())
                    .put("reference_mt_attempt_count", referenceRows.length())
                    .put("reference_span_mt_attempt_count", rawReferenceRows.length())
                    .put("reference_mt_failed_or_empty_count", count(referenceRows) { it.optString("mt_status") != "returned" })
                    .put("reference_span_mt_failed_or_empty_count", count(rawReferenceRows) { it.optString("mt_status") != "returned" })
                    .put("semantic_review_required", "Review actual ASR, every requested MT input/target, revisions, overlap and all baseline boundaries. Neither full audio traversal nor stable punctuation proves completeness, semantic accuracy, display viability or target-phone performance.")
            } catch (failure: Throwable) {
                report.put("status", "failed").put("failure_class", failure.javaClass.name)
                throw failure
            } finally {
                try { native?.release() } finally { try { productionAsr.close() } finally { mt.close(); save() } }
            }
        }
        Unit
    }

    private suspend fun baseline(pcm: ShortArray, duration: Long, modelDir: File,
        actualAsr: SherpaAsrEngine, actualMt: MlKitMtEngine, fixtureRow: JSONObject, save: () -> Unit) {
        val rows = JSONArray()
        val report = JSONObject().put("rows", rows).put("status", "running").put("window_ms", 3_000)
            .put("expected_window_count", (duration + 2_999L) / 3_000L)
        fixtureRow.put("production_3s", report)
        val audio = PinnedPcmAudio(pcm)
        val asr = ObservedAsr(actualAsr)
        val mt = ObservedMt(actualMt)
        val engine = RealListenTranslateEngine(audio, asr, mt, PinnedModels(modelDir),
            asrModelLabel = { "${SherpaModelInstaller.MODEL_VERSION}|sherpa-${BuildConfig.SHERPA_RUNTIME_IDENTITY}" },
            monotonicTimeMs = { SystemClock.elapsedRealtimeNanos() / 1_000_000L })
        val start = now()
        var through = 0L
        try {
            while (through < duration) {
                val end = minOf(through + 3_000L, duration)
                val row = JSONObject().put("window_start_ms", through).put("window_end_ms", end).put("status", "running")
                rows.put(row)
                asr.calls = JSONArray(); mt.calls = JSONArray(); audio.lastRead = null
                asr.decodeStartMs = maxOf(0, through - 750L); asr.windowStartMs = through; asr.windowEndMs = end
                row.put("actual_asr_calls", asr.calls).put("actual_mt_calls", mt.calls)
                asr.onUpdate = save; mt.onUpdate = save
                val requestStart = now()
                val result = engine.processWindowWithProgress(through, end, "ja", "zh") { source ->
                    row.put("source_ready_wall_ms", elapsed(start)).put("source_request_wall_ms", elapsed(requestStart))
                        .put("published_source_text", source.textSrc); save()
                }
                row.put("text_src", result.textSrc).put("text_tgt", result.textTgt)
                    .put("target_ready_wall_ms", if (result.textTgt.isNotBlank()) elapsed(start) else JSONObject.NULL)
                    .put("request_wall_ms", elapsed(requestStart)).put("stage_timings", stages(result.stageTimings))
                    .put("cue_start_ms", result.cueStartMs ?: JSONObject.NULL).put("cue_end_ms", result.cueEndMs ?: JSONObject.NULL)
                    .put("blank_reason", result.blankReason?.name ?: JSONObject.NULL)
                    .put("translation_retryable_failure", result.retryableErrorMessage != null)
                    .put("actual_pcm_read", audio.lastRead ?: JSONObject.NULL).put("status", "collected")
                assertTrue(asr.calls.length() <= 1)
                assertEquals(if (result.textSrc.isBlank()) 0 else 1, mt.calls.length())
                through = end
                report.put("processed_through_ms", through).put("audio_traversal_complete", through == duration)
                    .put("completed_window_count", rows.length())
                save()
            }
            report.put("status", "collected")
        } finally { report.put("wall_ms", elapsed(start)); engine.close(); save() }
    }

    private data class Decoded(val raw: String, val tokens: List<String>, val onsets: List<Float>)
    private fun decode(native: OfflineRecognizer, pcm: ShortArray, start: Long, end: Long, row: JSONObject): Decoded {
        require(start >= 0L && end > start && end <= pcm.size)
        val readStart = now()
        val samples = pcm.copyOfRange(start.toInt(), end.toInt())
        row.put("pcm_copy_ms", elapsed(readStart)).put("pcm_samples", samples.size).put("pcm_s16le_sha256", pcmHash(samples))
        val asrStart = now()
        val stream = native.createStream()
        try {
            stream.acceptWaveform(FloatArray(samples.size) { samples[it] / 32768f }, 16_000)
            native.decode(stream)
            val result = native.getResult(stream)
            row.put("raw_text", result.text).put("raw_tokens", JSONArray(result.tokens.toList()))
                .put("token_onsets_seconds_relative", JSONArray(result.timestamps.toList()))
                .put("token_onsets_seconds_clip", JSONArray(result.timestamps.map { start / 16000.0 + it }))
                .put("native_durations_seconds", JSONArray(result.durations.toList()))
            return Decoded(result.text, result.tokens.toList(), result.timestamps.toList())
        } finally { stream.release(); row.put("asr_ms_including_stream_lifecycle", elapsed(asrStart)) }
    }

    private suspend fun translate(mt: MlKitMtEngine, text: String, row: JSONObject, save: () -> Unit) {
        row.put("input_text", text).put("source_language", "ja").put("target_language", "zh")
        if (text.isBlank()) { row.put("mt_status", "skipped_empty_source").put("target_text", ""); save(); return }
        row.put("mt_status", "running"); save()
        val start = now()
        try {
            val target = mt.translate(text, "ja", "zh")
            row.put("target_text", target).put("mt_status", if (target.isBlank()) "empty_target" else "returned")
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Throwable) {
            row.put("mt_status", "failed").put("mt_failure_class", failure.javaClass.name)
        } finally { row.put("mt_ms", elapsed(start)); save() }
    }

    private suspend fun rolling(pcm: ShortArray, native: OfflineRecognizer, mt: MlKitMtEngine,
        fixtureRow: JSONObject, save: () -> Unit) {
        val controller = RollingLocalAgreement()
        val planner = RollingCaptionRequestPlanner()
        val rows = JSONArray()
        val units = JSONArray()
        val ceilings = JSONArray()
        val revisions = JSONArray()
        val report = JSONObject().put("status", "running").put("asr_requests", rows)
            .put("bounded_mt_units", units).put("candidate_revisions", revisions).put("quality_ceiling_groups", ceilings)
            .put("controller_protocol_version", 2).put("generation", 1)
            .put("final_coverage", false).put("source_boundary_implemented", false).put("renderer_implemented", false)
        fixtureRow.put("rolling", report)
        var result = controller.reset(1, 0, pcm.size.toLong())
        val assembled = StringBuilder()
        var groupStart = 0L
        var asrTotalMs = 0.0
        var highestEnd = 0L
        var mtTotalMs = 0.0
        var firstSentenceSource: Double? = null
        var firstSentenceTarget: Double? = null
        var firstPreviewSource: Double? = null
        var firstPreviewTarget: Double? = null
        val start = now()
        try {
            while (result.request != null) {
                val request = requireNotNull(result.request)
                require(request.numSamples <= RollingCaptionRequestPlanner.MAX_HYPOTHESIS)
                val row = JSONObject().put("request_id", request.id).put("kind", request.kind.name)
                    .put("context_start_sample", request.startSample).put("observed_end_sample", request.endSample)
                    .put("context_start_ms", request.startSample / 16.0).put("observed_end_ms", request.endSample / 16.0)
                    .put("input_duration_ms", request.numSamples / 16.0).put("request_started_wall_ms", elapsed(start))
                    .put("final_coverage", false).put("status", "running")
                rows.put(row); save()
                val decoded = decode(native, pcm, request.startSample, request.endSample, row)
                val sourceReady = elapsed(start)
                row.put("source_ready_wall_ms", sourceReady)
                val cursor = RollingCaptionRequestPlanner.eligibilityRawStart(decoded.raw, decoded.tokens,
                    decoded.onsets, request.startSample, request.endSample)
                row.put("tokens_exactly_reconstruct_raw", true).put("retained_eligibility_raw_start", cursor)
                    .put("retained_origin_sample", maxOf(request.startSample, request.endSample - RollingLocalAgreement.RETAIN))
                    .put("eligibility_policy", "First original token of any kind with exact onset >= retained origin; strict join==raw; no tolerance, lexical prefilter, PCM cuts or raw cleanup")
                result = controller.observe(request.generation, request.id, request.startSample, request.endSample,
                    request.numSamples, decoded.raw, cursor)
                check(result.status != RollingLocalAgreement.Status.REJECTED)
                row.put("controller_status", result.status.name).put("controller_match_failure", result.matchFailure?.name ?: JSONObject.NULL)
                    .put("prefix_delta_not_mt_unit", result.prefixDelta ?: JSONObject.NULL)
                    .put("controller_closed_block", closed(result.closedBlock))
                    .put("controller_splice", splice(result.spliceAudit)).put("controller_recovery", recovery(result.recoveryAudit))
                    .put("hard_restart_at_sample", result.hardRestartAtSample ?: JSONObject.NULL)
                    .put("single_transcript_available", result.snapshot?.singleTranscriptAvailable ?: false)
                    .put("controller_retained_code_points", result.snapshot?.retainedRawCodePoints ?: 0)
                // An external collector assembles closed groups solely for an offline quality ceiling.
                // prefixDelta is never a translation request or displayed source unit.
                result.prefixDelta?.let { assembled.append(it) }
                result.closedBlock?.let { block ->
                    val group = JSONObject().put("group_id", ceilings.length()).put("purpose", "QUALITY_CEILING_ONLY")
                        .put("source_context_start_sample", groupStart).put("source_observed_end_sample", block.observedEndSample)
                        .put("source_horizon_ms", (block.observedEndSample - groupStart) / 16.0)
                        .put("bounded_latency_unit", false).put("final_coverage", false).put("reason", block.reason)
                    if (block.remainder == null) {
                        group.put("status", "unavailable_ambiguous_ownership").put("input_text", JSONObject.NULL)
                    } else {
                        assembled.append(block.remainder)
                        group.put("status", "awaiting_quality_ceiling_mt").put("input_text", assembled.toString())
                    }
                    ceilings.put(group); assembled.setLength(0)
                    groupStart = result.request?.startSample ?: block.observedEndSample
                }
                val decision = planner.observe(RollingCaptionRequestPlanner.Hypothesis(request.id,
                    request.startSample, request.endSample, decoded.raw,
                    recovery = request.kind == RollingLocalAgreement.Kind.RECOVERY,
                    terminal = result.snapshot?.terminal == true))
                val eligibleReady = elapsed(start)
                row.put("candidate_eligibility_ready_wall_ms", eligibleReady)
                    .put("stable_key_prefix_code_points", decision.stableKeyPrefixCodePoints)
                    .put("deadline_sample", decision.deadlineSample ?: JSONObject.NULL)
                    .put("next_deadline_sample", decision.nextDeadlineSample).put("deadline_disposition", decision.deadlineDisposition)
                for (revision in decision.revisions) {
                    revisions.put(JSONObject().put("unit_id", revision.unitId).put("reason", revision.reason)
                        .put("discovered_at_request_id", request.id).put("discovered_at_observed_end_sample", request.endSample)
                        .put("discovered_at_wall_ms", sourceReady))
                }
                for (unit in decision.requests) {
                    val unitRow = JSONObject().put("unit_id", unit.id).put("type", unit.type.name)
                        .put("hypothesis_request_id", unit.hypothesisRequestId).put("reason", unit.reason)
                        .put("context_start_sample", unit.startSample).put("observed_end_sample", unit.endSample)
                        .put("raw_start_utf16", unit.rawStart).put("raw_end_utf16", unit.rawEnd)
                        .put("input_text", unit.text).put("source_ready_wall_ms", sourceReady)
                        .put("candidate_eligibility_ready_wall_ms", eligibleReady)
                        .put("stable_key_prefix_code_points", unit.stableKeyPrefixCodePoints)
                        .put("stable_lexical_right_context_code_points", unit.stableLexicalRightContext)
                        .put("left_edge_unverified", unit.leftEdgeUnverified).put("final_coverage", unit.finalCoverage)
                        .put("cross_origin_overlap_possible", true).put("consumes_controller_tail", false)
                        .put("supersedes_preview_unit_id", unit.supersedes ?: JSONObject.NULL)
                        .put("is_same_end_correction", unit.correction).put("new_media_latency_sample", unit.latencySample)
                        .put("mt_requested_wall_ms", elapsed(start))
                    units.put(unitRow)
                    val sentence = unit.type == RollingCaptionRequestPlanner.Type.STABLE_SENTENCE_CANDIDATE
                    if (sentence && firstSentenceSource == null) firstSentenceSource = eligibleReady
                    if (!sentence && firstPreviewSource == null) firstPreviewSource = eligibleReady
                    translate(mt, unit.text, unitRow, save)
                    mtTotalMs += unitRow.optDouble("mt_ms", 0.0)
                    if (unitRow.optString("mt_status") == "returned") {
                        val targetReady = elapsed(start)
                        unitRow.put("target_ready_wall_ms", targetReady)
                        if (sentence && firstSentenceTarget == null) firstSentenceTarget = targetReady
                        if (!sentence && firstPreviewTarget == null) firstPreviewTarget = targetReady
                    } else unitRow.put("target_ready_wall_ms", JSONObject.NULL)
                }
                asrTotalMs += row.getDouble("asr_ms_including_stream_lifecycle")
                highestEnd = maxOf(highestEnd, request.endSample)
                row.put("status", "collected")
                save()
            }
            check(assembled.isEmpty())
            result = controller.finish(1)
            assertTrue(result.snapshot?.eofObserved == true)
            assertEquals(pcm.size.toLong(), highestEnd)
            report.put("bounded_stage_wall_ms", elapsed(start)).put("raw_asr_sum_ms", asrTotalMs)
                .put("bounded_mt_sum_ms", mtTotalMs).put("raw_asr_rtf", asrTotalMs / (pcm.size / 16.0))
                .put("first_stable_sentence_source_wall_ms", firstSentenceSource ?: JSONObject.NULL)
                .put("first_stable_sentence_target_wall_ms", firstSentenceTarget ?: JSONObject.NULL)
                .put("first_preview_source_wall_ms", firstPreviewSource ?: JSONObject.NULL)
                .put("first_preview_target_wall_ms", firstPreviewTarget ?: JSONObject.NULL)
                .put("audio_traversal_complete", true).put("eof_observed", true)
                .put("terminal_reason", result.snapshot?.terminalReason ?: JSONObject.NULL)
                .put("controller_single_transcript_available", result.snapshot?.singleTranscriptAvailable ?: false)
                .put("controller_hard_restart_count", result.snapshot?.hardRestartCount ?: 0)
                .put("status", "bounded_requests_collected")
            save()
            val qualityStart = now()
            for (i in 0 until ceilings.length()) {
                val row = ceilings.getJSONObject(i)
                if (row.isNull("input_text")) continue
                translate(mt, row.getString("input_text"), row, save)
                row.put("status", "collected")
            }
            report.put("quality_ceiling_mt_wall_ms", elapsed(qualityStart)).put("status", "collected")
                .put("asr_request_count", rows.length()).put("bounded_mt_request_count", units.length())
                .put("stable_sentence_request_count", count(units) { it.getString("type") == "STABLE_SENTENCE_CANDIDATE" })
                .put("preview_request_count", count(units) { it.getString("type") == "REVISABLE_PREVIEW" })
                .put("same_end_correction_count", count(units) { it.getBoolean("is_same_end_correction") })
                .put("candidate_revision_count", revisions.length()).put("quality_ceiling_group_count", ceilings.length())
                .put("bounded_mt_failed_or_empty_count", count(units) { it.optString("mt_status") != "returned" })
                .put("quality_ceiling_mt_request_count", count(ceilings) { !it.isNull("input_text") })
                .put("quality_ceiling_unavailable_count", count(ceilings) { it.isNull("input_text") })
                .put("quality_ceiling_mt_failed_or_empty_count", count(ceilings) { !it.isNull("input_text") && it.optString("mt_status") != "returned" })
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failure_class", failure.javaClass.name)
            throw failure
        } finally { report.put("total_wall_ms_including_quality_ceiling", elapsed(start)); save() }
    }

    private fun count(rows: JSONArray, predicate: (JSONObject) -> Boolean): Int =
        (0 until rows.length()).count { predicate(rows.getJSONObject(it)) }

    private fun closed(c: RollingLocalAgreement.ClosedBlock?): Any = c?.let {
        JSONObject().put("status", it.status).put("reason", it.reason).put("context_start_sample", it.contextStartSample)
            .put("observed_end_sample", it.observedEndSample).put("remainder", it.remainder ?: JSONObject.NULL)
            .put("old_pending_text", it.oldPending?.text ?: JSONObject.NULL)
            .put("recovery_full_text", it.recoveryFull?.fullRaw ?: JSONObject.NULL)
            .put("mt_unit", false).put("final_coverage", false)
    } ?: JSONObject.NULL

    private fun splice(a: RollingLocalAgreement.SpliceAudit?): Any = a?.let {
        JSONObject().put("anchor_key", it.anchorKey).put("anchor_letter_digit_count", it.anchorLetterDigitCount)
            .put("old_context_start_sample", it.oldContextStartSample).put("old_observed_end_sample", it.oldObservedEndSample)
            .put("old_delta_raw_start", it.oldDeltaRawStart).put("old_delta_raw_end", it.oldDeltaRawEnd)
            .put("old_replaced_tail_raw_start", it.oldReplacedTailRawStart).put("old_replaced_tail_raw_end", it.oldReplacedTailRawEnd)
            .put("old_replaced_tail", it.oldReplacedTail).put("new_omitted_prefix", it.newOmittedPrefix)
            .put("new_omitted_prefix_raw_start", it.newOmittedPrefixRawStart).put("new_omitted_prefix_raw_end", it.newOmittedPrefixRawEnd)
            .put("new_pending_raw_start", it.newPendingRawStart).put("candidate_context_start_sample", it.candidateContextStartSample)
            .put("candidate_first_end_sample", it.candidateFirstEndSample).put("candidate_second_end_sample", it.candidateSecondEndSample)
            .put("old_retained_eligibility_raw_start", it.oldRetainedEligibilityRawStart)
            .put("candidate_first_retained_eligibility_raw_start", it.candidateFirstRetainedEligibilityRawStart)
            .put("candidate_second_retained_eligibility_raw_start", it.candidateSecondRetainedEligibilityRawStart)
    } ?: JSONObject.NULL

    private fun recovery(a: RollingLocalAgreement.RecoveryAudit?): Any = a?.let {
        JSONObject().put("trigger", it.trigger.name).put("witness_role", it.witnessRole)
            .put("witness_raw", it.witnessRaw).put("witness_key", it.witnessKey).put("matched", it.matched)
            .put("failure", it.failure ?: JSONObject.NULL).put("recovery_omitted_raw", it.recoveryOmittedRaw ?: JSONObject.NULL)
            .put("recovery_omitted_raw_start", it.recoveryOmittedRawStart ?: JSONObject.NULL)
            .put("recovery_omitted_raw_end", it.recoveryOmittedRawEnd ?: JSONObject.NULL)
    } ?: JSONObject.NULL
}
