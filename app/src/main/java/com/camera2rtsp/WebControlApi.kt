package com.camera2rtsp

import android.content.Context
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.IHTTPSession

/**
 * WebControlApi
 * Responsável exclusivamente pela lógica de negócio das APIs JSON.
 * Chamado pelo WebControlServer (roteador HTTP).
 */
object WebControlApi {

    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()

    // ─────────────────────────────────────────────
    // GET /api/capabilities
    // ─────────────────────────────────────────────
    fun serveCapabilities(cameraController: Camera2Controller, context: Context): Response {
        val capabilities = cameraController.discoverAllCameras(context)
        val json = gson.toJson(capabilities)
        return okJson(json)
    }

    // ─────────────────────────────────────────────
    // GET /api/status  ou  GET /status
    // Agora recebe o server para ler connectedClients real
    // ─────────────────────────────────────────────
    fun serveStatus(cameraController: Camera2Controller, server: WebControlServer): Response {
        val c = cameraController
        val streaming = c.rtmpCamera?.isStreaming == true
        val focusMode = if (c.autoFocus) "continuous-video" else "off"
        val focusDist = String.format(java.util.Locale.US, "%.2f", c.focusDistance)

        // ✓ Contador real de clientes com painel web aberto
        val numClients = server.connectedClients

        val curvals = mapOf(
            "video_size"           to "${c.currentWidth}x${c.currentHeight}",
            "ffc"                  to if (c.currentCameraId == "1") "on" else "off",
            "camera_id"            to c.currentCameraId,
            "zoom"                 to "${(c.zoomLevel * 100).toInt() + 100}",
            "focusmode"            to focusMode,
            "focus_distance"       to focusDist,
            "focal_length"         to "4.30",
            "aperture"             to "1.5 (fixo)",
            "whitebalance"         to c.whiteBalanceMode,
            "torch"                to if (c.lanternEnabled) "on" else "off",
            "iso"                  to c.isoValue.toString(),
            "exposure_ns"          to c.exposureNs.toString(),
            "frame_duration"       to c.frameDurationNs.toString(),
            "manual_sensor"        to if (c.manualSensor) "on" else "off",
            "bitrate_kbps"         to c.currentBitrate.toString(),
            "fps"                  to c.currentFps.toString(),
            "ois"                  to if (c.oisEnabled) "on" else "off",
            "eis"                  to if (c.eisEnabled) "on" else "off",
            "ae_lock"              to if (c.aeLocked) "on" else "off",
            "awb_lock"             to if (c.awbLocked) "on" else "off",
            "flash_mode"           to c.flashMode,
            "edge_mode"            to edgeModeStr(c.edgeMode),
            "noise_reduction_mode" to nrModeStr(c.noiseReductionMode),
            "tonemap_mode"         to "high_quality",
            "hot_pixel_mode"       to hotPixelModeStr(c.hotPixelMode)
        )

        val avail = mapOf(
            "focusmode"      to listOf("off", "auto", "continuous-video", "continuous-picture"),
            "whitebalance"   to listOf("auto", "incandescent", "fluorescent", "daylight", "cloudy"),
            "video_size"     to listOf("3840x2160", "1920x1080", "1280x720", "960x540"),
            "torch"          to listOf("on", "off"),
            "manual_sensor"  to listOf("on", "off"),
            "focus_distance" to (0..100).map { String.format(java.util.Locale.US, "%.2f", it * 0.1) },
            "iso"            to listOf("50","100","200","400","800","1600","3200"),
            "zoom"           to (100..800 step 7).map { it.toString() },
            "camera_id"      to listOf("0", "1", "2", "3"),
            "flash_mode"     to listOf("off", "torch", "single"),
            "edge_mode"      to listOf("off", "fast", "high_quality"),
            "noise_reduction_mode" to listOf("off", "minimal", "fast", "high_quality"),
            "tonemap_mode"   to listOf("high_quality"),
            "hot_pixel_mode" to listOf("off", "fast", "high_quality")
        )

        val status = mapOf(
            "video_connections" to numClients,
            "audio_connections" to 0,
            "streaming"         to streaming,
            "rtmp_url"          to (StreamingService.instance?.rtmpUrl ?: ""),
            "curvals"           to curvals,
            "avail"             to avail
        )

        return okJson(gson.toJson(status))
    }

    // ─────────────────────────────────────────────
    // POST /api/control
    // ─────────────────────────────────────────────
    fun handleControl(session: IHTTPSession, cameraController: Camera2Controller): Response {
        val map = mutableMapOf<String, String>()
        return try {
            session.parseBody(map)
            val json = map["postData"] ?: return NanoHTTPD.newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/plain", "No data"
            )
            val params = com.google.gson.Gson().fromJson<Map<String, Any>>(
                json, object : TypeToken<Map<String, Any>>() {}.type
            )
            // Troca de URL RTMP em tempo real
            (params["rtmpUrl"] as? String)?.let { newUrl ->
                StreamingService.instance?.let { svc ->
                    svc.rtmpUrl = newUrl
                    svc.stopStream()
                    svc.startStream()
                }
            }
            cameraController.updateSettings(params)
            okJson("""{"status":"ok"}""")
        } catch (e: Exception) {
            NanoHTTPD.newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "application/json",
                """{"status":"error","message":"${e.message}"}"""
            )
        }
    }

    // ─────────────────────────────────────────────
    // Helpers privados de conversão de modo
    // ─────────────────────────────────────────────
    private fun edgeModeStr(v: Int) = when (v) {
        android.hardware.camera2.CameraMetadata.EDGE_MODE_OFF          -> "off"
        android.hardware.camera2.CameraMetadata.EDGE_MODE_FAST         -> "fast"
        android.hardware.camera2.CameraMetadata.EDGE_MODE_HIGH_QUALITY -> "high_quality"
        else -> "high_quality"
    }

    private fun nrModeStr(v: Int) = when (v) {
        android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_OFF          -> "off"
        android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL      -> "minimal"
        android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_FAST         -> "fast"
        android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY -> "high_quality"
        else -> "high_quality"
    }

    private fun hotPixelModeStr(v: Int) = when (v) {
        android.hardware.camera2.CameraMetadata.HOT_PIXEL_MODE_OFF          -> "off"
        android.hardware.camera2.CameraMetadata.HOT_PIXEL_MODE_FAST         -> "fast"
        android.hardware.camera2.CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY -> "high_quality"
        else -> "high_quality"
    }

    // ─────────────────────────────────────────────
    // Utilitário: resposta JSON com CORS
    // ─────────────────────────────────────────────
    private fun okJson(body: String): Response {
        val resp = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", body)
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }
}
