package com.framenest.feature.listen_translate

import android.content.res.AssetManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framenest.BuildConfig
import com.framenest.feature.listen_translate.asr.AsrRecognition
import com.framenest.feature.listen_translate.asr.SherpaAsrEngine
import com.framenest.feature.listen_translate.asr.SherpaModelInstaller
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.VersionInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in real JNI comparison. Build/install/run each runtime APK separately with
 * -e runtimeComparison true -e expectedRuntime 1.12.32 (or 1.13.8).
 * The argument never selects or mocks a runtime: VersionInfo interrogates JNI.
 * Prepare pinned public assets with prepare-sensevoice-runtime-comparison.py.
 * Pull files/sensevoice-runtime-comparison.json before installing the other APK.
 *
 * No downloads, VAD execution, MT, user media, production cache, or player state.
 * Raw float input and production short/32768f input are checked bit-for-bit.
 * Excluded VAD gaps are not decoded, silent, or accepted completed coverage.
 */
@RunWith(AndroidJUnit4::class)
class SenseVoiceRuntimeComparisonTest {
    @Test
    fun comparePinnedPcmThroughRealNativeAndProductionAdapter(): Unit = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runtimeComparison") == "true")
        val expectedRuntime = requireNotNull(arguments.getString("expectedRuntime"))
        require(expectedRuntime in setOf("1.12.32", "1.13.8"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val manifestBytes = assets.open("$ASSET_ROOT/manifest.json").use { it.readBytes() }
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        val prepared = JSONObject(assets.open("$ASSET_ROOT/generated/prepared.json").bufferedReader().use { it.readText() })
        assertEquals(1, manifest.getInt("schema_version"))
        assertEquals(sha256(manifestBytes), prepared.getString("manifest_sha256"))
        assertTrue(prepared.getBoolean("all_inputs_verified"))
        assertFalse(prepared.getBoolean("gap_coverage_accepted"))
        val actualRuntime = VersionInfo.version
        assertEquals("Native runtime must match the separately built APK label", expectedRuntime, actualRuntime.removePrefix("v"))
        assertEquals("Build label must match actual JNI, not substitute for it", BuildConfig.SHERPA_RUNTIME_VERSION, actualRuntime.removePrefix("v"))
        val runs = JSONArray()
        val lifecycle = JSONObject()
            .put("raw_recognizer_created", 0).put("raw_recognizer_released", 0)
            .put("raw_stream_created", 0).put("raw_stream_released", 0)
            .put("production_ensure_calls", 0).put("production_recognize_calls", 0)
            .put("production_close_calls", 0)
            .put("production_native_allocations_observable", false)
        val report = JSONObject()
            .put("schema_version", 1).put("status", "running")
            .put("runtime_expected", expectedRuntime).put("runtime_actual_native", actualRuntime)
            .put("runtime_build_label", BuildConfig.SHERPA_RUNTIME_VERSION)
            .put("native_git_sha1", VersionInfo.gitSha1).put("native_git_date", VersionInfo.gitDate)
            .put("api", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS.first())
            .put("device_model", Build.MODEL).put("device_manufacturer", Build.MANUFACTURER)
            .put("apk_sha256", sha256(File(context.applicationInfo.sourceDir)))
            .put("fixture_manifest_sha256", sha256(manifestBytes)).put("fixture_manifest", manifest)
            .put("gap_coverage_accepted", false).put("accuracy_accepted", false)
            .put("hardware_performance_accepted", false).put("translation_tested", false)
            .put("timing_scope", "Monotonic elapsed wall time on this Android target; separate preparation and decode. Three sequential passes per backend, not paced playback or bilingual latency.")
            .put("decode_timing_boundaries", "Raw: create stream, accept waveform, decode, get result; release measured by lifecycle count only. Production: complete recognize call including dispatch, short-to-float conversion and stream release. Compare each backend only against the same backend in the other APK.")
            .put("runs", runs).put("lifecycle", lifecycle)
        val reportFile = File(context.filesDir, "sensevoice-runtime-comparison.json")
        fun save() { reportFile.writeText(report.toString(2)) }
        save()
        val modelDir = File(context.cacheDir, "sensevoice-runtime-comparison-model").apply { mkdirs() }
        val production = SherpaAsrEngine(numThreads = 2)
        var raw: OfflineRecognizer? = null
        try {
            val fixturePcm = loadPcm(assets, manifest)
            val windows = loadWindows(manifest, fixturePcm)
            assertEquals(prepared.getInt("windows"), windows.size)
            report.put("windows_per_pass", windows.size)
                .put("production_float_roundtrip_max_abs_error", 0.0)
                .put("production_float_input_bit_identical_to_raw", true)
            val modelStageStart = now()
            for (index in 0 until manifest.getJSONArray("models").length()) {
                val spec = manifest.getJSONArray("models").getJSONObject(index)
                val file = File(modelDir, File(spec.getString("file")).name)
                assets.open(spec.getString("asset")).use { input -> file.outputStream().use { input.copyTo(it) } }
                assertEquals(spec.getLong("bytes"), file.length())
                assertEquals(spec.getString("sha256"), sha256(file))
            }
            report.put("model_asset_copy_and_verification_ms", elapsedMs(modelStageStart))
            assertTrue("Use the production pack verifier before any native load", SherpaModelInstaller.verifyPack(modelDir))
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = File(modelDir, SherpaModelInstaller.MODEL_FILE).absolutePath,
                        language = "ja", useInverseTextNormalization = true,
                    ),
                    tokens = File(modelDir, SherpaModelInstaller.TOKENS_FILE).absolutePath,
                    numThreads = 2, debug = false, provider = "cpu",
                ),
                decodingMethod = "greedy_search", maxActivePaths = 4,
            )
            report.put("raw_full_config", config.toString())
                .put("production_config", "SherpaAsrEngine(numThreads=2), ensureModel(verified modelDir, ja); production defaults: ITN true, CPU, greedy_search, maxActivePaths 4")
            val rawPreparationStart = now()
            // Absolute private paths: never pass AssetManager to the recognizer.
            raw = OfflineRecognizer(config = config)
            val recognizer = raw
            increment(lifecycle, "raw_recognizer_created")
            report.put("raw_native_preparation_ms", elapsedMs(rawPreparationStart))
            val rawTexts = mutableListOf<String>()
            repeat(PASSES) { pass ->
                for (window in windows) {
                    val row = window.identity(pass, "raw_native_f32le")
                    val start = now()
                    val stream = recognizer.createStream()
                    increment(lifecycle, "raw_stream_created")
                    try {
                        stream.acceptWaveform(window.floats, SAMPLE_RATE)
                        recognizer.decode(stream)
                        val result = recognizer.getResult(stream)
                        row.put("decode_ms", elapsedMs(start))
                            .put("text", result.text)
                            .put("tokens", JSONArray(result.tokens.toList()))
                            .put("timestamps_relative_seconds", JSONArray(result.timestamps.toList()))
                            .put("timestamps_absolute_seconds", JSONArray(result.timestamps.map { window.startSample.toDouble() / SAMPLE_RATE + it }))
                            .put("durations_seconds", JSONArray(result.durations.toList()))
                            .put("lang", result.lang).put("emotion", result.emotion).put("event", result.event)
                        rawTexts.add(result.text.trim())
                    } finally {
                        stream.release()
                        increment(lifecycle, "raw_stream_released")
                    }
                    runs.put(row)
                    save()
                }
            }
            raw.release()
            raw = null
            increment(lifecycle, "raw_recognizer_released")

