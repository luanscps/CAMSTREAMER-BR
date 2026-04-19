package br.camui.telemetry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HeartbeatLoop(
    private val scope: CoroutineScope,
    private val heartbeatService: HeartbeatService,
    private val intervalMs: Long = 30_000L,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { heartbeatService.sendHeartbeat() }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
