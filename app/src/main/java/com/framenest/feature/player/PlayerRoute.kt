package com.framenest.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.framenest.R
import com.framenest.app.AppContainer
import com.framenest.core.model.PlaybackIdentity
import com.framenest.core.model.PlaybackRequest
import com.framenest.core.model.SavedServer
import com.framenest.data.history.PlaybackProgressRules
import com.framenest.player.CredentialRedactor
import com.framenest.smb.SmbPathUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads server credentials + resume position, then hosts [PlayerScreen].
 *
 * Route args are (serverId, share, path) only — never passwords.
 */
@Composable
fun PlayerRoute(
    serverId: String,
    share: String,
    path: String,
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var request by remember(serverId, share, path) { mutableStateOf<PlaybackRequest?>(null) }
    var error by remember(serverId, share, path) { mutableStateOf<String?>(null) }

    LaunchedEffect(serverId, share, path) {
        request = null
        error = null
        val built = withContext(Dispatchers.IO) {
            buildPlaybackRequest(
                container = container,
                serverId = serverId,
                share = share,
                path = path,
            )
        }
        built.fold(
            onSuccess = { request = it },
            onFailure = { e ->
                error = CredentialRedactor.redact(e.message ?: e.toString())
            },
        )
    }

    when {
        error != null -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .testTag("player_route_error"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = error ?: stringResource(R.string.player_error_generic),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        request == null -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .testTag("player_route_loading"),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        else -> {
            PlayerScreen(
                request = request!!,
                onBack = onBack,
                modifier = modifier,
            )
        }
    }
}

private suspend fun buildPlaybackRequest(
    container: AppContainer,
    serverId: String,
    share: String,
    path: String,
): Result<PlaybackRequest> {
    val normalizedPath = SmbPathUtils.normalizeRelative(path)
    if (share.isBlank() || normalizedPath.isBlank()) {
        return Result.failure(IllegalArgumentException("share and path are required"))
    }

    val identity = PlaybackIdentity(serverId = serverId, share = share, path = normalizedPath)
    val history = container.historyRepository.get(identity)
    val resumeMs = history?.let {
        PlaybackProgressRules.resumePositionMs(it.positionMs, it.durationMs, it.completed)
    } ?: 0L

    val server: SavedServer = container.serverRepository.getServer(serverId)
        ?: return Result.failure(IllegalStateException("Server not found"))

    val password = container.serverRepository.getPassword(server)
        ?: return Result.failure(IllegalStateException("Missing credentials for server"))

    return try {
        Result.success(
            PlaybackRequestFactory.fromSavedServer(
                server = server,
                share = share,
                path = normalizedPath,
                password = password,
                startPositionMs = resumeMs,
                displayName = history?.displayName
                    ?: normalizedPath.substringAfterLast('/'),
            ),
        )
    } finally {
        // fromSavedServer copies the char array into the request; clear our buffer.
        password.fill('\u0000')
    }
}
