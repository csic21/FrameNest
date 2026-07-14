package com.framenest.smb.spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.framenest.ui.theme.FrameNestTheme

/**
 * Isolated FN-02 harness. Launch:
 * ```
 * adb shell am start -n com.framenest/.smb.spike.SmbSpikeActivity
 * ```
 * Does not own the product navigation shell (FN-03).
 */
class SmbSpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FrameNestTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SmbSpikeScreen()
                }
            }
        }
    }
}
