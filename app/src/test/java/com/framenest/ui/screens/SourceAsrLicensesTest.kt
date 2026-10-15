package com.framenest.ui.screens

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceAsrLicensesTest {
    @Test
    fun inventoryContainsExactlyTheReviewed29Notices() {
        val expected = setOf(
            "sherpa-onnx/LICENSE", "kaldi-native-fbank/LICENSE", "kaldi-decoder/LICENSE",
            "kaldifst/LICENSE", "simple-sentencepiece/LICENSE", "openfst/COPYING",
            "kissfft/COPYING", "kissfft/LICENSES/BSD-3-Clause", "kissfft/LICENSES/Unlicense",
            "eigen/LICENSE", "eigen/COPYING.MPL2", "eigen/COPYING.README", "eigen/COPYING.BSD",
            "eigen/COPYING.APACHE", "eigen/COPYING.MINPACK",
            "simple-sentencepiece/DARTS-BSD-2-Clause.txt", "json/LICENSE.MIT",
            "json/Hedley-header-attributions.txt", "json/Grisu2-header-attributions.txt",
            "json/UTF8-header-attributions.txt", "json/Abseil-CXX11-fallback-attributions.txt",
            "onnxruntime/LICENSE", "onnxruntime/ThirdPartyNotices.txt",
            "android-ndk/ndk-runtime-NOTICE.txt", "android-ndk/original/NOTICE",
            "android-ndk/original/NOTICE.toolchain",
            "android-ndk/original/toolchains/llvm/prebuilt/linux-x86_64/NOTICE",
            "android-ndk/original/toolchains/llvm/prebuilt/linux-x86_64/sysroot/NOTICE",
            "NOTICE.txt",
        ).map { "third_party_licenses/$it" }.toSet()
        assertEquals(29, SourceAsrLicenses.aarNotices.size)
        assertEquals(expected, SourceAsrLicenses.aarNotices.map { it.assetPath }.toSet())
        for (notice in SourceAsrLicenses.aarNotices + SourceAsrLicenses.supplemental) {
            assertEquals(notice, SourceAsrLicenses.noticeForPath(notice.assetPath))
            assertTrue(notice.byteLimit in 1..SourceAsrLicenses.MAX_NOTICE_BYTES)
            assertTrue(requireNotNull(notice.sha256).matches(Regex("[a-f0-9]{64}")))
        }
        assertEquals(1_941_704, SourceAsrLicenses.aarNotices.maxOf { it.byteLimit })
        assertEquals(2_209, SourceAsrLicenses.supplemental.byteLimit)
    }

    @Test
    fun rejectsTraversalAliasesProvenanceAndUnlistedAssetsBeforeOpening() {
        val invalid = listOf(
            "", "/third_party_licenses/NOTICE.txt", "third_party_licenses//NOTICE.txt",
            "third_party_licenses/./NOTICE.txt", "third_party_licenses/../NOTICE.txt",
            "third_party_licenses/eigen/../../NOTICE.txt", "third_party_licenses\\NOTICE.txt",
            "third_party_licenses/%2e%2e/NOTICE.txt", "third_party_licenses/NOTICE.txt\u0000",
            "third_party_licenses/NOTICE.txt?x=1", "third_party_licenses/notice.txt",
            "third_party_licenses/license-bundle-manifest.json",
            "third_party_licenses/source-build-manifest.json", "other/NOTICE.txt",
            "https://example.com/NOTICE.txt", "a".repeat(181),
        )
        for (path in invalid) {
            assertThrows("Path: $path", IllegalArgumentException::class.java) {
                runBlocking { readSourceAsrNotice(path) { error("Must not open an invalid path") } }
            }
        }
    }

    @Test
    fun actualSupplementHasPinnedBytesAndReadableSourceUrl() = runBlocking {
        val notice = SourceAsrLicenses.supplemental
        val file = listOf(File("src/sourceAsr/assets"), File("app/src/sourceAsr/assets"))
            .map { File(it, notice.assetPath) }.first { it.isFile }
        val pages = readSourceAsrNotice(notice.assetPath) { file.inputStream() }
        assertEquals(file.readText(Charsets.UTF_8), pages.joinToString(""))
        assertTrue(pages.joinToString("").contains(
            "https://gitlab.com/libeigen/eigen/-/archive/5.0.1/eigen-5.0.1.tar.gz",
        ))
        assertTrue(pages.all { it.length <= SourceAsrLicenses.PAGE_CHARS })
    }

    @Test
    fun sizeLimitStopsAfterAtMostOneExcessByteAndClosesStream() {
        val notice = SourceAsrLicenses.aarNotices.first { it.title == "kissfft/COPYING" }
        var bytesRead = 0
        var closed = false
        val endless = object : InputStream() {
            override fun read(): Int = error("Use bounded bulk read")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                bytesRead += length
                return length
            }
            override fun close() { closed = true }
        }
        assertThrows(IOException::class.java) {
            runBlocking { readSourceAsrNotice(notice.assetPath) { endless } }
        }
        assertEquals(notice.byteLimit + 1, bytesRead)
        assertTrue(closed)
    }

    @Test
    fun missingEmptyTruncatedAndSameSizeCorruptNoticesFailHonestly() {
        val notice = SourceAsrLicenses.supplemental
        assertThrows(FileNotFoundException::class.java) {
            runBlocking { readSourceAsrNotice(notice.assetPath) { throw FileNotFoundException() } }
        }
        for (size in listOf(0, notice.byteLimit - 1, notice.byteLimit)) {
            var closed = false
            assertThrows(IOException::class.java) {
                runBlocking {
                    readSourceAsrNotice(notice.assetPath) {
                        object : ByteArrayInputStream(ByteArray(size) { 'a'.code.toByte() }) {
                            override fun close() { closed = true; super.close() }
                        }
                    }
                }
            }
            assertTrue(closed)
        }
    }

    @Test
    fun cancellationDuringReadClosesStreamAndDoesNotReadAgain() {
        var closed = false
        var reads = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                val context = currentCoroutineContext()
                readSourceAsrNotice(SourceAsrLicenses.supplemental.assetPath) {
                    object : InputStream() {
                        override fun read(): Int = error("Use bounded bulk read")
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            reads++
                            context.cancel()
                            buffer[offset] = 'a'.code.toByte()
                            return 1
                        }
                        override fun close() { closed = true }
                    }
                }
            }
        }
        assertTrue(closed)
        assertEquals(1, reads)
    }

    @Test
    fun utf8IsDecodedExactlyAndMalformedSequencesAreRejected() {
        val text = "中\uD83D\uDE80e\u0301\r\n许可证"
        assertEquals(text, decodeSourceAsrNotice(text.toByteArray(Charsets.UTF_8)))
        assertThrows(CharacterCodingException::class.java) {
            decodeSourceAsrNotice(byteArrayOf(0xC3.toByte(), 0x28))
        }
        assertThrows(CharacterCodingException::class.java) {
            decodeSourceAsrNotice(byteArrayOf(0xF0.toByte(), 0x9F.toByte()))
        }
    }

    @Test
    fun everyPageIsBoundedAndReconstructsWithoutSplittingSurrogatesOrCrLf() {
        val samples = listOf(
            "", "a", "a".repeat(12_000),
            "中\uD83D\uDE80e\u0301\r\n许可证".repeat(900),
            "12345\uD83D\uDE80\r\n6", "\uD83D\uDE80".repeat(100),
        )
        for (text in samples) {
            for (limit in listOf(2, 3, 6, SourceAsrLicenses.PAGE_CHARS)) {
                val pages = paginateSourceAsrNotice(text, limit)
                assertEquals(text, pages.joinToString(""))
                assertTrue(pages.isNotEmpty())
                assertTrue(pages.all { it.length <= limit })
                pages.forEach {
                    assertFalse(it.firstOrNull()?.isLowSurrogate() == true)
                    assertFalse(it.lastOrNull()?.isHighSurrogate() == true)
                }
                pages.zipWithNext().forEach { (first, second) ->
                    assertFalse(first.endsWith('\r') && second.startsWith('\n'))
                }
            }
        }
    }

    @Test
    fun largestNoticeUsesBoundedPagesRatherThanOneHugeText() {
        val text = "a".repeat(SourceAsrLicenses.MAX_NOTICE_BYTES)
        val pages = paginateSourceAsrNotice(text)
        assertEquals(324, pages.size)
        assertEquals(text, pages.joinToString(""))
        assertTrue(pages.all { it.length <= SourceAsrLicenses.PAGE_CHARS })
    }

    @Test
    fun paginationChecksCancellationBetweenPages() {
        var checks = 0
        assertThrows(CancellationException::class.java) {
            paginateSourceAsrNotice("a".repeat(100), 10) {
                checks++
                if (checks == 3) throw CancellationException()
            }
        }
        assertEquals(3, checks)
        assertThrows(IllegalArgumentException::class.java) { paginateSourceAsrNotice("a", 1) }
    }
}
