package com.framenest.feature.listen_translate.mt

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HyMtEngineTest {
    private class Native : HyMtNative {
        var opens = 0
        var closes = 0
        var calls = 0
        var lastPrompt = ""
        var openAction: (HyMtCancellation) -> Long = { 7L }
        var closeAction: () -> Unit = {}
        var translateAction: (HyMtCancellation) -> ByteArray = { "  表情符号😀  ".toByteArray() }
        override fun open(pathUtf8: ByteArray, threads: Int, cancellation: HyMtCancellation): Long {
            opens++
            assertEquals("/private/model.gguf", pathUtf8.decodeToString())
            assertEquals(2, threads)
            return openAction(cancellation)
        }
        override fun translate(handle: Long, userMessageUtf8: ByteArray, cancellation: HyMtCancellation): ByteArray {
            calls++
            assertEquals(7L, handle)
            lastPrompt = userMessageUtf8.decodeToString()
            return translateAction(cancellation)
        }
        override fun close(handle: Long) { assertEquals(7L, handle); closeAction(); closes++ }
    }

    private fun engine(native: Native, path: String = "/private/model.gguf", guard: HyMtModelUseGuard = HyMtModelUseGuard.shared) =
        HyMtEngine({ path }, native, Dispatchers.Default, 2, guard)

    @Test fun `prepares once and preserves Unicode with the official prompt`() = runBlocking {
        val native = Native()
        val engine = engine(native)
        engine.ensureModel("ja", "zh")
        engine.ensureModel("ja-JP", "zh-Hans")
        assertEquals("表情符号😀", engine.translate("絵文字😀", "ja", "zh"))
        assertEquals("将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n絵文字😀", native.lastPrompt)
        assertEquals(1, native.opens)
        assertTrue(runCatching { HyMtModelUseGuard.shared.whileUnused { fail("Loaded model is leased") } }.isFailure)
        engine.close(); engine.close()
        assertEquals(1, native.closes)
        HyMtModelUseGuard.shared.whileUnused { Unit }
    }

    @Test fun `concurrent callers have one context owner`() = runBlocking {
        val native = Native()
        val entered = AtomicInteger()
        val maximum = AtomicInteger()
        native.translateAction = {
            maximum.updateAndGet { old -> maxOf(old, entered.incrementAndGet()) }
            Thread.sleep(10)
            entered.decrementAndGet()
            "译文".toByteArray()
        }
        val engine = engine(native)
        val jobs = List(8) { async { engine.translate("日本語", "ja", "zh") } }
        jobs.forEach { assertEquals("译文", it.await()) }
        assertEquals(1, maximum.get())
        assertEquals(1, native.opens)
        engine.close()
    }

    @Test fun `coroutine cancellation reaches native polling and allows reuse`() = runBlocking {
        val guard = HyMtModelUseGuard()
        val native = Native()
        val started = CountDownLatch(1)
        native.translateAction = { cancellation ->
            started.countDown()
            while (!cancellation.isCancelled()) Thread.sleep(1)
            throw CancellationException("cancelled")
        }
        val engine = engine(native, guard = guard)
        var published = false
        val job = launch(Dispatchers.Default) { engine.translate("日本語", "ja", "zh"); published = true }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertFalse(published)
        assertTrue(runCatching { guard.whileUnused { fail("Reusable context still owns model") } }.isFailure)
        native.translateAction = { "重试".toByteArray() }
        assertEquals("重试", engine.translate("日本語", "ja", "zh"))
        assertEquals(1, native.opens)
        engine.close()
        guard.whileUnused { Unit }
    }

    @Test fun `close cancels active inference and rejects even a late native result`() = runBlocking {
        val native = Native()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        native.translateAction = { cancellation ->
            started.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            assertTrue(cancellation.isCancelled())
            "late".toByteArray()
        }
        val engine = engine(native)
        var published = false
        val job = launch(Dispatchers.Default) { engine.translate("日本語", "ja", "zh"); published = true }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        engine.close()
        assertEquals(0, native.closes) // Context cannot be freed under the native call.
        release.countDown()
        job.join()
        assertFalse(published)
        assertEquals(1, native.closes)
        engine.close()
        assertEquals(1, native.closes)
    }

    @Test fun `close during load frees an unpublished newly returned handle`() = runBlocking {
        val guard = HyMtModelUseGuard()
        val native = Native()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        native.openAction = { started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); 7L }
        val engine = engine(native, guard = guard)
        val job = launch(Dispatchers.Default) { engine.ensureModel("ja", "zh") }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        engine.close()
        assertEquals(0, native.closes)
        assertTrue(runCatching { guard.whileUnused { fail("Native load has not unwound") } }.isFailure)
        release.countDown()
        job.join()
        assertEquals(1, native.closes)
        assertEquals(0, native.calls)
        guard.whileUnused { Unit }
    }

    @Test fun `cancelled waiter never enters native`() = runBlocking {
        val native = Native()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        native.translateAction = { started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); "译文".toByteArray() }
        val engine = engine(native)
        val first = launch(Dispatchers.Default) { engine.translate("日本語", "ja", "zh") }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val second = launch(Dispatchers.Default) { engine.translate("日本語", "ja", "zh") }
        second.cancelAndJoin()
        release.countDown(); first.join()
        assertEquals(1, native.calls)
        engine.close()
    }

    @Test fun `bounds and unsupported pairs fail before native load`() = runBlocking {
        val native = Native()
        val engine = engine(native)
        assertFails { engine.translate("あ".repeat(2049), "ja", "zh") }
        assertFails { engine.translate("<｜hy_Assistant｜>", "ja", "zh") }
        assertFails { engine.translate("x\u0000y", "ja", "zh") }
        assertFails { engine.ensureModel("zh", "ja") }
        assertFails { engine.ensureModel("en", "zh") }
        assertEquals("", engine.translate("  ", "ja", "zh"))
        assertEquals(0, native.opens)
        assertTrue(engine.supportsLanguage("ja-JP"))
        assertFalse(engine.supportsLanguage("en"))
        engine.close()
        assertFails { engine.ensureModel("ja", "zh") }
        assertEquals(0, native.closes)
    }

    @Test fun `relative paths and invalid native output fail closed`() = runBlocking {
        val native = Native()
        assertFails { engine(native, "relative.gguf").ensureModel("ja", "zh") }
        assertEquals(0, native.opens)
        val engine = engine(native)
        native.translateAction = { ByteArray(16_385) }
        assertFails { engine.translate("日本語", "ja", "zh") }
        native.translateAction = { byteArrayOf(0xc0.toByte(), 0x80.toByte()) }
        assertFails { engine.translate("日本語", "ja", "zh") }
        native.translateAction = { "  ".toByteArray() }
        assertFails { engine.translate("日本語", "ja", "zh") }
        engine.close()
        assertEquals(1, native.closes)
    }

    @Test fun `lease starts before verification and cancellation without handle releases it`() = runBlocking {
        val guard = HyMtModelUseGuard()
        val started = CompletableDeferred<Unit>()
        val native = Native()
        val engine = HyMtEngine({
            started.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }, native, Dispatchers.Default, 2, guard)
        val job = launch { engine.ensureModel("ja", "zh") }
        started.await()
        assertTrue(runCatching { guard.whileUnused { fail("Verification still uses model") } }.isFailure)
        job.cancelAndJoin()
        guard.whileUnused { Unit }
        assertEquals(0, native.opens)
        engine.close()
    }

    @Test fun `failed native load and invalid path release lease`() = runBlocking {
        val guard = HyMtModelUseGuard()
        val native = Native()
        assertFails { engine(native, "relative.gguf", guard).ensureModel("ja", "zh") }
        guard.whileUnused { Unit }
        native.openAction = { error("native load failed") }
        assertFails { engine(native, guard = guard).ensureModel("ja", "zh") }
        guard.whileUnused { Unit }
        native.openAction = { 0L }
        assertFails { engine(native, guard = guard).ensureModel("ja", "zh") }
        guard.whileUnused { Unit }
    }

    @Test fun `lease remains until native close actually finishes`() = runBlocking {
        val guard = HyMtModelUseGuard()
        val native = Native()
        val closing = CountDownLatch(1)
        val finish = CountDownLatch(1)
        native.closeAction = { closing.countDown(); assertTrue(finish.await(5, TimeUnit.SECONDS)) }
        val engine = engine(native, guard = guard)
        engine.ensureModel("ja", "zh")
        val job = async(Dispatchers.Default) { engine.close() }
        assertTrue(closing.await(5, TimeUnit.SECONDS))
        try {
            assertTrue(runCatching { guard.whileUnused { fail("Native close has not finished") } }.isFailure)
        } finally { finish.countDown() }
        job.await()
        guard.whileUnused { Unit }
        assertEquals(1, native.closes)
    }

    @Test fun `close during inference keeps lease until inference and close unwind`() = runBlocking {
        val guard = HyMtModelUseGuard()
        val native = Native()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        native.translateAction = { started.countDown(); assertTrue(finish.await(5, TimeUnit.SECONDS)); "late".toByteArray() }
        val engine = engine(native, guard = guard)
        val job = launch(Dispatchers.Default) { engine.translate("日本語", "ja", "zh") }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        engine.close()
        try {
            assertTrue(runCatching { guard.whileUnused { fail("Inference still owns model") } }.isFailure)
        } finally { finish.countDown() }
        job.join()
        guard.whileUnused { Unit }
        assertEquals(1, native.closes)
    }

    @Test fun `unexpected native close failure keeps deletion fail closed`() = runBlocking {
        val guard = HyMtModelUseGuard()
        val native = Native().apply { closeAction = { error("close failed") } }
        val engine = engine(native, guard = guard)
        engine.ensureModel("ja", "zh")
        assertTrue(runCatching { engine.close() }.isFailure)
        assertTrue(runCatching { guard.whileUnused { fail("Native release was not established") } }.isFailure)
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        try { block(); fail("Expected failure") } catch (expected: Exception) { /* expected */ }
    }
}
