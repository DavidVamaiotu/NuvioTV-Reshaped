package com.nuvio.tv.ui.reshaped.phoneentry

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.nuvio.tv.reshaped.net.LanAddress
import com.nuvio.tv.reshaped.phoneentry.PhoneEntryPage
import com.nuvio.tv.reshaped.phoneentry.PhoneEntryServer
import com.nuvio.tv.ui.theme.NuvioTheme
import android.os.Handler
import android.os.Looper

private class PhoneEntryState(val url: String?, val qr: Bitmap?, val error: String?)

/**
 * A QR code for typing [page]'s value on a phone. The page's server runs only while this is
 * shown and the app is in the foreground; [onValue] gets each value sent, on the main thread.
 */
@Composable
internal fun PhoneEntryQr(
    page: PhoneEntryPage,
    instruction: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnValue by rememberUpdatedState(onValue)
    var state by remember { mutableStateOf<PhoneEntryState?>(null) }
    DisposableEffect(lifecycleOwner) {
        val main = Handler(Looper.getMainLooper())
        var running: PhoneEntryServer? = null
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (running == null) {
                    val ip = LanAddress.get(context)
                    if (ip == null) {
                        state = PhoneEntryState(null, null, context.getString(R.string.error_network_required))
                    } else {
                        val server = PhoneEntryServer.startOnAvailablePort(page) { value -> main.post { currentOnValue(value) } }
                        running = server
                        state = if (server == null) {
                            PhoneEntryState(null, null, context.getString(R.string.error_server_ports_unavailable))
                        } else {
                            val url = "http://$ip:${server.listeningPort}/${server.token}/"
                            PhoneEntryState(url, QrCodeGenerator.generate(url, 400), null)
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
            main.removeCallbacksAndMessages(null)
        }
    }

    Column(
        modifier = modifier.width(200.dp),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        state?.qr?.let { qr ->
            Image(
                bitmap = remember(qr) { qr.asImageBitmap() },
                contentDescription = stringResource(R.string.cd_qr_code),
                modifier = Modifier.size(170.dp),
                contentScale = ContentScale.Fit,
            )
        }
        Text(
            text = state?.error ?: instruction,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
        )
        state?.url?.let { url ->
            Text(text = url, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary)
        }
    }
}
