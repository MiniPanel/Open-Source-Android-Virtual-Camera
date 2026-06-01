package com.virtualcamera

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

enum class VirtualCameraState { IDLE, RUNNING, STOPPING, STOPPED, ERROR }

class CoreVirtualCamera(
    private val context: Context,
    private val videoWidth: Int,
    private val videoHeight: Int
) : FrameSink, CoroutineScope {

    override val coroutineContext: CoroutineContext =
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "CoreVirtualCamera error", e)
            _stateFlow.value = VirtualCameraState.ERROR
        }

    private val _stateFlow = MutableStateFlow(VirtualCameraState.IDLE)
    val stateFlow: StateFlow<VirtualCameraState> = _stateFlow

    private val _isRunning = AtomicBoolean(false)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionResultCode: Int = 0
    private var projectionData: Intent? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var proxyCallback: ((Surface) -> Unit)? = null

    fun setProjectionData(resultCode: Int, data: Intent?) {
        projectionResultCode = resultCode
        projectionData = data
    }

    fun setProxyCallback(cb: ((Surface) -> Unit)?) {
        proxyCallback = cb
    }

    fun startCamera(): Boolean {
        if (_isRunning.compareAndSet(false, true)) {
            val ok = startPipeline()
            _stateFlow.value = if (ok) VirtualCameraState.RUNNING else VirtualCameraState.ERROR
            return ok
        }
        return _stateFlow.value == VirtualCameraState.RUNNING
    }

    fun stopCamera() {
        if (!_isRunning.compareAndSet(true, false)) return
        _stateFlow.value = VirtualCameraState.STOPPING
        mainHandler.post {
            try {
                virtualDisplay?.release()
                virtualDisplay = null
                imageReader?.close()
                imageReader = null
                mediaProjection?.stop()
                mediaProjection = null
            } catch (e: Exception) {
                Log.w(TAG, "Stop cleanup error", e)
            }
            _stateFlow.value = VirtualCameraState.STOPPED
        }
    }

    @SuppressLint("WrongConstant")
    private fun startPipeline(): Boolean {
        if (projectionResultCode == 0 || projectionData == null) {
            Log.w(TAG, "Missing MediaProjection token")
            return false
        }
        return try {
            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(projectionResultCode, projectionData!!)
            imageReader = ImageReader.newInstance(
                videoWidth, videoHeight,
                android.graphics.PixelFormat.RGBA_8888, 2
            )
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION") 8
            } else 0
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "VirtualCameraDisplay",
                videoWidth,
                videoHeight,
                context.resources.displayMetrics.densityDpi,
                flags,
                imageReader!!.surface,
                null,
                null
            )
            proxyCallback?.invoke(imageReader!!.surface)
            Log.i(TAG, "Virtual display created")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create virtual display", e)
            false
        }
    }

    override fun onFrame(data: ByteArray, bufferInfo: android.media.MediaCodec.BufferInfo) {}
    override fun onImage(image: Image?) {
        if (image != null) onFrameFromImage(image)
    }
    override fun release() = stopCamera()

    private var latest: ByteArray? = null
    fun latestFrame(): ByteArray? = latest

    private fun onFrameFromImage(image: Image) {
        if (!_isRunning.get()) return
        try {
            val plane = image.planes[0]
            val len = plane.buffer.remaining()
            val chunk = ByteArray(len)
            plane.buffer.rewind()
            plane.buffer.get(chunk)
            val nv21 = ByteArray(videoWidth * videoHeight * 3 / 2)
            rgbaToNV21(chunk, videoWidth, videoHeight, nv21)
            latest = nv21
        } catch (e: Exception) {
            Log.w(TAG, "Frame conversion error", e)
        } finally {
            try { image.close() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "CoreVirtualCamera"
        fun rgbaToNV21(rgba: ByteArray, width: Int, height: Int, out: ByteArray) {
            var pos = 0
            val n = width * height
            for (i in 0 until n) {
                val r = rgba[i * 4].toInt() and 0xFF
                val g = rgba[i * 4 + 1].toInt() and 0xFF
                val b = rgba[i * 4 + 2].toInt() and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                out[pos++] = y.coerceIn(0, 255).toByte()
            }
            for (j in 0 until height / 2) {
                for (i in 0 until width / 2) {
                    val idx = (j * width + i) * 4
                    val r = rgba[idx].toInt() and 0xFF
                    val g = rgba[idx + 1].toInt() and 0xFF
                    val b = rgba[idx + 2].toInt() and 0xFF
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    out[pos++] = v.coerceIn(0, 255).toByte()
                    out[pos++] = u.coerceIn(0, 255).toByte()
                }
            }
        }
    }
}
