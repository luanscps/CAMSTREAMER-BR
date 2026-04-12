package com.camera2rtsp

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * WebControlServer
 * Responsável exclusivamente pelo roteamento HTTP na porta 8080.
 *
 * Delega:
 *   - Lógica de negócio JSON  → WebControlApi
 *   - Geração do painel HTML  → WebControlHtml
 *
 * Rastreamento de clientes:
 *   O painel web faz GET /api/heartbeat a cada 5s.
 *   Cada cliente recebe um clientId único (query param ?cid=...).
 *   Se um clientId não fizer heartbeat por mais de 15s, é considerado desconectado.
 */
class WebControlServer(
    port: Int,
    private val cameraController: Camera2Controller,
    private val context: Context
) : NanoHTTPD(port) {

    private val tag = "WebControlServer"

    /** Map de clientId → timestamp do último heartbeat (ms). */
    private val clientHeartbeats = ConcurrentHashMap<String, AtomicLong>()

    /** Timeout: cliente sem heartbeat por 15s é removido. */
    private val clientTimeoutMs = 15_000L

    /** Número de clientes ativos com o painel web aberto. */
    val connectedClients: Int
        get() {
            val now = System.currentTimeMillis()
            clientHeartbeats.entries.removeIf { (_, ts) ->
                now - ts.get() > clientTimeoutMs
            }
            return clientHeartbeats.size
        }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return when {
            uri == "/"                    -> serveControlPanel()
            uri == "/status"              -> WebControlApi.serveStatus(cameraController, this)
            uri == "/api/status"          -> WebControlApi.serveStatus(cameraController, this)
            uri == "/api/capabilities"    -> WebControlApi.serveCapabilities(cameraController, context)
            uri == "/api/heartbeat"       -> handleHeartbeat(session)
            uri == "/api/control" && session.method == Method.POST
                                          -> WebControlApi.handleControl(session, cameraController)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }

    /**
     * GET /api/heartbeat?cid=<uuid>
     * Registra ou renova o timestamp do cliente.
     * O painel web chama isso a cada 5s.
     */
    private fun handleHeartbeat(session: IHTTPSession): Response {
        val params = session.parameters
        val cid = params["cid"]?.firstOrNull() ?: run {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                """{"error":"cid required"}"""
            )
        }
        clientHeartbeats.getOrPut(cid) { AtomicLong(0L) }.set(System.currentTimeMillis())
        Log.v(tag, "heartbeat cid=$cid clients=${connectedClients}")
        val resp = newFixedLengthResponse(
            Response.Status.OK, "application/json",
            """{"clients":${connectedClients}}"""
        )
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }

    private fun serveControlPanel(): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html", WebControlHtml.build())
}
