package com.framenest.feature.player

import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.framenest.player.PlayerController

/**
 * Compose integration for libVLC video output.
 *
 * Approach (documented for 0001 decision):
 * - Host a [FrameLayout] via [AndroidView]
 * - On factory / update, call [PlayerController.attachVideoLayout] so the
 *   controller injects [org.videolan.libvlc.util.VLCVideoLayout] and attaches
 *   MediaPlayer views (SurfaceView path, useTextureView=false)
 * - On dispose, detach views but leave player release to the Activity lifecycle
 *   so configuration changes can re-attach cleanly if needed
 *
 * Do not put Compose draw modifiers that clip/hardware-layer the surface in ways
 * that prevent the SurfaceView from compositing correctly.
 */
@Composable
fun VlcVideoSurface(
    controller: PlayerController,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            FrameLayout(context).also { container ->
                controller.attachVideoLayout(container)
            }
        },
        update = { container ->
            controller.attachVideoLayout(container)
        },
        onRelease = {
            controller.detachVideoLayout()
        },
    )
}
