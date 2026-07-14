package com.framenest.feature.listen_translate.spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
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
 * FN-10 debug spike: libVLC playback + parallel MediaCodec PCM tap.
 *
 * ```
 * adb shell am start -n com.framenest/.feature.listen_translate.spike.PcmCaptureSpikeActivity
 * ```
 *
 * Logcat: `adb logcat -s FrameNestPcmTap FrameNestPlayer`
 */
class PcmCaptureSpikeActivity : ComponentActivity() {

    private var controller: VlcPlayerController? = null
    private var pcmTap: MediaCodecPcmTap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val player = VlcPlayerController(applicationContext, enableHwDecoder = true)
        controller = player
        val tap = MediaCodecPcmTap(applicationContext)
        pcmTap = tap

        setContent {
            FrameNestTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val playerState by player.state.collectAsState()
                    val pcmStats by tap.stats.collectAsState()
                    val remembered = remember { player }

                    LaunchedEffect(Unit) {
                        remembered.prepare(MediaSource.RawResource(R.raw.sample_h264))
                    }

                    DisposableEffect(Unit) {
                        // Start PCM decode as soon as the activity is up; independent of VLC.
                        tap.startFromRaw(R.raw.sample_h264)
                        onDispose {
                            tap.stop()
                        }
                    }

                    PcmCaptureSpikeScreen(
                        controller = remembered,
                        playerState = playerState,
                        pcmStats = pcmStats,
                        onClose = { finish() },
                        onRestartPcm = {
                            tap.stop()
                            tap.startFromRaw(R.raw.sample_h264)
                        },
                        modifier = Modifier.safeDrawingPadding(),
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        controller?.pause()
    }

    override fun onDestroy() {
        pcmTap?.stop()
        pcmTap = null
        controller?.release()
        controller = null
        super.onDestroy()
    }
}
