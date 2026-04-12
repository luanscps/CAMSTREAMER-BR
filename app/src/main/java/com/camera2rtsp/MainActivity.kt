package com.camera2rtsp

import android.Manifest
import android.animation.ObjectAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.SurfaceHolder
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pedro.library.view.OpenGlView

class MainActivity : AppCompatActivity() {

    // -- Views ---------------------------------------------------------------
    private lateinit var cameraPreview: OpenGlView
    private lateinit var topBar: View
    private lateinit var statusText: TextView
    private lateinit var rtspBadge: TextView
    private lateinit var clientsBadge: TextView
    private lateinit var batteryText: TextView
    private lateinit var bottomActions: View
    private lateinit var settingsPanel: View
    private lateinit var btnShutter: View
    private lateinit var btnSettings: ImageButton
    private lateinit var btnClosePanel: ImageButton
    private lateinit var editRtmpUrl: EditText
    private lateinit var btnApplyRtmpUrl: Button

    // -- SharedPreferences ---------------------------------------------------
    private lateinit var prefs: SharedPreferences
    private val prefFile   = "camera2rtmp_prefs"
    private val keyRtmpUrl = "rtmp_url"
    // Fix 5: usa a constante centralizada no StreamingService
    private val defaultUrl get() = StreamingService.DEFAULT_RTMP_URL

    // -- Estado --------------------------------------------------------------
    private var isPanelOpen = false
    // Fix 1: isStreaming lê diretamente do service
    private val isStreaming: Boolean
        get() = service?.rtmpStreamer?.isStreaming ?: false

    // -- HUD ticker ----------------------------------------------------------
    private val hudHandler  = Handler(Looper.getMainLooper())
    private val hudRunnable = object : Runnable {
        override fun run() { tickHud(); hudHandler.postDelayed(this, 1000) }
    }
    private val permissionCode = 100

