package com.framenest.feature.listen_translate.mt

import kotlinx.coroutines.*

fun main(args: Array<String>) = runBlocking {
    val engine = HyMtEngine({ args[0] })
    val start = System.nanoTime()
    engine.ensureModel("ja", "zh")
    println("load_ms=" + (System.nanoTime() - start) / 1_000_000)
    val source = "日本語で質問してもいいですか。"
    val first = engine.translate(source, "ja", "zh")
    println("first=$first")
    check(first.isNotBlank())
    var published = false
    val job = launch(Dispatchers.Default) {
        engine.translate("これは日本語の文章です。".repeat(20), "ja", "zh")
        published = true
    }
    delay(100)
    val cancelled = System.nanoTime()
    job.cancelAndJoin()
    println("cancel_ms=" + (System.nanoTime() - cancelled) / 1_000_000)
    check(!published)
    val second = engine.translate(source, "ja", "zh")
    println("reused=$second")
    check(first == second) { "Reused context retained state" }
    check(runCatching { engine.translate("A ".repeat(1_000), "ja", "zh") }.isFailure) {
        "Oversized prompt must not silently truncate"
    }
    check(engine.translate(source, "ja", "zh") == first)
    var late = false
    val closing = launch(Dispatchers.Default) {
        engine.translate("これは日本語の文章です。".repeat(20), "ja", "zh")
        late = true
    }
    delay(100)
    engine.close()
    closing.join()
    check(!late)
    engine.close()
    println("JNI native model load, inference, cancellation, reuse and close passed")
}
