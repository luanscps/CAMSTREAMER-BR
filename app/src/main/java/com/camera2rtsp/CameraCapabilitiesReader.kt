package com.camera2rtsp

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Log
import android.util.Size

/**
 * Le as capabilities reais da camera via Camera2 API e preenche
 * um CameraCapabilities. Chamado em loadCapabilities() sempre que
 * a camera ativa muda.
 */
object CameraCapabilitiesReader {

    fun read(context: Context, cameraId: String): CameraCapabilities? {
        return try {
            val mgr   = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = mgr.getCameraCharacteristics(cameraId)

            val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT    -> "FRONT"
                CameraCharacteristics.LENS_FACING_BACK     -> "BACK"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                else -> "UNKNOWN"
            }

            val hwLevel = when (chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY  -> "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL    -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3       -> "LEVEL_3"
                else -> "LIMITED"
            }

            val availCaps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()

            val supportsManualSensor = availCaps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val supportsManualPostProc = availCaps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
            val supportsRaw = availCaps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            val supportsBurst = availCaps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE)
            val supportsDepth = availCaps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)
            val supportsLogical = availCaps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            val isDepth = supportsDepth && !availCaps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)

            val focalLen = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf()
            val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf()

            val name = when (facing) {
                "FRONT" -> "Frontal"
                else -> {
                    val fl = focalLen.firstOrNull() ?: 4f
                    when {
                        fl < 2.5f -> "Ultra Wide"
                        fl > 6f   -> "Tele"
                        else      -> "Wide"
                    }
                }
            }

            val isoRange  = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val expRange  = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val evRange   = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val focusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            val maxZoom   = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

            val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { listOf(it.lower, it.upper) } ?: emptyList()

            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.map { afModeToStr(it) } ?: listOf("auto")

            val aeModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
                ?.map { aeModeToStr(it) } ?: listOf("on")

            val awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                ?.map { awbModeToStr(it) } ?: listOf("auto")

            val sceneModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
                ?.map { sceneModeToStr(it) }
                ?.filter { it != "disabled" }
                ?: emptyList()

            val effectModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS)
                ?.map { effectModeToStr(it) }
                ?.filter { it != "off" }
                ?: emptyList()

            val focusCalibration = when (chars.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)) {
                CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED  -> "CALIBRATED"
                CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "APPROXIMATE"
                else -> "UNCALIBRATED"
            }

            val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val hasOis   = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true

            val streamCfg = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            // Resoluções de streaming: YUV_420_888 filtradas por tier 16:9
            val yuvSizes = streamCfg?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
            val resolutions = buildStreamingResolutions(yuvSizes)

            // Resolução RAW real do sensor — tamanho que o ImageReader RAW_SENSOR aceita
            val rawResolution: String? = if (supportsRaw) {
                streamCfg?.getOutputSizes(ImageFormat.RAW_SENSOR)
                    ?.maxByOrNull { it.width * it.height }
                    ?.let { "${it.width}x${it.height}" }
            } else null

            // ── campos extras ─────────────────────────────────────────────────

            val pixelArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val sensorPixelArraySize = pixelArraySize?.let { listOf(it.width, it.height) }

            val physicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val sensorPhysicalSize = physicalSize?.let { listOf(it.width, it.height) }

            val lensMinFocusDistance = focusDist

            val croppingType = when (chars.get(CameraCharacteristics.SCALER_CROPPING_TYPE)) {
                CameraCharacteristics.SCALER_CROPPING_TYPE_FREEFORM    -> "FREEFORM"
                CameraCharacteristics.SCALER_CROPPING_TYPE_CENTER_ONLY -> "CENTER_ONLY"
                else -> "CENTER_ONLY"
            }

            val maxRegionsAf = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            val maxRegionsAe = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            val maxFaceCount = chars.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT) ?: 0

            CameraCapabilities(
                cameraId                     = cameraId,
                hardwareLevel                = hwLevel,
                facing                       = facing,
                name                         = name,
                isDepth                      = isDepth,
                supportsManualSensor         = supportsManualSensor,
                supportsManualPostProcessing = supportsManualPostProc,
                supportsRaw                  = supportsRaw,
                supportsBurstCapture         = supportsBurst,
                supportsDepthOutput          = supportsDepth,
                supportsLogicalMultiCamera   = supportsLogical,
                isoRange                     = if (isoRange != null) listOf(isoRange.lower, isoRange.upper) else null,
                exposureTimeRange            = if (expRange != null) listOf(expRange.lower, expRange.upper) else null,
                evRange                      = if (evRange  != null) listOf(evRange.lower, evRange.upper)   else null,
                focusDistanceRange           = if (focusDist != null) listOf(0f, focusDist) else null,
                zoomRange                    = listOf(1f, maxZoom),
                fpsRanges                    = fpsRanges,
                availableResolutions         = resolutions,
                rawResolution                = rawResolution,
                supportedAfModes             = afModes,
                supportedAeModes             = aeModes,
                supportedAwbModes            = awbModes,
                supportedSceneModes          = sceneModes,
                supportedEffectModes         = effectModes,
                hasFlash                     = hasFlash,
                hasOis                       = hasOis,
                focalLengths                 = focalLen.toList(),
                apertures                    = apertures.toList(),
                focusDistanceCalibration     = focusCalibration,
                sensorPixelArraySize         = sensorPixelArraySize,
                sensorPhysicalSize           = sensorPhysicalSize,
                lensMinFocusDistance         = lensMinFocusDistance,
                scalerCroppingType           = croppingType,
                maxRegionsAf                 = maxRegionsAf,
                maxRegionsAe                 = maxRegionsAe,
                maxFaceCount                 = maxFaceCount
            )
        } catch (e: Exception) {
            Log.e("CameraCapReader", "Erro ao ler caps id=$cameraId", e)
            null
        }
    }

    /**
     * Seleciona até 1 resolução por tier (4K/1080p/720p) priorizando aspecto 16:9.
     * Aspecto 16:9 = largura/altura entre 1.70 e 1.82.
     * Fallback: maior área do tier se não houver 16:9 disponível.
     */
    private fun buildStreamingResolutions(sizes: Array<Size>): List<String> {
        data class Tier(val minH: Int, val maxH: Int)
        val tiers = listOf(
            Tier(2160, Int.MAX_VALUE), // 4K
            Tier(1080, 2159),          // 1080p
            Tier(720,  1079)           // 720p
        )
        return tiers.mapNotNull { tier ->
            val candidates = sizes.filter { it.height in tier.minH..tier.maxH }
            if (candidates.isEmpty()) return@mapNotNull null
            val widescreen = candidates.filter {
                val ratio = it.width.toFloat() / it.height
                ratio in 1.70f..1.82f
            }
            val best = if (widescreen.isNotEmpty())
                widescreen.maxByOrNull { it.width * it.height }
            else
                candidates.maxByOrNull { it.width * it.height }
            best?.let { "${it.width}x${it.height}" }
        }
    }

    private fun afModeToStr(m: Int) = when (m) {
        CameraMetadata.CONTROL_AF_MODE_OFF                -> "off"
        CameraMetadata.CONTROL_AF_MODE_AUTO               -> "auto"
        CameraMetadata.CONTROL_AF_MODE_MACRO              -> "macro"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO   -> "continuous-video"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "continuous-picture"
        else -> "auto"
    }

    private fun aeModeToStr(m: Int) = when (m) {
        CameraMetadata.CONTROL_AE_MODE_OFF                          -> "off"
        CameraMetadata.CONTROL_AE_MODE_ON                           -> "on"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH                -> "on_auto_flash"
        CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH              -> "on_always_flash"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE         -> "on_auto_flash_redeye"
        else -> "on"
    }

    private fun awbModeToStr(m: Int) = when (m) {
        CameraMetadata.CONTROL_AWB_MODE_AUTO             -> "auto"
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT     -> "incandescent"
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT      -> "fluorescent"
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "warm_fluorescent"
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT         -> "daylight"
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT  -> "cloudy"
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT         -> "twilight"
        CameraMetamer.CONTROL_AWB_MODE_SHADE            -> "shade"
        else -> "auto"
    }

    private fun sceneModeToStr(m: Int) = when (m) {
        CameraMetadata.CONTROL_SCENE_MODE_DISABLED        -> "disabled"
        CameraMetadata.CONTROL_SCENE_MODE_ACTION          -> "action"
        CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT        -> "portrait"
        CameraMetadata.CONTROL_SCENE_MODE_LANDSCAPE       -> "landscape"
        CameraMetadata.CONTROL_SCENE_MODE_NIGHT           -> "night"
        CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT  -> "night_portrait"
        CameraMetadata.CONTROL_SCENE_MODE_THEATRE         -> "theatre"
        CameraMetadata.CONTROL_SCENE_MODE_BEACH           -> "beach"
        CameraMetadata.CONTROL_SCENE_MODE_SNOW            -> "snow"
        CameraMetadata.CONTROL_SCENE_MODE_SUNSET          -> "sunset"
        CameraMetadata.CONTROL_SCENE_MODE_STEADYPHOTO     -> "steadyphoto"
        CameraMetadata.CONTROL_SCENE_MODE_FIREWORKS       -> "fireworks"
        CameraMetadata.CONTROL_SCENE_MODE_SPORTS          -> "sports"
        CameraMetadata.CONTROL_SCENE_MODE_PARTY           -> "party"
        CameraMetadata.CONTROL_SCENE_MODE_CANDLELIGHT     -> "candlelight"
        CameraMetadata.CONTROL_SCENE_MODE_BARCODE         -> "barcode"
        17                                                -> "high_speed_video"
        CameraMetadata.CONTROL_SCENE_MODE_HDR             -> "hdr"
        else -> "unknown_$m"
    }

    private fun effectModeToStr(m: Int) = when (m) {
        CameraMetadata.CONTROL_EFFECT_MODE_OFF        -> "off"
        CameraMetadata.CONTROL_EFFECT_MODE_MONO       -> "mono"
        CameraMetadata.CONTROL_EFFECT_MODE_NEGATIVE   -> "negative"
        CameraMetadata.CONTROL_EFFECT_MODE_SOLARIZE   -> "solarize"
        CameraMetadata.CONTROL_EFFECT_MODE_SEPIA      -> "sepia"
        CameraMetadata.CONTROL_EFFECT_MODE_POSTERIZE  -> "posterize"
        CameraMetadata.CONTROL_EFFECT_MODE_WHITEBOARD -> "whiteboard"
        CameraMetadata.CONTROL_EFFECT_MODE_BLACKBOARD -> "blackboard"
        CameraMetadata.CONTROL_EFFECT_MODE_AQUA       -> "aqua"
        else -> "unknown_$m"
    }
}
