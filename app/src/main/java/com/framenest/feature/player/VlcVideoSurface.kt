package com.framenest.feature.player

import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import com.framenest.player.PlayerController

/**
 * Compose integration for libVLC video output.
 *
 * Approach (documented for 0001 decision):
 * - Host a [FrameLayout] via [AndroidView]
 * - On factory, call [PlayerController.attachVideoLayout] so the
 *   controller injects [org.videolan.libvlc.util.VLCVideoLayout] and attaches
 *   MediaPlayer views (SurfaceView path, useTextureView=false)
 * - Key the host by controller instead of re-binding on every progress recomposition
 * - On dispose, detach views but leave player release to the Activity lifecycle
 *   so configuration changes can re-attach cleanly if needed
 *
 * Avoid calling [PlayerController.refreshVideoSurfaces] on every recomposition —
 * that resets libVLC surfaces and freezes the picture at the first decoded frame
 * while the UI still reports "Playing".
 *
 * Do not put Compose draw modifiers that clip/hardware-layer the surface in ways
 * that prevent the SurfaceView from compositing correctly.
 */
@Composable
fun VlcVideoSurface(
    controller: PlayerController,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    var lastWidth by remember(controller) { mutableIntStateOf(0) }
    var lastHeight by remember(controller) { mutableIntStateOf(0) }

    // Only when orientation / window size class actually changes — and only after
    // a frame is ready so prepare is not interrupted by surface rebuilds.
    LaunchedEffect(
        controller,
        configuration.orientation,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
    ) {
        if (controller.state.value.firstFrameReady) {
            controller.refreshVideoSurfaces()
        }
    }

    key(controller) {
        AndroidView(
            modifier = modifier.onSizeChanged { size ->
                if (size.width <= 0 || size.height <= 0) return@onSizeChanged
                if (size.width == lastWidth && size.height == lastHeight) return@onSizeChanged
                lastWidth = size.width
                lastHeight = size.height
                if (controller.state.value.firstFrameReady) {
                    controller.refreshVideoSurfaces()
                }
            },
            factory = { context ->
                FrameLayout(context).also { container ->
                    controller.attachVideoLayout(container)
                }
            },
            onRelease = {
                // Detach video output only — do not stop/release the player here.
                // Player lifecycle is owned by PlayerViewModel.onCleared.
                controller.detachVideoLayout()
            },
        )
    }
}
