package com.framenest.data.settings

/**
 * Offline ASR backend for listen-translate.
 *
 * - [SHERPA]: sherpa-onnx + SenseVoice-Small int8; one ~240MB pack covers
 *   zh/yue/en/ja/ko with punctuation. Default for new installs (FN-51).
 * - [VOSK]: legacy Vosk small packs (~40–90MB per language); kept as fallback
 *   and for fr/de/es, which SenseVoice does not cover.
 */
enum class AsrEngineChoice {
    VOSK,
    SHERPA,
    ;

    fun storageValue(): String = name.lowercase()

    companion object {
        fun fromStorage(value: String?): AsrEngineChoice =
            when (value?.trim()?.lowercase()) {
                "vosk" -> VOSK
                else -> SHERPA
            }
    }
}
