package com.nuvio.tv.reshaped.debuglog

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A read-only web page that hands the TV's AutoSync and audio sync debug logs to a phone. Every
 * path carries a random per-session token, which is in the QR code, so only someone who can see
 * the TV screen can reach it. Runs only while the TV screen showing the QR code is open.
 */
internal class DebugLogServer(
    port: Int,
    private val reportDir: File,
    private val audioSyncLog: () -> String,
) : NanoHTTPD(port) {

    val token: String = newToken()
    private val pagePath = "/$token/"
    private val filePrefix = "/$token/log/"

    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.GET) return forbidden()
        val uri = session.uri
        return when {
            uri == pagePath || uri == "/$token" -> html(page())
            uri == "$filePrefix$AUDIO_SYNC_LOG" -> text(AUDIO_SYNC_LOG, audioSyncLog())
            uri.startsWith(filePrefix) -> reports().firstOrNull { it.name == uri.removePrefix(filePrefix) }
                ?.let { file -> runCatching { text(file.name, file.readText()) }.getOrNull() }
                ?: newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            else -> forbidden()
        }
    }

    /** AutoSync's saved reports, newest first ("latest.txt" is a copy of the newest one). */
    private fun reports(): List<File> =
        reportDir.listFiles { file -> file.isFile && file.name.startsWith("autosync-") && file.name.endsWith(".txt") }
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .take(MAX_REPORTS)

    private fun page(): String {
        fun h(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("'", "&#39;").replace("\"", "&quot;")
        val time = SimpleDateFormat("d MMM HH:mm:ss", Locale.getDefault())
        val rows = reports().joinToString("\n") { file ->
            val kb = (file.length() + 1023) / 1024
            """<a class="row" href="${h(filePrefix + file.name)}" download="${h(file.name)}">
<span>AutoSync report · ${h(time.format(Date(file.lastModified())))}</span><span class="size">$kb KB</span></a>"""
        }.ifEmpty {
            """<p class="empty">No AutoSync report yet. With Debug logs on, one is saved each time AutoSync finishes.</p>"""
        }
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Nuvio Reshaped debug logs</title>
<style>
*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#000;color:#fff;
display:flex;justify-content:center;min-height:100vh;padding:16px}
.card{max-width:560px;width:100%;padding:32px 0}
h1{font-size:22px;font-weight:600;margin-bottom:6px}
.subtitle{font-size:13px;color:rgba(255,255,255,0.5);margin-bottom:24px;line-height:1.4}
h2{font-size:12px;color:rgba(255,255,255,0.5);font-weight:500;margin:20px 0 8px}
.row{display:flex;justify-content:space-between;gap:12px;padding:14px 16px;border-radius:14px;margin-bottom:8px;
border:1px solid rgba(255,255,255,0.14);background:rgba(255,255,255,0.04);color:#fff;text-decoration:none;font-size:15px}
.size{color:rgba(255,255,255,0.5);font-size:13px;white-space:nowrap}
.empty{font-size:13px;color:rgba(255,255,255,0.5);line-height:1.4}
</style>
</head>
<body>
<div class="card">
  <h1>Debug logs</h1>
  <p class="subtitle">Tap a log to download it, then send the file with your report.</p>
  <h2>AutoSync</h2>
  $rows
  <h2>Audio sync</h2>
  <a class="row" href="${h(filePrefix + AUDIO_SYNC_LOG)}" download="$AUDIO_SYNC_LOG"><span>Audio sync log</span></a>
</div>
</body>
</html>
""".trimIndent()
    }

    private fun html(body: String): Response = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)

    private fun text(name: String, body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", body).apply {
            addHeader("Content-Disposition", "attachment; filename=\"$name\"")
        }

    private fun forbidden(): Response =
        newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden").apply { closeConnection(true) }

    companion object {
        private const val AUDIO_SYNC_LOG = "audio-sync-log.txt"
        private const val MAX_REPORTS = 10
        /** After the phone entry pages (8120+), so they never collide. */
        private const val START_PORT = 8130
        private const val MAX_ATTEMPTS = 10

        private fun newToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }

        fun startOnAvailablePort(reportDir: File, audioSyncLog: () -> String): DebugLogServer? {
            for (port in START_PORT until START_PORT + MAX_ATTEMPTS) {
                try {
                    return DebugLogServer(port, reportDir, audioSyncLog).apply { start(SOCKET_READ_TIMEOUT, false) }
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}
