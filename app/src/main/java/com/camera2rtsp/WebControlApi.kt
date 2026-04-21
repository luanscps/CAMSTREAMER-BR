package com.camera2rtsp

import android.content.Context
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response

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

        val currentCap = cameras.firstOrNull { it.cameraId == c.currentCameraId }

        val monitor = mapOf(
            "iso" to c.liveIso,
            "shutter_ns" to c.liveExposureNs,
            "af_state" to c.liveAfState,
            "ae_state" to c.liveAeState,
            "rggb_r" to c.liveRggbR,
            "rggb_gr" to c.liveRggbGr,
            "rggb_gb" to c.liveRggbGb,
            "rggb_b" to c.liveRggbB
        )

        val lensInfo = mapOf(
            "name" to currentCap?.name,
            "facing" to currentCap?.facing,
            "focal_lengths" to (currentCap?.focalLengths ?: emptyList<Float>()),
            "apertures" to (currentCap?.apertures ?: emptyList<Float>()),
            "focus_distance_calibration" to (currentCap?.focusDistanceCalibration ?: "UNCALIBRATED"),
            "lens_min_focus_distance" to currentCap?.lensMinFocusDistance,
            "is_ultra_wide" to (currentCap?.isUltraWide() ?: false),
            "is_telephoto" to (currentCap?.isTelephoto() ?: false),
            "physical_camera_ids" to (currentCap?.physicalCameraIds ?: emptyList<String>())
        )

        val sensorInfo = mapOf(
            "sensor_orientation" to (currentCap?.sensorOrientation ?: 0),
            "sensor_pixel_array_size" to (currentCap?.sensorPixelArraySize ?: emptyList<Int>()),
            "active_array_size" to (currentCap?.activeArraySize ?: emptyList<Int>()),
            "sensor_physical_size" to (currentCap?.sensorPhysicalSize ?: emptyList<Float>()),
            "timestamp_source" to currentCap?.timestampSource,
            "hardware_level" to currentCap?.hardwareLevel,
            "scaler_cropping_type" to currentCap?.scalerCroppingType
        )

        val capabilityInfo = mapOf(
            "supports_manual_sensor" to (currentCap?.supportsManualSensor ?: false),
            "supports_manual_post_processing" to (currentCap?.supportsManualPostProcessing ?: false),
            "supports_raw" to (currentCap?.supportsRaw ?: false),
            "supports_burst_capture" to (currentCap?.supportsBurstCapture ?: false),
            "supports_depth_output" to (currentCap?.supportsDepthOutput ?: false),
            "supports_logical_multi_camera" to (currentCap?.supportsLogicalMultiCamera ?: false),
            "logical_zoom_supported" to (currentCap?.logicalZoomSupported ?: false),
            "ae_lock_available" to (currentCap?.aeLockAvailable ?: false),
            "awb_lock_available" to (currentCap?.awbLockAvailable ?: false),
            "supports_high_speed" to (currentCap?.supportsHighSpeed() ?: false),
            "supports_60fps" to (currentCap?.supports60Fps() ?: false),
            "has_variable_focus" to (currentCap?.hasVariableFocus() ?: false),
            "max_regions_af" to (currentCap?.maxRegionsAf ?: 0),
            "max_regions_ae" to (currentCap?.maxRegionsAe ?: 0),
            "max_regions_awb" to (currentCap?.maxRegionsAwb ?: 0),
            "max_face_count" to (currentCap?.maxFaceCount ?: 0)
        )

        val formatsInfo = mapOf(
            "output_formats" to (currentCap?.outputFormats ?: emptyList<String>()),
            "available_resolutions" to (currentCap?.availableResolutions ?: emptyList<String>()),
            "yuv_resolutions" to (currentCap?.yuvResolutions ?: emptyList<String>()),
            "jpeg_resolutions" to (currentCap?.jpegResolutions ?: emptyList<String>()),
            "raw_resolutions" to (currentCap?.rawResolutions ?: emptyList<String>()),
            "high_speed_video_sizes" to (currentCap?.highSpeedVideoSizes ?: emptyList<String>()),
            "resolution_fps_map" to (currentCap?.resolutionFpsMap ?: emptyMap<String, List<Int>>()),
            "fps_ranges" to (currentCap?.fpsRanges ?: emptyList<List<Int>>()),
            "high_speed_video_fps_ranges" to (currentCap?.highSpeedVideoFpsRanges ?: emptyList<List<Int>>())
        )

        val controlsInfo = mapOf(
            "supported_af_modes" to (currentCap?.supportedAfModes ?: emptyList<String>()),
            "supported_ae_modes" to (currentCap?.supportedAeModes ?: emptyList<String>()),
            "supported_awb_modes" to (currentCap?.supportedAwbModes ?: emptyList<String>()),
            "supported_scene_modes" to (currentCap?.supportedSceneModes ?: emptyList<String>()),
            "supported_effect_modes" to (currentCap?.supportedEffectModes ?: emptyList<String>()),
            "video_stabilization_modes" to (currentCap?.videoStabilizationModes ?: emptyList<String>()),
            "distortion_correction_modes" to (currentCap?.distortionCorrectionModes ?: emptyList<String>()),
            "noise_reduction_modes" to (currentCap?.noiseReductionModes ?: emptyList<String>()),
            "edge_modes" to (currentCap?.edgeModes ?: emptyList<String>()),
            "hot_pixel_modes" to (currentCap?.hotPixelModes ?: emptyList<String>()),
            "face_detect_modes" to (currentCap?.faceDetectModes ?: emptyList<String>()),
            "iso_range" to (currentCap?.isoRange ?: emptyList<Int>()),
            "exposure_time_range" to (currentCap?.exposureTimeRange ?: emptyList<Long>()),
            "ev_range" to (currentCap?.evRange ?: emptyList<Int>()),
            "ev_range_stops" to (currentCap?.evRangeInStops() ?: emptyList<Float>()),
            "ae_compensation_step" to currentCap?.aeCompensationStep,
            "focus_distance_range" to (currentCap?.focusDistanceRange ?: emptyList<Float>()),
            "zoom_range" to (currentCap?.zoomRange ?: emptyList<Float>()),
            "zoom_ratio_range" to (currentCap?.zoomRatioRange ?: emptyList<Float>()),
            "max_digital_zoom" to currentCap?.maxDigitalZoom
        )

        val status = mapOf(
            "streaming" to streaming,
            "rtmp_url" to (StreamingService.instance?.rtmpUrl ?: ""),

            "camera_id" to c.currentCameraId,
            "resolution" to "${c.currentWidth}x${c.currentHeight}",
            "bitrate_kbps" to c.currentBitrate,
            "fps" to c.currentFps,

            "focus_mode" to (if (c.autoFocus) "continuous-video" else "off"),
            "focus_dist" to c.focusDistance,

            "iso" to c.isoValue,
            "exposure_ns" to c.exposureNs,
            "frame_duration_ns" to c.frameDurationNs,
            "manual_sensor" to c.manualSensor,

            "focal_length" to currentCap?.focalLengths?.firstOrNull(),
            "aperture" to currentCap?.apertures?.firstOrNull(),
            "zoom" to c.zoomLevel,

            "wb" to c.whiteBalanceMode,
            "ois" to c.oisEnabled,
            "eis" to c.eisEnabled,
            "ae_lock" to c.aeLocked,
            "awb_lock" to c.awbLocked,
            "torch" to c.lanternEnabled,
            "edge" to edgeModeStr(c.edgeMode),
            "nr" to nrModeStr(c.noiseReductionMode),
            "hot_pixel" to hotPixelModeStr(c.hotPixelMode),

            "rggb_enabled" to c.rggbEnabled,
            "rggb_r" to c.rggbGains[0],
            "rggb_gr" to c.rggbGains[1],
            "rggb_gb" to c.rggbGains[2],
            "rggb_b" to c.rggbGains[3],

            "optical_zoom_index" to c.opticalZoomIndex,
            "optical_zoom_levels" to c.opticalZoomLevels,

            "monitor" to monitor,
            "lens_info" to lensInfo,
            "sensor_info" to sensorInfo,
            "capability_info" to capabilityInfo,
            "formats_info" to formatsInfo,
            "controls_info" to controlsInfo,

            "cameras" to cameras
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
                when (action) {
                    "start" -> cameraController.streamStart()
                    "stop" -> cameraController.streamStop()
                    "restart" -> cameraController.streamRestart()
                }
                return okJson("""{"status":"ok","action":"$action"}""")
            }

            (params["rtmpUrl"] as? String)?.let { newUrl ->
                cameraController.setRtmpUrlAndRestart(newUrl)
            }

            if (params.containsKey("camera") || params.containsKey("camera_id")) {
                capsCache = null
                capsCacheForCamId = null
            }

            cameraController.updateSettings(params)
            okJson("""{"status":"ok"}""")
        } catch (e: Exception) {
            NanoHTTPD.newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"status":"error","message":"${e.message}"}"""
            )
        }
    }

    private fun edgeModeStr(v: Int) = when (v) {
        android.hardware.camera2.CameraMetadata.EDGE_MODE_OFF -> "off"
        android.hardware.camera2.CameraMetadata.EDGE_MODE_FAST -> "fast"
        android.hardware.camera2.CameraMetadata.EDGE_MODE_HIGH_QUALITY -> "high_quality"
        else -> "high_quality"
    }

    private fun nrModeStr(v: Int) = when (v) {
        android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_OFF -> "off"
        android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL -> "minimal"
        android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_FAST -> "fast"
        android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY -> "high_quality"
        else -> "high_quality"
    }

    private fun hotPixelModeStr(v: Int) = when (v) {
        android.hardware.camera2.CameraMetadata.HOT_PIXEL_MODE_OFF -> "off"
        android.hardware.camera2.CameraMetadata.HOT_PIXEL_MODE_FAST -> "fast"
        android.hardware.camera2.CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY -> "high_quality"
        else -> "high_quality"
    }

    private fun okJson(body: String): Response {
        val resp = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", body)
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }
}