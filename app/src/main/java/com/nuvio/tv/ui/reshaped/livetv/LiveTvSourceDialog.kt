@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.reshaped.livetv

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.reshaped.livetv.LiveTvError
import com.nuvio.tv.reshaped.livetv.LiveTvRepository
import com.nuvio.tv.reshaped.livetv.LiveTvSetupServer
import com.nuvio.tv.reshaped.livetv.LiveTvSourceType
import com.nuvio.tv.reshaped.livetv.LiveTvStalkerSettings
import com.nuvio.tv.reshaped.livetv.LiveTvXtreamSettings
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

private class SetupServerState(val server: LiveTvSetupServer?, val url: String?, val qr: Bitmap?, val error: String?)

/**
 * Where the channels come from: a QR code for the phone setup page next to the same fields for
 * the remote. Closes by itself once a new list loaded, whichever side sent it.
 */
@Composable
internal fun LiveTvSourceDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uiState by LiveTvRepository.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(uiState.sourceType) }
    var m3uUrl by rememberSaveable { mutableStateOf(uiState.sourceUrl.takeIf { uiState.sourceType == LiveTvSourceType.M3u && it.startsWith("http") }.orEmpty()) }
    var xtreamServer by rememberSaveable { mutableStateOf(uiState.xtreamSettings.serverUrl) }
    var xtreamUser by rememberSaveable { mutableStateOf(uiState.xtreamSettings.username) }
    var xtreamPassword by rememberSaveable { mutableStateOf(uiState.xtreamSettings.password) }
    var stalkerPortal by rememberSaveable { mutableStateOf(uiState.stalkerSettings.portalUrl) }
    var stalkerMac by rememberSaveable { mutableStateOf(uiState.stalkerSettings.macAddress) }
    var stalkerUser by rememberSaveable { mutableStateOf(uiState.stalkerSettings.username) }
    var stalkerPassword by rememberSaveable { mutableStateOf(uiState.stalkerSettings.password) }
    val firstFocus = remember { FocusRequester() }

    // Close once a load that started while the dialog was open finishes with channels.
    var sawLoading by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.isLoading, uiState.error) {
        if (uiState.isLoading) {
            sawLoading = true
        } else if (sawLoading && (uiState.error == null || uiState.error == LiveTvError.StalkerIncomplete) && uiState.channels.isNotEmpty()) {
            onDismiss()
        }
    }

    // The phone page runs only while this dialog is open and the app is in the foreground.
    var serverState by remember { mutableStateOf<SetupServerState?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var running: LiveTvSetupServer? = null
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (running == null) {
                    val started = startSetupServer(context)
                    running = started.server
                    serverState = started
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
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.live_tv_source_title),
        subtitle = stringResource(R.string.live_tv_source_description),
        width = 900.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
        ) {
            Column(
                modifier = Modifier.width(220.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                serverState?.qr?.let { qr ->
                    Image(
                        bitmap = remember(qr) { qr.asImageBitmap() },
                        contentDescription = stringResource(R.string.cd_qr_code),
                        modifier = Modifier.size(200.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
                Text(
                    text = serverState?.error ?: stringResource(R.string.live_tv_phone_instruction),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                )
                serverState?.url?.let { url ->
                    Text(text = url, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary)
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    LiveTvPillButton(
                        text = stringResource(R.string.live_tv_source_m3u),
                        selected = tab == LiveTvSourceType.M3u,
                        onClick = { tab = LiveTvSourceType.M3u },
                        modifier = Modifier.focusRequester(firstFocus),
                    )
                    LiveTvPillButton(
                        text = stringResource(R.string.live_tv_source_xtream),
                        selected = tab == LiveTvSourceType.Xtream,
                        onClick = { tab = LiveTvSourceType.Xtream },
                    )
                    LiveTvPillButton(
                        text = stringResource(R.string.live_tv_source_stalker),
                        selected = tab == LiveTvSourceType.Stalker,
                        onClick = { tab = LiveTvSourceType.Stalker },
                    )
                }

                when (tab) {
                    LiveTvSourceType.M3u -> {
                        LiveTvTextField(m3uUrl, { m3uUrl = it }, stringResource(R.string.live_tv_m3u_hint))
                    }
                    LiveTvSourceType.Xtream -> {
                        LiveTvTextField(xtreamServer, { xtreamServer = it }, stringResource(R.string.live_tv_xtream_server_hint))
                        LiveTvTextField(xtreamUser, { xtreamUser = it }, stringResource(R.string.live_tv_username_hint), keyboardType = KeyboardType.Text)
                        LiveTvTextField(xtreamPassword, { xtreamPassword = it }, stringResource(R.string.live_tv_password_hint), password = true)
                    }
                    LiveTvSourceType.Stalker -> {
                        LiveTvTextField(stalkerPortal, { stalkerPortal = it }, stringResource(R.string.live_tv_stalker_portal_hint))
                        LiveTvTextField(stalkerMac, { stalkerMac = it }, stringResource(R.string.live_tv_stalker_mac_hint), keyboardType = KeyboardType.Ascii)
                        LiveTvTextField(stalkerUser, { stalkerUser = it }, stringResource(R.string.live_tv_optional_username_hint), keyboardType = KeyboardType.Text)
                        LiveTvTextField(stalkerPassword, { stalkerPassword = it }, stringResource(R.string.live_tv_optional_password_hint), password = true)
                    }
                }

                val status = when {
                    uiState.isLoading -> stringResource(R.string.live_tv_loading)
                    uiState.error != null -> uiState.error?.message(context)
                    uiState.sourceUrl.isNotBlank() -> stringResource(R.string.live_tv_current_source, uiState.sourceUrl.redacted())
                    else -> null
                }
                status?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (uiState.error != null && !uiState.isLoading) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm, Alignment.End),
                ) {
                    if (uiState.hasSource) {
                        LiveTvPillButton(
                            text = stringResource(R.string.live_tv_remove_source),
                            onClick = { LiveTvRepository.disconnect() },
                        )
                    }
                    LiveTvPillButton(text = stringResource(R.string.live_tv_close), onClick = onDismiss)
                    LiveTvPillButton(
                        text = stringResource(R.string.live_tv_load),
                        enabled = !uiState.isLoading,
                        onClick = {
                            when (tab) {
                                LiveTvSourceType.M3u -> LiveTvRepository.loadM3uUrl(m3uUrl)
                                LiveTvSourceType.Xtream -> LiveTvRepository.loadXtream(LiveTvXtreamSettings(xtreamServer, xtreamUser, xtreamPassword))
                                LiveTvSourceType.Stalker -> LiveTvRepository.loadStalker(
                                    LiveTvStalkerSettings(stalkerPortal, stalkerMac, stalkerUser, stalkerPassword),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun startSetupServer(context: android.content.Context): SetupServerState {
    val ip = DeviceIpAddress.get(context)
        ?: return SetupServerState(null, null, null, context.getString(R.string.error_network_required))
    val server = LiveTvSetupServer.startOnAvailablePort(context)
        ?: return SetupServerState(null, null, null, context.getString(R.string.error_server_ports_unavailable))
    val url = "http://$ip:${server.listeningPort}/${server.token}/"
    return SetupServerState(server, url, QrCodeGenerator.generate(url, 400), null)
}

/** A source for display: an Xtream-style link's login is not shown on screen. */
private fun String.redacted(): String =
    replace(Regex("""(?i)(password|username)=[^&]*"""), "$1=…")
