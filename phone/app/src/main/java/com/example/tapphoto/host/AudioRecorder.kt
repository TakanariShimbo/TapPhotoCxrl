package com.example.tapphoto.host

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream

private const val TAG = "AudioRecorder"
private const val PCM_FILE_PREFIX = "audio_"
private const val PCM_FILE_EXT = ".pcm"

/**
 * Buffers an in-progress audio recording. Audio bytes (16 kHz mono 16-bit
 * signed PCM) arrive on the binder thread from Hi Rokid via IAudioStreamCbk
 * and are written incrementally to a temp file in cacheDir — keeping memory
 * flat regardless of recording length. On stop the file is left in place so
 * the user can hit Save (which converts PCM → WAV via MediaSaver).
 *
 * Lifecycle is parallel to VideoRecorder but stores a single PCM stream
 * instead of a frame list.
 */
data class AudioSnapshot(val pcmFile: File, val byteCount: Long)

object AudioRecorder {
    private val lock = Any()
    private var pcmFile: File? = null
    private var pcmStream: FileOutputStream? = null
    private var byteCount: Long = 0L

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _hasContent = MutableStateFlow(false)
    val hasContent: StateFlow<Boolean> = _hasContent.asStateFlow()

    fun startNewSession(context: Context) {
        synchronized(lock) {
            closeStreamLocked()
            deletePcmFileLocked()
            val dir = context.cacheDir
            val file = File(dir, "$PCM_FILE_PREFIX${System.currentTimeMillis()}$PCM_FILE_EXT")
            pcmFile = file
            pcmStream = FileOutputStream(file, false)
            byteCount = 0L
        }
        _hasContent.value = false
        _recording.value = true
        Log.d(TAG, "startNewSession pcm=${pcmFile?.absolutePath}")
    }

    fun stopRecording() {
        val (file, bytes) = synchronized(lock) {
            closeStreamLocked()
            pcmFile to byteCount
        }
        _recording.value = false
        _hasContent.value = file != null && bytes > 0L
        Log.d(TAG, "stopRecording bytes=$bytes file=${file?.absolutePath}")
    }

    /**
     * Called from binder thread (Hi Rokid SDK callback). Performs SDK-bug
     * guarding (offset/length out of range) before writing.
     */
    fun onAudioChunk(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        synchronized(lock) {
            val out = pcmStream ?: return
            val safeOffset = if (offset in 0 until data.size) offset else 0
            val maxAvail = data.size - safeOffset
            val safeLength = when {
                length in 1..maxAvail -> length
                maxAvail > 0 -> maxAvail
                else -> 0
            }
            if (safeLength <= 0) return
            runCatching {
                out.write(data, safeOffset, safeLength)
                byteCount += safeLength
            }.onFailure {
                Log.w(TAG, "pcm write failed offset=$offset length=$length", it)
            }
        }
    }

    fun snapshot(): AudioSnapshot? = synchronized(lock) {
        val file = pcmFile ?: return null
        if (!file.exists() || byteCount <= 0L) return null
        AudioSnapshot(file, byteCount)
    }

    fun clear() {
        synchronized(lock) {
            closeStreamLocked()
            deletePcmFileLocked()
            byteCount = 0L
        }
        _recording.value = false
        _hasContent.value = false
    }

    private fun closeStreamLocked() {
        runCatching {
            pcmStream?.flush()
            pcmStream?.fd?.sync()
        }
        runCatching { pcmStream?.close() }
        pcmStream = null
    }

    private fun deletePcmFileLocked() {
        pcmFile?.let { runCatching { it.delete() } }
        pcmFile = null
    }
}
