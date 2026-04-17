package com.camera2rtsp.auth

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class AuthResult<out T> {
    data class Success<T>(val data: T) : AuthResult<T>()
    data class Error(val message: String) : AuthResult<Nothing>()
}

/**
 * LicenseRepository — camada de abstração entre Activities e a API.
 * Todas as funções são suspend e devem ser chamadas em coroutine (Dispatchers.IO).
 */
object LicenseRepository {

    // ── Login ─────────────────────────────────────────────────────────────────

    suspend fun login(email: String, password: String, context: Context): AuthResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val resp = ApiClient.supabaseAuth.login(
                    apiKey = ApiClient.SUPABASE_ANON_KEY,
                    body   = SupabaseLoginRequest(email, password)
                )
                if (resp.isSuccessful) {
                    val token = resp.body()?.accessToken
                        ?: return@withContext AuthResult.Error("Token não recebido")
                    activate(token, context)
                } else {
                    val errBody = resp.errorBody()?.string() ?: ""
                    AuthResult.Error(parseSupabaseError(errBody))
                }
            } catch (e: Exception) {
                AuthResult.Error("Erro de conexão: ${e.message}")
            }
        }

    // ── Cadastro ──────────────────────────────────────────────────────────────

    suspend fun register(email: String, password: String, context: Context): AuthResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val resp = ApiClient.supabaseAuth.signUp(
                    apiKey = ApiClient.SUPABASE_ANON_KEY,
                    body   = SupabaseSignUpRequest(email, password)
                )
                if (resp.isSuccessful) {
                    val token = resp.body()?.accessToken
                        ?: return@withContext AuthResult.Error("Confirme seu e-mail para ativar a conta.")
                    activate(token, context)
                } else {
                    val errBody = resp.errorBody()?.string() ?: ""
                    AuthResult.Error(parseSupabaseError(errBody))
                }
            } catch (e: Exception) {
                AuthResult.Error("Erro de conexão: ${e.message}")
            }
        }

    // ── Ativar device (POST /api/activate) ────────────────────────────────────

    private suspend fun activate(jwtToken: String, context: Context): AuthResult<String> {
        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        )
        val body = ActivateRequest(
            androidId      = androidId,
            deviceName     = "${Build.MANUFACTURER} ${Build.MODEL}",
            deviceBrand    = Build.MANUFACTURER,
            deviceModel    = Build.MODEL,
            deviceHardware = Build.HARDWARE,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt         = Build.VERSION.SDK_INT,
            appVersion     = context.packageManager
                                 .getPackageInfo(context.packageName, 0).versionName
        )
        val resp = ApiClient.licenseApi.activate(
            bearerToken = "Bearer $jwtToken",
            body        = body
        )
        return if (resp.isSuccessful && resp.body()?.ok == true) {
            val activateResp = resp.body()!!
            SessionManager.saveSubLicenseKey(activateResp.subLicenseKey!!)
            SessionManager.setFeaturesFromActivate(activateResp)
            AuthResult.Success(activateResp.subLicenseKey)
        } else {
            val err = resp.body()?.error ?: resp.errorBody()?.string() ?: "Erro ao ativar device"
            AuthResult.Error(err)
        }
    }

    // ── Validar licença (GET /api/license/validate) ───────────────────────────

    suspend fun validateLicense(): AuthResult<LicenseValidateResponse> =
        withContext(Dispatchers.IO) {
            try {
                val key = SessionManager.getSubLicenseKey()
                    ?: return@withContext AuthResult.Error("Sem licença salva")
                val resp = ApiClient.licenseApi.validate("Bearer $key")
                if (resp.isSuccessful && resp.body()?.ok == true) {
                    SessionManager.setFeaturesFromResponse(resp.body()!!)
                    AuthResult.Success(resp.body()!!)
                } else {
                    val err = resp.body()?.error ?: "Licença inválida ou expirada"
                    AuthResult.Error(err)
                }
            } catch (e: Exception) {
                AuthResult.Error("Erro de conexão: ${e.message}")
            }
        }

    // ── Helper ────────────────────────────────────────────────────────────────

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
