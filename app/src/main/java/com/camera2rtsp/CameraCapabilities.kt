package com.camera2rtsp

data class CameraCapabilities(
    val cameraId: String,
    val hardwareLevel: String,  // LEGACY, LIMITED, FULL, LEVEL_3
    val facing: String,         // BACK, FRONT, EXTERNAL
    val name: String,           // Wide, Ultra Wide, Telephoto, Frontal
    val isDepth: Boolean,       // true = sensor Depth/ToF (ocultar da UI)

    // Capabilities flags
    val supportsManualSensor: Boolean,
    val supportsManualPostProcessing: Boolean,
    val supportsRaw: Boolean,
    val supportsBurstCapture: Boolean,
    val supportsDepthOutput: Boolean,
    val supportsLogicalMultiCamera: Boolean,

    // Ranges como List<Number> para serializar como array JSON [min, max]
    val isoRange: List<Int>?,            // [min, max]
    val exposureTimeRange: List<Long>?,  // [min_ns, max_ns]
    val evRange: List<Int>?,             // [min, max]
    val focusDistanceRange: List<Float>?,
    val zoomRange: List<Float>?,
    val fpsRanges: List<List<Int>>,      // [[min,max], ...]

    // Formatos e resoluções
    val availableResolutions: List<String>,

    // NOTA sobre nomenclatura de siglas com Gson LOWER_CASE_WITH_UNDERSCORES:
    // Cada letra maiúscula vira _letra separada. Siglas compostas precisam ser
    // escritas com apenas a 1ª letra maiúscula para gerar o snake_case correto:
    //   supportedAFModes  -> supported_a_f_modes  (ERRADO)
    //   supportedAfModes  -> supported_af_modes   (CORRETO)
    val supportedAfModes: List<String>,
    val supportedAeModes: List<String>,
    val supportedAwbModes: List<String>,

    // Scene Modes e Effect Modes
    val supportedSceneModes: List<String>,
    val supportedEffectModes: List<String>,

    // Hardware físico
    val hasFlash: Boolean,
    val hasOis: Boolean,
    val focalLengths: List<Float>,
    val apertures: List<Float>,

    // Calibração do foco
    val focusDistanceCalibration: String, // "UNCALIBRATED" | "APPROXIMATE" | "CALIBRATED"

    // ── NOVOS: Informações extras do sensor e hardware ─────────────────────

    // Tamanho do array de pixels físico (ex: [4000, 3000] = 12MP)
    val sensorPixelArraySize: List<Int>?,

    // Tamanho físico do sensor em milímetros [width_mm, height_mm]
    val sensorPhysicalSize: List<Float>?,

    // Distância mínima de foco em diopters (1/metros). null = AF fixo ou infinito
    // LENS_INFO_MINIMUM_FOCUS_DISTANCE
    val lensMinFocusDistance: Float?,

    // Tipo de cropping suportado pelo scaler para zoom digital
    // "CENTER_ONLY" = apenas crop central | "FREEFORM" = qualquer região
    val scalerCroppingType: String,

    // Número máximo de regiões AF/AE simultâneas suportadas pelo hardware
    val maxRegionsAf: Int,
    val maxRegionsAe: Int,

    // Detecção de face: 0 = não suporta, >0 = número máximo de faces
    val maxFaceCount: Int
)
