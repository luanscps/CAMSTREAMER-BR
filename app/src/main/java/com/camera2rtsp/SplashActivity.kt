package com.camera2rtsp

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.camera2rtsp.auth.AuthResult
import com.camera2rtsp.auth.LicenseRepository
import com.camera2rtsp.auth.SessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * SplashActivity — Gateway de entrada do app.
 *
 * Fluxo:
 *  1. Se NÃO tem sub_license_key → vai para LoginActivity
 *  2. Se TEM sub_license_key → chama GET /api/license/validate
 *     - ok: true  → vai para MainActivity
 *     - ok: false → limpa sessão e vai para LoginActivity
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        lifecycleScope.launch {
            delay(1200) // tempo mínimo de splash para exibir logo

            if (!SessionManager.isLoggedIn()) {
                goToLogin()
                return@launch
            }

            when (val result = LicenseRepository.validateLicense()) {
                is AuthResult.Success -> goToMain()
                is AuthResult.Error   -> {
                    Log.w("SplashActivity", "Licença inválida: ${result.message}")
                    SessionManager.logout()
                    goToLogin()
                }
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun goToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
