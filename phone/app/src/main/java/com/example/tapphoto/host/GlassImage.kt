package com.example.tapphoto.host

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import com.rokid.cxr.Caps

private const val TAG = "GlassImage"

/**
 * One frame received from the glass over the `frame` event channel. The JPEG
 * bytes are kept verbatim for save-to-disk; rotation is applied lazily when
 * decoding to a Bitmap.
 */
data class GlassFrame(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val timestampMs: Long,
    val kind: String,   // "shot" or "stream"
)

/**
 * Parser + decoder for `frame` Caps payloads.
 *
 * Wire format (Caps from glass):
 *   "event"  string  = "frame"
 *   "kind"   string  "shot" | "stream"
 *   "w"      int32
 *   "h"      int32
 *   "rot"    int32   sensor orientation (degrees, clockwise)
 *   "ts"     int64
 *   "data"   binary  JPEG bytes
 */
object GlassImage {

    fun parse(caps: Caps): GlassFrame? {
        val data = readBinary(caps, "data") ?: run {
            Log.w(TAG, "frame missing data binary")
            return null
        }
        return GlassFrame(
            jpeg = data,
            width = readInt32(caps, "w") ?: 0,
            height = readInt32(caps, "h") ?: 0,
            rotation = readInt32(caps, "rot") ?: 0,
            timestampMs = readInt64(caps, "ts") ?: System.currentTimeMillis(),
            kind = readString(caps, "kind") ?: "shot",
        )
    }

    /** Decodes the JPEG and applies the frame's rotation. */
    fun decode(frame: GlassFrame): Bitmap? {
        val raw = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size) ?: run {
            Log.w(TAG, "decode failed (size=${frame.jpeg.size})")
            return null
        }
        return if (frame.rotation != 0) rotate(raw, frame.rotation) else raw
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (rotated !== src) src.recycle()
        return rotated
    }

    private fun readString(caps: Caps, fieldName: String): String? = runCatching {
        for (i in 0 until caps.size() - 1) {
            val key = caps.at(i)
            if (key.type() == Caps.Value.TYPE_STRING && key.string == fieldName) {
                val v = caps.at(i + 1)
                if (v.type() == Caps.Value.TYPE_STRING) return@runCatching v.string
            }
        }
        null
    }.getOrNull()

    private fun readInt32(caps: Caps, fieldName: String): Int? = runCatching {
        for (i in 0 until caps.size() - 1) {
            val key = caps.at(i)
            if (key.type() == Caps.Value.TYPE_STRING && key.string == fieldName) {
                val v = caps.at(i + 1)
                if (v.type() == Caps.Value.TYPE_INT32 || v.type() == Caps.Value.TYPE_UINT32) {
                    return@runCatching v.int
                }
            }
        }
        null
    }.getOrNull()

    private fun readInt64(caps: Caps, fieldName: String): Long? = runCatching {
        for (i in 0 until caps.size() - 1) {
            val key = caps.at(i)
            if (key.type() == Caps.Value.TYPE_STRING && key.string == fieldName) {
                val v = caps.at(i + 1)
                if (v.type() == Caps.Value.TYPE_INT64 || v.type() == Caps.Value.TYPE_UINT64) {
                    return@runCatching v.long
                }
            }
        }
        null
    }.getOrNull()

    private fun readBinary(caps: Caps, fieldName: String): ByteArray? = runCatching {
        for (i in 0 until caps.size() - 1) {
            val key = caps.at(i)
            if (key.type() == Caps.Value.TYPE_STRING && key.string == fieldName) {
                val v = caps.at(i + 1)
                if (v.type() == Caps.Value.TYPE_BINARY) {
                    val bin = v.binary
                    return@runCatching bin.data.copyOfRange(bin.offset, bin.offset + bin.length)
                }
            }
        }
        null
    }.getOrNull()
}
