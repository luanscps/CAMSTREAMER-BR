package com.camera2rtsp.auth

import com.google.gson.annotations.SerializedName

/** Resposta completa de GET /api/license/validate */
data class LicenseValidateResponse(
    @SerializedName("ok")         val ok: Boolean,
    @SerializedName("plan")       val plan: String?        = null,
    @SerializedName("expires_at") val expiresAt: String?   = null,
    @SerializedName("device_id")  val deviceId: String?    = null,
    @SerializedName("features")   val features: PlanFeatures? = null,
    @SerializedName("error")      val error: String?       = null
)
