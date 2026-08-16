package com.framenest.feature.player

import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenPreparationCleanupTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancelledPreparation_closesEveryResourceOnCleanupDispatcher() = runTest {
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "listen-cleanup-test")
        }.asCoroutineDispatcher()
        val closed = Collections.synchronizedList(mutableListOf<String>())
        val threads = Collections.synchronizedList(mutableListOf<String>())
        try {
            val preparation = launch {
                try {
                    awaitCancellation()
                } finally {
                    closeListenPreparationResources(
                        closeActions = listOf(
                            {
                                closed += "audio"
                                threads += Thread.currentThread().name
                                error("audio close failed")
                            },
                            {
                                closed += "asr"
                                threads += Thread.currentThread().name
                            },
                            {
                                closed += "mt"
                                threads += Thread.currentThread().name
                            },
                        ),
                        dispatcher = dispatcher,
                    )
                }
            }

            runCurrent()
            preparation.cancelAndJoin()

            assertEquals(listOf("audio", "asr", "mt"), closed)
            assertTrue(threads.all { it.startsWith("listen-cleanup-test") })
        } finally {
            dispatcher.close()
        }
    }
}
