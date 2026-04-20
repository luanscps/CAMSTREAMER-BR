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
    private val provider: DeviceRuntimeInfoProvider,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { heartbeatService.sendHeartbeat() }
                delay(adaptiveInterval())
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    // Intervalo adaptativo baseado em estado atual do device
    // streaming + bateria critica sem carga: 60s (economiza bateria)
    // streaming normal:                      10s (atualizacao frequente)
    // carregando sem streaming:              30s (balanceado)
    // idle (sem stream, sem carga):          60s (minimo consumo)
    private fun adaptiveInterval(): Long {
        val streaming   = provider.isStreamingNow()
        val battery     = provider.batteryLevelPercent()?.toInt() ?: 100
        val charging    = provider.isCharging() ?: false

        return when {
            streaming && !charging && battery < 20 -> 60_000L
            streaming                              -> 10_000L
            charging                               -> 30_000L
            else                                   -> 60_000L
        }
    }
}
