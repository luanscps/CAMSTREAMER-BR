package com.camera2rtsp

/**
 * WebControlHtml — v5-CAMUI (refatorado assets/)
 *
 * Antes: 61KB de HTML/CSS/JS inline num único StringBuilder.
 * Agora: serve os arquivos da pasta assets/webui/ copiados no APK.
 *
 * COMO USAR NO PROJETO:
 *   1. Copie index.html, style.css e app.js para:
 *      app/src/main/assets/webui/
 *   2. No WebControlServer, substitua o serve de "/" para chamar
 *      WebControlHtml.serve(session, context) em vez de build().
 *   3. Para debug local, o método build() ainda funciona lendo os arquivos.
 *
 * Benefícios:
 *   - HTML/CSS/JS editáveis sem recompilar o APK
 *   - Hot-reload: basta alterar os assets e reinstalar
 *   - Separação clara de responsabilidades
 *   - Tamanho do .kt: de ~61KB → ~2KB
 */
object WebControlHtml {

    /**
     * Serve um arquivo da pasta assets/webui/ como resposta NanoHTTPD.
     * Chame este método no WebControlServer para "/" e sub-rotas de assets.
     *
     * Exemplo no WebControlServer:
     *
     *   "/style.css" -> WebControlHtml.serveAsset(session, context, "style.css", "text/css")
     *   "/app.js"    -> WebControlHtml.serveAsset(session, context, "app.js",    "application/javascript")
     *   "/"          -> WebControlHtml.serveAsset(session, context, "index.html","text/html")
     */
    fun serveAsset(context: android.content.Context, filename: String): String {
        return try {
            context.assets.open("webui/$filename").bufferedReader().readText()
        } catch (e: Exception) {
            "<!-- asset webui/$filename não encontrado: ${e.message} -->"
        }
    }

    /**
     * Compatibilidade: retorna o index.html completo lendo dos assets.
     * Requer que o Context seja passado. Use serveAsset() de preferência.
     */
    fun build(context: android.content.Context): String {
        val html = serveAsset(context, "index.html")
        val css  = serveAsset(context, "style.css")
        val js   = serveAsset(context, "app.js")
        // Inline tudo numa única resposta (sem dependência de múltiplos requests)
        return html
            .replace("<link rel=\"stylesheet\" href=\"style.css\">",
                     "<style>$css</style>")
            .replace("<script src=\"app.js\"></script>",
                     "<script>$js</script>")
    }
}
