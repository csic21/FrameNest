package com.framenest.player

/** Runs blocking native player teardown away from the Android main thread. */
internal object PlayerReleaseExecutor {

    fun launch(threadName: String, block: () -> Unit): Thread =
        Thread(block, threadName).apply {
            isDaemon = true
            start()
        }
}
