package com.example.tapphoto.host

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import com.rokid.cxr.Caps

private const val TAG = "GlassImage"

/**
 * Decodes a frame_jpeg payload that came over CustomCmd from the glass.
 *
 * Wire format (Caps from glass):
 *   "event"  string  = "frame_jpeg"
 *   "w"      int32
 *   "h"      int32
 *   "rot"    int32   sensor orientation (degrees, clockwise)
 *   "ts"     int64
 *   "data"   binary  JPEG bytes
 *
 * The decoder applies the rotation so callers receive an upright Bitmap.
 */
object GlassImage {

    fun decodeFrame(caps: Caps): Bitmap? {
        val jpeg = readBinary(caps, "data") ?: run {
            Log.w(TAG, "frame missing data binary")
            return null
        }
        val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: run {
            Log.w(TAG, "decode failed (size=${jpeg.size})")
            return null
        }
        val rotation = readInt32(caps, "rot") ?: 0
        return if (rotation != 0) rotate(raw, rotation) else raw
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

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
