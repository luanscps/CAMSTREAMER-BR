package com.camera2rtsp

data class CameraCapabilities(
    val cameraId: String,
    val hardwareLevel: String,
    val facing: String,
    val name: String,
    val isDepth: Boolean,

    // Capabilities flags
    val supportsManualSensor: Boolean,
    val supportsManualPostProcessing: Boolean,
    val supportsRaw: Boolean,
    val supportsBurstCapture: Boolean,
    val supportsDepthOutput: Boolean,
    val supportsLogicalMultiCamera: Boolean,

    // Ranges
    val isoRange: List<Int>?,
    val exposureTimeRange: List<Long>?,
    val evRange: List<Int>?,
    val focusDistanceRange: List<Float>?,
    val zoomRange: List<Float>?,
    val zoomRatioRange: List<Float>?,
    val fpsRanges: List<List<Int>>,
    val highSpeedVideoFpsRanges: List<List<Int>>,

    // Streams / resoluções
    val availableResolutions: List<String>,
    val yuvResolutions: List<String>,
    val jpegResolutions: List<String>,
    val rawResolutions: List<String>,
    val highSpeedVideoSizes: List<String>,
    val outputFormats: List<String>,

    // Mapa opcional para UI futura
    val resolutionFpsMap: Map<String, List<Int>> = emptyMap(),

    // Modos
    val supportedAfModes: List<String>,
    val supportedAeModes: List<String>,
    val supportedAwbModes: List<String>,
    val supportedSceneModes: List<String>,
    val supportedEffectModes: List<String>,
    val videoStabilizationModes: List<String>,
    val distortionCorrectionModes: List<String>,
    val noiseReductionModes: List<String>,
    val edgeModes: List<String>,
    val hotPixelModes: List<String>,
    val faceDetectModes: List<String>,

    // Hardware físico
    val hasFlash: Boolean,
    val hasOis: Boolean,
    val focalLengths: List<Float>,
    val apertures: List<Float>,

    // Foco / calibração
    val focusDistanceCalibration: String,
    val lensMinFocusDistance: Float?,

    // Sensor / geometria
    val sensorPixelArraySize: List<Int>?,
    val activeArraySize: List<Int>?,
    val sensorPhysicalSize: List<Float>?,
    val sensorOrientation: Int,
    val scalerCroppingType: String,

    // AE/AWB
    val aeCompensationStep: Float?,
    val aeLockAvailable: Boolean,
    val awbLockAvailable: Boolean,

    // Multi-regions / face
    val maxRegionsAf: Int,
    val maxRegionsAe: Int,
    val maxRegionsAwb: Int,
    val maxFaceCount: Int,

    // Multi-câmera / tempo
    val physicalCameraIds: List<String>,
    val timestampSource: String?,

    // Auxiliares para UI
    val maxDigitalZoom: Float?,
    val logicalZoomSupported: Boolean
) {
    fun isManualControlFullyAvailable(): Boolean {
        return supportsManualSensor && supportsManualPostProcessing
    }

    fun supportsHighSpeed(): Boolean {
        return highSpeedVideoSizes.isNotEmpty() && highSpeedVideoFpsRanges.isNotEmpty()
    }

    fun supports60Fps(): Boolean {
        return fpsRanges.any { it.size >= 2 && it[1] >= 60 } ||
                highSpeedVideoFpsRanges.any { it.size >= 2 && it[1] >= 60 }
    }

    fun isUsableForUi(): Boolean = !isDepth

    fun hasVariableFocus(): Boolean = (lensMinFocusDistance ?: 0f) > 0f

    fun minZoomRatioOrDefault(): Float {
        return zoomRatioRange?.getOrNull(0)
            ?: zoomRange?.getOrNull(0)
            ?: 1f
    }

    fun maxZoomRatioOrNull(): Float? {
        return zoomRatioRange?.getOrNull(1)
            ?: zoomRange?.getOrNull(1)
            ?: maxDigitalZoom
    }

    fun isFrontCamera(): Boolean = facing == "FRONT"
    fun isBackCamera(): Boolean = facing == "BACK"
    fun isLogicalCamera(): Boolean = supportsLogicalMultiCamera

    fun isUltraWide(): Boolean = focalLengths.any { it < 2.5f }
    fun isTelephoto(): Boolean = focalLengths.any { it > 6f }

    fun bestPreviewResolution(): String? {
        return availableResolutions.maxByOrNull { parseResolutionPixels(it) }
    }

    fun bestYuvResolutionForRtsp(maxWidth: Int = 1920): String? {
        return yuvResolutions
            .filter { parseWidth(it) <= maxWidth }
            .maxByOrNull { parseResolutionPixels(it) }
    }

    fun evRangeInStops(): List<Float>? {
        val step = aeCompensationStep ?: return null
        val range = evRange ?: return null
        if (range.size < 2) return null
        return listOf(range[0] * step, range[1] * step)
    }

    private fun parseWidth(s: String): Int {
        val parts = s.lowercase().split("x")
        return parts.getOrNull(0)?.toIntOrNull() ?: 0
    }

    private fun parseResolutionPixels(s: String): Int {
        val parts = s.lowercase().split("x")
        val w = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val h = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return w * h
    }
}