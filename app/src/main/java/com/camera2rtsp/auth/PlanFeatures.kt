package com.camera2rtsp.auth

import com.google.gson.annotations.SerializedName

/**
 * Mapeia o objeto "features" retornado por GET /api/license/validate do camui-panel.
 *
 * Exemplo de payload:
 * {
 *   "ok": true,
 *   "plan": "PRO",
 *   "expires_at": "2026-12-31T00:00:00Z",
 *   "device_id": "uuid",
 *   "features": {
 *     "max_rtmp_outputs": 3,
 *     "max_resolution": 1080,
 *     "max_bitrate_kbps": 8000,
 *     "web_control": true,
 *     "local_recording": true,
 *     "max_stream_minutes": -1,
 *     "front_camera": true,
 *     "max_devices": 5
 *   }
 * }
 */
data class PlanFeatures(
    @SerializedName("max_rtmp_outputs")   val maxRtmpOutputs: Int     = 1,
    @SerializedName("max_resolution")     val maxResolution: Int      = 720,
    @SerializedName("max_bitrate_kbps")   val maxBitrateKbps: Int     = 2000,
    @SerializedName("web_control")        val webControl: Boolean     = false,
    @SerializedName("local_recording")    val localRecording: Boolean = false,
    @SerializedName("max_stream_minutes") val maxStreamMinutes: Int   = 60,
    @SerializedName("front_camera")       val frontCamera: Boolean    = false,
    @SerializedName("max_devices")        val maxDevices: Int         = 1
) {
    companion object {
        /** Features padrão para plano BASIC — usadas enquanto a validação ainda não retornou. */
        fun basic() = PlanFeatures()

        /** Features completas para plano PRO. */
        fun pro() = PlanFeatures(
            maxRtmpOutputs   = 5,
            maxResolution    = 1080,
            maxBitrateKbps   = 10000,
            webControl       = true,
            localRecording   = true,
            maxStreamMinutes = -1,   // -1 = ilimitado
            frontCamera      = true,
            maxDevices       = 5
        )
    }

    val isPro: Boolean
        get() = webControl && frontCamera && localRecording

    /** Verifica se uma resolução (altura em px) é permitida pelo plano. */
    fun allowsResolution(heightPx: Int): Boolean = heightPx <= maxResolution

    /** Verifica se um bitrate em kbps é permitido pelo plano. */
    fun allowsBitrate(kbps: Int): Boolean = maxBitrateKbps < 0 || kbps <= maxBitrateKbps

    /** Verifica se o número de outputs RTMP é permitido pelo plano. */
    fun allowsRtmpOutputs(count: Int): Boolean = maxRtmpOutputs < 0 || count <= maxRtmpOutputs
}
