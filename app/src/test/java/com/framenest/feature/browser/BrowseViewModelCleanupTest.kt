package com.framenest.feature.browser

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.framenest.core.model.RemoteLocation
import com.framenest.data.server.BlockingCleanupSmbClient
import com.framenest.data.server.BrowseTestSmbClient
import com.framenest.data.server.browseTestRepository
import com.framenest.data.server.browseTestServerRepository
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.system.measureTimeMillis

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelCleanupTest {
    @Before fun setMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test(timeout = 5_000)
    fun clearingAfterCompletedLoad_releasesSessionWithoutWaitingForClose() = runBlocking {
        val client = BlockingCleanupSmbClient(blockListing = false, forceAbort = true)
        val repo = browseTestRepository { client }
        val viewModel = BrowseViewModel("s1", RemoteLocation.shareRoot("media"), browseTestServerRepository(), repo)
        val store = ViewModelStore().also { it.put("browse", viewModel) }
        try {
            withTimeout(1_000) { viewModel.uiState.first { !it.isLoading && it.entries.isNotEmpty() } }
            val clearMs = measureTimeMillis { store.clear() }
            assertTrue("clear waited for blocked cleanup: ${clearMs}ms", clearMs < 1_000)
            assertTrue(client.cleanupEntered.await(1, TimeUnit.SECONDS))
            assertEquals(1L, client.allowCleanup.count)
            store.clear()
            assertEquals(1, client.closeCount.get())
        } finally {
            client.allowCleanup.countDown()
            store.clear()
            repo.releaseSession()
            viewModel.viewModelScope.coroutineContext.job.join()
        }
    }

    @Test(timeout = 5_000)
    fun repeatedRefreshAndExit_doesNotWaitForOldBlockingCleanup() = runBlocking {
        val old = BlockingCleanupSmbClient(blockListing = true, forceAbort = false)
        val successors = ConcurrentLinkedQueue<BrowseTestSmbClient>()
        val created = AtomicInteger()
        val repo = browseTestRepository {
            if (created.getAndIncrement() == 0) old else BrowseTestSmbClient().also(successors::add)
        }
        val viewModel = BrowseViewModel("s1", RemoteLocation.shareRoot("media"), browseTestServerRepository(), repo)
        val store = ViewModelStore().also { it.put("browse", viewModel) }
        try {
            assertTrue(old.listingEntered.await(1, TimeUnit.SECONDS))
            val refreshMs = measureTimeMillis { repeat(3) { viewModel.refresh() } }
            assertTrue("refresh waited for blocked cleanup: ${refreshMs}ms", refreshMs < 1_000)
            assertTrue(old.cleanupEntered.await(1, TimeUnit.SECONDS))
            withTimeout(1_000) { viewModel.uiState.first { !it.isLoading && it.entries.isNotEmpty() } }
            assertEquals(1L, old.allowCleanup.count)
            store.clear()
            assertTrue(successors.isNotEmpty())
            successors.forEach { assertTrue(it.cleanupEntered.await(1, TimeUnit.SECONDS)) }
            assertEquals(1, old.closeCount.get())
        } finally {
            old.allowCleanup.countDown()
            store.clear()
            repo.releaseSession()
            viewModel.viewModelScope.coroutineContext.job.join()
        }
    }
}
