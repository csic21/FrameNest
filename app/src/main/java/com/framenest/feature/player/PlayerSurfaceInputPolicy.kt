package com.framenest.feature.player

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType

/** Pure decisions shared by pointer, accessibility and hardware-key input. */
internal object PlayerSurfaceInputPolicy {
    enum class GestureEndReason { Release, Cancel }

    data class GestureEndAction(
        val commitSeek: Boolean = false,
        val clearSeekUi: Boolean = false,
        val endLevelGesture: Boolean = false,
    )

    fun gestureEndAction(
        axis: GestureSeekMath.Axis,
        reason: GestureEndReason,
    ): GestureEndAction = when (axis) {
        GestureSeekMath.Axis.Horizontal -> GestureEndAction(
            commitSeek = reason == GestureEndReason.Release,
            clearSeekUi = true,
        )
        GestureSeekMath.Axis.Vertical -> GestureEndAction(endLevelGesture = true)
        GestureSeekMath.Axis.None -> GestureEndAction()
    }

    enum class KeyAction {
        Activate,
        SkipBack,
        SkipForward,
        Consume,
        Ignore,
    }

    fun keyAction(key: Key, type: KeyEventType): KeyAction {
        val pressedAction = when (key) {
            Key.Spacebar,
            Key.Enter,
            -> KeyAction.Activate
            Key.DirectionLeft -> KeyAction.SkipBack
            Key.DirectionRight -> KeyAction.SkipForward
            else -> return KeyAction.Ignore
        }
        return if (type == KeyEventType.KeyDown) {
            pressedAction
        } else {
            // Consume key-up too so focus navigation or another layer cannot
            // perform a second, conflicting action.
            KeyAction.Consume
        }
    }
}
