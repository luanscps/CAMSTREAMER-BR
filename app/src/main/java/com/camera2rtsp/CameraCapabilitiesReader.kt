package com.camera2rtsp

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size

object CameraCapabilitiesReader {

    fun read(context: Context, cameraId: String): CameraCapabilities? {
        return try {
            val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = mgr.getCameraCharacteristics(cameraId)

            val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                else -> "UNKNOWN"
            }

            val hwLevel = when (chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                else -> "LIMITED"
            }

            val availCaps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()

            val supportsManualSensor = availCaps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
            )
            val supportsManualPostProc = availCaps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
            )
            val supportsRaw = availCaps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
            )
            val supportsBurst = availCaps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE
            )
            val supportsDepth = availCaps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT
            )
            val supportsLogical = availCaps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
            )
            val isDepth = supportsDepth && !availCaps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE
            )

            val focalLen = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf()
            val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf()

            val name = when (facing) {
                "FRONT" -> "Frontal"
                else -> {
                    val fl = focalLen.firstOrNull() ?: 4f
                    when {
                        fl < 2.5f -> "Ultra Wide"
                        fl > 6f -> "Tele"
                        else -> "Wide"
                    }
                }
            }

            val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            val focusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

            val zoomRatioRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                    listOf(it.lower, it.upper)
                }
            } else null

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

            val videoStabilizationModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?.map { videoStabilizationModeToStr(it) }
                ?: emptyList()

            val distortionModes =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    chars.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
                        ?.map { distortionCorrectionModeToStr(it) } ?: emptyList()
                } else emptyList()

            val nrModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                ?.map { nrModeToStr(it) } ?: emptyList()

            val edgeModes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
                ?.map { edgeModeToStr(it) } ?: emptyList()

            val hotPixelModes = chars.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)
                ?.map { hotPixelModeToStr(it) } ?: emptyList()

            val faceDetectModes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
                ?.map { faceDetectModeToStr(it) } ?: emptyList()

            val focusCalibration = when (chars.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)) {
                CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "CALIBRATED"
                CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "APPROXIMATE"
                else -> "UNCALIBRATED"
            }

            val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val hasOis = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true

            val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            val jpegResolutions = getOutputSizes(streamMap, ImageFormat.JPEG)
            val yuvResolutions = getOutputSizes(streamMap, ImageFormat.YUV_420_888)
            val rawResolutions = getOutputSizes(streamMap, ImageFormat.RAW_SENSOR)
            val availableResolutions = jpegResolutions.ifEmpty { yuvResolutions }

            val highSpeedVideoSizes = getHighSpeedSizes(streamMap)
            val highSpeedVideoFpsRanges = getHighSpeedFpsRanges(streamMap)

            val outputFormats = streamMap
                ?.outputFormats
                ?.map { imageFormatToStr(it) }
                ?.distinct()
                ?: emptyList()

            val resolutionFpsMap = buildResolutionFpsMap(
                resolutions = (availableResolutions + yuvResolutions + highSpeedVideoSizes).distinct(),
                normalFpsRanges = fpsRanges,
                highSpeedRanges = highSpeedVideoFpsRanges,
                highSpeedSizes = highSpeedVideoSizes
            )

            val pixelArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val sensorPixelArraySize = pixelArraySize?.let { listOf(it.width, it.height) }

            val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val activeArraySize = activeArray?.let {
                listOf(it.left, it.top, it.width(), it.height())
            }

            val physicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val sensorPhysicalSize = physicalSize?.let { listOf(it.width, it.height) }

            val croppingType = when (chars.get(CameraCharacteristics.SCALER_CROPPING_TYPE)) {
                CameraCharacteristics.SCALER_CROPPING_TYPE_FREEFORM -> "FREEFORM"
                CameraCharacteristics.SCALER_CROPPING_TYPE_CENTER_ONLY -> "CENTER_ONLY"
                else -> "CENTER_ONLY"
            }

            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val maxRegionsAf = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            val maxRegionsAe = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            val maxRegionsAwb = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB) ?: 0
            val maxFaceCount = chars.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT) ?: 0
            val aeLockAvailable = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
            val awbLockAvailable = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true

            val physicalCameraIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                chars.physicalCameraIds?.toList()
            } else emptyList()

            val timestampSource = when (chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)) {
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN"
                else -> null
            }

            CameraCapabilities(
                cameraId = cameraId,
                hardwareLevel = hwLevel,
                facing = facing,
                name = name,
                isDepth = isDepth,

                supportsManualSensor = supportsManualSensor,
                supportsManualPostProcessing = supportsManualPostProc,
                supportsRaw = supportsRaw,
                supportsBurstCapture = supportsBurst,
                supportsDepthOutput = supportsDepth,
                supportsLogicalMultiCamera = supportsLogical,

                isoRange = if (isoRange != null) listOf(isoRange.lower, isoRange.upper) else null,
                exposureTimeRange = if (expRange != null) listOf(expRange.lower, expRange.upper) else null,
                evRange = if (evRange != null) listOf(evRange.lower, evRange.upper) else null,
                focusDistanceRange = if (focusDist != null) listOf(0f, focusDist) else null,
                zoomRange = listOf(1f, maxZoom),
                zoomRatioRange = zoomRatioRange,
                fpsRanges = fpsRanges,
                highSpeedVideoFpsRanges = highSpeedVideoFpsRanges,

                availableResolutions = availableResolutions,
                yuvResolutions = yuvResolutions,
                jpegResolutions = jpegResolutions,
                rawResolutions = rawResolutions,
                highSpeedVideoSizes = highSpeedVideoSizes,
                outputFormats = outputFormats,
                resolutionFpsMap = resolutionFpsMap,

                supportedAfModes = afModes,
                supportedAeModes = aeModes,
                supportedAwbModes = awbModes,
                supportedSceneModes = sceneModes,
                supportedEffectModes = effectModes,
                videoStabilizationModes = videoStabilizationModes,
                distortionCorrectionModes = distortionModes,
                noiseReductionModes = nrModes,
                edgeModes = edgeModes,
                hotPixelModes = hotPixelModes,
                faceDetectModes = faceDetectModes,

                hasFlash = hasFlash,
                hasOis = hasOis,
                focalLengths = focalLen.toList(),
                apertures = apertures.toList(),

                focusDistanceCalibration = focusCalibration,
                lensMinFocusDistance = focusDist,

                sensorPixelArraySize = sensorPixelArraySize,
                activeArraySize = activeArraySize,
                sensorPhysicalSize = sensorPhysicalSize,
                sensorOrientation = sensorOrientation,
                scalerCroppingType = croppingType,

                aeCompensationStep = evStep?.toFloat(),
                aeLockAvailable = aeLockAvailable,
                awbLockAvailable = awbLockAvailable,

                maxRegionsAf = maxRegionsAf,
                maxRegionsAe = maxRegionsAe,
                maxRegionsAwb = maxRegionsAwb,
                maxFaceCount = maxFaceCount,

                physicalCameraIds = physicalCameraIds,
                timestampSource = timestampSource,

                maxDigitalZoom = maxZoom,
                logicalZoomSupported = zoomRatioRange != null
            )
        } catch (e: Exception) {
            Log.e("CameraCapReader", "Erro ao ler caps id=$cameraId", e)
            null
        }
    }

    private fun buildResolutionFpsMap(
        resolutions: List<String>,
        normalFpsRanges: List<List<Int>>,
        highSpeedRanges: List<List<Int>>,
        highSpeedSizes: List<String>
    ): Map<String, List<Int>> {
        val candidates = listOf(15, 24, 30, 60, 120, 240)
        val normal = collectSupportedFps(normalFpsRanges, candidates)
        val high = collectSupportedFps(highSpeedRanges, candidates)
        val map = linkedMapOf<String, List<Int>>()

        resolutions.distinct().forEach { res ->
            val fps = if (highSpeedSizes.contains(res)) {
                (normal + high).distinct().sorted()
            } else {
                normal.distinct().sorted()
            }
            map[res] = fps
        }
        return map
    }

    private fun collectSupportedFps(ranges: List<List<Int>>, candidates: List<Int>): List<Int> {
        return candidates.filter { fps ->
            ranges.any { it.size >= 2 && fps >= it[0] && fps <= it[1] }
        }
    }

    private fun getOutputSizes(map: StreamConfigurationMap?, format: Int): List<String> {
        return map?.getOutputSizes(format)
            ?.filter { it.width >= 640 }
            ?.sortedByDescending { it.width * it.height }
            ?.map { "${it.width}x${it.height}" }
            ?: emptyList()
    }

    private fun getHighSpeedSizes(map: StreamConfigurationMap?): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            map?.highSpeedVideoSizes
                ?.sortedByDescending { it.width * it.height }
                ?.map { "${it.width}x${it.height}" }
                ?: emptyList()
        } else emptyList()
    }

    private fun getHighSpeedFpsRanges(map: StreamConfigurationMap?): List<List<Int>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || map == null) return emptyList()
        val out = mutableListOf<List<Int>>()
        map.highSpeedVideoSizes?.forEach { sz ->
            map.getHighSpeedVideoFpsRangesFor(sz)?.forEach { range ->
                val pair = listOf(range.lower, range.upper)
                if (!out.contains(pair)) out.add(pair)
            }
        }
        return out.sortedBy { it.getOrNull(1) ?: 0 }
    }

    private fun afModeToStr(m: Int) = when (m) {
        CameraMetadata.CONTROL_AF_MODE_OFF -> "off"
        CameraMetadata.CONTROL_AF_MODE_AUTO -> "auto"
        CameraMetadata.CONTROL_AF_MODE_MACRO -> "macro"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "continuous-video"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "continuous-picture"
        CameraMetadata.CONTROL_AF_MODE_EDOF -> "edof"
        else -> "auto"
    }

    private fun aeModeToStr(m: Int) = when (m) {
        CameraMetadata.CONTROL_AE_MODE_OFF -> "off"
        CameraMetadata.CONTROL_AE_MODE_ON -> "on"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH -> "on_auto_flash"
        CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> "on_always_flash"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> "on_auto_flash_redeye"
        else -> "on"
    }

    private fun awbModeToStr(m: Int) = when (m) {
        CameraMetadata.CONTROL_AWB_MODE_AUTO -> "auto"
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "incandescent"
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "fluorescent"
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "warm_fluorescent"
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "daylight"
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "cloudy"
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "twilight"
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> "shade"
        else -> "auto"
    }

    private fun sceneModeToStr(m: Int) = when (m) {
        CameraMetadata.CONTROL_SCENE_MODE_DISABLED -> "disabled"
        CameraMetadata.CONTROL_SCENE_MODE_ACTION -> "action"
        CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT -> "portrait"
        CameraMetadata.CONTROL_SCENE_MODE_LANDSCAPE -> "landscape"
        CameraMetadata.CONTROL_SCENE_MODE_NIGHT -> "night"
        CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT -> "night_portrait"
        CameraMetadata.CONTROL_SCENE_MODE_THEATRE -> "theatre"
        CameraMetadata.CONTROL_SCENE_MODE_BEACH -> "beach"
        CameraMetadata.CONTROL_SCENE_MODE_SNOW -> "snow"
        CameraMetadata.CONTROL_SCENE_MODE_SUNSET -> "sunset"
        CameraMetadata.CONTROL_SCENE_MODE_STEADYPHOTO -> "steadyphoto"
        CameraMetadata.CONTROL_SCENE_MODE_FIREWORKS -> "fireworks"
        CameraMetadata.CONTROL_SCENE_MODE_SPORTS -> "sports"
        CameraMetadata.CONTROL_SCENE_MODE_PARTY -> "party"
        CameraMetadata.CONTROL_SCENE_MODE_CANDLELIGHT -> "candlelight"
        CameraMetadata.CONTROL_SCENE_MODE_BARCODE -> "barcode"
        17 -> "high_speed_video"
        CameraMetadata.CONTROL_SCENE_MODE_HDR -> "hdr"
        else -> "unknown_$m"
    }

    private fun effectModeToStr(m: Int) = when (m) {
        CameraMetadata.CONTROL_EFFECT_MODE_OFF -> "off"
        CameraMetadata.CONTROL_EFFECT_MODE_MONO -> "mono"
        CameraMetadata.CONTROL_EFFECT_MODE_NEGATIVE -> "negative"
        CameraMetadata.CONTROL_EFFECT_MODE_SOLARIZE -> "solarize"
        CameraMetadata.CONTROL_EFFECT_MODE_SEPIA -> "sepia"
        CameraMetadata.CONTROL_EFFECT_MODE_POSTERIZE -> "posterize"
        CameraMetadata.CONTROL_EFFECT_MODE_WHITEBOARD -> "whiteboard"
        CameraMetadata.CONTROL_EFFECT_MODE_BLACKBOARD -> "blackboard"
        CameraMetadata.CONTROL_EFFECT_MODE_AQUA -> "aqua"
        else -> "unknown_$m"
    }

    private fun videoStabilizationModeToStr(m: Int) = when (m) {
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF -> "off"
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "on"
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION -> "preview_stabilization"
        else -> "unknown_$m"
    }

    private fun distortionCorrectionModeToStr(m: Int) = when (m) {
        CameraMetadata.DISTORTION_CORRECTION_MODE_OFF -> "off"
        CameraMetadata.DISTORTION_CORRECTION_MODE_FAST -> "fast"
        CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY -> "high_quality"
        else -> "unknown_$m"
    }

    private fun nrModeToStr(m: Int) = when (m) {
        CameraMetadata.NOISE_REDUCTION_MODE_OFF -> "off"
        CameraMetadata.NOISE_REDUCTION_MODE_FAST -> "fast"
        CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY -> "high_quality"
        CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL -> "minimal"
        CameraMetadata.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG -> "zero_shutter_lag"
        else -> "unknown_$m"
    }

    private fun edgeModeToStr(m: Int) = when (m) {
        CameraMetadata.EDGE_MODE_OFF -> "off"
        CameraMetadata.EDGE_MODE_FAST -> "fast"
        CameraMetadata.EDGE_MODE_HIGH_QUALITY -> "high_quality"
        CameraMetadata.EDGE_MODE_ZERO_SHUTTER_LAG -> "zero_shutter_lag"
        else -> "unknown_$m"
    }

    private fun hotPixelModeToStr(m: Int) = when (m) {
        CameraMetadata.HOT_PIXEL_MODE_OFF -> "off"
        CameraMetadata.HOT_PIXEL_MODE_FAST -> "fast"
        CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY -> "high_quality"
        else -> "unknown_$m"
    }

    private fun faceDetectModeToStr(m: Int) = when (m) {
        CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF -> "off"
        CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE -> "simple"
        CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL -> "full"
        else -> "unknown_$m"
    }

    private fun imageFormatToStr(format: Int): String = when (format) {
        ImageFormat.JPEG -> "JPEG"
        ImageFormat.YUV_420_888 -> "YUV_420_888"
        ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
        ImageFormat.PRIVATE -> "PRIVATE"
        ImageFormat.DEPTH16 -> "DEPTH16"
        else -> "FMT_$format"
    }
}