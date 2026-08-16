package com.framenest.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
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

    @Test
    fun `phased teardown stops then detaches on caller then releases on worker`() {
        val caller = Thread.currentThread()
        val mainQueue = LinkedBlockingQueue<() -> Unit>()
        val order = CopyOnWriteArrayList<String>()
        val stopThread = AtomicReference<Thread>()
        val detachThread = AtomicReference<Thread>()
        val releaseThread = AtomicReference<Thread>()
        val released = CountDownLatch(1)

        val launched = PlayerReleaseExecutor.launchPhased(
            threadName = "test-phased-release",
            stopNative = {
                stopThread.set(Thread.currentThread())
                order += "stop"
            },
            postToMain = { action -> mainQueue.put(action) },
            detachOnMain = {
                detachThread.set(Thread.currentThread())
                order += "detach"
            },
            releaseNative = {
                releaseThread.set(Thread.currentThread())
                order += "release"
                released.countDown()
            },
        )

        val detach = mainQueue.poll(1, TimeUnit.SECONDS)
        assertEquals(listOf("stop"), order)
        requireNotNull(detach).invoke()

        assertTrue(released.await(1, TimeUnit.SECONDS))
        launched.join(1_000L)
        assertEquals(listOf("stop", "detach", "release"), order)
        assertNotSame(caller, stopThread.get())
        assertSame(caller, detachThread.get())
        assertSame(stopThread.get(), releaseThread.get())
    }

    @Test
    fun `main detach timeout never races final release`() {
        val mainQueue = LinkedBlockingQueue<() -> Unit>()
        val timedOut = CountDownLatch(1)
        val released = CountDownLatch(1)

        val launched = PlayerReleaseExecutor.launchPhased(
            threadName = "test-timeout-release",
            stopNative = {},
            postToMain = { action -> mainQueue.put(action) },
            detachOnMain = {},
            releaseNative = { released.countDown() },
            mainPhaseTimeoutMs = 20L,
            onMainPhaseTimeout = { timedOut.countDown() },
        )

        val detach = mainQueue.poll(1, TimeUnit.SECONDS)
        assertTrue(timedOut.await(1, TimeUnit.SECONDS))
        assertFalse(released.await(50, TimeUnit.MILLISECONDS))

        requireNotNull(detach).invoke()
        assertTrue(released.await(1, TimeUnit.SECONDS))
        launched.join(1_000L)
        assertFalse(launched.isAlive)
    }
}
