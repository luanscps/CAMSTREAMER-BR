package com.camera2rtsp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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

    var rtmpUrl = DEFAULT_RTMP_URL

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        const val ACTION_STOP    = "com.camera2rtsp.STOP"
        const val EXTRA_RTMP_URL = "rtmp_url"
        const val DEFAULT_RTMP_URL = "rtmp://192.168.1.100:1935/live/stream"

        // Timeout de segurança do WakeLock: 4 horas
        // Garante liberação automática mesmo se o processo crashar sem chamar onDestroy()
        private const val WAKELOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L

        var instance: StreamingService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        cameraController = Camera2Controller().also {
            it.appContext = applicationContext
            it.currentCameraId = "0"
        }
        rtmpStreamer = RtmpStreamer(cameraController, this)
        rtmpStreamer.initBackground(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        intent?.getStringExtra(EXTRA_RTMP_URL)?.let { rtmpUrl = it }

        startForeground(notifId, buildNotification("Aguardando preview..."))

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "camera2rtsp:streaming")
            .apply { acquire(WAKELOCK_TIMEOUT_MS) }

        try {
            httpServer = WebControlServer(8080, cameraController, applicationContext)
            httpServer.start()
            Log.i(tag, "Painel web em http://${getLocalIpAddress()}:8080")
        } catch (e: Exception) {
            Log.e(tag, "Erro ao iniciar httpServer", e)
        }

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
        if (wakeLock?.isHeld == true) wakeLock?.release()
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
        if (!wasStreaming) {
            rtmpStreamer.stopPreview()
            rtmpStreamer.initBackground(applicationContext)
        }
    }

    fun switchCamera(cameraId: String, facing: CameraHelper.Facing) {
        currentFacing = facing
        cameraController.currentCameraId = cameraId
        rtmpStreamer.switchCameraById(cameraId, facing)
        Log.i(tag, "switchCamera: id=$cameraId facing=$facing")
    }

    fun startPreview(facing: CameraHelper.Facing = currentFacing) {
        currentFacing = facing
        rtmpStreamer.startPreview(facing)
    }

    fun stopPreview()  = rtmpStreamer.stopPreview()
    fun startStream()  { rtmpStreamer.startStream(applicationContext, rtmpUrl) }
    fun stopStream()   = rtmpStreamer.stopStream()

    override fun onConnectionStarted(url: String)    { Log.i(tag, "RTMP conectando: $url");   updateNotification("Conectando RTMP...") }
    override fun onConnectionSuccess()               { Log.i(tag, "RTMP conectado");           updateNotification("Streaming ativo") }
    override fun onConnectionFailed(reason: String)  { Log.e(tag, "RTMP falhou: $reason");     updateNotification("Erro: $reason") }
    override fun onDisconnect()                      { Log.i(tag, "RTMP desconectado");        updateNotification("Desconectado") }
    override fun onAuthError()                       { Log.e(tag, "RTMP auth error") }
    override fun onAuthSuccess()                     { Log.i(tag, "RTMP auth ok") }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId, "Camera2 RTMP Streaming",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Stream RTMP ativo"; setShowBadge(false) }
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
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Camera2 RTMP")
            .setContentText(status)
            .setSubText(rtmpUrl)
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
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
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
