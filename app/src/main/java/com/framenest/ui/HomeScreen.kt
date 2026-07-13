package com.framenest.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.framenest.navigation.FrameNestApp

/**
 * Compatibility entry used by previews/tests that still reference HomeScreen.
 * FN-03 replaces the empty home with the adaptive navigation shell.
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    FrameNestApp(modifier = modifier)
}
