package com.camera2rtsp

data class CameraCapabilities(
    val cameraId: String,
    val hardwareLevel: String,
    val facing: String,
    val name: String,
    val isDepth: Boolean,
    val supportsManualSensor: Boolean,
    val supportsManualPostProcessing: Boolean,
    val supportsRaw: Boolean,
    val supportsBurstCapture: Boolean,
    val supportsDepthOutput: Boolean,
    val supportsLogicalMultiCamera: Boolean,
    val isoRange: List<Int>?,
    val exposureTimeRange: List<Long>?,
    val evRange: List<Int>?,
    val focusDistanceRange: List<Float>?,
    val zoomRange: List<Float>?,
    val fpsRanges: List<List<Int>>,
    val availableResolutions: List<String>,
    val supportedAfModes: List<String>,
    val supportedAeModes: List<String>,
    val supportedAwbModes: List<String>,
    val supportedSceneModes: List<String>,
    val supportedEffectModes: List<String>,
    val hasFlash: Boolean,
    val hasOis: Boolean,
    val focalLengths: List<Float>,
    val apertures: List<Float>,
    val focusDistanceCalibration: String,
    val sensorPixelArraySize: List<Int>?,
    val sensorPhysicalSize: List<Float>?,
    val lensMinFocusDistance: Float?,
    val scalerCroppingType: String,
    val maxRegionsAf: Int,
    val maxRegionsAe: Int,
    val maxFaceCount: Int
) {
    fun supportsYuvImageReader(): Boolean = availableResolutions.isNotEmpty()

    fun bestYuvWithConstantFps(targetFps: Int = 30): Pair<String, Int>? {
        val fps = fpsRanges
            .filter { it.size >= 2 && it[0] == it[1] && it[1] >= targetFps }
            .maxByOrNull { it[1] }
            ?.get(1) ?: return null
        val res = availableResolutions.firstOrNull() ?: return null
        return Pair(res, fps)
    }

    fun bestRawResolution(): String? = sensorPixelArraySize
        ?.takeIf { it.size >= 2 }
        ?.let { "${it[0]}x${it[1]}" }

    fun isRawCaptureFeasible(): Boolean =
        supportsRaw && supportsManualSensor && bestRawResolution() != null

    fun hasUsableDepthSensor(): Boolean = supportsDepthOutput && isDepth

    fun recommendedDepthResolution(): String? = if (isDepth) {
        sensorPixelArraySize
            ?.takeIf { it.size >= 2 }
            ?.let { "${it[0]}x${it[1]}" }
    } else null

    fun supportsYuvDepthFusion(): Boolean =
        supportsYuvImageReader() && hasUsableDepthSensor() &&
            (hardwareLevel == "FULL" || hardwareLevel == "LEVEL_3")

    fun depthFusionScore(): Int {
        var score = 0
        if (supportsYuvImageReader()) score += 30
        if (hasUsableDepthSensor()) score += 30
        if (supportsManualSensor) score += 20
        if (hardwareLevel == "FULL" || hardwareLevel == "LEVEL_3") score += 20
        return score
    }
}
