package com.framenest.data.server

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.framenest.core.model.SavedServer

/**
 * Room row for a saved SMB server.
 *
 * Never store plaintext passwords here — only [credentialAlias].
 */
@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val domain: String?,
    val credentialAlias: String,
    val defaultShare: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    @ColumnInfo(defaultValue = "1") val requireEncryption: Boolean = true,
) {
    fun toModel(): SavedServer = SavedServer(
        id = id,
        name = name,
        host = host,
        port = port,
        username = username,
        domain = domain,
        credentialAlias = credentialAlias,
        defaultShare = defaultShare,
        requireEncryption = requireEncryption,
    )

    companion object {
        fun fromModel(server: SavedServer, createdAtMs: Long, updatedAtMs: Long): ServerEntity =
            ServerEntity(
                id = server.id,
                name = server.name,
                host = server.host,
                port = server.port,
                username = server.username,
                domain = server.domain,
                credentialAlias = server.credentialAlias,
                defaultShare = server.defaultShare,
                requireEncryption = server.requireEncryption,
                createdAtMs = createdAtMs,
                updatedAtMs = updatedAtMs,
            )
    }
}
