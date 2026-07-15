package com.framenest.feature.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

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

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
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
                if (shouldStartPlayback) {
                    onFocusGained()
                }
            }
        }
    }

    fun request(): AudioFocusRequestResult {
        if (hasFocus) return AudioFocusRequestResult.Granted
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setOnAudioFocusChangeListener(listener)
                .setAcceptsDelayedFocusGain(true)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        val mapped = audioFocusRequestResult(result)
        hasFocus = mapped == AudioFocusRequestResult.Granted
        waitingForDelayedGain = mapped == AudioFocusRequestResult.Delayed
        return mapped
    }

    fun abandon() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(listener)
        }
        hasFocus = false
        waitingForDelayedGain = false
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
