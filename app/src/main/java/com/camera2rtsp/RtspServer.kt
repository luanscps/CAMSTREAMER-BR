package com.camera2rtsp

import android.content.Context
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.rtsp.RtspCamera2

/**
 * Wrapper RTSP usando RtspCamera2 (push para servidor externo).
 *
 * O modulo rtsp-server nao existe como artefato no JitPack.
 * A solucao e usar RtspCamera2 para fazer push para o MediaMTX,
 * que entao serve o stream RTSP para os clientes:
 *
 *   Celular --> push RTSP --> MediaMTX --> clientes assistem via rtsp://...
 *
 * URL de push padrao: rtsp://ip-do-servidor:8554/live
 */
class RtspServer(
    private val context: Context,
    private val ctrl: Camera2Controller
) : ConnectChecker {

    private val tag = "RtspServer"

    private var camera: RtspCamera2? = null

    var connectedClients: Int = 0
        private set

    val isStreaming: Boolean get() = camera?.isStreaming == true
    val isOnPreview: Boolean get() = camera?.isOnPreview == true

    /** Inicializa em modo background (sem view de preview). */
    fun initBackground() {
        camera = RtspCamera2(context, this)
        Log.i(tag, "RtspCamera2 inicializado (modo background)")
    }

    /**
     * Prepara o encoder e inicia o push RTSP para o servidor externo.
     * @param url ex: "rtsp://192.168.1.100:8554/live"
     */
    fun start(url: String) {
        val cam = camera ?: run { Log.e(tag, "initBackground() nao chamado"); return }
        if (cam.isStreaming) { Log.d(tag, "ja streaming"); return }
        val rotation = CameraHelper.getCameraOrientation(context)
        val vOk = cam.prepareVideo(
            ctrl.currentWidth, ctrl.currentHeight,
            ctrl.currentFps, ctrl.currentBitrate * 1024, rotation
        )
        val aOk = cam.prepareAudio(128 * 1024, 44100, true)
        if (vOk && aOk) {
            cam.startStream(url)
            Log.i(tag, "RTSP push iniciado: $url")
        } else {
            Log.e(tag, "Falha ao preparar encoder: vOk=$vOk aOk=$aOk")
        }
    }

    fun startPreview(facing: CameraHelper.Facing = CameraHelper.Facing.BACK) {
        val cam = camera ?: return
        try { if (!cam.isOnPreview) cam.startPreview(facing) }
        catch (e: Exception) { Log.e(tag, "Erro ao iniciar preview", e) }
    }

    fun stopPreview() {
        try { if (camera?.isOnPreview == true) camera?.stopPreview() }
        catch (e: Exception) { Log.e(tag, "Erro ao parar preview", e) }
    }

    fun stop() {
        try {
            if (camera?.isStreaming == true) camera?.stopStream()
            if (camera?.isOnPreview == true) camera?.stopPreview()
        } catch (e: Exception) { Log.e(tag, "Erro ao parar", e) }
    }

    // -- ConnectChecker -------------------------------------------------------
    override fun onConnectionStarted(url: String)  { Log.d(tag, "onConnectionStarted: $url") }
    override fun onConnectionSuccess()              { connectedClients = 1; Log.i(tag, "RTSP conectado") }
    override fun onConnectionFailed(reason: String) { Log.e(tag, "RTSP falhou: $reason") }
    override fun onDisconnect()                     { connectedClients = 0; Log.i(tag, "RTSP desconectado") }
    override fun onAuthError()                      { Log.e(tag, "auth error") }
    override fun onAuthSuccess()                    { Log.i(tag, "auth ok") }
}
