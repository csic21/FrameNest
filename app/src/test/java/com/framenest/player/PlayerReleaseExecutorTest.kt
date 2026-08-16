package com.framenest.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerReleaseExecutorTest {

    @Test
    fun `native teardown runs on a named daemon worker`() {
        val caller = Thread.currentThread()
        val started = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        var worker: Thread? = null

        val launched = PlayerReleaseExecutor.launch("test-player-release") {
            worker = Thread.currentThread()
            started.countDown()
            allowFinish.await(1, TimeUnit.SECONDS)
        }

        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertNotSame(caller, worker)
        assertEquals("test-player-release", worker?.name)
        assertTrue(worker?.isDaemon == true)

        allowFinish.countDown()
        launched.join(1_000L)
        assertTrue(!launched.isAlive)
    }
}
