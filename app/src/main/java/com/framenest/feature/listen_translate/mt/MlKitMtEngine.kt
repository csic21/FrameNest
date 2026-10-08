package com.framenest.feature.listen_translate.mt

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * On-device MT via Google ML Kit Translate.
 * Language packs are downloaded into ML Kit's private storage (app-scoped).
 */
class MlKitMtEngine internal constructor(
    private val allowMeteredDownloads: Boolean,
    private val translatorFactory: (TranslatorOptions) -> Translator,
) {
    constructor(allowMeteredDownloads: Boolean = false) : this(
        allowMeteredDownloads = allowMeteredDownloads,
        translatorFactory = Translation::getClient,
    )

    private class TranslatorSlot {
        val preparation = Mutex()
        var translator: Translator? = null
    }

    private val lifecycleLock = Any()
    private val translators = mutableMapOf<String, TranslatorSlot>()
    private var closed = false

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
        val translation = synchronized(lifecycleLock) {
            check(!closed) { "Translation engine is closed" }
            translator.translate(text)
        }
        translation.await()
    }

    fun close() {
        val owned = synchronized(lifecycleLock) {
            closed = true
            translators.values.mapNotNull { it.translator }.also { translators.clear() }
        }
        owned.forEach { runCatching { it.close() } }
    }

    private suspend fun translatorFor(sourceLang: String, targetLang: String): Translator {
        val src = toMlKit(sourceLang)
        val tgt = toMlKit(targetLang)
        val key = "$src|$tgt"
        val slot = synchronized(lifecycleLock) {
            check(!closed) { "Translation engine is closed" }
            translators.getOrPut(key) { TranslatorSlot() }
        }
        // Only one caller prepares a language pair; different pairs can download together.
        return slot.preparation.withLock {
            currentCoroutineContext().ensureActive()
            val translator = synchronized(lifecycleLock) {
                check(!closed) { "Translation engine is closed" }
                slot.translator?.let { return it }
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(tgt)
                    .build()
                // Own the native client before any suspension, including a model download.
                translatorFactory(options).also { slot.translator = it }
            }
            try {
                val download = synchronized(lifecycleLock) {
                    check(!closed) { "Translation engine is closed" }
                    val conditions = DownloadConditions.Builder().apply {
                        if (!allowMeteredDownloads) requireWifi()
                    }.build()
                    translator.downloadModelIfNeeded(conditions)
                }
                download.await()
                currentCoroutineContext().ensureActive()
                synchronized(lifecycleLock) {
                    // close() can run while an uncancellable ML Kit download finishes.
                    check(!closed) { "Translation engine is closed" }
                    translator
                }
            } catch (failure: Throwable) {
                val ownsClient = synchronized(lifecycleLock) {
                    if (translators[key] === slot && slot.translator === translator) {
                        slot.translator = null
                        true
                    } else {
                        false // close() already took ownership of releasing this client.
                    }
                }
                if (ownsClient) runCatching { translator.close() }
                throw failure
            }
        }
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
