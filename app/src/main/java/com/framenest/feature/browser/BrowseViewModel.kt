package com.framenest.feature.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.framenest.core.model.RemoteEntry
import com.framenest.core.model.RemoteLocation
import com.framenest.core.model.SavedServer
import com.framenest.data.server.BrowseRepository
import com.framenest.data.server.ServerRepository
import com.framenest.data.server.SmbUiMessages
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseUiState(
    val server: SavedServer? = null,
    val location: RemoteLocation = RemoteLocation.ROOT,
    val entries: List<RemoteEntry> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isShareList: Boolean = true,
)

class BrowseViewModel(
    private val serverId: String,
    initialLocation: RemoteLocation,
    private val serverRepository: ServerRepository,
    private val browseRepository: BrowseRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(
        BrowseUiState(location = initialLocation, isShareList = initialLocation.isShareList),
    )
    val uiState: StateFlow<BrowseUiState> = _ui.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            val server = serverRepository.getServer(serverId)
            _ui.update { it.copy(server = server) }
            if (server == null) {
                _ui.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "服务器不存在",
                    )
                }
            } else {
                refresh()
            }
        }
    }

    fun refresh() {
        abortInFlightLoad()
        loadJob = viewModelScope.launch {
            val location = _ui.value.location
            _ui.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    isShareList = location.isShareList,
                )
            }
            val result = browseRepository.load(serverId, location)
            result.fold(
                onSuccess = { content ->
                    val entries = when (content) {
                        is BrowseRepository.BrowseContent.Shares -> content.entries
                        is BrowseRepository.BrowseContent.Directory -> content.entries
                    }
                    _ui.update {
                        it.copy(
                            isLoading = false,
                            entries = entries,
                            errorMessage = null,
                            isShareList = location.isShareList,
                        )
                    }
                },
                onFailure = { error ->
                    _ui.update {
                        it.copy(
                            isLoading = false,
                            entries = emptyList(),
                            errorMessage = SmbUiMessages.fromThrowable(error),
                        )
                    }
                },
            )
        }
    }

    fun title(): String {
        val state = _ui.value
        val location = state.location
        return when {
            location.isShareList -> state.server?.name ?: serverId
            location.normalizedPath.isEmpty() -> location.share
            else -> location.normalizedPath.substringAfterLast('/')
        }
    }

    fun pathLabel(): String {
        val location = _ui.value.location
        return when {
            location.isShareList -> "/"
            location.normalizedPath.isEmpty() -> "/${location.share}"
            else -> "/${location.share}/${location.normalizedPath}"
        }
    }

    override fun onCleared() {
        abortInFlightLoad()
        super.onCleared()
    }

    private fun abortInFlightLoad() {
        val previous = loadJob
        loadJob = null
        if (previous != null && !previous.isCompleted) {
            previous.cancel()
            // cancel() is cooperative and will not interrupt blocking SMB I/O.
            // Close the transport so the waiter unblocks and releases sessionMutex.
            browseRepository.releaseSession()
        }
    }

    class Factory(
        private val serverId: String,
        private val location: RemoteLocation,
        private val serverRepository: ServerRepository,
        private val browseRepository: BrowseRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(BrowseViewModel::class.java)) {
                return BrowseViewModel(
                    serverId = serverId,
                    initialLocation = location,
                    serverRepository = serverRepository,
                    browseRepository = browseRepository,
                ) as T
            }
            error("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
