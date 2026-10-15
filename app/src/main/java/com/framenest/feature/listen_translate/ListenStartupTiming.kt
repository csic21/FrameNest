package com.framenest.feature.listen_translate

/** One bounded, memory-only sample. No cue text, media identity, paths or telemetry. */
data class ListenStartupTiming(
    /** ASR install (if needed), integrity verification and native model loading. */
    val asrPreparationMs: Long? = null,
    /** First nonempty decoded PCM; silence is valid PCM, not recognized speech. */
    val firstPcmReadyMs: Long? = null,
    val firstSourcePublishedMs: Long? = null,
    val firstBilingualPublishedMs: Long? = null,
    /** First generated bilingual window; independent of an earlier warm-cache milestone. */
    val firstGeneratedBilingualStages: ListenStageTimings? = null,
    val firstGeneratedBilingualCachePublishMs: Long? = null,
    val firstSourceSelectedMs: Long? = null,
    val firstBilingualSelectedMs: Long? = null,
)

/**
 * Monotonic enable/preparation-to-overlay-selection timing, not physical rendering.
 * The wall-time milestones include queueing, cache reads/commits and overlapping
 * stages; summing individual stage durations would overstate the critical path.
 * A token rejects preparation callbacks from a previous attempt after reset.
 */
internal class ListenStartupTimer(private val nowMs: () -> Long) {
    @Volatile
    private var generation = 0L
    val token: Long get() = generation
    private var startedAtMs: Long? = null
    var sample: ListenStartupTiming? = null
        private set

    fun begin(): Long {
        generation++
        startedAtMs = nowMs()
        sample = ListenStartupTiming()
        return generation
    }

    fun reset() {
        generation++
        startedAtMs = null
        sample = null
    }

    fun recordAsrPreparation(token: Long, elapsedMs: Long) {
        if (token != generation) return
        sample = sample?.copy(asrPreparationMs = elapsedMs.coerceAtLeast(0L))
    }

    fun recordFirstPcm(token: Long, eventAtMs: Long) {
        if (token != generation) return
        val started = startedAtMs ?: return
        if (eventAtMs < started) return
        val current = sample ?: return
        if (current.firstPcmReadyMs != null) return
        sample = current.copy(firstPcmReadyMs = (eventAtMs - started).coerceAtLeast(0L))
    }

    fun publish(
        hasSource: Boolean,
        hasBilingual: Boolean,
    ) {
        val started = startedAtMs ?: return
        val current = sample ?: return
        if ((!hasSource || current.firstSourcePublishedMs != null) &&
            (!hasBilingual || current.firstBilingualPublishedMs != null)) return
        val elapsed = (nowMs() - started).coerceAtLeast(0L)
        sample = current.copy(
            firstSourcePublishedMs = current.firstSourcePublishedMs ?: elapsed.takeIf { hasSource },
            firstBilingualPublishedMs = current.firstBilingualPublishedMs ?: elapsed.takeIf { hasBilingual },
        )
    }

    fun recordGeneratedBilingual(stages: ListenStageTimings?, cachePublishMs: Long) {
        val current = sample ?: return
        if (current.firstGeneratedBilingualCachePublishMs != null) return
        sample = current.copy(
            firstGeneratedBilingualStages = stages,
            firstGeneratedBilingualCachePublishMs = cachePublishMs.coerceAtLeast(0L),
        )
    }

    fun select(sourceVisible: Boolean, bilingualVisible: Boolean) {
        val started = startedAtMs ?: return
        val current = sample ?: return
        if ((!sourceVisible || current.firstSourceSelectedMs != null) &&
            (!bilingualVisible || current.firstBilingualSelectedMs != null)) return
        val elapsed = (nowMs() - started).coerceAtLeast(0L)
        sample = current.copy(
            firstSourceSelectedMs = current.firstSourceSelectedMs
                ?: elapsed.takeIf { sourceVisible },
            firstBilingualSelectedMs = current.firstBilingualSelectedMs
                ?: elapsed.takeIf { bilingualVisible },
        )
    }
}

internal fun formatListenStartupTiming(sample: ListenStartupTiming): String {
    fun Long?.ms(): String = this?.let { "${it}ms" } ?: "—"
    val stages = sample.firstGeneratedBilingualStages
    return listOfNotNull(
        "本次启动（单调时钟墙时；并行阶段不相加）",
        "ASR 安装/校验/载入 ${sample.asrPreparationMs.ms()} · 首次 PCM 到达 ${sample.firstPcmReadyMs.ms()}（非语音识别）",
        "首条原文缓存就绪 ${sample.firstSourcePublishedMs.ms()} · 首条双语缓存就绪 ${sample.firstBilingualPublishedMs.ms()}",
        stages?.let {
            "首个新生成双语窗口：PCM ${it.pcmReadMs.ms()} · ASR ${it.asrMs.ms()} · 翻译准备 ${it.translationPreparationMs.ms()} · 翻译 ${it.translationMs.ms()} · 缓存/发布 ${sample.firstGeneratedBilingualCachePublishMs.ms()}"
        },
        "首条原文选入叠层 ${sample.firstSourceSelectedMs.ms()}",
        "首条双语选入叠层 ${sample.firstBilingualSelectedMs.ms()}（自启用/准备开始，非屏幕渲染）",
    ).joinToString("\n")
}
