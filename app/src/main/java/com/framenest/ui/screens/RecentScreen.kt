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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.framenest.FrameNestApplication
import com.framenest.R
import com.framenest.data.history.PlaybackHistoryItem
import kotlinx.coroutines.flow.flowOf

/**
 * Recent playback list backed by Room history (FN-05).
 *
 * [onOpenItem] receives (serverId, share, path) for the stable player route.
 */
@Composable
fun RecentScreen(
    onOpenItem: (serverId: String, share: String, path: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext
    val historyFlow = remember(app) {
        (app as? FrameNestApplication)?.historyRepository?.observeRecent()
            ?: flowOf(emptyList())
    }
    val history by historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())

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

        if (history.isEmpty()) {
            Text(
                text = stringResource(R.string.recent_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .testTag("recent_empty"),
            )
        } else {
            Text(
                text = stringResource(R.string.recent_subtitle_history),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(
                    history,
                    key = { "${it.identity.serverId}|${it.identity.share}|${it.identity.path}" },
                ) { item ->
                    HistoryRow(
                        item = item,
                        onClick = {
                            onOpenItem(
                                item.identity.serverId,
                                item.identity.share,
                                item.identity.path,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    item: PlaybackHistoryItem,
    onClick: () -> Unit,
) {
    val key = "${item.identity.serverId}_${item.identity.path}"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("recent_history_$key")
            .semantics { contentDescription = "recent_history_$key" }
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Text(item.displayName, style = MaterialTheme.typography.titleMedium)
        Text(
            text = "${item.identity.share}/${item.identity.path}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (item.completed) {
                Text(
                    text = stringResource(R.string.recent_completed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
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
