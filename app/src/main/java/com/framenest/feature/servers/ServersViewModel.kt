package com.framenest.feature.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.framenest.core.model.SavedServer
import com.framenest.data.server.ServerRepository
import com.framenest.data.server.SmbUiMessages
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

data class ServersUiState(
    val servers: List<SavedServer> = emptyList(),
    val selectedServerId: String? = null,
    val editor: ServerEditorState? = null,
    val actionError: String? = null,
    val isDeleting: Boolean = false,
)

class ServersViewModel(
    private val serverRepository: ServerRepository,
) : ViewModel() {

    private val serversFlow: StateFlow<List<SavedServer>> = serverRepository
        .observeServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(ServersUiState())
    val uiState: StateFlow<ServersUiState> = _ui.asStateFlow()

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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ServersViewModel::class.java)) {
                return ServersViewModel(serverRepository) as T
            }
            error("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
