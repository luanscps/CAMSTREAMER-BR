package com.camera2rtsp

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.pedro.encoder.input.video.Camera2ApiManager
import com.pedro.library.base.Camera2Base
import com.pedro.library.rtmp.RtmpCamera2

class Camera2Controller {

    private val tag = "Camera2Ctrl"

    var rtmpCamera: RtmpCamera2? = null
    var appContext: Context? = null

    var currentCameraId  = "0"
    var exposureLevel    = 0
    var exposureNs       = 33_333_333L
    var frameDurationNs  = 33_333_333L
    var isoValue         = 100
    var manualSensor     = false
    var whiteBalanceMode = "auto"
    var autoFocus        = true
    var focusDistance    = 0f
    var zoomLevel        = 0f
    var lanternEnabled   = false
    var oisEnabled       = false
    var eisEnabled       = false
    var aeLocked         = false
    var awbLocked        = false
    var flashMode        = "off"
    var currentWidth     = 1920
    var currentHeight    = 1080
    var currentBitrate   = 4000
    var currentFps       = 30

    var edgeMode           = CameraMetadata.EDGE_MODE_HIGH_QUALITY
    var noiseReductionMode = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
    var hotPixelMode       = CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY

    // -------------------------------------------------------------------------
    // Zoom Óptico — lista de focal lengths disponíveis no dispositivo
    // opticalZoomIndex = índice na lista; -1 = não ativo (usa zoom digital)
    // -------------------------------------------------------------------------
    var opticalZoomLevels: List<Float> = emptyList()   // populado em discoverAllCameras
    var opticalZoomIndex  = -1                         // -1 = inativo

    // -------------------------------------------------------------------------
    // WB Manual por canal RGGB
    // rggbGains[0]=R, [1]=Gr, [2]=Gb, [3]=B  (1.0f = neutro)
    // rggbEnabled=false => usa AWB normal
    // -------------------------------------------------------------------------
    var rggbEnabled = false
    var rggbGains   = floatArrayOf(1f, 1f, 1f, 1f)

    // -------------------------------------------------------------------------
    // Monitor Ao Vivo — atualizado pelo setCustomOnCaptureCompletedCallback
    // Leitura thread-safe via @Volatile; WebControlApi lê direto
    // -------------------------------------------------------------------------
    @Volatile var liveIso        = 0
    @Volatile var liveExposureNs = 0L
    @Volatile var liveRggbR      = 1f
    @Volatile var liveRggbGr     = 1f
    @Volatile var liveRggbGb     = 1f
    @Volatile var liveRggbB      = 1f
    @Volatile var liveAfState    = "unknown"
    @Volatile var liveAeState    = "unknown"

    // -------------------------------------------------------------------------
    // Worker thread — todas as operações de câmera passam por aqui
    // -------------------------------------------------------------------------

    private val workerThread = HandlerThread("CameraWorker").also { it.start() }
    private val worker = Handler(workerThread.looper)
    private fun post(block: () -> Unit) =
        worker.post { runCatching(block).onFailure { Log.e(tag, "worker error", it) } }

    // -------------------------------------------------------------------------
    // Melhoria 1 + 2: Reflection com lazy cache e flag de validação
    // -------------------------------------------------------------------------

    private val reflField_cameraManager: java.lang.reflect.Field? by lazy {
        runCatching {
            Camera2Base::class.java.getDeclaredField("cameraManager")
                .also { it.isAccessible = true }
        }.onFailure { Log.w(tag, "[reflection] campo 'cameraManager' não encontrado: ${it.message}") }
         .getOrNull()
    }

    private val reflField_builderInputSurface: java.lang.reflect.Field? by lazy {
        runCatching {
            Camera2ApiManager::class.java.getDeclaredField("builderInputSurface")
                .also { it.isAccessible = true }
        }.onFailure { Log.w(tag, "[reflection] campo 'builderInputSurface' não encontrado: ${it.message}") }
         .getOrNull()
    }

    private val reflMethod_applyRequest: java.lang.reflect.Method? by lazy {
        runCatching {
            Camera2ApiManager::class.java.getDeclaredMethod(
                "applyRequest", CaptureRequest.Builder::class.java
            ).also { it.isAccessible = true }
        }.onFailure { Log.w(tag, "[reflection] método 'applyRequest' não encontrado: ${it.message}") }
         .getOrNull()
    }

