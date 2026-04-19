package br.camui.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.min

@Serializable
data class RemoteCommandRow(
    val id: String,
    @SerialName("device_id") val deviceId: String,
    val command: String,
    val payload: Map<String, String>? = null,
    val status: String,
)

interface RemoteCommandExecutor {
    suspend fun execute(command: RemoteCommandRow): Map<String, String>
}

/**
 * Intervalos de polling:
 *   - Streaming ativo : INTERVAL_STREAMING_MS = 180s
 *   - App idle        : INTERVAL_IDLE_MS      = 360s
 *   - Erro consecutivo: backoff exponencial 2x até MAX_BACKOFF_MS = 600s
 */
class RemoteCommandsService(
    private val supabase: SupabaseClient,
    private val activationIdProvider: () -> String,
    private val executor: RemoteCommandExecutor,
    private val isStreamingNow: () -> Boolean = { false },
) {
    companion object {
        private const val INTERVAL_STREAMING_MS = 180_000L  // 3 min
        private const val INTERVAL_IDLE_MS      = 360_000L  // 6 min
        private const val MAX_BACKOFF_MS        = 600_000L  // 10 min
    }

    suspend fun pollAndExecuteForever() = withContext(Dispatchers.IO) {
        var consecutiveErrors = 0

        while (true) {
            val success = runCatching { pollOnce() }.isSuccess

            consecutiveErrors = if (success) 0 else consecutiveErrors + 1

            val baseInterval = if (isStreamingNow()) INTERVAL_STREAMING_MS else INTERVAL_IDLE_MS
            val interval = if (consecutiveErrors > 0) {
                // backoff exponencial: base * 2^erros, limitado a MAX_BACKOFF_MS
                min(baseInterval * (1L shl consecutiveErrors), MAX_BACKOFF_MS)
            } else {
                baseInterval
            }

            delay(interval)
        }
    }

    suspend fun pollOnce() {
        val deviceId = activationIdProvider()

        val pending = supabase.postgrest["remote_commands"]
            .select {
                filter {
                    eq("device_id", deviceId)
                    eq("status", "pending")
                }
                order("issued_at", Order.ASCENDING)
                limit(10)
            }
            .decodeList<RemoteCommandRow>()

        pending.forEach { command ->
            markDelivered(command.id)
            val result = runCatching { executor.execute(command) }
            result.onSuccess { markExecuted(command.id, it) }
            result.onFailure { markFailed(command.id, it.message ?: "unknown_error") }
        }
    }

    private suspend fun markDelivered(id: String) {
        supabase.postgrest["remote_commands"].update(
            {
                set("status", "delivered")
                set("delivered_at", "now()")
            }
        ) { filter { eq("id", id) } }
    }

    private suspend fun markExecuted(id: String, result: Map<String, String>) {
        supabase.postgrest["remote_commands"].update(
            {
                set("status", "executed")
                set("executed_at", "now()")
                set("result", result)
            }
        ) { filter { eq("id", id) } }
    }

    private suspend fun markFailed(id: String, errorMessage: String) {
        supabase.postgrest["remote_commands"].update(
            {
                set("status", "failed")
                set("executed_at", "now()")
                set("error_message", errorMessage)
            }
        ) { filter { eq("id", id) } }
    }
}
