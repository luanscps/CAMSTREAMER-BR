package com.camera2rtsp

import com.camera2rtsp.auth.SessionManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response

/**
 * WebControlAuth — Login / Logout / Plan para a WebUI (NanoHTTPD)
 *
 * POST /auth/login  → valida senha local → seta cookie CAMSESSION (8h)
 * GET  /auth/logout → invalida token     → expira cookie
 * GET  /api/plan    → retorna features do plano atual
 *
 * A senha é gerenciada pelo SessionManager (EncryptedSharedPreferences).
 * Primeiro acesso: qualquer senha digitada é salva como senha definitiva.
 */
object WebControlAuth {

    private val gson = Gson()

    // ── POST /auth/login ───────────────────────────────────────────────────

    fun handleLogin(session: NanoHTTPD.IHTTPSession): Response {
        return try {
            val map = mutableMapOf<String, String>()
            session.parseBody(map)

            val json = map["postData"]
                ?: return badRequest("Corpo da requisicao vazio")

            val params = gson.fromJson<Map<String, Any>>(
                json, object : TypeToken<Map<String, Any>>() {}.type
            )

            val password = (params["password"] as? String)
                ?: return badRequest("Campo 'password' obrigatorio")

            // Primeiro acesso: salva a senha automaticamente
            if (!SessionManager.hasWebPassword()) {
                SessionManager.setWebPassword(password)
            }

            if (!SessionManager.checkWebPassword(password)) {
                val resp = NanoHTTPD.newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, "application/json",
                    """{"status":"error","message":"Senha incorreta"}"""
                )
                resp.addHeader("Access-Control-Allow-Origin", "*")
                return resp
            }

            val token = SessionManager.createWebSession()
            val resp = NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "application/json",
                """{"status":"ok","plan":"${SessionManager.plan}"}"""
            )
            // Cookie HttpOnly, TTL 8h
            resp.addHeader("Set-Cookie", "CAMSESSION=$token; Path=/; HttpOnly; Max-Age=28800")
            resp.addHeader("Access-Control-Allow-Origin", "*")
            resp
        } catch (e: Exception) {
            internalError(e.message ?: "Erro interno")
        }
    }

    // ── GET /auth/logout ───────────────────────────────────────────────────

    fun handleLogout(): Response {
        SessionManager.invalidateWebSession()
        val resp = NanoHTTPD.newFixedLengthResponse(
            Response.Status.REDIRECT, "text/plain", "Saindo..."
        )
        resp.addHeader("Set-Cookie", "CAMSESSION=; Path=/; HttpOnly; Max-Age=0")
        resp.addHeader("Location", "/auth/login")
        return resp
    }

    // ── GET /api/plan ──────────────────────────────────────────────────────

    fun servePlan(): Response {
        val f = SessionManager.features
        val body = """{
            "plan":"${SessionManager.plan}",
            "max_rtmp_outputs":${f.maxRtmpOutputs},
            "max_resolution":${f.maxResolution},
            "max_bitrate_kbps":${f.maxBitrateKbps},
            "web_control":${f.webControl},
            "local_recording":${f.localRecording},
            "max_stream_minutes":${f.maxStreamMinutes},
            "front_camera":${f.frontCamera},
            "max_devices":${f.maxDevices}
        }"""
        val resp = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", body)
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun badRequest(msg: String): Response {
        val resp = NanoHTTPD.newFixedLengthResponse(
            Response.Status.BAD_REQUEST, "application/json",
            """{"status":"error","message":"$msg"}"""
        )
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }

    private fun internalError(msg: String): Response {
        val resp = NanoHTTPD.newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR, "application/json",
            """{"status":"error","message":"$msg"}"""
        )
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }
}
