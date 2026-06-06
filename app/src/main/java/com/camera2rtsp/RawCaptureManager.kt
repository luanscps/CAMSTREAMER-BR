package com.camera2rtsp

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RawCaptureManager(
    private val width: Int,
    private val height: Int,
    private val characteristics: CameraCharacteristics,
    private val onRawFrame: (RawFrame) -> Unit,
    // Chamado imediatamente apos consumir o TotalCaptureResult da fila.
    // Usado pelo Camera2Controller para resetar rawCapturePending=false
    // e evitar que resultados de preview subsequentes entrem na fila.
    private val onResultConsumed: () -> Unit = {}
) {
    data class RawFrame(
        val width: Int,
        val height: Int,
        val timestampNs: Long,
        val buffer: ByteArray,
        val isoUsed: Int,
        val exposureNsUsed: Long,
        val captureResult: TotalCaptureResult,
        val dngBytes: ByteArray
    )

    private val tag = "RawCaptureManager"

    // Bug #3 fix: HandlerThread dedicada — nunca bloqueia a Main Thread
    private val rawThread = HandlerThread("RawCaptureThread").also { it.start() }

    // Bug #4 fix: fila thread-safe — resolve race condition pendingResult vs frame
    private val resultQueue = LinkedBlockingQueue<TotalCaptureResult>(4)

    private var imageReader: ImageReader? = null

    fun init() {
        // maxImages=2: evita starvation se frame chega enquanto anterior e processado
        imageReader = ImageReader.newInstance(width, height, ImageFormat.RAW_SENSOR, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            try {
                // Bug #4 fix: poll com timeout — aguarda resultado chegar na fila
                val result = resultQueue.poll(2, TimeUnit.SECONDS)
                if (result == null) {
                    Log.w(tag, "Timeout aguardando TotalCaptureResult — frame descartado")
                    return@setOnImageAvailableListener
                }

                // Notifica imediatamente que o resultado foi consumido.
                // Camera2Controller reseta rawCapturePending=false aqui,
                // antes do DngCreator (que pode demorar ~800ms), garantindo
                // que nenhum resultado de preview subsequente entre na fila.
                onResultConsumed()

                // Gera DNG em memoria ANTES de fechar image
                val dngBytes = buildDng(image, result)

                // Extrai buffer RAW bruto
                val buf = image.planes[0].buffer
                buf.rewind()
                val rawBytes = ByteArray(buf.remaining()).also { buf.get(it) }

                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L

                onRawFrame(
                    RawFrame(
                        width          = image.width,
                        height         = image.height,
                        timestampNs    = image.timestamp,
                        buffer         = rawBytes,
                        isoUsed        = iso,
                        exposureNsUsed = exp,
                        captureResult  = result,
                        dngBytes       = dngBytes
                    )
                )
            } finally {
                image.close()
            }
        }, Handler(rawThread.looper)) // Bug #3 fix: thread dedicada
    }

    /**
     * Empilha o TotalCaptureResult na fila para o ImageReader consumir.
     * Deve ser chamado APENAS quando rawCapturePending=true no Camera2Controller,
     * garantindo que so o resultado do still RAW entre na fila.
     */
    fun offerResult(result: TotalCaptureResult) {
        resultQueue.offer(result)
    }

    private fun buildDng(image: android.media.Image, result: TotalCaptureResult): ByteArray {
        val out = ByteArrayOutputStream()
        return try {
            DngCreator(characteristics, result).use { dng ->
                dng.writeImage(out, image)
            }
            out.toByteArray()
        } catch (e: Exception) {
            Log.e(tag, "DngCreator falhou: ${e.message}", e)
            ByteArray(0)
        }
    }

    fun surface() = imageReader?.surface

    fun release() {
        resultQueue.clear()
        imageReader?.close()
        imageReader = null
        rawThread.quitSafely()
    }
}
