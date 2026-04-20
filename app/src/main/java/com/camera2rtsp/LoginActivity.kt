package com.camera2rtsp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.camera2rtsp.auth.AuthResult
import com.camera2rtsp.auth.LicenseRepository
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

class LoginActivity : AppCompatActivity() {

    private lateinit var editEmail: EditText
    private lateinit var editPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnGoRegister: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvWebPanelUrl: TextView
    private lateinit var btnCopyUrl: TextView

    companion object {
        private const val NANOHTTPD_PORT = 8080
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        editEmail     = findViewById(R.id.editEmail)
        editPassword  = findViewById(R.id.editPassword)
        btnLogin      = findViewById(R.id.btnLogin)
        btnGoRegister = findViewById(R.id.btnGoRegister)
        progressBar   = findViewById(R.id.progressBar)
        tvWebPanelUrl = findViewById(R.id.tvWebPanelUrl)
        btnCopyUrl    = findViewById(R.id.btnCopyUrl)

        btnLogin.setOnClickListener { doLogin() }

        btnGoRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        btnCopyUrl.setOnClickListener { copyUrlToClipboard() }

        updateWebPanelUrl()
    }

    override fun onResume() {
        super.onResume()
        // Atualiza IP ao voltar para a tela (ex: troca de rede)
        updateWebPanelUrl()
    }

    // ── IP + URL do painel ────────────────────────────────────────────────────

    private fun updateWebPanelUrl() {
        val ip = getLocalIpAddress()
        if (ip != null) {
            val url = "http://$ip:$NANOHTTPD_PORT"
            tvWebPanelUrl.text = url
            tvWebPanelUrl.setTextColor(getColor(android.R.color.holo_red_light))
            btnCopyUrl.visibility = View.VISIBLE
        } else {
            tvWebPanelUrl.text = "Wi-Fi desconectado"
            tvWebPanelUrl.setTextColor(0xFF888888.toInt())
            btnCopyUrl.visibility = View.GONE
        }
    }

    /**
     * Retorna o IPv4 da interface Wi-Fi ativa.
     * - Android < 12: usa WifiManager.connectionInfo (deprecated mas funcional)
     * - Android 12+: itera NetworkInterface para pegar o IP real
     * Retorna null se sem Wi-Fi ou IP indisponível.
     */
    private fun getLocalIpAddress(): String? {
        // Tenta via NetworkInterface (mais confiável em Android 12+)
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback: WifiManager (Android < 12)
        return try {
            @Suppress("DEPRECATION")
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo
            val ipInt = info.ipAddress
            if (ipInt == 0) return null
            String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        } catch (_: Exception) { null }
    }

    private fun copyUrlToClipboard() {
        val url = tvWebPanelUrl.text.toString()
        if (url.startsWith("http")) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Painel CamStreamer", url))
            Toast.makeText(this, "URL copiada!", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    private fun doLogin() {
        val email    = editEmail.text.toString().trim()
        val password = editPassword.text.toString()

        if (email.isEmpty() || password.isEmpty()) {
            toast("Preencha e-mail e senha")
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            when (val result = LicenseRepository.login(email, password, applicationContext)) {
                is AuthResult.Success -> {
                    setLoading(false)
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                }
                is AuthResult.Error -> {
                    setLoading(false)
                    toast(result.message)
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnLogin.isEnabled     = !loading
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