    val reflectionAvailable: Boolean by lazy {
        val ok = reflField_cameraManager != null &&
                 reflField_builderInputSurface != null &&
                 reflMethod_applyRequest != null
        if (ok) Log.i(tag, "[reflection] cache OK — lazy resolvido com sucesso")
        else    Log.w(tag, "[reflection] INDISPONÍVEL — pós-processamento desativado")
        ok
    }

    // -------------------------------------------------------------------------
    // Helpers de reflection
    // -------------------------------------------------------------------------

    private fun getCam2Manager(): Camera2ApiManager? {
        if (!reflectionAvailable) return null
        return runCatching {
            reflField_cameraManager!!.get(rtmpCamera) as? Camera2ApiManager
        }.onFailure { Log.e(tag, "getCam2Manager falhou", it) }.getOrNull()
    }

    private fun applyOnBuilder(block: (CaptureRequest.Builder) -> Unit): Boolean {
        if (!reflectionAvailable) return false
        val cam = getCam2Manager() ?: return false
        return runCatching {
            val builder = reflField_builderInputSurface!!.get(cam) as? CaptureRequest.Builder
                ?: run { Log.w(tag, "builderInputSurface nulo"); return false }
            block(builder)
            reflMethod_applyRequest!!.invoke(cam, builder) as? Boolean ?: false
        }.onFailure { Log.e(tag, "applyOnBuilder falhou", it) }.getOrElse { false }
    }

    // -------------------------------------------------------------------------
    // Monitor ao vivo — registra o callback no Camera2ApiManager via reflection
    // Chamado uma vez após a câmera abrir (initLiveMonitor)
    // -------------------------------------------------------------------------

    fun initLiveMonitor() {
        val cam2mgr = getCam2Manager() ?: return
        runCatching {
            cam2mgr.setCustomOnCaptureCompletedCallback { result: TotalCaptureResult ->
                liveIso        = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: liveIso
                liveExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: liveExposureNs
                val rggb = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                if (rggb != null) {
                    liveRggbR  = rggb[0]
                    liveRggbGr = rggb[1]
                    liveRggbGb = rggb[2]
                    liveRggbB  = rggb[3]
                }
                liveAfState = when (result.get(CaptureResult.CONTROL_AF_STATE)) {
                    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED      -> "focused"
                    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED  -> "not_focused"
                    CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN         -> "scanning"
                    CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED     -> "passive_focused"
                    CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN        -> "passive_scan"
                    else                                                -> "idle"
                }
                liveAeState = when (result.get(CaptureResult.CONTROL_AE_STATE)) {
                    CaptureResult.CONTROL_AE_STATE_CONVERGED    -> "converged"
                    CaptureResult.CONTROL_AE_STATE_SEARCHING    -> "searching"
                    CaptureResult.CONTROL_AE_STATE_LOCKED       -> "locked"
                    CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> "flash_required"
                    else                                         -> "idle"
                }
            }
            Log.i(tag, "[liveMonitor] callback registrado com sucesso")
        }.onFailure { Log.w(tag, "[liveMonitor] setCustomOnCaptureCompletedCallback falhou: ${it.message}") }
    }

    // -------------------------------------------------------------------------
    // Post Processing com debounce
    // -------------------------------------------------------------------------

    private var postProcPending = false

