package com.camera2rtsp.auth

import com.google.gson.annotations.SerializedName

/** Resposta de POST /api/activate */
data class ActivateResponse(
    @SerializedName("ok")              val ok: Boolean,
    @SerializedName("plan")            val plan: String?          = null,
    @SerializedName("max_devices")     val maxDevices: Int?       = null,
    @SerializedName("account_number")  val accountNumber: String? = null,
    @SerializedName("device_id")       val deviceId: String?      = null,
    @SerializedName("sub_license_key") val subLicenseKey: String? = null,
    @SerializedName("status")          val status: String?        = null,
    @SerializedName("error")           val error: String?         = null
)
