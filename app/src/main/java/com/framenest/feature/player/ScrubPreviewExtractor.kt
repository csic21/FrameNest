package com.framenest.feature.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.HandlerThread
import android.util.Log
import com.framenest.core.model.PlaybackDataSource
import com.framenest.core.model.PlaybackRequest
import com.framenest.player.CredentialRedactor
import com.framenest.player.SmbSeekableMedia
import com.framenest.smb.SmbjClient
import com.framenest.smb.SmbCredentials
import java.io.File

/**
 * One MediaMetadataRetriever for the current file. List covers stay on their
 * own workers; this session only serves the scrub strip and closes with the
 * player.
 */
internal class ScrubPreviewExtractor(
    private val appContext: Context,
    private val request: PlaybackRequest,
    private val disk: ScrubPreviewDisk = ScrubPreviewDisk(
        File(appContext.cacheDir, ScrubPreviewPlan.CACHE_DIR),
    ),
) : AutoCloseable {
    private val gate = Any()
    private var retriever: MediaMetadataRetriever? = null
    private var afd: android.content.res.AssetFileDescriptor? = null
    private var client: SmbjClient? = null
    private var proxyThread: HandlerThread? = null
    private var cacheDigest: String? = null
    private var opened: Boolean = false
    private var broken: Boolean = false
    private var closed: Boolean = false

    fun frameAt(bucketStartMs: Long): Bitmap? = synchronized(gate) {
        if (closed || broken) return null
        if (!opened) {
            try {
                open()
                opened = true
            } catch (t: Throwable) {
                broken = true
                Log.w(TAG, "open failed: ${t.javaClass.simpleName}: ${CredentialRedactor.redact(t.message)}")
                closeLocked()
                return null
            }
        }
        val digest = cacheDigest
        if (digest != null) {
            disk.read(digest, bucketStartMs)?.let { return it }
        }
        val source = retriever ?: return null
        val frame = try {
            source.getFrameAtTime(
                bucketStartMs.coerceAtLeast(0L) * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            )
        } catch (t: Throwable) {
            Log.d(TAG, "frame failed bucket=$bucketStartMs: ${t.javaClass.simpleName}")
            null
        } ?: return null
        val scaled = scaleDown(frame)
        if (scaled !== frame && !frame.isRecycled) frame.recycle()
        if (digest != null) {
            runCatching { disk.write(digest, bucketStartMs, scaled) }
                .onFailure { t ->
                    Log.w(TAG, "disk write failed: ${t.javaClass.simpleName}")
                }
        }
        scaled
    }

    override fun close() = synchronized(gate) {
        closed = true
        closeLocked()
    }

    private fun open() {
        when (val source = request.dataSource) {
            is PlaybackDataSource.LocalFile -> openLocalFile(source.path)
            is PlaybackDataSource.LocalRawResource -> openRaw(source.resId)
            is PlaybackDataSource.SeekableSmb -> openSmb(
                host = source.host,
                port = source.port,
                username = source.username,
                password = source.password.copyOf(),
                domain = source.domain,
                share = source.share,
                path = source.path,
            )
            is PlaybackDataSource.DirectSmbUrl -> openSmb(
                host = source.host,
                port = source.port ?: SmbCredentials.DEFAULT_PORT,
                username = source.username,
                password = source.password.toCharArray(),
                domain = source.domain.orEmpty(),
                share = source.share,
                path = source.path,
            )
        }
    }

    private fun openLocalFile(path: String) {
        val file = File(path)
        if (file.isFile) {
            cacheDigest = ScrubPreviewPlan.cacheDigest(
                serverId = request.identity.serverId,
                share = request.identity.share,
                path = request.identity.normalizedPath(),
                sizeBytes = file.length(),
                modifiedTimeMs = file.lastModified().coerceAtLeast(0L),
            )
        }
        val next = MediaMetadataRetriever()
        next.setDataSource(file.absolutePath)
        retriever = next
    }

    private fun openRaw(resId: Int) {
        val descriptor = appContext.resources.openRawResourceFd(resId)
        afd = descriptor
        cacheDigest = ScrubPreviewPlan.cacheDigest(
            serverId = request.identity.serverId,
            share = request.identity.share,
            path = request.identity.normalizedPath(),
            sizeBytes = descriptor.length.coerceAtLeast(0L),
            modifiedTimeMs = 0L,
        )
        val next = MediaMetadataRetriever()
        next.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
        retriever = next
    }

    private fun openSmb(
        host: String,
        port: Int,
        username: String,
        password: CharArray,
        domain: String,
        share: String,
        path: String,
    ) {
        val nextClient = SmbjClient()
        val credentials = SmbCredentials(
            host = host,
            port = port,
            username = username,
            password = password,
            domain = domain,
        )
        try {
            nextClient.connect(credentials)
        } catch (t: Throwable) {
            runCatching { nextClient.close() }
            throw t
        } finally {
            credentials.clearPassword()
        }
        client = nextClient
        runCatching { nextClient.metadata(share, path) }
            .onSuccess { meta ->
                cacheDigest = ScrubPreviewPlan.cacheDigest(
                    serverId = request.identity.serverId,
                    share = request.identity.share,
                    path = request.identity.normalizedPath(),
                    sizeBytes = meta.sizeBytes,
                    modifiedTimeMs = meta.lastModifiedEpochMs,
                )
            }
            .onFailure { t ->
                Log.w(TAG, "metadata skipped: ${t.javaClass.simpleName}")
            }
        val randomAccess = nextClient.openRandomAccess(share, path)
        val thread = HandlerThread("scrub-preview-pfd").also { it.start() }
        proxyThread = thread
        val openedMedia = SmbSeekableMedia.open(
            context = appContext,
            randomAccess = randomAccess,
            debugLabel = "scrub-preview",
            ioThread = thread,
        )
        val descriptor = openedMedia.assetFileDescriptor
        afd = descriptor
        val next = MediaMetadataRetriever()
        try {
            next.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
        } catch (t: Throwable) {
            runCatching { next.release() }
            throw t
        }
        retriever = next
    }

    private fun closeLocked() {
        runCatching { retriever?.release() }
        retriever = null
        runCatching { afd?.close() }
        afd = null
        val thread = proxyThread
        proxyThread = null
        thread?.quitSafely()
        val smb = client
        client = null
        runCatching { smb?.close() }
        opened = false
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val maxEdge = maxOf(source.width, source.height)
        if (maxEdge <= MAX_EDGE_PX) return source
        val scale = MAX_EDGE_PX.toFloat() / maxEdge.toFloat()
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    companion object {
        private const val TAG = "FrameNestScrubPreview"
        const val MAX_EDGE_PX: Int = 240
    }
}