    private val postProcRunnable = Runnable {
        postProcPending = false
        val ok = applyOnBuilder { b ->
            b.set(CaptureRequest.EDGE_MODE,            edgeMode)
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, noiseReductionMode)
            b.set(CaptureRequest.HOT_PIXEL_MODE,       hotPixelMode)
            b.set(CaptureRequest.TONEMAP_MODE,         CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)
        }
        Log.d(tag, "postProcessing ok=$ok edge=$edgeMode nr=$noiseReductionMode hp=$hotPixelMode")
    }

    private fun schedulePostProcessing() {
        if (!postProcPending) {
            postProcPending = true
            worker.postDelayed(postProcRunnable, 50)
        }
    }

    // -------------------------------------------------------------------------
    // Aplica gains RGGB via builder (WB Manual)
    // -------------------------------------------------------------------------

    private fun applyRggbGains() {
        post {
            val ok = applyOnBuilder { b ->
                // Desativa AWB automático para aplicar ganhos manuais
                b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                val rggb = android.hardware.camera2.params.RggbChannelVector(
                    rggbGains[0], rggbGains[1], rggbGains[2], rggbGains[3]
                )
                b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                b.set(CaptureRequest.COLOR_CORRECTION_GAINS, rggb)
            }
            Log.d(tag, "rggbGains ok=$ok R=${rggbGains[0]} Gr=${rggbGains[1]} Gb=${rggbGains[2]} B=${rggbGains[3]}")
        }
    }

    // -------------------------------------------------------------------------
    // Aplica zoom óptico via LENS_FOCAL_LENGTH
    // -------------------------------------------------------------------------

    private fun applyOpticalZoom(focalLength: Float) {
        post {
            val ok = applyOnBuilder { b ->
                b.set(CaptureRequest.LENS_FOCAL_LENGTH, focalLength)
            }
            Log.d(tag, "opticalZoom ok=$ok focalLength=$focalLength")
        }
    }

    // -------------------------------------------------------------------------
    // Sensor manual / auto
    // -------------------------------------------------------------------------

    private fun applyManualSensor() {
        val safeDuration = maxOf(frameDurationNs, exposureNs)
        post {
            val ok = applyOnBuilder { b ->
                b.set(CaptureRequest.CONTROL_MODE,          CameraMetadata.CONTROL_MODE_OFF)
                b.set(CaptureRequest.CONTROL_AE_MODE,       CameraMetadata.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_SENSITIVITY,    isoValue)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME,  exposureNs)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, safeDuration)
            }
            Log.d(tag, "manualSensor ok=$ok ISO=$isoValue exp=${exposureNs}ns dur=${safeDuration}ns")
            schedulePostProcessing()
        }
    }

    private fun applyAutoSensor() {
        post {
            val ok = applyOnBuilder { b ->
                b.set(CaptureRequest.CONTROL_MODE,    CameraMetadata.CONTROL_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            }
            Log.d(tag, "autoSensor ok=$ok")
        }
    }

    // -------------------------------------------------------------------------
    // updateSettings
    // -------------------------------------------------------------------------

    fun updateSettings(params: Map<String, Any>) {
        val cam = rtmpCamera ?: run { Log.w(tag, "rtmpCamera nulo"); return }

        params["manualSensor"]?.let {
            manualSensor = it as Boolean
            if (manualSensor) applyManualSensor()
            else { applyAutoSensor(); cam.setExposure(0); exposureLevel = 0 }
            Log.d(tag, "manualSensor -> $manualSensor")
        }

        params["iso"]?.let {
            isoValue = when (it) {
                is Double -> it.toInt()
                is Int    -> it
                is Number -> it.toInt()
                else      -> it.toString().toIntOrNull() ?: 100
            }
            if (manualSensor) applyManualSensor()
            else {
                val minEv = cam.minExposure; val maxEv = cam.maxExposure
                val ev = (((isoValue - 50f) / (3200f - 50f)) * (maxEv - minEv) + minEv)
                    .toInt().coerceIn(minEv, maxEv)
                exposureLevel = ev; cam.setExposure(ev)
            }
            Log.d(tag, "iso -> $isoValue manual=$manualSensor")
        }

        params["shutterSpeed"]?.let { raw ->
            exposureNs = parseTimeParam(raw, 33_333_333L)
            if (manualSensor) applyManualSensor()
            Log.d(tag, "shutterSpeed -> ${exposureNs}ns")
        }

        params["frameDuration"]?.let { raw ->
            frameDurationNs = parseTimeParam(raw, 33_333_333L)
            if (manualSensor) applyManualSensor()
            Log.d(tag, "frameDuration -> ${frameDurationNs}ns")
        }

        params["exposure"]?.let {
            if (!manualSensor) {
                val ev = when (it) {
                    is Double -> it.toInt()
                    is Int    -> it
                    is Number -> it.toInt()
                    else      -> it.toString().toIntOrNull() ?: 0
                }.coerceIn(cam.minExposure, cam.maxExposure)
                exposureLevel = ev; cam.setExposure(ev)
                Log.d(tag, "EV -> $ev")
            }
        }

        params["focus"]?.let {
            val norm = when (it) {
                is Double -> it.toFloat()
                is Number -> it.toFloat()
                else      -> it.toString().toFloatOrNull() ?: 0f
            }.coerceIn(0f, 1f)
            if (norm == 0f) {
                autoFocus = true; focusDistance = 0f; cam.enableAutoFocus()
            } else {
                autoFocus = false; focusDistance = norm * 10f
                cam.disableAutoFocus(); cam.setFocusDistance(focusDistance)
            }
            Log.d(tag, "focus norm=$norm dist=$focusDistance")
        }

        params["focusmode"]?.let {
            when (it as String) {
                "continuous-video", "continuous-picture", "auto" -> {
                    autoFocus = true; focusDistance = 0f; cam.enableAutoFocus()
                }
                "off" -> cam.disableAutoFocus()
            }
            Log.d(tag, "focusMode -> $it")
        }

        params["afTrigger"]?.let { cam.enableAutoFocus(); Log.d(tag, "afTrigger") }

        params["whiteBalance"]?.let {
            whiteBalanceMode = it as String
            // Ao trocar modo AWB desativa RGGB manual
            rggbEnabled = false
            val mode = when (whiteBalanceMode) {
                "daylight"                 -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                "cloudy"                   -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                "tungsten", "incandescent" -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
                "fluorescent"              -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
                else                       -> CameraMetadata.CONTROL_AWB_MODE_AUTO
            }
            cam.enableAutoWhiteBalance(mode)
            Log.d(tag, "WB -> $whiteBalanceMode")
        }

        // ── WB Manual RGGB ────────────────────────────────────────────────────
        // Parâmetros individuais: rggbR, rggbGr, rggbGb, rggbB  (Float 0.1–4.0)
        // Parâmetro combinado:   rggbGains = [r, gr, gb, b]
        // ─────────────────────────────────────────────────────────────────────
        var rggbDirty = false

        params["rggbR"]?.let {
            rggbGains[0] = toFloat(it).coerceIn(0.1f, 4f)
            rggbEnabled = true; rggbDirty = true
        }
        params["rggbGr"]?.let {
            rggbGains[1] = toFloat(it).coerceIn(0.1f, 4f)
            rggbEnabled = true; rggbDirty = true
        }
        params["rggbGb"]?.let {
            rggbGains[2] = toFloat(it).coerceIn(0.1f, 4f)
            rggbEnabled = true; rggbDirty = true
        }
        params["rggbB"]?.let {
            rggbGains[3] = toFloat(it).coerceIn(0.1f, 4f)
            rggbEnabled = true; rggbDirty = true
        }
        (params["rggbGains"] as? List<*>)?.let { list ->
            if (list.size >= 4) {
                rggbGains[0] = toFloat(list[0]).coerceIn(0.1f, 4f)
                rggbGains[1] = toFloat(list[1]).coerceIn(0.1f, 4f)
                rggbGains[2] = toFloat(list[2]).coerceIn(0.1f, 4f)
                rggbGains[3] = toFloat(list[3]).coerceIn(0.1f, 4f)
                rggbEnabled = true; rggbDirty = true
            }
        }
        params["rggbReset"]?.let {
            rggbGains = floatArrayOf(1f, 1f, 1f, 1f)
            rggbEnabled = false
            // Restaura AWB automático
            cam.enableAutoWhiteBalance(CameraMetadata.CONTROL_AWB_MODE_AUTO)
            whiteBalanceMode = "auto"
            Log.d(tag, "rggbReset")
        }
        if (rggbDirty) applyRggbGains()

        // ── Zoom Digital ──────────────────────────────────────────────────────
        params["zoom"]?.let {
            val z = when (it) {
                is Double -> it.toFloat()
                is Number -> it.toFloat()
                else      -> it.toString().toFloatOrNull() ?: 0f
            }.coerceIn(0f, 1f)
            zoomLevel = z
            opticalZoomIndex = -1  // ao usar zoom digital, cancela óptico ativo
            val zr = cam.zoomRange
            cam.setZoom(zr.lower + z * (zr.upper - zr.lower))
            Log.d(tag, "zoom -> $z (real=${zr.lower + z * (zr.upper - zr.lower)})")
        }

        // ── Zoom Óptico ───────────────────────────────────────────────────────
        // Parâmetro: opticalZoom = índice na lista opticalZoomLevels
        params["opticalZoom"]?.let { raw ->
            val idx = when (raw) {
                is Double -> raw.toInt()
                is Number -> raw.toInt()
                else      -> raw.toString().toIntOrNull() ?: -1
            }
            if (idx >= 0 && idx < opticalZoomLevels.size) {
                opticalZoomIndex = idx
                applyOpticalZoom(opticalZoomLevels[idx])
                // Reseta zoom digital ao 1x ao trocar lente óptica
                zoomLevel = 0f
                val zr = cam.zoomRange
                cam.setZoom(zr.lower)
                Log.d(tag, "opticalZoom idx=$idx focalLength=${opticalZoomLevels[idx]}mm")
            } else {
                Log.w(tag, "opticalZoom idx=$idx inválido (disponíveis: ${opticalZoomLevels.size})")
            }
        }

        params["lantern"]?.let {
            lanternEnabled = it as Boolean
            if (lanternEnabled) cam.enableLantern() else cam.disableLantern()
            Log.d(tag, "lantern -> $lanternEnabled")
        }

        params["ois"]?.let {
            oisEnabled = it as Boolean
            if (oisEnabled) cam.enableOpticalVideoStabilization()
            else cam.disableOpticalVideoStabilization()
            Log.d(tag, "ois -> $oisEnabled")
        }

        params["eis"]?.let {
            eisEnabled = it as Boolean
            if (eisEnabled) cam.enableVideoStabilization()
            else cam.disableVideoStabilization()
            Log.d(tag, "eis -> $eisEnabled")
        }

        params["aeLock"]?.let {
            aeLocked = it as Boolean
            if (aeLocked) cam.disableAutoExposure() else cam.enableAutoExposure()
            Log.d(tag, "aeLock -> $aeLocked")
        }

        params["awbLock"]?.let {
            awbLocked = it as Boolean
            if (awbLocked) cam.disableAutoWhiteBalance()
            else cam.enableAutoWhiteBalance(CameraMetadata.CONTROL_AWB_MODE_AUTO)
            Log.d(tag, "awbLock -> $awbLocked")
        }

        params["flashMode"]?.let {
            flashMode = it as String
            when (flashMode) {
                "torch" -> { cam.enableLantern(); lanternEnabled = true }
                else    -> { cam.disableLantern(); lanternEnabled = false }
            }
            Log.d(tag, "flashMode -> $flashMode")
        }

        params["bitrate"]?.let {
            currentBitrate = when (it) {
                is Double -> it.toInt()
                is Number -> it.toInt()
                else      -> it.toString().toIntOrNull() ?: 4000
            }
            if (cam.isStreaming) cam.setVideoBitrateOnFly(currentBitrate * 1024)
            Log.d(tag, "bitrate -> ${currentBitrate}kbps")
        }

        params["fps"]?.let { value ->
            val fps = when (value) {
                is Double -> value.toInt()
                is Number -> value.toInt()
                else      -> value.toString().toIntOrNull() ?: 30
            }.coerceIn(15, 60)
            currentFps = fps
            if (!manualSensor) frameDurationNs = 1_000_000_000L / fps
            post {
                val wasStreaming = cam.isStreaming
                val url = StreamingService.instance?.rtmpUrl ?: ""
                if (wasStreaming) cam.stopStream()
                val vOk = cam.prepareVideo(currentWidth, currentHeight, fps, currentBitrate * 1024, 0)
                val aOk = cam.prepareAudio(128 * 1024, 44100, true)
                if (wasStreaming && vOk && aOk) cam.startStream(url)
                Log.d(tag, "fps -> $fps vOk=$vOk")
            }
        }

        params["camera"]?.let { value ->
            currentCameraId = value as String
            // Ao trocar câmera, recalcula focal lengths disponíveis e reseta zoom óptico
            opticalZoomIndex = -1
            appContext?.let { ctx ->
                val newCaps = discoverAllCameras(ctx).firstOrNull { it.cameraId == currentCameraId }
                opticalZoomLevels = newCaps?.focalLengths ?: emptyList()
                Log.d(tag, "camera=$currentCameraId opticalZoomLevels=$opticalZoomLevels")
            }
            post {
                cam.switchCamera(currentCameraId)
                initLiveMonitor() // registra callback na nova câmera
                if (manualSensor) applyManualSensor()
                else if (!autoFocus && focusDistance > 0f) cam.setFocusDistance(focusDistance)
                Log.d(tag, "camera -> $currentCameraId")
            }
        }

        params["resolution"]?.let { value ->
            val (w, h, br) = when (value as String) {
                "4k", "3840x2160"    -> Triple(3840, 2160, 20000)
                "1080p", "1920x1080" -> Triple(1920, 1080, 8000)
                "720p", "1280x720"   -> Triple(1280, 720, 4000)
                else -> {
                    val parts = (value).split("x")
                    Triple(
                        parts.getOrNull(0)?.toIntOrNull() ?: 1920,
                        parts.getOrNull(1)?.toIntOrNull() ?: 1080,
                        currentBitrate
                    )
                }
            }
            currentWidth = w; currentHeight = h; currentBitrate = br
            post {
                val wasStreaming = cam.isStreaming
                val url = StreamingService.instance?.rtmpUrl ?: ""
                if (wasStreaming) cam.stopStream()
                val vOk = cam.prepareVideo(w, h, currentFps, br * 1024, 0)
                val aOk = cam.prepareAudio(128 * 1024, 44100, true)
                if (wasStreaming && vOk && aOk) cam.startStream(url)
                Log.d(tag, "resolution -> ${w}x${h} vOk=$vOk")
            }
        }

        params["edgeMode"]?.let {
            edgeMode = when (it as String) {
                "off"  -> CameraMetadata.EDGE_MODE_OFF
                "fast" -> CameraMetadata.EDGE_MODE_FAST
                else   -> CameraMetadata.EDGE_MODE_HIGH_QUALITY
            }
            schedulePostProcessing()
            Log.d(tag, "edgeMode -> $it")
        }

        params["noiseReduction"]?.let {
            noiseReductionMode = when (it as String) {
                "off"     -> CameraMetadata.NOISE_REDUCTION_MODE_OFF
                "fast"    -> CameraMetadata.NOISE_REDUCTION_MODE_FAST
                "minimal" -> CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
                else      -> CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
            }
            schedulePostProcessing()
            Log.d(tag, "noiseReduction -> $it")
        }

        params["hotPixel"]?.let {
            hotPixelMode = when (it as String) {
                "off"  -> CameraMetadata.HOT_PIXEL_MODE_OFF
                "fast" -> CameraMetadata.HOT_PIXEL_MODE_FAST
                else   -> CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY
            }
            schedulePostProcessing()
            Log.d(tag, "hotPixel -> $it")
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun toFloat(v: Any?): Float = when (v) {
        is Double -> v.toFloat()
        is Float  -> v
        is Number -> v.toFloat()
        is String -> v.toFloatOrNull() ?: 1f
        else      -> 1f
    }

    private fun parseTimeParam(raw: Any, default: Long): Long = when (raw) {
        is String -> {
            val p = raw.split("/")
            if (p.size == 2) {
                val n = p[0].trim().toDoubleOrNull() ?: 1.0
                val d = p[1].trim().toDoubleOrNull() ?: 30.0
                ((n / d) * 1_000_000_000.0).toLong()
            } else raw.toLongOrNull() ?: default
        }
        is Double -> raw.toLong()
        is Long   -> raw
        is Number -> raw.toLong()
        else      -> default
    }

    fun release() {
        worker.removeCallbacks(postProcRunnable)
        workerThread.quitSafely()
        rtmpCamera = null
    }

    // -------------------------------------------------------------------------
    // Discovery de cameras
    // -------------------------------------------------------------------------

    fun discoverAllCameras(context: Context): List<CameraCapabilities> {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameras = mutableListOf<CameraCapabilities>()
        for (id in mgr.cameraIdList) {
            val ch = mgr.getCameraCharacteristics(id)
            val hwLevel = when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY  -> "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL    -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3       -> "LEVEL_3"
                else -> "UNKNOWN"
            }
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            fun hasCap(v: Int) = caps.contains(v)
            val supManual = hasCap(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val supPost   = hasCap(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
            val supRaw    = hasCap(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            val supBurst  = hasCap(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE)
            val supDepth  = hasCap(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)
            val supMulti  = if (android.os.Build.VERSION.SDK_INT >= 28)
                hasCap(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) else false
            val isoRange  = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { listOf(it.lower, it.upper) }
            val expRange  = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { listOf(it.lower, it.upper) }
            val evRange   = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { listOf(it.lower, it.upper) }
            val focRange  = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.let { listOf(0f, it) }
            val zomRange  = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.let { listOf(1.0f, it) }
            val fpsRanges = (ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: arrayOf())
                .map { listOf(it.lower, it.upper) }
            val streamCfg = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val resolutions = streamCfg?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
                ?.map { "${it.width}x${it.height}" }?.distinct()
                ?.sortedByDescending { it.split("x").getOrNull(0)?.toIntOrNull() ?: 0 } ?: emptyList()
            val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.map {
                when (it) {
                    CameraCharacteristics.CONTROL_AF_MODE_OFF                -> "off"
                    CameraCharacteristics.CONTROL_AF_MODE_AUTO               -> "auto"
                    CameraCharacteristics.CONTROL_AF_MODE_MACRO              -> "macro"
                    CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO   -> "continuous-video"
                    CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "continuous-picture"
                    CameraCharacteristics.CONTROL_AF_MODE_EDOF               -> "edof"
                    else -> "unknown"
                }
            } ?: emptyList()
            val aeModes = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.map {
                when (it) {
                    CameraCharacteristics.CONTROL_AE_MODE_OFF                  -> "off"
                    CameraCharacteristics.CONTROL_AE_MODE_ON                   -> "on"
                    CameraCharacteristics.CONTROL_AE_MODE_ON_AUTO_FLASH        -> "on-auto-flash"
                    CameraCharacteristics.CONTROL_AE_MODE_ON_ALWAYS_FLASH      -> "on-always-flash"
                    CameraCharacteristics.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> "on-auto-flash-redeye"
                    else -> "unknown"
                }
            } ?: emptyList()
            val awbModes = ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.map {
                when (it) {
                    CameraCharacteristics.CONTROL_AWB_MODE_OFF              -> "off"
                    CameraCharacteristics.CONTROL_AWB_MODE_AUTO             -> "auto"
                    CameraCharacteristics.CONTROL_AWB_MODE_INCANDESCENT     -> "incandescent"
                    CameraCharacteristics.CONTROL_AWB_MODE_FLUORESCENT      -> "fluorescent"
                    CameraCharacteristics.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "warm-fluorescent"
                    CameraCharacteristics.CONTROL_AWB_MODE_DAYLIGHT         -> "daylight"
                    CameraCharacteristics.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT  -> "cloudy"
                    CameraCharacteristics.CONTROL_AWB_MODE_TWILIGHT         -> "twilight"
                    CameraCharacteristics.CONTROL_AWB_MODE_SHADE            -> "shade"
                    else -> "unknown"
                }
            } ?: emptyList()
            val hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            val hasOis   = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) ?: false
            val focalLengths = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()
            val apertures    = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList() ?: emptyList()
            val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK     -> "BACK"
                CameraCharacteristics.LENS_FACING_FRONT    -> "FRONT"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                else -> "UNKNOWN"
            }
            val isDepth = supDepth && resolutions.isEmpty()
            val name = when {
                isDepth                        -> "Depth/ToF"
                id == "0" && facing == "BACK"  -> "Wide"
                id == "1" && facing == "FRONT" -> "Frontal"
                id == "2" && facing == "BACK"  -> "Ultra Wide"
                id == "3" && facing == "BACK"  -> "Telephoto"
                facing == "FRONT"              -> "Frontal $id"
                else                           -> "Cam $id"
            }
            cameras.add(CameraCapabilities(
                cameraId = id, hardwareLevel = hwLevel, facing = facing, name = name,
                isDepth = isDepth, supportsManualSensor = supManual,
                supportsManualPostProcessing = supPost, supportsRaw = supRaw,
                supportsBurstCapture = supBurst, supportsDepthOutput = supDepth,
                supportsLogicalMultiCamera = supMulti, isoRange = isoRange,
                exposureTimeRange = expRange, evRange = evRange,
                focusDistanceRange = focRange, zoomRange = zomRange,
                fpsRanges = fpsRanges, availableResolutions = resolutions,
                supportedAfModes = afModes, supportedAeModes = aeModes,
                supportedAwbModes = awbModes, hasFlash = hasFlash, hasOis = hasOis,
                focalLengths = focalLengths, apertures = apertures
            ))
        }
        return cameras
    }
}
