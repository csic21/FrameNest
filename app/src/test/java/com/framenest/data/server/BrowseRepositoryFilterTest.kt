package com.framenest.data.server

import com.framenest.core.model.RemoteLocation
import com.framenest.core.model.SavedServer
import com.framenest.smb.SmbClient
import com.framenest.smb.SmbCredentials
import com.framenest.smb.SmbEntry
import com.framenest.smb.SmbFileMetadata
import com.framenest.smb.SmbRandomAccess
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseRepositoryFilterTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Test
    fun directoryListing_filtersNonMediaFiles_keepsDirsVideosSubtitles() = runTest(dispatcher) {
        val server = SavedServer(
            id = "s1",
            name = "NAS",
            host = "h",
            username = "u",
            credentialAlias = "a",
            defaultShare = "media",
        )
        val dao = object : ServerDao by ThrowingDao {
            override suspend fun getById(id: String) = ServerEntity.fromModel(server, 0, 0)
        }
        val store = InMemoryCredentialStore().also {
            it.savePassword("a", "pw".toCharArray())
        }
        val serverRepo = ServerRepository(
            serverDao = dao,
            credentialStore = store,
            ioDispatcher = dispatcher,
        )
        val listing = listOf(
            SmbEntry("Movies", "Movies", true, 0, 0),
            SmbEntry("clip.mkv", "clip.mkv", false, 10, 0),
            SmbEntry("clip.srt", "clip.srt", false, 1, 0),
            SmbEntry("readme.txt", "readme.txt", false, 1, 0),
            SmbEntry("poster.jpg", "poster.jpg", false, 1, 0),
        )
        var connectCount = 0
        val browseRepo = BrowseRepository(
            serverRepository = serverRepo,
            clientFactory = {
                object : SmbClient {
                    override val isConnected: Boolean = true
                    override fun connect(credentials: SmbCredentials) {
                        connectCount++
                    }
                    override fun listShares(knownShares: List<String>) = knownShares
                    override fun listDirectory(shareName: String, path: String) = listing
                    override fun metadata(shareName: String, path: String): SmbFileMetadata =
                        error("n/a")
                    override fun openRandomAccess(shareName: String, path: String): SmbRandomAccess =
                        error("n/a")
                    override fun disconnect() = Unit
                    override fun close() = Unit
                }
            },
            ioDispatcher = dispatcher,
        )

        val result = browseRepo.load("s1", RemoteLocation.shareRoot("media")).getOrThrow()
        val names = (result as BrowseRepository.BrowseContent.Directory).entries.map { it.name }
        assertEquals(listOf("Movies", "clip.mkv", "clip.srt"), names)
        assertTrue(names.none { it.endsWith(".txt") || it.endsWith(".jpg") })

        browseRepo.load("s1", RemoteLocation.shareRoot("media")).getOrThrow()
        assertEquals(1, connectCount)
    }
}

private object ThrowingDao : ServerDao {
    override fun observeAll() = error("unused")
    override suspend fun getAll() = error("unused")
    override suspend fun getById(id: String) = error("unused")
    override suspend fun upsert(entity: ServerEntity) = error("unused")
    override suspend fun update(entity: ServerEntity) = error("unused")
    override suspend fun deleteById(id: String) = error("unused")
}
