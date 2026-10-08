package com.framenest.feature.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Minimal correct audio focus for video playback.
 * Pause is requested on transient/permanent loss via [onFocusLost].
 */
internal class PlayerAudioFocus(
    context: Context,
    private val onFocusLost: () -> Unit,
    private val onFocusGained: () -> Unit,
) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus: Boolean = false
    private var waitingForDelayedGain: Boolean = false

    private var requestGeneration = 0L

    // A callback queued by an abandoned request must not grant a newer request.
    private fun listener(generation: Long) = AudioManager.OnAudioFocusChangeListener { change ->
        if (generation != requestGeneration || focusRequest == null) return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                hasFocus = false
                waitingForDelayedGain = false
                onFocusLost()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                val shouldStartPlayback = waitingForDelayedGain
                hasFocus = true
                waitingForDelayedGain = false
                if (shouldStartPlayback) onFocusGained()
            }
        }
    }

    fun request(): AudioFocusRequestResult {
        if (hasFocus) return AudioFocusRequestResult.Granted
        // minSdk is 26. Retire the old listener before creating a replacement.
        abandon()
        val generation = requestGeneration
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setOnAudioFocusChangeListener(
                listener(generation),
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
            .setAcceptsDelayedFocusGain(true)
            .build()
        focusRequest = req
        val result = audioManager.requestAudioFocus(req)
        val mapped = audioFocusRequestResult(result)
        hasFocus = mapped == AudioFocusRequestResult.Granted
        waitingForDelayedGain = mapped == AudioFocusRequestResult.Delayed
        return mapped
    }

    fun abandon() {
        // Invalidate before the platform call: it may enqueue a final callback.
        requestGeneration++
        val request = focusRequest
        focusRequest = null
        hasFocus = false
        waitingForDelayedGain = false
        request?.let { audioManager.abandonAudioFocusRequest(it) }
    }
}

internal enum class AudioFocusRequestResult {
    Granted,
    Delayed,
    Failed,
}

internal fun audioFocusRequestResult(result: Int): AudioFocusRequestResult =
    when (result) {
        AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> AudioFocusRequestResult.Granted
        AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> AudioFocusRequestResult.Delayed
        else -> AudioFocusRequestResult.Failed
    }
