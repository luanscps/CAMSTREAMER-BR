package com.camera2rtsp.auth

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.provider.Settings
import com.camera2rtsp.CameraCapabilitiesReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class AuthResult<out T> {
    data class Success<T>(val data: T) : AuthResult<T>()
    data class Error(val message: String) : AuthResult<Nothing>()
}

object LicenseRepository {

    // Login

    suspend fun login(email: String, password: String, context: Context): AuthResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val resp = ApiClient.supabaseAuth.login(
                    apiKey = ApiClient.SUPABASE_ANON_KEY,
                    body   = SupabaseLoginRequest(email, password)
                )
                if (resp.isSuccessful) {
                    val token = resp.body()?.accessToken
                        ?: return@withContext AuthResult.Error("Token nao recebido")
                    SessionManager.saveSupabaseToken(token)
                    activate(token, context)
                } else {
                    val errBody = resp.errorBody()?.string() ?: ""
                    AuthResult.Error(parseSupabaseError(errBody))
                }
            } catch (e: Exception) {
                AuthResult.Error("Erro de conexao: ${e.message}")
            }
        }

    // Cadastro
    // @param fullName nome completo enviado como user_metadata ao Supabase Auth

    suspend fun register(
        email: String,
        password: String,
        fullName: String,
        context: Context
    ): AuthResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val resp = ApiClient.supabaseAuth.signUp(
                    apiKey = ApiClient.SUPABASE_ANON_KEY,
                    body   = SupabaseSignUpRequest(
                        email    = email,
                        password = password,
                        data     = mapOf("full_name" to fullName)
                    )
                )
                if (resp.isSuccessful) {
                    val token = resp.body()?.accessToken
                        ?: return@withContext AuthResult.Error("Confirme seu e-mail para ativar a conta.")
                    SessionManager.saveSupabaseToken(token)
                    activate(token, context)
                } else {
                    val errBody = resp.errorBody()?.string() ?: ""
                    AuthResult.Error(parseSupabaseError(errBody))
                }
            } catch (e: Exception) {
                AuthResult.Error("Erro de conexao: ${e.message}")
            }
        }

    // Ativar device (POST /api/activate)

    suspend fun activate(jwtToken: String, context: Context): AuthResult<String> {
        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        val appVersion = context.packageManager
            .getPackageInfo(context.packageName, 0).versionName ?: "unknown"

        val cameras = readCameraInfoList(context)

        val body = ActivateRequest(
            androidId      = androidId,
            deviceName     = "${Build.MANUFACTURER} ${Build.MODEL}",
            deviceBrand    = Build.MANUFACTURER,
            deviceModel    = Build.MODEL,
            deviceHardware = Build.HARDWARE,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt         = Build.VERSION.SDK_INT,
            appVersion     = appVersion,
            cameras        = cameras
        )

        val resp = ApiClient.licenseApi.activate(
            bearerToken = "Bearer $jwtToken",
            body        = body
        )

        return if (resp.isSuccessful && resp.body()?.ok == true) {
            val activateResp = resp.body()!!
            val licenseKey = activateResp.subLicenseKey ?: ""
            SessionManager.saveSubLicenseKey(licenseKey)
            SessionManager.setFeaturesFromActivate(activateResp)
            SessionManager.saveAppVersion(appVersion)
            AuthResult.Success(licenseKey)
        } else {
            val err = resp.body()?.error ?: resp.errorBody()?.string() ?: "Erro ao ativar device"
            AuthResult.Error(err)
        }
    }

    // Re-activate automatico ao detectar nova versao do app

    suspend fun reactivateIfVersionChanged(context: Context): Boolean {
        val appVersion   = context.packageManager
            .getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        val savedVersion = SessionManager.getAppVersion()
        if (savedVersion == appVersion) return false

        val token = SessionManager.getSupabaseToken() ?: return false
        return try {
            withContext(Dispatchers.IO) {
                val result = activate(token, context)
                result is AuthResult.Success
            }
        } catch (_: Exception) { false }
    }

    // Validar licenca (GET /api/license/validate)

    suspend fun validateLicense(): AuthResult<LicenseValidateResponse> =
        withContext(Dispatchers.IO) {
            try {
                val key = SessionManager.getSubLicenseKey()
                    ?: return@withContext AuthResult.Error("Sem licenca salva")
                val resp = ApiClient.licenseApi.validate("Bearer $key")
                if (resp.isSuccessful && resp.body()?.ok == true) {
                    SessionManager.setFeaturesFromResponse(resp.body()!!)
                    AuthResult.Success(resp.body()!!)
                } else {
                    val err = resp.body()?.error ?: "Licenca invalida ou expirada"
                    AuthResult.Error(err)
                }
            } catch (e: Exception) {
                AuthResult.Error("Erro de conexao: ${e.message}")
            }
        }

    // Ler cameras via Camera2 API

    private fun readCameraInfoList(context: Context): List<CameraInfo> {
        return try {
            val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            mgr.cameraIdList.mapNotNull { cameraId ->
                val caps = CameraCapabilitiesReader.read(context, cameraId) ?: return@mapNotNull null
                val maxFps = caps.fpsRanges.mapNotNull { it.lastOrNull() }.maxOrNull() ?: 30
                CameraInfo(
                    id             = cameraId,
                    facing         = caps.facing,
                    label          = caps.name,
                    focalLengthsMm = caps.focalLengths?.map { it.toFloat() },
                    maxResolution  = caps.availableResolutions.firstOrNull(),
                    maxFps         = maxFps,
                    oisSupported   = caps.hasOis,
                    eisSupported   = false,
                    isoRange       = caps.isoRange?.map { it.toInt() },
                    hasRaw         = caps.supportsRaw,
                    hasHdr         = caps.supportedSceneModes.contains("hdr"),
                    zoomRatioMax   = caps.zoomRange?.lastOrNull(),
                    lensCount      = if (caps.supportsLogicalMultiCamera) 3 else 1,
                    hwLevel        = caps.hardwareLevel,
                    hasFlash       = caps.hasFlash,
                    apertures      = caps.apertures
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Helper

    private fun parseSupabaseError(body: String): String {
        return try {
            val json = com.google.gson.JsonParser.parseString(body).asJsonObject
            json.get("error_description")?.asString
                ?: json.get("msg")?.asString
                ?: json.get("error")?.asString
                ?: "Erro desconhecido"
        } catch (_: Exception) { "Erro desconhecido" }
    }
}
