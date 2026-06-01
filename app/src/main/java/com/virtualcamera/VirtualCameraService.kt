package com.virtualcamera

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class VirtualCameraService : Service() {

    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var decoderThread: Thread? = null
    private var isRunning = false

    private var projectionResultCode = 0
    private var projectionData: Intent? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                projectionResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                } else intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                startVirtualCamera()
            }
            ACTION_STOP -> stopVirtualCamera()
            ACTION_BOOT -> {
                projectionResultCode = VirtualCameraService.projectionResultCodeStatic
                projectionData = VirtualCameraService.projectionDataStatic
                if (projectionResultCode != 0 && projectionData != null) {
                    startVirtualCamera()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        stopVirtualCamera()
    }

    inner class LocalBinder : Binder() {
        fun getService(): VirtualCameraService = this@VirtualCameraService
    }

    private fun startVirtualCamera() {
        if (isRunning) return
        if (projectionResultCode == 0 || projectionData == null) {
            Log.w(TAG, "No projection token available")
            return
        }

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(projectionResultCode, projectionData!!)

        val width = 1280
        val height = 720
        val density = resources.displayMetrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION") 8
        } else 0

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "VirtualCamera",
            width, height, density,
            flags,
            imageReader!!.surface,
            null,
            null
        )

        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification("Virtual camera active"))

        decoderThread = Thread {
            val decoder = VideoDecoder(this@VirtualCameraService, null)
            decoder.setDecoderCallback(object : DecoderCallback {
                override fun onFrameAvailable(buffer: java.nio.ByteBuffer, bufferInfo: android.media.MediaCodec.BufferInfo) {
                    if (!isRunning) return
                    try {
                        val data = ByteArray(bufferInfo.size)
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        buffer[data]
                        onDecodedFrame(data, bufferInfo)
                    } catch (e: Exception) {
                        Log.w(TAG, "Frame copy error", e)
                    }
                }
            })
            if (decoder.prepare()) {
                decoder.start()
            }
        }.also { it.start() }

        Log.i(TAG, "Virtual camera started")
    }

    private fun onDecodedFrame(data: ByteArray, bufferInfo: android.media.MediaCodec.BufferInfo) {
        if (!isRunning) return
    }

    private fun stopVirtualCamera() {
        if (!isRunning) return
        isRunning = false

        try {
            decoderThread?.interrupt()
            decoderThread = null
        } catch (e: Exception) {}

        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {}

        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {}

        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()

        Log.i(TAG, "Virtual camera stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Virtual Camera",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Virtual camera background service"
                setSound(null, null)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, VirtualCameraService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 2001, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Open Source Virtual Camera")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "VirtualCameraService"
        private const val CHANNEL_ID = "virtual_camera_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.virtualcamera.START"
        const val ACTION_STOP = "com.virtualcamera.STOP"
        const val ACTION_BOOT = "com.virtualcamera.BOOT_COMPLETED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_VIDEO_URI = "video_uri"

        var projectionResultCodeStatic = 0
        var projectionDataStatic: Intent? = null

        fun start(context: android.content.Context, videoUri: String? = null) {
            val intent = Intent(context, VirtualCameraService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_VIDEO_URI, videoUri)
                putExtra(EXTRA_RESULT_CODE, projectionResultCodeStatic)
                putExtra(EXTRA_PROJECTION_DATA, projectionDataStatic)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: android.content.Context) {
            val intent = Intent(context, VirtualCameraService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
