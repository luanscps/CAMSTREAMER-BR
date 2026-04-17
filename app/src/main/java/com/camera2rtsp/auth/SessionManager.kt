package com.camera2rtsp.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * SessionManager — Singleton central de autenticação.
 *
 * Responsabilidades:
 *  - Persistir sub_license_key em EncryptedSharedPreferences
 *  - Armazenar PlanFeatures em memória após validação
 *  - Gerar e validar webSessionToken (para o NanoHTTPD na porta 8080)
 *  - Gerenciar senha local da WebGUI
 *
 * Uso:
 *   SessionManager.init(context)           // no Application.onCreate()
 *   SessionManager.isLoggedIn()            // verifica se tem chave salva
 *   SessionManager.features                // PlanFeatures atual
 *   SessionManager.isValidWebSession(tok)  // middleware do NanoHTTPD
 */
object SessionManager {

    // ── Chaves de preferências ──────────────────────────────────────────────
    private const val PREFS_FILE          = "camstreamer_secure_prefs"
    private const val KEY_SUB_LICENSE     = "sub_license_key"
    private const val KEY_WEB_PASSWORD    = "web_gui_password"
    private const val KEY_WEB_SESSION_TOK = "web_session_token"
    private const val KEY_WEB_SESSION_EXP = "web_session_expires"
    private const val KEY_PLAN            = "plan_name"

    /** TTL do webSessionToken em milissegundos (8 horas) */
    private const val WEB_SESSION_TTL_MS = 8 * 60 * 60 * 1000L

    // ── Estado em memória ───────────────────────────────────────────────────
    @Volatile var features: PlanFeatures = PlanFeatures.basic()
        private set

    @Volatile var plan: String = "BASIC"
        private set

    private var prefs: androidx.security.crypto.EncryptedSharedPreferences? = null

    // ── Inicialização ───────────────────────────────────────────────────────

    /**
     * Deve ser chamado no Application.onCreate() antes de qualquer uso.
     */
    fun init(context: Context) {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        @Suppress("UNCHECKED_CAST")
        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as androidx.security.crypto.EncryptedSharedPreferences

        // Restaurar plano salvo localmente
        plan = prefs!!.getString(KEY_PLAN, "BASIC") ?: "BASIC"
        if (plan == "PRO") features = PlanFeatures.pro()
    }

    // ── sub_license_key ─────────────────────────────────────────────────────

    fun saveSubLicenseKey(key: String) {
        requirePrefs().edit().putString(KEY_SUB_LICENSE, key).apply()
    }

    fun getSubLicenseKey(): String? =
        requirePrefs().getString(KEY_SUB_LICENSE, null)

    fun isLoggedIn(): Boolean =
        !getSubLicenseKey().isNullOrBlank()

    fun logout() {
        requirePrefs().edit()
            .remove(KEY_SUB_LICENSE)
            .remove(KEY_WEB_SESSION_TOK)
            .remove(KEY_WEB_SESSION_EXP)
            .remove(KEY_PLAN)
            .apply()
        features = PlanFeatures.basic()
        plan = "BASIC"
    }

    // ── Atualizar features após validação ───────────────────────────────────

    fun setFeaturesFromResponse(response: LicenseValidateResponse) {
        features = response.features ?: PlanFeatures.basic()
        plan = response.plan ?: "BASIC"
        requirePrefs().edit().putString(KEY_PLAN, plan).apply()
    }

    fun setFeaturesFromActivate(response: ActivateResponse) {
        plan = response.plan ?: "BASIC"
        features = if (plan == "PRO") PlanFeatures.pro() else PlanFeatures.basic()
        requirePrefs().edit().putString(KEY_PLAN, plan).apply()
    }

    // ── WebGUI — Senha local ────────────────────────────────────────────────

    /**
     * Define a senha local da WebGUI (definida pelo usuário na MainActivity).
     * Não tem relação com a senha do Supabase.
     */
    fun setWebPassword(password: String) {
        requirePrefs().edit().putString(KEY_WEB_PASSWORD, password).apply()
    }

    fun hasWebPassword(): Boolean =
        !requirePrefs().getString(KEY_WEB_PASSWORD, null).isNullOrBlank()

    /**
     * Verifica a senha local da WebGUI.
     * Retorna true se a senha bater OU se nenhuma senha foi cadastrada ainda.
     */
    fun checkWebPassword(input: String): Boolean {
        val saved = requirePrefs().getString(KEY_WEB_PASSWORD, null)
        if (saved.isNullOrBlank()) return false   // força cadastro de senha
        return saved == input
    }

    // ── WebGUI — Session Token (cookie CAMSESSION) ──────────────────────────

    /**
     * Cria um novo webSessionToken com TTL de 8h e o persiste.
     * @return o token gerado (deve ser enviado como cookie Set-Cookie)
     */
    fun createWebSession(): String {
        val token   = UUID.randomUUID().toString()
        val expires = System.currentTimeMillis() + WEB_SESSION_TTL_MS
        requirePrefs().edit()
            .putString(KEY_WEB_SESSION_TOK, token)
            .putLong(KEY_WEB_SESSION_EXP, expires)
            .apply()
        return token
    }

    /**
     * Valida um token recebido no header/cookie da requisição NanoHTTPD.
     * @return true se o token é válido e não expirou
     */
    fun isValidWebSession(token: String): Boolean {
        if (token.isBlank()) return false
        val saved   = requirePrefs().getString(KEY_WEB_SESSION_TOK, null) ?: return false
        val expires = requirePrefs().getLong(KEY_WEB_SESSION_EXP, 0L)
        return token == saved && System.currentTimeMillis() < expires
    }

    /** Invalida o webSessionToken (ex: após logout ou licença expirada). */
    fun invalidateWebSession() {
        requirePrefs().edit()
            .remove(KEY_WEB_SESSION_TOK)
            .remove(KEY_WEB_SESSION_EXP)
            .apply()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun requirePrefs(): android.content.SharedPreferences {
        return checkNotNull(prefs) {
            "SessionManager não foi inicializado. Chame SessionManager.init(context) no Application.onCreate()."
        }
    }
}
