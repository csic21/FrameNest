package com.framenest.feature.listen_translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

/**
 * One bounded window: overlap MT model preparation with ASR, never another
 * recognition window. Source publication does not wait for MT or its failures.
 * Every child belongs to the window, including when seek/background cancels it.
 */
internal suspend fun processListenWindowStages(
    prepareTranslation: suspend () -> Unit,
    recognizeSource: suspend (startTranslationPreparation: () -> Unit) -> ListenWindowResult,
    publishSource: suspend (ListenWindowResult) -> Unit,
    translateSource: suspend (String) -> String,
    timings: () -> ListenStageTimings,
    awaitPreparation: suspend (suspend () -> Unit) -> Unit = { it() },
): ListenWindowResult = supervisorScope {
    val preparation = async(start = CoroutineStart.LAZY) { prepareTranslation() }
    try {
        val source = recognizeSource { preparation.start() }
        if (source.textSrc.isBlank()) return@supervisorScope source.copy(stageTimings = timings())
        publishSource(source.copy(textTgt = "", stageTimings = timings()))
        val target = try {
            awaitPreparation { preparation.await() }
            translateSource(source.textSrc)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return@supervisorScope source.copy(
                textTgt = "",
                retryableErrorMessage = "翻译失败：${failure.message?.take(80) ?: "error"}",
                stageTimings = timings(),
            )
        }
        source.copy(textTgt = target, stageTimings = timings())
    } finally {
        // Blank audio / ASR failure must not wait for a model download. Cancellation
        // releases the pending ML Kit client through its existing ownership rules.
        preparation.cancel()
    }
}
