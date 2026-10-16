package com.framenest.feature.listen_translate

import android.content.res.AssetManager
import android.os.Build
import android.os.SystemClock
import com.framenest.BuildConfig
import com.framenest.data.settings.AsrEngineChoice
import com.framenest.feature.listen_translate.asr.AsrEngine
import com.framenest.feature.listen_translate.asr.AsrModelSupport
import com.framenest.feature.listen_translate.asr.AsrRecognition
import com.framenest.feature.listen_translate.asr.SherpaModelInstaller
import com.framenest.feature.listen_translate.audio.ListenAudioSource
import com.framenest.feature.listen_translate.mt.ListenMtEngine
import com.k2fsa.sherpa.onnx.VersionInfo
import java.io.File
import java.io.InputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Existing fixed-fixture gates and production observers reused by the opt-in FN-77 test. */
internal object RollingCaptionDiagnosticSupport {
    class ObservedAsr(private val delegate: AsrEngine) : AsrEngine by delegate {
        var calls = JSONArray()
        var onUpdate: () -> Unit = {}
        var decodeStartMs = 0L
        var windowStartMs = 0L
        var windowEndMs = 0L
        override fun close() = Unit
        override suspend fun recognize(pcm16kMono: ShortArray): AsrRecognition {
            val call = JSONObject().put("input_pcm_samples", pcm16kMono.size).put("input_pcm_s16le_sha256", pcmHash(pcm16kMono))
            calls.put(call); onUpdate()
            try {
                val result = delegate.recognize(pcm16kMono)
                val selected = selectWordsForWindow(result.words, decodeStartMs, windowStartMs, windowEndMs)
                call.put("full_recognition_text", result.text).put("text_separator", result.textSeparator)
                    .put("mapped_tokens", JSONArray(result.words.map { word ->
                        JSONObject().put("text", word.text).put("start_ms_relative_to_decode", word.startMs)
                            .put("end_ms_relative_to_decode", word.endMs).put("start_ms_clip", decodeStartMs + word.startMs)
                            .put("end_ms_clip", decodeStartMs + word.endMs).put("selected_by_existing_midpoint_rule", word in selected)
                    })).put("status", "returned")
                onUpdate()
                return result
            } catch (failure: Throwable) {
                call.put("status", "failed").put("failure_class", failure.javaClass.name)
                throw failure
            }
        }
    }

