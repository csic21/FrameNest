package com.framenest.feature.listen_translate

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

/** Test-only bounded input contract. No production strategy or model behavior. */
internal data class CaptionDiagnosisUnit(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val evidence: JSONObject,
)

internal class CaptionDiagnosisFixture(val json: JSONObject) {
    val durationMs: Long = exactLong(json, "duration_ms", 1L, 120_000L)
    val originalStartMs: Long = exactLong(json, "original_media_start_ms", 0L, 86_400_000L)
    val units: List<CaptionDiagnosisUnit>

    init {
        require(exactLong(json, "schema_version", 1L, 1L) == 1L)
        require(json.getString("source_language") == "ja" && json.getString("target_language") == "zh")
        require(json.getString("license") in setOf("CC-BY-SA-4.0", "CC-BY-4.0"))
        for (key in listOf("source_url", "media_url", "license_url")) publicUrl(json.getString(key))
        require(json.getString("author").isNotBlank())
        val pcm = json.getJSONObject("pcm")
        require(pcm.getString("encoding") == "s16le")
        require(exactLong(pcm, "sample_rate_hz", 16_000L, 16_000L) == 16_000L)
        require(exactLong(pcm, "channels", 1L, 1L) == 1L)
        require(exactLong(pcm, "samples", 1L, 1_920_000L) == durationMs * 16L)
        require(exactLong(pcm, "bytes", 2L, 3_840_000L) == durationMs * 32L)
        for (key in listOf("media", "wav", "pcm", "reference")) validateFile(json.getJSONObject(key))
        val reference = json.getJSONObject("reference")
        require(reference.getString("kind") == "human_corrected_published_timed_text")
        require(reference.get("verbatim_gold") == false)
        publicUrl(reference.getString("source_url")); publicUrl(reference.getString("revision_url"))
        require(reference.getString("provenance_note").isNotBlank())
        val rows = json.getJSONArray("units")
        require(rows.length() in 1..64)
        var previousEnd = 0L
        val ids = mutableSetOf<String>()
        units = (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val id = row.getString("id")
            require(id.isNotBlank() && id.length <= 100 && ids.add(id))
            val start = exactLong(row, "start_ms", previousEnd, durationMs - 1L)
            val end = exactLong(row, "end_ms", start + 1L, minOf(durationMs, start + 30_000L))
            require(exactLong(row, "original_start_ms", 0L, Long.MAX_VALUE) == originalStartMs + start)
            require(exactLong(row, "original_end_ms", 0L, Long.MAX_VALUE) == originalStartMs + end)
            val text = row.getString("text")
            require(text.isNotBlank() && text.length <= 4096)
            require(row.getString("unit_kind") == "assistant_manually_grouped_published_caption_span")
            val cueIds = row.getJSONArray("cue_ids")
            require(cueIds.length() in 1..64)
            for (j in 0 until cueIds.length()) require(cueIds.get(j) is Int && cueIds.getInt(j) > 0)
            previousEnd = end
            CaptionDiagnosisUnit(id, start, end, text, row)
        }
        require(units.sumOf { it.text.length } <= 16_384)
        val models = json.getJSONArray("models")
        require(models.length() == 2)
        val names = mutableSetOf<String>()
        for (i in 0 until models.length()) {
            val spec = models.getJSONObject(i)
            validateFile(spec)
            val name = spec.getString("file")
            require(names.add(name))
            val expected = MODEL_FILES.getValue(name)
            require(spec.getLong("bytes") == expected.first && spec.getString("sha256") == expected.second)
        }
    }

    /** Clip-local grid, exactly the default 3-second product window helper. */
    fun windows(): List<Pair<Long, Long>> = generateSequence(0L) { it + ListenTranslateWindows.DEFAULT_WINDOW_MS }
        .takeWhile { it < durationMs }
        .map { ListenTranslateWindows.windowContaining(it, durationMs = durationMs) }.toList()

    fun unitsOverlapping(startMs: Long, endMs: Long): List<String> =
        units.filter { it.startMs < endMs && it.endMs > startMs }.map { it.id }

    fun decodePcm(bytes: ByteArray): ShortArray {
        require(bytes.size.toLong() == durationMs * 32L)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(bytes.size / 2).also { buffer.get(it) }
    }

    companion object {
        val MODEL_FILES = mapOf(
            "model.int8.onnx" to (239_233_841L to "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
            "tokens.txt" to (315_894L to "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"),
        )
        fun exactLong(json: JSONObject, key: String, minimum: Long, maximum: Long): Long {
            val value = json.get(key)
            require(value is Int || value is Long) { "Expected exact integer: $key" }
            return (value as Number).toLong().also { require(it in minimum..maximum) }
        }
        fun validateFile(spec: JSONObject) {
            val name = spec.getString("file")
            require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")) && !name.contains(".."))
            exactLong(spec, "bytes", 1L, 512L * 1024L * 1024L)
            require(spec.getString("sha256").matches(Regex("[0-9a-f]{64}")))
        }
        private fun publicUrl(value: String) {
            val uri = java.net.URI(value)
            require(uri.scheme == "https" && uri.userInfo == null && uri.host in setOf(
                "commons.wikimedia.org", "upload.wikimedia.org", "creativecommons.org",
            ))
        }
    }
}
