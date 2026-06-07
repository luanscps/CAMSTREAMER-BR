package com.camera2rtsp

import android.content.Context
import android.util.Log
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.IHTTPSession

object WebControlApi {

    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()

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

    fun serveCapabilities(cameraController: Camera2Controller, context: Context): Response {
        return okJson(gson.toJson(getCapsCache(cameraController, context)))
    }

    fun serveStatus(cameraController: Camera2Controller, context: Context): Response {
        val c = cameraController
        val streaming = c.rtmpCamera?.isStreaming == true
        val cameras = getCapsCache(c, context)

        val focusCalibration = try {
            cameras.firstOrNull { it.cameraId == c.currentCameraId }
                ?.focusDistanceCalibration ?: "UNCALIBRATED"
        } catch (e: Exception) { "UNCALIBRATED" }

        val focusCaps = cameras.firstOrNull { it.cameraId == c.currentCameraId }
        val focalLength = focusCaps?.focalLengths?.firstOrNull() ?: 4.30f
        val aperture = focusCaps?.apertures?.firstOrNull() ?: 1.5f

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

        val advancedVision = mapOf(
            "yuv_enabled"      to c.yuvProcessorEnabled,
            "raw_enabled"      to c.rawCaptureEnabled,
            "depth_enabled"    to c.depthFusionEnabled,
            "last_yuv_ts_ns"   to c.lastYuvTimestampNs,
            "depth_mean_mm"    to c.lastDepthMeanMm,
            "depth_min_mm"     to c.lastDepthMinMm,
            "depth_max_mm"     to c.lastDepthMaxMm,
            "raw_ready"        to (c.lastRawDngBytes?.isNotEmpty() == true),
            "raw_filename"     to c.lastRawFilename
        )

        val status = mapOf(
            "streaming"          to streaming,
            "rtmp_url"           to (StreamingService.instance?.rtmpUrl ?: ""),
            "camera_id"          to c.currentCameraId,
            "resolution"         to "${c.currentWidth}x${c.currentHeight}",
            "bitrate_kbps"       to c.currentBitrate,
            "fps"                to c.currentFps,
            "focus_mode"         to (if (c.autoFocus) "continuous-video" else "off"),
            "focus_dist"         to c.focusDistance,
            "focus_distance_calibration" to focusCalibration,
            "iso"                to c.isoValue,
            "exposure_ns"        to c.exposureNs,
            "frame_duration_ns"  to c.frameDurationNs,
            "manual_sensor"      to c.manualSensor,
            "focal_length"       to focalLength,
            "aperture"           to aperture,
            "zoom"               to c.zoomLevel,
            "wb"                 to c.whiteBalanceMode,
            "ois"                to c.oisEnabled,
            "eis"                to c.eisEnabled,
            "ae_lock"            to c.aeLocked,
            "awb_lock"           to c.awbLocked,
            "torch"              to c.lanternEnabled,
            "edge"               to edgeModeStr(c.edgeMode),
            "nr"                 to nrModeStr(c.noiseReductionMode),
            "hot_pixel"          to hotPixelModeStr(c.hotPixelMode),
            "rggb_enabled"       to c.rggbEnabled,
            "rggb_r"             to c.rggbGains[0],
            "rggb_gr"            to c.rggbGains[1],
            "rggb_gb"            to c.rggbGains[2],
            "rggb_b"             to c.rggbGains[3],
            "optical_zoom_index"  to c.opticalZoomIndex,
            "optical_zoom_levels" to c.opticalZoomLevels,
            "monitor"            to monitor,
            "advanced_vision"    to advancedVision,
            "yuv_enabled"        to c.yuvProcessorEnabled,
            "raw_enabled"        to c.rawCaptureEnabled,
            "depth_enabled"      to c.depthFusionEnabled,
            "last_yuv_ts_ns"     to c.lastYuvTimestampNs,
            "depth_mean_mm"      to c.lastDepthMeanMm,
            "depth_min_mm"       to c.lastDepthMinMm,
            "depth_max_mm"       to c.lastDepthMaxMm,
            "cameras"            to cameras
        )

        return okJson(gson.toJson(status))
    }

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

