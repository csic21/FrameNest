package com.framenest.feature.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.framenest.R
import com.framenest.player.MediaSource
import com.framenest.player.VlcPlayerController
import com.framenest.ui.theme.FrameNestTheme

/**
 * Debug/spike entry for FN-01 libVLC validation.
 *
 * Launch:
 * ```
 * adb shell am start -n com.framenest/.feature.player.PlayerSpikeActivity
 * ```
 *
 * Optional SMB extras (credentials never required in URI):
 * ```
 * adb shell am start -n com.framenest/.feature.player.PlayerSpikeActivity \
 *   --es smb_host 192.168.1.10 \
 *   --es smb_share media \
 *   --es smb_path samples/movie.mkv \
 *   --es smb_user myuser \
 *   --es smb_password '***' \
 *   --es smb_domain WORKGROUP
 * ```
 * Prefer injecting password via a local-only mechanism; do not commit real values.
 */
class PlayerSpikeActivity : ComponentActivity() {

    private var controller: VlcPlayerController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val player = VlcPlayerController(applicationContext, enableHwDecoder = true)
        controller = player

        val mediaSource = resolveMediaSource()

        setContent {
            FrameNestTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by player.state.collectAsState()
                    val rememberedController = remember { player }

                    LaunchedEffect(mediaSource) {
                        rememberedController.prepare(mediaSource)
                    }

                    PlayerSpikeScreen(
                        controller = rememberedController,
                        state = state,
                        onClose = { finish() },
                        modifier = Modifier.safeDrawingPadding(),
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Keep instance for configuration; pause to free audio focus while backgrounded.
        controller?.pause()
    }

    override fun onDestroy() {
        controller?.release()
        controller = null
        super.onDestroy()
    }

    private fun resolveMediaSource(): MediaSource {
        val host = intent.getStringExtra(EXTRA_SMB_HOST)
        val share = intent.getStringExtra(EXTRA_SMB_SHARE)
        if (!host.isNullOrBlank() && !share.isNullOrBlank()) {
            val path = intent.getStringExtra(EXTRA_SMB_PATH).orEmpty()
            val uri = com.framenest.player.SmbMediaUri.build(host, share, path)
            val user = intent.getStringExtra(EXTRA_SMB_USER)
            val password = intent.getStringExtra(EXTRA_SMB_PASSWORD)
            val domain = intent.getStringExtra(EXTRA_SMB_DOMAIN)
            val credentials = if (!user.isNullOrEmpty() && password != null) {
                com.framenest.player.SmbCredentials(
                    username = user,
                    password = password,
                    domain = domain,
                )
            } else {
                null
            }
            // Never log password / full credentials.
            return MediaSource.Smb(uri = uri, credentials = credentials)
        }
        return MediaSource.RawResource(R.raw.sample_h264)
    }

    companion object {
        const val EXTRA_SMB_HOST = "smb_host"
        const val EXTRA_SMB_SHARE = "smb_share"
        const val EXTRA_SMB_PATH = "smb_path"
        const val EXTRA_SMB_USER = "smb_user"
        const val EXTRA_SMB_PASSWORD = "smb_password"
        const val EXTRA_SMB_DOMAIN = "smb_domain"
    }
}
