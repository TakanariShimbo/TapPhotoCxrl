package com.example.tapphoto.client

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlin.math.abs

private const val TAG = "GlassCamera"
const val STREAM_FRAME_PERIOD_MS = 1000L  // tick-to-tick interval; independent of capture/send latency. Sent to phone in stream_start so playback period matches capture.
private const val WARMUP_MS = 700L  // preview frames before first capture so AE/AWB can converge

typealias FrameCallback = (jpeg: ByteArray, width: Int, height: Int, rotation: Int, captureTs: Long) -> Unit
typealias ErrorCallback = (reason: String) -> Unit

/**
 * Glass-side Camera2 wrapper. Bypasses the Hi Rokid takePhoto pipeline so the
 * system photo overlay never shows up.
 *
 * Two entry points:
 *   - takeShot(...)   — single JPEG (one open/close cycle)
 *   - startStream(...) / stopStream() — repeating JPEGs until stopped
 *
 * Only one operation runs at a time. Both deliver bytes via a callback that
 * also reports the frame's rotation (= camera SENSOR_ORIENTATION). The
 * caller is expected to apply that rotation on the receiving side.
 */
object GlassCamera {

    private enum class Mode { IDLE, SHOT, STREAM }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var mode = Mode.IDLE
    private var width = 0
    private var height = 0
    private var jpegQuality: Byte = 80
    private var sensorOrientation = 0
    private var onFrame: FrameCallback? = null
    private var onError: ErrorCallback? = null
    private var nextStreamTickAt = 0L

    private val streamTickRunnable = object : Runnable {
        override fun run() {
            if (mode != Mode.STREAM) return
            capture()
            val now = SystemClock.uptimeMillis()
            nextStreamTickAt += STREAM_FRAME_PERIOD_MS
            if (nextStreamTickAt < now) nextStreamTickAt = now  // fast-forward if we fell behind
            handler?.postAtTime(this, nextStreamTickAt)
        }
    }

    fun takeShot(
        ctx: Context,
        width: Int,
        height: Int,
        quality: Int,
        onSuccess: FrameCallback,
        onError: ErrorCallback,
    ) {
        if (!begin(ctx, Mode.SHOT, width, height, quality, onSuccess, onError)) return
        handler!!.post { openCamera(ctx.applicationContext) }
    }

    fun startStream(
        ctx: Context,
        width: Int,
        height: Int,
        quality: Int,
        onFrame: FrameCallback,
        onError: ErrorCallback,
    ) {
        if (!begin(ctx, Mode.STREAM, width, height, quality, onFrame, onError)) return
        handler!!.post { openCamera(ctx.applicationContext) }
    }

    fun stopStream() {
        if (mode != Mode.STREAM) return
        Log.d(TAG, "stopStream requested")
        val h = handler ?: return
        h.post { closeAll() }
    }

    // ---- internals ----