    // -- Service binding -----------------------------------------------------
    private var service: StreamingService? = null
    private var viewAttached = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as? StreamingService.LocalBinder ?: return
            service = b.getService()
            attachViewIfReady()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            viewAttached = false
        }
    }

    // -- Lifecycle -----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(prefFile, Context.MODE_PRIVATE)
        bindViews()
        setupBottomActions()
        setupSettingsPanel()
        if (checkPermissions()) startAndBindService() else requestPermissions()
        hudHandler.post(hudRunnable)
    }

    override fun onResume() {
        super.onResume()
        refreshStatusBar()
        if (service == null) bindToService()
    }

    override fun onStop() {
        super.onStop()
        service?.detachView()
        viewAttached = false
        try { unbindService(serviceConnection) } catch (_: Exception) {}
        service = null
    }

    override fun onDestroy() {
        super.onDestroy()
        hudHandler.removeCallbacksAndMessages(null)
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig) }

    // -- Service binding helpers ---------------------------------------------

    private fun startAndBindService() {
        val url = prefs.getString(keyRtmpUrl, defaultUrl) ?: defaultUrl
        val intent = Intent(this, StreamingService::class.java)
            .putExtra(StreamingService.EXTRA_RTMP_URL, url)
        startForegroundService(intent)
        bindToService()
    }

    private fun bindToService() {
        val intent = Intent(this, StreamingService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun attachViewIfReady() {
        if (viewAttached) return
        val svc = service ?: return
        if (!cameraPreview.holder.surface.isValid) {
            cameraPreview.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    cameraPreview.holder.removeCallback(this)
                    if (!viewAttached) { svc.attachView(cameraPreview); viewAttached = true }
                }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) { viewAttached = false }
            })
        } else {
            svc.attachView(cameraPreview)
            viewAttached = true
        }
    }

    // -- Bind views ----------------------------------------------------------

    private fun bindViews() {
        cameraPreview  = findViewById(R.id.cameraPreview)
        topBar         = findViewById(R.id.topBar)
        statusText     = findViewById(R.id.statusText)
        rtspBadge      = findViewById(R.id.rtspBadge)
        clientsBadge   = findViewById(R.id.clientsBadge)
        batteryText    = findViewById(R.id.batteryText)
        bottomActions  = findViewById(R.id.bottomActions)
        settingsPanel  = findViewById(R.id.settingsPanel)
        btnShutter     = findViewById(R.id.btnShutter)
        btnSettings    = findViewById(R.id.btnSettings)
        btnClosePanel  = settingsPanel.findViewById(R.id.btnClosePanel)
        editRtmpUrl    = settingsPanel.findViewById(R.id.editRtmpUrl)
        btnApplyRtmpUrl = settingsPanel.findViewById(R.id.btnApplyRtmpUrl)

        val savedUrl = prefs.getString(keyRtmpUrl, defaultUrl) ?: defaultUrl
        editRtmpUrl.setText(savedUrl)
    }

    // -- Bottom actions ------------------------------------------------------

    private fun setupBottomActions() {
        btnShutter.setOnClickListener {
            // Fix 1: lê estado real do service antes de agir
            if (isStreaming) {
                service?.stopStream()
            } else {
                service?.startStream()
            }
            // Visual atualizado imediatamente; tickHud sincroniza em até 1s
            btnShutter.setBackgroundResource(
                if (!isStreaming) R.drawable.bg_shutter_active else R.drawable.bg_shutter_inner
            )
            showToast(if (!isStreaming) "Transmitindo..." else "Stream parado")
        }
        btnSettings.setOnClickListener { togglePanel() }
        btnClosePanel.setOnClickListener { closePanel() }
    }

    // -- Settings panel ------------------------------------------------------

    private fun setupSettingsPanel() {
        btnApplyRtmpUrl.setOnClickListener {
            val url = editRtmpUrl.text.toString().trim()
            if (url.isEmpty() || !url.startsWith("rtmp://")) {
                showToast("URL invalida. Use rtmp://IP:1935/live/stream")
                return@setOnClickListener
            }
            prefs.edit().putString(keyRtmpUrl, url).apply()
            service?.let { svc ->
                svc.rtmpUrl = url
                svc.stopStream()
                svc.startStream()
                showToast("Reconectando para $url")
            } ?: showToast("URL salva. Sera usada no proximo inicio.")
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(editRtmpUrl.windowToken, 0)
            statusText.text = url
            closePanel()
        }
    }

    private fun togglePanel() { if (isPanelOpen) closePanel() else openPanel() }

    private fun openPanel() {
        isPanelOpen = true
        val url = prefs.getString(keyRtmpUrl, defaultUrl) ?: defaultUrl
        editRtmpUrl.setText(url)
        ObjectAnimator.ofFloat(settingsPanel, "translationX", 0f).apply {
            duration = 280; interpolator = DecelerateInterpolator(); start()
        }
    }

    private fun closePanel() {
        isPanelOpen = false
        val w = settingsPanel.width.toFloat().takeIf { it > 0f } ?: (280f * resources.displayMetrics.density)
        ObjectAnimator.ofFloat(settingsPanel, "translationX", w).apply {
            duration = 280; interpolator = DecelerateInterpolator(); start()
        }
    }

    // -- HUD ticker ----------------------------------------------------------

    private fun tickHud() {
        val streaming = isStreaming

        // Fix badge: LIVE = verde, OFF = vermelho
        rtspBadge.text = if (streaming) "LIVE" else "OFF"
        rtspBadge.setBackgroundResource(
            if (streaming) R.drawable.bg_badge_green else R.drawable.bg_badge_red
        )
        // Sincroniza botão shutter com estado real
        btnShutter.setBackgroundResource(
            if (streaming) R.drawable.bg_shutter_active else R.drawable.bg_shutter_inner
        )

        // Fix 2: clientsBadge — browsers com painel aberto
        val clients = service?.httpServer?.let {
            if (it.isAlive) it.connectedClients else 0
        } ?: 0
        clientsBadge.text = if (clients > 0) "\uD83D\uDDA5 $clients" else "\uD83D\uDDA5 0"
        clientsBadge.alpha = if (clients > 0) 1f else 0.4f

        // Bateria
        val bat = getBattery()
        batteryText.text = "$bat%"
        batteryText.setTextColor(when {
            bat > 50 -> 0xFF22c55e.toInt()
            bat > 20 -> 0xFFfbbf24.toInt()
            else     -> 0xFFef4444.toInt()
        })
    }

    // -- Helpers -------------------------------------------------------------

    private fun refreshStatusBar() {
        val url = prefs.getString(keyRtmpUrl, defaultUrl) ?: defaultUrl
        statusText.text = url
    }

    private fun getBattery(): Int =
        (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun showToast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // -- Permissões ----------------------------------------------------------

    private fun checkPermissions() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val p = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            p.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, p.toTypedArray(), permissionCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionCode &&
            grantResults.size >= 2 &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED &&
            grantResults[1] == PackageManager.PERMISSION_GRANTED)
            startAndBindService()
    }
}
