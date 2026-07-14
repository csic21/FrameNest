package com.framenest.feature.servers

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.framenest.core.model.SavedServer
import com.framenest.data.discovery.DiscoveredHost
import com.framenest.data.discovery.DiscoveryEvent
import com.framenest.data.discovery.DiscoveryMerge
import com.framenest.data.discovery.DiscoveryPhase
import com.framenest.data.discovery.DiscoverySource
import com.framenest.data.discovery.LanDiscoveryCoordinator
import com.framenest.data.listen_translate.ListenTranslateRepository
import com.framenest.data.server.ServerRepository
import com.framenest.data.server.SmbUiMessages
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerEditorState(
    val editingId: String? = null,
    val name: String = "",
    val host: String = "",
    val port: String = SavedServer.DEFAULT_PORT.toString(),
    val username: String = "",
    val domain: String = "",
    val defaultShare: String = "",
    val password: String = "",
    val isPasswordRequired: Boolean = true,
    val isTesting: Boolean = false,
    val isSaving: Boolean = false,
    val testMessage: String? = null,
    val testSucceeded: Boolean = false,
    val formError: String? = null,
)

data class LanDiscoveryUiState(
    val isOpen: Boolean = false,
    val mdnsRunning: Boolean = false,
    val portScanRunning: Boolean = false,
    val results: List<DiscoveredHost> = emptyList(),
    val statusMessage: String? = null,
) {
    val isBusy: Boolean get() = mdnsRunning || portScanRunning
}

data class ServersUiState(
    val servers: List<SavedServer> = emptyList(),
    val selectedServerId: String? = null,
    val editor: ServerEditorState? = null,
    val discovery: LanDiscoveryUiState = LanDiscoveryUiState(),
    val actionError: String? = null,
    val isDeleting: Boolean = false,
)

