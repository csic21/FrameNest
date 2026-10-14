package com.framenest.feature.listen_translate

import com.framenest.feature.listen_translate.asr.AsrRecognition
import com.framenest.feature.listen_translate.asr.isNearSilencePcm
import com.framenest.feature.listen_translate.asr.AsrEngine
import com.framenest.feature.listen_translate.asr.AsrModelSupport
import com.framenest.feature.listen_translate.audio.ListenAudioSource
import com.framenest.feature.listen_translate.mt.ListenMtEngine
import com.framenest.feature.listen_translate.recorded.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Optional recorded-file path. Audio advances on a fixed grid, never on token estimates. */
class RecordedFileListenTranslateEngine(
    private val audio: ListenAudioSource,
    private val asr: AsrEngine,
    private val mt: ListenMtEngine,
    private val asrModels: AsrModelSupport,
    private val selectedAudioTrackOrdinal: () -> Int? = { null },
    private val asrModelLabel: () -> String = { "sensevoice" },
    private val mtModelLabel: () -> String = { "hy-mt2" },
    private val onPcmReady: (Long) -> Unit = {},
    private val monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : RecordedListenTranslateEngine {
    private val mutex = Mutex()
    private var coordinator: RecordedSourceUnitCoordinator? = null
    private var generation = Long.MIN_VALUE
    private var eofMs: Long? = null
    private var pendingPublication: RecordedCoordinatorResult? = null
    private class PublicationFailure(cause: Exception) : IllegalStateException("字幕提交失败", cause)
    @Volatile private var closed = false
    override val asrModelId get() = asrModelLabel() + "|" + RecordedCaptionPolicy.VERSION
    override val mtModelId get() = mtModelLabel()
    override fun supportsRecordedPair(sourceLang: String, targetLang: String) =
        sourceLang.equals("ja", true) && targetLang.equals("zh", true)
    override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult =
        error("Recorded captions require independent read and completion cursors")

    override suspend fun processAhead(request: RecordedListenRequest, onCue: suspend (RecordedListenCue) -> Unit): RecordedListenProgress = withContext(Dispatchers.Default) { mutex.withLock {
        check(!closed)
        require(supportsRecordedPair(request.sourceLang, request.targetLang))
        require(request.generation >= generation) { "stale recorded generation" }
        if (generation != request.generation) {
            generation = request.generation
            // Include preceding acoustic context after a seek; left edge remains explicitly uncertain.
            val start = ((request.positionMs - 9_000L).coerceAtLeast(0L) / 9_000L) * 9_000L
            coordinator = RecordedSourceUnitCoordinator(KuromojiJapaneseMorphology(), generation, start)
            eofMs = null
            pendingPublication = null
        }
        val c = checkNotNull(coordinator)
        val before = c.snapshot()
        val timer = ListenStageTimer(monotonicTimeMs)
        var state = before
        var changed = false
        suspend fun publish(result: RecordedCoordinatorResult) {
            check(result.accepted) { result.error ?: "recorded result rejected" }
            pendingPublication = result
            try {
            currentCoroutineContext().ensureActive(); check(!closed)
            for (record in result.records) {
                if (record.state == RecordedResultState.SUPERSEDED) continue
                val unit = record.unit
                val superseded = result.records.firstOrNull { it.state == RecordedResultState.SUPERSEDED && it.supersededBy == unit.id }?.unit
                val translated = record.translatedText.orEmpty()
                val final = unit.status == RecordedSourceStatus.FINAL && record.state == RecordedResultState.TRANSLATED
                onCue(RecordedListenCue(unit.displayStartMs, unit.displayEndMs, unit.text, translated,
                    isFinal = final, coverageStartMs = if (final) unit.coverageStartMs else null,
                    coverageEndMs = if (final) unit.coverageEndMs else null,
                    decodedThroughMs = result.readThroughMs, reason = unit.reason.name, timings = timer.timings,
                    supersededStartMs = superseded?.displayStartMs, supersededEndMs = superseded?.displayEndMs))
                changed = true
            }
            state = result
            pendingPublication = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { throw PublicationFailure(failure) }
        }
        pendingPublication?.let { saved ->
            publish(saved)
            return@withLock RecordedListenProgress(saved.readThroughMs, 0L, changed, true, timings = timer.timings)
        }
        val action = before.nextAction
        if (action is RecordedNextAction.DecodeWindow && eofMs == null &&
            action.startMs > request.positionMs + request.lookAheadMs.coerceIn(0L, 30_000L)) {
            return@withLock RecordedListenProgress(before.readThroughMs, 0L, false, true)
        }
        // EOF flush is explicit decoder evidence, never a short resampled PCM inference.
        if (eofMs != null && action is RecordedNextAction.DecodeWindow) {
            publish(c.finish(generation, checkNotNull(eofMs)))
        } else when (action) {
            is RecordedNextAction.Translate -> {
                val ticket = action.ticket
                if (ticket.unit.status != RecordedSourceStatus.FINAL) {
                    // Whole-context tests show that MT can invent a conclusion for incomplete text.
                    publish(c.deferUnconfirmed(ticket))
                } else {
                    try {
                        timer.measure(ListenPipelineStage.TranslationPreparation) { mt.ensureModel(request.sourceLang, request.targetLang) }
                        val translated = timer.measure(ListenPipelineStage.Translation) {
                            mt.translate(ticket.unit.text, request.sourceLang, request.targetLang)
                        }
                        currentCoroutineContext().ensureActive()
                        publish(c.translationSucceeded(ticket, translated))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        if (failure is PublicationFailure) throw failure
                        publish(c.translationFailed(ticket, failure.message?.take(120) ?: "翻译失败"))
                    }
                }
            }
            is RecordedNextAction.DecodeWindow, is RecordedNextAction.DecodeUnion -> {
                val start = if (action is RecordedNextAction.DecodeWindow) action.startMs else (action as RecordedNextAction.DecodeUnion).request.startMs
                val requestedEnd = if (action is RecordedNextAction.DecodeWindow) start + action.durationMs else (action as RecordedNextAction.DecodeUnion).request.endMs
                val end = if (request.durationMs > 0) minOf(requestedEnd, request.durationMs) else requestedEnd
                if (end <= start) {
                    // Player duration alone cannot certify an undecoded audio tail.
                    return@withLock RecordedListenProgress(before.readThroughMs, 0, false, true, "等待音频尾部确认")
                }
                try {
                    val model = asrModels.modelDir(request.sourceLang) ?: throw ModelsNotReadyException(asrModels.incompleteModelMessage(request.sourceLang))
                    timer.measure(ListenPipelineStage.ModelCheck) { asr.ensureModel(model, request.sourceLang) }
                    val pcm = timer.measure(ListenPipelineStage.PcmRead) { audio.pcmWindowDetailed(start, end, selectedAudioTrackOrdinal()) }
                    timer.recordRemoteReads(audio.takeRemoteReadStats())
                    val through = requireNotNull(pcm.decodedThroughMs) { "当前音频源缺少录播时间边界" }.coerceAtMost(end)
                    if (pcm.reachedEndOfStream && pcm.samples.isEmpty() && action is RecordedNextAction.DecodeWindow) {
                        require(through >= before.readThroughMs) { "音频 EOF 倒退" }
                        eofMs = through
                        publish(c.finish(generation, through))
                        return@withLock RecordedListenProgress(state.readThroughMs,
                            (state.readThroughMs - before.readThroughMs).coerceAtLeast(0L), changed,
                            state.nextAction !is RecordedNextAction.Finished, timings = timer.timings)
                    }
                    require(through > start) { "音频读取未推进" }
                    if (pcm.samples.isNotEmpty()) onPcmReady(monotonicTimeMs())
                    val recognition = if (isNearSilencePcm(pcm.samples)) AsrRecognition.EMPTY else
                        timer.measure(ListenPipelineStage.Asr) { asr.recognize(pcm.samples) }
                    val tokens = recognition.words.map { RecordedToken(it.text, (start + it.startMs).coerceIn(start, through - 1)) }
                    require(tokens.isNotEmpty() || recognition.text.isBlank()) { "ASR 返回原文但没有可靠片段映射" }
                    val quiet = RecordedPcmQuietDetector.detect(FloatArray(pcm.samples.size) { pcm.samples[it] / 32768f }, 16_000, start).mapNotNull {
                        val stop = minOf(it.endMs, through)
                        if (stop > it.startMs) RecordedQuietSpan(it.startMs, stop) else null
                    }
                    val input = RecordedWindowInput(RecordedHypothesis(generation, start, through, tokens), quiet)
                    if (pcm.reachedEndOfStream) eofMs = through
                    publish(if (action is RecordedNextAction.DecodeUnion) c.recoverUnion(input) else c.push(input))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (failure is PublicationFailure) throw failure
                    if (action is RecordedNextAction.DecodeUnion) publish(c.failUnion(generation, "扩展上下文未确认"))
                    else throw failure
                }
            }
            RecordedNextAction.Finished -> Unit
        }
        val advanced = (state.readThroughMs - before.readThroughMs).coerceAtLeast(0L)
        RecordedListenProgress(state.readThroughMs, advanced, changed || advanced > 0L,
            pending = state.nextAction !is RecordedNextAction.Finished || state.pendingText.isNotBlank(),
            message = when {
                state.records.any { it.state == RecordedResultState.TRANSLATION_FAILED } -> "翻译未完成，已保留原文；可关闭后重新启用听译重试"
                state.records.any { it.unit.status != RecordedSourceStatus.FINAL } -> "部分原文句段待确认"
                else -> null
            },
            timings = timer.timings)
    }

    }

    override fun close() {
        closed = true
        runCatching { audio.close() }
        runCatching { mt.close() }
        runCatching { asr.close() }
    }
}
