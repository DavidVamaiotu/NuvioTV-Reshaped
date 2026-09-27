package com.nuvio.tv.reshaped.phoneentry

import fi.iki.elonen.NanoHTTPD
import java.security.SecureRandom

/**
 * A one-field web page for typing a value (an API key, a password) on a phone instead of with
 * the TV remote. Every path carries a random per-session [token], which is in the QR code, so
 * only someone who can see the TV screen can reach it. Runs only while the TV screen asking for
 * the value is open. [onValue] is called on the server's thread.
 */
internal class PhoneEntryServer(
    port: Int,
    private val page: PhoneEntryPage,
    private val onValue: (String) -> Unit,
) : NanoHTTPD(port) {

    val token: String = newToken()
    private val pagePath = "/$token/"
    private val valuePath = "/$token/api/value"

    override fun serve(session: IHTTPSession): Response = when {
        session.method == Method.GET && (session.uri == pagePath || session.uri == "/$token") ->
            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page.html(valuePath))
        session.method == Method.POST && session.uri == valuePath -> receive(session)
        else -> newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden").apply { closeConnection(true) }
    }

    private fun receive(session: IHTTPSession): Response {
        // A page from another site may post here: its Origin then differs from our Host.
        val origin = session.headers["origin"]
        val host = session.headers["host"]
        if (origin != null && (host == null || !origin.equals("http://$host", ignoreCase = true))) {
            return plain(Response.Status.FORBIDDEN).apply { closeConnection(true) }
        }
        val length = session.headers["content-length"]?.toIntOrNull()
        if (length == null || length <= 0 || length > MAX_BYTES) return plain(Response.Status.BAD_REQUEST).apply { closeConnection(true) }
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = session.inputStream.read(bytes, read, length - read)
            if (n < 0) break
            read += n
        }
        val value = String(bytes, 0, read, Charsets.UTF_8).trim()
        if (value.isEmpty()) return plain(Response.Status.BAD_REQUEST)
        onValue(value)
        return plain(Response.Status.OK)
    }

    private fun plain(status: Response.Status): Response = newFixedLengthResponse(status, MIME_PLAINTEXT, status.description)

    companion object {
        private const val MAX_BYTES = 4 * 1024
        /** Next to Live TV's setup page (8110+), so both can never collide. */
        private const val START_PORT = 8120
        private const val MAX_ATTEMPTS = 10

        private fun newToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }

        fun startOnAvailablePort(page: PhoneEntryPage, onValue: (String) -> Unit): PhoneEntryServer? {
            for (port in START_PORT until START_PORT + MAX_ATTEMPTS) {
                try {
                    return PhoneEntryServer(port, page, onValue).apply { start(SOCKET_READ_TIMEOUT, false) }
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}

/** The texts of a phone entry page; all plain text, escaped when the page is built. */
internal class PhoneEntryPage(
    val title: String,
    val subtitle: String,
    val fieldLabel: String,
    val send: String,
    val sending: String,
    val sent: String,
    val failed: String,
    val secret: Boolean,
) {
    fun html(valuePath: String): String {
        fun h(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("'", "&#39;").replace("\"", "&quot;")
        // Values go into the page's script as data attributes, read back with dataset: no script escaping needed.
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${h(title)}</title>
<style>
*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#000;color:#fff;
display:flex;justify-content:center;min-height:100vh;padding:16px}
.card{max-width:560px;width:100%;padding:32px 0}
h1{font-size:22px;font-weight:600;margin-bottom:6px}
.subtitle{font-size:13px;color:rgba(255,255,255,0.5);margin-bottom:24px;line-height:1.4}
label{display:block;font-size:12px;color:rgba(255,255,255,0.5);margin:14px 0 6px}
input{width:100%;padding:13px 16px;border-radius:14px;border:1px solid rgba(255,255,255,0.14);
background:rgba(255,255,255,0.04);color:#fff;font-size:15px;outline:none}
input:focus{border-color:rgba(255,255,255,0.5)}
.send{width:100%;margin-top:20px;padding:14px;border:0;border-radius:100px;background:#fff;color:#000;
font-size:15px;font-weight:600;cursor:pointer}
.send:disabled{opacity:.4}
.status{margin-top:14px;font-size:13px;text-align:center;min-height:20px}
.status.ok{color:#4caf50}.status.err{color:#ef5350}
</style>
</head>
<body>
<div class="card" id="card" data-api="${h(valuePath)}" data-sending="${h(sending)}" data-sent="${h(sent)}" data-failed="${h(failed)}">
  <h1>${h(title)}</h1>
  <p class="subtitle">${h(subtitle)}</p>
  <label for="v">${h(fieldLabel)}</label>
  <input id="v" type="${if (secret) "password" else "text"}" autocapitalize="off" autocomplete="off" spellcheck="false">
  <button class="send" id="send">${h(send)}</button>
  <div id="status" class="status"></div>
</div>
<script>
const c=document.getElementById('card').dataset,st=document.getElementById('status'),btn=document.getElementById('send');
function msg(t,ok){st.textContent=t;st.className='status '+(ok?'ok':'err')}
btn.onclick=async()=>{const v=document.getElementById('v').value.trim();if(!v)return;btn.disabled=true;msg(c.sending,true);
try{const r=await fetch(c.api,{method:'POST',headers:{'Content-Type':'text/plain'},body:v});msg(r.ok?c.sent:c.failed,r.ok)}
catch(e){msg(c.failed,false)}finally{btn.disabled=false}};
</script>
</body>
</html>
""".trimIndent()
    }
}
