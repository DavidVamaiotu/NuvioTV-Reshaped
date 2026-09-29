package com.nuvio.tv.reshaped.sync

import fi.iki.elonen.NanoHTTPD
import java.net.URLEncoder
import java.security.SecureRandom

/** The texts of the phone page; plain text, escaped when the page is built. */
internal class GoogleSignInPhoneTexts(
    val title: String,
    val instruction: String,
    val button: String,
    val copied: String,
)

/**
 * The page the TV's sign-in QR code opens on a phone: the sign-in code and one button that
 * copies it and opens Google's device page (with the code filled in where Google accepts it).
 * Served from the TV only while its sign-in dialog is open, behind a random token in the QR code.
 */
internal class GoogleSignInPhonePage private constructor(
    port: Int,
    private val code: GoogleDeviceCode,
    private val texts: GoogleSignInPhoneTexts,
) : NanoHTTPD(port) {

    val token: String = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    override fun serve(session: IHTTPSession): Response =
        if (session.method == Method.GET && (session.uri == "/$token/" || session.uri == "/$token")) {
            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html())
        } else {
            newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden").apply { closeConnection(true) }
        }

    private fun html(): String {
        fun h(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("'", "&#39;").replace("\"", "&quot;")
        val link = code.verificationUrl + "?user_code=" + URLEncoder.encode(code.userCode, "UTF-8")
        // The copy uses a selected field and execCommand: the page is plain http, where the
        // clipboard API is not available.
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${h(texts.title)}</title>
<style>
*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#000;color:#fff;
display:flex;justify-content:center;min-height:100vh;padding:16px}
.card{max-width:560px;width:100%;padding:40px 0;text-align:center}
h1{font-size:22px;font-weight:600;margin-bottom:8px}
p{font-size:14px;color:rgba(255,255,255,0.55);line-height:1.45}
.code{margin:28px 0;font-size:34px;font-weight:600;letter-spacing:6px;padding:18px;border-radius:20px;
border:1px solid rgba(255,255,255,0.14);background:rgba(255,255,255,0.05);width:100%;color:#fff;text-align:center;outline:none}
a.go{display:block;padding:15px;border-radius:100px;background:#fff;color:#000;font-size:15px;font-weight:600;text-decoration:none}
.status{margin-top:14px;font-size:13px;color:#4caf50;min-height:20px}
</style>
</head>
<body>
<div class="card" id="card" data-copied="${h(texts.copied)}">
  <h1>${h(texts.title)}</h1>
  <p>${h(texts.instruction)}</p>
  <input class="code" id="code" value="${h(code.userCode)}" readonly>
  <a class="go" id="go" href="${h(link)}">${h(texts.button)}</a>
  <div class="status" id="status"></div>
</div>
<script>
document.getElementById('go').addEventListener('click',function(){
  var f=document.getElementById('code');f.removeAttribute('readonly');f.focus();f.select();
  f.setSelectionRange(0,f.value.length);
  try{if(document.execCommand('copy'))document.getElementById('status').textContent=document.getElementById('card').dataset.copied}catch(e){}
  f.setAttribute('readonly','');f.blur();
});
</script>
</body>
</html>
""".trimIndent()
    }

    companion object {
        /** After the phone entry pages (8120+) and Live TV's setup page (8110+). */
        private const val START_PORT = 8130
        private const val MAX_ATTEMPTS = 10

        fun start(code: GoogleDeviceCode, texts: GoogleSignInPhoneTexts): GoogleSignInPhonePage? {
            for (port in START_PORT until START_PORT + MAX_ATTEMPTS) {
                try {
                    return GoogleSignInPhonePage(port, code, texts).apply { start(SOCKET_READ_TIMEOUT, false) }
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}
