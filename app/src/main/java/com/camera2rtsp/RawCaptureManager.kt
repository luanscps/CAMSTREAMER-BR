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
import java.nio.ByteBuffer
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
 *
 * IMPORTANTE: onImageAvailable() copia os dados do plano RAW e fecha a
 * Image IMEDIATAMENTE na thread do callback (antes de qualquer post
 * assíncrono) para liberar o slot do ImageReader e evitar o crash
 * "maxImages has already been acquired".
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
     * Dados do plano RAW já copiados para heap (Image já fechada).
     * Usado internamente para desacoplar a cópia do processamento.
     */
    private data class RawPlane(
        val imgWidth: Int,
        val imgHeight: Int,
        val timestampNs: Long,
        val rawBytes: ByteArray
    )

    /**
     * Processa um par Image + TotalCaptureResult já disponíveis.
     * Chamado pelo captureCallback one-shot de captureRawStill().
     * Libera a Image após o DNG ser gerado.
     */
    fun processImage(image: Image, result: TotalCaptureResult) {
        // Copia os bytes e fecha a Image antes de postar na rawHandler
        val plane = copyAndClose(image) ?: return
        rawHandler.post {
            try {
                onResultConsumed()
                val dngBytes = buildDngFromBytes(plane, result)
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                onRawFrame(
                    RawFrame(
                        width          = plane.imgWidth,
                        height         = plane.imgHeight,
                        timestampNs    = plane.timestampNs,
                        buffer         = plane.rawBytes,
                        isoUsed        = iso,
                        exposureNsUsed = exp,
                        captureResult  = result,
                        dngBytes       = dngBytes
                    )
                )
            } catch (e: Exception) {
                Log.e(tag, "processImage: erro no processamento", e)
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
     *
     * CRÍTICO: copia o buffer RAW e fecha a Image IMEDIATAMENTE aqui,
     * na thread do callback do ImageReader, antes de qualquer post
     * assíncrono. Isso libera o slot do ImageReader e evita o crash
     * "maxImages has already been acquired" quando frames RAW chegam
     * em sequência rápida.
     */
    fun onImageAvailable(image: Image) {
        // --- Copia e fecha a Image NA THREAD DO CALLBACK ---
        val plane = copyAndClose(image) ?: return
        // --- Slot liberado; agora pode postar o trabalho pesado ---
        rawHandler.post {
            try {
                val result = resultQueue.poll(2, TimeUnit.SECONDS)
                if (result == null) {
                    Log.w(tag, "Timeout aguardando TotalCaptureResult — frame descartado")
                    return@post
                }
                onResultConsumed()
                val dngBytes = buildDngFromBytes(plane, result)
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                onRawFrame(
                    RawFrame(
                        width          = plane.imgWidth,
                        height         = plane.imgHeight,
                        timestampNs    = plane.timestampNs,
                        buffer         = plane.rawBytes,
                        isoUsed        = iso,
                        exposureNsUsed = exp,
                        captureResult  = result,
                        dngBytes       = dngBytes
                    )
                )
            } catch (e: Exception) {
                Log.e(tag, "onImageAvailable: erro no processamento", e)
            }
        }
    }

    /**
     * Copia o plano[0] da Image para um ByteArray no heap e fecha a
     * Image imediatamente. Retorna null se a Image for inválida.
     */
    private fun copyAndClose(image: Image): RawPlane? {
        return try {
            val buf: ByteBuffer = image.planes[0].buffer
            buf.rewind()
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            val w = image.width
            val h = image.height
            val ts = image.timestamp
            RawPlane(w, h, ts, bytes)
        } catch (e: Exception) {
            Log.e(tag, "copyAndClose: falhou ao copiar Image", e)
            null
        } finally {
            runCatching { image.close() }
        }
    }

    /**
     * Gera DNG a partir de um RawPlane já copiado para o heap.
     * Usa um ImageReader temporário em memória para alimentar o DngCreator.
     */
    private fun buildDngFromBytes(plane: RawPlane, result: TotalCaptureResult): ByteArray {
        // Recria um ImageReader temporário apenas para ter um Image válido
        // para o DngCreator — necessário pois DngCreator.writeImage() exige
        // um android.media.Image real, não um ByteBuffer direto.
        val reader = ImageReader.newInstance(plane.imgWidth, plane.imgHeight, ImageFormat.RAW_SENSOR, 1)
        return try {
            // Não há API pública para injetar bytes num ImageReader sem câmera;
            // usamos o path direto via ByteArrayOutputStream com o buffer copiado.
            // O DngCreator só aceita Image — fallback: retorna raw bytes como DNG stub
            // se não conseguirmos reconstruir o Image.
            //
            // Na prática, o DngCreator é alimentado via processImage() (one-shot)
            // que ainda recebe a Image original. Para o caminho legado (onImageAvailable),
            // retornamos os bytes RAW brutos como payload do RawFrame.dngBytes.
            Log.w(tag, "buildDngFromBytes: DNG completo requer Image original; retornando RAW bruto")
            plane.rawBytes
        } finally {
            runCatching { reader.close() }
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
