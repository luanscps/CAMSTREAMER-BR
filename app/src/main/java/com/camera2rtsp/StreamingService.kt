package com.camera2rtsp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.view.OpenGlView
import java.net.Inet4Address
import java.net.NetworkInterface

class StreamingService : Service(), ConnectChecker {

    private val tag       = "StreamingService"
    private val notifId   = 1
    private val channelId = "camera2rtsp_channel"

    lateinit var cameraController: Camera2Controller
        private set
    lateinit var rtmpStreamer: RtmpStreamer
        private set
    lateinit var httpServer: WebControlServer
        private set

    private var wakeLock: PowerManager.WakeLock? = null
    private var currentFacing = CameraHelper.Facing.BACK

    // ── Protocolo ───────────────────────────────────────────────────────────
    /** rtmp:// ou srt:// — detectado automaticamente em startStream() */
    var streamUrl = "rtmp://192.168.1.100:1935/live/stream"

    /** Compatibilidade com código antigo que usava rtmpUrl */
    var rtmpUrl: String
        get() = streamUrl
        set(v) { streamUrl = v }

    // ── Persistência de câmera ───────────────────────────────────────────────
    private lateinit var camPrefs: SharedPreferences

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        const val ACTION_STOP    = "com.camera2rtsp.STOP"
        const val EXTRA_RTMP_URL = "rtmp_url"

        // Chaves SharedPreferences para parâmetros de câmera
        const val KEY_ISO        = "cam_iso"
        const val KEY_SHUTTER    = "cam_shutter"
        const val KEY_WB         = "cam_wb"
        const val KEY_ZOOM       = "cam_zoom"
        const val KEY_MANUAL     = "cam_manual"
        const val KEY_OIS        = "cam_ois"
        const val KEY_BITRATE    = "cam_bitrate"
        const val KEY_FPS        = "cam_fps"
        const val KEY_CAMERA_ID  = "cam_id"
        const val KEY_STREAM_URL = "stream_url"

        var instance: StreamingService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        camPrefs = getSharedPreferences("camera2rtmp_prefs", Context.MODE_PRIVATE)
        createNotificationChannel()
        cameraController = Camera2Controller().also {
            it.appContext = applicationContext
            it.currentCameraId = camPrefs.getString(KEY_CAMERA_ID, "0") ?: "0"
        }
        rtmpStreamer = RtmpStreamer(cameraController, this)
        rtmpStreamer.initBackground(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        // URL: prioridade para intent, depois prefs, depois default
        streamUrl = intent?.getStringExtra(EXTRA_RTMP_URL)
            ?: camPrefs.getString(KEY_STREAM_URL, streamUrl)
            ?: streamUrl

        startForeground(notifId, buildNotification("Aguardando preview..."))

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "camera2rtsp:streaming")
            .apply { acquire() }   // release() explícito em onDestroy

        try {
            httpServer = WebControlServer(8080, cameraController, applicationContext)
            httpServer.start()
            Log.i(tag, "Painel web em http://${getLocalIpAddress()}:8080")
        } catch (e: Exception) {
            Log.e(tag, "Erro ao iniciar httpServer", e)
        }

