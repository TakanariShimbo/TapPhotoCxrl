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
import java.io.FileInputStream
import java.io.OutputStream

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
        periodMs: Long,
        displayName: String,
    ): Uri? = withContext(Dispatchers.IO) {
        if (frames.isEmpty()) return@withContext null
        val tempFile = File(ctx.cacheDir, "stream_encode_${System.currentTimeMillis()}.mp4")
        try {
            encodeMp4(frames, periodMs, tempFile)
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

    suspend fun saveAudio(
        ctx: Context,
        snapshot: VoiceSnapshot,
        displayName: String,
    ): Uri? = withContext(Dispatchers.IO) {
        if (!snapshot.pcmFile.exists() || snapshot.byteCount <= 0L) {
            Log.w(TAG, "saveAudio: empty pcm")
            return@withContext null
        }
        val resolver = ctx.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/$SUBDIR")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: run {
            Log.w(TAG, "saveAudio: insert returned null")
            return@withContext null
        }
        try {
            resolver.openOutputStream(uri)?.use { out ->
                writePcmAsWav(out, snapshot.pcmFile, snapshot.byteCount)
            } ?: run {
                resolver.delete(uri, null, null)
                return@withContext null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (t: Throwable) {
            Log.w(TAG, "saveAudio failed", t)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /** Writes a 44-byte RIFF/WAVE header followed by the raw PCM bytes. */
    private fun writePcmAsWav(out: OutputStream, pcm: File, pcmSize: Long) {
        val sampleRate = 16_000
        val channels: Short = 1
        val bitsPerSample: Short = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        out.write(buildWavHeader(pcmSize, sampleRate, channels, bitsPerSample, byteRate))
        FileInputStream(pcm).use { it.copyTo(out) }
    }

    private fun buildWavHeader(
        totalAudioLen: Long,
        sampleRate: Int,
        channels: Short,
        bitsPerSample: Short,
        byteRate: Int,
    ): ByteArray {
        val totalDataLen = totalAudioLen + 36
        val h = ByteArray(44)
        // "RIFF" + size + "WAVE"
        h[0] = 'R'.code.toByte(); h[1] = 'I'.code.toByte(); h[2] = 'F'.code.toByte(); h[3] = 'F'.code.toByte()
        h[4] = (totalDataLen and 0xff).toByte()
        h[5] = ((totalDataLen shr 8) and 0xff).toByte()
        h[6] = ((totalDataLen shr 16) and 0xff).toByte()
        h[7] = ((totalDataLen shr 24) and 0xff).toByte()
        h[8] = 'W'.code.toByte(); h[9] = 'A'.code.toByte(); h[10] = 'V'.code.toByte(); h[11] = 'E'.code.toByte()
        // "fmt " subchunk
        h[12] = 'f'.code.toByte(); h[13] = 'm'.code.toByte(); h[14] = 't'.code.toByte(); h[15] = ' '.code.toByte()
        h[16] = 16; h[17] = 0; h[18] = 0; h[19] = 0          // subchunk size = 16 (PCM)
        h[20] = 1; h[21] = 0                                  // audio format = 1 (PCM)
        h[22] = channels.toByte(); h[23] = 0
        h[24] = (sampleRate and 0xff).toByte()
        h[25] = ((sampleRate shr 8) and 0xff).toByte()
        h[26] = ((sampleRate shr 16) and 0xff).toByte()
        h[27] = ((sampleRate shr 24) and 0xff).toByte()
        h[28] = (byteRate and 0xff).toByte()
        h[29] = ((byteRate shr 8) and 0xff).toByte()
        h[30] = ((byteRate shr 16) and 0xff).toByte()
        h[31] = ((byteRate shr 24) and 0xff).toByte()
        h[32] = ((channels * bitsPerSample / 8) and 0xff).toByte()
        h[33] = 0
        h[34] = bitsPerSample.toByte(); h[35] = 0
        // "data" subchunk
        h[36] = 'd'.code.toByte(); h[37] = 'a'.code.toByte(); h[38] = 't'.code.toByte(); h[39] = 'a'.code.toByte()
        h[40] = (totalAudioLen and 0xff).toByte()
        h[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        h[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        h[43] = ((totalAudioLen shr 24) and 0xff).toByte()
        return h
    }

    private fun encodeMp4(frames: List<GlassFrame>, periodMs: Long, outputFile: File) {
        // Glass only sends frames it managed to push over BT; drops appear as
        // gaps in the timestamp sequence. Hold the previous frame across each
        // gap so playback runs at real time and "freezes" on dropped regions
        // instead of speeding up. periodMs is the glass-side capture period
        // (received in stream_start) — authoritative, not estimated.
        val period = periodMs.coerceAtLeast(1L)
        val expanded = expandWithGapFill(frames, period)
        val fps = computePlaybackFps(period)
        Log.d(TAG, "encodeMp4 origFrames=${frames.size} expanded=${expanded.size} period=${period}ms fps=${fps.num}/${fps.den}")
        val out = NIOUtils.writableChannel(outputFile)
        try {
            val encoder = AndroidSequenceEncoder(out, fps)
            // Decode each unique frame once; reuse for duplicates that fill gaps.
            var lastJpeg: ByteArray? = null
            var lastBitmap: android.graphics.Bitmap? = null
            for (frame in expanded) {
                val bmp = if (frame.jpeg === lastJpeg && lastBitmap != null && !lastBitmap.isRecycled) {
                    lastBitmap
                } else {
                    lastBitmap?.recycle()
                    val decoded = GlassImage.decode(frame) ?: continue
                    lastJpeg = frame.jpeg
                    lastBitmap = decoded
                    decoded
                }
                val even = ensureEvenDimensions(bmp)
                encoder.encodeImage(even)
                if (even !== bmp) even.recycle()
            }
            lastBitmap?.recycle()
            encoder.finish()
        } finally {
            NIOUtils.closeQuietly(out)
        }
    }

    /**
     * Where consecutive timestamps are >1.5× the period apart, repeat the
     * previous frame to fill. Each duplicate keeps the original `jpeg`
     * reference so the encoder can avoid re-decoding (see encodeMp4).
     */
    private fun expandWithGapFill(frames: List<GlassFrame>, periodMs: Long): List<GlassFrame> {
        if (frames.size < 2) return frames
        val threshold = (periodMs * 3) / 2
        val out = ArrayList<GlassFrame>(frames.size)
        for (i in frames.indices) {
            out.add(frames[i])
            if (i == frames.size - 1) continue
            val actualGap = frames[i + 1].timestampMs - frames[i].timestampMs
            if (actualGap > threshold) {
                val nFill = ((actualGap / periodMs) - 1).toInt().coerceAtLeast(0)
                repeat(nFill) { out.add(frames[i]) }
            }
        }
        return out
    }

    /**
     * Playback FPS = 1000 / periodMs (clamped). After expandWithGapFill the
     * frame list is uniform at periodMs intervals, so encoding at this rate
     * yields real-time playback.
     */
    private fun computePlaybackFps(periodMs: Long): Rational {
        val fpsX100 = (100_000L / periodMs).toInt().coerceIn(50, 6000)
        return Rational.R(fpsX100, 100)
    }

    private fun ensureEvenDimensions(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val w = src.width and 1.inv()
        val h = src.height and 1.inv()
        return if (w == src.width && h == src.height) src
        else android.graphics.Bitmap.createBitmap(src, 0, 0, w, h)
    }
}
