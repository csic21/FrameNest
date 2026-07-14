package com.framenest.app

import android.content.Context
import com.framenest.data.history.PlaybackHistoryRepository
import com.framenest.data.server.AppDatabase
import com.framenest.data.server.BrowseRepository
import com.framenest.data.server.CredentialStore
import com.framenest.data.server.EncryptedCredentialStore
import com.framenest.data.server.ServerRepository
import com.framenest.data.thumbnail.ThumbnailRepository
import com.framenest.smb.SmbClient
import com.framenest.smb.SmbjClient

/**
 * Minimal manual DI graph (single app module).
 */
class AppContainer(
    context: Context,
    database: AppDatabase = AppDatabase.getInstance(context),
    credentialStore: CredentialStore = EncryptedCredentialStore(context),
    clientFactory: () -> SmbClient = { SmbjClient() },
) {
    val database: AppDatabase = database

    val serverRepository: ServerRepository = ServerRepository(
        serverDao = database.serverDao(),
        credentialStore = credentialStore,
        clientFactory = clientFactory,
    )

    val browseRepository: BrowseRepository = BrowseRepository(
        serverRepository = serverRepository,
        clientFactory = clientFactory,
    )

    val historyRepository: PlaybackHistoryRepository = PlaybackHistoryRepository(
        dao = database.playbackHistoryDao(),
    )

    /** List thumbnails (FN-07); clearable via [ThumbnailRepository.clearCache]. */
    val thumbnailRepository: ThumbnailRepository = ThumbnailRepository(
        context = context,
        serverRepository = serverRepository,
        clientFactory = clientFactory,
    )

    companion object {
        fun forTests(
            context: Context,
            credentialStore: CredentialStore,
            clientFactory: () -> SmbClient = { SmbjClient() },
        ): AppContainer {
            val db = AppDatabase.createInMemory(context)
            return AppContainer(
                context = context,
                database = db,
                credentialStore = credentialStore,
                clientFactory = clientFactory,
            )
        }
    }
}
