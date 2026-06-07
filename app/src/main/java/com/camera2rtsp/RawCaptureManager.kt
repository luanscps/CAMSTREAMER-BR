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
 * IMPORTANTE: processImage() mantém a Image ABERTA até o DngCreator
 * terminar de escrever — fecha no finally. Isso evita o OOM de 23 MB
 * que ocorria ao copiar o buffer RAW para o heap Java antes do DngCreator.
 *
 * onImageAvailable() (caminho legado) ainda usa copyAndClose pois não
 * tem acesso ao TotalCaptureResult na mesma thread, e o DngCreator não
 * pode ser usado sem ele.
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
     * Usado apenas pelo caminho legado (onImageAvailable).
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
     *
     * A Image é mantida ABERTA até o DngCreator terminar de escrever
     * no ByteArrayOutputStream — fechada no finally. Isso garante que
     * o DngCreator acessa o buffer nativo diretamente, sem alocar
     * os ~23 MB do frame RAW no heap Java.
     */
    fun processImage(image: Image, result: TotalCaptureResult) {
        rawHandler.post {
            try {
                val dngBytes = buildDng(image, result)
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                onResultConsumed()
                onRawFrame(
                    RawFrame(
                        width          = image.width,
                        height         = image.height,
                        timestampNs    = image.timestamp,
                        buffer         = ByteArray(0), // buffer bruto não necessário no path one-shot
                        isoUsed        = iso,
                        exposureNsUsed = exp,
                        captureResult  = result,
                        dngBytes       = dngBytes
                    )
                )
            } catch (e: Exception) {
                Log.e(tag, "processImage: erro no processamento", e)
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
     *
     * CRÍTICO: copia o buffer RAW e fecha a Image IMEDIATAMENTE aqui,
     * na thread do callback do ImageReader, antes de qualquer post
     * assíncrono. Isso libera o slot do ImageReader e evita o crash
     * "maxImages has already been acquired" quando frames RAW chegam
     * em sequência rápida.
     *
     * Neste caminho o DngCreator não pode ser usado pois a Image
     * já foi fechada — o RawFrame.dngBytes fica vazio.
     */
    fun onImageAvailable(image: Image) {
        val plane = copyAndClose(image) ?: return
        rawHandler.post {
            try {
                val result = resultQueue.poll(2, TimeUnit.SECONDS)
                if (result == null) {
                    Log.w(tag, "Timeout aguardando TotalCaptureResult — frame descartado")
                    return@post
                }
                onResultConsumed()
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
                        dngBytes       = ByteArray(0) // DNG indisponível no caminho legado
                    )
                )
            } catch (e: Exception) {
                Log.e(tag, "onImageAvailable: erro no processamento", e)
            }
        }
    }

    /**
     * Copia o plano[0] da Image para um ByteArray no heap e fecha a
     * Image imediatamente. Usado apenas pelo caminho legado (onImageAvailable).
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
     * Gera DNG a partir de uma Image ainda aberta e seu TotalCaptureResult.
     * O DngCreator.writeImage() lê o buffer nativo da Image diretamente
     * para o ByteArrayOutputStream — nenhum ByteArray de 23 MB é alocado
     * no heap Java. A Image deve ser fechada pelo chamador após este retorno.
     */
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
