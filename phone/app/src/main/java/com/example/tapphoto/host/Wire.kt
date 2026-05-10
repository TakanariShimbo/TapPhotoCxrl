package com.example.tapphoto.host

/**
 * Wire vocabulary shared by phone and glass. Both sides must agree on these
 * exact strings; the constants live in each module to keep them gradle-local.
 */
internal object Wire {
    const val CHANNEL_TO_GLASS = "rk_custom_client"
    const val CHANNEL_FROM_GLASS = "rk_custom_key"

    const val EVENT_SESSION_OPEN = "session_open"
    const val EVENT_SESSION_CLOSE = "session_close"
    const val EVENT_PING = "ping"
    const val EVENT_FRAME = "frame"
    const val EVENT_CAPTURE_START = "capture_start"
    const val EVENT_CAPTURE_END = "capture_end"
    const val EVENT_MODE_CHANGE = "mode_change"

    const val FIELD_EVENT = "event"
    const val FIELD_TS = "ts"
    const val FIELD_KIND = "kind"
    const val FIELD_MODE = "mode"
    const val FIELD_PERIOD_MS = "period_ms"
    const val FIELD_W = "w"
    const val FIELD_H = "h"
    const val FIELD_ROT = "rot"
    const val FIELD_DATA = "data"
}
