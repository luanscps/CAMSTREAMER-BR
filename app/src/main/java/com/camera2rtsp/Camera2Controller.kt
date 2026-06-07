package com.camera2rtsp

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.pedro.encoder.input.video.Camera2ApiManager
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.base.Camera2Base
import com.pedro.library.rtmp.RtmpCamera2
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

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

    var yuvProcessorEnabled = false
    var yuvProcessor: YuvFrameProcessor? = null
    var yuvFrameCallback: ((YuvFrame) -> Unit)? = null

    // RAW capture
    private var rawManager: RawCaptureManager? = null
    private var rawFrameCallback: ((RawCaptureManager.RawFrame) -> Unit)? = null
    private val rawReaderThread = HandlerThread("RawReaderThread").also { it.start() }
    private val rawReaderHandler = Handler(rawReaderThread.looper)

    // fix: pendingCaptureResult substituído por AtomicReference para thread-safety real
    private val pendingCaptureResultRef = java.util.concurrent.atomic.AtomicReference<TotalCaptureResult?>(null)

    @Volatile var lastRawDngBytes: ByteArray? = null
    @Volatile var lastRawFilename: String = ""

    var depthFusionEnabled = false
    var depthProcessor: DepthFusionProcessor? = null
    var depthFrameCallback: ((DepthFrame) -> Unit)? = null

    @Volatile var liveIso        = 0
    @Volatile var liveExposureNs = 0L
    @Volatile var liveRggbR      = 1f
    @Volatile var liveRggbGr     = 1f
    @Volatile var liveRggbGb     = 1f
    @Volatile var liveRggbB      = 1f
    @Volatile var liveAfState    = "unknown"
    @Volatile var liveAeState    = "unknown"
    @Volatile var lastDepthMeanMm = 0f
    @Volatile var lastDepthMinMm = 0
    @Volatile var lastDepthMaxMm = 0
    @Volatile var lastYuvTimestampNs = 0L

    private val workerThread = HandlerThread("CameraWorker").also { it.start() }
    private val worker = Handler(workerThread.looper)
    private fun post(block: () -> Unit) =
        worker.post { runCatching(block).onFailure { Log.e(tag, "worker error", it) } }

    // -------------------------------------------------------------------------
    // Reflexão: campos do Camera2Base / Camera2ApiManager
    // -------------------------------------------------------------------------

    private val reflField_cameraManager: java.lang.reflect.Field? by lazy {
        runCatching {
            Camera2Base::class.java.getDeclaredField("cameraManager")
                .also { it.isAccessible = true }
        }.onFailure { Log.w(tag, "[reflection] campo 'cameraManager' nao encontrado: ${it.message}") }
            .getOrNull()
    }

    private val reflField_captureSession: java.lang.reflect.Field? by lazy {
        runCatching {
            Camera2ApiManager::class.java.getDeclaredField("cameraCaptureSession")
                .also { it.isAccessible = true }
        }.onFailure { Log.w(tag, "[reflection] campo 'cameraCaptureSession' nao encontrado: ${it.message}") }
            .getOrNull()
    }

    private val reflField_builder: java.lang.reflect.Field? by lazy {
        runCatching {
            Camera2ApiManager::class.java.getDeclaredField("builderInputSurface")
                .also { it.isAccessible = true }
        }.onFailure { Log.w(tag, "[reflection] campo 'builderInputSurface' nao encontrado: ${it.message}") }
            .getOrNull()
    }

    private val reflField_cameraHandler: java.lang.reflect.Field? by lazy {
        runCatching {
            Camera2ApiManager::class.java.getDeclaredField("cameraHandler")
                .also { it.isAccessible = true }
        }.onFailure { Log.w(tag, "[reflection] campo 'cameraHandler' nao encontrado: ${it.message}") }
            .getOrNull()
    }

    private val reflField_cameraDevice: java.lang.reflect.Field? by lazy {
        runCatching {
            Camera2ApiManager::class.java.getDeclaredField("cameraDevice")
                .also { it.isAccessible = true }
        }.onFailure { Log.w(tag, "[reflection] campo 'cameraDevice' nao encontrado: ${it.message}") }
            .getOrNull()
    }

    private fun getCam2Manager(): Camera2ApiManager? {
        val field = reflField_cameraManager ?: return null
        return runCatching {
            field.get(rtmpCamera) as? Camera2ApiManager
        }.onFailure { Log.e(tag, "getCam2Manager falhou", it) }.getOrNull()
    }

    private fun getCaptureSession(cam2: Camera2ApiManager): CameraCaptureSession? =
        runCatching { reflField_captureSession?.get(cam2) as? CameraCaptureSession }
            .onFailure { Log.e(tag, "getCaptureSession falhou", it) }.getOrNull()

    private fun getBuilderInputSurface(cam2: Camera2ApiManager): CaptureRequest.Builder? =
        runCatching { reflField_builder?.get(cam2) as? CaptureRequest.Builder }
            .onFailure { Log.e(tag, "getBuilderInputSurface falhou", it) }.getOrNull()

    private fun getCameraHandler(cam2: Camera2ApiManager): Handler? =
        runCatching { reflField_cameraHandler?.get(cam2) as? Handler }
            .onFailure { Log.e(tag, "getCameraHandler falhou", it) }.getOrNull()

    private fun getCameraDevice(cam2: Camera2ApiManager): CameraDevice? =
        runCatching { reflField_cameraDevice?.get(cam2) as? CameraDevice }
            .onFailure { Log.e(tag, "getCameraDevice falhou", it) }.getOrNull()

    // -------------------------------------------------------------------------

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
            Log.w(tag, "[liveMonitor] getCam2Manager nulo - abortando")
            return
        }
        // fix: verifica se a sessão já está aberta antes de registrar o callback
        val session = getCaptureSession(cam2mgr)
        if (session == null) {
            Log.w(tag, "[liveMonitor] sessão ainda não aberta - reagendando em 500ms")
            worker.postDelayed({ initLiveMonitor() }, 500L)
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

    fun enableYuvProcessor(width: Int = currentWidth, height: Int = currentHeight, callback: ((YuvFrame) -> Unit)? = null): Boolean {
        val ctx = appContext ?: return false
        val caps = CameraCapabilitiesReader.read(ctx, currentCameraId) ?: return false
        if (!caps.supportsYuvImageReader()) {
            Log.w(tag, "enableYuvProcessor: camera $currentCameraId sem suporte YUV")
            return false
        }
        releaseYuvProcessor()
        yuvFrameCallback = callback
        yuvProcessor = YuvFrameProcessor(width, height) { frame ->
            lastYuvTimestampNs = frame.timestampNs
            yuvFrameCallback?.invoke(frame)
        }.also { it.init() }
        yuvProcessorEnabled = true
        Log.i(tag, "enableYuvProcessor ok ${width}x${height}")
        return true
    }

    fun disableYuvProcessor() {
        releaseYuvProcessor()
        yuvFrameCallback = null
        yuvProcessorEnabled = false
        Log.i(tag, "disableYuvProcessor ok")
    }

    // -------------------------------------------------------------------------
    // RAW Capture — sessão dedicada: stop stream → abre câmera → captura → resume
    //
    // O Android Camera2 não permite adicionar surfaces a uma CameraCaptureSession
    // já aberta. A única abordagem correta é:
    //   1. Parar o stream (libera a sessão do RootEncoder)
    //   2. Abrir o CameraDevice diretamente via CameraManager
    //   3. Criar sessão com apenas rawImageReader.surface
    //   4. Disparar TEMPLATE_STILL_CAPTURE one-shot
    //   5. Fechar sessão e device RAW
    //   6. Retomar o stream
    // -------------------------------------------------------------------------

    /**
     * Ponto de entrada: valida suporte RAW e despacha no worker thread.
     */
    fun captureRawStill(context: Context) {
        val ctx = appContext ?: context
        val caps = CameraCapabilitiesReader.read(ctx, currentCameraId)
        if (caps == null || !caps.isRawCaptureFeasible()) {
            Log.w(tag, "captureRawStill: camera $currentCameraId nao suporta RAW")
            return
        }
        val mgrSvc = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = runCatching {
            mgrSvc.getCameraCharacteristics(currentCameraId)
        }.getOrElse {
            Log.e(tag, "captureRawStill: CameraCharacteristics falhou", it)
            return
        }
        val rawSize = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.maxByOrNull { it.width * it.height }
        val rw = rawSize?.width  ?: currentWidth
        val rh = rawSize?.height ?: currentHeight

        post { dispatchRawCapture(ctx, mgrSvc, characteristics, rw, rh) }
    }

    /**
     * Executa a captura RAW com sessão dedicada.
     * Roda no worker thread.
     */
    private fun dispatchRawCapture(
        ctx: Context,
        cameraManager: CameraManager,
        characteristics: CameraCharacteristics,
        rw: Int,
        rh: Int
    ) {
        // fix: sempre recria o rawManager para garantir estado limpo a cada captura
        releaseRawManager()
        rawManager = RawCaptureManager(
            rw, rh, characteristics,
            onRawFrame = { frame ->
                if (frame.dngBytes.isNotEmpty()) {
                    val fn = "RAW_${System.currentTimeMillis()}.dng"
                    lastRawDngBytes = frame.dngBytes
                    lastRawFilename = fn
                    appContext?.let { saveToMediaStore(it, frame.dngBytes, fn) }
                    Log.i(tag, "onRawFrame: DNG pronto — ${frame.dngBytes.size / 1024} KB, arquivo=$fn")
                } else {
                    Log.w(tag, "onRawFrame: dngBytes vazio, captura ignorada")
                }
                rawFrameCallback?.invoke(frame)
            }
        )

        // --- 1. Para o stream para liberar a sessão do RootEncoder ---
        val wasStreaming = rtmpCamera?.isStreaming == true
        val svc = StreamingService.instance
        if (wasStreaming) {
            Log.i(tag, "dispatchRawCapture: pausando stream para captura RAW")
            svc?.stopStream()
            // fix: aguarda até 2s pelo fechamento real da sessão em vez de sleep fixo
            val stopLatch = java.util.concurrent.CountDownLatch(1)
            worker.postDelayed({ stopLatch.countDown() }, 600)
            stopLatch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        }

        val rawReader = ImageReader.newInstance(rw, rh, ImageFormat.RAW_SENSOR, 2)
        var rawDevice: CameraDevice? = null
        var rawSession: CameraCaptureSession? = null

        try {
            // --- 2. Abre o CameraDevice diretamente ---
            val openLatch = java.util.concurrent.CountDownLatch(1)
            cameraManager.openCamera(
                currentCameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        rawDevice = device
                        openLatch.countDown()
                    }
                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        openLatch.countDown()
                    }
                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        openLatch.countDown()
                        Log.e(tag, "dispatchRawCapture: openCamera error=$error")
                    }
                },
                rawReaderHandler
            )
            if (!openLatch.await(4, java.util.concurrent.TimeUnit.SECONDS) || rawDevice == null) {
                Log.e(tag, "dispatchRawCapture: openCamera timeout ou falhou")
                return
            }

            // --- 3. Cria sessão com apenas a surface RAW ---
            val sessionLatch = java.util.concurrent.CountDownLatch(1)
            rawDevice!!.createCaptureSession(
                listOf(rawReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        rawSession = session
                        sessionLatch.countDown()
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(tag, "dispatchRawCapture: createCaptureSession onConfigureFailed")
                        sessionLatch.countDown()
                    }
                },
                rawReaderHandler
            )
            if (!sessionLatch.await(4, java.util.concurrent.TimeUnit.SECONDS) || rawSession == null) {
                Log.e(tag, "dispatchRawCapture: createCaptureSession timeout ou falhou")
                return
            }

            // --- 4. Dispara one-shot STILL_CAPTURE ---
            // fix: usa AtomicReference para sincronização real entre onCaptureCompleted e onImageAvailable
            pendingCaptureResultRef.set(null)
            val captureLatch = java.util.concurrent.CountDownLatch(1)

            rawReader.setOnImageAvailableListener({ imageReader ->
                val image = imageReader.acquireNextImage() ?: return@setOnImageAvailableListener
                try {
                    // fix: aguarda até 3s pelo TotalCaptureResult antes de descartar
                    val deadline = System.currentTimeMillis() + 3000L
                    var result: TotalCaptureResult? = null
                    while (result == null && System.currentTimeMillis() < deadline) {
                        result = pendingCaptureResultRef.getAndSet(null)
                        if (result == null) Thread.sleep(20)
                    }
                    if (result != null) {
                        Log.d(tag, "onImageAvailable: processando DNG ${rw}x${rh}")
                        rawManager?.processImage(image, result)
                    } else {
                        Log.w(tag, "onImageAvailable: TotalCaptureResult timeout (3s) — descartando frame")
                    }
                } finally {
                    runCatching { image.close() }
                    captureLatch.countDown()
                }
            }, rawReaderHandler)

            val stillBuilder = rawDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            stillBuilder.addTarget(rawReader.surface)
            stillBuilder.set(
                CaptureRequest.CONTROL_CAPTURE_INTENT,
                CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE
            )

            rawSession!!.capture(
                stillBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        Log.d(tag, "dispatchRawCapture: onCaptureCompleted")
                        // fix: seta via AtomicReference — thread-safe
                        pendingCaptureResultRef.set(result)
                    }
                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure
                    ) {
                        Log.e(tag, "dispatchRawCapture: onCaptureFailed reason=${failure.reason}")
                        captureLatch.countDown()
                    }
                },
                rawReaderHandler
            )
            Log.i(tag, "dispatchRawCapture: one-shot enviado ${rw}x${rh}")

            // Aguarda imagem disponível (máx 8s para sensor RAW)
            captureLatch.await(8, java.util.concurrent.TimeUnit.SECONDS)

        } catch (e: Exception) {
            Log.e(tag, "dispatchRawCapture: falhou", e)
        } finally {
            // --- 5. Fecha sessão e device RAW ---
            runCatching { rawSession?.close() }
            runCatching { rawDevice?.close() }
            runCatching { rawReader.close() }
            pendingCaptureResultRef.set(null)
            Log.i(tag, "dispatchRawCapture: sessão RAW fechada")

            // --- 6. Retoma o stream ---
            if (wasStreaming) {
                Thread.sleep(300)
                Log.i(tag, "dispatchRawCapture: retomando stream")
                svc?.startStream()
            }
        }
    }

    private fun saveToMediaStore(context: Context, bytes: ByteArray, filename: String) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/CAMSTREAMER")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: run { Log.e(tag, "saveToMediaStore: insert retornou null"); return }
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                Log.i(tag, "saveToMediaStore (Q+): $uri")
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "CAMSTREAMER"
                )
                dir.mkdirs()
                val file = File(dir, filename)
                file.writeBytes(bytes)
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(file.absolutePath), arrayOf("image/x-adobe-dng"), null
                )
                Log.i(tag, "saveToMediaStore (<Q): ${file.absolutePath}")
            }
        }.onFailure { Log.e(tag, "saveToMediaStore falhou", it) }
    }

    fun enableDepthFusion(width: Int, height: Int, callback: ((DepthFrame) -> Unit)? = null): Boolean {
        val ctx = appContext ?: return false
        val caps = CameraCapabilitiesReader.read(ctx, currentCameraId) ?: return false
        if (!caps.hasUsableDepthSensor() && !caps.supportsYuvDepthFusion()) {
            Log.w(tag, "enableDepthFusion: camera $currentCameraId sem suporte depth")
            return false
        }
        releaseDepthProcessor()
        depthFrameCallback = callback
        depthProcessor = DepthFusionProcessor(width, height) { frame ->
            lastDepthMeanMm = frame.meanDepthMm
            lastDepthMinMm = frame.minDepthMm
            lastDepthMaxMm = frame.maxDepthMm
            depthFrameCallback?.invoke(frame)
        }.also { it.init() }
        depthFusionEnabled = true
        Log.i(tag, "enableDepthFusion ok ${width}x${height}")
        return true
    }

    fun disableDepthFusion() {
        releaseDepthProcessor()
        depthFrameCallback = null
        depthFusionEnabled = false
        Log.i(tag, "disableDepthFusion ok")
    }

    private fun releaseYuvProcessor() {
        runCatching { yuvProcessor?.release() }
            .onFailure { Log.e(tag, "releaseYuvProcessor falhou", it) }
        yuvProcessor = null
    }

    private fun releaseRawManager() {
        runCatching { rawManager?.release() }
            .onFailure { Log.e(tag, "releaseRawManager falhou", it) }
        rawManager = null
    }

    private fun releaseDepthProcessor() {
        runCatching { depthProcessor?.release() }
            .onFailure { Log.e(tag, "releaseDepthProcessor falhou", it) }
        depthProcessor = null
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
            // fix: removido disableAutoWhiteBalance() duplicado no path de lock
            // Apenas no unlock, garante que AWB volta ao automático
            if (!lock) {
                if (!ok) {
                    runCatching { cam.enableAutoWhiteBalance(CameraMetadata.CONTROL_AWB_MODE_AUTO) }
                        .onFailure { Log.e(tag, "awb unlock fallback falhou", it) }
                }
            }
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
                    if (!ok) Log.w(tag, "flash single nao suportado via custom request")
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

        params["yuvCapture"]?.let { value ->
            val enabled = when (value) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                else -> value.toString().equals("true", ignoreCase = true)
            }
            if (enabled) enableYuvProcessor(currentWidth, currentHeight)
            else disableYuvProcessor()
            Log.d(tag, "yuvCapture -> $enabled")
        }

        params["depthFusion"]?.let { value ->
            val enabled = when (value) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                else -> value.toString().equals("true", ignoreCase = true)
            }
            if (enabled) enableDepthFusion(currentWidth, currentHeight)
            else disableDepthFusion()
            Log.d(tag, "depthFusion -> $enabled")
        }

        params["manualSensor"]?.let {
            manualSensor = it as Boolean
            if (manualSensor) {
                applyManualSensor()
            } else {
                // fix: reseta frameDurationNs ao sair do modo manual para evitar valor stale
                frameDurationNs = 33_333_333L
                applyAutoSensor(cam)
                cam.setExposure(0)
                exposureLevel = 0
            }
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
                autoFocus = true; focusDistance = 0f; applyAutoFocus(cam)
            } else {
                autoFocus = false; focusDistance = norm * 10f; applyFocusDistance(cam, focusDistance)
            }
            Log.d(tag, "focus norm=$norm dist=$focusDistance")
        }

        params["focusmode"]?.let {
            when (it as String) {
                "continuous-video", "continuous-picture", "auto" -> {
                    autoFocus = true; focusDistance = 0f; applyAutoFocus(cam)
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

        // fix: afTrigger envia TRIGGER_START seguido de TRIGGER_IDLE no próximo frame
        // para evitar que o trigger fique "preso" e degrade o AF contínuo
        params["afTrigger"]?.let {
            post {
                val okStart = setCustomRequest { b ->
                    b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                }
                Log.d(tag, "afTrigger START ok=$okStart")
                // reset imediato para IDLE no próximo frame
                worker.postDelayed({
                    setCustomRequest { b ->
                        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                    }
                    Log.d(tag, "afTrigger -> IDLE (reset)")
                }, 100L)
            }
        }

        params["zoom"]?.let {
            val z = when (it) {
                is Double -> it.toFloat()
                is Number -> it.toFloat()
                else      -> it.toString().toFloatOrNull() ?: 0f
            }.coerceIn(0f, 1f)
            zoomLevel = z
            if (opticalZoomLevels.isNotEmpty() && opticalZoomIndex >= 0) {
                val fl = opticalZoomLevels.getOrNull(opticalZoomIndex) ?: opticalZoomLevels.first()
                applyOpticalZoom(cam, fl)
            } else {
                applyDigitalZoom(cam, z)
            }
            Log.d(tag, "zoom -> $z")
        }

        params["opticalZoomIndex"]?.let {
            val idx = when (it) {
                is Double -> it.toInt()
                is Int    -> it
                is Number -> it.toInt()
                else      -> it.toString().toIntOrNull() ?: -1
            }
            opticalZoomIndex = idx
            val fl = opticalZoomLevels.getOrNull(idx)
            if (fl != null) applyOpticalZoom(cam, fl)
            Log.d(tag, "opticalZoomIndex -> $idx fl=$fl")
        }

        params["wb"]?.let {
            val mode = it as String
            whiteBalanceMode = mode
            post {
                runCatching {
                    if (mode == "auto") {
                        cam.enableAutoWhiteBalance(CameraMetadata.CONTROL_AWB_MODE_AUTO)
                    } else {
                        val wbConst = when (mode) {
                            "incandescent"  -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
                            "fluorescent"   -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
                            "warm_fluorescent" -> CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT
                            "daylight"      -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                            "cloudy"        -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                            "twilight"      -> CameraMetadata.CONTROL_AWB_MODE_TWILIGHT
                            "shade"         -> CameraMetadata.CONTROL_AWB_MODE_SHADE
                            else            -> CameraMetadata.CONTROL_AWB_MODE_AUTO
                        }
                        cam.enableAutoWhiteBalance(wbConst)
                    }
                }.onFailure { Log.e(tag, "wb falhou", it) }
                Log.d(tag, "wb -> $mode")
            }
        }

        params["ois"]?.let {
            oisEnabled = it as Boolean
            applyOIS(cam, oisEnabled)
        }

        params["eis"]?.let {
            eisEnabled = it as Boolean
            applyEIS(cam, eisEnabled)
        }

        params["aeLock"]?.let {
            aeLocked = it as Boolean
            applyAELock(cam, aeLocked)
        }

        params["awbLock"]?.let {
            awbLocked = it as Boolean
            applyAWBLock(cam, awbLocked)
        }

        params["torch"]?.let {
            lanternEnabled = it as Boolean
            applyTorch(cam, lanternEnabled)
        }

        params["flashMode"]?.let {
            flashMode = it as String
            applyFlashMode(cam, flashMode)
        }

        params["edge"]?.let {
            edgeMode = when (it as String) {
                "off"          -> CameraMetadata.EDGE_MODE_OFF
                "fast"         -> CameraMetadata.EDGE_MODE_FAST
                "high_quality" -> CameraMetadata.EDGE_MODE_HIGH_QUALITY
                else           -> CameraMetadata.EDGE_MODE_HIGH_QUALITY
            }
            schedulePostProcessing()
        }

        params["nr"]?.let {
            noiseReductionMode = when (it as String) {
                "off"          -> CameraMetadata.NOISE_REDUCTION_MODE_OFF
                "minimal"      -> CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
                "fast"         -> CameraMetadata.NOISE_REDUCTION_MODE_FAST
                "high_quality" -> CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
                else           -> CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
            }
            schedulePostProcessing()
        }

        params["hotPixel"]?.let {
            hotPixelMode = when (it as String) {
                "off"          -> CameraMetadata.HOT_PIXEL_MODE_OFF
                "fast"         -> CameraMetadata.HOT_PIXEL_MODE_FAST
                "high_quality" -> CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY
                else           -> CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY
            }
            schedulePostProcessing()
        }

        params["rggbEnabled"]?.let {
            rggbEnabled = it as Boolean
            if (!rggbEnabled) {
                // fix: zera gains para 1f antes de reativar AWB para evitar balanço residual
                rggbGains = floatArrayOf(1f, 1f, 1f, 1f)
                post {
                    runCatching {
                        setCustomRequest { b ->
                            val neutralGains = android.hardware.camera2.params.RggbChannelVector(1f, 1f, 1f, 1f)
                            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, neutralGains)
                        }
                        cam.enableAutoWhiteBalance(CameraMetadata.CONTROL_AWB_MODE_AUTO)
                    }
                }
            }
            Log.d(tag, "rggbEnabled -> $rggbEnabled")
        }

        params["rggbR"]?.let  { rggbGains[0] = (it as Number).toFloat() }
        params["rggbGr"]?.let { rggbGains[1] = (it as Number).toFloat() }
        params["rggbGb"]?.let { rggbGains[2] = (it as Number).toFloat() }
        params["rggbB"]?.let  { rggbGains[3] = (it as Number).toFloat() }
        if (params.any { it.key in listOf("rggbR", "rggbGr", "rggbGb", "rggbB") } && rggbEnabled) {
            applyRggbGains(cam)
        }

        // fix: changeCamera → delega para StreamingService.switchCamera que já trata facing corretamente
        params["camera"]?.let { camIdAny ->
            val newCamId = camIdAny.toString()
            if (newCamId != currentCameraId) {
                currentCameraId = newCamId
                releaseRawManager()
                val facing = if (newCamId == "1") CameraHelper.Facing.FRONT else CameraHelper.Facing.BACK
                post {
                    runCatching {
                        StreamingService.instance?.switchCamera(newCamId, facing)
                    }.onFailure { Log.e(tag, "camera switch falhou", it) }
                    Log.d(tag, "camera -> $newCamId facing=$facing")
                }
            }
        }

        params["camera_id"]?.let { camIdAny ->
            val newCamId = camIdAny.toString()
            if (newCamId != currentCameraId) {
                currentCameraId = newCamId
                releaseRawManager()
                val facing = if (newCamId == "1") CameraHelper.Facing.FRONT else CameraHelper.Facing.BACK
                post {
                    runCatching {
                        StreamingService.instance?.switchCamera(newCamId, facing)
                    }.onFailure { Log.e(tag, "camera_id switch falhou", it) }
                    Log.d(tag, "camera_id -> $newCamId facing=$facing")
                }
            }
        }
    }

    fun applyAdvancedVision(
        visionEnabled: Boolean,
        ctx: Context
    ) {
        if (visionEnabled) {
            enableYuvProcessor(currentWidth, currentHeight)
        } else {
            disableYuvProcessor()
        }
    }

    private fun parseTimeParam(raw: Any, default: Long): Long = when (raw) {
        is Double -> raw.toLong()
        is Long   -> raw
        is Int    -> raw.toLong()
        is Number -> raw.toLong()
        is String -> raw.toLongOrNull() ?: default
        else      -> default
    }

    // fix: discoverAllCameras agora delega explicitamente para CameraCapabilitiesReader
    // resolve o "Unresolved reference" quando chamado dentro de post {} lambdas
    fun discoverAllCameras(context: Context): List<CameraCapabilities> =
        CameraCapabilitiesReader.discoverAllCameras(context)

    fun release() {
        releaseYuvProcessor()
        releaseRawManager()
        releaseDepthProcessor()
        runCatching { workerThread.quitSafely() }
        runCatching { rawReaderThread.quitSafely() }
    }
}
