package com.framenest.feature.listen_translate.mt

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framenest.BuildConfig
import com.framenest.feature.listen_translate.RecordedFileListenTranslateEngine
import com.framenest.feature.listen_translate.RecordedListenRequest
import com.framenest.feature.listen_translate.asr.SherpaAsrEngine
import com.framenest.feature.listen_translate.asr.SherpaAsrModelSupport
import com.framenest.feature.listen_translate.asr.SherpaModelInstaller
import com.framenest.feature.listen_translate.audio.FileListenAudioSource
import com.framenest.feature.listen_translate.audio.ListenAudioSource
import com.framenest.player.audio.PcmWindowResult
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit opt-in only. Ordinary instrumentation skips both methods before model access.
 *
 * Build with -PhyMtNative=true and run this class with runHyMtBenchmark=true.
 * This authorizes the pinned ~1.06 GiB model installation on an allowed network.
 * Mobile data requires the separate hyMtAllowMeteredDownloads=true argument.
 * The second method also installs the existing SenseVoice pack and reads only the
 * bounded, already-bundled public fixture. Prepare it with the existing
 * scripts/prepare-japanese-benchmark.py before building the test APK. Select the
 * first method explicitly if only the text/JNI smoke is wanted.
 *
 * Reports stay in targetContext.filesDir. No user media, production captions, URLs
 * from failures, model paths, or audio are exported. These are functional subgates;
 * semantic review and physical-device latency/thermal acceptance remain separate.
 */
@RunWith(AndroidJUnit4::class)
class HyMtNativeAcceptanceTest {
    @Test
    fun realNativeControlsCancellationReuseAndClose() = runBlocking {
        requireOptIn()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = baseReport("real Android JNI; authored complete-sentence controls")
        val rows = JSONArray()
        report.put("controls", rows)
            .put("semantic_acceptance_passed", JSONObject.NULL)
            .put("memory_before_model", memorySnapshot())
        val output = File(context.filesDir, "hy-mt-native-acceptance.json")
        save(output, report)
        var engine: HyMtEngine? = null
        val native = ObservedNative()
        val pathRequests = AtomicInteger()
        try {
            withTimeout(30 * 60_000L) {
                val model = prepareModel(context, report, output)
                engine = HyMtEngine(
                    verifiedModelPath = { pathRequests.incrementAndGet(); model.absolutePath },
                    native = native,
                    dispatcher = Dispatchers.IO,
                    threadCount = 2,
                )
                val owned = checkNotNull(engine)
                val loading = now()
                owned.ensureModel("ja", "zh")
                report.put("first_native_load_ms", now() - loading)
                    .put("load_definition", "Fresh engine initialization; excludes download/hash verification. Native library may already be loaded; verification warms filesystem caches, which were not cleared.")
                    .put("memory_after_load", memorySnapshot())
                save(output, report)

                val controls = loadControls()
                var firstSource = ""
                var firstTranslation = ""
                for (index in 0 until controls.length()) {
                    val control = controls.getJSONObject(index)
                    val source = control.getString("source_ja")
                    check(source.isNotBlank() && source.length <= 512)
                    val began = now()
                    val translated = withTimeout(180_000L) { owned.translate(source, "ja", "zh") }
                    assertTranslation(source, translated)
                    rows.put(JSONObject().put("id", control.getString("id"))
                        .put("source_ja", source).put("translation_zh", translated)
                        .put("example_zh", control.getString("example_zh"))
                        .put("semantic_checks", control.getString("semantic_checks"))
                        .put("inference_mode", if (index == 0) "first_inference_after_fresh_engine_load" else "resident_context_reuse")
                        .put("resident_model_inference_ms", now() - began)
                        .put("memory_after_inference", memorySnapshot()))
                    if (index == 0) { firstSource = source; firstTranslation = translated }
                    save(output, report)
                }

                report.put("cancellation", interruptNative(owned, native, closeEngine = false))
                val reuse = now()
                val translatedAgain = withTimeout(180_000L) { owned.translate(firstSource, "ja", "zh") }
                assertEquals("Seeded same-input translation after cancellation/KV reset", firstTranslation, translatedAgain)
                report.put("post_cancel_resident_inference_ms", now() - reuse)
                assertEquals("One verified path request for the resident model", 1, pathRequests.get())
                assertEquals("Native model must not reload after cancellation", 1, native.opens.get())
                assertEquals(0, native.closes.get())
                save(output, report)

                report.put("close_during_native_generation", interruptNative(owned, native, closeEngine = true))
                owned.close() // Must be idempotent after the in-flight call released ownership.
                assertEquals("Native context/model released exactly once", 1, native.closes.get())
                assertEquals(1, native.opens.get())
                assertEquals(1, pathRequests.get())
                report.put("native_open_calls", native.opens.get())
                    .put("native_close_calls", native.closes.get())
                    .put("verified_path_requests", pathRequests.get())
                    .put("functional_acceptance_passed", true)
            }
        } catch (failure: Throwable) {
            report.put("functional_acceptance_passed", false).put("failure_type", failure.javaClass.simpleName)
            throw failure
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { engine?.close() }
            report.put("memory_after_close", memorySnapshot())
            save(output, report)
        }
        Unit
    }

