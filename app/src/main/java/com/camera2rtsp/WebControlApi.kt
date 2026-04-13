package com.camera2rtsp

import android.content.Context
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.IHTTPSession

/**
 * WebControlApi — v5-CAMUI
 *
 * /api/status agora emite JSON no formato exato que o app.js consome:
 *
 *  {
 *    streaming: bool,
 *    camera_id: String,
 *    resolution: String,       // "1920x1080"
 *    bitrate_kbps: Int,
 *    fps: Int,
 *    rtmp_url: String,
 *    focus_mode: String,
 *    focus_dist: Float,
 *    iso: Int,
 *    exposure_ns: Long,
 *    frame_duration_ns: Long,
 *    focal_length: Float,
 *    aperture: Float,
 *    wb: String,
 *    ois: Bool,
 *    eis: Bool,
 *    manual_sensor: Bool,
 *    edge: String,
 *    nr: String,
 *    hot_pixel: String,
 *    zoom: Float,
 *    monitor: {
 *      iso: Int, shutter_ns: Long,
 *      af_state: String, ae_state: String,
 *      rggb_r: Float, rggb_gr: Float, rggb_gb: Float, rggb_b: Float
 *    },
 *    cameras: [ CameraCapabilities... ]  // para popular botoes de camera
 *  }
 */
object WebControlApi {

    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()

    // ── Cache de capabilities ──────────────────────────────────────────────────
    @Volatile private var capsCache: List<CameraCapabilities>? = null
    @Volatile private var capsCacheForCamId: String? = null

    private fun getCapsCache(
        cameraController: Camera2Controller,
        context: Context
    ): List<CameraCapabilities> {
        val currentCamId = cameraController.currentCameraId
        val cached = capsCache
        if (cached != null && capsCacheForCamId == currentCamId) return cached
        val fresh = cameraController.discoverAllCameras(context)
        capsCache = fresh
        capsCacheForCamId = currentCamId
        return fresh
    }

    // ── GET /api/capabilities ──────────────────────────────────────────────────
    fun serveCapabilities(cameraController: Camera2Controller, context: Context): Response {
        return okJson(gson.toJson(getCapsCache(cameraController, context)))
    }

    // ── GET /api/status ────────────────────────────────────────────────────────
    fun serveStatus(cameraController: Camera2Controller, context: Context): Response {
        val c = cameraController
        val streaming = c.rtmpCamera?.isStreaming == true

        // Câmeras — usa cache, app.js usa na primeira carga para montar botões
        val cameras = getCapsCache(c, context)

        // Calibração de foco da câmera ativa (via cache)
        val focusCalibration = try {
            cameras.firstOrNull { it.cameraId == c.currentCameraId }
                ?.focusDistanceCalibration ?: "UNCALIBRATED"
        } catch (e: Exception) { "UNCALIBRATED" }

        // Monitor ao vivo — objeto separado como app.js espera em s.monitor
        val monitor = mapOf(
            "iso"        to c.liveIso,
            "shutter_ns" to c.liveExposureNs,
            "af_state"   to c.liveAfState,
            "ae_state"   to c.liveAeState,
            "rggb_r"     to c.liveRggbR,
            "rggb_gr"    to c.liveRggbGr,
            "rggb_gb"    to c.liveRggbGb,
            "rggb_b"     to c.liveRggbB
        )

        // Raiz do JSON — nomes exatos que o app.js lê
        val status = mapOf(
            // Stream
            "streaming"       to streaming,
            "rtmp_url"        to (StreamingService.instance?.rtmpUrl ?: ""),
            // Câmera
            "camera_id"       to c.currentCameraId,
            "resolution"      to "${c.currentWidth}x${c.currentHeight}",
            "bitrate_kbps"    to c.currentBitrate,
            "fps"             to c.currentFps,
            // Foco
            "focus_mode"      to (if (c.autoFocus) "continuous-video" else "off"),
            "focus_dist"      to c.focusDistance,
            "focus_distance_calibration" to focusCalibration,
            // Sensor
            "iso"             to c.isoValue,
            "exposure_ns"     to c.exposureNs,
            "frame_duration_ns" to c.frameDurationNs,
            "manual_sensor"   to c.manualSensor,
            // Óptica
            "focal_length"    to 4.30f,
            "aperture"        to 1.5f,
            "zoom"            to c.zoomLevel,
            // Imagem
            "wb"              to c.whiteBalanceMode,
            "ois"             to c.oisEnabled,
            "eis"             to c.eisEnabled,
            "ae_lock"         to c.aeLocked,
            "awb_lock"        to c.awbLocked,
            "torch"           to c.lanternEnabled,
            "edge"            to edgeModeStr(c.edgeMode),
            "nr"              to nrModeStr(c.noiseReductionMode),
            "hot_pixel"       to hotPixelModeStr(c.hotPixelMode),
            // RGGB
            "rggb_enabled"    to c.rggbEnabled,
            "rggb_r"          to c.rggbGains[0],
            "rggb_gr"         to c.rggbGains[1],
            "rggb_gb"         to c.rggbGains[2],
            "rggb_b"          to c.rggbGains[3],
            // Zoom óptico
            "optical_zoom_index"  to c.opticalZoomIndex,
            "optical_zoom_levels" to c.opticalZoomLevels,
            // Monitor ao vivo (TotalCaptureResult)
            "monitor"         to monitor,
            // Câmeras disponíveis — app.js usa para construir botões na 1ª carga
            "cameras"         to cameras
        )

        return okJson(gson.toJson(status))
    }

    // ── POST /api/control ──────────────────────────────────────────────────────
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

            // Ações de stream
            (params["streamAction"] as? String)?.let { action ->
                val svc = StreamingService.instance
                when (action) {
                    "start"   -> svc?.startStream()
                    "stop"    -> svc?.stopStream()
                    "restart" -> svc?.let { it.stopStream(); it.startStream() }
                }
                return okJson("""{"status":"ok","action":"$action"}""")
            }

            // Troca de URL RTMP
            (params["rtmpUrl"] as? String)?.let { newUrl ->
                StreamingService.instance?.let { svc ->
                    svc.rtmpUrl = newUrl
                    svc.stopStream()
                    svc.startStream()
                }
            }

            // Troca de câmera — invalida cache de capabilities
            if (params.containsKey("camera") || params.containsKey("camera_id")) {
                capsCache = null
                capsCacheForCamId = null
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

    // ── Helpers de conversão de modo ───────────────────────────────────────────
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

    // ── Resposta JSON com CORS ─────────────────────────────────────────────────
    private fun okJson(body: String): Response {
        val resp = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", body)
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }
}
