package com.framenest.feature.listen_translate

/** One window only, memory-only: no audio, text, paths, identifiers, or telemetry. */
data class ListenStageTimings(
    val modelCheckMs: Long? = null,
    val pcmReadMs: Long? = null,
    val silenceGateMs: Long? = null,
    val asrMs: Long? = null,
    val translationMs: Long? = null,
)

/**
 * Wall-clock elapsed times use a monotonic clock. Media delays use the last player
 * tick and are deliberately separate: 2x media lateness is not 2x processing time.
 * Stage durations include failed attempts. Null means not started/still running;
 * for cue readiness it means no cue was committed, not a zero-cost measurement.
 */
data class ListenPipelineDiagnostics(
    val stages: ListenStageTimings = ListenStageTimings(),
    val totalMs: Long = 0L,
    val queueWaitMs: Long = 0L,
    val cacheCommitMs: Long = 0L,
    val sourceReadyMs: Long? = null,
    val translationReadyMs: Long? = null,
    val sourceLatenessMs: Long? = null,
    val translationLatenessMs: Long? = null,
    /** Distance from the selected window's start to playback when work starts. */
    val windowBacklogMs: Long = 0L,
    val playbackRate: Float = 1f,
    val completed: Boolean = false,
    val failed: Boolean = false,
)

internal enum class ListenPipelineStage { ModelCheck, PcmRead, SilenceGate, Asr, Translation }

internal class ListenStageTimer(private val nowMs: () -> Long) {
    var timings = ListenStageTimings()
        private set

    suspend fun <T> measure(stage: ListenPipelineStage, block: suspend () -> T): T {
        val start = nowMs()
        return try {
            block()
        } finally {
            val elapsed = elapsedListenTimeMs(start, nowMs())
            timings = when (stage) {
                ListenPipelineStage.ModelCheck -> timings.copy(modelCheckMs = elapsed)
                ListenPipelineStage.PcmRead -> timings.copy(pcmReadMs = elapsed)
                ListenPipelineStage.SilenceGate -> timings.copy(silenceGateMs = elapsed)
                ListenPipelineStage.Asr -> timings.copy(asrMs = elapsed)
                ListenPipelineStage.Translation -> timings.copy(translationMs = elapsed)
            }
        }
    }
}

internal class ListenPipelineException(
    cause: Throwable,
    val stages: ListenStageTimings,
) : IllegalStateException(cause.message, cause)

internal fun elapsedListenTimeMs(startMs: Long, nowMs: Long): Long =
    (nowMs - startMs).coerceAtLeast(0L)

internal fun listenCueLatenessMs(positionMs: Long, cueStartMs: Long): Long =
    (positionMs - cueStartMs).coerceAtLeast(0L)

/** Deliberately content-free text; never includes cue text or media/model identifiers. */
internal fun formatListenDiagnostics(sample: ListenPipelineDiagnostics): String {
    fun Long?.ms(): String = this?.let { "${it}ms" } ?: "—"
    val stages = sample.stages
    val outcome = when {
        sample.failed -> "（失败；含失败阶段耗时）"
        sample.completed -> "（完成）"
        else -> "（处理中）"
    }
    return listOf(
        "最近窗口$outcome · ${sample.playbackRate}x",
        "模型检查 ${stages.modelCheckMs.ms()} · 音频读取/解码 ${stages.pcmReadMs.ms()}",
        "静音检查 ${stages.silenceGateMs.ms()} · ASR ${stages.asrMs.ms()} · 翻译 ${stages.translationMs.ms()}",
        "缓存/UI提交 ${sample.cacheCommitMs}ms · 处理总计 ${sample.totalMs}ms · 等待 ${sample.queueWaitMs}ms",
        "窗口起点落后播放 ${sample.windowBacklogMs}ms（媒体时间）",
        "原文就绪 ${sample.sourceReadyMs.ms()} · 译文就绪 ${sample.translationReadyMs.ms()}（自窗口开始）",
        "原文迟到 ${sample.sourceLatenessMs.ms()} · 译文迟到 ${sample.translationLatenessMs.ms()}（相对字幕起点、媒体时间）",
    ).joinToString("\n")
}
