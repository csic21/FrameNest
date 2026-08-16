package com.framenest.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

internal const val PLAYER_VIEWPORT_SURFACE_HOST_TAG = "player_viewport_surface_host"

/**
 * Keeps the video surface at fixed full-viewport bounds while transient player
 * chrome is composed above it. Chrome height and visibility must never
 * participate in the surface measurement pass.
 */
@Composable
internal fun PlayerViewport(
    surface: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    viewportTag: String? = null,
    topChrome: (@Composable BoxScope.() -> Unit)? = null,
    bottomChrome: (@Composable BoxScope.() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (viewportTag != null) Modifier.testTag(viewportTag) else Modifier,
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(PLAYER_VIEWPORT_SURFACE_HOST_TAG),
                content = surface,
            )
            topChrome?.let { chrome ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                    content = chrome,
                )
            }
            bottomChrome?.let { chrome ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    content = chrome,
                )
            }
            overlay()
        }
    }
}
