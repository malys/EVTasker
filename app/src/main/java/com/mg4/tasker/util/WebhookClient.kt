package com.mg4.tasker.util

import java.net.HttpURLConnection
import java.net.URL

/** Small, bounded webhook client. HTTPS-only and redirect-safe for vehicle security. */
object WebhookClient {
    data class Response(val ok: Boolean, val detail: String)

    fun call(method: String, rawUrl: String, body: String?): Response {
        var url = runCatching { URL(rawUrl) }.getOrNull()
            ?: return Response(false, "invalid URL")
        repeat(4) { hop ->
            if (url.protocol != "https") return Response(false, "HTTPS required")
            val connection = (url.openConnection() as? HttpURLConnection)
                ?: return Response(false, "unsupported URL")
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = method
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("Accept", "application/json, text/plain, */*")
                if (method == "POST") {
                    val bytes = body.orEmpty().toByteArray(Charsets.UTF_8)
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                val code = connection.responseCode
                if (code in 300..399) {
                    if (hop == 3) return Response(false, "too many redirects")
                    val location = connection.getHeaderField("Location")
                        ?: return Response(false, "redirect without location")
                    url = URL(url, location)
                    return@repeat
                }
                return Response(code in 200..299, "HTTP $code")
            } catch (e: Exception) {
                return Response(false, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
            } finally {
                connection.disconnect()
            }
        }
        return Response(false, "too many redirects")
    }
}
