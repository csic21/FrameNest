package com.framenest.feature.listen_translate

import android.content.res.AssetManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framenest.BuildConfig
import com.framenest.data.settings.AsrEngineChoice
import com.framenest.feature.listen_translate.asr.AsrEngine
import com.framenest.feature.listen_translate.asr.AsrModelSupport
import com.framenest.feature.listen_translate.asr.AsrRecognition
import com.framenest.feature.listen_translate.asr.SherpaAsrEngine
import com.framenest.feature.listen_translate.asr.SherpaModelInstaller
import com.framenest.feature.listen_translate.audio.ListenAudioSource
import com.framenest.feature.listen_translate.mt.ListenMtEngine
import com.framenest.feature.listen_translate.mt.MlKitMtEngine
import com.k2fsa.sherpa.onnx.VersionInfo
import java.io.File
import java.io.InputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipFile
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
 * Fixed 3s/6s diagnostic, never a product strategy change or semantic acceptance.
 * One already-available full PCM fixture is traversed to its true EOF per condition.
 * A NAS file can be read ahead; this synthetic source does NOT measure NAS/codec
 * decode or imply that a 6s processing window requires 6s of real-time waiting.
 */
@RunWith(AndroidJUnit4::class)
class FixedCaptionWindowComparisonTest {
    @Test
    fun compareThreeAndSixSecondWindows(): Unit = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("fixedCaptionWindowComparison") == "true")
        require(arguments.getString("captionDiagnosisNetwork") == "true") { "Explicit ML Kit model-download opt-in required" }
        withTimeout(20 * 60_000L) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val reportFile = File(context.filesDir, "fixed-caption-window-comparison.json")
            val runStartedEpochMs = System.currentTimeMillis()
            // Replace any old success before verification; interrupted/invalid runs cannot inherit it.
            writeReport(reportFile, JSONObject().put("schema_version", 1).put("status", "verifying_inputs")
                .put("collection_complete", false).put("run_started_epoch_ms", runStartedEpochMs))
            assertTrue(BuildConfig.SHERPA_ASR_SOURCE)
            assertFalse(BuildConfig.HY_MT_NATIVE)
            assertEquals(SOURCE_AAR_SHA256, BuildConfig.SHERPA_AAR_SHA256)
            assertEquals("1.13.8-asr-no-tts-sha256-$SOURCE_AAR_SHA256", BuildConfig.SHERPA_RUNTIME_IDENTITY)
            assertEquals("1.13.8", BuildConfig.SHERPA_RUNTIME_VERSION)
            assertEquals("1.13.8", VersionInfo.version.removePrefix("v"))
            assertEquals("Product default remains 3 seconds", 3_000L, ListenTranslateWindows.DEFAULT_WINDOW_MS)
            val assets = instrumentation.context.assets
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
            val verifiedProfile = verifySourceProfile(JSONObject(profileBytes.toString(Charsets.UTF_8)), appApk)
            val referenceBytes = verifiedAsset(assets, fixture.json.getJSONObject("reference"))
            val reference = JSONObject(referenceBytes.toString(Charsets.UTF_8))
            verifyReference(fixture, reference)
            val inputFixtures = listOf(fixture.json to fixture.decodePcm(verifiedAsset(assets, fixture.json.getJSONObject("pcm"))),
                comparison.emoji to decodePcm(verifiedAsset(assets, comparison.emoji.getJSONObject("pcm"))))
            val modelDir = File(context.cacheDir, "caption-diagnosis-model").apply { mkdirs() }
            val modelInputs = fixture.json.getJSONArray("models")
            for (i in 0 until modelInputs.length()) copyVerifiedAsset(assets, modelInputs.getJSONObject(i), modelDir)
            assertTrue(SherpaModelInstaller.verifyPack(modelDir))

            val fixtureReports = JSONArray()
            val report = JSONObject().put("schema_version", 1).put("status", "running").put("collection_complete", false)
                .put("run_started_epoch_ms", runStartedEpochMs)
                .put("comparison_manifest_sha256", sha256(comparisonBytes)).put("comparison_manifest", comparison.json)
                .put("diagnosis_manifest_sha256", sha256(manifestBytes)).put("diagnosis_manifest", fixture.json)
                .put("prepared_inputs", prepared).put("comparison_prepared_inputs", comparisonPrepared)
                .put("reference", reference).put("reference_sha256", sha256(referenceBytes))
                .put("app_apk_sha256", appHash).put("test_apk_sha256", testHash)
                .put("source_aar_sha256", BuildConfig.SHERPA_AAR_SHA256).put("runtime_identity", BuildConfig.SHERPA_RUNTIME_IDENTITY)
                .put("native_version", VersionInfo.version).put("native_git_sha1", VersionInfo.gitSha1)
                .put("native_git_date", VersionInfo.gitDate).put("verified_source_profile", verifiedProfile)
                .put("api", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS.first())
                .put("device_model", Build.MODEL).put("device_manufacturer", Build.MANUFACTURER)
                .put("window_ms", JSONArray(comparison.windowSizes)).put("context_padding_ms", 750)
                .put("production_default_window_ms", ListenTranslateWindows.DEFAULT_WINDOW_MS)
                .put("semantic_acceptance_passed", JSONObject.NULL).put("accuracy_accepted", false).put("hardware_performance_accepted", false)
                .put("scope", "Same complete public PCM per fixed 3s/6s condition; existing production ASR/MT once per eligible window; no altered token selection or discarded comparison rows; no real NAS/codec/Room/player/rendering benchmark")
                .put("timing_scope", "Full file PCM available ahead; no real-time window pacing. Shared ASR and MT prepared once; fixed MCDC 3s/6s then Emoji 3s/6s order. Monotonic wall time includes observers/hashes/report writes, not a controlled cold/warm speedup benchmark.")
                .put("token_scope", "All actual production-adapter mapped tokens retained, including outside-window context; ends are next-token estimates. No native raw-token rerun, scoring cleanup, deduplication, or semantic gold invented.")
                .put("fixtures", fixtureReports)
            fun save() { writeReport(reportFile, report) }
            val actualAsr = SherpaAsrEngine()
            val actualMt = MlKitMtEngine(allowMeteredDownloads = true)
            save()
            try {
                val asrStart = now()
                actualAsr.ensureModel(modelDir, "ja")
                report.put("shared_asr_preparation_ms", elapsed(asrStart))
                val mtStart = now()
                actualMt.ensureModel("ja", "zh")
                report.put("shared_mt_preparation_ms", elapsed(mtStart))
                save()
                for ((identity, pcm) in inputFixtures) {
                    val duration = identity.getLong("duration_ms")
                    val origin = identity.getLong("original_media_start_ms")
                    assertEquals(duration * 16L, pcm.size.toLong())
                    val conditions = JSONArray()
                    val fixtureReport = JSONObject().put("fixture_id", identity.getString("id"))
                        .put("duration_ms", duration).put("original_media_start_ms", origin)
                        .put("pcm", identity.getJSONObject("pcm")).put("attribution", identity.getString("attribution"))
                        .put("source_url", identity.getString("source_url")).put("license", identity.getString("license"))
                        .put("license_url", identity.getString("license_url"))
                        .put("reference_kind", if (identity === fixture.json) reference.getString("reference_kind") else identity.getString("reference_kind"))
                        .put("reference_is_verbatim_gold", false).put("reference_independently_listened", false).put("conditions", conditions)
                    fixtureReports.put(fixtureReport)
                    for (windowMs in comparison.windowSizes) {
                        val windows = comparison.windows(duration, windowMs)
                        val rows = JSONArray()
                        val condition = JSONObject().put("window_ms", windowMs).put("status", "running")
                            .put("expected_window_count", windows.size).put("rows", rows)
                            .put("coverage", coverage(0L, duration, 0, windows.size))
                            .put("final_eof", JSONObject.NULL).put("semantic_acceptance_passed", JSONObject.NULL)
                            .put("first_source_ready_ms", JSONObject.NULL).put("first_translation_ready_ms", JSONObject.NULL)
                        conditions.put(condition)
                        val audio = PinnedPcmAudio(pcm)
                        val asr = ObservedAsr(actualAsr)
                        val mt = ObservedMt(actualMt)
                        // Fresh engine means source-retry cache never crosses a condition/fixture.
                        // Non-owning wrappers keep the single shared ASR/MT models alive until final cleanup.
                        val engine = RealListenTranslateEngine(audio, asr, mt, PinnedModels(modelDir),
                            asrModelLabel = { "${SherpaModelInstaller.MODEL_VERSION}|sherpa-${BuildConfig.SHERPA_RUNTIME_IDENTITY}" },
                            monotonicTimeMs = { SystemClock.elapsedRealtimeNanos() / 1_000_000L })
                        val conditionStart = now()
                        var processedThrough = 0L
                        var firstSourceSeen = false
                        var firstTranslationSeen = false
                        save()
                        try {
                            for ((index, window) in windows.withIndex()) {
                                val (startMs, endMs) = window
                                check(startMs == processedThrough)
                                asr.calls = JSONArray(); mt.calls = JSONArray(); audio.lastRead = null
                                asr.decodeStartMs = maxOf(0L, startMs - 750L); asr.windowStartMs = startMs; asr.windowEndMs = endMs
                                val row = JSONObject().put("window_id", "w$index").put("window_index", index)
                                    .put("window_start_ms", startMs).put("window_end_ms", endMs)
                                    .put("window_duration_ms", endMs - startMs).put("core_pcm_samples", (endMs - startMs) * 16L)
                                    .put("original_window_start_ms", origin + startMs).put("original_window_end_ms", origin + endMs)
                                    .put("overlapping_reference_unit_ids", JSONArray(if (identity === fixture.json) fixture.unitsOverlapping(startMs, endMs) else emptyList<String>()))
                                    .put("is_final_window", index == windows.lastIndex).put("status", "running")
                                    .put("actual_production_asr_calls", asr.calls).put("actual_mt_calls", mt.calls)
                                    .put("semantic_acceptance_passed", JSONObject.NULL)
                                rows.put(row)
                                fun checkpoint() {
                                    audio.lastRead?.let { row.put("actual_pcm_read", it) }
                                    save()
                                }
                                asr.onUpdate = { checkpoint() }; mt.onUpdate = { checkpoint() }
                                checkpoint()
                                val start = now()
                                try {
                                    val result = engine.processWindowWithProgress(startMs, endMs, "ja", "zh") { source ->
                                        row.put("source_ready_ms", elapsed(start)).put("published_source_text", source.textSrc)
                                        if (!firstSourceSeen) {
                                            condition.put("first_source_ready_ms", elapsed(conditionStart)).put("first_source_window_index", index)
                                            firstSourceSeen = true
                                        }
                                        checkpoint()
                                    }
                                    row.put("text_src", result.textSrc).put("text_tgt", result.textTgt)
                                        .put("cue_start_ms", result.cueStartMs ?: JSONObject.NULL).put("cue_end_ms", result.cueEndMs ?: JSONObject.NULL)
                                        .put("blank_reason", result.blankReason?.name ?: JSONObject.NULL)
                                        .put("translation_retryable_failure", result.retryableErrorMessage != null)
                                        .put("stage_timings", stages(result.stageTimings))
                                        .put("production_asr_model_id", engine.asrModelId).put("production_mt_model_id", engine.mtModelId)
                                    if (!firstTranslationSeen && result.textTgt.isNotBlank()) {
                                        condition.put("first_translation_ready_ms", elapsed(conditionStart)).put("first_translation_window_index", index)
                                        firstTranslationSeen = true
                                    }
                                    assertTrue("At most one real ASR per window", asr.calls.length() <= 1)
                                    assertEquals("Exactly one MT for nonblank source, no retries", if (result.textSrc.isBlank()) 0 else 1, mt.calls.length())
                                    if (result.blankReason != ListenBlankReason.NearSilence && result.blankReason != ListenBlankReason.EmptyPcm) {
                                        assertEquals("Non-silent PCM gets exactly one real ASR", 1, asr.calls.length())
                                    }
                                    row.put("status", "collected")
                                    processedThrough = endMs
                                    condition.put("coverage", coverage(processedThrough, duration, index + 1, windows.size))
                                    if (index == windows.lastIndex) {
                                        val read = requireNotNull(audio.lastRead)
                                        assertEquals(duration, read.getLong("actual_end_ms"))
                                        condition.put("final_eof", JSONObject().put("reached", true)
                                            .put("core_end_ms", endMs).put("actual_pcm_end_ms", read.getLong("actual_end_ms"))
                                            .put("requested_context_end_ms", read.getLong("requested_end_ms"))
                                            .put("context_clipped_at_eof", read.getLong("requested_end_ms") > duration)
                                            .put("remaining_core_samples", 0).put("final_window_duration_ms", endMs - startMs))
                                    }
                                } catch (failure: Throwable) {
                                    row.put("status", "failed").put("failure_class", failure.javaClass.name)
                                    if (failure is ListenPipelineException) row.put("stage_timings", stages(failure.stages))
                                    throw failure
                                } finally {
                                    row.put("wall_ms", elapsed(start)).put("asr_call_count", asr.calls.length()).put("mt_call_count", mt.calls.length())
                                    audio.lastRead?.let { row.put("actual_pcm_read", it) }
                                    if (index == 0) condition.put("first_window_wall_ms", row.getDouble("wall_ms"))
                                    save()
                                }
                            }
                            assertEquals(duration, processedThrough)
                            condition.put("status", "collected")
                        } finally {
                            condition.put("wall_ms", elapsed(conditionStart))
                            engine.close(); save()
                        }
                    }
                }
                report.put("status", "collected").put("collection_complete", true)
                    .put("semantic_review_required", "Review both full fixtures and every adjacent source/target boundary; complete audio coverage is not transcript completeness or translation accuracy. No omitted rows or post-hoc text cleanup.")
            } catch (failure: Throwable) {
                report.put("status", "failed").put("failure_class", failure.javaClass.name)
                throw failure
            } finally {
                try { actualAsr.close() } finally { actualMt.close(); save() }
            }
        }
        Unit
    }

    private fun coverage(through: Long, duration: Long, completed: Int, expected: Int) = JSONObject()
        .put("processed_through_ms", through).put("duration_ms", duration).put("covered_core_ms", through)
        .put("covered_core_samples", through * 16L).put("total_core_samples", duration * 16L)
        .put("completed_windows", completed).put("expected_windows", expected)
        .put("gap_ms", 0).put("overlap_ms", 0).put("remaining_ms", duration - through)
        .put("complete", through == duration && completed == expected)
        .put("scope", "Contiguous processing coverage, including blank/failed-translation rows; never a word-retention or semantic score")

    private class ObservedAsr(private val delegate: AsrEngine) : AsrEngine by delegate {
        var calls = JSONArray()
        var onUpdate: () -> Unit = {}
        var decodeStartMs = 0L
        var windowStartMs = 0L
        var windowEndMs = 0L
        override fun close() = Unit
        override suspend fun recognize(pcm16kMono: ShortArray): AsrRecognition {
            val call = JSONObject().put("input_pcm_samples", pcm16kMono.size).put("input_pcm_s16le_sha256", pcmHash(pcm16kMono))
            calls.put(call); onUpdate()
            try {
                val result = delegate.recognize(pcm16kMono)
                val selected = selectWordsForWindow(result.words, decodeStartMs, windowStartMs, windowEndMs)
                call.put("full_recognition_text", result.text).put("text_separator", result.textSeparator)
                    .put("mapped_tokens", JSONArray(result.words.map { word ->
                        JSONObject().put("text", word.text).put("start_ms_relative_to_decode", word.startMs)
                            .put("end_ms_relative_to_decode", word.endMs).put("start_ms_clip", decodeStartMs + word.startMs)
                            .put("end_ms_clip", decodeStartMs + word.endMs).put("selected_by_existing_midpoint_rule", word in selected)
                    })).put("status", "returned")
                onUpdate()
                return result
            } catch (failure: Throwable) {
                call.put("status", "failed").put("failure_class", failure.javaClass.name)
                throw failure
            }
        }
    }

    private class ObservedMt(private val delegate: ListenMtEngine) : ListenMtEngine by delegate {
        var calls = JSONArray()
        var onUpdate: () -> Unit = {}
        override fun close() = Unit
        override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
            val call = JSONObject().put("input_text", text).put("source_language", sourceLang).put("target_language", targetLang)
            calls.put(call); onUpdate()
            val start = now()
            try {
                return delegate.translate(text, sourceLang, targetLang).also {
                    call.put("target_text", it).put("status", if (it.isBlank()) "empty_target" else "returned")
                }
            } catch (failure: Throwable) {
                call.put("status", "failed").put("failure_class", failure.javaClass.name)
                throw failure
            } finally { call.put("wall_ms", elapsed(start)); onUpdate() }
        }
    }

    /** Same-directory atomic replacement preserves the last complete checkpoint on process death. */
    private fun writeReport(file: File, report: JSONObject) {
        val pending = File(file.parentFile, "${file.name}.pending")
        FileOutputStream(pending).use { stream ->
            stream.write(report.toString(2).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun decodePcm(bytes: ByteArray): ShortArray {
        require(bytes.size % 2 == 0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(bytes.size / 2).also { buffer.get(it) }
    }
    private class PinnedPcmAudio(private val pcm: ShortArray) : ListenAudioSource {
        var lastRead: JSONObject? = null
        fun slice(startMs: Long, endMs: Long): ShortArray {
            require(startMs >= 0L && endMs > startMs)
            val start = minOf(startMs * 16L, pcm.size.toLong()).toInt()
            val end = minOf(endMs * 16L, pcm.size.toLong()).toInt()
            return pcm.copyOfRange(start, end)
        }
        override suspend fun pcmWindow(startMs: Long, endMs: Long, preferredAudioTrackOrdinal: Int?): ShortArray {
            require(preferredAudioTrackOrdinal == null)
            return slice(startMs, endMs).also {
                lastRead = JSONObject().put("requested_start_ms", startMs).put("requested_end_ms", endMs)
                    .put("actual_start_ms", minOf(startMs, pcm.size / 16L))
                    .put("actual_end_ms", minOf(endMs, pcm.size / 16L))
                    .put("samples", it.size).put("s16le_sha256", pcmHash(it))
            }
        }
    }

    private class PinnedModels(private val directory: File) : AsrModelSupport {
        override fun engineChoice() = AsrEngineChoice.SHERPA
        override fun supportedSourceLanguages() = setOf("ja")
        override fun isInstalled(langTag: String) = langTag == "ja"
        override suspend fun ensureInstalled(langTag: String, allowMeteredDownloads: Boolean, onProgress: (Float) -> Unit): File =
            error("Diagnostic models must already be packaged and verified")
        override fun modelDir(langTag: String) = directory.takeIf { langTag == "ja" }
        override fun unsupportedLanguageMessage(langTag: String) = "Pinned Japanese fixture only"
        override fun incompleteModelMessage(langTag: String) = "Missing pinned diagnostic model"
        override fun downloadingMessage(langTag: String) = "No ASR download in this test"
        override fun readyMessage(sourceLang: String) = "Pinned model"
        override fun modelLabel(sourceLang: String) = SherpaModelInstaller.MODEL_VERSION
    }

    private fun verifyReference(fixture: CaptionDiagnosisFixture, reference: JSONObject) {
        assertEquals("human_corrected_published_timed_text", reference.getString("reference_kind"))
        assertFalse(reference.getBoolean("is_verbatim_gold"))
        assertEquals(fixture.originalStartMs, reference.getLong("source_offset_ms"))
        assertEquals(fixture.durationMs, reference.getLong("duration_ms"))
        val units = reference.getJSONArray("units")
        assertEquals(fixture.units.size, units.length())
        for (i in fixture.units.indices) {
            val expected = fixture.units[i]
            val actual = units.getJSONObject(i)
            assertEquals(expected.id, actual.getString("id"))
            assertEquals(expected.startMs, actual.getLong("start_ms"))
            assertEquals(expected.endMs, actual.getLong("end_ms"))
            assertEquals(expected.text, actual.getString("text"))
            assertEquals(expected.evidence.getJSONArray("cue_ids").toString(), actual.getJSONArray("cue_ids").toString())
        }
    }

    private fun verifiedAsset(assets: AssetManager, spec: JSONObject): ByteArray {
        CaptionDiagnosisFixture.validateFile(spec)
        require(spec.getLong("bytes") <= 4_000_000L) { "Large model assets must be streamed" }
        val bytes = assets.open("$ROOT/generated/${spec.getString("file")}").use { it.readBytes() }
        assertEquals(spec.getLong("bytes"), bytes.size.toLong())
        assertEquals(spec.getString("sha256"), sha256(bytes))
        return bytes
    }

    private fun copyVerifiedAsset(assets: AssetManager, spec: JSONObject, directory: File) {
        CaptionDiagnosisFixture.validateFile(spec)
        val file = File(directory, spec.getString("file"))
        val expected = spec.getLong("bytes")
        var count = 0L
        assets.open("$ROOT/generated/${spec.getString("file")}").use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(65_536)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    count += size
                    require(count <= expected) { "Model asset exceeds pinned length" }
                    output.write(buffer, 0, size)
                }
            }
        }
        assertEquals(expected, count)
        assertEquals(spec.getString("sha256"), sha256(file))
    }

    private fun verifySourceProfile(profile: JSONObject, appApk: File): JSONObject {
        assertEquals(SOURCE_AAR_SHA256, profile.getString("aar_sha256"))
        assertEquals(BuildConfig.SHERPA_RUNTIME_IDENTITY, profile.getString("runtime_identity"))
        assertEquals(VersionInfo.version.removePrefix("v"), profile.getString("upstream_runtime_version"))
        val verified = JSONArray()
        var activeMembers = 0
        ZipFile(appApk).use { apk ->
            for (group in listOf("apk_assets", "native_members")) {
                val specs = profile.getJSONArray(group)
                for (i in 0 until specs.length()) {
                    val spec = specs.getJSONObject(i)
                    val path = spec.getString("path")
                    val active = group == "native_members" && spec.getString("abi") == Build.SUPPORTED_ABIS.first()
                    val entry = apk.getEntry(path)
                    if (group == "native_members" && !active && entry == null) continue
                    requireNotNull(entry) { "Missing pinned source APK member: $path" }
                    assertEquals(spec.getLong("bytes"), entry.size)
                    val hash = apk.getInputStream(entry).use { sha256(it) }
                    assertEquals(spec.getString("sha256"), hash)
                    verified.put(JSONObject().put("path", path).put("sha256", hash).put("bytes", entry.size))
                    if (active) activeMembers++
                }
            }
        }
        assertEquals("Pinned JNI and ORT for the active ABI", 2, activeMembers)
        return JSONObject().put("profile", profile).put("verified_apk_members", verified)
    }

    private fun stages(stages: ListenStageTimings?): Any = stages?.let {
        JSONObject().put("model_check_ms", it.modelCheckMs ?: JSONObject.NULL)
            .put("pcm_copy_ms", it.pcmReadMs ?: JSONObject.NULL)
            .put("silence_gate_ms", it.silenceGateMs ?: JSONObject.NULL)
            .put("asr_ms_including_observer", it.asrMs ?: JSONObject.NULL)
            .put("mt_preparation_ms_overlaps_asr", it.translationPreparationMs ?: JSONObject.NULL)
            .put("mt_preparation_exposed_wait_ms", it.translationPreparationWaitMs ?: JSONObject.NULL)
            .put("mt_ms", it.translationMs ?: JSONObject.NULL)
    } ?: JSONObject.NULL

    companion object {
        private const val ROOT = "caption-diagnosis"
        private const val SOURCE_AAR_SHA256 = "6a243d2da9a1f521a85ceeb30b51e00a660e2a1bfb05b79f6b591361af7c7503"
        private fun now() = SystemClock.elapsedRealtimeNanos()
        private fun elapsed(start: Long) = (now() - start) / 1_000_000.0
        private fun jsonAsset(assets: AssetManager, path: String) =
            JSONObject(assets.open(path).bufferedReader().use { it.readText() })
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        private fun sha256(file: File) = file.inputStream().use { sha256(it) }
        private fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65_536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            return digest.digest().hex()
        }
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        private fun pcmHash(pcm: ShortArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(pcm.size * 2)
            pcm.forEachIndexed { i, value -> bytes[i * 2] = value.toByte(); bytes[i * 2 + 1] = (value.toInt() shr 8).toByte() }
            return digest.digest(bytes).hex()
        }
    }
}
