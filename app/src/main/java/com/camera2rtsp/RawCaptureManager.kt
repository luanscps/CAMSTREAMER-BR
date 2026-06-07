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

/**
 * Gerencia a captura e processamento de frames RAW (DNG).
 *
 * Versão refatorada: não possui mais ImageReader próprio.
 * A surface é registrada na CameraCaptureSession via ImageReader
 * independente criado em Camera2Controller.captureRawStill().
 * O método processImage() recebe Image + TotalCaptureResult
 * e executa o DngCreator em thread dedicada.
 *
 * FLUXO CORRETO:
 *   captureRawStill() cria ImageReader próprio
 *   onImageAvailable → processImage(image, result)
 *   rawHandler.post { buildDng() } → onRawFrame() → captureLatch.countDown()
 *   finally { image.close() }  ← Único ponto de close, após DngCreator terminar
 *
 * A Image é mantida ABERTA até o DngCreator terminar de escrever no
 * ByteArrayOutputStream — fechada no finally. Isso garante que o
 * DngCreator acessa o buffer nativo diretamente, sem alocar os ~23 MB
 * do frame RAW no heap Java.
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

    /**
     * Processa um par Image + TotalCaptureResult já disponíveis.
     * Chamado por Camera2Controller via onImageAvailable do ImageReader próprio.
     *
     * IMPORTANTE: A Image NÃO deve ser fechada pelo chamador antes deste método
     * terminar. O close() ocorre somente no finally do rawHandler.post{},
     * após DngCreator.writeImage() concluir a leitura do buffer nativo.
     *
     * O captureLatch.countDown() também é disparado via onRawFrame callback
     * em Camera2Controller — garantindo que a sessão RAW não seja fechada
     * antes do DNG estar completamente gerado.
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
                        buffer         = ByteArray(0),
                        isoUsed        = iso,
                        exposureNsUsed = exp,
                        captureResult  = result,
                        dngBytes       = dngBytes
                    )
                )
            } catch (e: Exception) {
                Log.e(tag, "processImage: erro no processamento", e)
                // fix: em caso de exceção, invoca onRawFrame com dngBytes vazio
                // para garantir que captureLatch.countDown() seja chamado em Camera2Controller
                runCatching {
                    onRawFrame(
                        RawFrame(
                            width = width, height = height, timestampNs = 0L,
                            buffer = ByteArray(0), isoUsed = 0, exposureNsUsed = 0L,
                            captureResult = result, dngBytes = ByteArray(0)
                        )
                    )
                }
            } finally {
                // fix: único ponto de close — após DngCreator.writeImage() terminar
                // NÃO feche a Image antes daqui
                runCatching { image.close() }
                Log.d(tag, "processImage: image.close() executado")
            }
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
        rawThread.quitSafely()
    }
}
