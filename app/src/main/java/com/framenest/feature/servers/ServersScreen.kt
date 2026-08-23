package com.framenest.feature.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.framenest.ContextAppContainer
import com.framenest.R
import com.framenest.core.model.SavedServer
import com.framenest.data.discovery.DiscoveredHost
import com.framenest.data.server.ServerRepository
import com.framenest.ui.theme.FrameNestDimens

@Composable
fun ServersRoute(
    useListDetail: Boolean,
    serverRepository: ServerRepository,
    onOpenBrowse: (serverId: String, defaultShare: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = remember(context) { ContextAppContainer(context) }
    val viewModel: ServersViewModel = viewModel(
        factory = ServersViewModel.Factory(
            serverRepository = serverRepository,
            appContext = context.applicationContext,
            listenTranslateRepository = container.listenTranslateRepository,
            playbackHistoryRepository = container.historyRepository,
        ),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ServersScreen(
        state = state,
        useListDetail = useListDetail,
        onSelect = viewModel::selectServer,
        onOpenBrowse = { server -> onOpenBrowse(server.id, server.defaultShare) },
        onAdd = viewModel::openAddEditor,
        onScanLan = viewModel::openLanDiscovery,
        onEdit = viewModel::openEditEditor,
        onDelete = viewModel::deleteServer,
        onDismissEditor = viewModel::dismissEditor,
        onUpdateEditor = viewModel::updateEditor,
        onTest = viewModel::testEditorConnection,
        onSave = viewModel::saveEditor,
        onClearError = viewModel::clearActionError,
        onDismissDiscovery = viewModel::dismissLanDiscovery,
        onStopDiscovery = viewModel::stopLanDiscovery,
        onDeepPortScan = viewModel::startDeepPortScan,
        onSelectDiscovered = viewModel::selectDiscoveredHost,
        modifier = modifier,
    )
}

@Composable
fun ServersScreen(
    state: ServersUiState,
    useListDetail: Boolean,
    onSelect: (String?) -> Unit,
    onOpenBrowse: (SavedServer) -> Unit,
    onAdd: () -> Unit,
    onScanLan: () -> Unit,
    onEdit: (SavedServer) -> Unit,
    onDelete: (String) -> Unit,
    onDismissEditor: () -> Unit,
    onUpdateEditor: ((ServerEditorState) -> ServerEditorState) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onClearError: () -> Unit,
    onDismissDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onDeepPortScan: () -> Unit,
    onSelectDiscovered: (DiscoveredHost) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            if (state.servers.isNotEmpty()) {
                FloatingActionButton(
                    onClick = onAdd,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("servers_add"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.servers_add),
                    )
                }
            }
        },
    ) { padding ->
        // useListDetail comes from WindowSizeClass medium+ (never raw width < 600).
        if (state.servers.isEmpty()) {
            ServerEmptyPane(
                onScanLan = onScanLan,
                onAdd = onAdd,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            )
        } else if (useListDetail) {
            Row(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .testTag("servers_list_detail"),
            ) {
                ServerListPane(
                    servers = state.servers,
                    selectedServerId = state.selectedServerId,
                    showRowActions = false,
                    onSelect = { onSelect(it.id) },
                    onOpen = onOpenBrowse,
                    onEdit = onEdit,
                    onDelete = { pendingDeleteId = it.id },
                    onScanLan = onScanLan,
                    modifier = Modifier
                        .weight(0.4f)
                        .fillMaxHeight(),
                )
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp),
                )
                ServerDetailPane(
                    server = state.servers.find { it.id == state.selectedServerId },
                    onBrowse = onOpenBrowse,
                    onEdit = onEdit,
                    onDelete = { pendingDeleteId = it.id },
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight()
                        .testTag("servers_detail_pane"),
                )
            }
        } else {
            ServerListPane(
                servers = state.servers,
                selectedServerId = null,
                showRowActions = true,
                onSelect = { onOpenBrowse(it) },
                onOpen = onOpenBrowse,
                onEdit = onEdit,
                onDelete = { pendingDeleteId = it.id },
                onScanLan = onScanLan,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .testTag("servers_single_pane"),
            )
        }
    }

    state.editor?.let { editor ->
        ServerEditorDialog(
            state = editor,
            onDismiss = onDismissEditor,
            onUpdate = onUpdateEditor,
            onTest = onTest,
            onSave = onSave,
        )
    }

    if (state.discovery.isOpen) {
        LanDiscoveryDialog(
            state = state.discovery,
            onDismiss = onDismissDiscovery,
            onStop = onStopDiscovery,
            onDeepScan = onDeepPortScan,
            onSelect = onSelectDiscovered,
        )
    }

    pendingDeleteId?.let { id ->
        val name = state.servers.find { it.id == id }?.name ?: id
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(stringResource(R.string.servers_delete_title)) },
            text = { Text(stringResource(R.string.servers_delete_body, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(id)
                        pendingDeleteId = null
                    },
                    modifier = Modifier.testTag("servers_delete_confirm"),
                ) {
                    Text(stringResource(R.string.servers_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    state.actionError?.let { message ->
        AlertDialog(
            onDismissRequest = onClearError,
            title = { Text(stringResource(R.string.servers_error_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onClearError) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }
}

@Composable
private fun ServerEmptyPane(
    onScanLan: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(FrameNestDimens.ScreenPadding)
            .testTag("servers_empty_pane"),
    ) {
        Text(
            text = stringResource(R.string.servers_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("servers_title"),
        )
        Text(
            text = stringResource(R.string.servers_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.servers_empty),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("servers_empty"),
            )
            Text(
                text = stringResource(R.string.servers_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .widthIn(max = FrameNestDimens.ReadableContentMaxWidth),
            )
            Button(
                onClick = onScanLan,
                modifier = Modifier
                    .padding(top = 20.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("servers_empty_scan"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Radar,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.servers_scan_lan))
            }
            OutlinedButton(
                onClick = onAdd,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("servers_empty_add"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.servers_add))
            }
            Spacer(Modifier.height(56.dp))
        }
    }
}

@Composable
private fun ServerListPane(
    servers: List<SavedServer>,
    selectedServerId: String?,
    showRowActions: Boolean,
    onSelect: (SavedServer) -> Unit,
    onOpen: (SavedServer) -> Unit,
    onEdit: (SavedServer) -> Unit,
    onDelete: (SavedServer) -> Unit,
    onScanLan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(FrameNestDimens.ScreenPadding)) {
        Text(
            text = stringResource(R.string.servers_title),
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("servers_title"),
        )
        Text(
            text = stringResource(R.string.servers_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        OutlinedButton(
            onClick = onScanLan,
            modifier = Modifier
                .padding(bottom = 12.dp)
                .minimumInteractiveComponentSize()
                .testTag("servers_scan_lan"),
        ) {
            Icon(
                imageVector = Icons.Filled.Radar,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.servers_scan_lan))
        }
        if (servers.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp)
                    .testTag("servers_empty"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.servers_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = FrameNestDimens.ReadableContentMaxWidth),
                )
                Text(
                    text = stringResource(R.string.servers_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .widthIn(max = FrameNestDimens.ReadableContentMaxWidth)
                        .padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(servers, key = { it.id }) { server ->
                    val selected = server.id == selectedServerId
                    val itemDescription = stringResource(
                        R.string.servers_item_cd,
                        server.name,
                        hostLabel(server),
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = FrameNestDimens.MinTouchTarget)
                            .testTag("server_item_${server.id}")
                            .semantics { contentDescription = itemDescription }
                            .clickable {
                                onSelect(server)
                                if (selectedServerId == null) {
                                    onOpen(server)
                                }
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = 8.dp,
                                    vertical = FrameNestDimens.ListRowVerticalPadding,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(8.dp),
                            ) {
                                Text(
                                    text = server.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = hostLabel(server),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                server.defaultShare?.let { share ->
                                    Text(
                                        text = stringResource(R.string.servers_default_share, share),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (showRowActions) {
                                IconButton(
                                    onClick = { onEdit(server) },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("server_row_edit_${server.id}"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Edit,
                                        contentDescription = stringResource(R.string.servers_edit),
                                    )
                                }
                                IconButton(
                                    onClick = { onDelete(server) },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("server_row_delete_${server.id}"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.servers_delete),
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerDetailPane(
    server: SavedServer?,
    onBrowse: (SavedServer) -> Unit,
    onEdit: (SavedServer) -> Unit,
    onDelete: (SavedServer) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier) {
        if (server == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .testTag("servers_detail_empty"),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.servers_select_prompt),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = FrameNestDimens.ReadableContentMaxWidth),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("servers_detail_content"),
            ) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.servers_detail_host, hostLabel(server)),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.servers_detail_user, server.username),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                server.defaultShare?.let { share ->
                    Text(
                        text = stringResource(R.string.servers_default_share, share),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(16.dp))
                TextButton(
                    onClick = { onBrowse(server) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("servers_open_browse"),
                ) {
                    Text(stringResource(R.string.servers_open_browse))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { onEdit(server) },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("servers_edit"),
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.servers_edit))
                    }
                    TextButton(
                        onClick = { onDelete(server) },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("servers_delete"),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.servers_delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerEditorDialog(
    state: ServerEditorState,
    onDismiss: () -> Unit,
    onUpdate: ((ServerEditorState) -> ServerEditorState) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
) {
    val title = if (state.editingId == null) {
        stringResource(R.string.servers_add_title)
    } else {
        stringResource(R.string.servers_edit_title)
    }
    AlertDialog(
        onDismissRequest = {
            if (canDismissServerEditor(state.isSaving)) onDismiss()
        },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { v -> onUpdate { it.copy(name = v) } },
                    enabled = canEditServerEditor(state.isTesting, state.isSaving),
                    label = { Text(stringResource(R.string.servers_field_name)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("server_field_name"),
                )
                OutlinedTextField(
                    value = state.host,
                    onValueChange = { v -> onUpdate { it.copy(host = v) } },
                    enabled = canEditServerEditor(state.isTesting, state.isSaving),
                    label = { Text(stringResource(R.string.servers_field_host)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("server_field_host"),
                )
                OutlinedTextField(
                    value = state.port,
                    onValueChange = { v -> onUpdate { it.copy(port = v) } },
                    enabled = canEditServerEditor(state.isTesting, state.isSaving),
                    label = { Text(stringResource(R.string.servers_field_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("server_field_port"),
                )
                OutlinedTextField(
                    value = state.username,
                    onValueChange = { v -> onUpdate { it.copy(username = v) } },
                    enabled = canEditServerEditor(state.isTesting, state.isSaving),
                    label = { Text(stringResource(R.string.servers_field_username)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("server_field_username"),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = { v -> onUpdate { it.copy(password = v) } },
                    enabled = canEditServerEditor(state.isTesting, state.isSaving),
                    label = {
                        Text(
                            if (state.isPasswordRequired) {
                                stringResource(R.string.servers_field_password)
                            } else {
                                stringResource(R.string.servers_field_password_optional)
                            },
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("server_field_password"),
                )
                OutlinedTextField(
                    value = state.domain,
                    onValueChange = { v -> onUpdate { it.copy(domain = v) } },
                    enabled = canEditServerEditor(state.isTesting, state.isSaving),
                    label = { Text(stringResource(R.string.servers_field_domain)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("server_field_domain"),
                )
                OutlinedTextField(
                    value = state.defaultShare,
                    onValueChange = { v -> onUpdate { it.copy(defaultShare = v) } },
                    enabled = canEditServerEditor(state.isTesting, state.isSaving),
                    label = { Text(stringResource(R.string.servers_field_share)) },
                    singleLine = true,
                    supportingText = {
                        Text(stringResource(R.string.servers_field_share_hint))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("server_field_share"),
                )
                state.formError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                state.testMessage?.let { msg ->
                    Text(
                        text = msg,
                        color = if (state.testSucceeded) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.testTag("server_test_message"),
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onTest,
                    enabled = !state.isTesting && !state.isSaving,
                    modifier = Modifier.testTag("server_test"),
                ) {
                    if (state.isTesting) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp))
                    } else {
                        Text(stringResource(R.string.servers_test_connection))
                    }
                }
                Button(
                    onClick = onSave,
                    enabled = !state.isTesting && !state.isSaving,
                    modifier = Modifier.testTag("server_save"),
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp))
                    } else {
                        Text(stringResource(R.string.action_save))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isSaving) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

internal fun canDismissServerEditor(isSaving: Boolean): Boolean = !isSaving

internal fun canEditServerEditor(isTesting: Boolean, isSaving: Boolean): Boolean =
    !isTesting && !isSaving

@Composable
private fun LanDiscoveryDialog(
    state: LanDiscoveryUiState,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
    onDeepScan: () -> Unit,
    onSelect: (DiscoveredHost) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("servers_discovery_dialog"),
        title = { Text(stringResource(R.string.servers_scan_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.servers_scan_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.statusMessage?.let { msg ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(16.dp)
                                    .testTag("servers_scan_progress"),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("servers_scan_status"),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onDeepScan,
                        enabled = !state.portScanRunning,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("servers_scan_deep"),
                    ) {
                        Text(
                            text = if (state.portScanRunning) {
                                stringResource(R.string.servers_scan_deep_running)
                            } else {
                                stringResource(R.string.servers_scan_deep)
                            },
                        )
                    }
                    if (state.isBusy) {
                        TextButton(
                            onClick = onStop,
                            modifier = Modifier.testTag("servers_scan_stop"),
                        ) {
                            Text(stringResource(R.string.servers_scan_stop))
                        }
                    }
                }
                if (state.results.isEmpty()) {
                    Text(
                        text = stringResource(R.string.servers_scan_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .testTag("servers_scan_empty"),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .testTag("servers_scan_results"),
                    ) {
                        items(state.results, key = { it.mergeKey() }) { host ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = FrameNestDimens.MinTouchTarget)
                                    .clickable { onSelect(host) }
                                    .padding(vertical = 10.dp)
                                    .testTag("servers_scan_item_${host.mergeKey()}"),
                            ) {
                                Text(
                                    text = host.displayTitle,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = buildString {
                                        append(host.connectHost)
                                        if (host.port != DiscoveredHost.DEFAULT_SMB_PORT) {
                                            append(':')
                                            append(host.port)
                                        }
                                        append(" · ")
                                        append(discoverySourceLabel(host.sources))
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("servers_scan_close"),
            ) {
                Text(stringResource(R.string.action_ok))
            }
        },
    )
}

private fun hostLabel(server: SavedServer): String =
    if (server.port == SavedServer.DEFAULT_PORT) {
        server.host
    } else {
        "${server.host}:${server.port}"
    }
