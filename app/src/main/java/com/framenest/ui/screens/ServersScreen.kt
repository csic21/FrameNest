package com.framenest.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.framenest.R
import com.framenest.navigation.FakeCatalog
import com.framenest.navigation.FakeServer

@Composable
fun ServersScreen(
    useListDetail: Boolean,
    onOpenBrowse: (serverId: String) -> Unit,
    onOpenPlayer: (serverId: String, entryId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val servers = FakeCatalog.servers
    var selectedServerId by rememberSaveable {
        mutableStateOf(servers.firstOrNull()?.id)
    }

    if (useListDetail) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .testTag("servers_list_detail"),
        ) {
            ServerListPane(
                servers = servers,
                selectedServerId = selectedServerId,
                onSelect = { selectedServerId = it.id },
                onOpen = { onOpenBrowse(it.id) },
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
                server = servers.find { it.id == selectedServerId },
                onBrowse = { serverId -> onOpenBrowse(serverId) },
                onOpenSampleVideo = { serverId, entryId -> onOpenPlayer(serverId, entryId) },
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxHeight()
                    .testTag("servers_detail_pane"),
            )
        }
    } else {
        ServerListPane(
            servers = servers,
            selectedServerId = null,
            onSelect = { onOpenBrowse(it.id) },
            onOpen = { onOpenBrowse(it.id) },
            modifier = modifier
                .fillMaxSize()
                .testTag("servers_single_pane"),
        )
    }
}

@Composable
private fun ServerListPane(
    servers: List<FakeServer>,
    selectedServerId: String?,
    onSelect: (FakeServer) -> Unit,
    onOpen: (FakeServer) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(16.dp)) {
        Text(
            text = stringResource(R.string.servers_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("servers_title"),
        )
        Text(
            text = stringResource(R.string.servers_subtitle_fake),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(servers, key = { it.id }) { server ->
                val selected = server.id == selectedServerId
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("server_item_${server.id}")
                        .semantics { contentDescription = "server_item_${server.id}" }
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
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(server.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = server.host,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.servers_share_count, server.shareCount),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
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

@Composable
private fun ServerDetailPane(
    server: FakeServer?,
    onBrowse: (String) -> Unit,
    onOpenSampleVideo: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier) {
        if (server == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.servers_select_prompt),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(server.name, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.servers_detail_host, server.host),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.servers_share_count, server.shareCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.servers_detail_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(
                    onClick = { onBrowse(server.id) },
                    modifier = Modifier.testTag("servers_open_browse"),
                ) {
                    Text(stringResource(R.string.servers_open_browse))
                }
                TextButton(
                    onClick = { onOpenSampleVideo(server.id, "movie-a") },
                    modifier = Modifier.testTag("servers_open_player"),
                ) {
                    Text(stringResource(R.string.servers_open_sample_player))
                }
            }
        }
    }
}
