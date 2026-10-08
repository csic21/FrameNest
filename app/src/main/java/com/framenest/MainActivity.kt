package com.framenest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.framenest.navigation.FrameNestApp
import com.framenest.ui.theme.FrameNestTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FrameNestTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FrameNestApp(
                        updateController = (application as FrameNestApplication).updateController,
                    )
                }
            }
        }
    }
}
