package com.framenest.feature.listen_translate.asr

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Invalid remote metadata/content cannot be retained as a resumable prefix. */
internal class InvalidModelDownload(message: String) : IOException(message)

internal fun verifiedModelFile(file: File, expectedBytes: Long, hash: String): Boolean =
    file.isFile && file.length() == expectedBytes && sha256(file).equals(hash, ignoreCase = true)

internal fun modelResponsePlan(offset: Long, expected: Long, code: Int, length: Long, range: String?): ResumeDownloadPlan {
    if (expected <= 0L || offset !in 0 until expected || code !in listOf(200, 206)) {
        throw InvalidModelDownload("模型下载响应无效")
    }
    val plan = resumeDownloadPlan(offset, code)
    if (code == 206) {
        val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(range.orEmpty())
            ?: throw InvalidModelDownload("模型续传范围无效")
        val values = match.groupValues.drop(1).map { it.toLongOrNull() }
        if (values[0] != plan.startBytes || values[1] != expected - 1L || values[2] != expected) {
            throw InvalidModelDownload("模型续传范围与固定清单不一致")
        }
    }
    // Subtraction avoids overflow from offset + Content-Length.
    if (length >= 0L && length != expected - plan.startBytes) {
        throw InvalidModelDownload("模型大小与固定清单不一致")
    }
    return plan
}

internal suspend fun copyModelBytes(input: InputStream, out: FileOutputStream, start: Long, expected: Long, onBytes: (Long, Long) -> Unit) {
    var total = start
    val buffer = ByteArray(64 * 1024)
    onBytes(total, expected)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        if (count.toLong() > expected - total) throw InvalidModelDownload("模型下载超过固定大小上限")
        out.write(buffer, 0, count)
        total += count
        onBytes(total, expected)
    }
    out.fd.sync()
    if (total != expected) throw IOException("模型下载不完整：$total/$expected bytes")
}

/** All redirects remain on the pinned model providers' HTTPS delivery domains. */
internal fun allowedModelUrl(url: URL): Boolean {
    val host = url.host.lowercase()
    return url.protocol == "https" && url.userInfo == null && url.ref == null &&
        (url.port == -1 || url.port == 443) &&
        (host == "alphacephei.com" || host == "huggingface.co" ||
            host.endsWith(".huggingface.co") || host == "hf.co" || host.endsWith(".hf.co"))
}

internal fun openModelConnection(url: String, offset: Long): HttpURLConnection {
    var target = URL(url)
    repeat(6) { hop ->
        if (!allowedModelUrl(target)) throw InvalidModelDownload("模型下载来源不受信任")
        val connection = (target.openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            if (connection.responseCode !in listOf(301, 302, 303, 307, 308)) return connection
            val next = connection.getHeaderField("Location")
                ?: throw InvalidModelDownload("模型重定向缺少地址")
            if (hop == 5) throw InvalidModelDownload("模型重定向次数超限")
            target = URL(target, next)
        } catch (failure: Throwable) {
            connection.disconnect()
            throw failure
        }
        connection.disconnect()
    }
    throw InvalidModelDownload("模型重定向次数超限")
}

internal suspend fun downloadVerifiedModel(
    url: String,
    dest: File,
    expected: Long,
    hash: String,
    requireNetwork: () -> Unit,
    onBytes: (Long, Long) -> Unit,
    connect: (String, Long) -> HttpURLConnection = ::openModelConnection,
) {
    require(expected > 0L && hash.matches(Regex("[a-fA-F0-9]{64}")))
    dest.parentFile?.mkdirs()
    val part = File(dest.path + ".part")
    if (verifiedModelFile(dest, expected, hash)) {
        onBytes(expected, expected)
        return
    }
    if (dest.exists()) check(dest.delete()) { "无法移除损坏模型" }
    if (part.length() > expected || (part.length() == expected && !verifiedModelFile(part, expected, hash))) {
        check(part.delete()) { "无法移除损坏下载" }
    }
    if (part.length() == expected) {
        atomicModelMove(part, dest)
        onBytes(expected, expected)
        return
    }
    requireNetwork()
    val offset = part.length()
    val connection = connect(url, offset)
    try {
        val plan = modelResponsePlan(offset, expected, connection.responseCode, connection.contentLengthLong,
            connection.getHeaderField("Content-Range"))
        connection.inputStream.buffered().use { input ->
            FileOutputStream(part, plan.append).use { out ->
                copyModelBytes(input, out, plan.startBytes, expected, onBytes)
            }
        }
        if (!verifiedModelFile(part, expected, hash)) throw InvalidModelDownload("模型 SHA-256 校验失败")
        atomicModelMove(part, dest)
    } catch (invalid: InvalidModelDownload) {
        part.delete()
        throw invalid
    } finally {
        connection.disconnect()
    }
}

internal fun atomicModelMove(source: File, destination: File) {
    // Same private-storage filesystem. Never expose a partially copied ready file.
    Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

internal fun publishModelReady(dir: File, marker: String, validate: () -> Boolean) {
    val ready = File(dir, ".ready")
    if (!validate()) {
        ready.delete()
        throw InvalidModelDownload("模型安装后校验失败")
    }
    val staging = File(dir, ".ready.part")
    FileOutputStream(staging).use { out ->
        out.write(marker.toByteArray(Charsets.UTF_8))
        out.fd.sync()
    }
    atomicModelMove(staging, ready)
}