            (params["streamAction"] as? String)?.let { action ->
                val svc = StreamingService.instance
                when (action) {
                    "start"   -> svc?.startStream()
                    "stop"    -> svc?.stopStream()
                    "restart" -> svc?.let { it.stopStream(); it.startStream() }
                }
                return okJson("""{"status":"ok","action":"$action"}""")
            }

            (params["rtmpUrl"] as? String)?.let { newUrl ->
                StreamingService.instance?.let { svc ->
                    svc.rtmpUrl = newUrl
                    svc.stopStream()
                    svc.startStream()
                }
            }

            if (
                params.containsKey("camera") ||
                params.containsKey("camera_id") ||
                params.containsKey("yuvCapture") ||
                params.containsKey("rawCapture") ||
                params.containsKey("depthFusion")
            ) {
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

    /**
     * POST /api/raw/capture
     * Dispara captureRawStill() e retorna 202 imediatamente.
     * O app.js faz polling em GET /api/raw/result?poll=1 para saber quando ficou pronto.
     */
    fun handleRawCapture(
        cameraController: Camera2Controller,
        context: Context
    ): Response {
        return try {
            // Limpa o resultado anterior para que o polling nao entregue foto velha
            cameraController.lastRawDngBytes = null
            cameraController.lastRawFilename = ""

            cameraController.captureRawStill(context)

            Log.i("WebControlApi", "handleRawCapture: disparo enviado, retornando 202")
            val resp = NanoHTTPD.newFixedLengthResponse(
                Response.Status.ACCEPTED,
                "application/json",
                """{"status":"capturing","message":"Captura iniciada. Consulte GET /api/raw/result?poll=1"}"""
            )
            resp.addHeader("Access-Control-Allow-Origin", "*")
            resp
        } catch (e: Exception) {
            Log.e("WebControlApi", "handleRawCapture falhou", e)
            NanoHTTPD.newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "application/json",
                """{"status":"error","message":"${e.message}"}"""
            )
        }
    }

    /**
     * GET /api/raw/result
     * Com ?poll=1: retorna JSON {ready, filename, size_kb}.
     * Sem ?poll=1: retorna o DNG binario para download se pronto, 404 se nao.
     * Apos servir o download, zera lastRawDngBytes para evitar entregar foto velha.
     */
    fun serveRawResult(
        session: IHTTPSession,
        cameraController: Camera2Controller
    ): Response {
        val isPoll = session.parameters["poll"]?.firstOrNull() == "1"
        val dngBytes = cameraController.lastRawDngBytes
        val filename = cameraController.lastRawFilename

        if (isPoll) {
            val ready = dngBytes != null && dngBytes.isNotEmpty()
            val sizeKb = if (ready) dngBytes!!.size / 1024 else 0
            val resp = okJson("""{"ready":$ready,"filename":"$filename","size_kb":$sizeKb}""")
            resp.addHeader("Access-Control-Allow-Origin", "*")
            return resp
        }

        if (dngBytes == null || dngBytes.isEmpty()) {
            val resp = NanoHTTPD.newFixedLengthResponse(
                Response.Status.NOT_FOUND, "application/json",
                """{"status":"not_ready","message":"Nenhuma captura RAW disponivel"}"""
            )
            resp.addHeader("Access-Control-Allow-Origin", "*")
            return resp
        }

        val safeFilename = if (filename.isNotBlank()) filename else "raw_capture.dng"

        // fix: zera apos servir o download para evitar entregar DNG antigo em nova captura
        cameraController.lastRawDngBytes = null
        cameraController.lastRawFilename = ""
        Log.i("WebControlApi", "serveRawResult: DNG servido e cache zerado ($safeFilename)")

        val resp = NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK,
            "image/x-adobe-dng",
            java.io.ByteArrayInputStream(dngBytes),
            dngBytes.size.toLong()
        )
        resp.addHeader("Content-Disposition", "attachment; filename=\"$safeFilename\"")
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Access-Control-Expose-Headers", "Content-Disposition")
        return resp
    }

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

    private fun okJson(body: String): Response {
        val resp = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", body)
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }
}
