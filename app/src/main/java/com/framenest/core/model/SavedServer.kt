package com.framenest.core.model

/**
 * Public server model for UI and cross-feature use (FN-04 / FN-05+).
 *
 * Sensitive password material is never included — only [credentialAlias]
 * points at encrypted storage (Keystore-backed EncryptedSharedPreferences).
 */
data class SavedServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = DEFAULT_PORT,
    val username: String,
    val domain: String? = null,
    val credentialAlias: String,
    /**
     * Optional primary share name used as a listShares candidate and as the
     * default entry point when opening the browser.
     */
    val defaultShare: String? = null,
    /** SMBJ browsing/auxiliary reads only; direct libVLC playback negotiates separately. */
    val requireEncryption: Boolean = true,
) {
    companion object {
        const val DEFAULT_PORT: Int = 445
    }
}