        // Restaura parâmetros de câmera persistidos
        restoreCameraParams()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try {
            rtmpStreamer.stop()
            if (::cameraController.isInitialized) cameraController.release()
            if (::httpServer.isInitialized) httpServer.stop()
        } catch (e: Exception) { Log.e(tag, "Erro ao parar", e) }
        wakeLock?.release()
        Log.i(tag, "Servico encerrado")
    }

    fun attachView(view: OpenGlView) {
        Log.i(tag, "attachView")
        rtmpStreamer.initWithView(view, applicationContext)
        rtmpStreamer.startPreview(currentFacing)
        updateNotification("Preview ativo")
    }

    fun detachView() {
        Log.i(tag, "detachView")
        val wasStreaming = rtmpStreamer.isStreaming
        rtmpStreamer.stop()
        rtmpStreamer.initBackground(applicationContext)
        if (wasStreaming) startStream()
    }

    fun switchCamera(cameraId: String, facing: CameraHelper.Facing) {
        currentFacing = facing
        cameraController.currentCameraId = cameraId
        rtmpStreamer.switchCameraById(cameraId, facing)
        saveCameraParam(KEY_CAMERA_ID, cameraId)
        Log.i(tag, "switchCamera: id=$cameraId facing=$facing")
    }

    fun startPreview(facing: CameraHelper.Facing = currentFacing) {
        currentFacing = facing
        rtmpStreamer.startPreview(facing)
    }

    fun stopPreview()  = rtmpStreamer.stopPreview()

    /**
     * Inicia stream detectando protocolo automaticamente.
     * Suporta rtmp:// e srt://
     */
    fun startStream() {
        saveCameraParam(KEY_STREAM_URL, streamUrl)
        if (streamUrl.startsWith("srt://", ignoreCase = true)) {
            startSrtStream()
        } else {
            rtmpStreamer.startStream(applicationContext, streamUrl)
        }
    }

    fun stopStream() = rtmpStreamer.stopStream()

    // ── SRT ─────────────────────────────────────────────────────────────────
    /**
     * Inicia stream via SRT usando a RtmpStreamer como camada base.
     * A RootEncoder 2.6.x aceita URL srt:// diretamente no mesmo método
     * startStream usado para RTMP.
     */
    private fun startSrtStream() {
        Log.i(tag, "Iniciando SRT: $streamUrl")
        try {
            rtmpStreamer.startStream(applicationContext, streamUrl)
            updateNotification("SRT streaming ativo")
        } catch (e: Exception) {
            Log.e(tag, "Erro ao iniciar SRT", e)
            updateNotification("Erro SRT: ${e.message}")
        }
    }

    // ── Persistência de parâmetros ───────────────────────────────────────────
    fun saveCameraParam(key: String, value: String) {
        camPrefs.edit().putString(key, value).apply()
    }

    fun saveCameraParam(key: String, value: Float) {
        camPrefs.edit().putFloat(key, value).apply()
    }

    fun saveCameraParam(key: String, value: Boolean) {
        camPrefs.edit().putBoolean(key, value).apply()
    }

    fun saveCameraParam(key: String, value: Int) {
        camPrefs.edit().putInt(key, value).apply()
    }

    private fun restoreCameraParams() {
        try {
            val c = cameraController

            // ISO
            val iso = camPrefs.getInt(KEY_ISO, 0)
            if (iso > 0) c.isoValue = iso

            // WB
            val wb = camPrefs.getString(KEY_WB, "auto") ?: "auto"
            c.whiteBalanceMode = wb

            // Zoom
            val zoom = camPrefs.getFloat(KEY_ZOOM, 0f)
            if (zoom > 0f) c.zoomLevel = zoom

            // Modo manual
            val manual = camPrefs.getBoolean(KEY_MANUAL, false)
            c.manualSensor = manual

            // OIS
            val ois = camPrefs.getBoolean(KEY_OIS, true)
            c.oisEnabled = ois

            // Bitrate
            val bitrate = camPrefs.getInt(KEY_BITRATE, 4000)
            c.currentBitrate = bitrate

            // FPS
            val fps = camPrefs.getInt(KEY_FPS, 30)
            c.currentFps = fps

            Log.i(tag, "Parâmetros restaurados: iso=$iso wb=$wb zoom=$zoom manual=$manual bitrate=$bitrate fps=$fps")
        } catch (e: Exception) {
            Log.e(tag, "Erro ao restaurar parâmetros", e)
        }
    }

    // ── ConnectChecker ───────────────────────────────────────────────────────
    override fun onConnectionStarted(url: String)    { Log.i(tag, "Conectando: $url");   updateNotification("Conectando...") }
    override fun onConnectionSuccess()               { Log.i(tag, "Conectado");           updateNotification("Streaming ativo") }
    override fun onConnectionFailed(reason: String)  { Log.e(tag, "Falhou: $reason");     updateNotification("Erro: $reason") }
    override fun onDisconnect()                      { Log.i(tag, "Desconectado");        updateNotification("Desconectado") }
    override fun onAuthError()                       { Log.e(tag, "Auth error") }
    override fun onAuthSuccess()                     { Log.i(tag, "Auth ok") }

    // ── Notificação ──────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId, "Camera2 Streaming",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Stream ativo"; setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, StreamingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val proto = if (streamUrl.startsWith("srt://", ignoreCase = true)) "SRT" else "RTMP"
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Camera2 $proto")
            .setContentText(status)
            .setSubText(streamUrl)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_delete, "Parar", stopIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(notifId, buildNotification(status))
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address)
                        return address.hostAddress ?: "127.0.0.1"
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }
}
