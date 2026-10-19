package com.nuvio.tv.ui.screens.player.seekpreview.engine

import com.nuvio.tv.reshaped.debuglog.DebugLogServer
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class PreviewReportExportTest {
    private fun session(path: String, method: NanoHTTPD.Method = NanoHTTPD.Method.GET): NanoHTTPD.IHTTPSession =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(NanoHTTPD.IHTTPSession::class.java)) { _, called, _ ->
            when (called.name) {
                "getUri" -> path
                "getMethod" -> method
                else -> error("Unexpected request property: ${called.name}")
            }
        } as NanoHTTPD.IHTTPSession

    @Test fun previewReportIsDownloadableOnlyWithTheCurrentToken() {
        val server = DebugLogServer(0, File("missing-reports"), { "audio" }, { "preview report" })
        val response = server.serve(session("/${server.token}/log/seek-preview-log.txt"))
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("preview report", response.data.bufferedReader().readText())
        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN,
            server.serve(session("/wrong/log/seek-preview-log.txt")).status)
        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN,
            server.serve(session("/${server.token}/log/seek-preview-log.txt", NanoHTTPD.Method.POST)).status)
    }

    @Test fun phonePageIncludesThePreviewDownload() {
        val server = DebugLogServer(0, File("missing-reports"), { "audio" }, { "preview report" })
        val html = server.serve(session("/${server.token}/")).data.bufferedReader().readText()
        assertTrue(html.contains("/${server.token}/log/seek-preview-log.txt"))
        assertTrue(html.contains("audio-sync-log.txt"))
    }
}
