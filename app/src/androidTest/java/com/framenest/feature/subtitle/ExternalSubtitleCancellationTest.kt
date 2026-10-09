package com.framenest.feature.subtitle

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framenest.smb.GatedAuxiliarySmbClient
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalSubtitleCancellationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val request = ExternalSubtitleLoader.LoadRequest(
        host = "example.invalid", username = "", password = charArrayOf(),
        share = "media", remotePath = "movie.srt", fileName = "movie.srt",
    )

    @Test fun exitAbortsBlockedSubtitleConnect() = verifyExit(GatedAuxiliarySmbClient.Stage.CONNECT)
    @Test fun exitAbortsBlockedSubtitleRead() = verifyExit(GatedAuxiliarySmbClient.Stage.READ)

    private fun verifyExit(stage: GatedAuxiliarySmbClient.Stage) = runBlocking {
        val client = GatedAuxiliarySmbClient(stage)
        val loader = ExternalSubtitleLoader(context, { client }, sessionCacheKey = UUID.randomUUID().toString())
        val load = async(Dispatchers.IO) { loader.loadToLocalFile(request) }
        try {
            assertTrue(client.entered.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { loader.close() }
            assertTrue(client.aborted.await(5, TimeUnit.SECONDS))
            assertNotEquals(android.os.Looper.getMainLooper().thread, client.abortThread)
            runCatching { withTimeout(5_000) { load.await() } }
            assertTrue(load.isCancelled)
            assertEquals(1, client.aborts.get())
        } finally {
            client.unblock.countDown()
            loader.clearCache()
        }
    }

    @Test fun replacingSubtitleLoadLeavesNewGenerationUsable() = runBlocking {
        val old = GatedAuxiliarySmbClient(GatedAuxiliarySmbClient.Stage.READ)
        val fresh = GatedAuxiliarySmbClient()
        val count = AtomicInteger()
        val loader = ExternalSubtitleLoader(
            context, { if (count.incrementAndGet() == 1) old else fresh },
            sessionCacheKey = UUID.randomUUID().toString(),
        )
        val oldLoad = async(Dispatchers.IO) { loader.loadToLocalFile(request) }
        try {
            assertTrue(old.entered.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { loader.cancelPendingLoads() }
            val result = loader.loadToLocalFile(request).getOrThrow()
            assertTrue(result.localFile.readText().contains("Test subtitle"))
            runCatching { withTimeout(5_000) { oldLoad.await() } }
            assertTrue(oldLoad.isCancelled)
            assertEquals(1, fresh.connects.get())
            assertTrue(result.localFile.isFile)
        } finally {
            old.unblock.countDown()
            loader.clearCache()
        }
    }
}
