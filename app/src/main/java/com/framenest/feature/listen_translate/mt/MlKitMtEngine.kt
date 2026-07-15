package com.framenest.feature.listen_translate.mt

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * On-device MT via Google ML Kit Translate.
 * Language packs are downloaded into ML Kit's private storage (app-scoped).
 */
class MlKitMtEngine {
    private val translators = ConcurrentHashMap<String, Translator>()

    suspend fun ensureModel(sourceLang: String, targetLang: String) {
        if (sourceLang.equals(targetLang, ignoreCase = true)) return
        translatorFor(sourceLang, targetLang)
    }

    suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
    ): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""
        if (sourceLang.equals(targetLang, ignoreCase = true)) return@withContext text
        val translator = translatorFor(sourceLang, targetLang)
        translator.translate(text).await()
    }

    fun close() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }

    private suspend fun translatorFor(sourceLang: String, targetLang: String): Translator {
        val src = toMlKit(sourceLang)
        val tgt = toMlKit(targetLang)
        val key = "$src|$tgt"
        translators[key]?.let { return it }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(src)
            .setTargetLanguage(tgt)
            .build()
        val translator = Translation.getClient(options)
        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions).await()
        translators[key] = translator
        return translator
    }

    companion object {
        fun toMlKit(lang: String): String {
            return when (lang.lowercase()) {
                "zh", "zh-cn", "zh-hans", "cn" -> TranslateLanguage.CHINESE
                "en" -> TranslateLanguage.ENGLISH
                "ja" -> TranslateLanguage.JAPANESE
                "ko" -> TranslateLanguage.KOREAN
                "fr" -> TranslateLanguage.FRENCH
                "de" -> TranslateLanguage.GERMAN
                "es" -> TranslateLanguage.SPANISH
                "ru" -> TranslateLanguage.RUSSIAN
                "pt" -> TranslateLanguage.PORTUGUESE
                "it" -> TranslateLanguage.ITALIAN
                "hi" -> TranslateLanguage.HINDI
                "ar" -> TranslateLanguage.ARABIC
                "th" -> TranslateLanguage.THAI
                "vi" -> TranslateLanguage.VIETNAMESE
                "id" -> TranslateLanguage.INDONESIAN
                "tr" -> TranslateLanguage.TURKISH
                "pl" -> TranslateLanguage.POLISH
                "nl" -> TranslateLanguage.DUTCH
                else -> error("ML Kit 不支持的语言: $lang")
            }
        }

        fun isSupported(lang: String): Boolean =
            runCatching { toMlKit(lang) }.isSuccess
    }
}
