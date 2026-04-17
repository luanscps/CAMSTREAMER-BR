package com.camera2rtsp

import com.camera2rtsp.auth.ApiClient
import com.camera2rtsp.auth.SessionManager
import com.camera2rtsp.auth.SupabaseLoginRequest
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.runBlocking

/**
 * WebControlAuth — Login/Logout da WebGUI usando Supabase (mesmas credenciais do app).
 *
 * POST /auth/login  → email + password → valida no Supabase → seta cookie CAMSESSION
 * GET  /auth/logout → invalida token   → expira cookie
 * GET  /api/plan    → retorna plano atual
 */
object WebControlAuth {

    private val gson = Gson()

    // ── POST /auth/login ───────────────────────────────────────────────────

    fun handleLogin(session: NanoHTTPD.IHTTPSession): Response {
        return try {
            val map = mutableMapOf<String, String>()
            session.parseBody(map)
            val json = map["postData"] ?: return badRequest("Corpo vazio")

            val params = gson.fromJson<Map<String, Any>>(
                json, object : TypeToken<Map<String, Any>>() {}.type
            )

            val email    = (params["email"]    as? String) ?: return badRequest("Campo 'email' obrigatório")
            val password = (params["password"] as? String) ?: return badRequest("Campo 'password' obrigatório")

            // Valida no Supabase — mesmas credenciais do app
            val authResp = runBlocking {
                ApiClient.supabaseAuth.login(
                    apiKey = ApiClient.SUPABASE_ANON_KEY,
                    body   = SupabaseLoginRequest(email, password)
                )
            }

            if (!authResp.isSuccessful || authResp.body()?.accessToken == null) {
                val errBody = authResp.errorBody()?.string() ?: ""
                val msg = try {
                    val j = com.google.gson.JsonParser.parseString(errBody).asJsonObject
                    j.get("error_description")?.asString
                        ?: j.get("msg")?.asString
                        ?: j.get("error")?.asString
                        ?: "Credenciais inválidas"
                } catch (_: Exception) { "Credenciais inválidas" }

                val resp = NanoHTTPD.newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, "application/json",
                    """{"status":"error","message":"$msg"}"""
                )
                resp.addHeader("Access-Control-Allow-Origin", "*")
                return resp
            }

            // Login OK → cria sessão WebGUI (cookie 8h)
            val token = SessionManager.createWebSession()
            val resp = NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "application/json",
                """{"status":"ok","plan":"${SessionManager.plan}"}"""
            )
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
        val body = """{"plan":"${SessionManager.plan}","max_rtmp_outputs":${f.maxRtmpOutputs},"max_resolution":${f.maxResolution},"max_bitrate_kbps":${f.maxBitrateKbps},"web_control":${f.webControl},"local_recording":${f.localRecording},"max_stream_minutes":${f.maxStreamMinutes},"front_camera":${f.frontCamera},"max_devices":${f.maxDevices}}"""
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
