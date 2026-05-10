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
 *
 * No `kind` field on the frame — photo vs continuous is decided by the
 * receiving session state (VideoRecorder.recording), not the frame itself.
 */
data class GlassFrame(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val timestampMs: Long,
)

/**
 * Parser + decoder for `frame` Caps payloads. Wire format:
 *   "event"  string  = "frame"
 *   "w"      int32
 *   "h"      int32
 *   "rot"    int32   sensor orientation (degrees, clockwise)
 *   "ts"     int64
 *   "data"   binary  JPEG bytes
 */
object GlassImage {

    fun parse(caps: Caps): GlassFrame? {
        val data = caps.readBinary(Wire.FIELD_DATA) ?: run {
            Log.w(TAG, "frame missing data binary")
            return null
        }
        return GlassFrame(
            jpeg = data,
            width = caps.readInt32(Wire.FIELD_W) ?: 0,
            height = caps.readInt32(Wire.FIELD_H) ?: 0,
            rotation = caps.readInt32(Wire.FIELD_ROT) ?: 0,
            timestampMs = caps.readInt64(Wire.FIELD_TS) ?: System.currentTimeMillis(),
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
}
