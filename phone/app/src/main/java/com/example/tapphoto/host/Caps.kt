package com.example.tapphoto.host

import com.rokid.cxr.Caps

/**
 * Caps is positional: keys and values alternate. These helpers walk the
 * payload looking for `field` and return the immediately-following value if
 * it has the expected type, otherwise null.
 */
internal fun Caps.readEvent(): String? = readString(Wire.FIELD_EVENT)

internal fun Caps.readString(field: String): String? = readField(field) { v ->
    if (v.type() == Caps.Value.TYPE_STRING) v.string else null
}

internal fun Caps.readInt32(field: String): Int? = readField(field) { v ->
    if (v.type() == Caps.Value.TYPE_INT32 || v.type() == Caps.Value.TYPE_UINT32) v.int else null
}

internal fun Caps.readInt64(field: String): Long? = readField(field) { v ->
    if (v.type() == Caps.Value.TYPE_INT64 || v.type() == Caps.Value.TYPE_UINT64) v.long else null
}

internal fun Caps.readBinary(field: String): ByteArray? = readField(field) { v ->
    if (v.type() != Caps.Value.TYPE_BINARY) return@readField null
    val bin = v.binary
    bin.data.copyOfRange(bin.offset, bin.offset + bin.length)
}

private inline fun <T> Caps.readField(field: String, extract: (Caps.Value) -> T?): T? = runCatching {
    for (i in 0 until size() - 1) {
        val key = at(i)
        if (key.type() == Caps.Value.TYPE_STRING && key.string == field) {
            return@runCatching extract(at(i + 1))
        }
    }
    null
}.getOrNull()
