package com.camera2rtsp.auth

import com.google.gson.annotations.SerializedName

data class CameraInfo(
    @SerializedName("id")               val id: String,
    @SerializedName("facing")           val facing: String,
    @SerializedName("label")            val label: String?             = null,
    @SerializedName("focal_lengths_mm") val focalLengthsMm: List<Float>? = null,
    @SerializedName("max_resolution")   val maxResolution: String?     = null,
    @SerializedName("max_fps")          val maxFps: Int?               = null,
    @SerializedName("ois_supported")    val oisSupported: Boolean?     = null,
    @SerializedName("eis_supported")    val eisSupported: Boolean?     = null,
    @SerializedName("iso_range")        val isoRange: List<Int>?       = null,
    @SerializedName("has_raw")          val hasRaw: Boolean?           = null,
    @SerializedName("has_hdr")          val hasHdr: Boolean?           = null,
    @SerializedName("zoom_ratio_max")   val zoomRatioMax: Float?       = null,
    @SerializedName("lens_count")       val lensCount: Int?            = null,
    @SerializedName("hw_level")         val hwLevel: String?           = null,
    @SerializedName("has_flash")        val hasFlash: Boolean?         = null,
    @SerializedName("apertures")        val apertures: List<Float>?    = null
)

data class ActivateRequest(
    @SerializedName("android_id")       val androidId: String,
    @SerializedName("device_name")      val deviceName: String?        = null,
    @SerializedName("device_brand")     val deviceBrand: String?       = null,
    @SerializedName("device_model")     val deviceModel: String?       = null,
    @SerializedName("device_hardware")  val deviceHardware: String?    = null,
    @SerializedName("android_version")  val androidVersion: String?    = null,
    @SerializedName("sdk_int")          val sdkInt: Int?               = null,
    @SerializedName("app_version")      val appVersion: String?        = null,
    @SerializedName("cameras")          val cameras: List<CameraInfo>  = emptyList()
)
