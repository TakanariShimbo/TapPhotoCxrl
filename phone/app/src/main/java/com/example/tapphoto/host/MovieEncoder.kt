package com.example.tapphoto.host

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

private const val TAG = "MovieEncoder"

private const val MIME_AUDIO = "audio/mp4a-latm"
private const val AUDIO_BITRATE = 64_000
private const val AUDIO_SAMPLE_RATE = 16_000
private const val AUDIO_CHANNELS = 1
private const val AUDIO_BYTES_PER_FRAME = 2 * AUDIO_CHANNELS  // 16-bit
private const val DEQUEUE_TIMEOUT_US = 10_000L
private const val PCM_READ_CHUNK = 4_096
private const val SAMPLE_BUFFER_BYTES = 1024 * 1024

/**
 * MOVIE save helpers. Approach: make a video-only MP4 (jcodec, see
 * [MediaSaver.encodeMp4Video]) + an audio-only MP4 (AAC) here, then mux the
 * tracks of those two files into a single MP4 with MediaExtractor +
 * MediaMuxer. Doing the video and audio passes independently sidesteps the
 * encoder-color-format / stride pitfalls of feeding MediaCodec H.264 with
 * raw bitmaps directly — both proven paths (jcodec for video, MediaCodec for
 * AAC) are kept untouched.
 */
object MovieEncoder {

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
