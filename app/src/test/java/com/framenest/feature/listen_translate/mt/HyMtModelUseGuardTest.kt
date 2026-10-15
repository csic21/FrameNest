package com.framenest.feature.listen_translate.mt

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HyMtModelUseGuardTest {
    @Test fun multipleUsersAndRepeatedCloseNeverReleaseAnotherLease() {
        val guard = HyMtModelUseGuard()
        val first = guard.acquire()
        val second = guard.acquire()
        assertTrue(runCatching { guard.whileUnused { fail("Must not delete") } }.isFailure)
        first.close(); first.close()
        assertTrue(runCatching { guard.whileUnused { fail("Second lease still active") } }.isFailure)
        second.close()
        assertEquals("deleted", guard.whileUnused { "deleted" })
    }

    @Test fun deleteAndAcquireAreExclusiveWithoutHoldingThreadLockDuringWork() = runBlocking {
        val guard = HyMtModelUseGuard()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val delete = async(Dispatchers.IO) {
            guard.whileUnused {
                started.countDown()
                assertTrue(finish.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        try {
            assertTrue(runCatching { guard.acquire() }.isFailure)
            assertTrue(runCatching { guard.whileUnused { fail("Concurrent deletion") } }.isFailure)
        } finally { finish.countDown() }
        delete.await()
        guard.acquire().close()
        guard.whileUnused { Unit }
    }

    @Test fun failedDeletionClearsDeletionFlag() {
        val guard = HyMtModelUseGuard()
        assertTrue(runCatching { guard.whileUnused { error("disk failed") } }.isFailure)
        guard.acquire().close()
        guard.whileUnused { Unit }
    }
}
