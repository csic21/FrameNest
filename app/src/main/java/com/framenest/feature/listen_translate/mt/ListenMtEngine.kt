package com.framenest.feature.listen_translate.mt

/** Translation lifecycle seam; preparation and inference remain separately measured. */
interface ListenMtEngine {
    fun supportsLanguage(langTag: String): Boolean
    suspend fun ensureModel(sourceLang: String, targetLang: String)
    suspend fun translate(text: String, sourceLang: String, targetLang: String): String
    fun close()
}
