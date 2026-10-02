package com.framenest.feature.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.framenest.core.model.PlaybackDataSource
import com.framenest.core.model.PlaybackRequest
import com.framenest.player.CredentialRedactor
import com.framenest.smb.SmbjClient
import com.framenest.smb.SmbCredentials
import java.io.File

/**
 * Scrub frames for the current file. Local files use one
 * [MediaMetadataRetriever]. SMB uses one software-decode libVLC, because the
 * retriever returns no frame for these large remote files. The session closes
 * with the player.
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
    private var cacheDigest: String? = null
    private var digestResolved: Boolean = false
    private var opened: Boolean = false
    private var broken: Boolean = false
    private var closed: Boolean = false
    private var remoteFrames: ScrubPreviewVlc? = null

    fun frameAt(bucketStartMs: Long, abandon: () -> Boolean = { false }): ScrubGrab {
        if (abandon()) return ScrubGrab.Abandoned
        val digest = synchronized(gate) {
            if (closed) return ScrubGrab.Miss
            ensureDigestLocked()
            cacheDigest
        }
        if (digest != null) {
            disk.read(digest, bucketStartMs)?.let { return ScrubGrab.Image(it) }
        }
        if (abandon()) return ScrubGrab.Abandoned
        val grabbed = when (val source = request.dataSource) {
            is PlaybackDataSource.LocalFile,
            is PlaybackDataSource.LocalRawResource,
            -> localFrame(bucketStartMs)
            is PlaybackDataSource.SeekableSmb -> remoteFrame(
                host = source.host,
                port = source.port,
                username = source.username,
                password = source.password.copyOf(),
                domain = source.domain,
                share = source.share,
                path = source.path,
                timeMs = bucketStartMs,
                abandon = abandon,
            )
            is PlaybackDataSource.DirectSmbUrl -> remoteFrame(
                host = source.host,
                port = source.port ?: com.framenest.smb.SmbCredentials.DEFAULT_PORT,
                username = source.username,
                password = source.password.toCharArray(),
                domain = source.domain.orEmpty(),
                share = source.share,
                path = source.path,
                timeMs = bucketStartMs,
                abandon = abandon,
            )
        }
        val writeDigest = synchronized(gate) { cacheDigest } ?: digest
        if (grabbed is ScrubGrab.Image && writeDigest != null) {
            runCatching { disk.write(writeDigest, bucketStartMs, grabbed.bitmap) }
                .onFailure { t ->
                    Log.w(TAG, "disk write failed: ${t.javaClass.simpleName}")
                }
        }
        return grabbed
    }

    fun pause() {
        remoteFrames?.pauseOutput()
    }

    override fun close() {
        val remote = synchronized(gate) {
            closed = true
            closeLocked()
            remoteFrames.also { remoteFrames = null }
        }
        remote?.close()
    }

    private fun ensureDigestLocked() {
        if (digestResolved) return
        digestResolved = true
        when (val source = request.dataSource) {
            is PlaybackDataSource.LocalFile -> {
                val file = File(source.path)
                if (file.isFile) {
                    cacheDigest = ScrubPreviewPlan.cacheDigest(
                        serverId = request.identity.serverId,
                        share = request.identity.share,
                        path = request.identity.normalizedPath(),
                        sizeBytes = file.length(),
                        modifiedTimeMs = file.lastModified().coerceAtLeast(0L),
                    )
                }
            }
            is PlaybackDataSource.LocalRawResource -> Unit
            is PlaybackDataSource.SeekableSmb -> if (!applyListedDigest()) rememberSmbDigest(
                host = source.host,
                port = source.port,
                username = source.username,
                password = source.password.copyOf(),
                domain = source.domain,
                share = source.share,
                path = source.path,
            )
            is PlaybackDataSource.DirectSmbUrl -> if (!applyListedDigest()) rememberSmbDigest(
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

    private fun localFrame(bucketStartMs: Long): ScrubGrab = synchronized(gate) {
        if (closed || broken) return ScrubGrab.Miss
        if (!opened) {
            try {
                when (val source = request.dataSource) {
                    is PlaybackDataSource.LocalFile -> openLocalFile(source.path)
                    is PlaybackDataSource.LocalRawResource -> openRaw(source.resId)
                    else -> return ScrubGrab.Miss
                }
                opened = true
            } catch (t: Throwable) {
                broken = true
                Log.w(TAG, "open failed: ${t.javaClass.simpleName}: ${CredentialRedactor.redact(t.message)}")
                closeLocked()
                return ScrubGrab.Miss
            }
        }
        val source = retriever ?: return ScrubGrab.Miss
        val frame = try {
            source.getFrameAtTime(
                bucketStartMs.coerceAtLeast(0L) * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            )
        } catch (t: Throwable) {
            Log.d(TAG, "frame failed bucket=$bucketStartMs: ${t.javaClass.simpleName}")
            null
        } ?: return ScrubGrab.Miss
        val scaled = scaleDown(frame)
        if (scaled !== frame && !frame.isRecycled) frame.recycle()
        ScrubGrab.Image(scaled)
    }

    private fun remoteFrame(
        host: String,
        port: Int,
        username: String,
        password: CharArray,
        domain: String,
        share: String,
        path: String,
        timeMs: Long,
        abandon: () -> Boolean,
    ): ScrubGrab {
        val session = synchronized(gate) {
            if (closed) return ScrubGrab.Miss
            remoteFrames ?: ScrubPreviewVlc(appContext).also { remoteFrames = it }
        }
        return session.frameAt(
            host = host,
            port = port,
            share = share,
            path = path,
            username = username,
            password = password,
            domain = domain,
            timeMs = timeMs,
            abandon = abandon,
        )
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

    private fun applyListedDigest(): Boolean {
        val digest = ScrubPreviewPlan.digestFromKnownContent(
            serverId = request.identity.serverId,
            share = request.identity.share,
            path = request.identity.normalizedPath(),
            sizeBytes = request.contentSizeBytes,
            modifiedTimeMs = request.contentModifiedTimeMs,
        ) ?: return false
        cacheDigest = digest
        return true
    }

    private fun rememberSmbDigest(
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
            val meta = nextClient.metadata(share, path)
            cacheDigest = ScrubPreviewPlan.cacheDigest(
                serverId = request.identity.serverId,
                share = request.identity.share,
                path = request.identity.normalizedPath(),
                sizeBytes = meta.sizeBytes,
                modifiedTimeMs = meta.lastModifiedEpochMs,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "metadata skipped: ${t.javaClass.simpleName}")
        } finally {
            credentials.clearPassword()
            runCatching { nextClient.close() }
        }
    }

    private fun closeLocked() {
        runCatching { retriever?.release() }
        retriever = null
        runCatching { afd?.close() }
        afd = null
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
