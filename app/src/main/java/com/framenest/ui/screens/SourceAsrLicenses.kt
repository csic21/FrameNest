package com.framenest.ui.screens

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class SourceAsrNotice(
    val assetPath: String,
    val title: String,
    val byteLimit: Int,
    val sha256: String,
)

/** Fixed inventory from the reviewed AAR's license-bundle-manifest.json, not asset discovery. */
internal object SourceAsrLicenses {
    const val PAGE_CHARS = 6_000
    // Actual largest reviewed notice: android-ndk/ndk-runtime-NOTICE.txt.
    const val MAX_NOTICE_BYTES = 1_941_704
    val supplemental = SourceAsrNotice(
        "source_asr_notices/SUPPLEMENTAL-NOTICE.txt",
        "补充声明及来源说明",
        2_209,
        "e2fa4a31a1b49181a284e278704f6d5ae40906a9f5fbd0ce625d8a909718460d",
    )
    val aarNotices: List<SourceAsrNotice> = listOf(
        SourceAsrNotice(
            "third_party_licenses/sherpa-onnx/LICENSE",
            "sherpa-onnx/LICENSE",
            11_358,
            "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
        ),
        SourceAsrNotice(
            "third_party_licenses/kaldi-native-fbank/LICENSE",
            "kaldi-native-fbank/LICENSE",
            11_358,
            "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
        ),
        SourceAsrNotice(
            "third_party_licenses/kaldi-decoder/LICENSE",
            "kaldi-decoder/LICENSE",
            11_358,
            "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
        ),
        SourceAsrNotice(
            "third_party_licenses/kaldifst/LICENSE",
            "kaldifst/LICENSE",
            11_728,
            "a682d6efd1ee5dee08a8e405c233c2c198ea70ae0718129daa83ab58cfe31c5d",
        ),
        SourceAsrNotice(
            "third_party_licenses/simple-sentencepiece/LICENSE",
            "simple-sentencepiece/LICENSE",
            11_357,
            "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4",
        ),
        SourceAsrNotice(
            "third_party_licenses/openfst/COPYING",
            "openfst/COPYING",
            559,
            "4300529197035fd3452350718a0b8cee984e9412c9932d7f35fcde849fc97a4b",
        ),
        SourceAsrNotice(
            "third_party_licenses/kissfft/COPYING",
            "kissfft/COPYING",
            246,
            "a2840585f8411be8e6826a31ef15ae65c950bd74a2437a73b013398a934ad0c6",
        ),
        SourceAsrNotice(
            "third_party_licenses/kissfft/LICENSES/BSD-3-Clause",
            "kissfft/LICENSES/BSD-3-Clause",
            1_830,
            "4d6bcab46f27d6d0c878e8305d4bdcfe040d52bf6656b9771f04c9fb9902c0ed",
        ),
        SourceAsrNotice(
            "third_party_licenses/kissfft/LICENSES/Unlicense",
            "kissfft/LICENSES/Unlicense",
            1_521,
            "c543dab3c100d8741a83b02f397746b1e10fc0046f30b942f308191a68e99407",
        ),
        SourceAsrNotice(
            "third_party_licenses/eigen/LICENSE",
            "eigen/LICENSE",
            16_725,
            "1f256ecad192880510e84ad60474eab7589218784b9a50bc7ceee34c2b91f1d5",
        ),
        SourceAsrNotice(
            "third_party_licenses/eigen/COPYING.MPL2",
            "eigen/COPYING.MPL2",
            16_727,
            "66a3107d5ad6a058aab753eaac2047ccb2ed0e39465dd0fe5844da3e300d5172",
        ),
        SourceAsrNotice(
            "third_party_licenses/eigen/COPYING.README",
            "eigen/COPYING.README",
            264,
            "db640ff2bd90c6abd6a4d3fbb351e0ee4d555417cf840492054d1cbb2ea85644",
        ),
        SourceAsrNotice(
            "third_party_licenses/eigen/COPYING.BSD",
            "eigen/COPYING.BSD",
            1_517,
            "51928dce36213c5333ba3172e847d735d4c6e9b7ff2722a326c49067155b82eb",
        ),
        SourceAsrNotice(
            "third_party_licenses/eigen/COPYING.APACHE",
            "eigen/COPYING.APACHE",
            11_362,
            "03379001a7b12a2ec997a25554247d985270b353c10d5bafee9ac8d6519820b7",
        ),
        SourceAsrNotice(
            "third_party_licenses/eigen/COPYING.MINPACK",
            "eigen/COPYING.MINPACK",
            2_193,
            "c87b7f8ee88f6195e91743820c00354833583aef091b72e2d4a49c8e28e798a0",
        ),
        SourceAsrNotice(
            "third_party_licenses/simple-sentencepiece/DARTS-BSD-2-Clause.txt",
            "simple-sentencepiece/DARTS-BSD-2-Clause.txt",
            1_315,
            "30704ed1913ec07b4090a1eaeafd4aa37af4efe83eebbc3b5a85cedb238e0666",
        ),
        SourceAsrNotice(
            "third_party_licenses/json/LICENSE.MIT",
            "json/LICENSE.MIT",
            1_076,
            "46a65cffd1ea955132d95a8dd921640714a8d6b537d2e4e482d31145ae95b603",
        ),
        SourceAsrNotice(
            "third_party_licenses/json/Hedley-header-attributions.txt",
            "json/Hedley-header-attributions.txt",
            428,
            "74e8901fb61d928cee338c5e49df2cd7bab47da4d0c6b5dff105da8cd77a0d02",
        ),
        SourceAsrNotice(
            "third_party_licenses/json/Grisu2-header-attributions.txt",
            "json/Grisu2-header-attributions.txt",
            416,
            "38a9f09f91f221a9f1f84ce69be301f62ac9f8f323e0c13889fdd60b27d9dc55",
        ),
        SourceAsrNotice(
            "third_party_licenses/json/UTF8-header-attributions.txt",
            "json/UTF8-header-attributions.txt",
            450,
            "5be3ab2f312152cc864119f46c87cc8cebf6b8c95091679be1d19b31e2a44a68",
        ),
        SourceAsrNotice(
            "third_party_licenses/json/Abseil-CXX11-fallback-attributions.txt",
            "json/Abseil-CXX11-fallback-attributions.txt",
            1_517,
            "7aca6e84cbde6d057dcd6d15dd2071d7823c4eea7c023a10ca9f25375f6c6434",
        ),
        SourceAsrNotice(
            "third_party_licenses/onnxruntime/LICENSE",
            "onnxruntime/LICENSE",
            1_073,
            "2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c",
        ),
        SourceAsrNotice(
            "third_party_licenses/onnxruntime/ThirdPartyNotices.txt",
            "onnxruntime/ThirdPartyNotices.txt",
            325_054,
            "0e07b95f3a8d6230037707c5c4a2b554d12c4cb67369669ac255635528ffcee2",
        ),
        SourceAsrNotice(
            "third_party_licenses/android-ndk/ndk-runtime-NOTICE.txt",
            "android-ndk/ndk-runtime-NOTICE.txt",
            1_941_704,
            "e15e0bcaee943231836afe3a961b8a1d2ade12cb9e244c29d7d13a84cf71750f",
        ),
        SourceAsrNotice(
            "third_party_licenses/android-ndk/original/NOTICE",
            "android-ndk/original/NOTICE",
            516_438,
            "faf0dc52cf6ce9ab09a04b0c074a378dd2565ee1ed9bc1179d83793bcdba012e",
        ),
        SourceAsrNotice(
            "third_party_licenses/android-ndk/original/NOTICE.toolchain",
            "android-ndk/original/NOTICE.toolchain",
            799_639,
            "789714a9076571c580cf531bdcebec776a4d889c12c3c1108adafc6e4338710a",
        ),
        SourceAsrNotice(
            "third_party_licenses/android-ndk/original/toolchains/llvm/prebuilt/linux-x86_64/NOTICE",
            "android-ndk/original/toolchains/llvm/prebuilt/linux-x86_64/NOTICE",
            130_424,
            "f96f763beb66a7ba7a667647fc64c0226ace875e590c831fdd9579ec1c1d91e1",
        ),
        SourceAsrNotice(
            "third_party_licenses/android-ndk/original/toolchains/llvm/prebuilt/linux-x86_64/sysroot/NOTICE",
            "android-ndk/original/toolchains/llvm/prebuilt/linux-x86_64/sysroot/NOTICE",
            494_921,
            "2905ac5c59628352af990c748a8f4c3675a04f36eca7914612e3068661d2c6a6",
        ),
        SourceAsrNotice(
            "third_party_licenses/NOTICE.txt",
            "NOTICE.txt",
            2_576,
            "e56277e18ea4ae772b85ae121f019b30448940e01e42e09e8c6b2e844a22b539",
        ),
    )
    private val allowedNotices = (listOf(supplemental) + aarNotices).associateBy { it.assetPath }

