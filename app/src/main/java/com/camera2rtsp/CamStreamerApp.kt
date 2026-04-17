package com.camera2rtsp

import android.app.Application
import com.camera2rtsp.auth.SessionManager

/**
 * Application class — ponto de entrada do processo.
 * Inicializa o SessionManager com o Context da aplicação.
 *
 * IMPORTANTE: Registrar no AndroidManifest.xml:
 *   <application
 *       android:name=".CamStreamerApp"
 *       ...>
 */
class CamStreamerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SessionManager.init(this)
    }
}
