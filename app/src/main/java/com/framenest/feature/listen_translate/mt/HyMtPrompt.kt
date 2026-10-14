package com.framenest.feature.listen_translate.mt

import java.util.Locale

/** Only the Japanese → Chinese pair exercised by the pinned-model experiment. */
internal object HyMtPrompt {
    const val MAX_SOURCE_CHARS = 2_048
    const val MAX_SOURCE_BYTES = 8_192
    const val MAX_OUTPUT_BYTES = 16_384
    const val CONTEXT_TOKENS = 1_024
    const val OUTPUT_TOKENS = 256

    fun language(tag: String): String? = when (tag.lowercase(Locale.ROOT)) {
        "ja", "ja-jp" -> "ja"
        "zh", "zh-cn", "zh-hans", "cn" -> "zh"
        else -> null
    }

    fun validatePair(source: String, target: String) {
        require(language(source) == "ja" && language(target) == "zh") {
            "Hy-MT2 currently supports Japanese to Simplified Chinese only"
        }
    }

    fun userMessage(text: String): ByteArray {
        require(text.length <= MAX_SOURCE_CHARS && text.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES) {
            "Translation input exceeds the local model limit"
        }
        // These strings are special-token delimiters in the pinned model; source text
        // must never be allowed to manufacture another chat role.
        require(!text.contains("<｜") && '\u0000' !in text) { "Unsupported special token in translation input" }
        return "将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n$text".toByteArray(Charsets.UTF_8)
    }
}