    class ObservedMt(private val delegate: ListenMtEngine) : ListenMtEngine by delegate {
        var calls = JSONArray()
        var onUpdate: () -> Unit = {}
        override fun close() = Unit
        override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
            val call = JSONObject().put("input_text", text).put("source_language", sourceLang).put("target_language", targetLang)
            calls.put(call); onUpdate()
            val start = now()
            try {
                return delegate.translate(text, sourceLang, targetLang).also {
                    call.put("target_text", it).put("status", if (it.isBlank()) "empty_target" else "returned")
                }
            } catch (failure: Throwable) {
                call.put("status", "failed").put("failure_class", failure.javaClass.name)
                throw failure
            } finally { call.put("wall_ms", elapsed(start)); onUpdate() }
        }
    }

    /** Same-directory atomic replacement preserves the last complete checkpoint on process death. */
    fun writeReport(file: File, report: JSONObject) {
        val pending = File(file.parentFile, "${file.name}.pending")
        FileOutputStream(pending).use { stream ->
            stream.write(report.toString(2).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun decodePcm(bytes: ByteArray): ShortArray {
        require(bytes.size % 2 == 0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(bytes.size / 2).also { buffer.get(it) }
    }
    class PinnedPcmAudio(private val pcm: ShortArray) : ListenAudioSource {
        var lastRead: JSONObject? = null
        fun slice(startMs: Long, endMs: Long): ShortArray {
            require(startMs >= 0L && endMs > startMs)
            val start = minOf(startMs * 16L, pcm.size.toLong()).toInt()
            val end = minOf(endMs * 16L, pcm.size.toLong()).toInt()
            return pcm.copyOfRange(start, end)
        }
        override suspend fun pcmWindow(startMs: Long, endMs: Long, preferredAudioTrackOrdinal: Int?): ShortArray {
            require(preferredAudioTrackOrdinal == null)
            return slice(startMs, endMs).also {
                lastRead = JSONObject().put("requested_start_ms", startMs).put("requested_end_ms", endMs)
                    .put("actual_start_ms", minOf(startMs, pcm.size / 16L))
                    .put("actual_end_ms", minOf(endMs, pcm.size / 16L))
                    .put("samples", it.size).put("s16le_sha256", pcmHash(it))
            }
        }
    }

    class PinnedModels(private val directory: File) : AsrModelSupport {
        override fun engineChoice() = AsrEngineChoice.SHERPA
        override fun supportedSourceLanguages() = setOf("ja")
        override fun isInstalled(langTag: String) = langTag == "ja"
        override suspend fun ensureInstalled(langTag: String, allowMeteredDownloads: Boolean, onProgress: (Float) -> Unit): File =
            error("Diagnostic models must already be packaged and verified")
        override fun modelDir(langTag: String) = directory.takeIf { langTag == "ja" }
        override fun unsupportedLanguageMessage(langTag: String) = "Pinned Japanese fixture only"
        override fun incompleteModelMessage(langTag: String) = "Missing pinned diagnostic model"
        override fun downloadingMessage(langTag: String) = "No ASR download in this test"
        override fun readyMessage(sourceLang: String) = "Pinned model"
        override fun modelLabel(sourceLang: String) = SherpaModelInstaller.MODEL_VERSION
    }

    fun verifyReference(fixture: CaptionDiagnosisFixture, reference: JSONObject) {
        assertEquals("human_corrected_published_timed_text", reference.getString("reference_kind"))
        assertFalse(reference.getBoolean("is_verbatim_gold"))
        assertEquals(fixture.originalStartMs, reference.getLong("source_offset_ms"))
        assertEquals(fixture.durationMs, reference.getLong("duration_ms"))
        val units = reference.getJSONArray("units")
        assertEquals(fixture.units.size, units.length())
        for (i in fixture.units.indices) {
            val expected = fixture.units[i]
            val actual = units.getJSONObject(i)
            assertEquals(expected.id, actual.getString("id"))
            assertEquals(expected.startMs, actual.getLong("start_ms"))
            assertEquals(expected.endMs, actual.getLong("end_ms"))
            assertEquals(expected.text, actual.getString("text"))
            assertEquals(expected.evidence.getJSONArray("cue_ids").toString(), actual.getJSONArray("cue_ids").toString())
        }
    }

    fun verifiedAsset(assets: AssetManager, spec: JSONObject): ByteArray {
        CaptionDiagnosisFixture.validateFile(spec)
        require(spec.getLong("bytes") <= 4_000_000L) { "Large model assets must be streamed" }
        val bytes = assets.open("$ROOT/generated/${spec.getString("file")}").use { it.readBytes() }
        assertEquals(spec.getLong("bytes"), bytes.size.toLong())
        assertEquals(spec.getString("sha256"), sha256(bytes))
        return bytes
    }

    fun copyVerifiedAsset(assets: AssetManager, spec: JSONObject, directory: File) {
        CaptionDiagnosisFixture.validateFile(spec)
        val file = File(directory, spec.getString("file"))
        val expected = spec.getLong("bytes")
        var count = 0L
        assets.open("$ROOT/generated/${spec.getString("file")}").use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(65_536)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    count += size
                    require(count <= expected) { "Model asset exceeds pinned length" }
                    output.write(buffer, 0, size)
                }
            }
        }
        assertEquals(expected, count)
        assertEquals(spec.getString("sha256"), sha256(file))
    }

    fun verifySourceProfile(profile: JSONObject, appApk: File): JSONObject {
        assertEquals(SOURCE_AAR_SHA256, profile.getString("aar_sha256"))
        assertEquals(BuildConfig.SHERPA_RUNTIME_IDENTITY, profile.getString("runtime_identity"))
        assertEquals(VersionInfo.version.removePrefix("v"), profile.getString("upstream_runtime_version"))
        val verified = JSONArray()
        var activeMembers = 0
        ZipFile(appApk).use { apk ->
            for (group in listOf("apk_assets", "native_members")) {
                val specs = profile.getJSONArray(group)
                for (i in 0 until specs.length()) {
                    val spec = specs.getJSONObject(i)
                    val path = spec.getString("path")
                    val active = group == "native_members" && spec.getString("abi") == Build.SUPPORTED_ABIS.first()
                    val entry = apk.getEntry(path)
                    if (group == "native_members" && !active && entry == null) continue
                    requireNotNull(entry) { "Missing pinned source APK member: $path" }
                    assertEquals(spec.getLong("bytes"), entry.size)
                    val hash = apk.getInputStream(entry).use { sha256(it) }
                    assertEquals(spec.getString("sha256"), hash)
                    verified.put(JSONObject().put("path", path).put("sha256", hash).put("bytes", entry.size))
                    if (active) activeMembers++
                }
            }
        }
        assertEquals("Pinned JNI and ORT for the active ABI", 2, activeMembers)
        return JSONObject().put("profile", profile).put("verified_apk_members", verified)
    }

    fun stages(stages: ListenStageTimings?): Any = stages?.let {
        JSONObject().put("model_check_ms", it.modelCheckMs ?: JSONObject.NULL)
            .put("pcm_copy_ms", it.pcmReadMs ?: JSONObject.NULL)
            .put("silence_gate_ms", it.silenceGateMs ?: JSONObject.NULL)
            .put("asr_ms_including_observer", it.asrMs ?: JSONObject.NULL)
            .put("mt_preparation_ms_overlaps_asr", it.translationPreparationMs ?: JSONObject.NULL)
            .put("mt_preparation_exposed_wait_ms", it.translationPreparationWaitMs ?: JSONObject.NULL)
            .put("mt_ms", it.translationMs ?: JSONObject.NULL)
    } ?: JSONObject.NULL


    const val ROOT = "caption-diagnosis"
    const val SOURCE_AAR_SHA256 = "6a243d2da9a1f521a85ceeb30b51e00a660e2a1bfb05b79f6b591361af7c7503"
    fun now() = SystemClock.elapsedRealtimeNanos()
    fun elapsed(start: Long) = (now() - start) / 1_000_000.0
    fun jsonAsset(assets: AssetManager, path: String) =
        JSONObject(assets.open(path).bufferedReader().use { it.readText() })
    fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    fun sha256(file: File) = file.inputStream().use { sha256(it) }
    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65_536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().hex()
    }
    fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    fun pcmHash(pcm: ShortArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(pcm.size * 2)
        pcm.forEachIndexed { i, value -> bytes[i * 2] = value.toByte(); bytes[i * 2 + 1] = (value.toInt() shr 8).toByte() }
        return digest.digest(bytes).hex()
    }
}
