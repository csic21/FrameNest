package com.framenest.feature.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.framenest.ContextAppContainer
import com.framenest.R
import com.framenest.core.model.MediaExtensions
import com.framenest.core.model.RemoteEntry
import com.framenest.core.model.RemoteLocation
import com.framenest.data.server.BrowseRepository
import com.framenest.data.server.ServerRepository
import com.framenest.data.settings.BrowseLayoutMode
import com.framenest.data.thumbnail.ThumbnailRepository
import com.framenest.data.thumbnail.ThumbnailUiState
import com.framenest.ui.theme.FrameNestDimens
import com.framenest.ui.theme.FrameNestIvory
import com.framenest.ui.theme.FrameNestMidnight

@Composable
fun BrowseRoute(
    serverId: String,
    location: RemoteLocation,
    serverRepository: ServerRepository,
    browseRepository: BrowseRepository,
    thumbnailRepository: ThumbnailRepository,
    onBack: () -> Unit,
    onOpenDirectory: (RemoteLocation) -> Unit,
    onOpenFile: (RemoteEntry) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BrowseViewModel = viewModel(
        key = "browse-$serverId-${location.share}-${location.path}",
        factory = BrowseViewModel.Factory(
            serverId = serverId,
            location = location,
            serverRepository = serverRepository,
            browseRepository = browseRepository,
        ),
    ),
) {
    val context = LocalContext.current
    val prefs = remember(context) { ContextAppContainer(context).userPreferences }
    var layoutMode by remember { mutableStateOf(prefs.browseLayoutMode()) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BrowseScreen(
        state = state,
        title = viewModel.title(),
        pathLabel = viewModel.pathLabel(),
        layoutMode = layoutMode,
        thumbnailRepository = thumbnailRepository,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onToggleLayout = {
            val next = when (layoutMode) {
                BrowseLayoutMode.LIST -> BrowseLayoutMode.GRID
                BrowseLayoutMode.GRID -> BrowseLayoutMode.LIST
            }
            layoutMode = next
            prefs.setBrowseLayoutMode(next)
        },
        onOpenEntry = { entry ->
            when (browseEntryAction(entry)) {
                BrowseEntryAction.OPEN_SHARE -> {
                    onOpenDirectory(RemoteLocation.shareRoot(entry.share))
                }
                BrowseEntryAction.OPEN_DIRECTORY -> onOpenDirectory(
                    RemoteLocation.of(entry.share, entry.path),
                )
                BrowseEntryAction.OPEN_VIDEO -> onOpenFile(entry)
                BrowseEntryAction.NONE -> Unit
            }
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    state: BrowseUiState,
    title: String,
    pathLabel: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenEntry: (RemoteEntry) -> Unit,
    modifier: Modifier = Modifier,
    layoutMode: BrowseLayoutMode = BrowseLayoutMode.LIST,
    onToggleLayout: (() -> Unit)? = null,
    thumbnailRepository: ThumbnailRepository? = null,
) {
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
                actions = {
                    if (onToggleLayout != null) {
                        val switchToGrid = layoutMode == BrowseLayoutMode.LIST
                        IconButton(
                            onClick = onToggleLayout,
                            modifier = Modifier.testTag("browse_layout_toggle"),
                        ) {
                            Icon(
                                imageVector = if (switchToGrid) {
                                    Icons.Filled.GridView
                                } else {
                                    Icons.AutoMirrored.Filled.ViewList
                                },
                                contentDescription = stringResource(
                                    if (switchToGrid) {
                                        R.string.browse_layout_grid
                                    } else {
                                        R.string.browse_layout_list
                                    },
                                ),
                            )
                        }
                    }
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.testTag("browse_refresh"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.browse_refresh),
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
                text = stringResource(R.string.browse_path_label, pathLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("browse_path"),
            )

            when {
                state.isLoading && state.entries.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("browse_loading"),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                state.errorMessage != null && state.entries.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .testTag("browse_error"),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = state.errorMessage,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(
                            onClick = onRefresh,
                            modifier = Modifier
                                .padding(top = 16.dp)
                                .testTag("browse_retry"),
                        ) {
                            Text(stringResource(R.string.browse_retry))
                        }
                    }
                }
                state.entries.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("browse_empty"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (state.isShareList) {
                                stringResource(R.string.browse_empty_shares)
                            } else {
                                stringResource(R.string.browse_empty_dir)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .widthIn(max = FrameNestDimens.ReadableContentMaxWidth)
                                .padding(FrameNestDimens.ScreenPadding),
                        )
                    }
                }
                layoutMode == BrowseLayoutMode.GRID -> {
                    BrowseGrid(
                        entries = state.entries,
                        thumbnailRepository = thumbnailRepository,
                        onOpenEntry = onOpenEntry,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("browse_grid"),
                    )
                }
                else -> {
                    BrowseList(
                        entries = state.entries,
                        thumbnailRepository = thumbnailRepository,
                        onOpenEntry = onOpenEntry,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("browse_list"),
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowseList(
    entries: List<RemoteEntry>,
    thumbnailRepository: ThumbnailRepository?,
    onOpenEntry: (RemoteEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        items(entries, key = { it.stableKey() }) { entry ->
            val tag = "browse_item_${entry.stableKey()}"
            val typeLabel = entrySupportingText(entry)
            val entryDescription = stringResource(
                R.string.browse_entry_cd,
                entry.name,
                typeLabel,
            )
            ListItem(
                headlineContent = {
                    Text(
                        text = entry.name,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(
                        text = typeLabel,
                        maxLines = if (MediaExtensions.isSubtitle(entry.name)) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = {
                    BrowseEntryLeading(
                        entry = entry,
                        thumbnailRepository = thumbnailRepository,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = FrameNestDimens.MinTouchTarget)
                    .minimumInteractiveComponentSize()
                    .testTag(tag)
                    .semantics { contentDescription = entryDescription }
                    .then(
                        if (isBrowseEntryActionable(entry)) {
                            Modifier.clickable { onOpenEntry(entry) }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

@Composable
private fun BrowseGrid(
    entries: List<RemoteEntry>,
    thumbnailRepository: ThumbnailRepository?,
    onOpenEntry: (RemoteEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val columns = browseGridColumnCount(maxWidth.value)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(FrameNestDimens.BrowseGridPadding),
            horizontalArrangement = Arrangement.spacedBy(FrameNestDimens.BrowseGridSpacing),
            verticalArrangement = Arrangement.spacedBy(FrameNestDimens.BrowseGridSpacing),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(entries, key = { it.stableKey() }) { entry ->
                BrowseGridCell(
                    entry = entry,
                    thumbnailRepository = thumbnailRepository,
                    onOpen = { onOpenEntry(entry) },
                )
            }
        }
    }
}

@Composable
private fun BrowseGridCell(
    entry: RemoteEntry,
    thumbnailRepository: ThumbnailRepository?,
    onOpen: () -> Unit,
) {
    val tag = "browse_item_${entry.stableKey()}"
    val typeLabel = entrySupportingText(entry)
    val entryDescription = stringResource(
        R.string.browse_entry_cd,
        entry.name,
        typeLabel,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(FrameNestDimens.BrowseThumbCorner))
            .then(
                if (isBrowseEntryActionable(entry)) {
                    Modifier.clickable(onClick = onOpen)
                } else {
                    Modifier
                },
            )
            .testTag(tag)
            .semantics {
                contentDescription = entryDescription
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(FrameNestDimens.BrowseThumbCorner))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val isVideo = entry.isFile && MediaExtensions.isVideo(entry.name)
            if (isVideo && thumbnailRepository != null) {
                BrowseVideoThumbnail(
                    entry = entry,
                    repository = thumbnailRepository,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("browse_thumb_${entry.stableKey()}"),
                )
            } else {
                Icon(
                    imageVector = entryIcon(entry),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 2.dp),
        )
        Text(
            text = typeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (MediaExtensions.isSubtitle(entry.name)) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BrowseEntryLeading(
    entry: RemoteEntry,
    thumbnailRepository: ThumbnailRepository?,
) {
    val isVideo = entry.isFile && MediaExtensions.isVideo(entry.name)
    if (!isVideo) {
        Icon(
            imageVector = entryIcon(entry),
            contentDescription = null,
        )
        return
    }
    Box(
        modifier = Modifier
            .size(
                width = FrameNestDimens.BrowseListThumbWidth,
                height = FrameNestDimens.BrowseListThumbHeight,
            )
            .clip(RoundedCornerShape(FrameNestDimens.BrowseThumbCorner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag("browse_thumb_slot_${entry.stableKey()}"),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnailRepository != null) {
            BrowseVideoThumbnail(
                entry = entry,
                repository = thumbnailRepository,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("browse_thumb_${entry.stableKey()}"),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Movie,
                contentDescription = null,
            )
        }
    }
}

/**
 * Shows a **cached** bitmap only. While generating, a placeholder icon is shown.
 * Does not create a player instance.
 */
@Composable
private fun BrowseVideoThumbnail(
    entry: RemoteEntry,
    repository: ThumbnailRepository,
    modifier: Modifier = Modifier,
) {
    val thumbnailState = remember(entry, repository) { repository.observe(entry) }
    val state by thumbnailState.collectAsStateWithLifecycle(
        initialValue = ThumbnailUiState.None,
    )
    DisposableEffect(entry, repository) {
        repository.retain(entry)
        onDispose { repository.release(entry) }
    }

    Box(
        modifier = modifier.clip(RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            is ThumbnailUiState.Ready -> {
                Image(
                    bitmap = s.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("browse_thumb_ready"),
                )
                if (s.durationMs > 0L) {
                    Text(
                        text = BrowseEntryText.formatDuration(s.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = FrameNestIvory,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(FrameNestMidnight.copy(alpha = 0.72f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                            .clearAndSetSemantics { },
                    )
                }
            }
            ThumbnailUiState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(20.dp)
                        .testTag("browse_thumb_loading"),
                    strokeWidth = 2.dp,
                )
            }
            else -> {
                Icon(
                    imageVector = Icons.Filled.Movie,
                    contentDescription = null,
                    modifier = Modifier.testTag("browse_thumb_placeholder"),
                )
            }
        }
    }
}

@Composable
private fun entrySupportingText(entry: RemoteEntry): String = when {
    entry.isShare -> stringResource(R.string.browse_type_share)
    entry.isDirectory -> stringResource(R.string.browse_type_folder)
    MediaExtensions.isSubtitle(entry.name) -> stringResource(R.string.browse_type_subtitle_hint)
    MediaExtensions.isVideo(entry.name) -> {
        val size = BrowseEntryText.formatBytes(entry.sizeBytes)
        if (size == null) {
            stringResource(R.string.browse_type_video)
        } else {
            stringResource(R.string.browse_type_video_with_size, size)
        }
    }
    else -> stringResource(R.string.browse_type_file)
}

private fun entryIcon(entry: RemoteEntry) = when {
    entry.isShare -> Icons.Filled.Storage
    entry.isDirectory -> Icons.Filled.Folder
    MediaExtensions.isVideo(entry.name) -> Icons.Filled.Movie
    MediaExtensions.isSubtitle(entry.name) -> Icons.Filled.Subtitles
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}
