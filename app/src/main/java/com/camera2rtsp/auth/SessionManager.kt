package com.camera2rtsp.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

object SessionManager {

    private const val PREFS_FILE          = "camstreamer_secure_prefs"
    private const val KEY_SUB_LICENSE     = "sub_license_key"
    private const val KEY_WEB_PASSWORD    = "web_gui_password"
    private const val KEY_WEB_SESSION_TOK = "web_session_token"
    private const val KEY_WEB_SESSION_EXP = "web_session_expires"
    private const val KEY_PLAN            = "plan_name"
    private const val KEY_APP_VERSION     = "app_version"
    private const val KEY_SUPABASE_TOKEN  = "supabase_token"

    private const val WEB_SESSION_TTL_MS = 8 * 60 * 60 * 1000L

    @Volatile var features: PlanFeatures = PlanFeatures.basic()
        private set

    @Volatile var plan: String = "BASIC"
        private set

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        plan = prefs!!.getString(KEY_PLAN, "BASIC") ?: "BASIC"
        if (plan == "PRO") features = PlanFeatures.pro()
    }

    // ── sub_license_key ──────────────────────────────────────────────────────

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
            .remove(KEY_APP_VERSION)
            .remove(KEY_SUPABASE_TOKEN)
            .apply()
        features = PlanFeatures.basic()
        plan = "BASIC"
    }

    // ── Features ─────────────────────────────────────────────────────────────

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

    // ── App Version ──────────────────────────────────────────────────────────

    fun saveAppVersion(version: String) {
        requirePrefs().edit().putString(KEY_APP_VERSION, version).apply()
    }

    fun getAppVersion(): String? =
        requirePrefs().getString(KEY_APP_VERSION, null)

    // ── Supabase Token ───────────────────────────────────────────────────────

    fun saveSupabaseToken(token: String) {
        requirePrefs().edit().putString(KEY_SUPABASE_TOKEN, token).apply()
    }

    fun getSupabaseToken(): String? =
        requirePrefs().getString(KEY_SUPABASE_TOKEN, null)

    // ── WebGUI — Senha local ─────────────────────────────────────────────────

    fun setWebPassword(password: String) {
        requirePrefs().edit().putString(KEY_WEB_PASSWORD, password).apply()
    }

    fun hasWebPassword(): Boolean =
        !requirePrefs().getString(KEY_WEB_PASSWORD, null).isNullOrBlank()

    fun checkWebPassword(input: String): Boolean {
        val saved = requirePrefs().getString(KEY_WEB_PASSWORD, null)
        if (saved.isNullOrBlank()) return false
        return saved == input
    }

    // ── WebGUI — Session Token ───────────────────────────────────────────────

    fun createWebSession(): String {
        val token   = UUID.randomUUID().toString()
        val expires = System.currentTimeMillis() + WEB_SESSION_TTL_MS
        requirePrefs().edit()
            .putString(KEY_WEB_SESSION_TOK, token)
            .putLong(KEY_WEB_SESSION_EXP, expires)
            .apply()
        return token
    }

    fun isValidWebSession(token: String): Boolean {
        if (token.isBlank()) return false
        val saved   = requirePrefs().getString(KEY_WEB_SESSION_TOK, null) ?: return false
        val expires = requirePrefs().getLong(KEY_WEB_SESSION_EXP, 0L)
        return token == saved && System.currentTimeMillis() < expires
    }

    fun invalidateWebSession() {
        requirePrefs().edit()
            .remove(KEY_WEB_SESSION_TOK)
            .remove(KEY_WEB_SESSION_EXP)
            .apply()
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun requirePrefs(): SharedPreferences =
        checkNotNull(prefs) {
            "SessionManager nao inicializado. Chame SessionManager.init(context) no Application.onCreate()."
        }
}
