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

    // ── NOVOS: Scene Modes e Effect Modes ─────────────────────────────────
    // Lista de modos de cena disponíveis no hardware (ex: "night", "action", "portrait")
    val supportedSceneModes: List<String>,
    // Lista de efeitos de cor disponíveis (ex: "mono", "sepia", "negative")
    val supportedEffectModes: List<String>,

    // Hardware físico
    val hasFlash: Boolean,
    val hasOis: Boolean,
    val focalLengths: List<Float>,
    val apertures: List<Float>,

    // ── NOVO: Calibração do foco ───────────────────────────────────────────
    // "UNCALIBRATED" | "APPROXIMATE" | "CALIBRATED"
    // Afeta como os valores do slider de foco devem ser interpretados
    val focusDistanceCalibration: String
)
