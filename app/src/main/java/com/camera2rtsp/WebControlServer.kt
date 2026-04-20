package com.camera2rtsp

import android.content.Context
import com.camera2rtsp.auth.SessionManager
import fi.iki.elonen.NanoHTTPD

/*
 * WebControlServer (NanoHTTPD)
 *
 * Inicia ANTES do login Supabase - rotas publicas sao servidas sem cookie.
 * Apenas as rotas protegidas exigem sessao CAMSESSION valida.
 *
 * Rotas publicas (sem autenticacao):
 *   GET  /auth/login   -> serve login.html direto (NUNCA redirect para evitar loop)
 *   POST /auth/login   -> processa credenciais web
 *   GET  /auth/logout  -> limpa cookie, redireciona para /auth/login
 *   GET  /style.css    -> CSS publico
 *   GET  /app.js       -> JS publico
 *
 * Rotas protegidas (exigem cookie CAMSESSION):
 *   GET  /             -> index.html
 *   GET  /status       -> status JSON
 *   GET  /api/*        -> APIs JSON
 *   POST /api/control  -> controle camera
 *
 * Fix issue #5: / sem auth serve login.html DIRETO (sem redirect)
 * eliminando o loop ERR_TOO_MANY_REDIRECTS.
 */
class WebControlServer(
    port: Int,
    private val cameraController: Camera2Controller,
    private val context: Context
) : NanoHTTPD(port) {

    var connectedClients: Int = 0

    override fun serve(session: IHTTPSession): Response {
        val uri    = session.uri
        val method = session.method

        // Rotas publicas - sem verificacao de cookie

        // Assets CSS/JS sempre publicos (necessarios para renderizar login.html)
        if (uri == "/style.css") return serveAsset("style.css", "text/css")
        if (uri == "/app.js")    return serveAsset("app.js",    "application/javascript")

        // Login: GET serve HTML direto, POST processa credenciais
        if (uri == "/auth/login") {
            return when (method) {
                Method.GET -> {
                    val token = extractSessionCookie(session)
                    if (SessionManager.isValidWebSession(token)) redirectTo("/")
                    else serveAsset("login.html", "text/html") // NUNCA redirect aqui
                }
                Method.POST -> WebControlAuth.handleLogin(session)
                else        -> notFound()
            }
        }

        // Logout: limpa cookie e redireciona para login UMA VEZ
        if (uri == "/auth/logout") return WebControlAuth.handleLogout()

        // Middleware: verifica cookie para rotas protegidas

        val token = extractSessionCookie(session)
        val authenticated = SessionManager.isValidWebSession(token)

        // Raiz sem autenticacao: serve login.html DIRETO (fix issue #5)
        // Evita o loop: / -> redirect /auth/login -> / -> loop infinito
        if (uri == "/" && !authenticated) {
            return serveAsset("login.html", "text/html")
        }

        // APIs e rotas JSON sem auth: retorna 401 JSON (sem redirect)
        if (!authenticated) {
            return if (uri.startsWith("/api/") || uri == "/status") {
                unauthorizedJson()
            } else {
                // Qualquer outra rota desconhecida sem auth: serve login.html
                serveAsset("login.html", "text/html")
            }
        }

        // Rotas protegidas
        return when {
            uri == "/"                 -> serveAsset("index.html", "text/html")
            uri == "/status"           -> WebControlApi.serveStatus(cameraController, context)
            uri == "/api/status"       -> WebControlApi.serveStatus(cameraController, context)
            uri == "/api/capabilities" -> WebControlApi.serveCapabilities(cameraController, context)
            uri == "/api/plan"         -> WebControlAuth.servePlan()
            uri == "/api/control" && method == Method.POST
                                       -> WebControlApi.handleControl(session, cameraController)
            else -> notFound()
        }
    }

    // Helpers

    private fun extractSessionCookie(session: IHTTPSession): String {
        val header = session.headers["cookie"] ?: return ""
        return header.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("CAMSESSION=") }
            ?.removePrefix("CAMSESSION=") ?: ""
    }

    private fun serveAsset(filename: String, mimeType: String): Response =
        newFixedLengthResponse(
            Response.Status.OK, mimeType,
            WebControlHtml.serveAsset(context, filename)
        )

    private fun redirectTo(path: String, clearCookie: Boolean = false): Response {
        val resp = newFixedLengthResponse(
            Response.Status.REDIRECT, "text/plain", "Redirecionando..."
        )
        if (clearCookie) resp.addHeader("Set-Cookie", "CAMSESSION=; Path=/; Max-Age=0")
        resp.addHeader("Location", path)
        return resp
    }

    private fun unauthorizedJson(): Response {
        val resp = newFixedLengthResponse(
            Response.Status.UNAUTHORIZED, "application/json",
            """{"status":"error","message":"Nao autenticado. Acesse /auth/login"}"""
        )
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
}
