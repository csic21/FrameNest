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
import com.framenest.feature.listen_translate.mt.MlKitMtEngine
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.VersionInfo
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
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
 * FN-76: diagnosis only, never a semantic release gate.
 * Explicit captionDiagnosis=true AND captionDiagnosisNetwork=true are required.
 * The second flag acknowledges real ML Kit language-pack preparation/downloads.
 * Expected APK hashes pin the installed, retained source=true / Hy=false pair.
 *
 * Three real paths: published reference -> ML Kit; matching reference PCM span ->
 * native SenseVoice -> ML Kit; fixed 3-second RealListenTranslateEngine -> ML Kit.
 * Production ASR is observed by delegation, never replaced or reconstructed from a rerun.
 * Fixed PCM isolates text/segmentation/MT, so this does not measure MediaCodec,
 * SMB, Room, video playback, physical subtitle rendering, or target-phone latency.
 */
@RunWith(AndroidJUnit4::class)
class CaptionDiagnosisTest {
    @Test
    fun compareReferenceRawSpanAndProductionWindows(): Unit = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("captionDiagnosis") == "true")
        require(arguments.getString("captionDiagnosisNetwork") == "true") { "Explicit ML Kit model-download opt-in required" }
        assertTrue("FN-76 must use the released source AAR, never old default-debug JNI", BuildConfig.SHERPA_ASR_SOURCE)
        assertFalse("FN-76 diagnoses the existing ML Kit path only", BuildConfig.HY_MT_NATIVE)
        assertEquals(SOURCE_AAR_SHA256, BuildConfig.SHERPA_AAR_SHA256)
        assertEquals("1.13.8-asr-no-tts-sha256-$SOURCE_AAR_SHA256", BuildConfig.SHERPA_RUNTIME_IDENTITY)
        assertEquals("1.13.8", BuildConfig.SHERPA_RUNTIME_VERSION)
        assertEquals("1.13.8", VersionInfo.version.removePrefix("v"))

        withTimeout(25 * 60_000L) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val assets = instrumentation.context.assets
            val appApk = File(context.applicationInfo.sourceDir)
            val testApk = File(instrumentation.context.applicationInfo.sourceDir)
            val appHash = sha256(appApk)
            val testHash = sha256(testApk)
            for ((name, actual) in listOf("expectedAppApkSha256" to appHash, "expectedTestApkSha256" to testHash)) {
                val expected = requireNotNull(arguments.getString(name)) { "Supply retained APK hash: $name" }
                require(expected.matches(Regex("[0-9a-f]{64}")))
                assertEquals("Installed APK must equal the retained build", expected, actual)
            }
            val manifestBytes = assets.open("$ROOT/manifest.json").use { it.readBytes() }
            val fixture = CaptionDiagnosisFixture(JSONObject(manifestBytes.toString(Charsets.UTF_8)))
            val prepared = jsonAsset(assets, "$ROOT/generated/prepared.json")
            assertEquals(sha256(manifestBytes), prepared.getString("manifest_sha256"))
            assertTrue(prepared.getBoolean("all_inputs_verified"))
            val profileBytes = assets.open("$ROOT/source-profile.json").use { it.readBytes() }
            assertEquals(sha256(profileBytes), prepared.getString("source_profile_sha256"))
            val verifiedProfile = verifySourceProfile(JSONObject(profileBytes.toString(Charsets.UTF_8)), appApk)
            val pcm = fixture.decodePcm(verifiedAsset(assets, fixture.json.getJSONObject("pcm")))
            val referenceBytes = verifiedAsset(assets, fixture.json.getJSONObject("reference"))
            val reference = JSONObject(referenceBytes.toString(Charsets.UTF_8))
            verifyReference(fixture, reference)
            // Verify every input before loading native code or preparing network-backed MT.
            val modelInputs = fixture.json.getJSONArray("models")
            val modelDir = File(context.cacheDir, "caption-diagnosis-model").apply { mkdirs() }
            for (i in 0 until modelInputs.length()) copyVerifiedAsset(assets, modelInputs.getJSONObject(i), modelDir)
            assertTrue(SherpaModelInstaller.verifyPack(modelDir))

            val referenceRows = JSONArray()
            val rawRows = JSONArray()
            val productionRows = JSONArray()
            val report = JSONObject()
                .put("schema_version", 1).put("status", "running")
                .put("scope", "Pinned public PCM diagnostic; real source-profile JNI and ML Kit; no codec/SMB/Room/player/rendering measurement")
                .put("semantic_acceptance_passed", JSONObject.NULL)
                .put("accuracy_accepted", false).put("hardware_performance_accepted", false)
                .put("reference_kind", reference.getString("reference_kind"))
                .put("reference_is_verbatim_gold", false).put("reference_independently_listened", false)
                .put("manifest_sha256", sha256(manifestBytes)).put("manifest", fixture.json)
                .put("reference_sha256", sha256(referenceBytes)).put("reference", reference)
                .put("prepared_inputs", prepared)
                .put("app_apk_sha256", appHash).put("test_apk_sha256", testHash)
                .put("source_aar_sha256", BuildConfig.SHERPA_AAR_SHA256)
                .put("runtime_identity", BuildConfig.SHERPA_RUNTIME_IDENTITY)
                .put("native_version", VersionInfo.version).put("native_git_sha1", VersionInfo.gitSha1)
                .put("native_git_date", VersionInfo.gitDate).put("verified_source_profile", verifiedProfile)
                .put("api", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS.first())
                .put("device_model", Build.MODEL).put("device_manufacturer", Build.MANUFACTURER)
                .put("pcm_scope", "Identical pinned signed PCM16 source; clip-local timeline starts at zero, original-media offset retained; no loudness normalization or resampling at runtime")
                .put("timing_scope", "Monotonic elapsed wall time; shared MT prepared before all columns, fixed order reference/raw/production; production observer/hash overhead included; no cold/warm or speedup claim")
                .put("production_grid", "Clip-local 3000 ms grid, not the original full video's grid")
                .put("reference_to_mt", referenceRows).put("raw_span_asr_to_mt", rawRows)
                .put("production_3s_to_mt", productionRows)
            val reportFile = File(context.filesDir, "caption-diagnosis.json")
            fun save() { reportFile.writeText(report.toString(2)) }
            val mt = MlKitMtEngine(allowMeteredDownloads = true)
            val actualAsr = SherpaAsrEngine()
            val observer = ObservedAsr(actualAsr)
            val audio = PinnedPcmAudio(pcm)
            val engine = RealListenTranslateEngine(
                audio = audio, asr = observer, mt = mt, asrModels = PinnedModels(modelDir),
                asrModelLabel = { "${SherpaModelInstaller.MODEL_VERSION}|sherpa-${BuildConfig.SHERPA_RUNTIME_IDENTITY}" },
                monotonicTimeMs = { SystemClock.elapsedRealtimeNanos() / 1_000_000L },
            )
            var raw: OfflineRecognizer? = null
            save()
            try {
                val preparationStart = now()
                mt.ensureModel("ja", "zh")
                report.put("shared_mt_preparation_ms", elapsed(preparationStart))
                save()
                for (unit in fixture.units) {
                    val row = unitIdentity(unit, fixture).put("input_text", unit.text)
                    referenceRows.put(row)
                    translate(mt, unit.text, row)
                    save()
                }
                val config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = File(modelDir, SherpaModelInstaller.MODEL_FILE).absolutePath,
                            language = "ja", useInverseTextNormalization = true,
                        ),
                        tokens = File(modelDir, SherpaModelInstaller.TOKENS_FILE).absolutePath,
                        numThreads = SherpaAsrEngine.DEFAULT_NUM_THREADS, debug = false, provider = "cpu",
                    ), decodingMethod = "greedy_search", maxActivePaths = 4,
                )
                val rawPrepare = now()
                raw = OfflineRecognizer(config = config)
                report.put("raw_asr_preparation_ms", elapsed(rawPrepare)).put("raw_asr_config", config.toString())
                val recognizer = raw
                for (unit in fixture.units) {
                    val row = unitIdentity(unit, fixture)
                    rawRows.put(row)
                    val readStart = now()
                    val samples = audio.slice(unit.startMs, unit.endMs)
                    row.put("pcm_copy_ms", elapsed(readStart)).put("pcm_samples", samples.size)
                        .put("pcm_s16le_sha256", pcmHash(samples))
                    val asrStart = now()
                    val stream = recognizer.createStream()
                    val text: String
                    try {
                        stream.acceptWaveform(FloatArray(samples.size) { samples[it] / 32768f }, 16_000)
                        recognizer.decode(stream)
                        val result = recognizer.getResult(stream)
                        text = result.text.trim()
                        row.put("raw_text", result.text).put("input_text", text)
                            .put("raw_tokens", JSONArray(result.tokens.toList()))
                            .put("token_start_seconds_relative_to_span", JSONArray(result.timestamps.toList()))
                            .put("token_start_seconds_clip", JSONArray(result.timestamps.map { unit.startMs / 1000.0 + it }))
                            .put("token_start_seconds_original_media", JSONArray(result.timestamps.map { (fixture.originalStartMs + unit.startMs) / 1000.0 + it }))
                            .put("native_durations_seconds", JSONArray(result.durations.toList()))
                    } finally { stream.release() }
                    row.put("asr_ms_including_stream_lifecycle", elapsed(asrStart))
                    translate(mt, text, row)
                    save()
                }
                raw.release(); raw = null
                for ((index, window) in fixture.windows().withIndex()) {
                    val (startMs, endMs) = window
                    observer.last = null
                    audio.lastRead = null
                    val row = JSONObject().put("window_id", "p$index")
                        .put("window_start_ms", startMs).put("window_end_ms", endMs)
                        .put("original_window_start_ms", fixture.originalStartMs + startMs)
                        .put("original_window_end_ms", fixture.originalStartMs + endMs)
                        .put("overlapping_reference_unit_ids", JSONArray(fixture.unitsOverlapping(startMs, endMs)))
                        .put("semantic_acceptance_passed", JSONObject.NULL)
                    productionRows.put(row)
                    val start = now()
                    val result = engine.processWindowWithProgress(startMs, endMs, "ja", "zh") { source ->
                        row.put("source_ready_ms", elapsed(start)).put("published_source_text", source.textSrc)
                    }
                    row.put("wall_ms", elapsed(start)).put("text_src", result.textSrc).put("text_tgt", result.textTgt)
                        .put("cue_start_ms", result.cueStartMs ?: JSONObject.NULL)
                        .put("cue_end_ms", result.cueEndMs ?: JSONObject.NULL)
                        .put("blank_reason", result.blankReason?.name ?: JSONObject.NULL)
                        .put("translation_retryable_failure", result.retryableErrorMessage != null)
                        .put("stage_timings", stages(result.stageTimings))
                        .put("production_asr_model_id", engine.asrModelId).put("production_mt_model_id", engine.mtModelId)
                    audio.lastRead?.let { row.put("actual_pcm_read", it) }
                    observer.last?.let { row.put("actual_production_asr_call", it) }
                    save()
                }
                report.put("status", "collected")
                    .put("collection_complete", true)
                    .put("semantic_review_required", "Review reference adequacy, raw ASR, adjacent production windows, and each actual Chinese translation separately; collection does not imply usable captions")
            } catch (failure: Throwable) {
                report.put("status", "failed").put("failure_class", failure.javaClass.name)
                throw failure
            } finally {
                try { raw?.release() } finally { engine.close(); save() }
            }
        }
        Unit
    }

    private suspend fun translate(mt: MlKitMtEngine, text: String, row: JSONObject) {
        if (text.isBlank()) {
            row.put("mt_status", "skipped_empty_source").put("target_text", "")
            return
        }
        val start = now()
        try {
            val translated = mt.translate(text, "ja", "zh")
            row.put("target_text", translated).put("mt_status", if (translated.isBlank()) "empty_target" else "returned")
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Throwable) {
            row.put("mt_status", "failed").put("mt_failure_class", failure.javaClass.name)
        } finally { row.put("mt_ms", elapsed(start)) }
    }

    private fun unitIdentity(unit: CaptionDiagnosisUnit, fixture: CaptionDiagnosisFixture) = JSONObject()
        .put("reference_unit_id", unit.id).put("span_start_ms", unit.startMs).put("span_end_ms", unit.endMs)
        .put("original_span_start_ms", fixture.originalStartMs + unit.startMs)
        .put("original_span_end_ms", fixture.originalStartMs + unit.endMs)
        .put("reference_evidence", unit.evidence).put("semantic_acceptance_passed", JSONObject.NULL)

    private class ObservedAsr(private val delegate: AsrEngine) : AsrEngine by delegate {
        var last: JSONObject? = null
        override suspend fun recognize(pcm16kMono: ShortArray): AsrRecognition {
            val result = delegate.recognize(pcm16kMono)
            last = JSONObject().put("input_pcm_samples", pcm16kMono.size)
                .put("input_pcm_s16le_sha256", pcmHash(pcm16kMono))
                .put("full_recognition_text", result.text).put("text_separator", result.textSeparator)
                .put("mapped_tokens", JSONArray(result.words.map {
                    JSONObject().put("text", it.text).put("start_ms_relative_to_decode", it.startMs)
                        .put("end_ms_relative_to_decode", it.endMs)
                }))
                .put("token_scope", "Actual SherpaAsrEngine returned mapped words; ends are next-token start estimates, not native measured durations. No second recognition or synthetic reconstruction.")
            return result
        }
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
