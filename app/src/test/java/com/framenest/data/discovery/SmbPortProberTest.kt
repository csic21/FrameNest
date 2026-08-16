package com.framenest.data.discovery

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbPortProberTest {

    @Test
    fun boundedScan_usesFixedWorkers_andVisitsEachAddressOnce() = runTest {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val visits = ConcurrentHashMap<Int, AtomicInteger>()
        val found = mutableListOf<Int>()
        val addresses = (0 until 100).toList()

        scanAddressesBounded(
            addresses = addresses,
            concurrency = 7,
            probe = { address ->
                visits.computeIfAbsent(address) { AtomicInteger(0) }.incrementAndGet()
                val nowActive = active.incrementAndGet()
                maximumActive.getAndUpdate { previous -> maxOf(previous, nowActive) }
                try {
                    delay(1L)
                    address % 10 == 0
                } finally {
                    active.decrementAndGet()
                }
            },
            onFound = { found += it },
        )

        assertEquals(7, probeWorkerCount(addresses.size, concurrency = 7))
        assertEquals(7, maximumActive.get())
        assertEquals(addresses.toSet(), visits.keys)
        assertTrue(visits.values.all { it.get() == 1 })
        assertEquals((0 until 100 step 10).toSet(), found.toSet())
    }

    @Test
    fun boundedScan_cancellationPropagates_withoutPublishingFoundHost() = runTest {
        val enteredProbe = CompletableDeferred<Unit>()
        val found = mutableListOf<Int>()
        val scanJob = launch {
            scanAddressesBounded(
                addresses = (0 until 20).toList(),
                concurrency = 4,
                probe = {
                    enteredProbe.complete(Unit)
                    awaitCancellation()
                },
                onFound = { found += it },
            )
        }

        enteredProbe.await()
        scanJob.cancelAndJoin()

        assertTrue(scanJob.isCancelled)
        assertTrue(found.isEmpty())
        assertEquals(32, probeWorkerCount(addressCount = 253, concurrency = 32))
    }
}