    fun noticeForPath(path: String): SourceAsrNotice {
        // Reject aliases rather than normalizing untrusted paths into the allowlist.
        require(path.isNotEmpty() && path.length <= 180)
        require(path.all { it.isLetterOrDigit() && it.code < 128 || it in "/._-" })
        require(path.split('/').none { it.isEmpty() || it == "." || it == ".." })
        return requireNotNull(allowedNotices[path]) { "Unlisted source-ASR notice" }
    }
}

/** Reads only one allowlisted asset on IO. Cancellation closes the stream and stops paging. */
internal suspend fun readSourceAsrNotice(
    path: String,
    openAsset: (String) -> InputStream,
): List<String> = withContext(Dispatchers.IO) {
    val notice = SourceAsrLicenses.noticeForPath(path)
    check(notice.byteLimit in 1..SourceAsrLicenses.MAX_NOTICE_BYTES)
    val context = currentCoroutineContext()
    context.ensureActive()
    val bytes = openAsset(notice.assetPath).use { input ->
        val output = ByteArrayOutputStream(minOf(notice.byteLimit, 8_192))
        val buffer = ByteArray(8_192)
        while (true) {
            context.ensureActive()
            // Read at most one byte beyond the limit, without trusting available() or metadata.
            val count = input.read(buffer, 0, minOf(buffer.size, notice.byteLimit - output.size() + 1))
            if (count < 0) break
            if (count == 0) throw IOException("Notice stream made no progress")
            if (count > notice.byteLimit - output.size()) throw IOException("Notice exceeds byte limit")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    context.ensureActive()
    if (bytes.isEmpty()) throw IOException("Notice is empty")
    if (bytes.size != notice.byteLimit) throw IOException("Notice size mismatch")
    val actualHash = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    if (actualHash != notice.sha256) throw IOException("Notice checksum mismatch")
    val text = decodeSourceAsrNotice(bytes)
    paginateSourceAsrNotice(text) { context.ensureActive() }
}

/** A replacement character would hide corrupt license bytes, so reject malformed UTF-8. */
internal fun decodeSourceAsrNotice(bytes: ByteArray): String =
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

/** Exact reconstruction, bounded rendering, and no split UTF-16 surrogate pairs or CRLFs. */
internal fun paginateSourceAsrNotice(
    text: String,
    pageChars: Int = SourceAsrLicenses.PAGE_CHARS,
    checkCancellation: () -> Unit = {},
): List<String> {
    require(pageChars >= 2)
    if (text.isEmpty()) return listOf("")
    val pages = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        checkCancellation()
        var end = start + minOf(pageChars, text.length - start)
        if (end < text.length &&
            (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate() ||
                text[end - 1] == '\r' && text[end] == '\n')
        ) {
            end--
        }
        pages += text.substring(start, end)
        start = end
    }
    return pages
}
