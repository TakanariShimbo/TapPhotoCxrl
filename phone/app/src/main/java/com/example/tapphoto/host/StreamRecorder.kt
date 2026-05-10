package com.example.tapphoto.host

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Buffers stream frames in memory while a stream is running. The user can save
 * the buffer to MP4 after the stream ends. Cleared on new stream start or on
 * explicit clear (e.g., after a successful save).
 */
object StreamRecorder {
    private val frames = mutableListOf<GlassFrame>()

    private val _frameCount = MutableStateFlow(0)
    val frameCount: StateFlow<Int> = _frameCount.asStateFlow()

    fun startNewSession() {
        synchronized(frames) {
            frames.clear()
            _frameCount.value = 0
        }
    }

    fun add(frame: GlassFrame) {
        synchronized(frames) {
            frames.add(frame)
            _frameCount.value = frames.size
        }
    }

    fun snapshot(): List<GlassFrame> = synchronized(frames) { frames.toList() }

    fun clear() {
        synchronized(frames) {
            frames.clear()
            _frameCount.value = 0
        }
    }
}
