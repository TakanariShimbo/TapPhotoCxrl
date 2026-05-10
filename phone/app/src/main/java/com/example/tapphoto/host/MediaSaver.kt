package com.example.tapphoto.host

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jcodec.api.android.AndroidSequenceEncoder
import org.jcodec.common.io.NIOUtils
import org.jcodec.common.model.Rational
import java.io.File

private const val TAG = "MediaSaver"
private const val SUBDIR = "TapPhotoCxrl"

/**
 * Saves photos and stream videos to MediaStore (Pictures/{SUBDIR} and
 * Movies/{SUBDIR} respectively). Both methods are blocking I/O — call from
 * Dispatchers.IO.
 */
object MediaSaver {

    suspend fun savePhoto(
        ctx: Context,
        frame: GlassFrame,
        displayName: String,
    ): Uri? = withContext(Dispatchers.IO) {
        val resolver = ctx.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$SUBDIR")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: run {
            Log.w(TAG, "savePhoto: insert returned null")
            return@withContext null
        }
        try {
            // Re-encode the rotated bitmap so the saved JPEG is upright on any viewer.
            val bitmap = GlassImage.decode(frame) ?: return@withContext null
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            } ?: run {
                resolver.delete(uri, null, null)
                return@withContext null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (t: Throwable) {
            Log.w(TAG, "savePhoto failed", t)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    suspend fun saveVideo(
        ctx: Context,
        frames: List<GlassFrame>,
        displayName: String,
    ): Uri? = withContext(Dispatchers.IO) {
        if (frames.isEmpty()) return@withContext null
        val tempFile = File(ctx.cacheDir, "stream_encode_${System.currentTimeMillis()}.mp4")
        try {
            encodeMp4(frames, tempFile)
        } catch (t: Throwable) {
            Log.w(TAG, "encodeMp4 failed", t)
            tempFile.delete()
            return@withContext null
        }

        val resolver = ctx.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/$SUBDIR")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: run {
            tempFile.delete()
            return@withContext null
        }
        try {
            resolver.openOutputStream(uri)?.use { out ->
                tempFile.inputStream().use { it.copyTo(out) }
            } ?: run {
                resolver.delete(uri, null, null)
                return@withContext null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (t: Throwable) {
            Log.w(TAG, "saveVideo copy failed", t)
            runCatching { resolver.delete(uri, null, null) }
            null
        } finally {
            tempFile.delete()
        }
    }

    private fun encodeMp4(frames: List<GlassFrame>, outputFile: File) {
        val fps = computePlaybackFps(frames)
        Log.d(TAG, "encodeMp4 frames=${frames.size} fps=${fps.num}/${fps.den}")
        val out = NIOUtils.writableChannel(outputFile)
        try {
            val encoder = AndroidSequenceEncoder(out, fps)
            for (frame in frames) {
                val bitmap = GlassImage.decode(frame) ?: continue
                // H.264 requires even dimensions — pad if odd by cropping by 1 pixel.
                val even = ensureEvenDimensions(bitmap)
                encoder.encodeImage(even)
                if (even !== bitmap) bitmap.recycle()
                even.recycle()
            }
            encoder.finish()
        } finally {
            NIOUtils.closeQuietly(out)
        }
    }

    /**
     * Average capture rate over the recorded frame timestamps. Uses fixed-rate
     * playback at this average so the resulting video runs at real time
     * (within transmission jitter). Returned as Rational.R(fps * 100, 100) for
     * sub-fps precision; coerced to 0.5–60 fps.
     */
    private fun computePlaybackFps(frames: List<GlassFrame>): Rational {
        if (frames.size < 2) return Rational.R(1, 1)
        val durationMs = frames.last().timestampMs - frames.first().timestampMs
        if (durationMs <= 0) return Rational.R(1, 1)
        val fpsX100 = ((frames.size - 1) * 100_000L / durationMs)
            .toInt()
            .coerceIn(50, 6000)
        return Rational.R(fpsX100, 100)
    }

    private fun ensureEvenDimensions(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val w = src.width and 1.inv()
        val h = src.height and 1.inv()
        return if (w == src.width && h == src.height) src
        else android.graphics.Bitmap.createBitmap(src, 0, 0, w, h)
    }
}
