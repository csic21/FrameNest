package com.framenest.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hierynomus.security.Cipher.CryptMode
import com.hierynomus.security.bc.BCSecurityProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.spec.GCMParameterSpec

/** Real Android linkage/crypto checks for the security-only transitive upgrades; no NAS needed. */
@RunWith(AndroidJUnit4::class)
class SecurityCompatibilityTest {
    @Test fun smbAuthenticationDigestAndSigningMatchKnownVectors() {
        val provider = BCSecurityProvider()
        assertEquals("31d6cfe0d16ae931b73c59d7e0c089c0", provider.getDigest("MD4").digest().hex())
        val mac = provider.getMac("AESCMAC")
        mac.init("2b7e151628aed2a6abf7158809cf4f3c".chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        assertEquals("bb1d6929e95937287fa37d129b756746", mac.doFinal().hex())
    }

    @Test fun smbEncryptionRoundTripsAndRejectsTampering() {
        val provider = BCSecurityProvider()
        val plaintext = "FrameNest compatibility fixture".toByteArray()
        for (mode in listOf("AES/CCM/NoPadding", "AES/GCM/NoPadding")) {
            val key = ByteArray(16) { it.toByte() }
            val nonce = GCMParameterSpec(128, ByteArray(12) { (it + 1).toByte() })
            val encrypted = provider.getAEADBlockCipher(mode).run {
                init(CryptMode.ENCRYPT, key, nonce)
                doFinal(plaintext, 0, plaintext.size)
            }
            fun decrypt(bytes: ByteArray) = provider.getAEADBlockCipher(mode).run {
                init(CryptMode.DECRYPT, key, nonce)
                doFinal(bytes, 0, bytes.size)
            }
            assertArrayEquals(plaintext, decrypt(encrypted))
            encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
            assertTrue(runCatching { decrypt(encrypted) }.isFailure)
        }
    }

    @Test fun httpClientAndOkioLinkOnAndroid() {
        val client = OkHttpClient()
        try {
            val request = Request.Builder().url("https://example.invalid/model").build()
            assertEquals("example.invalid", client.newCall(request).request().url.host)
            "fixture".toResponseBody().use { assertEquals("fixture", it.string()) }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
