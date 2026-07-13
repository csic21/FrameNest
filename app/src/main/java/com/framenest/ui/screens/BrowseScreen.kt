package com.framenest.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.framenest.R
import com.framenest.navigation.FakeCatalog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    serverId: String,
    pathId: String,
    onBack: () -> Unit,
    onOpenDirectory: (pathId: String) -> Unit,
    onOpenFile: (entryId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val server = FakeCatalog.server(serverId)
    val entries = FakeCatalog.browseEntries(serverId, pathId)
    val title = when {
        pathId == FakeCatalog.ROOT_PATH_ID -> server?.name ?: serverId
        else -> FakeCatalog.entryTitle(pathId)
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("browse_screen"),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("browse_back"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Text(
                text = stringResource(
                    R.string.browse_path_label,
                    if (pathId == FakeCatalog.ROOT_PATH_ID) "/" else "/$pathId",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                text = stringResource(R.string.browse_fake_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.name) },
                        supportingContent = {
                            Text(
                                if (entry.isDirectory) {
                                    stringResource(R.string.browse_type_folder)
                                } else {
                                    stringResource(R.string.browse_type_file)
                                },
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = when {
                                    entry.isDirectory -> Icons.Filled.Folder
                                    entry.name.contains("mkv", ignoreCase = true) ||
                                        entry.name.contains("mp4", ignoreCase = true) ||
                                        entry.name.contains("webm", ignoreCase = true) ->
                                        Icons.Filled.Movie
                                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                                },
                                contentDescription = null,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("browse_item_${entry.id}")
                            .semantics { contentDescription = "browse_item_${entry.id}" }
                            .clickable {
                                if (entry.isDirectory) {
                                    onOpenDirectory(entry.id)
                                } else {
                                    onOpenFile(entry.id)
                                }
                            },
                    )
                }
            }
        }
    }
}
