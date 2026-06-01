package com.virtualcamera

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable
import kotlin.coroutines.CoroutineContext

class VideoDecoder(
    private val context: Context,
    private val videoUri: String?
) : Closeable, CoroutineScope {

    override val coroutineContext: CoroutineContext =
        Job() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Decoder coroutine error", e)
            _errorFlow.tryEmit(e.message ?: "Unknown error")
        }

    private var mediaExtractor: MediaExtractor? = null
    private var mediaCodec: MediaCodec? = null
    private var videoWidth = 1280
    private var videoHeight = 720
    private var frameRate = 30
    private var durationUs = 0L
    private var isLooping = true
    private var decoderCallback: DecoderCallback? = null
    private var decoderThread: Thread? = null

    private val _statusFlow = MutableStateFlow(DecoderStatus.IDLE)
    val statusFlow: StateFlow<DecoderStatus> = _statusFlow

    private val _errorFlow = MutableStateFlow<String?>(null)
    val errorFlow: StateFlow<String?> = _errorFlow

    enum class DecoderStatus { IDLE, PREPARING, READY, RUNNING, STOPPED, ERROR }

    fun setDecoderCallback(cb: DecoderCallback?) {
        decoderCallback = cb
    }

    fun prepare(): Boolean {
        _statusFlow.value = DecoderStatus.PREPARING
        mediaExtractor = MediaExtractor()

        return try {
            if (videoUri == null) {
                prepareBuiltinVideo()
            } else {
                prepareFromUri()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare decoder", e)
            _errorFlow.tryEmit("Failed to prepare video: ${e.message}")
            _statusFlow.value = DecoderStatus.ERROR
            false
        }
    }

    private fun prepareFromUri(): Boolean {
        when {
            videoUri!!.startsWith("content://") -> {
                context.contentResolver.openFileDescriptor(android.net.Uri.parse(videoUri), "r")?.use { pfd ->
                    mediaExtractor!!.setDataSource(pfd.fileDescriptor)
                } ?: run {
                    mediaExtractor!!.setDataSource(context, android.net.Uri.parse(videoUri), null)
                }
            }
            else -> {
                mediaExtractor!!.setDataSource(videoUri)
            }
        }

        val trackIndex = selectVideoTrack(mediaExtractor!!)
        if (trackIndex < 0) {
            _errorFlow.tryEmit("No video track found")
            return false
        }
        return buildDecoder(trackIndex)
    }

    private fun prepareBuiltinVideo(): Boolean {
        val resId = context.resources.getIdentifier("sample_video", "raw", context.packageName)
        if (resId == 0) {
            _errorFlow.tryEmit("No built-in video found. Please select a video manually.")
            _statusFlow.value = DecoderStatus.ERROR
            return false
        }
        val afd = context.resources.openRawResourceFd(resId)
        mediaExtractor!!.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()
        val trackIndex = selectVideoTrack(mediaExtractor!!)
        if (trackIndex < 0) {
            _errorFlow.tryEmit("No video track in bundled resource")
            return false
        }
        return buildDecoder(trackIndex)
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                extractor.selectTrack(i)
                val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
                val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                videoWidth = w
                videoHeight = h
                if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    frameRate = fmt.getInteger(MediaFormat.KEY_FRAME_RATE)
                }
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = fmt.getLong(MediaFormat.KEY_DURATION)
                }
                return i
            }
        }
        return -1
    }

    private fun buildDecoder(trackIndex: Int): Boolean {
        val format = mediaExtractor!!.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
        mediaCodec = MediaCodec.createDecoderByType(mime)
        mediaCodec!!.configure(format, null, null, 0)
        mediaCodec!!.start()
        _statusFlow.value = DecoderStatus.READY
        return true
    }

    fun start() {
        if (_statusFlow.value != DecoderStatus.READY && _statusFlow.value != DecoderStatus.STOPPED) return

        _statusFlow.value = DecoderStatus.RUNNING

        decoderThread = object : Thread("VideoDecoder-Loop") {
            override fun run() {
                decodeLoop()
            }
        }.also { it.start() }
    }

    private fun decodeLoop() {
        val codec = mediaCodec ?: return

        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        val timeoutUs = 10000L

        while (isActive && !sawOutputEOS) {
            if (!sawInputEOS) {
                val inIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val inputBuf = codec.getInputBuffer(inIndex)!!
                    val sampleSize = mediaExtractor!!.readSampleData(inputBuf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    } else {
                        val pts = mediaExtractor!!.sampleTime
                        codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                        mediaExtractor!!.advance()
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (outIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outIndex)!!

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    if (isLooping) {
                        mediaExtractor!!.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        codec.flush()
                        sawInputEOS = false
                        sawOutputEOS = false
                        codec.releaseOutputBuffer(outIndex, false)
                        continue
                    }
                    sawOutputEOS = true
                }

                if (sawOutputEOS) {
                    codec.releaseOutputBuffer(outIndex, false)
                    break
                }

                decoderCallback?.onFrameAvailable(outputBuffer, bufferInfo)
                codec.releaseOutputBuffer(outIndex, false)
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.d(TAG, "Output format changed: ${codec.outputFormat}")
            }
        }
    }

    fun stop() {
        _statusFlow.value = DecoderStatus.STOPPED
        cancel()
        decoderThread?.interrupt()
        decoderThread = null
    }

    val isRunning: Boolean
        get() = _statusFlow.value == DecoderStatus.RUNNING

    val currentStatus: DecoderStatus
        get() = _statusFlow.value

    val videoWidthPx: Int
        get() = videoWidth

    val videoHeightPx: Int
        get() = videoHeight

    override fun close() {
        try {
            stop()
            decoderThread = null
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {}
        mediaCodec = null
        try {
            mediaExtractor?.release()
        } catch (e: Exception) {}
        mediaExtractor = null
        _statusFlow.value = DecoderStatus.IDLE
    }

    companion object {
        private const val TAG = "VideoDecoder"
    }
}

interface DecoderCallback {
    fun onFrameAvailable(buffer: java.nio.ByteBuffer, bufferInfo: android.media.MediaCodec.BufferInfo)
}
