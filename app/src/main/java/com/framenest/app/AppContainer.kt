package com.framenest.app

import android.content.Context
import com.framenest.core.diagnostics.DiagnosticLogExporter
import com.framenest.data.history.PlaybackHistoryRepository
import com.framenest.data.listen_translate.ListenTranslateRepository
import com.framenest.data.listen_translate.model.ListenModelManager
import com.framenest.data.server.AppDatabase
import com.framenest.data.server.BrowseRepository
import com.framenest.data.server.CredentialStore
import com.framenest.data.server.EncryptedCredentialStore
import com.framenest.data.server.ServerRepository
import com.framenest.data.settings.UserPreferences
import com.framenest.data.thumbnail.ThumbnailRepository
import com.framenest.feature.listen_translate.asr.VoskModelInstaller
import com.framenest.feature.settings.CacheMaintenance
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
    private val appContext = context.applicationContext

    val database: AppDatabase = database

    val userPreferences: UserPreferences = UserPreferences(appContext)

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

    /** Listen-translate cues/jobs (FN-11); app-private Room only. */
    val listenTranslateRepository: ListenTranslateRepository = ListenTranslateRepository(
        dao = database.listenTranslateDao(),
    )

    /** Legacy JSON packs under filesDir (FN-13 shell); uninstall clears. */
    val listenModelManager: ListenModelManager = ListenModelManager(appContext)

    /** Vosk offline ASR models (FN-14); filesDir/listen_models/vosk. */
    val voskModelInstaller: VoskModelInstaller = VoskModelInstaller(appContext)

    /** List thumbnails (FN-07); concurrency from [userPreferences]. */
    val thumbnailRepository: ThumbnailRepository = ThumbnailRepository(
        context = appContext,
        serverRepository = serverRepository,
        clientFactory = clientFactory,
        concurrencyProvider = { userPreferences.thumbnailConcurrency() },
    )

    val cacheMaintenance: CacheMaintenance = CacheMaintenance(
        context = appContext,
        thumbnailRepository = thumbnailRepository,
        listenTranslateRepository = listenTranslateRepository,
        listenModelManager = listenModelManager,
        voskModelInstaller = voskModelInstaller,
    )

    val diagnosticLogExporter: DiagnosticLogExporter = DiagnosticLogExporter(appContext)

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
