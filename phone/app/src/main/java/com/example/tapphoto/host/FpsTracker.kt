package com.example.tapphoto.host

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val WINDOW = 8

object FpsTracker {
    private val timestamps = ArrayDeque<Long>(WINDOW)

    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps.asStateFlow()

    @Synchronized
    fun tick(now: Long = System.currentTimeMillis()) {
        timestamps.addLast(now)
        while (timestamps.size > WINDOW) timestamps.removeFirst()
        _fps.value = compute()
    }

    @Synchronized
    fun reset() {
        timestamps.clear()
        _fps.value = 0f
    }

    private fun compute(): Float {
        if (timestamps.size < 2) return 0f
        val span = timestamps.last() - timestamps.first()
        if (span <= 0) return 0f
        return (timestamps.size - 1) * 1000f / span
    }
}
