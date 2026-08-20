package com.framenest.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SMBJ-backed [SmbClient].
 *
 * Threading: connection state is guarded by [lock], but connect / list / metadata /
 * tree-connect I/O run **outside** that lock. [close] and [disconnect] can therefore
 * tear down the transport while a listing is blocked and unblock the waiter.
 * Callers should still keep heavy work off the main thread.
 */
class SmbjClient(
    private val config: SmbConfig = defaultConfig(),
) : SmbClient {

    private val client = SMBClient(config)
    private val lock = Any()
    private var connection: Connection? = null
    private var session: Session? = null
    private var connectedHost: String? = null
    private val shares = LinkedHashMap<String, DiskShare>()
    private val closed = AtomicBoolean(false)

    override val isConnected: Boolean
        get() = synchronized(lock) {
            !closed.get() && connection?.isConnected == true && session != null
        }

    @Throws(SmbException::class)
    override fun connect(credentials: SmbCredentials) {
        ensureOpen()
        disconnect()
        val host = credentials.host.trim()
        if (host.isEmpty()) {
            throw SmbException(SmbError.Network("Host is empty"))
        }
        SmbLog.i("Connecting ${credentials.safeSummary()}")
        try {
            val conn = if (credentials.port == SmbCredentials.DEFAULT_PORT) {
                client.connect(host)
            } else {
                client.connect(host, credentials.port)
            }
            val auth = AuthenticationContext(
                credentials.username,
                credentials.password,
                credentials.domain,
            )
            val sess = conn.authenticate(auth)
            val stale = try {
                synchronized(lock) {
                    ensureOpenLocked()
                    val previous = snapshotAndClearSession()
                    connection = conn
                    session = sess
                    connectedHost = host
                    previous
                }
            } catch (t: Throwable) {
                runCatching { sess.close() }
                runCatching { conn.close() }
                throw t
            }
            closeSessionSnapshot(stale, closeClient = false)
            SmbLog.i("Authenticated to host=$host port=${credentials.port}")
        } catch (t: Throwable) {
            if (t is SmbException) throw t
            val error = SmbErrorMapper.map(t)
            SmbLog.e("Connect failed type=${error::class.simpleName} msg=${error.message}", t)
            throw SmbException(error)
        }
    }

    @Throws(SmbException::class)
    override fun listShares(knownShares: List<String>): List<String> {
        val sess = requireSession()
        // Pure SMB2/3 has no share-directory op; full MS-SRVS NetShareEnum is not in
        // stock SMBJ 0.14. Probe user + common home-NAS names via tree-connect.
        val candidates = SmbShareCandidates.merge(knownShares)
        if (candidates.isEmpty()) {
            SmbLog.w("listShares: no candidates after merge")
            return emptyList()
        }
        SmbLog.i("listShares: probing ${candidates.size} candidates")
        val available = mutableListOf<String>()
        for (name in candidates) {
            try {
                sess.connectShare(name).use { share ->
                    if (share is DiskShare) {
                        available += name
                    }
                }
            } catch (t: Throwable) {
                val mapped = SmbErrorMapper.map(t)
                when (mapped) {
                    is SmbError.Auth -> throw SmbException(mapped)
                    is SmbError.Network, is SmbError.Disconnected -> throw SmbException(mapped)
                    else -> SmbLog.d("Share probe miss name=$name type=${mapped::class.simpleName}")
                }
            }
        }
        return available.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    @Throws(SmbException::class)
    override fun listDirectory(shareName: String, path: String): List<SmbEntry> {
        val relative = SmbPathUtils.normalizeRelative(path)
        val share = openDiskShare(shareName)
        try {
            val listing: List<FileIdBothDirectoryInformation> = share.list(relative)
            val dirAttr = FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value
            val entries = listing.mapNotNull { info ->
                val name = info.fileName
                if (SmbPathUtils.isDotEntry(name)) return@mapNotNull null
                val isDir = (info.fileAttributes and dirAttr) != 0L
                SmbEntry(
                    name = name,
                    path = SmbPathUtils.join(relative, name),
                    isDirectory = isDir,
                    sizeBytes = info.endOfFile,
                    lastModifiedEpochMs = info.changeTime.toEpochMillis(),
                )
            }
            return SmbPathUtils.sortEntries(entries)
        } catch (t: Throwable) {
            throw SmbException(SmbErrorMapper.map(t))
        }
    }

    @Throws(SmbException::class)
    override fun metadata(shareName: String, path: String): SmbFileMetadata {
        val relative = SmbPathUtils.normalizeRelative(path)
        if (relative.isEmpty()) {
            throw SmbException(SmbError.NotFound("Empty path"))
        }
        val share = openDiskShare(shareName)
        try {
            val isDir = share.folderExists(relative)
            val isFile = !isDir && share.fileExists(relative)
            if (!isDir && !isFile) {
                throw SmbException(SmbError.NotFound("Not found path=$relative"))
            }
            if (isDir) {
                return SmbFileMetadata(
                    path = relative,
                    sizeBytes = 0L,
                    lastModifiedEpochMs = 0L,
                    isDirectory = true,
                )
            }
            openFile(share, relative).use { file ->
                val standard = file.getFileInformation(FileStandardInformation::class.java)
                val basic = file.getFileInformation(FileBasicInformation::class.java)
                return SmbFileMetadata(
                    path = relative,
                    sizeBytes = standard.endOfFile,
                    lastModifiedEpochMs = basic.lastWriteTime.toEpochMillis(),
                    isDirectory = standard.isDirectory,
                )
            }
        } catch (t: Throwable) {
            if (t is SmbException) throw t
            throw SmbException(SmbErrorMapper.map(t))
        }
    }

    @Throws(SmbException::class)
    override fun openRandomAccess(shareName: String, path: String): SmbRandomAccess {
        val relative = SmbPathUtils.normalizeRelative(path)
        if (relative.isEmpty()) {
            throw SmbException(SmbError.NotFound("Empty path"))
        }
        val share = openDiskShare(shareName)
        try {
            val file = openFile(share, relative)
            val standard = file.getFileInformation(FileStandardInformation::class.java)
            val size = standard.endOfFile
            SmbLog.d("openRandomAccess share=$shareName path=$relative size=$size")
            // Keep the cached DiskShare open for the session; only the file handle is
            // owned by the random-access reader.
            return SmbjRandomAccess(file, size)
        } catch (t: Throwable) {
            if (t is SmbException) throw t
            throw SmbException(SmbErrorMapper.map(t))
        }
    }

    override fun disconnect() {
        val snapshot = snapshotAndClearSession()
        closeSessionSnapshot(snapshot, closeClient = false)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val snapshot = snapshotAndClearSession()
        closeSessionSnapshot(snapshot, closeClient = true)
    }

    private fun snapshotAndClearSession(): SessionSnapshot {
        synchronized(lock) {
            val snapshot = SessionSnapshot(
                host = connectedHost,
                shares = shares.values.toList(),
                session = session,
                connection = connection,
            )
            shares.clear()
            session = null
            connection = null
            connectedHost = null
            return snapshot
        }
    }

    private fun closeSessionSnapshot(snapshot: SessionSnapshot, closeClient: Boolean) {
        snapshot.shares.forEach { share ->
            try {
                share.close()
            } catch (t: Throwable) {
                SmbLog.w("Share close", t)
            }
        }
        try {
            snapshot.session?.close()
        } catch (t: Throwable) {
            SmbLog.w("Session close", t)
        }
        try {
            snapshot.connection?.close()
        } catch (t: Throwable) {
            SmbLog.w("Connection close", t)
        }
        if (closeClient) {
            try {
                client.close()
            } catch (t: Throwable) {
                SmbLog.w("SMBClient close", t)
            }
        }
        if (snapshot.host != null) {
            SmbLog.i("Disconnected host=${snapshot.host}")
        }
    }

    private fun requireSession(): Session = synchronized(lock) { requireSessionLocked() }

    private fun requireSessionLocked(): Session {
        ensureOpenLocked()
        val sess = session
        val conn = connection
        if (sess == null || conn == null || !conn.isConnected) {
            throw SmbException(SmbError.Disconnected("Not connected"))
        }
        return sess
    }

    private fun ensureOpen() {
        if (closed.get()) {
            throw SmbException(SmbError.Disconnected("Client closed"))
        }
    }

    private fun ensureOpenLocked() {
        ensureOpen()
    }

    private fun openDiskShare(shareName: String): DiskShare {
        val name = shareName.trim()
        if (name.isEmpty()) {
            throw SmbException(SmbError.NotFound("Share name is empty"))
        }
        synchronized(lock) {
            shares[name]?.takeIf { it.isConnected }?.let { return it }
            shares.remove(name)
            requireSessionLocked()
        }
        val sess = requireSession()
        val opened = try {
            val share = sess.connectShare(name)
            if (share !is DiskShare) {
                share.close()
                throw SmbException(SmbError.NotFound("Not a disk share name=$name"))
            }
            share
        } catch (t: Throwable) {
            if (t is SmbException) throw t
            throw SmbException(SmbErrorMapper.map(t))
        }
        synchronized(lock) {
            if (closed.get() || session !== sess || connection?.isConnected != true) {
                runCatching { opened.close() }
                throw SmbException(SmbError.Disconnected("Not connected"))
            }
            val cached = shares[name]
            if (cached != null && cached.isConnected) {
                if (cached !== opened) runCatching { opened.close() }
                return cached
            }
            shares[name] = opened
            return opened
        }
    }

    private fun openFile(share: DiskShare, relative: String): File {
        return share.openFile(
            relative,
            EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
            null,
            // A read handle should not prevent another NAS client from replacing,
            // writing, or deleting the file while FrameNest is reading it.
            EnumSet.of(
                SMB2ShareAccess.FILE_SHARE_READ,
                SMB2ShareAccess.FILE_SHARE_WRITE,
                SMB2ShareAccess.FILE_SHARE_DELETE,
            ),
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
    }

    companion object {
        fun defaultConfig(): SmbConfig =
            SmbConfig.builder()
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(30, TimeUnit.SECONDS)
                .withDfsEnabled(false)
                .withReadBufferSize(1024 * 1024)
                .withWriteBufferSize(1024 * 1024)
                .build()
    }

    private data class SessionSnapshot(
        val host: String?,
        val shares: List<DiskShare>,
        val session: Session?,
        val connection: Connection?,
    )
}

/**
 * Random-access reader. Closes the SMB file handle only; the DiskShare stays with
 * [SmbjClient] until disconnect. Not tied to the client's state lock after
 * construction so reads can proceed while the client is idle; do not use after
 * [SmbjClient.disconnect].
 */
internal class SmbjRandomAccess(
    private val file: File,
    override val size: Long,
) : SmbRandomAccess {

    private val closed = AtomicBoolean(false)

    @Throws(IOException::class)
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed.get()) throw IOException("Stream closed")
        if (position < 0) throw IOException("Negative position")
        if (offset < 0 || length < 0 || offset + length > buffer.size) {
            throw IOException("Bad buffer range")
        }
        if (length == 0) return 0
        if (position >= size) return -1
        val toRead = minOf(length.toLong(), size - position).toInt()
        return try {
            val n = file.read(buffer, position, offset, toRead)
            when {
                n > 0 -> n
                position >= size -> -1
                else -> -1
            }
        } catch (t: Throwable) {
            val mapped = SmbErrorMapper.map(t)
            throw IOException(mapped.message, t)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                file.close()
            } catch (t: Throwable) {
                SmbLog.w("File close", t)
            }
        }
    }
}
