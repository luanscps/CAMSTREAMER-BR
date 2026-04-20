package com.camera2rtsp

/*
 * WebControlHtml - v5-CAMUI
 *
 * Serve os arquivos da pasta assets/webui/ copiados no APK.
 * Arquivos: index.html, style.css, app.js
 * Caminho:  app/src/main/assets/webui/
 */
object WebControlHtml {

    fun serveAsset(context: android.content.Context, filename: String): String {
        return try {
            context.assets.open("webui/$filename").bufferedReader().readText()
        } catch (e: Exception) {
            "<!-- asset webui/$filename nao encontrado: ${e.message} -->"
        }
    }

    fun build(context: android.content.Context): String {
        val html = serveAsset(context, "index.html")
        val css  = serveAsset(context, "style.css")
        val js   = serveAsset(context, "app.js")
        return html
            .replace("<link rel=\"stylesheet\" href=\"style.css\">",
                     "<style>$css</style>")
            .replace("<script src=\"app.js\"></script>",
                     "<script>$js</script>")
    }
}
