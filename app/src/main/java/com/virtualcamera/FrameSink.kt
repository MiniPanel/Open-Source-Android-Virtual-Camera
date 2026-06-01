package com.virtualcamera

import android.media.Image
import android.media.MediaCodec

interface FrameSink {
    fun onFrame(data: ByteArray, bufferInfo: MediaCodec.BufferInfo)
    fun onImage(image: Image?)
    fun release()
}