    private fun begin(
        ctx: Context,
        m: Mode,
        targetW: Int,
        targetH: Int,
        quality: Int,
        frameCb: FrameCallback,
        errCb: ErrorCallback,
    ): Boolean {
        if (mode != Mode.IDLE) {
            Log.w(TAG, "$m rejected: busy (mode=$mode)")
            errCb("busy")
            return false
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "CAMERA permission not granted")
            errCb("permission")
            return false
        }
        mode = m
        width = targetW
        height = targetH
        jpegQuality = quality.coerceIn(1, 100).toByte()
        onFrame = frameCb
        onError = errCb
        thread = HandlerThread("glass-camera").apply { start() }
        handler = Handler(thread!!.looper)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(ctx: Context) {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = try {
            mgr.cameraIdList.firstOrNull()
        } catch (t: Throwable) {
            Log.e(TAG, "cameraIdList failed", t)
            null
        }
        if (id == null) {
            fail("no camera id")
            return
        }
        val ch = mgr.getCameraCharacteristics(id)
        sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        val targetAspect = width.toFloat() / height.toFloat()
        val matchAspect = sizes.filter {
            abs(it.width.toFloat() / it.height.toFloat() - targetAspect) < 0.05f
        }
        val chosen = (matchAspect.takeIf { it.isNotEmpty() } ?: sizes)
            .minByOrNull { abs(it.width - width) + abs(it.height - height) }
            ?: Size(640, 480)
        width = chosen.width
        height = chosen.height
        Log.d(TAG, "open id=$id mode=$mode size=${width}x${height} rot=$sensorOrientation")

        previewTexture = SurfaceTexture(0).apply { setDefaultBufferSize(width, height) }
        previewSurface = Surface(previewTexture)

        reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                val bytes = try {
                    val buf = img.planes[0].buffer
                    ByteArray(buf.remaining()).also { buf.get(it) }
                } finally {
                    img.close()
                }
                onImageBytes(bytes)
            }, handler)
        }

        try {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    createSession()
                }

                override fun onDisconnected(d: CameraDevice) {
                    Log.w(TAG, "camera disconnected")
                    d.close()
                    fail("disconnected")
                }

                override fun onError(d: CameraDevice, error: Int) {
                    Log.e(TAG, "camera error code=$error")
                    d.close()
                    fail("error=$error")
                }
            }, handler)
        } catch (t: Throwable) {
            Log.e(TAG, "openCamera threw", t)
            fail("open-throw")
        }
    }

    private fun createSession() {
        val d = device ?: return
        val r = reader ?: return
        val p = previewSurface ?: return
        try {
            d.createCaptureSession(
                listOf(p, r.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        startPreview()
                        // Let AE/AWB settle on preview frames before the first
                        // still capture so the first JPEG isn't off-color.
                        handler?.postDelayed({
                            if (mode == Mode.IDLE) return@postDelayed
                            capture()
                            if (mode == Mode.STREAM) {
                                nextStreamTickAt = SystemClock.uptimeMillis() + STREAM_FRAME_PERIOD_MS
                                handler?.postAtTime(streamTickRunnable, nextStreamTickAt)
                            }
                        }, WARMUP_MS)
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.e(TAG, "session configure failed")
                        fail("configure-failed")
                    }
                },
                handler,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "createCaptureSession threw", t)
            fail("session-throw")
        }
    }

    private fun startPreview() {
        val d = device ?: return
        val s = session ?: return
        val p = previewSurface ?: return
        try {
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(p)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }
            s.setRepeatingRequest(req.build(), null, handler)
        } catch (t: Throwable) {
            Log.e(TAG, "startPreview failed", t)
        }
    }

    private val captureCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession,
            req: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            // bytes will arrive on the ImageReader listener
        }

        override fun onCaptureFailed(
            s: CameraCaptureSession,
            req: CaptureRequest,
            failure: CaptureFailure,
        ) {
            Log.w(TAG, "capture FAILED reason=${failure.reason}")
            when (mode) {
                Mode.SHOT -> fail("capture-failed")
                // STREAM: ticker keeps firing on its own; just log and wait for next tick.
                Mode.STREAM -> Unit
                Mode.IDLE -> Unit
            }
        }
    }

    private fun capture() {
        val d = device ?: return
        val s = session ?: return
        val r = reader ?: return
        if (mode == Mode.IDLE) return
        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(r.surface)
            set(CaptureRequest.JPEG_QUALITY, jpegQuality)
            set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }
        try {
            s.capture(req.build(), captureCb, handler)
        } catch (t: Throwable) {
            Log.e(TAG, "capture submit failed", t)
        }
    }

    private fun onImageBytes(bytes: ByteArray) {
        // Capture timestamp as close to actual frame creation as we can see
        // from the API surface. Phone uses this to place frames on a timeline
        // and detect drop-induced gaps.
        val captureTs = System.currentTimeMillis()
        when (mode) {
            Mode.SHOT -> {
                val cb = onFrame
                cb?.invoke(bytes, width, height, sensorOrientation, captureTs)
                closeAll()
            }
            Mode.STREAM -> {
                // Just deliver the frame. The next capture is driven by streamTickRunnable
                // on a fixed period, independent of how long the consumer (BT send) takes.
                onFrame?.invoke(bytes, width, height, sensorOrientation, captureTs)
            }
            Mode.IDLE -> Unit
        }
    }

    private fun fail(reason: String) {
        if (mode == Mode.IDLE) return
        val cb = onError
        closeAll()
        cb?.invoke(reason)
    }

    private fun closeAll() {
        mode = Mode.IDLE
        handler?.removeCallbacks(streamTickRunnable)
        try { session?.stopRepeating() } catch (_: Throwable) {}
        try { session?.close() } catch (_: Throwable) {}
        try { device?.close() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { previewSurface?.release() } catch (_: Throwable) {}
        try { previewTexture?.release() } catch (_: Throwable) {}
        session = null
        device = null
        reader = null
        previewSurface = null
        previewTexture = null
        thread?.quitSafely()
        thread = null
        handler = null
        onFrame = null
        onError = null
    }
}
