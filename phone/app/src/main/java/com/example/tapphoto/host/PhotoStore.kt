package com.example.tapphoto.host

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the most recent frame received from the glass.
 *  - `latest`: decoded + rotated ImageBitmap for display
 *  - `latestFrame`: raw GlassFrame (JPEG bytes + metadata) for save-to-disk
 *  - `capturedAt`: glass-side capture timestamp (from frame.timestampMs)
 */
object PhotoStore {
    private val _latest = MutableStateFlow<ImageBitmap?>(null)
    val latest: StateFlow<ImageBitmap?> = _latest.asStateFlow()

    private val _capturedAt = MutableStateFlow<Long?>(null)
    val capturedAt: StateFlow<Long?> = _capturedAt.asStateFlow()

    private val _latestFrame = MutableStateFlow<GlassFrame?>(null)
    val latestFrame: StateFlow<GlassFrame?> = _latestFrame.asStateFlow()

    fun set(image: ImageBitmap, frame: GlassFrame) {
        _latest.value = image
        _capturedAt.value = frame.timestampMs
        _latestFrame.value = frame
    }

    fun clear() {
        _latest.value = null
        _capturedAt.value = null
        _latestFrame.value = null
    }
}
