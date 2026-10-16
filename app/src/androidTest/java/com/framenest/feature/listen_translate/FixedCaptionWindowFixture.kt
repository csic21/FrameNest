package com.framenest.feature.listen_translate

import org.json.JSONObject

/** Test-only fixed comparison. No user-configurable sweep or production changes. */
internal class FixedCaptionWindowFixture(val json: JSONObject) {
    val emoji: JSONObject = json.getJSONObject("emoji")
    val windowSizes = listOf(3_000L, 6_000L)
    init {
        exact(json, "schema_version", 1L)
        require(json.getString("id") == "fn76-fixed-3s-6s-mcdc-emoji")
        val sizes = json.getJSONArray("window_ms")
        require(sizes.length() == 2)
        for (i in windowSizes.indices) {
            val value = sizes.get(i)
            require((value is Int || value is Long) && (value as Number).toLong() == windowSizes[i])
        }
        exact(json, "context_padding_ms", 750L)
        exact(json, "timeout_ms", 1_200_000L)
        require(emoji.getString("id") == "emoji")
        exact(emoji, "duration_ms", 113_960L)
        exact(emoji, "original_media_start_ms", 0L)
        require(emoji.getString("source_url") == "https://commons.wikimedia.org/wiki/File:Emoji.webm")
        require(emoji.getString("media_url") == "https://upload.wikimedia.org/wikipedia/commons/a/a4/Emoji.webm")
        require(emoji.getString("author") == "Simpleshow Japan")
        require(emoji.getString("license") == "CC-BY-SA-4.0")
        require(emoji.getString("license_url") == "https://creativecommons.org/licenses/by-sa/4.0/")
        require(emoji.getString("attribution").isNotBlank())
        require(emoji.getString("reference_kind") == "none_independent_reference_unavailable")
        require(emoji.get("reference_is_verbatim_gold") == false)
        val media = emoji.getJSONObject("media")
        val pcm = emoji.getJSONObject("pcm")
        CaptionDiagnosisFixture.validateFile(media); CaptionDiagnosisFixture.validateFile(pcm)
        require(media.getString("file") == "emoji.webm")
        exact(media, "bytes", 9_487_128L)
        require(media.getString("sha256") == "ad3af093836cad823ef1793ff4d505139eed92dd7e7402de4c015a2ed50d5956")
        require(pcm.getString("file") == "emoji.s16le")
        exact(pcm, "bytes", 3_646_720L); exact(pcm, "samples", 1_823_360L)
        exact(pcm, "sample_rate_hz", 16_000L); exact(pcm, "channels", 1L)
        require(pcm.getString("encoding") == "s16le")
        require(pcm.getString("sha256") == "e3e4e1de9395948bc3e03c0f88a93cb55bbbb0768280344bd7f4bd3f164700a6")
    }
    fun windows(durationMs: Long, windowMs: Long): List<Pair<Long, Long>> {
        require(durationMs in 1L..120_000L && windowMs in windowSizes)
        return generateSequence(0L) { it + windowMs }.takeWhile { it < durationMs }
            .map { it to minOf(it + windowMs, durationMs) }.toList()
    }
    private fun exact(value: JSONObject, key: String, expected: Long) =
        CaptionDiagnosisFixture.exactLong(value, key, expected, expected)
}
