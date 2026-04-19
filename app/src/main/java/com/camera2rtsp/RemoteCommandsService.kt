package br.camui.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

class RemoteCommandsService(
    private val supabase: SupabaseClient,
    private val activationIdProvider: () -> String,
    private val executor: RemoteCommandExecutor,
) {
    suspend fun pollAndExecuteForever(intervalMs: Long = 3_000L) = withContext(Dispatchers.IO) {
        while (true) {
            runCatching { pollOnce() }
            delay(intervalMs)
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
                order("issued_at")
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
        ) {
            filter { eq("id", id) }
        }
    }

    private suspend fun markExecuted(id: String, result: Map<String, String>) {
        supabase.postgrest["remote_commands"].update(
            {
                set("status", "executed")
                set("executed_at", "now()")
                set("result", result)
            }
        ) {
            filter { eq("id", id) }
        }
    }

    private suspend fun markFailed(id: String, errorMessage: String) {
        supabase.postgrest["remote_commands"].update(
            {
                set("status", "failed")
                set("executed_at", "now()")
                set("error_message", errorMessage)
            }
        ) {
            filter { eq("id", id) }
        }
    }
}
