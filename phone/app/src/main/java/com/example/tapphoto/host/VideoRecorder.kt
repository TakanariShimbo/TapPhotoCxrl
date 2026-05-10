package com.example.tapphoto.host

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Buffers video frames in memory while a VIDEO or MOVIE session is running.
 * The user can save the buffer to MP4 after the session ends. Cleared on a
 * new video session start or on explicit clear (e.g., after a successful
 * save, or when a different session kind takes over).
 */
object VideoRecorder {
    private val frames = mutableListOf<GlassFrame>()

    @Volatile
    private var _periodMs: Long = 1000L
    /** Capture period reported by glass at capture_start. Used by MediaSaver for gap-fill and playback FPS. */
    val periodMs: Long get() = _periodMs

    private val _frameCount = MutableStateFlow(0)
    val frameCount: StateFlow<Int> = _frameCount.asStateFlow()

    fun startNewSession(periodMs: Long) {
        synchronized(frames) {
            frames.clear()
            _frameCount.value = 0
            _periodMs = periodMs
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
