package com.framenest.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.framenest.R
import com.framenest.navigation.FakeCatalog

@Composable
fun RecentScreen(
    onOpenItem: (serverId: String, entryId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("recent_screen"),
    ) {
        Text(
            text = stringResource(R.string.recent_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("recent_title"),
        )
        Text(
            text = stringResource(R.string.recent_subtitle_fake),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(FakeCatalog.recent, key = { it.id }) { item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("recent_item_${item.id}")
                        .semantics { contentDescription = "recent_item_${item.id}" }
                        .clickable {
                            // Map recent ids to fake player entry ids for the shell.
                            val entryId = when (item.id) {
                                "rec-1" -> "movie-a"
                                "rec-2" -> "ep-1"
                                else -> "movie-b"
                            }
                            onOpenItem(item.serverId, entryId)
                        }
                        .padding(vertical = 4.dp),
                ) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = item.serverName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = { item.progressFraction },
                            modifier = Modifier
                                .weight(1f)
                                .padding(top = 6.dp),
                        )
                        Text(
                            text = "${(item.progressFraction * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}
