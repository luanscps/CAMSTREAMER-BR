package com.camera2rtsp

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper

class DepthFusionProcessor(
    private val width: Int,
    private val height: Int,
    private val onDepthFrame: (DepthFrame) -> Unit
) {
    private var imageReader: ImageReader? = null

    fun init() {
        imageReader = ImageReader.newInstance(width, height, ImageFormat.DEPTH16, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                onDepthFrame(DepthFrame.fromImage(image))
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

data class DepthFrame(
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val depthData: ShortArray,
    val minDepthMm: Int,
    val maxDepthMm: Int,
    val meanDepthMm: Float
) {
    companion object {
        fun fromImage(image: Image): DepthFrame {
            val buffer = image.planes[0].buffer
            buffer.rewind()
            val shorts = ShortArray(buffer.remaining() / 2)
            buffer.asShortBuffer().get(shorts)

            var minD = Int.MAX_VALUE
            var maxD = 0
            var sum = 0L
            var count = 0

            shorts.forEach { s ->
                val distMm = (s.toInt() and 0xFFF8) ushr 3
                val conf = s.toInt() and 0x0007
                if (conf > 0 && distMm > 0) {
                    if (distMm < minD) minD = distMm
                    if (distMm > maxD) maxD = distMm
                    sum += distMm
                    count++
                }
            }

            return DepthFrame(
                width = image.width,
                height = image.height,
                timestampNs = image.timestamp,
                depthData = shorts,
                minDepthMm = if (minD == Int.MAX_VALUE) 0 else minD,
                maxDepthMm = maxD,
                meanDepthMm = if (count > 0) sum.toFloat() / count else 0f
            )
        }
    }

    fun depthAt(x: Int, y: Int): Int {
        val idx = y * width + x
        if (idx >= depthData.size) return 0
        return (depthData[idx].toInt() and 0xFFF8) ushr 3
    }

    fun confidenceAt(x: Int, y: Int): Int {
        val idx = y * width + x
        if (idx >= depthData.size) return 0
        return depthData[idx].toInt() and 0x0007
    }
}