class ServersViewModel(
    private val serverRepository: ServerRepository,
    private val discovery: LanDiscoveryCoordinator,
    private val listenTranslateRepository: ListenTranslateRepository? = null,
) : ViewModel() {

    private val serversFlow: StateFlow<List<SavedServer>> = serverRepository
        .observeServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(ServersUiState())
    val uiState: StateFlow<ServersUiState> = _ui.asStateFlow()

    private var eventsJob: Job? = null

    init {
        viewModelScope.launch {
            serversFlow.collect { list ->
                _ui.update { state ->
                    val selected = state.selectedServerId
                        ?.takeIf { id -> list.any { it.id == id } }
                        ?: list.firstOrNull()?.id
                    state.copy(servers = list, selectedServerId = selected)
                }
            }
        }
    }

    fun selectServer(id: String?) {
        _ui.update { it.copy(selectedServerId = id, actionError = null) }
    }

    fun openAddEditor() {
        _ui.update {
            it.copy(
                editor = ServerEditorState(isPasswordRequired = true),
                actionError = null,
            )
        }
    }

    fun openEditEditor(server: SavedServer) {
        _ui.update {
            it.copy(
                editor = ServerEditorState(
                    editingId = server.id,
                    name = server.name,
                    host = server.host,
                    port = server.port.toString(),
                    username = server.username,
                    domain = server.domain.orEmpty(),
                    defaultShare = server.defaultShare.orEmpty(),
                    password = "",
                    isPasswordRequired = false,
                ),
                actionError = null,
            )
        }
    }

    fun dismissEditor() {
        _ui.update { it.copy(editor = null) }
    }

    fun updateEditor(transform: (ServerEditorState) -> ServerEditorState) {
        _ui.update { state ->
            val editor = state.editor ?: return@update state
            state.copy(editor = transform(editor).copy(formError = null))
        }
    }

    fun openLanDiscovery() {
        stopDiscoveryInternal(keepDialog = false)
        _ui.update {
            it.copy(
                discovery = LanDiscoveryUiState(
                    isOpen = true,
                    mdnsRunning = true,
                    statusMessage = "正在通过 mDNS 发现 SMB 设备…",
                ),
                actionError = null,
            )
        }
        eventsJob = viewModelScope.launch {
            discovery.events.collect { event ->
                when (event) {
                    is DiscoveryEvent.Found -> {
                        _ui.update { state ->
                            state.copy(
                                discovery = state.discovery.copy(
                                    results = DiscoveryMerge.upsert(
                                        state.discovery.results,
                                        event.host,
                                    ),
                                ),
                            )
                        }
                    }
                    is DiscoveryEvent.Phase -> {
                        _ui.update { state ->
                            val d = state.discovery
                            state.copy(
                                discovery = when (event.phase) {
                                    DiscoveryPhase.MDNS_RUNNING -> d.copy(
                                        mdnsRunning = true,
                                        statusMessage = d.statusMessage
                                            ?: "正在通过 mDNS 发现 SMB 设备…",
                                    )
                                    DiscoveryPhase.PORT_SCAN_RUNNING -> d.copy(
                                        portScanRunning = true,
                                        statusMessage = "正在探测局域网 SMB 端口…",
                                    )
                                    DiscoveryPhase.PORT_SCAN_DONE -> d.copy(
                                        portScanRunning = false,
                                        statusMessage = if (d.mdnsRunning) {
                                            "端口探测结束；mDNS 仍在监听"
                                        } else {
                                            "扫描结束"
                                        },
                                    )
                                },
                            )
                        }
                    }
                    is DiscoveryEvent.Message -> {
                        _ui.update { state ->
                            state.copy(
                                discovery = state.discovery.copy(statusMessage = event.text),
                            )
                        }
                    }
                }
            }
        }
        discovery.startMdns(viewModelScope)
    }

    fun startDeepPortScan() {
        if (!_ui.value.discovery.isOpen) return
        if (_ui.value.discovery.portScanRunning) return
        discovery.startPortScan(viewModelScope)
    }

    fun stopLanDiscovery() {
        stopDiscoveryInternal(keepDialog = true)
        _ui.update { state ->
            state.copy(
                discovery = state.discovery.copy(
                    mdnsRunning = false,
                    portScanRunning = false,
                    statusMessage = "已停止",
                ),
            )
        }
    }

    fun dismissLanDiscovery() {
        stopDiscoveryInternal(keepDialog = false)
        _ui.update { it.copy(discovery = LanDiscoveryUiState()) }
    }

    fun selectDiscoveredHost(host: DiscoveredHost) {
        stopDiscoveryInternal(keepDialog = false)
        _ui.update {
            it.copy(
                discovery = LanDiscoveryUiState(),
                editor = ServerEditorState(
                    name = host.displayTitle,
                    host = host.connectHost,
                    port = host.port.toString(),
                    isPasswordRequired = true,
                ),
                actionError = null,
            )
        }
    }

    private fun stopDiscoveryInternal(keepDialog: Boolean) {
        discovery.stopAll()
        eventsJob?.cancel()
        eventsJob = null
        if (!keepDialog) {
            // caller resets discovery state
        }
    }

    fun testEditorConnection() {
        val editor = _ui.value.editor ?: return
        val parsed = parseEditor(editor) ?: return
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    editor = it.editor?.copy(
                        isTesting = true,
                        testMessage = null,
                        testSucceeded = false,
                    ),
                )
            }
            val result = if (editor.password.isNotEmpty()) {
                val passwordChars = editor.password.toCharArray()
                try {
                    serverRepository.testConnection(
                        host = parsed.host,
                        port = parsed.port,
                        username = parsed.username,
                        domain = parsed.domain,
                        password = passwordChars,
                    )
                } finally {
                    passwordChars.fill('\u0000')
                }
            } else if (editor.editingId != null) {
                serverRepository.testSavedServer(editor.editingId)
            } else {
                Result.failure(IllegalArgumentException("请输入密码"))
            }
            _ui.update {
                val msg = result.fold(
                    onSuccess = { "连接成功" },
                    onFailure = { err -> SmbUiMessages.fromThrowable(err) },
                )
                it.copy(
                    editor = it.editor?.copy(
                        isTesting = false,
                        testSucceeded = result.isSuccess,
                        testMessage = msg,
                    ),
                )
            }
        }
    }

    fun saveEditor() {
        val editor = _ui.value.editor ?: return
        val parsed = parseEditor(editor) ?: return
        viewModelScope.launch {
            _ui.update { it.copy(editor = it.editor?.copy(isSaving = true, formError = null)) }
            try {
                if (editor.editingId == null) {
                    val password = editor.password.toCharArray()
                    if (password.isEmpty()) {
                        _ui.update {
                            it.copy(
                                editor = it.editor?.copy(
                                    isSaving = false,
                                    formError = "请输入密码",
                                ),
                            )
                        }
                        return@launch
                    }
                    val saved = serverRepository.addServer(
                        name = parsed.name,
                        host = parsed.host,
                        port = parsed.port,
                        username = parsed.username,
                        domain = parsed.domain,
                        defaultShare = parsed.defaultShare,
                        password = password,
                    )
                    password.fill('\u0000')
                    _ui.update {
                        it.copy(
                            editor = null,
                            selectedServerId = saved.id,
                            actionError = null,
                        )
                    }
                } else {
                    val newPassword = editor.password
                        .takeIf { it.isNotEmpty() }
                        ?.toCharArray()
                    val saved = serverRepository.updateServer(
                        id = editor.editingId,
                        name = parsed.name,
                        host = parsed.host,
                        port = parsed.port,
                        username = parsed.username,
                        domain = parsed.domain,
                        defaultShare = parsed.defaultShare,
                        newPassword = newPassword,
                    )
                    newPassword?.fill('\u0000')
                    _ui.update {
                        it.copy(
                            editor = null,
                            selectedServerId = saved.id,
                            actionError = null,
                        )
                    }
                }
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        editor = it.editor?.copy(
                            isSaving = false,
                            formError = SmbUiMessages.fromThrowable(t),
                        ),
                    )
                }
            }
        }
    }

    fun deleteServer(id: String) {
        viewModelScope.launch {
            _ui.update { it.copy(isDeleting = true, actionError = null) }
            try {
                serverRepository.deleteServer(id)
                // Drop listen-translate jobs for this server (decision 0005 cleanup).
                listenTranslateRepository?.purgeServer(id)
                _ui.update { state ->
                    state.copy(
                        isDeleting = false,
                        selectedServerId = state.selectedServerId
                            ?.takeIf { it != id },
                    )
                }
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        isDeleting = false,
                        actionError = SmbUiMessages.fromThrowable(t),
                    )
                }
            }
        }
    }

    fun clearActionError() {
        _ui.update { it.copy(actionError = null) }
    }

    override fun onCleared() {
        stopDiscoveryInternal(keepDialog = false)
        super.onCleared()
    }

    private data class ParsedEditor(
        val name: String,
        val host: String,
        val port: Int,
        val username: String,
        val domain: String?,
        val defaultShare: String?,
    )

    private fun parseEditor(editor: ServerEditorState): ParsedEditor? {
        val name = editor.name.trim()
        val host = editor.host.trim()
        val username = editor.username.trim()
        val port = editor.port.trim().toIntOrNull() ?: SavedServer.DEFAULT_PORT
        if (name.isEmpty() || host.isEmpty() || username.isEmpty()) {
            _ui.update {
                it.copy(
                    editor = it.editor?.copy(formError = "名称、主机和用户名为必填项"),
                )
            }
            return null
        }
        if (port !in 1..65535) {
            _ui.update {
                it.copy(editor = it.editor?.copy(formError = "端口必须在 1–65535"))
            }
            return null
        }
        return ParsedEditor(
            name = name,
            host = host,
            port = port,
            username = username,
            domain = editor.domain.trim().ifEmpty { null },
            defaultShare = editor.defaultShare.trim().ifEmpty { null },
        )
    }

    class Factory(
        private val serverRepository: ServerRepository,
        private val appContext: Context,
        private val discovery: LanDiscoveryCoordinator = LanDiscoveryCoordinator(appContext),
        private val listenTranslateRepository: ListenTranslateRepository? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ServersViewModel::class.java)) {
                return ServersViewModel(
                    serverRepository,
                    discovery,
                    listenTranslateRepository,
                ) as T
            }
            error("Unknown ViewModel: ${modelClass.name}")
        }
    }
}

/** Source labels for discovery result rows (UI / tests). */
fun discoverySourceLabel(sources: Set<DiscoverySource>): String {
    val parts = buildList {
        if (DiscoverySource.MDNS in sources) add("mDNS")
        if (DiscoverySource.PORT_PROBE in sources) add("445")
    }
    return parts.joinToString(" · ").ifEmpty { "LAN" }
}
