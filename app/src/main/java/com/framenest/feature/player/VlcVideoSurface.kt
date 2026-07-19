package com.framenest.feature.player

import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
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
    var surfaceSize by remember(controller) { mutableStateOf(IntSize.Zero) }
    var refreshAtSize by remember(controller) { mutableStateOf<IntSize?>(null) }

    // A rotation may report several intermediate constraints. Wait until the next
    // frame and refresh only the last real size, never both orientation and size.
    LaunchedEffect(controller, refreshAtSize) {
        if (refreshAtSize != null) {
            withFrameNanos { }
        }
        if (refreshAtSize != null && controller.state.value.firstFrameReady) {
            controller.refreshVideoSurfaces()
        }
    }

    key(controller) {
        AndroidView(
            modifier = modifier.onSizeChanged { size ->
                if (size.width <= 0 || size.height <= 0) return@onSizeChanged
                if (size == surfaceSize) return@onSizeChanged
                if (surfaceSize != IntSize.Zero) {
                    refreshAtSize = size
                }
                surfaceSize = size
            },
            factory = { context ->
                FrameLayout(context).also { container ->
                    controller.attachVideoLayout(container)
                }
            },
            onRelease = {
                // Detach video output only — do not stop/release the player here.
                // Player lifecycle is owned by PlayerViewModel.onCleared.
                controller.detachVideoLayout(it)
            },
        )
    }
}
