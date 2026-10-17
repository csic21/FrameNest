package com.framenest.feature.listen_translate

import com.framenest.feature.listen_translate.asr.AsrEngine
import com.framenest.feature.listen_translate.asr.AsrModelSupport
import com.framenest.feature.listen_translate.asr.AsrWord
import com.framenest.feature.listen_translate.asr.ConservativeSpeechGate
import com.framenest.feature.listen_translate.asr.isNearSilencePcm
import com.framenest.feature.listen_translate.audio.ListenAudioSource
import com.framenest.feature.listen_translate.mt.ListenMtEngine
import com.framenest.feature.listen_translate.recorded.KuromojiJapaneseMorphology
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Product listen-translate engine (FN-14, FN-51):
 * PCM window → offline ASR ([AsrEngine]: Vosk small or SenseVoice/Sherpa) →
 * ML Kit on-device MT.
 */
class RealListenTranslateEngine(
    private val audio: ListenAudioSource,
    private val asr: AsrEngine,
    private val mt: ListenMtEngine,
    private val asrModels: AsrModelSupport,
    private val selectedAudioTrackOrdinal: () -> Int? = { null },
    private val asrModelLabel: () -> String = { "asr" },
    private val mtModelLabel: () -> String = { "mlkit-translate" },
    private val experimentalSilenceGate: Boolean = false,
    private val monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    /** Content-free event time; includes silent PCM, but not an empty decode. */
    private val onPcmReady: (Long) -> Unit = {},
    /** Unreleased experiment; preserve the fixed-window product default until validation. */
    private val experimentalJapaneseSegments: Boolean = false,
    private val knownMediaDurationMs: () -> Long = { 0L },
    /** Failure-only opt-in; never changes a nonempty baseline translation. */
    private val emptyTranslationRecoveryEnabled: Boolean = false,
) : OwnedListenTranslateEngine, EmptyTranslationRecoveryEngine {

    private data class SourceKey(val start: Long, val end: Long, val language: String, val track: Int?)
    private val recoveryLock = Any()
    // The failed unit and its one adjacent baseline source survive MT-only retries.
    private val cachedSources = linkedMapOf<SourceKey, ListenWindowResult>()
    private data class BaselineOutcome(val key: SourceKey, val targetLang: String, val result: ListenWindowResult)
    private data class RecoveryKey(val source: SourceKey, val targetLang: String)
    private var lastBaseline: BaselineOutcome? = null
    // Never evict an attempted key and silently re-arm it. An unusually failure-heavy
    // generation stops recovery at this bounded budget; ordinary MT retry still works.
    private val attemptedRecoveries = mutableSetOf<RecoveryKey>()
    @Volatile private var recoveryGeneration = 0L
    private val recoveryMorphology by lazy { KuromojiJapaneseMorphology() }

    override val asrModelId: String
        get() = listenAsrCacheModelId(asrModelLabel(), experimentalSilenceGate) +
            if (experimentalJapaneseSegments) "|ja-owned-prefix-v1" else ""
    override fun adaptiveMaxSpanMsFor(sourceLang: String): Long? =
        if (experimentalJapaneseSegments && sourceLang.equals("ja", ignoreCase = true))
            JapaneseOwnedPrefixPolicy.MAX_OWNED_MS else null
    override val mtModelId: String get() = mtModelLabel() +
        if (emptyTranslationRecoveryEnabled && !experimentalJapaneseSegments) "|empty-ja-crossing-v1" else ""

    override fun supportsEmptyTranslationRecovery(sourceLang: String, targetLang: String): Boolean =
        emptyTranslationRecoveryEnabled && !experimentalJapaneseSegments &&
            sourceLang.equals("ja", ignoreCase = true) && !sourceLang.equals(targetLang, ignoreCase = true)

    override fun resetEmptyTranslationRecovery() {
        synchronized(recoveryLock) {
            recoveryGeneration++
            cachedSources.clear()
            lastBaseline = null
            attemptedRecoveries.clear()
        }
    }

    override suspend fun processWindow(
        startMs: Long,
        endMs: Long,
        sourceLang: String,
        targetLang: String,
    ): ListenWindowResult = processWindowWithProgress(
        startMs, endMs, sourceLang, targetLang, onSourceRecognized = {},
    )

    override suspend fun processWindowWithProgress(
        startMs: Long,
        endMs: Long,
        sourceLang: String,
        targetLang: String,
        onSourceRecognized: suspend (ListenWindowResult) -> Unit,
    ): ListenWindowResult {
        val generation = recoveryGeneration
        val key = SourceKey(startMs, endMs, sourceLang.lowercase(), selectedAudioTrackOrdinal())
        val timer = ListenStageTimer(monotonicTimeMs)
        return try {
            processMeasuredWindow(startMs, endMs, sourceLang, targetLang, timer, onSourceRecognized, generation).also {
                ensureRecoveryGeneration(generation)
                synchronized(recoveryLock) {
                    checkRecoveryGeneration(generation)
                    lastBaseline = BaselineOutcome(key, targetLang.lowercase(), it)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            throw ListenPipelineException(failure, timer.timings)
        }
    }

    private suspend fun processMeasuredWindow(
        startMs: Long,
        endMs: Long,
        sourceLang: String,
        targetLang: String,
        timer: ListenStageTimer,
        onSourceRecognized: suspend (ListenWindowResult) -> Unit,
        generation: Long,
    ): ListenWindowResult {
        val srcLang = sourceLang.lowercase()
        val tgtLang = targetLang.lowercase()
        if (!asrModels.supportedSourceLanguages().contains(srcLang)) {
            throw IllegalStateException(asrModels.unsupportedLanguageMessage(srcLang))
        }
        if (srcLang != tgtLang && (!mt.supportsLanguage(srcLang) || !mt.supportsLanguage(tgtLang))) {
            throw IllegalStateException("语言对 $srcLang→$tgtLang 不被 ML Kit 支持")
        }

        return processListenWindowStages(
            prepareTranslation = {
                if (srcLang != tgtLang) {
                    timer.measure(ListenPipelineStage.TranslationPreparation) {
                        withContext(Dispatchers.IO) { mt.ensureModel(srcLang, tgtLang) }
                    }
                }
            },
            recognizeSource = { startTranslationPreparation ->
                val key = SourceKey(startMs, endMs, srcLang, selectedAudioTrackOrdinal())
                cachedSource(key, generation)
                    ?.copy(stageTimings = timer.timings)?.also { startTranslationPreparation() }
                    ?: recognizeWindow(startMs, endMs, srcLang, key.track, timer, startTranslationPreparation).also {
                        ensureRecoveryGeneration(generation)
                        cacheSource(key, it, generation)
                    }
            },
            publishSource = { source ->
                ensureRecoveryGeneration(generation)
                onSourceRecognized(source)
            },
            translateSource = { text ->
                ensureRecoveryGeneration(generation)
                timer.measure(ListenPipelineStage.Translation) { mt.translate(text, srcLang, tgtLang) }
            },
            timings = { timer.timings },
            awaitPreparation = { await ->
                if (srcLang == tgtLang) await() else {
                    // Only the exposed tail is outside ASR/source publication overlap.
                    timer.measure(ListenPipelineStage.TranslationPreparationWait) { await() }
                }
            },
        )
    }

    override suspend fun recoverEmptyTranslation(
        startMs: Long,
        endMs: Long,
        nextEndMs: Long,
        sourceLang: String,
        targetLang: String,
        original: ListenWindowResult,
    ): ListenEmptyTranslationRecovery? {
        if (!supportsEmptyTranslationRecovery(sourceLang, targetLang) || original.textSrc.isBlank() ||
            original.textTgt.isNotBlank() || original.retryableErrorMessage != null || original.blankReason != null
        ) return null
        if (startMs < 0L || startMs % 3_000L != 0L || endMs - startMs != 3_000L ||
            nextEndMs <= endMs || nextEndMs - endMs > 3_000L || nextEndMs > Long.MAX_VALUE - CONTEXT_PADDING_MS
        ) return null
        val duration = knownMediaDurationMs().coerceAtLeast(0L)
        if (duration > 0L && (endMs >= duration || nextEndMs > duration)) return null
        val srcLang = sourceLang.lowercase()
        val tgtLang = targetLang.lowercase()
        val key = SourceKey(startMs, endMs, srcLang, selectedAudioTrackOrdinal())
        val generation = recoveryGeneration
        ensureRecoveryGeneration(generation)
        synchronized(recoveryLock) {
            checkRecoveryGeneration(generation)
            // Only an actual successful empty MT result from this engine can trigger recovery.
            if (lastBaseline != BaselineOutcome(key, tgtLang, original)) return null
            val attempt = RecoveryKey(key, tgtLang)
            if (attemptedRecoveries.size >= MAX_RECOVERY_ATTEMPTS || !attemptedRecoveries.add(attempt)) return null
        }
        val timer = ListenStageTimer(monotonicTimeMs)
        return try {
            val nextKey = SourceKey(endMs, nextEndMs, srcLang, key.track)
            val next = cachedSource(nextKey, generation)?.copy(stageTimings = timer.timings)
                ?: recognizeWindow(endMs, nextEndMs, srcLang, key.track, timer, {}).also {
                    ensureRecoveryGeneration(generation)
                    cacheSource(nextKey, it, generation)
                }
            ensureRecoveryGeneration(generation)
            if (next.textSrc.isBlank() || next.blankReason != null || next.retryableErrorMessage != null) return null
            val combined = original.textSrc + next.textSrc
            // Offset analysis may omit whitespace; the MT/source bytes never do.
            val words = withContext(Dispatchers.Default) { recoveryMorphology.analyze(combined) }
            ensureRecoveryGeneration(generation)
            require(words.all { it.rawStart >= 0 && it.rawStart < it.rawEnd && it.rawEnd <= combined.length })
            require(words.zipWithNext().all { (a, b) -> a.rawEnd <= b.rawStart })
            if (words.none { it.rawStart < original.textSrc.length && original.textSrc.length < it.rawEnd }) return null
            val translated = timer.measure(ListenPipelineStage.Translation) { mt.translate(combined, srcLang, tgtLang) }
            ensureRecoveryGeneration(generation)
            if (translated.isBlank()) return null
            ListenEmptyTranslationRecovery(next, ListenWindowResult(
                textSrc = combined,
                textTgt = translated,
                cueStartMs = original.cueStartMs,
                cueEndMs = next.cueEndMs,
                processedThroughMs = nextEndMs,
                stageTimings = addRecoveryTimings(original.stageTimings, timer.timings),
            )).also { validateEmptyTranslationRecovery(startMs, endMs, nextEndMs, original, it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Keep the original durable source and leave both windows incomplete.
            ensureRecoveryGeneration(generation)
            null
        }
    }

    private suspend fun ensureRecoveryGeneration(generation: Long) {
        currentCoroutineContext().ensureActive()
        synchronized(recoveryLock) { checkRecoveryGeneration(generation) }
    }

    private fun checkRecoveryGeneration(generation: Long) {
        if (generation != recoveryGeneration) throw CancellationException("stale empty-translation recovery")
    }

    private fun cachedSource(key: SourceKey, generation: Long): ListenWindowResult? = synchronized(recoveryLock) {
        checkRecoveryGeneration(generation)
        cachedSources[key]
    }

    private fun cacheSource(key: SourceKey, source: ListenWindowResult, generation: Long) {
        synchronized(recoveryLock) {
            checkRecoveryGeneration(generation)
            if (source.textSrc.isBlank()) return
            cachedSources[key] = source
            while (cachedSources.size > 2) cachedSources.remove(cachedSources.keys.first())
        }
    }

    private suspend fun recognizeWindow(
        startMs: Long,
        endMs: Long,
        srcLang: String,
        track: Int?,
        timer: ListenStageTimer,
        startTranslationPreparation: () -> Unit,
    ): ListenWindowResult {
        val modelDir: File = asrModels.modelDir(srcLang)
            ?: throw ModelsNotReadyException(asrModels.incompleteModelMessage(srcLang))
        timer.measure(ListenPipelineStage.ModelCheck) {
            asr.ensureModel(modelDir, srcLang)
        }

        if (experimentalJapaneseSegments && srcLang == "ja") {
            return recognizeJapaneseSpan(startMs, endMs, track, timer, startTranslationPreparation)
        }

        val decodeStartMs = (startMs - CONTEXT_PADDING_MS).coerceAtLeast(0L)
        val decodeEndMs = endMs + CONTEXT_PADDING_MS
        val pcm = try {
            timer.measure(ListenPipelineStage.PcmRead) {
                audio.pcmWindow(
                    startMs = decodeStartMs,
                    endMs = decodeEndMs,
                    preferredAudioTrackOrdinal = track,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            throw IllegalStateException(
                "音频解码失败: ${t.message?.take(120) ?: t.javaClass.simpleName}",
                t,
            )
        }
        currentCoroutineContext().ensureActive()
        if (pcm.isNotEmpty()) onPcmReady(monotonicTimeMs())
        timer.recordRemoteReads(audio.takeRemoteReadStats())
        val blank = timer.measure(ListenPipelineStage.SilenceGate) {
            listenPcmBlankReason(pcm, experimentalSilenceGate)
        }
        when (val blankReason = blank) {
            null -> Unit
            else -> return ListenWindowResult(
                textSrc = "",
                textTgt = "",
                blankReason = blankReason,
                stageTimings = timer.timings,
            )
        }

        // Do not start/cancel model downloads repeatedly for confirmed silence.
        // Once PCM needs ASR, prepare MT concurrently with native recognition.
        startTranslationPreparation()
        val recognition = try {
            timer.measure(ListenPipelineStage.Asr) { asr.recognize(pcm) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            throw IllegalStateException(
                "ASR 失败: ${t.message?.take(120) ?: t.javaClass.simpleName}",
                t,
            )
        }
        val wordsInWindow = selectWordsForWindow(
            words = recognition.words,
            decodeStartMs = decodeStartMs,
            windowStartMs = startMs,
            windowEndMs = endMs,
        )
        val textSrc = if (wordsInWindow.isNotEmpty()) {
            wordsInWindow.joinToString(recognition.textSeparator) { it.text }.trim()
        } else if (recognition.words.isEmpty()) {
            recognition.text
        } else {
            ""
        }
        if (textSrc.isBlank()) {
            return ListenWindowResult(
                textSrc = "",
                textTgt = "",
                blankReason = ListenBlankReason.UnrecognizedSpeech,
                stageTimings = timer.timings,
            )
        }

        val cueStartMs = wordsInWindow.firstOrNull()
            ?.let { decodeStartMs + it.startMs }
            ?.coerceIn(startMs, endMs)
            ?: startMs
        val tokenEndMs = wordsInWindow.lastOrNull()
            ?.let { decodeStartMs + it.endMs }
            ?.coerceIn(cueStartMs, endMs)
            ?: endMs

        val cueEndMs = maxOf(tokenEndMs, cueStartMs + MIN_CUE_DURATION_MS).coerceAtMost(endMs)
        return ListenWindowResult(textSrc, "", cueStartMs, cueEndMs, stageTimings = timer.timings)
    }

    private suspend fun recognizeJapaneseSpan(
        startMs: Long,
        endMs: Long,
        track: Int?,
        timer: ListenStageTimer,
        startTranslationPreparation: () -> Unit,
    ): ListenWindowResult {
        require(startMs >= 0L && endMs > startMs)
        require(endMs - startMs <= JapaneseOwnedPrefixPolicy.MAX_OWNED_MS)
        require(endMs <= Long.MAX_VALUE - 3_000L)
        val duration = knownMediaDurationMs().coerceAtLeast(0L)
        val readEnd = (endMs + 3_000L).let { if (duration > 0L) minOf(it, duration) else it }
        require(readEnd > startMs)
        // One bounded network/decode read; both ASR passes slice the same PCM locally.
        val pcm = timer.measure(ListenPipelineStage.PcmRead) {
            audio.pcmWindow(startMs, readEnd, preferredAudioTrackOrdinal = track)
        }
        currentCoroutineContext().ensureActive()
        if (pcm.isNotEmpty()) onPcmReady(monotonicTimeMs())
        timer.recordRemoteReads(audio.takeRemoteReadStats())
        require(pcm.size <= JapaneseOwnedPrefixPolicy.MAX_READ_MS * 16L)
        val actualEnd = startMs + pcm.size / 16L
        if (actualEnd <= startMs) return ListenWindowResult("", "", blankReason = ListenBlankReason.EmptyPcm,
            stageTimings = timer.timings)
        val ownedLimit = minOf(endMs, actualEnd)
        val coreSamples = ((ownedLimit - startMs) * 16L).toInt()
        val core = if (coreSamples == pcm.size) pcm else pcm.copyOf(coreSamples)
        val blank = timer.measure(ListenPipelineStage.SilenceGate) {
            listenPcmBlankReason(core, experimentalSilenceGate)
        }
        if (blank != null) return ListenWindowResult("", "", blankReason = blank,
            stageTimings = timer.timings, processedThroughMs = ownedLimit)
        startTranslationPreparation()
        val (shorter, contextual) = timer.measure(ListenPipelineStage.Asr) {
            val first = asr.recognize(core)
            currentCoroutineContext().ensureActive()
            first to if (core === pcm) first else asr.recognize(pcm)
        }
        currentCoroutineContext().ensureActive()
        val tokens = contextual.words.map { JapaneseTimedToken(it.text, startMs + it.startMs) }
        val stableExclusive = listenStableTokenPrefix(shorter.text, tokens.map { it.text })
        val selected = JapaneseOwnedPrefixPolicy.select(
            tokens = tokens,
            quietRuns = japanesePcmQuietRuns(pcm, startMs),
            startMs = startMs,
            decodedThroughMs = actualEnd,
            endOfMedia = duration > 0L && actualEnd >= duration && ownedLimit >= duration,
            ownedLimitMs = ownedLimit,
            stableTokenEndExclusive = stableExclusive,
        ) ?: throw IllegalStateException("日语句段尚无可靠进展，未提交后续音频覆盖")
        require(selected.text.isNotBlank() && selected.processedThroughMs in (startMs + 1L)..ownedLimit)
        // Phrase-level bounds only. Never map an entire translation to invented word times.
        val cueStart = tokens.firstOrNull()?.startMs?.coerceIn(startMs, selected.processedThroughMs)
            ?: startMs
        return ListenWindowResult(selected.text, "", cueStart, selected.processedThroughMs,
            stageTimings = timer.timings, processedThroughMs = selected.processedThroughMs,
            segmentBoundaryReason = selected.reason.name)
    }

    override fun close() {
        resetEmptyTranslationRecovery()
        audio.close()
        asr.close()
        mt.close()
    }

    private companion object {
        const val CONTEXT_PADDING_MS = 750L
        const val MIN_CUE_DURATION_MS = 600L
        const val MAX_RECOVERY_ATTEMPTS = 64
    }
}

private fun addRecoveryTimings(original: ListenStageTimings?, recovery: ListenStageTimings): ListenStageTimings {
    val first = original ?: ListenStageTimings()
    fun sum(a: Long?, b: Long?): Long? = if (a == null && b == null) null else (a ?: 0L) + (b ?: 0L)
    return ListenStageTimings(
        modelCheckMs = sum(first.modelCheckMs, recovery.modelCheckMs),
        pcmReadMs = sum(first.pcmReadMs, recovery.pcmReadMs),
        silenceGateMs = sum(first.silenceGateMs, recovery.silenceGateMs),
        asrMs = sum(first.asrMs, recovery.asrMs),
        translationMs = sum(first.translationMs, recovery.translationMs),
        translationPreparationMs = first.translationPreparationMs,
        remoteReads = sum(first.remoteReads?.toLong(), recovery.remoteReads?.toLong())?.toInt(),
        remoteReadBytes = sum(first.remoteReadBytes, recovery.remoteReadBytes),
        translationPreparationWaitMs = first.translationPreparationWaitMs,
    )
}

internal fun listenPcmBlankReason(
    pcm16kMono: ShortArray,
    experimentalSilenceGate: Boolean = false,
): ListenBlankReason? = when {
    pcm16kMono.isEmpty() -> ListenBlankReason.EmptyPcm
    experimentalSilenceGate -> if (ConservativeSpeechGate.evaluate(pcm16kMono).shouldRecognize) {
        null
    } else {
        ListenBlankReason.NearSilence
    }
    isNearSilencePcm(pcm16kMono) -> ListenBlankReason.NearSilence
    else -> null
}

/** Do not reuse baseline silence coverage when the experimental gate is selected. */
internal fun listenAsrCacheModelId(modelId: String, experimentalSilenceGate: Boolean): String =
    if (experimentalSilenceGate) "$modelId|silence-gate-v1|text-v3" else "$modelId|digital-zero-v2|text-v3"

internal fun selectWordsForWindow(
    words: List<AsrWord>,
    decodeStartMs: Long,
    windowStartMs: Long,
    windowEndMs: Long,
): List<AsrWord> = words.filter { word ->
    val absoluteMidpoint = decodeStartMs + (word.startMs + word.endMs) / 2L
    absoluteMidpoint in windowStartMs until windowEndMs
}
