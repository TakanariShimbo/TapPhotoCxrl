package com.example.tapphoto.host

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PhotoStore {
    private val _latest = MutableStateFlow<ImageBitmap?>(null)
    val latest: StateFlow<ImageBitmap?> = _latest.asStateFlow()

    private val _capturedAt = MutableStateFlow<Long?>(null)
    val capturedAt: StateFlow<Long?> = _capturedAt.asStateFlow()

    fun set(image: ImageBitmap, ts: Long = System.currentTimeMillis()) {
        _latest.value = image
        _capturedAt.value = ts
    }

    fun clear() {
        _latest.value = null
        _capturedAt.value = null
    }
}
