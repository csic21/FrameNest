package com.framenest.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs blocking native player teardown away from the Android main thread. */
internal object PlayerReleaseExecutor {

    fun launch(threadName: String, block: () -> Unit): Thread =
        Thread(block, threadName).apply {
            isDaemon = true
            start()
        }

    /**
     * Stop native playback off-main, detach Android views on main after stop has
     * completed, then finish native release off-main. This ordering keeps return
     * navigation responsive without moving Surface/View callbacks to a worker.
     */
    fun launchPhased(
        threadName: String,
        stopNative: () -> Unit,
        postToMain: ((() -> Unit) -> Unit),
        detachOnMain: () -> Unit,
        releaseNative: () -> Unit,
        mainPhaseTimeoutMs: Long = DEFAULT_MAIN_PHASE_TIMEOUT_MS,
        onMainPhaseTimeout: () -> Unit = {},
    ): Thread = launch(threadName) {
        runCatching(stopNative)

        val mainPhaseComplete = CountDownLatch(1)
        val posted = runCatching {
            postToMain {
                try {
                    detachOnMain()
                } finally {
                    mainPhaseComplete.countDown()
                }
            }
        }.isSuccess
        val completedWithinTimeout = posted && try {
            mainPhaseComplete.await(mainPhaseTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!completedWithinTimeout) onMainPhaseTimeout()

        // A timeout is diagnostic, not permission to release libVLC concurrently
        // with a queued Surface detach. Wait only on this daemon worker; UI remains
        // free, and the final native release cannot race Android view callbacks.
        if (posted && !completedWithinTimeout) {
            var interrupted = false
            while (mainPhaseComplete.count > 0L) {
                try {
                    mainPhaseComplete.await()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }

        runCatching(releaseNative)
    }

    private const val DEFAULT_MAIN_PHASE_TIMEOUT_MS: Long = 2_000L
}
