package com.example.tapphoto.client

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

private const val TAG = "CameraSfx"

object CameraSfx {
    private var generator: ToneGenerator? = null

    private fun ensure(): ToneGenerator? {
        return generator ?: runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        }.onFailure { Log.w(TAG, "ToneGenerator init failed", it) }
            .getOrNull()
            ?.also { generator = it }
    }

    fun playShutter() {
        ensure()?.startTone(ToneGenerator.TONE_PROP_BEEP2, 70)
    }

    fun playFail() {
        ensure()?.startTone(ToneGenerator.TONE_PROP_NACK, 120)
    }

    fun release() {
        generator?.release()
        generator = null
    }
}
