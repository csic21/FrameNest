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
import com.hierynomus.smbj.event.SMBEventBus
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
 * detach state while a listing is blocked. [abort] skips graceful SMB logoff.
 * Callers should still keep heavy work off the main thread.
 */
class SmbjClient(
    private val config: SmbConfig = defaultConfig(),
    private val clientFactory: (SmbConfig) -> SMBClient = { SMBClient(it) },
    private val connectionFactory: (SmbConfig, SMBClient) -> Connection = { config, client ->
        Connection(config, client, SMBEventBus(), client.serverList)
    },
) : SmbClient {

    // Never share an SMBJ connection cache across reconnect generations.
    private var connectingClient: SMBClient? = null
    private val lock = Any()
    private var connection: Connection? = null
    private var session: Session? = null
    private var connectedHost: String? = null
    private val shares = LinkedHashMap<String, DiskShare>()
    private val closed = AtomicBoolean(false)

    override val isConnected: Boolean
        get() {
            val conn = synchronized(lock) {
                if (closed.get() || session == null) null else connection
            }
            return conn?.isConnected == true
        }

    @Throws(SmbException::class)
    override fun connect(credentials: SmbCredentials) {
        ensureOpen()
        val host = credentials.host.trim()
        if (host.isEmpty()) throw SmbException(SmbError.Network("Host is empty"))
        val effectiveConfig = secureConfig(config, credentials.requireEncryption)
        val next = clientFactory(effectiveConfig)
        val stale = synchronized(lock) {
            ensureOpenLocked()
            snapshotAndClearSession().also { connectingClient = next }
        }
        closeSessionSnapshot(stale, force = true)
        runCatching { SmbLog.i("Connecting SMB transport port=${credentials.port}") }
        var unpublishedConnection: Connection? = null
        try {
            // Construct the public SMBJ Connection before doing network I/O.
            // SMBClient.connect hides it until negotiation completes, which
            // would leave that phase unreachable to a cancellation/timeout.
            val conn = connectionFactory(effectiveConfig, next)
            unpublishedConnection = conn
            synchronized(lock) {
                ensureCurrentClient(next)
                connection = conn
                connectedHost = host
            }
            conn.connect(host, credentials.port)
            synchronized(lock) { ensureCurrentClient(next) }
            if (credentials.requireEncryption && !conn.connectionContext.clientPrefersEncryption()) {
                throw SmbException(SmbError.Security())
            }
            unpublishedConnection = null
            val sess = conn.authenticate(
                AuthenticationContext(credentials.username, credentials.password, credentials.domain),
            )
            if (!sess.isSigningRequired || sess.isGuest || sess.isAnonymous ||
                (credentials.requireEncryption && !sess.shouldEncryptData())) {
                throw SmbException(SmbError.Security())
            }
            synchronized(lock) {
                ensureCurrentClient(next)
                session = sess
            }
            runCatching { SmbLog.i("Authenticated to host=$host port=${credentials.port}") }
        } catch (t: Throwable) {
            // Socket creation can finish after a concurrent abort. Retire that
            // late transport again before it can authenticate or publish state.
            // Never touch the new generation's connection or SMBClient.
            unpublishedConnection?.let(::abortConnection)
            val failed = synchronized(lock) {
                if (connectingClient === next) snapshotAndClearSession() else null
            }
            failed?.let { closeSessionSnapshot(it, force = true) }
            if (t is SmbException) throw t
            val error = SmbErrorMapper.map(t)
            runCatching { SmbLog.e("Connect failed type=${error::class.simpleName} msg=${error.message}", t) }
            throw SmbException(error)
        }
    }

    private fun ensureCurrentClient(expected: SMBClient) {
        ensureOpenLocked()
        if (connectingClient !== expected) {
            throw SmbException(SmbError.Disconnected("Connection attempt released"))
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
        closeSessionSnapshot(snapshot, force = true)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val snapshot = snapshotAndClearSession()
        closeSessionSnapshot(snapshot, force = false)
    }

    override fun abort() {
        if (!closed.compareAndSet(false, true)) return
        val snapshot = snapshotAndClearSession()
        closeSessionSnapshot(snapshot, force = true)
    }

    private fun snapshotAndClearSession(): SessionSnapshot {
        synchronized(lock) {
            val snapshot = SessionSnapshot(
                host = connectedHost,
                shares = shares.values.toList(),
                session = session,
                connection = connection,
                client = connectingClient,
            )
            shares.clear()
            session = null
            connection = null
            connectedHost = null
            connectingClient = null
            return snapshot
        }
    }

    private fun closeSessionSnapshot(snapshot: SessionSnapshot, force: Boolean) {
        if (force) {
            // Supported SMBJ 0.14 API: close(true) disconnects the transport
            // without tree-disconnect / session-logoff network round trips.
            // Do NOT call Share.close or Session.close before this abort.
            snapshot.connection?.let(::abortConnection)
        } else {
            snapshot.shares.forEach { share ->
                runCatching { share.close() }.onFailure { SmbLog.w("Share close", it) }
            }
            runCatching { snapshot.session?.close() }.onFailure { SmbLog.w("Session close", it) }
            runCatching { snapshot.connection?.close() }.onFailure { SmbLog.w("Connection close", it) }
        }
        // This SMBClient belongs solely to the detached generation. Late
        // connect completion is handled by the identity check and forced close.
        runCatching { snapshot.client?.close() }.onFailure { SmbLog.w("SMBClient close", it) }
        if (snapshot.host != null) runCatching { SmbLog.i("Disconnected host=${snapshot.host}") }
    }

    private fun abortConnection(conn: Connection) {
        runCatching { conn.close(true) }
            .onFailure { runCatching { SmbLog.w("Connection abort", it) } }
        // SMBJ stops its packet reader during close(true). A stopped reader does
        // not report an error to outstanding request futures, so also notify its
        // public error handler AFTER transport shutdown. This wakes blocked I/O;
        // any ensuing session cleanup runs against the already closed transport.
        runCatching { conn.handleError(IOException("SMB transport aborted")) }
            .onFailure { runCatching { SmbLog.w("Connection abort notification", it) } }
    }

    private fun requireSession(): Session = synchronized(lock) { requireSessionLocked() }

    private fun requireSessionLocked(): Session {
        ensureOpenLocked()
        val sess = session
        val conn = connection
        if (sess == null || conn == null) {
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
        val cached = synchronized(lock) { shares[name] }
        if (cached?.isConnected == true) return cached
        val sess = synchronized(lock) {
            if (shares[name] === cached) shares.remove(name)
            requireSessionLocked()
        }
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
        val selected = synchronized(lock) {
            if (closed.get() || session !== sess || connection == null) {
                null
            } else {
                shares[name] ?: opened.also { shares[name] = it }
            }
        }
        if (selected !== opened) runCatching { opened.close() }
        return selected ?: throw SmbException(SmbError.Disconnected("Not connected"))
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
        internal fun secureConfig(base: SmbConfig, requireEncryption: Boolean): SmbConfig =
            SmbConfig.builder(base).withSigningRequired(true).withSigningEnabled(true)
                .withEncryptData(requireEncryption).build()

        fun defaultConfig(): SmbConfig =
            SmbConfig.builder()
                .withSigningRequired(true)
                .withEncryptData(true)
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
        val client: SMBClient?,
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
