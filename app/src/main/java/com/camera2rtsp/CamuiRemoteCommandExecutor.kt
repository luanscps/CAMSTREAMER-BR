package br.camui.remote

class CamuiRemoteCommandExecutor : RemoteCommandExecutor {
    override suspend fun execute(command: RemoteCommandRow): Map<String, String> {
        return when (command.command) {
            "start_stream" -> mapOf("ok" to "true", "action" to "start_stream")
            "stop_stream" -> mapOf("ok" to "true", "action" to "stop_stream")
            "set_bitrate" -> mapOf("ok" to "true", "action" to "set_bitrate", "value" to (command.payload?.get("bitrate") ?: ""))
            "set_resolution" -> mapOf("ok" to "true", "action" to "set_resolution", "value" to (command.payload?.get("resolution") ?: ""))
            "switch_camera" -> mapOf("ok" to "true", "action" to "switch_camera")
            "request_status" -> mapOf("ok" to "true", "action" to "request_status")
            else -> throw IllegalArgumentException("unsupported_command:${command.command}")
        }
    }
}
