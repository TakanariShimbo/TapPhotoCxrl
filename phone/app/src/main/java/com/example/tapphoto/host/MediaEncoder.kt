package com.example.tapphoto.host

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import org.jcodec.api.android.AndroidSequenceEncoder
import org.jcodec.common.io.NIOUtils
import org.jcodec.common.model.Rational
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

private const val TAG = "MediaEncoder"

private const val MIME_AUDIO = "audio/mp4a-latm"
private const val AUDIO_BITRATE = 64_000
private const val AUDIO_SAMPLE_RATE = 16_000
private const val AUDIO_CHANNELS = 1
private const val AUDIO_BYTES_PER_FRAME = 2 * AUDIO_CHANNELS  // 16-bit
private const val DEQUEUE_TIMEOUT_US = 10_000L
private const val PCM_READ_CHUNK = 4_096
private const val SAMPLE_BUFFER_BYTES = 1024 * 1024

/**
 * All encoding helpers used by [MediaSaver]:
 *   - [encodeVideoMp4]      JPEG frame list → video-only MP4 (jcodec)
 *   - [encodePcmToAacMp4]   raw PCM         → audio-only MP4 (MediaCodec/AAC)
 *   - [combineAvMp4]        v.mp4 + a.mp4   → muxed MP4 (no re-encode)
 *
 * Splitting video and audio passes sidesteps MediaCodec H.264 color/stride
 * pitfalls — both proven paths are kept untouched.
 */
object MediaEncoder {

    /**
     * JPEG frame list → video-only MP4 with gap-fill. `periodMs` is the
     * authoritative glass-side capture period (received in `capture_start`),
     * used to detect dropped frames and to set the playback FPS so the
     * encoded video runs at real time.
     */
    fun encodeVideoMp4(frames: List<GlassFrame>, periodMs: Long, outputFile: File) {
        val period = periodMs.coerceAtLeast(1L)
        val expanded = expandWithGapFill(frames, period)
        val fps = computePlaybackFps(period)
        Log.d(TAG, "encodeVideoMp4 origFrames=${frames.size} expanded=${expanded.size} period=${period}ms fps=${fps.num}/${fps.den}")
        val out = NIOUtils.writableChannel(outputFile)
        try {
            val encoder = AndroidSequenceEncoder(out, fps)
            // Decode each unique frame once; reuse for duplicates that fill gaps.
            var lastJpeg: ByteArray? = null
            var lastBitmap: Bitmap? = null
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
     * PCM (16 kHz / mono / 16-bit) → AAC LC inside an MP4 container.
     */
    fun encodePcmToAacMp4(pcmFile: File, outputMp4: File) {
        if (!pcmFile.exists() || pcmFile.length() <= 0L) {
            throw IllegalArgumentException("encodePcmToAacMp4: empty pcm")
        }
        val format = MediaFormat.createAudioFormat(MIME_AUDIO, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MIME_AUDIO)
        val muxer = MediaMuxer(outputMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        var trackIdx = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()
        var sampleFrames = 0L
        var sawEos = false

        fun drain(blocking: Boolean): Boolean {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (blocking) DEQUEUE_TIMEOUT_US else 0L)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIdx = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    idx >= 0 -> {
                        val output = codec.getOutputBuffer(idx)
                        val isCodecConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (output != null && info.size > 0 && !isCodecConfig && muxerStarted) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIdx, output, info)
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawEos = true
                            return true
                        }
                    }
                }
            }
        }

        try {
            FileInputStream(pcmFile).use { input ->
                val buf = ByteArray(PCM_READ_CHUNK)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    var offset = 0
                    while (offset < read) {
                        val inputIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (inputIdx < 0) {
                            drain(false)
                            continue
                        }
                        val inputBuffer = codec.getInputBuffer(inputIdx)!!
                        inputBuffer.clear()
                        val chunk = minOf(read - offset, inputBuffer.capacity())
                        inputBuffer.put(buf, offset, chunk)
                        val ptsUs = sampleFrames * 1_000_000L / AUDIO_SAMPLE_RATE
                        codec.queueInputBuffer(inputIdx, 0, chunk, ptsUs, 0)
                        sampleFrames += chunk / AUDIO_BYTES_PER_FRAME
                        offset += chunk
                        drain(false)
                    }
                }
            }
            // EOS
            val inputIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIdx >= 0) {
                val ptsUs = sampleFrames * 1_000_000L / AUDIO_SAMPLE_RATE
                codec.queueInputBuffer(inputIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            while (!sawEos) {
                if (!drain(true)) break
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
        Log.d(TAG, "encodePcmToAacMp4: $sampleFrames frames -> ${outputMp4.length()} bytes (${outputMp4.absolutePath})")
    }

    /**
     * Copies the first video track of [videoMp4] and the first audio track
     * of [audioMp4] into [outputMp4] verbatim (no re-encode). Both source
     * files retain their original PTS.
     */
    fun combineAvMp4(videoMp4: File, audioMp4: File, outputMp4: File) {
        val outMuxer = MediaMuxer(outputMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val videoEx = MediaExtractor()
        val audioEx = MediaExtractor()
        try {
            videoEx.setDataSource(videoMp4.absolutePath)
            audioEx.setDataSource(audioMp4.absolutePath)
            val vSrcIdx = findTrack(videoEx, "video/")
                ?: throw IllegalStateException("no video track in $videoMp4")
            val aSrcIdx = findTrack(audioEx, "audio/")
                ?: throw IllegalStateException("no audio track in $audioMp4")
            videoEx.selectTrack(vSrcIdx)
            audioEx.selectTrack(aSrcIdx)
            val videoFormat = videoEx.getTrackFormat(vSrcIdx)
            val audioFormat = audioEx.getTrackFormat(aSrcIdx)
            val vDstIdx = outMuxer.addTrack(videoFormat)
            val aDstIdx = outMuxer.addTrack(audioFormat)
            outMuxer.start()

            copyTrack(videoEx, outMuxer, vDstIdx)
            copyTrack(audioEx, outMuxer, aDstIdx)

            outMuxer.stop()
        } finally {
            runCatching { videoEx.release() }
            runCatching { audioEx.release() }
            runCatching { outMuxer.release() }
        }
        Log.d(TAG, "combineAvMp4: ${videoMp4.length()} + ${audioMp4.length()} -> ${outputMp4.length()} bytes")
    }

    /**
     * Where consecutive timestamps are >1.5× the period apart, repeat the
     * previous frame to fill. Each duplicate keeps the original `jpeg`
     * reference so the encoder can avoid re-decoding (see encodeVideoMp4).
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

    private fun ensureEvenDimensions(src: Bitmap): Bitmap {
        val w = src.width and 1.inv()
        val h = src.height and 1.inv()
        return if (w == src.width && h == src.height) src
        else Bitmap.createBitmap(src, 0, 0, w, h)
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return null
    }

    private fun copyTrack(extractor: MediaExtractor, muxer: MediaMuxer, dstTrackIdx: Int) {
        val buffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            info.offset = 0
            info.size = sampleSize
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(dstTrackIdx, buffer, info)
            extractor.advance()
        }
    }
}
