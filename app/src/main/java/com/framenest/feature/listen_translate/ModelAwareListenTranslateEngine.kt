package com.framenest.feature.listen_translate

import com.framenest.data.listen_translate.model.ListenModelManager

/**
 * Uses installed on-device packs (FN-13). When packs are missing, [processWindow]
 * fails with [ModelsNotReadyException] so the UI can prompt install.
 *
 * Current packs are layout-compatible placeholders: MT applies a small phrase table;
 * ASR remains time-window text until whisper/sherpa weights replace the ASR pack.
 */
class ModelAwareListenTranslateEngine(
    private val models: ListenModelManager,
    private val fallbackWhenMissing: ListenTranslateEngine? = null,
) : ListenTranslateEngine {

    override val asrModelId: String
        get() = models.asrModelId()

    override val mtModelId: String
        get() = models.mtModelId()

    override suspend fun processWindow(
        startMs: Long,
        endMs: Long,
        sourceLang: String,
        targetLang: String,
    ): ListenWindowResult {
        if (!models.areCoreModelsReady()) {
            val fallback = fallbackWhenMissing
            if (fallback != null) {
                return fallback.processWindow(startMs, endMs, sourceLang, targetLang)
            }
            throw ModelsNotReadyException(
                "请先在设置中安装听译模型（ASR + MT），数据仅保存在应用内。",
            )
        }

        val s = (startMs / 1000L).coerceAtLeast(0L)
        val e = (endMs / 1000L).coerceAtLeast(s)
        // Placeholder ASR text — pack id is real; swap implementation when weights land.
        val src = "[${sourceLang}] ${s}s–${e}s"
        val pairKey = "${sourceLang.lowercase()}|${targetLang.lowercase()}"
        val table = models.loadMtPhraseTable()[pairKey].orEmpty()
        val tgt = translateWithTable(src, table, targetLang, s, e)
        return ListenWindowResult(textSrc = src, textTgt = tgt)
    }

    private fun translateWithTable(
        src: String,
        table: Map<String, String>,
        targetLang: String,
        s: Long,
        e: Long,
    ): String {
        // Prefer exact phrase hits inside src; else structured target template.
        for ((k, v) in table) {
            if (src.contains(k, ignoreCase = true)) {
                return v
            }
        }
        return when (targetLang.lowercase()) {
            "zh" -> "【$s–${e}秒】"
            "en" -> "[$s–${e}s]"
            else -> "[$targetLang] $s–${e}s"
        }
    }
}

class ModelsNotReadyException(message: String) : IllegalStateException(message)

/** Factory used by the player (legacy placeholder packs — prefer [RealListenTranslateEngine]). */
object ListenTranslateEngineFactory {
    fun create(models: ListenModelManager): ListenTranslateEngine =
        ModelAwareListenTranslateEngine(
            models = models,
            fallbackWhenMissing = null,
        )

    fun createWithStubFallback(models: ListenModelManager): ListenTranslateEngine =
        ModelAwareListenTranslateEngine(
            models = models,
            fallbackWhenMissing = StubListenTranslateEngine(),
        )
}
