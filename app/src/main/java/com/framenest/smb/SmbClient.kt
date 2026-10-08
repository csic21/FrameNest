package com.framenest.smb

import java.io.Closeable

/**
 * Minimal SMB2/3 client surface for browsing and random-access reads.
 * UI and repositories depend on this, not on SMBJ types.
 */
interface SmbClient : Closeable {
    val isConnected: Boolean

    @Throws(SmbException::class)
    fun connect(credentials: SmbCredentials)

    /**
     * Best-effort share name listing.
     * SMBJ has no first-class share enum without MS-SRVS; implementations may
     * return only shares the session can tree-connect to, or require [knownShares].
     */
    @Throws(SmbException::class)
    fun listShares(knownShares: List<String> = emptyList()): List<String>

    @Throws(SmbException::class)
    fun listDirectory(shareName: String, path: String = ""): List<SmbEntry>

    @Throws(SmbException::class)
    fun metadata(shareName: String, path: String): SmbFileMetadata

    @Throws(SmbException::class)
    fun openRandomAccess(shareName: String, path: String): SmbRandomAccess

    /**
     * Force-close transport; subsequent calls fail until [connect].
     */
    fun disconnect()

    /**
     * Terminal cancellation/timeout cleanup. Implementations should skip graceful
     * network logoff and close the transport first. Still call off the UI thread:
     * socket teardown or a legacy implementation's [close] may block.
     */
    fun abort() = close()
}
