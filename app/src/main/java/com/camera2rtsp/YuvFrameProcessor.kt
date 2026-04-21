package com.camera2rtsp

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer

class YuvFrameProcessor(
    private val width: Int,
    private val height: Int,
    private val maxImages: Int = 2,
    private val onFrame: (YuvFrame) -> Unit
) {
    var imageReader: ImageReader? = null
        private set

    fun init() {
        imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, maxImages)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                onFrame(YuvFrame.fromImage(image, System.nanoTime()))
            } finally {
                image.close()
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun surface() = imageReader?.surface

    fun release() {
        imageReader?.close()
        imageReader = null
    }
}

data class YuvFrame(
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val yPlane: ByteArray,
    val uPlane: ByteArray,
    val vPlane: ByteArray,
    val yRowStride: Int,
    val uvRowStride: Int,
    val uvPixelStride: Int
) {
    companion object {
        fun fromImage(image: Image, timestampNs: Long): YuvFrame {
            val y = image.planes[0]
            val u = image.planes[1]
            val v = image.planes[2]
            return YuvFrame(
                width = image.width,
                height = image.height,
                timestampNs = timestampNs,
                yPlane = y.buffer.toByteArray(),
                uPlane = u.buffer.toByteArray(),
                vPlane = v.buffer.toByteArray(),
                yRowStride = y.rowStride,
                uvRowStride = u.rowStride,
                uvPixelStride = u.pixelStride
            )
        }

        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()
            return ByteArray(remaining()).also { get(it) }
        }
    }
}