    /** Same opt-in: actual File PCM → SenseVoice → coordinator/Kuromoji → Hy-MT2. */
    @Test
    fun recordedProducerOnPinnedPublicJapaneseFixture() = runBlocking {
        requireOptIn()
        val acceptanceStarted = now()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = baseReport("Real recorded-file producer on pinned public fixture; no Room/UI/NAS/physical-rendering claim")
        val rows = JSONArray()
        val reads = JSONArray()
        report.put("cues", rows).put("audio_windows", reads)
            .put("semantic_acceptance_passed", JSONObject.NULL)
            .put("manual_semantic_review", "pending")
            .put("startup_clock_definition", "Test entry through first finalized bilingual callback, including fixture copy, model installation/verification/loading and PCM/ASR/MT. Existing model files and OS caches may be warm from preceding tests; not a phone, NAS, Room or physical-render latency.")
        val output = File(context.filesDir, "hy-mt-recorded-fixture-acceptance.json")
        save(output, report)
        var media: File? = null
        var producer: RecordedFileListenTranslateEngine? = null
        var asr: SherpaAsrEngine? = null
        var mt: HyMtEngine? = null
        var audio: ListenAudioSource? = null
        try {
            withTimeout(30 * 60_000L) {
                val manifest = JSONObject(readAsset("japanese-benchmark/manifest.json", 16_384))
                media = copyVerifiedFixture(context, manifest)
                report.put("fixture_id", manifest.getString("id"))
                    .put("fixture_sha256", manifest.getString("sha256"))
                    .put("source", manifest.getString("source"))
                    .put("author", manifest.getString("author"))
                    .put("license", manifest.getString("license"))
                    .put("license_url", manifest.getString("license_url"))
                    .put("changes", "Machine-generated Japanese transcription and Chinese translation excerpts; may contain errors.")
                    .put("derived_text_license", "CC-BY-SA-4.0")
                save(output, report)
                val model = prepareModel(context, report, output)
                mt = HyMtEngine({ model.absolutePath })
                val mtLoad = now()
                checkNotNull(mt).ensureModel("ja", "zh")
                report.put("native_mt_load_ms", now() - mtLoad)
                val installer = SherpaModelInstaller(context)
                val asrPreparation = now()
                val asrModel = installer.ensureInstalled("ja", allowMeteredDownloads = allowMetered())
                assertTrue(SherpaModelInstaller.verifyPack(asrModel))
                asr = SherpaAsrEngine()
                checkNotNull(asr).ensureModel(asrModel, "ja")
                report.put("asr_install_verify_and_load_ms", now() - asrPreparation)
                    .put("memory_after_both_models", memorySnapshot())

                audio = BoundedFixtureAudio(FileListenAudioSource(checkNotNull(media).absolutePath), reads)
                producer = RecordedFileListenTranslateEngine(checkNotNull(audio), checkNotNull(asr), checkNotNull(mt),
                    SherpaAsrModelSupport(installer),
                    asrModelLabel = { SherpaModelInstaller.MODEL_VERSION },
                    mtModelLabel = { HyMtModelInstaller.MODEL_VERSION + "|" + HyMtEngine.RUNTIME_VERSION },
                    monotonicTimeMs = SystemClock::elapsedRealtime)
                val began = now()
                var stalled = false
                var finalCount = 0
                var temporaryBilingualCount = 0
                var maximumReadMs = 0L
                for (step in 0 until 32) {
                    val progress = checkNotNull(producer).processAhead(
                        // Advance the playhead normally (no generation reset) after the first
                        // 42 s read, so the attributive clause ending at 42.22 s is included.
                        // The file continues; 51 s is a test horizon, never a fake EOF.
                        RecordedListenRequest(1, if (maximumReadMs >= 42_000) 9_000 else 0, 0, 27_000, "ja", "zh"),
                    ) { cue ->
                        check(rows.length() < 64 && cue.textSrc.length <= 2_048 && cue.textTgt.length <= 16_384)
                        assertTrue(cue.displayStartMs >= 0 && cue.displayEndMs <= 51_000)
                        if (cue.isFinal) {
                            assertTranslation(cue.textSrc, cue.textTgt)
                            assertNotNull(cue.coverageStartMs); assertNotNull(cue.coverageEndMs)
                            if (finalCount == 0) {
                                report.put("first_final_bilingual_from_test_start_ms", now() - acceptanceStarted)
                                    .put("first_final_bilingual_from_prepared_producer_ms", now() - began)
                            }
                            finalCount++
                        } else {
                            // Bounded provisional units may have an explicitly temporary
                            // translation. They must never claim completed coverage.
                            if (cue.textTgt.isNotBlank()) {
                                assertTranslation(cue.textSrc, cue.textTgt)
                                temporaryBilingualCount++
                            }
                            assertNull(cue.coverageStartMs); assertNull(cue.coverageEndMs)
                        }
                        rows.put(JSONObject().put("source_ja", cue.textSrc).put("translation_zh", cue.textTgt)
                            .put("is_final", cue.isFinal).put("reason", cue.reason)
                            .put("display_start_ms", cue.displayStartMs).put("display_end_ms", cue.displayEndMs)
                            .put("coverage_start_ms", cue.coverageStartMs ?: JSONObject.NULL)
                            .put("coverage_end_ms", cue.coverageEndMs ?: JSONObject.NULL)
                            .put("decoded_through_ms", cue.decodedThroughMs)
                            .put("ready_since_producer_start_ms", now() - began)
                            .put("ready_since_test_start_ms", now() - acceptanceStarted)
                            .put("pcm_ms", cue.timings?.pcmReadMs ?: JSONObject.NULL)
                            .put("asr_ms", cue.timings?.asrMs ?: JSONObject.NULL)
                            .put("mt_ms", cue.timings?.translationMs ?: JSONObject.NULL))
                        save(output, report)
                    }
                    maximumReadMs = maxOf(maximumReadMs, progress.readThroughMs)
                    if (!progress.madeProgress) { stalled = true; break }
                }
                assertTrue("Producer must reach the bounded fixture horizon", maximumReadMs >= 51_000)
                assertTrue("Producer must stop instead of decoding the entire file", stalled)
                assertTrue("At least one real finalized bilingual source unit", finalCount > 0)
                report.put("producer_elapsed_ms", now() - began).put("read_through_ms", maximumReadMs)
                    .put("final_bilingual_cues", finalCount)
                    .put("temporary_bilingual_cues", temporaryBilingualCount)
                    .put("functional_acceptance_passed", true)
            }
        } catch (failure: Throwable) {
            report.put("functional_acceptance_passed", false).put("failure_type", failure.javaClass.simpleName)
            throw failure
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (producer != null) producer?.close() else { audio?.close(); mt?.close(); asr?.close() }
                media?.delete()
            }
            report.put("memory_after_close", memorySnapshot())
            save(output, report)
        }
        Unit
    }

    private suspend fun interruptNative(engine: HyMtEngine, native: ObservedNative, closeEngine: Boolean): JSONObject = coroutineScope {
        val probe = native.observeNextCall()
        val published = AtomicBoolean()
        val request = async(Dispatchers.Default) {
            engine.translate("これは日本語の文章です。".repeat(20), "ja", "zh").also { published.set(true) }
        }
        withTimeout(10_000L) { probe.entered.await() }
        delay(100) // Allow prefill/decode to start; verify the synchronous JNI call is still in flight.
        assertFalse("Cancellation must exercise an in-flight JNI call", probe.returned.get())
        val began = now()
        if (closeEngine) engine.close() else request.cancel(CancellationException("Acceptance cancellation"))
        withTimeout(30_000L) { request.join() }
        assertTrue("Native JNI must surface cancellation", probe.cancelledInNative.get())
        assertTrue(request.isCancelled)
        assertFalse("Cancelled/closed inference must not publish late output", published.get())
        JSONObject().put("native_call_entered", true).put("native_cancellation_observed", true)
            .put("late_publication", false).put("cancel_to_completion_ms", now() - began)
            .put("memory_after", memorySnapshot())
    }

    private suspend fun prepareModel(context: Context, report: JSONObject, output: File): File {
        val installer = HyMtModelInstaller(context)
        val verifyStart = now()
        val existing = installer.verifiedModelFile()
        report.put("initial_sha256_verification_ms", now() - verifyStart)
            .put("verified_model_present_before_test", existing != null)
        save(output, report)
        val began = now()
        val model = existing ?: withTimeout(20 * 60_000L) { installer.ensureInstalled(allowMeteredDownloads = allowMetered()) }
        report.put("download_and_install_verification_ms", if (existing == null) now() - began else 0L)
            .put("model_downloaded_this_test", existing == null)
        assertEquals(HyMtModelInstaller.DOWNLOAD_BYTES, model.length())
        save(output, report)
        return model
    }

    private class NativeProbe {
        val entered = CompletableDeferred<Unit>()
        val returned = AtomicBoolean()
        val cancelledInNative = AtomicBoolean()
    }

    /** Observes the actual synchronous JNI call; no mocked output or fake cancellation. */
    private class ObservedNative : HyMtNative {
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        private val next = AtomicReference<NativeProbe?>()
        fun observeNextCall(): NativeProbe = NativeProbe().also { check(next.compareAndSet(null, it)) }
        override fun open(pathUtf8: ByteArray, threads: Int, cancellation: HyMtCancellation): Long =
            HyMtJni.open(pathUtf8, threads, cancellation).also { opens.incrementAndGet() }
        override fun translate(handle: Long, userMessageUtf8: ByteArray, cancellation: HyMtCancellation): ByteArray {
            val probe = next.getAndSet(null)
            probe?.entered?.complete(Unit)
            return try { HyMtJni.translate(handle, userMessageUtf8, cancellation) }
            catch (cancelled: CancellationException) { probe?.cancelledInNative?.set(true); throw cancelled }
            finally { probe?.returned?.set(true) }
        }
        override fun close(handle: Long) { HyMtJni.close(handle); closes.incrementAndGet() }
    }

    private class BoundedFixtureAudio(private val delegate: FileListenAudioSource, private val reads: JSONArray) : ListenAudioSource {
        override suspend fun pcmWindow(startMs: Long, endMs: Long, preferredAudioTrackOrdinal: Int?): ShortArray =
            pcmWindowDetailed(startMs, endMs, preferredAudioTrackOrdinal).samples
        override suspend fun pcmWindowDetailed(startMs: Long, endMs: Long, preferredAudioTrackOrdinal: Int?): PcmWindowResult {
            check(startMs in setOf(0L, 9_000L, 18_000L, 27_000L, 36_000L))
            check(endMs in setOf(15_000L, 24_000L, 33_000L, 42_000L, 51_000L) && endMs - startMs in 1L..24_000L)
            check(reads.length() < 12)
            reads.put(JSONObject().put("start_ms", startMs).put("end_ms", endMs))
            return delegate.pcmWindowDetailed(startMs, endMs, preferredAudioTrackOrdinal)
        }
        override fun close() = delegate.close()
    }

    private fun copyVerifiedFixture(context: Context, manifest: JSONObject): File {
        check(manifest.getString("asset") == "japanese-benchmark/emoji.webm")
        check(manifest.getLong("bytes") == 9_487_128L)
        check(manifest.getString("sha256") == "ad3af093836cad823ef1793ff4d505139eed92dd7e7402de4c015a2ed50d5956")
        val target = File(context.cacheDir, "hy-mt-acceptance-emoji.webm")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open(manifest.getString("asset")).use { input ->
                target.outputStream().use { output ->
                    val bytes = ByteArray(65_536)
                    while (true) {
                        val count = input.read(bytes)
                        if (count < 0) break
                        size += count
                        check(size <= manifest.getLong("bytes"))
                        digest.update(bytes, 0, count); output.write(bytes, 0, count)
                    }
                }
            }
            assertEquals(manifest.getLong("bytes"), size)
            assertEquals(manifest.getString("sha256"), digest.digest().joinToString("") { "%02x".format(it) })
            return target
        } catch (failure: Throwable) { target.delete(); throw failure }
    }

    private fun loadControls(): JSONArray = JSONObject(readAsset("japanese-benchmark/controlled-text-probes.json", 16_384))
        .getJSONArray("probes").also { check(it.length() in 1..8) }
    private fun readAsset(name: String, maximum: Int): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).bufferedReader().use { reader ->
            reader.readText().also { check(it.length <= maximum) }
        }
    private fun arguments() = InstrumentationRegistry.getArguments()
    private fun allowMetered() = arguments().getString("hyMtAllowMeteredDownloads") == "true"
    private fun requireOptIn() {
        assumeTrue("Native build required", BuildConfig.HY_MT_NATIVE)
        assumeTrue("Explicit 1.06 GiB model test opt-in required", arguments().getString("runHyMtBenchmark") == "true")
    }
    private fun now() = SystemClock.elapsedRealtime()
    private fun assertTranslation(source: String, translated: String) {
        assertTrue("Real translation must be nonempty and bounded", translated.isNotBlank() && translated.length <= 16_384)
        assertTrue("Chinese-script functional check", translated.any { it in '\u4e00'..'\u9fff' })
        assertNotEquals("Translation must not echo the source", source, translated)
    }
    private fun baseReport(scope: String) = JSONObject().put("schema_version", 1).put("scope", scope)
        .put("started_at_epoch_ms", System.currentTimeMillis()).put("api", Build.VERSION.SDK_INT)
        .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("available_processors", Runtime.getRuntime().availableProcessors())
        .put("model_revision", HyMtModelInstaller.REVISION).put("model_sha256", HyMtModelInstaller.SHA256)
        .put("model_bytes", HyMtModelInstaller.DOWNLOAD_BYTES).put("runtime", HyMtEngine.RUNTIME_VERSION)
        .put("cpu_threads", 2).put("context_tokens", 1_024).put("output_token_cap", 256)
        .put("hardware_performance_accepted", false)
        .put("memory_definition", "Android Debug snapshots in KiB; native allocated heap in bytes. These are not peak RSS or a memory-budget acceptance.")
    private fun memorySnapshot(): JSONObject {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return JSONObject().put("total_pss_kib", info.totalPss).put("native_pss_kib", info.nativePss)
            .put("dalvik_pss_kib", info.dalvikPss).put("private_dirty_kib", info.totalPrivateDirty)
            .put("native_heap_allocated_bytes", Debug.getNativeHeapAllocatedSize())
    }
    private fun save(file: File, report: JSONObject) {
        val bytes = report.toString(2).toByteArray(Charsets.UTF_8)
        check(bytes.size <= 256 * 1_024) { "Benchmark report exceeds its bounded artifact limit" }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }
}
