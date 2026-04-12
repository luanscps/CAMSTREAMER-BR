package com.camera2rtsp

import android.content.Context
import fi.iki.elonen.NanoHTTPD

/**
 * WebControlServer
 * Responsável exclusivamente pelo roteamento HTTP na porta 8080.
 *
 * Delega:
 *   - Lógica de negócio JSON  → WebControlApi
 *   - Geração do painel HTML  → WebControlHtml
 */
class WebControlServer(
    port: Int,
    private val cameraController: Camera2Controller,
    private val context: Context
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return when {
            uri == "/"                 -> serveControlPanel()
            uri == "/status"           -> WebControlApi.serveStatus(cameraController)
            uri == "/api/status"       -> WebControlApi.serveStatus(cameraController)
            uri == "/api/capabilities" -> WebControlApi.serveCapabilities(cameraController, context)
            uri == "/api/control" && session.method == Method.POST
                                       -> WebControlApi.handleControl(session, cameraController)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }

    private fun serveControlPanel(): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html", WebControlHtml.build())
}
