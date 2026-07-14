package com.framenest.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.framenest.FrameNestApplication
import com.framenest.R
import com.framenest.data.history.PlaybackHistoryItem
import com.framenest.ui.theme.FrameNestDimens
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
            // Edge-to-edge (MainActivity): keep title below the status bar.
            .statusBarsPadding()
            .padding(FrameNestDimens.ScreenPadding)
            .testTag("recent_screen"),
    ) {
        Text(
            text = stringResource(R.string.recent_title),
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("recent_title"),
        )

        if (history.isEmpty()) {
            Text(
                text = stringResource(R.string.recent_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .widthIn(max = FrameNestDimens.ReadableContentMaxWidth)
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
    val itemCd = stringResource(R.string.recent_item_cd, item.displayName)
    val progressCd = stringResource(
        R.string.recent_progress_cd,
        (item.progressFraction * 100).toInt().coerceIn(0, 100),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = FrameNestDimens.MinTouchTarget)
            .testTag("recent_history_$key")
            .semantics { contentDescription = itemCd }
            .clickable(onClick = onClick)
            .padding(vertical = FrameNestDimens.ListRowVerticalPadding),
    ) {
        Text(
            text = item.displayName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${item.identity.share}/${item.identity.path}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
                        .padding(top = 6.dp)
                        .semantics { contentDescription = progressCd },
                )
                Text(
                    text = "${(item.progressFraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
