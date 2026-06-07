package com.camera2rtsp

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Gerencia a captura e processamento de frames RAW (DNG).
 *
 * Versão refatorada: não possui mais ImageReader próprio.
 * A surface é registrada na CameraCaptureSession pela lib via
 * Camera2ApiManager.addImageListener(), que chama onImageAvailable
 * diretamente. O método processImage() recebe Image + TotalCaptureResult
 * e executa o DngCreator em thread dedicada.
 */
class RawCaptureManager(
    private val width: Int,
    private val height: Int,
    private val characteristics: CameraCharacteristics,
    private val onRawFrame: (RawFrame) -> Unit,
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

    // Thread dedicada para DngCreator (~800ms) sem bloquear CameraWorker
    private val rawThread = HandlerThread("RawCaptureThread").also { it.start() }
    private val rawHandler = Handler(rawThread.looper)

    // Fila de TotalCaptureResult para parear com Image quando
    // o fluxo ainda usa o caminho legado (offerResult)
    private val resultQueue = LinkedBlockingQueue<TotalCaptureResult>(4)

    /**
     * Processa um par Image + TotalCaptureResult já disponíveis.
     * Chamado pelo captureCallback one-shot de captureRawStill().
     * Libera a Image após o DNG ser gerado.
     */
    fun processImage(image: Image, result: TotalCaptureResult) {
        rawHandler.post {
            try {
                onResultConsumed()
                val dngBytes = buildDng(image, result)
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
                runCatching { image.close() }
            }
        }
    }

    /**
     * Caminho legado: empilha o TotalCaptureResult para parear
     * com a Image que chega via addImageListener da lib.
     * Mantido como fallback caso o captureCallback one-shot falhe.
     */
    fun offerResult(result: TotalCaptureResult) {
        resultQueue.offer(result)
    }

    /**
     * Caminho legado: recebe Image do addImageListener e busca
     * o TotalCaptureResult da fila.
     * Chamado pelo Camera2ApiManager.ImageCallback.
     */
    fun onImageAvailable(image: Image) {
        rawHandler.post {
            try {
                val result = resultQueue.poll(2, TimeUnit.SECONDS)
                if (result == null) {
                    Log.w(tag, "Timeout aguardando TotalCaptureResult — frame descartado")
                    return@post
                }
                onResultConsumed()
                val dngBytes = buildDng(image, result)
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
                runCatching { image.close() }
            }
        }
    }

    private fun buildDng(image: Image, result: TotalCaptureResult): ByteArray {
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

    fun release() {
        resultQueue.clear()
        rawThread.quitSafely()
    }
}
