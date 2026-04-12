package com.camera2rtsp

import android.content.Context
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView

/**
 * Wrapper do RtmpCamera2 (RootEncoder library:2.7.2).
 *
 * Dois modos:
 *  1. initWithView(OpenGlView)  -> preview visivel + stream RTMP
 *  2. initBackground(Context)  -> sem preview (modo servico em segundo plano)
 *
 * A Activity deve chamar initWithView() passando a OpenGlView / AutoFitTextureView.
 * Quando a Activity e destruida, chamar detachView() para voltar ao modo background.
 */
class RtmpStreamer(
    private val ctrl: Camera2Controller,
    private val checker: ConnectChecker
) {
    private val tag = "RtmpStreamer"

    var camera: RtmpCamera2? = null
        private set

    val isStreaming: Boolean get() = camera?.isStreaming == true
    val isOnPreview: Boolean get() = camera?.isOnPreview == true

    // -- Inicializacao --------------------------------------------------------

    /**
     * Inicializa com OpenGlView para exibir preview na tela.
     * Se ja existir uma instancia em background, para ela primeiro.
     */
    fun initWithView(view: OpenGlView, context: Context) {
        releaseCamera()
        camera = RtmpCamera2(view, checker)
        ctrl.rtmpCamera = camera
        Log.i(tag, "RtmpCamera2 inicializado com OpenGlView")
    }

    /**
     * Inicializa em modo background (sem preview na tela).
     * Usado quando a Activity nao esta visivel.
     */
    fun initBackground(context: Context) {
        if (camera != null && !isOnPreview) return   // ja em background
        releaseCamera()
        camera = RtmpCamera2(context, checker)
        ctrl.rtmpCamera = camera
        Log.i(tag, "RtmpCamera2 inicializado (modo background)")
    }

    // -- Preview --------------------------------------------------------------

    fun startPreview(facing: CameraHelper.Facing = CameraHelper.Facing.BACK) {
        val cam = camera ?: return
        try { if (!cam.isOnPreview) cam.startPreview(facing) }
        catch (e: Exception) { Log.e(tag, "Erro ao iniciar preview", e) }
    }

    fun stopPreview() {
        try { if (camera?.isOnPreview == true) camera?.stopPreview() }
        catch (e: Exception) { Log.e(tag, "Erro ao parar preview", e) }
    }

    /**
     * Troca a camera ativa.
     * Para o preview, muda o facing e reinicia — a TextureView continua a mesma.
     */
    fun switchCamera(facing: CameraHelper.Facing) {
        val cam = camera ?: return
        try {
            val wasStreaming = cam.isStreaming
            if (cam.isOnPreview) cam.stopPreview()
            cam.startPreview(facing)
            Log.i(tag, "Camera trocada para $facing")
        } catch (e: Exception) {
            Log.e(tag, "Erro ao trocar camera", e)
        }
    }

    /**
     * Troca para uma camera pelo ID (ex: "0", "1", "2").
     * Usa stopPreview + startPreview com o mesmo facing implicitamente.
     */
    fun switchCameraById(cameraId: String, facing: CameraHelper.Facing) {
        val cam = camera ?: return
        try {
            if (cam.isOnPreview) cam.stopPreview()
            cam.startPreview(facing)
            ctrl.currentCameraId = cameraId
            Log.i(tag, "Camera trocada para ID=$cameraId facing=$facing")
        } catch (e: Exception) {
            Log.e(tag, "Erro ao trocar camera por ID", e)
        }
    }

    // -- Stream ---------------------------------------------------------------

    fun startStream(context: Context, url: String) {
        val cam = camera ?: run { Log.e(tag, "init nao chamado"); return }
        if (cam.isStreaming) { Log.d(tag, "ja streaming"); return }
        val rotation = CameraHelper.getCameraOrientation(context)
        val vOk = cam.prepareVideo(
            ctrl.currentWidth, ctrl.currentHeight,
            ctrl.currentFps, ctrl.currentBitrate * 1024, rotation
        )
        val aOk = cam.prepareAudio(128 * 1024, 44100, true)
        if (vOk && aOk) {
            cam.startStream(url)
            Log.i(tag, "RTMP iniciado: $url")
        } else {
            Log.e(tag, "Falha ao preparar encoder: vOk=$vOk aOk=$aOk")
        }
    }

    fun stopStream() {
        try { if (camera?.isStreaming == true) camera?.stopStream() }
        catch (e: Exception) { Log.e(tag, "Erro ao parar stream", e) }
    }

    fun stop() {
        stopStream()
        stopPreview()
        releaseCamera()
    }

    private fun releaseCamera() {
        try {
            camera?.let {
                if (it.isStreaming) it.stopStream()
                if (it.isOnPreview) it.stopPreview()
            }
        } catch (e: Exception) { Log.w(tag, "releaseCamera: ${e.message}") }
        camera = null
        ctrl.rtmpCamera = null
    }
}
