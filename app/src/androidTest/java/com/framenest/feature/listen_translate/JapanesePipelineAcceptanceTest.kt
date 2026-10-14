package com.framenest.feature.listen_translate

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framenest.core.model.PlaybackIdentity
import com.framenest.data.listen_translate.ListenTranslateRepository
import com.framenest.data.server.AppDatabase
import com.framenest.feature.listen_translate.asr.SherpaAsrEngine
import com.framenest.feature.listen_translate.asr.SherpaAsrModelSupport
import com.framenest.feature.listen_translate.asr.SherpaModelInstaller
import com.framenest.feature.listen_translate.audio.FileListenAudioSource
import com.framenest.feature.listen_translate.mt.MlKitMtEngine
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real codec -> SenseVoice -> ML Kit -> Room -> session overlay selection.
 * Opt-in: network/model downloads are never part of ordinary connected tests.
 * This proves functional integration, not OPPO performance, visual rendering,
 * simultaneous video playback, SMB throughput, or independently scored accuracy.
 */
@RunWith(AndroidJUnit4::class)
class JapanesePipelineAcceptanceTest {
    @Test
    fun realJapaneseToChineseColdAndWarmModelSessions() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("japaneseBenchmark") == "true")
        withTimeout(25 * 60_000L) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val assets = instrumentation.context.assets
            val manifest = JSONObject(assets.open("japanese-benchmark/manifest.json").bufferedReader().use { it.readText() })
            val window = ListenTranslateWindows.windowContaining(
                manifest.getLong("playhead_ms"), durationMs = manifest.getLong("test_extent_ms"),
            )
            assertEquals(manifest.getLong("window_start_ms"), window.first)
            assertEquals(manifest.getLong("window_end_ms"), window.second)
            val media = File(context.cacheDir, "japanese-benchmark.webm")
            assets.open(manifest.getString("asset")).use { input -> media.outputStream().use { input.copyTo(it) } }
            assertEquals("Pinned fixture length", manifest.getLong("bytes"), media.length())
            val digest = MessageDigest.getInstance("SHA-256")
            media.inputStream().use { input ->
                val buffer = ByteArray(65_536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            assertEquals("Pinned fixture hash", manifest.getString("sha256"), digest.digest().joinToString("") { "%02x".format(it) })
            val rows = JSONArray()
            val report = JSONObject()
                .put("scope", "Android emulator functional integration; paused playhead; overlay selection not physical rendering")
                .put("accuracy_scored", false)
                .put("semantic_review_status", "pending")
                .put("hardware_performance_accepted", false)
                .put("api", Build.VERSION.SDK_INT)
                .put("abi", Build.SUPPORTED_ABIS.first())
                .put("runs", rows)
            // Fixture-only review artifact: deliberately separate from production diagnostics.
            // The media has already passed the manifest's exact size and SHA-256 checks.
            val reviewWindows = JSONArray()
            val fixtureReview = JSONObject()
                .put("fixture_id", manifest.getString("id"))
                .put("fixture_sha256", manifest.getString("sha256"))
                .put("source_url", manifest.getString("source"))
                .put("media_url", manifest.getString("url"))
                .put("author", manifest.getString("author"))
                .put("license", manifest.getString("license"))
                .put("license_url", manifest.getString("license_url"))
                .put("manual_semantic_review", "pending")
                .put("changes", "Machine-generated Japanese transcription and Chinese translation excerpts from the original video; may contain recognition or translation errors.")
                .put("derived_text_license", "CC-BY-SA-4.0")
                .put("semantic_acceptance_passed", JSONObject.NULL)
                .put("independent_golden_transcript", false)
                .put("review_instructions", "Listen to the pinned public fixture. Review complete utterances and adjacent 3-second window splits for omissions, hallucinations and Japanese-to-Chinese meaning. Script detection and non-echo checks are functional checks only.")
                .put("windows", reviewWindows)
            val reviewFile = File(context.filesDir, "japanese-fixture-review.json")
            fun saveReview() { reviewFile.writeText(fixtureReview.toString(2)) }
            saveReview()
            val reportFile = File(context.filesDir, "japanese-benchmark.json")
            fun save() { reportFile.writeText(report.toString(2)) }
            save()
            try {
                for (pass in 0..1) {
                    val row = JSONObject().put("pass", if (pass == 0) "first_install" else "warm_download_cache_fresh_native_and_session")
                    rows.put(row)
                    save()
                    val db = AppDatabase.createInMemory(context)
                    val job = SupervisorJob()
                    val scope = CoroutineScope(job + Dispatchers.Main.immediate)
                    val installer = SherpaModelInstaller(context)
                    val asr = SherpaAsrEngine()
                    val mt = MlKitMtEngine(allowMeteredDownloads = true)
                    lateinit var session: ListenTranslateSession
                    val engine = RealListenTranslateEngine(
                        FileListenAudioSource(media.absolutePath), asr, mt, SherpaAsrModelSupport(installer),
                        asrModelLabel = { SherpaModelInstaller.MODEL_VERSION },
                        monotonicTimeMs = SystemClock::elapsedRealtime,
                        onPcmReady = { at ->
                            val timingToken = session.startupTimingToken
                            scope.launch { session.recordFirstPcmTiming(timingToken, at) }
                        },
                    )
                    session = ListenTranslateSession(
                        ListenTranslateRepository(db.listenTranslateDao()), engine, scope,
                        PlaybackIdentity("public-fixture", "benchmark", "emoji.webm"),
                        monotonicTimeMs = SystemClock::elapsedRealtime,
                    )
                    try {
                        val token = withContext(Dispatchers.Main.immediate) {
                            session.setSourceLang("ja")
                            session.setTargetLang("zh")
                            session.beginStartupTiming()
                        }
                        val preparationStart = SystemClock.elapsedRealtime()
                        val model = installer.ensureInstalled("ja", allowMeteredDownloads = true)
                        assertTrue("Downloaded model bytes must verify", SherpaModelInstaller.verifyPack(model))
                        asr.ensureModel(model, "ja")
                        withContext(Dispatchers.Main.immediate) {
                            session.recordAsrPreparationTiming(token, SystemClock.elapsedRealtime() - preparationStart)
                            session.onPlaybackTick(manifest.getLong("playhead_ms"), manifest.getLong("test_extent_ms"), playing = false)
                            session.setEnabled(true)
                        }
                        val state = withTimeout(10 * 60_000L) {
                            while (true) {
                                val current = session.uiState.value
                                check(current.errorMessage == null) { "Pipeline stage failed (see content-free diagnostics)" }
                                if (current.diagnostics?.completed == true && current.activeCue?.textTgt?.isNotBlank() == true) break
                                delay(50)
                            }
                            session.uiState.value
                        }
                        val cue = requireNotNull(state.activeCue)
                        assertTrue("ASR produced Japanese script", cue.textSrc.any { it in '\u3040'..'\u30ff' })
                        assertTrue("MT produced Chinese characters", cue.textTgt.any { it in '\u4e00'..'\u9fff' })
                        assertTrue("MT is not an identity echo", cue.textSrc != cue.textTgt)
                        assertTrue("Both actual texts selected for overlay", state.overlayText == "${cue.textSrc}\n${cue.textTgt}")
                        assertTrue("Cue is inside production window", cue.startMs >= window.first && cue.endMs <= window.second)
                        val startup = requireNotNull(state.startupTiming)
                        val stages = requireNotNull(startup.firstGeneratedBilingualStages)
                        assertNotNull("First nonempty production PCM arrival", startup.firstPcmReadyMs)
                        assertNotNull("Source cache publication", startup.firstSourcePublishedMs)
                        assertNotNull("Bilingual cache publication", startup.firstBilingualPublishedMs)
                        assertNotNull("Source selected for eligible overlay", startup.firstSourceSelectedMs)
                        assertNotNull("Bilingual selected for eligible overlay", startup.firstBilingualSelectedMs)
                        assertNotNull("Production PCM measured", stages.pcmReadMs)
                        assertNotNull("Real native ASR measured", stages.asrMs)
                        assertNotNull("Real MT measured", stages.translationMs)
                        assertTrue("Source publication precedes bilingual publication",
                            requireNotNull(startup.firstSourcePublishedMs) <= requireNotNull(startup.firstBilingualPublishedMs))
                        assertTrue("Bilingual overlay selection follows publication",
                            requireNotNull(startup.firstBilingualSelectedMs) >= requireNotNull(startup.firstBilingualPublishedMs))
                        row.put("passed", true)
                            .put("media_playhead_ms", manifest.getLong("playhead_ms"))
                            .put("window_start_ms", window.first)
                            .put("window_end_ms", window.second)
                            .put("cue_start_ms", cue.startMs).put("cue_end_ms", cue.endMs)
                            .put("asr_preparation_ms", startup.asrPreparationMs)
                            .put("first_pcm_ready_ms", startup.firstPcmReadyMs)
                            .put("source_published_ms", startup.firstSourcePublishedMs)
                            .put("bilingual_published_ms", startup.firstBilingualPublishedMs)
                            .put("source_overlay_eligible_ms", startup.firstSourceSelectedMs)
                            .put("bilingual_overlay_eligible_ms", startup.firstBilingualSelectedMs)
                            .put("pcm_ms", stages.pcmReadMs).put("asr_ms", stages.asrMs)
                            .put("mt_prepare_ms", stages.translationPreparationMs).put("mt_ms", stages.translationMs)
                            .put("mt_prepare_exposed_wait_ms", stages.translationPreparationWaitMs)
                            .put("full_effective_rtf", state.effectiveRealtimeFactor)
                            .put("adaptation_effective_rtf", state.adaptationRealtimeFactor)
                            .put("cache_publish_ms", startup.firstGeneratedBilingualCachePublishMs)
                        save()
                        if (pass == 1) {
                            // The session subgate has finished. Stop its polling before reusing
                            // the real warm native/MT clients for fixture-only review windows.
                            withContext(Dispatchers.Main.immediate) { session.release() }
                            job.cancelAndJoin()
                            val reviewConfig = manifest.getJSONObject("fixture_review")
                            val starts = reviewConfig.getJSONArray("window_starts_ms")
                            assertEquals(ListenTranslateWindows.DEFAULT_WINDOW_MS, reviewConfig.getLong("window_duration_ms"))
                            for (index in 0 until starts.length()) {
                                val startMs = starts.getLong(index)
                                val endMs = startMs + ListenTranslateWindows.DEFAULT_WINDOW_MS
                                val result = engine.processWindow(startMs, endMs, "ja", "zh")
                                reviewWindows.put(JSONObject()
                                    .put("window_start_ms", startMs).put("window_end_ms", endMs)
                                    .put("decode_context_start_ms", (startMs - 750L).coerceAtLeast(0L))
                                    .put("decode_context_end_ms", endMs + 750L)
                                    .put("cue_start_ms", result.cueStartMs ?: JSONObject.NULL)
                                    .put("cue_end_ms", result.cueEndMs ?: JSONObject.NULL)
                                    .put("textSrc", result.textSrc).put("textTgt", result.textTgt)
                                    .put("blank_reason", result.blankReason?.name ?: JSONObject.NULL)
                                    .put("translation_retryable_failure", result.retryableErrorMessage != null)
                                    .put("asr_ms", result.stageTimings?.asrMs ?: JSONObject.NULL)
                                    .put("mt_ms", result.stageTimings?.translationMs ?: JSONObject.NULL))
                                saveReview()
                                check(result.retryableErrorMessage == null) { "Fixture review translation stage failed" }
                            }
                            fixtureReview.put("collection_completed", true)
                            saveReview()
                        }
                    } finally {
                        withContext(NonCancellable) {
                            withContext(Dispatchers.Main.immediate) { session.release() }
                            job.cancelAndJoin()
                            withContext(Dispatchers.IO) { engine.close() }
                            db.close()
                        }
                    }
                }
                report.put("functional_acceptance_passed", true)
                    .put("semantic_acceptance_passed", JSONObject.NULL)
                    .put("fixture_review_collected", fixtureReview.optBoolean("collection_completed", false))
            } catch (failure: Throwable) {
                report.put("functional_acceptance_passed", false).put("failure_type", failure.javaClass.simpleName)
                throw failure
            } finally {
                save()
                media.delete()
            }
        }
        Unit // JUnit 4 requires a void-returning test method.
    }
}
