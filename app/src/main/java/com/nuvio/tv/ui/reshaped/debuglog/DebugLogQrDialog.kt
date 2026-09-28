package com.nuvio.tv.ui.reshaped.debuglog

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.reshaped.debuglog.DebugLogServer
import com.nuvio.tv.reshaped.net.LanAddress
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.player.audiosync.SyncLog
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File

private class DebugLogQrState(val url: String?, val qr: Bitmap?, val error: String?)

/**
 * A QR code that opens a page on the phone listing the TV's debug logs to download. Its server
 * runs only while this dialog is shown and the app is in the foreground.
 */
@Composable
internal fun DebugLogQrDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var state by remember { mutableStateOf<DebugLogQrState?>(null) }
    DisposableEffect(lifecycleOwner) {
        var running: DebugLogServer? = null
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (running == null) {
                    val ip = LanAddress.get(context)
                    state = if (ip == null) {
                        DebugLogQrState(null, null, context.getString(R.string.error_network_required))
                    } else {
                        // Where AutoSyncDebugLog saves its reports.
                        val reports = File(context.cacheDir, "autosync-debug")
                        val server = DebugLogServer.startOnAvailablePort(reports) { SyncLog.text() }
                        running = server
                        if (server == null) {
                            DebugLogQrState(null, null, context.getString(R.string.error_server_ports_unavailable))
                        } else {
                            val url = "http://$ip:${server.listeningPort}/${server.token}/"
                            DebugLogQrState(url, QrCodeGenerator.generate(url, 400), null)
                        }
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    running?.stop()
                    running = null
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            running?.stop()
        }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.reshaped_debug_logs_to_phone_title),
        subtitle = stringResource(R.string.reshaped_debug_logs_to_phone_instruction),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            state?.qr?.let { qr ->
                Image(
                    bitmap = remember(qr) { qr.asImageBitmap() },
                    contentDescription = stringResource(R.string.cd_qr_code),
                    modifier = Modifier.size(200.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            state?.error?.let { error ->
                Text(text = error, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
            }
            state?.url?.let { url ->
                Text(text = url, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary)
            }
        }
    }
}
