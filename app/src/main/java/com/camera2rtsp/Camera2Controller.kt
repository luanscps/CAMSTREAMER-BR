package com.camera2rtsp

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
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

    var opticalZoomLevels: List<Float> = emptyList()
    var opticalZoomIndex  = -1

    var rggbEnabled = false
    var rggbGains   = floatArrayOf(1f, 1f, 1f, 1f)

    @Volatile var liveIso        = 0
    @Volatile var liveExposureNs = 0L
    @Volatile var liveRggbR      = 1f
    @Volatile var liveRggbGr     = 1f
    @Volatile var liveRggbGb     = 1f
    @Volatile var liveRggbB      = 1f
    @Volatile var liveAfState    = "unknown"
    @Volatile var liveAeState    = "unknown"

    private val workerThread = HandlerThread("CameraWorker").also { it.start() }
    private val worker = Handler(workerThread.looper)
    private fun post(block: () -> Unit) =
        worker.post { runCatching(block).onFailure { Log.e(tag, "worker error", it) } }

    private val reflField_cameraManager: java.lang.reflect.Field? by lazy {
        runCatching {
            Camera2Base::class.java.getDeclaredField("cameraManager")
                .also { it.isAccessible = true }
        }.onFailure { Log.w(tag, "[reflection] campo 'cameraManager' não encontrado: ${it.message}") }
            .getOrNull()
    }

    private fun getCam2Manager(): Camera2ApiManager? {
        val field = reflField_cameraManager ?: return null
        return runCatching {
            field.get(rtmpCamera) as? Camera2ApiManager
        }.onFailure { Log.e(tag, "getCam2Manager falhou", it) }.getOrNull()
    }

    private fun setCustomRequest(block: (CaptureRequest.Builder) -> Unit): Boolean {
        val cam2 = getCam2Manager() ?: run {
            Log.w(tag, "setCustomRequest: getCam2Manager nulo")
            return false
        }
        return runCatching {
            cam2.setCustomRequest(block)
        }.onFailure { Log.e(tag, "setCustomRequest falhou", it) }.getOrElse { false }
    }

    fun initLiveMonitor() {
        val cam2mgr = getCam2Manager() ?: run {
            Log.w(tag, "[liveMonitor] getCam2Manager nulo — abortando")
            return
        }
        runCatching {
            cam2mgr.setCustomOnCaptureCompletedCallback { _: CameraCaptureSession, _: CaptureRequest, result: TotalCaptureResult ->
                liveIso        = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: liveIso
                liveExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: liveExposureNs
                val rggb = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                if (rggb != null) {
                    liveRggbR  = rggb.red
                    liveRggbGr = rggb.greenEven
                    liveRggbGb = rggb.greenOdd
                    liveRggbB  = rggb.blue
                }
                liveAfState = when (result.get(CaptureResult.CONTROL_AF_STATE)) {
                    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED      -> "focused"
                    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED  -> "not_focused"
                    CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN         -> "scanning"
                    CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED     -> "passive_focused"
                    CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN        -> "passive_scan"
                    else                                               -> "idle"
                }
                liveAeState = when (result.get(CaptureResult.CONTROL_AE_STATE)) {
                    CaptureResult.CONTROL_AE_STATE_CONVERGED      -> "converged"
                    CaptureResult.CONTROL_AE_STATE_SEARCHING      -> "searching"
                    CaptureResult.CONTROL_AE_STATE_LOCKED         -> "locked"
                    CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> "flash_required"
                    else                                           -> "idle"
                }
            }
            Log.i(tag, "[liveMonitor] callback registrado com sucesso")
        }.onFailure { Log.w(tag, "[liveMonitor] falhou: ${it.message}") }
    }

    fun initLiveMonitorDelayed(delayMs: Long = 300L) {
        worker.postDelayed({ initLiveMonitor() }, delayMs)
    }

    fun onStreamStarted() {
        worker.postDelayed({ initLiveMonitor() }, 500L)
        Log.i(tag, "[liveMonitor] onStreamStarted agendado (500ms)")
    }

    private var postProcPending = false

    private val postProcRunnable = Runnable {
        postProcPending = false
        val ok = setCustomRequest { b ->
            b.set(CaptureRequest.EDGE_MODE, edgeMode)
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, noiseReductionMode)
            b.set(CaptureRequest.HOT_PIXEL_MODE, hotPixelMode)
            b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)
        }
        Log.d(tag, "postProcessing ok=$ok edge=$edgeMode nr=$noiseReductionMode hp=$hotPixelMode")
    }

    private fun schedulePostProcessing() {
        if (!postProcPending) {
            postProcPending = true
            worker.postDelayed(postProcRunnable, 50)
        }
    }

    private fun applyRggbGains(cam: RtmpCamera2) {
        post {
            val ok = runCatching {
                cam.disableAutoWhiteBalance()
                cam.setColorCorrectionGains(rggbGains[0], rggbGains[1], rggbGains[2], rggbGains[3])
            }.getOrElse {
                Log.w(tag, "setColorCorrectionGains falhou, fallback custom request", it)
                setCustomRequest { b ->
                    b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                    val rggb = android.hardware.camera2.params.RggbChannelVector(
                        rggbGains[0], rggbGains[1], rggbGains[2], rggbGains[3]
                    )
                    b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                    b.set(CaptureRequest.COLOR_CORRECTION_GAINS, rggb)
                }
            }
            Log.d(tag, "rggbGains ok=$ok R=${rggbGains[0]} Gr=${rggbGains[1]} Gb=${rggbGains[2]} B=${rggbGains[3]}")
        }
    }

    private fun applyOpticalZoom(cam: RtmpCamera2, focalLength: Float) {
        post {
            runCatching { cam.setOpticalZoom(focalLength) }
                .onFailure { Log.e(tag, "opticalZoom falhou", it) }
            Log.d(tag, "opticalZoom focalLength=$focalLength")
        }
    }

    private fun applyManualSensor() {
        val safeDuration = maxOf(frameDurationNs, exposureNs)
        post {
            val ok = setCustomRequest { b ->
                b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, isoValue)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, safeDuration)
            }
            Log.d(tag, "manualSensor ok=$ok ISO=$isoValue exp=${exposureNs}ns dur=${safeDuration}ns")
            schedulePostProcessing()
        }
    }

    private fun applyAutoSensor(cam: RtmpCamera2) {
        post {
            val ok = setCustomRequest { b ->
                b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            }
            runCatching { cam.enableAutoExposure() }
            Log.d(tag, "autoSensor ok=$ok")
        }
    }

    private fun applyDigitalZoom(cam: RtmpCamera2, zNorm: Float) {
        post {
            val zr = cam.zoomRange
            val absZoom = zr.lower + zNorm * (zr.upper - zr.lower)
            runCatching { cam.setZoom(absZoom) }
                .onFailure { Log.e(tag, "digitalZoom falhou", it) }
            Log.d(tag, "digitalZoom abs=$absZoom zNorm=$zNorm")
        }
    }

    private fun applyFocusDistance(cam: RtmpCamera2, dist: Float) {
        post {
            runCatching {
                cam.disableAutoFocus()
                cam.setFocusDistance(dist)
            }.onFailure { Log.e(tag, "focusDistance falhou", it) }
            Log.d(tag, "focusDistance dist=$dist")
        }
    }

    private fun applyAutoFocus(cam: RtmpCamera2) {
        post {
            val ok = runCatching { cam.enableAutoFocus() }.getOrDefault(false)
            Log.d(tag, "autoFocus ok=$ok")
        }
    }

    private fun applyTorch(cam: RtmpCamera2, enable: Boolean) {
        post {
            runCatching {
                if (enable) cam.enableLantern() else cam.disableLantern()
            }.onFailure { Log.e(tag, "torch falhou", it) }
            Log.d(tag, "torch enable=$enable")
        }
    }

    private fun applyOIS(cam: RtmpCamera2, enable: Boolean) {
        post {
            runCatching {
                if (enable) cam.enableOpticalVideoStabilization()
                else cam.disableOpticalVideoStabilization()
            }.onFailure { Log.e(tag, "ois falhou", it) }
            Log.d(tag, "ois enable=$enable")
        }
    }

    private fun applyEIS(cam: RtmpCamera2, enable: Boolean) {
        post {
            runCatching {
                if (enable) cam.enableVideoStabilization()
                else cam.disableVideoStabilization()
            }.onFailure { Log.e(tag, "eis falhou", it) }
            Log.d(tag, "eis enable=$enable")
        }
    }

    private fun applyAELock(cam: RtmpCamera2, lock: Boolean) {
        post {
            val ok = setCustomRequest { b ->
                b.set(CaptureRequest.CONTROL_AE_LOCK, lock)
            }
            if (!ok) {
                runCatching {
                    if (lock) cam.disableAutoExposure() else cam.enableAutoExposure()
                }.onFailure { Log.e(tag, "aeLock fallback falhou", it) }
            }
            Log.d(tag, "aeLock ok=$ok lock=$lock")
        }
    }

    private fun applyAWBLock(cam: RtmpCamera2, lock: Boolean) {
        post {
            val ok = setCustomRequest { b ->
                b.set(CaptureRequest.CONTROL_AWB_LOCK, lock)
            }
            if (!ok && !lock) {
                runCatching { cam.enableAutoWhiteBalance(CameraMetadata.CONTROL_AWB_MODE_AUTO) }
                    .onFailure { Log.e(tag, "awb unlock fallback falhou", it) }
            }
            if (lock) runCatching { cam.disableAutoWhiteBalance() }
            Log.d(tag, "awbLock ok=$ok lock=$lock")
        }
    }

    private fun applyFlashMode(cam: RtmpCamera2, mode: String) {
        post {
            when (mode) {
                "torch" -> runCatching { cam.enableLantern(); lanternEnabled = true }
                    .onFailure { Log.e(tag, "flash torch falhou", it) }
                "single" -> {
                    val ok = setCustomRequest { b ->
                        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                        b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
                    }
                    if (!ok) Log.w(tag, "flash single não suportado via custom request")
                    lanternEnabled = false
                }
                else -> runCatching { cam.disableLantern(); lanternEnabled = false }
                    .onFailure { Log.e(tag, "flash off falhou", it) }
            }
            Log.d(tag, "flashMode mode=$mode")
        }
    }

    fun updateSettings(params: Map<String, Any>) {
        val cam = rtmpCamera ?: run { Log.w(tag, "rtmpCamera nulo"); return }

        params["manualSensor"]?.let {
            manualSensor = it as Boolean
            if (manualSensor) applyManualSensor()
            else { applyAutoSensor(cam); cam.setExposure(0); exposureLevel = 0 }
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
                val minEv = cam.minExposure
                val maxEv = cam.maxExposure
                val ev = (((isoValue - 50f) / (3200f - 50f)) * (maxEv - minEv) + minEv)
                    .toInt().coerceIn(minEv, maxEv)
                exposureLevel = ev
                cam.setExposure(ev)
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
                exposureLevel = ev
                cam.setExposure(ev)
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
                autoFocus = true
                focusDistance = 0f
                applyAutoFocus(cam)
            } else {
                autoFocus = false
                focusDistance = norm * 10f
                applyFocusDistance(cam, focusDistance)
            }
            Log.d(tag, "focus norm=$norm dist=$focusDistance")
        }

        params["focusmode"]?.let {
            when (it as String) {
                "continuous-video", "continuous-picture", "auto" -> {
                    autoFocus = true
                    focusDistance = 0f
                    applyAutoFocus(cam)
                }
                "off" -> {
                    autoFocus = false
                    post {
                        runCatching { cam.disableAutoFocus() }
                            .onFailure { Log.e(tag, "disableAutoFocus falhou", it) }
                    }
                }
            }
            Log.d(tag, "focusMode -> $it")
        }

        params["afTrigger"]?.let {
            post {
                val ok = setCustomRequest { b ->
                    b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                    b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                }
                Log.d(tag, "afTrigger ok=$ok")
            }
        }

        params["whiteBalance"]?.let {
            whiteBalanceMode = it as String
            rggbEnabled = false
            awbLocked = false
            val mode = when (whiteBalanceMode) {
                "daylight" -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                "cloudy" -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                "tungsten", "incandescent" -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
                "fluorescent" -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
                else -> CameraMetadata.CONTROL_AWB_MODE_AUTO
            }
            post {
                runCatching { cam.enableAutoWhiteBalance(mode) }
                    .onFailure { Log.e(tag, "whiteBalance falhou", it) }
            }
            Log.d(tag, "WB -> $whiteBalanceMode")
        }

        var rggbDirty = false
        params["rggbR"]?.let { rggbGains[0] = toFloat(it).coerceIn(0.1f, 4f); rggbEnabled = true; rggbDirty = true }
        params["rggbGr"]?.let { rggbGains[1] = toFloat(it).coerceIn(0.1f, 4f); rggbEnabled = true; rggbDirty = true }
        params["rggbGb"]?.let { rggbGains[2] = toFloat(it).coerceIn(0.1f, 4f); rggbEnabled = true; rggbDirty = true }
        params["rggbB"]?.let { rggbGains[3] = toFloat(it).coerceIn(0.1f, 4f); rggbEnabled = true; rggbDirty = true }
        (params["rggbGains"] as? List<*>)?.let { list ->
            if (list.size >= 4) {
                rggbGains[0] = toFloat(list[0]).coerceIn(0.1f, 4f)
                rggbGains[1] = toFloat(list[1]).coerceIn(0.1f, 4f)
                rggbGains[2] = toFloat(list[2]).coerceIn(0.1f, 4f)
                rggbGains[3] = toFloat(list[3]).coerceIn(0.1f, 4f)
                rggbEnabled = true
                rggbDirty = true
            }
        }
        params["rggbReset"]?.let {
            rggbGains = floatArrayOf(1f, 1f, 1f, 1f)
            rggbEnabled = false
            awbLocked = false
            post {
                runCatching { cam.enableAutoWhiteBalance(CameraMetadata.CONTROL_AWB_MODE_AUTO) }
                    .onFailure { Log.e(tag, "rggbReset falhou", it) }
            }
            whiteBalanceMode = "auto"
            Log.d(tag, "rggbReset")
        }
        if (rggbDirty) applyRggbGains(cam)

        params["zoom"]?.let {
            val z = when (it) {
                is Double -> it.toFloat()
                is Number -> it.toFloat()
                else      -> it.toString().toFloatOrNull() ?: 0f
            }.coerceIn(0f, 1f)
            zoomLevel = z
            opticalZoomIndex = -1
            applyDigitalZoom(cam, z)
            Log.d(tag, "zoom -> $z")
        }

        params["opticalZoom"]?.let { raw ->
            val idx = when (raw) {
                is Double -> raw.toInt()
                is Number -> raw.toInt()
                else      -> raw.toString().toIntOrNull() ?: -1
            }
            if (idx >= 0 && idx < opticalZoomLevels.size) {
                opticalZoomIndex = idx
                applyOpticalZoom(cam, opticalZoomLevels[idx])
                zoomLevel = 0f
                post { runCatching { cam.setZoom(cam.zoomRange.lower) } }
                Log.d(tag, "opticalZoom idx=$idx focalLength=${opticalZoomLevels[idx]}mm")
            } else {
                Log.w(tag, "opticalZoom idx=$idx inválido")
            }
        }

        params["lantern"]?.let {
            lanternEnabled = it as Boolean
            flashMode = if (lanternEnabled) "torch" else "off"
            applyTorch(cam, lanternEnabled)
            Log.d(tag, "lantern -> $lanternEnabled")
        }

        params["ois"]?.let {
            oisEnabled = it as Boolean
            applyOIS(cam, oisEnabled)
            Log.d(tag, "ois -> $oisEnabled")
        }

        params["eis"]?.let {
            eisEnabled = it as Boolean
            applyEIS(cam, eisEnabled)
            Log.d(tag, "eis -> $eisEnabled")
        }

        params["aeLock"]?.let {
            aeLocked = it as Boolean
            applyAELock(cam, aeLocked)
            Log.d(tag, "aeLock -> $aeLocked")
        }

        params["awbLock"]?.let {
            awbLocked = it as Boolean
            applyAWBLock(cam, awbLocked)
            Log.d(tag, "awbLock -> $awbLocked")
        }

        params["flashMode"]?.let {
            flashMode = it as String
            lanternEnabled = flashMode == "torch"
            applyFlashMode(cam, flashMode)
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
            opticalZoomIndex = -1
            appContext?.let { ctx ->
                val newCaps = discoverAllCameras(ctx).firstOrNull { it.cameraId == currentCameraId }
                opticalZoomLevels = newCaps?.focalLengths ?: emptyList()
                Log.d(tag, "camera=$currentCameraId opticalZoomLevels=$opticalZoomLevels")
            }
            post {
                cam.switchCamera(currentCameraId)
                initLiveMonitorDelayed(300L)
                if (manualSensor) applyManualSensor()
                else if (!autoFocus && focusDistance > 0f) applyFocusDistance(cam, focusDistance)
                Log.d(tag, "camera -> $currentCameraId")
            }
        }

        params["resolution"]?.let { value ->
            val (w, h, br) = when (value as String) {
                "4k", "3840x2160"    -> Triple(3840, 2160, 20000)
                "1080p", "1920x1080" -> Triple(1920, 1080, 8000)
                "720p", "1280x720"   -> Triple(1280, 720, 4000)
                else -> {
                    val parts = value.split("x")
                    Triple(
                        parts.getOrNull(0)?.toIntOrNull() ?: 1920,
                        parts.getOrNull(1)?.toIntOrNull() ?: 1080,
                        currentBitrate
                    )
                }
            }
            currentWidth = w
            currentHeight = h
            currentBitrate = br
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
                "off" -> CameraMetadata.EDGE_MODE_OFF
                "fast" -> CameraMetadata.EDGE_MODE_FAST
                else -> CameraMetadata.EDGE_MODE_HIGH_QUALITY
            }
            schedulePostProcessing()
            Log.d(tag, "edgeMode -> $it")
        }

        params["noiseReduction"]?.let {
            noiseReductionMode = when (it as String) {
                "off" -> CameraMetadata.NOISE_REDUCTION_MODE_OFF
                "fast" -> CameraMetadata.NOISE_REDUCTION_MODE_FAST
                "minimal" -> CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
                else -> CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
            }
            schedulePostProcessing()
            Log.d(tag, "noiseReduction -> $it")
        }

        params["hotPixel"]?.let {
            hotPixelMode = when (it as String) {
                "off" -> CameraMetadata.HOT_PIXEL_MODE_OFF
                "fast" -> CameraMetadata.HOT_PIXEL_MODE_FAST
                else -> CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY
            }
            schedulePostProcessing()
            Log.d(tag, "hotPixel -> $it")
        }
    }

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

            // ── Scene Modes (17 = HIGH_SPEED_VIDEO, deprecated como constante no Android 12+)
            val sceneModes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)?.map {
                when (it) {
                    CameraMetadata.CONTROL_SCENE_MODE_DISABLED       -> "disabled"
                    CameraMetadata.CONTROL_SCENE_MODE_ACTION         -> "action"
                    CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT       -> "portrait"
                    CameraMetadata.CONTROL_SCENE_MODE_LANDSCAPE      -> "landscape"
                    CameraMetadata.CONTROL_SCENE_MODE_NIGHT          -> "night"
                    CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT -> "night_portrait"
                    CameraMetadata.CONTROL_SCENE_MODE_THEATRE        -> "theatre"
                    CameraMetadata.CONTROL_SCENE_MODE_BEACH          -> "beach"
                    CameraMetadata.CONTROL_SCENE_MODE_SNOW           -> "snow"
                    CameraMetadata.CONTROL_SCENE_MODE_SUNSET         -> "sunset"
                    CameraMetadata.CONTROL_SCENE_MODE_STEADYPHOTO    -> "steadyphoto"
                    CameraMetadata.CONTROL_SCENE_MODE_FIREWORKS      -> "fireworks"
                    CameraMetadata.CONTROL_SCENE_MODE_SPORTS         -> "sports"
                    CameraMetadata.CONTROL_SCENE_MODE_PARTY          -> "party"
                    CameraMetadata.CONTROL_SCENE_MODE_CANDLELIGHT    -> "candlelight"
                    CameraMetadata.CONTROL_SCENE_MODE_BARCODE        -> "barcode"
                    17                                               -> "high_speed_video"
                    CameraMetadata.CONTROL_SCENE_MODE_HDR            -> "hdr"
                    else -> "unknown_$it"
                }
            }?.filter { it != "disabled" } ?: emptyList()

            // ── Effect Modes
            val effectModes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS)?.map {
                when (it) {
                    CameraMetadata.CONTROL_EFFECT_MODE_OFF       -> "off"
                    CameraMetadata.CONTROL_EFFECT_MODE_MONO      -> "mono"
                    CameraMetadata.CONTROL_EFFECT_MODE_NEGATIVE  -> "negative"
                    CameraMetadata.CONTROL_EFFECT_MODE_SOLARIZE  -> "solarize"
                    CameraMetadata.CONTROL_EFFECT_MODE_SEPIA     -> "sepia"
                    CameraMetadata.CONTROL_EFFECT_MODE_POSTERIZE -> "posterize"
                    CameraMetadata.CONTROL_EFFECT_MODE_WHITEBOARD -> "whiteboard"
                    CameraMetadata.CONTROL_EFFECT_MODE_BLACKBOARD -> "blackboard"
                    CameraMetadata.CONTROL_EFFECT_MODE_AQUA      -> "aqua"
                    else -> "unknown_$it"
                }
            }?.filter { it != "off" } ?: emptyList()

            // ── Focus Distance Calibration
            val focusCalibration = when (ch.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)) {
                CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED   -> "CALIBRATED"
                CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE  -> "APPROXIMATE"
                else                                                                     -> "UNCALIBRATED"
            }

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
                supportedAwbModes = awbModes,
                supportedSceneModes = sceneModes,
                supportedEffectModes = effectModes,
                hasFlash = hasFlash, hasOis = hasOis,
                focalLengths = focalLengths, apertures = apertures,
                focusDistanceCalibration = focusCalibration
            ))
        }
        return cameras
    }
}
