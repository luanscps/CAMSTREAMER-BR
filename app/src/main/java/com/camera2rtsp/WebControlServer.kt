package com.camera2rtsp

import android.content.Context
import com.camera2rtsp.auth.SessionManager
import fi.iki.elonen.NanoHTTPD

/**
 * WebControlServer — v5-CAMUI + autenticação Supabase via cookie CAMSESSION
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

        // ── Rotas públicas ─────────────────────────────────────────────────
        if (uri == "/auth/login") {
            return when (method) {
                Method.GET -> {
                    // Se já tem sessão válida → vai direto pro painel
                    val token = extractSessionCookie(session)
                    if (SessionManager.isValidWebSession(token)) {
                        redirectTo("/")
                    } else {
                        serveAsset("login.html", "text/html")
                    }
                }
                Method.POST -> WebControlAuth.handleLogin(session)
                else        -> notFound()
            }
        }
        private fun redirectTo(path: String): Response {
            val resp = newFixedLengthResponse(
                Response.Status.REDIRECT, "text/plain", "Redirecionando..."
            )
            resp.addHeader("Location", path)
            return resp
        }
        if (uri == "/auth/logout") return WebControlAuth.handleLogout()

        // ── Middleware: verificar cookie CAMSESSION ────────────────────────
        val token = extractSessionCookie(session)
        if (!SessionManager.isValidWebSession(token)) {
            return if (uri.startsWith("/api/") || uri == "/status") {
                unauthorizedJson()
            } else {
                redirectToLogin()
            }
        }

        // ── Rotas protegidas ───────────────────────────────────────────────
        return when {
            uri == "/"                 -> serveAsset("index.html", "text/html")
            uri == "/style.css"        -> serveAsset("style.css",  "text/css")
            uri == "/app.js"           -> serveAsset("app.js",     "application/javascript")
            uri == "/status"           -> WebControlApi.serveStatus(cameraController, context)
            uri == "/api/status"       -> WebControlApi.serveStatus(cameraController, context)
            uri == "/api/capabilities" -> WebControlApi.serveCapabilities(cameraController, context)
            uri == "/api/plan"         -> WebControlAuth.servePlan()
            uri == "/api/control" && method == Method.POST
                                       -> WebControlApi.handleControl(session, cameraController)
            else -> notFound()
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

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

    private fun redirectToLogin(): Response {
        val resp = newFixedLengthResponse(
            Response.Status.REDIRECT, "text/plain", "Redirecionando..."
        )
        resp.addHeader("Location", "/auth/login")
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