            val preparationStart = now()
            increment(lifecycle, "production_ensure_calls")
            production.ensureModel(modelDir, "ja")
            report.put("production_preparation_ms_including_pack_verification", elapsedMs(preparationStart))
            val warmPreparationStart = now()
            increment(lifecycle, "production_ensure_calls")
            production.ensureModel(modelDir, "ja")
            report.put("production_same_model_warm_ensure_ms", elapsedMs(warmPreparationStart))
            var rawIndex = 0
            repeat(PASSES) { pass ->
                for (window in windows) {
                    val start = now()
                    increment(lifecycle, "production_recognize_calls")
                    val result = production.recognize(window.shorts)
                    val row = window.identity(pass, "production_sherpa_asr_engine")
                        .put("decode_ms", elapsedMs(start)).put("result", productionJson(result))
                        .put("text_equals_same_pass_raw", result.text == rawTexts[rawIndex++])
                    runs.put(row)
                    save()
                    assertTrue("Raw native and production adapter must agree on identical samples", row.getBoolean("text_equals_same_pass_raw"))
                }
            }
            val probeWindow = windows.maxBy { it.shorts.size }
            report.put("cancellation_probe", cancellationProbe(production, probeWindow, lifecycle))
            val reference = production.recognize(probeWindow.shorts)
            increment(lifecycle, "production_recognize_calls")
            val closeStart = now()
            production.close()
            production.close()
            increment(lifecycle, "production_close_calls", 2)
            report.put("production_idempotent_close_ms", elapsedMs(closeStart))
            val reloadStart = now()
            increment(lifecycle, "production_ensure_calls")
            production.ensureModel(modelDir, "ja")
            report.put("production_reload_preparation_ms", elapsedMs(reloadStart))
            increment(lifecycle, "production_recognize_calls")
            assertEquals("Cancelled/closed engine remains reusable after reload", reference, production.recognize(probeWindow.shorts))
            report.put("production_close_reload_result_equal", true).put("status", "passed")
        } catch (error: Throwable) {
            report.put("status", "failed").put("failure_class", error.javaClass.name)
            throw error
        } finally {
            try {
                raw?.let { it.release(); increment(lifecycle, "raw_recognizer_released") }
            } finally {
                production.close()
                increment(lifecycle, "production_close_calls")
                report.put("raw_stream_counts_balanced", lifecycle.getInt("raw_stream_created") == lifecycle.getInt("raw_stream_released"))
                save()
            }
        }
        // JUnit4 requires a JVM void method; never infer JSONObject from put().
        Unit
    }

    private suspend fun cancellationProbe(engine: SherpaAsrEngine, window: Window, counters: JSONObject): JSONObject = coroutineScope {
        var preCancelledEntered = false
        val beforeDispatch = launch(start = CoroutineStart.LAZY) {
            preCancelledEntered = true
            engine.recognize(window.shorts)
        }
        beforeDispatch.cancelAndJoin()
        assertFalse(preCancelledEntered)
        val entered = CompletableDeferred<Unit>()
        val published = CompletableDeferred<Unit>()
        var cancellationObservedByCaller = false
        val job = launch(Dispatchers.Default) {
            entered.complete(Unit)
            try {
                engine.recognize(window.shorts)
                currentCoroutineContext().ensureActive()
                published.complete(Unit)
            } catch (cancelled: CancellationException) {
                cancellationObservedByCaller = true
                throw cancelled
            }
        }
        increment(counters, "production_recognize_calls")
        entered.await()
        delay(10)
        val completedBeforeCancel = job.isCompleted
        val start = now()
        job.cancelAndJoin()
        val suppressed = !published.isCompleted
        // Completion may race the snapshot above; only assert when cancellation
        // actually took effect. This is never proof that JNI decode was interrupted.
        if (cancellationObservedByCaller) assertTrue("Cancelled caller must not publish a stale result", suppressed)
        JSONObject().put("pre_dispatch_cancel_skipped_body", !preCancelledEntered)
            .put("requested_after_call_entry", true).put("completed_before_cancel", completedBeforeCancel)
            .put("cancellation_took_effect", job.isCancelled)
            .put("cancellation_observed_by_caller", cancellationObservedByCaller)
            .put("result_publication_suppressed", suppressed).put("cancel_join_ms", elapsedMs(start))
            .put("native_decode_interrupted_proven", false)
            .put("scope", "Coroutine publication and reuse only. Entry precedes Dispatchers.IO; no native-start hook. JNI decode may finish before cancellation is observed; no seek/player lifecycle or bounded native cancellation claim.")
    }

    private fun loadPcm(assets: AssetManager, manifest: JSONObject): Map<String, Pcm> {
        val result = mutableMapOf<String, Pcm>()
        val fixtures = manifest.getJSONArray("fixtures")
        for (index in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(index)
            val spec = fixture.getJSONObject("pcm")
            val bytes = assets.open(spec.getString("asset")).use { it.readBytes() }
            assertEquals(spec.getLong("bytes"), bytes.size.toLong())
            assertEquals(spec.getString("sha256"), sha256(bytes))
            assertEquals(spec.getInt("samples") * 4, bytes.size)
            assertEquals(SAMPLE_RATE, spec.getInt("sample_rate_hz"))
            assertEquals(1, spec.getInt("channels"))
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val floats = FloatArray(spec.getInt("samples")) { buffer.float }
            val shorts = ShortArray(floats.size) { sample ->
                val value = floats[sample]
                require(value.isFinite() && value >= -1f && value < 1f)
                val short = (value * 32768f).toInt().toShort()
                require((short / 32768f).toRawBits() == value.toRawBits()) {
                    "Public float input is not exactly representable by production PCM16; refusing quantization"
                }
                short
            }
            val shortBytes = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            shorts.forEach { shortBytes.putShort(it) }
            assertEquals(spec.getString("s16le_sha256"), sha256(shortBytes.array()))
            result[fixture.getString("id")] = Pcm(bytes, floats, shorts)
        }
        return result
    }

    private fun loadWindows(manifest: JSONObject, pcm: Map<String, Pcm>): List<Window> = buildList {
        val samples = manifest.getJSONArray("samples")
        for (sampleIndex in 0 until samples.length()) {
            val sample = samples.getJSONObject(sampleIndex)
            val source = pcm.getValue(sample.getString("fixture_id"))
            val extent = sample.getInt("extent_samples")
            require(extent in 1..source.floats.size)
            val rows = sample.getJSONArray("rows")
            var previousEnd = 0
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val lo = row.getInt("start_sample")
                val hi = row.getInt("end_sample")
                require(row.getInt("index") == index && lo >= previousEnd && hi > lo && hi <= extent)
                require(hi - lo <= 25 * SAMPLE_RATE)
                val hash = sha256(source.bytes.copyOfRange(lo * 4, hi * 4))
                assertEquals(row.getString("f32le_sha256"), hash)
                add(Window(sample.getString("id"), index, lo, hi, row.getDouble("ready_input_seconds"), hash,
                    source.floats.copyOfRange(lo, hi), source.shorts.copyOfRange(lo, hi)))
                previousEnd = hi
            }
        }
    }

    private data class Pcm(val bytes: ByteArray, val floats: FloatArray, val shorts: ShortArray)
    private data class Window(
        val sample: String, val index: Int, val startSample: Int, val endSample: Int,
        val readyInputSeconds: Double, val hash: String, val floats: FloatArray, val shorts: ShortArray,
    ) {
        fun identity(pass: Int, backend: String) = JSONObject().put("pass", pass).put("backend", backend)
            .put("sample", sample).put("window_index", index)
            .put("start_sample", startSample).put("end_sample", endSample)
            .put("start_seconds", startSample.toDouble() / SAMPLE_RATE)
            .put("end_seconds", endSample.toDouble() / SAMPLE_RATE)
            .put("ready_input_seconds_from_host_vad", readyInputSeconds)
            .put("f32le_sha256", hash)
    }

    private fun productionJson(result: AsrRecognition) = JSONObject().put("text", result.text)
        .put("text_separator", result.textSeparator).put("words", JSONArray(result.words.map {
            JSONObject().put("text", it.text).put("start_ms", it.startMs).put("end_ms", it.endMs)
        }))

    private fun increment(json: JSONObject, key: String, count: Int = 1) { json.put(key, json.getInt(key) + count) }
    private fun now() = SystemClock.elapsedRealtimeNanos()
    private fun elapsedMs(start: Long) = (now() - start) / 1_000_000.0
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val ASSET_ROOT = "runtime-comparison"
        const val SAMPLE_RATE = 16_000
        const val PASSES = 3
    }
}
