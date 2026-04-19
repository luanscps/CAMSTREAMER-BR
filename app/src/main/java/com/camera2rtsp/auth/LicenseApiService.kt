package com.camera2rtsp.auth

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import com.google.gson.annotations.SerializedName

// ── Request bodies ────────────────────────────────────────────────────────────

data class SupabaseLoginRequest(
    @SerializedName("email")    val email: String,
    @SerializedName("password") val password: String
)

data class SupabaseSignUpRequest(
    @SerializedName("email")    val email: String,
    @SerializedName("password") val password: String,
    @SerializedName("data")     val data: Map<String, String> = emptyMap()
)

data class SupabaseAuthResponse(
    @SerializedName("access_token")  val accessToken: String?  = null,
    @SerializedName("refresh_token") val refreshToken: String? = null,
    @SerializedName("error")         val error: String?        = null,
    @SerializedName("error_description") val errorDescription: String? = null,
    @SerializedName("msg")           val msg: String?          = null
)

// ── Supabase Auth API (token endpoint) ───────────────────────────────────────

interface SupabaseAuthService {
    @POST("auth/v1/token?grant_type=password")
    suspend fun login(
        @Header("apikey")        apiKey: String,
        @Header("Content-Type")  contentType: String = "application/json",
        @Body body: SupabaseLoginRequest
    ): Response<SupabaseAuthResponse>

    @POST("auth/v1/signup")
    suspend fun signUp(
        @Header("apikey")        apiKey: String,
        @Header("Content-Type")  contentType: String = "application/json",
        @Body body: SupabaseSignUpRequest
    ): Response<SupabaseAuthResponse>
}

// ── camui-panel API ───────────────────────────────────────────────────────────

interface LicenseApiService {

    /** POST /api/activate — registra o device e obtém sub_license_key */
    @POST("api/activate")
    suspend fun activate(
        @Header("Authorization") bearerToken: String,
        @Body body: ActivateRequest
    ): Response<ActivateResponse>

    /** GET /api/license/validate — valida sub_license_key e retorna features */
    @GET("api/license/validate")
    suspend fun validate(
        @Header("Authorization") bearerSubKey: String
    ): Response<LicenseValidateResponse>
}

// ── Factory singleton ─────────────────────────────────────────────────────────

object ApiClient {

    const val PANEL_BASE_URL    = "https://camui-panel.vercel.app/"
    const val SUPABASE_URL      = "https://rqjxvzzfgoagcsxihdwe.supabase.co"
    const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InJxanh2enpmZ29hZ2NzeGloZHdlIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzMzMjQ0MDAsImV4cCI6MjA4ODkwMDQwMH0.khbppeoBGg1DPaGveTpT7hAetp-uAhAXigqE4LpQrGg"

    val licenseApi: LicenseApiService by lazy {
        Retrofit.Builder()
            .baseUrl(PANEL_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LicenseApiService::class.java)
    }

    val supabaseAuth: SupabaseAuthService by lazy {
        Retrofit.Builder()
            .baseUrl(SUPABASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SupabaseAuthService::class.java)
    }
}
