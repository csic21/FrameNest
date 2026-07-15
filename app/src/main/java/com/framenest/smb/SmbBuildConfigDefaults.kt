package com.framenest.smb

import com.framenest.BuildConfig

/**
 * Non-sensitive empty defaults for the debug spike.
 * NAS details are entered at runtime and are never compiled into BuildConfig.
 */
object SmbBuildConfigDefaults {
    fun host(): String = BuildConfig.SMB_HOST
    fun port(): Int = BuildConfig.SMB_PORT
    fun username(): String = BuildConfig.SMB_USERNAME
    fun passwordChars(): CharArray = BuildConfig.SMB_PASSWORD.toCharArray()
    fun domain(): String = BuildConfig.SMB_DOMAIN
    fun share(): String = BuildConfig.SMB_SHARE
    fun path(): String = BuildConfig.SMB_PATH
    fun testFile(): String = BuildConfig.SMB_TEST_FILE

    fun hasHost(): Boolean = host().isNotBlank()

    fun toCredentialsOrNull(): SmbCredentials? {
        if (!hasHost()) return null
        return SmbCredentials(
            host = host(),
            port = port(),
            username = username(),
            password = passwordChars(),
            domain = domain(),
        )
    }
}
