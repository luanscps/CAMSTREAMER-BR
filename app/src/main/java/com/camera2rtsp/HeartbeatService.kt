package br.camui.telemetry

import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceHeartbeatArgs(
    @SerialName("p_sub_license_key") val subLicenseKey: String,
    @SerialName("p_streaming_now") val streamingNow: Boolean = false,
    @SerialName("p_rtmp_url") val rtmpUrl: String? = null,
    @SerialName("p_bitrate_kbps") val bitrateKbps: Int? = null,
    @SerialName("p_battery_level") val batteryLevel: Short? = null,
    @SerialName("p_is_charging") val isCharging: Boolean? = null,
    @SerialName("p_thermal_state") val thermalState: String? = null,
    @SerialName("p_network_type") val networkType: String? = null,
    @SerialName("p_network_strength") val networkStrength: Short? = null,
    @SerialName("p_stream_error") val streamError: String? = null,
)

@Serializable
data class DeviceHeartbeatResult(
    val ok: Boolean,
    val reason: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
)

interface DeviceRuntimeInfoProvider {
    fun currentBitrateKbps(): Int?
    fun currentRtmpUrl(): String?
    fun isStreamingNow(): Boolean
    fun batteryLevelPercent(): Short?
    fun isCharging(): Boolean?
    fun thermalState(): String?
    fun networkType(): String?
    fun networkStrength(): Short?
}

class HeartbeatService(
    private val supabase: SupabaseClient,
    private val provider: DeviceRuntimeInfoProvider,
    private val subLicenseKeyProvider: () -> String,
) {
    suspend fun sendHeartbeat(lastStreamError: String? = null): DeviceHeartbeatResult = withContext(Dispatchers.IO) {
        val payload = DeviceHeartbeatArgs(
            subLicenseKey = subLicenseKeyProvider(),
            streamingNow = provider.isStreamingNow(),
            rtmpUrl = provider.currentRtmpUrl(),
            bitrateKbps = provider.currentBitrateKbps(),
            batteryLevel = provider.batteryLevelPercent(),
            isCharging = provider.isCharging(),
            thermalState = provider.thermalState(),
            networkType = provider.networkType(),
            networkStrength = provider.networkStrength(),
            streamError = lastStreamError,
        )

        supabase.postgrest.rpc("device_heartbeat", payload).decodeAs<DeviceHeartbeatResult>()
    }
}
