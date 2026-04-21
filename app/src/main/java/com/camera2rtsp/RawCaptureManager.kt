package com.camera2rtsp

import android.graphics.ImageFormat
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.Looper

class RawCaptureManager(
    private val width: Int,
    private val height: Int,
    private val onRawFrame: (RawFrame) -> Unit
) {
    data class RawFrame(
        val width: Int,
        val height: Int,
        val timestampNs: Long,
        val buffer: ByteArray,
        val isoUsed: Int,
        val exposureNsUsed: Long,
        val captureResult: TotalCaptureResult
    )

    private var imageReader: ImageReader? = null

    @Volatile
    var pendingResult: TotalCaptureResult? = null

    fun init() {
        imageReader = ImageReader.newInstance(width, height, ImageFormat.RAW_SENSOR, 1)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            try {
                val result = pendingResult ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                buffer.rewind()
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                onRawFrame(
                    RawFrame(
                        width = image.width,
                        height = image.height,
                        timestampNs = image.timestamp,
                        buffer = bytes,
                        isoUsed = 0,
                        exposureNsUsed = 0L,
                        captureResult = result
                    )
                )
            } finally {
                image.close()
                pendingResult = null
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun surface() = imageReader?.surface

    fun release() {
        imageReader?.close()
        imageReader = null
    }
}
