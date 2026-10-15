package com.framenest.feature.listen_translate.mt

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Host-only O0/O3 control, using the actual JNI bridge/model/CPU2 settings. */
fun main(args: Array<String>) = runBlocking {
    require(args.size == 1)
    val model = File(args[0]).canonicalFile
    require(model.length() == 1_133_080_448L)
    val digest = MessageDigest.getInstance("SHA-256")
    model.inputStream().buffered().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    val sha = digest.digest().joinToString("") { "%02x".format(it) }
    require(sha == "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699")
    val engine = HyMtEngine({ model.absolutePath }, threadCount = 2)
    try {
        val start = System.nanoTime()
        engine.ensureModel("ja", "zh")
        println("load_ms=" + (System.nanoTime() - start) / 1_000_000)
        val source = "日本語で質問してもいいですか。"
        println("source=$source")
        var previous: String? = null
        repeat(2) { index ->
            val inferenceStart = System.nanoTime()
            // Host diagnosis only. Android acceptance limits are unchanged.
            val result = withTimeout(600_000) { engine.translate(source, "ja", "zh") }
            println("inference_${index + 1}_ms=" + (System.nanoTime() - inferenceStart) / 1_000_000)
            println("target_${index + 1}=$result")
            require(result.isNotBlank() && result != source)
            if (previous != null) check(previous == result) { "Seeded context reuse changed output" }
            previous = result
        }
        println("verified_model_sha256=$sha")
        println("host_only=true")
    } finally {
        engine.close()
    }
}
