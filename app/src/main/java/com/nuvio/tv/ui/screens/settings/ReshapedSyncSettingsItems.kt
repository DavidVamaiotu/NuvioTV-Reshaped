@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.util.Log
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.reshaped.sync.GoogleAccount
import com.nuvio.tv.reshaped.sync.GoogleAuthException
import com.nuvio.tv.reshaped.net.LanAddress
import com.nuvio.tv.reshaped.sync.GoogleDeviceCode
import com.nuvio.tv.reshaped.sync.GoogleSignInPhonePage
import com.nuvio.tv.reshaped.sync.GoogleSignInPhoneTexts
import com.nuvio.tv.reshaped.sync.ReshapedSync
import com.nuvio.tv.reshaped.sync.ReshapedSyncFailure
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * "Sync with Google" rows: sign in with a code typed on a phone, then choose what syncs between
 * this TV and the viewer's other TVs and phone. Both switches are off until turned on.
 */
internal fun LazyListScope.reshapedSyncSettingsItems(onFocused: () -> Unit = {}) {
    item(key = "reshaped_sync") {
        val context = LocalContext.current
        GoogleAccount.ensureLoaded(context)
        ReshapedSync.ensureLoaded(context)
        val email by GoogleAccount.email.collectAsStateWithLifecycle()
        val syncSettings by ReshapedSync.syncSettings.collectAsStateWithLifecycle()
        val syncLiveTv by ReshapedSync.syncLiveTv.collectAsStateWithLifecycle()
        val status by ReshapedSync.status.collectAsStateWithLifecycle()
        var showSignIn by remember { mutableStateOf(false) }
        var showSignOut by remember { mutableStateOf(false) }
        val configured = GoogleAccount.isConfigured

        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            if (email == null) {
                NavigationSettingsItem(
                    icon = Icons.Default.CloudSync,
                    title = stringResource(R.string.reshaped_sync_sign_in_title),
                    subtitle = stringResource(
                        if (configured) R.string.reshaped_sync_sign_in_description else R.string.reshaped_sync_not_configured
                    ),
                    onClick = { showSignIn = true },
                    onFocused = onFocused,
                    enabled = configured,
                )
            } else {
                NavigationSettingsItem(
                    icon = Icons.Default.AccountCircle,
                    title = stringResource(R.string.reshaped_sync_account_title),
                    subtitle = email?.takeIf(String::isNotBlank)?.let { stringResource(R.string.reshaped_sync_signed_in_as, it) }
                        ?: stringResource(R.string.reshaped_sync_signed_in),
                    onClick = { showSignOut = true },
                    onFocused = onFocused,
                )
                ToggleSettingsItem(
                    icon = Icons.Default.Tune,
                    title = stringResource(R.string.reshaped_sync_settings_title),
                    subtitle = stringResource(R.string.reshaped_sync_settings_description),
                    isChecked = syncSettings,
                    onCheckedChange = { ReshapedSync.setSyncSettings(context, it) },
                    onFocused = onFocused,
                )
                ToggleSettingsItem(
                    icon = Icons.Default.LiveTv,
                    title = stringResource(R.string.reshaped_sync_live_tv_title),
                    subtitle = stringResource(R.string.reshaped_sync_live_tv_description),
                    isChecked = syncLiveTv,
                    onCheckedChange = { ReshapedSync.setSyncLiveTv(context, it) },
                    onFocused = onFocused,
                )
                if (syncSettings || syncLiveTv) {
                    NavigationSettingsItem(
                        icon = Icons.Default.Sync,
                        title = stringResource(R.string.reshaped_sync_now_title),
                        subtitle = when {
                            status.running -> stringResource(R.string.reshaped_sync_running)
                            status.failed == ReshapedSyncFailure.Network -> stringResource(R.string.reshaped_sync_failed_network) +
                                status.failedDetail.takeIf(String::isNotBlank)?.let { "\n" + it }.orEmpty()
                            status.failed == ReshapedSyncFailure.SignedOut -> stringResource(R.string.reshaped_sync_failed_signed_out)
                            status.failed == ReshapedSyncFailure.NewerVersion -> stringResource(R.string.reshaped_sync_failed_newer)
                            status.lastSyncedAtMs == 0L -> stringResource(R.string.reshaped_sync_never)
                            else -> stringResource(
                                R.string.reshaped_sync_last,
                                DateUtils.getRelativeTimeSpanString(
                                    status.lastSyncedAtMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                                ).toString(),
                            )
                        },
                        onClick = { if (!status.running) ReshapedSync.syncNow(context) },
                        onFocused = onFocused,
                    )
                }
            }
        }

        if (showSignIn) GoogleSignInDialog(onDismiss = { showSignIn = false })
        if (showSignOut) GoogleSignOutDialog(onDismiss = { showSignOut = false })
    }
}

private sealed interface SignInStep {
    data object Starting : SignInStep
    data class Waiting(val code: GoogleDeviceCode) : SignInStep
    data class Failed(val message: Int, val detail: String = "") : SignInStep
}

@Composable
private fun GoogleSignInDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableStateOf<SignInStep>(SignInStep.Starting) }
    var attempt by remember { mutableIntStateOf(0) }

    // Leaving the dialog cancels this, which stops waiting for the phone.
    LaunchedEffect(attempt) {
        step = SignInStep.Starting
        step = try {
            val code = GoogleAccount.requestDeviceCode()
            step = SignInStep.Waiting(code)
            GoogleAccount.awaitSignIn(context, code)
            ReshapedSync.onSignedIn(context)
            onDismiss()
            return@LaunchedEffect
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: GoogleAuthException) {
            Log.w("ReshapedSync", "Google sign-in refused: ${error.detail}")
            when (error.code) {
                "access_denied" -> SignInStep.Failed(R.string.reshaped_sync_sign_in_declined)
                "expired_token" -> SignInStep.Failed(R.string.reshaped_sync_sign_in_expired)
                else -> SignInStep.Failed(R.string.reshaped_sync_sign_in_refused, error.detail)
            }
        } catch (error: Exception) {
            Log.w("ReshapedSync", "Google sign-in failed", error)
            SignInStep.Failed(R.string.reshaped_sync_sign_in_failed, error.message?.takeIf(String::isNotBlank) ?: error::class.java.simpleName)
        }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.reshaped_sync_sign_in_title),
        subtitle = stringResource(R.string.reshaped_sync_sign_in_description),
        width = 760.dp,
        usePlatformDefaultWidth = false,
    ) {
        when (val current = step) {
            SignInStep.Starting -> Text(
                text = stringResource(R.string.reshaped_sync_sign_in_starting),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
            )
            is SignInStep.Waiting -> Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val qrUrl = rememberSignInPhoneUrl(current.code)
                val qr = remember(qrUrl) { QrCodeGenerator.generate(qrUrl, 400).asImageBitmap() }
                Image(bitmap = qr, contentDescription = null, modifier = Modifier.size(200.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                ) {
                    Text(
                        text = stringResource(R.string.reshaped_sync_sign_in_instruction, current.code.verificationUrl.removePrefix("https://")),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                    )
                    Text(
                        text = current.code.userCode,
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp),
                        color = NuvioTheme.colors.TextPrimary,
                    )
                    Text(
                        text = stringResource(R.string.reshaped_sync_sign_in_waiting),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextTertiary,
                    )
                    DialogButton(stringResource(R.string.action_cancel), onDismiss)
                }
            }
            is SignInStep.Failed -> Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)) {
                Text(
                    text = stringResource(current.message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                )
                if (current.detail.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.reshaped_sync_sign_in_detail, current.detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextTertiary,
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    DialogButton(stringResource(R.string.action_cancel), onDismiss)
                    Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                    DialogButton(stringResource(R.string.reshaped_sync_try_again)) { attempt++ }
                }
            }
        }
    }
}

/**
 * The QR code's link: a page served by this TV that copies the code and opens Google on the
 * phone, so nothing is typed. Google's own page when the TV has no network address or port.
 */
@Composable
private fun rememberSignInPhoneUrl(code: GoogleDeviceCode): String {
    val context = LocalContext.current
    val texts = GoogleSignInPhoneTexts(
        title = stringResource(R.string.reshaped_sync_sign_in_title),
        instruction = stringResource(R.string.reshaped_sync_phone_instruction),
        button = stringResource(R.string.reshaped_sync_phone_button),
        copied = stringResource(R.string.reshaped_sync_phone_copied),
    )
    var url by remember(code) { mutableStateOf(code.verificationUrl) }
    DisposableEffect(code) {
        val ip = LanAddress.get(context)
        val page = if (ip != null) GoogleSignInPhonePage.start(code, texts) else null
        if (page != null) url = "http://$ip:${page.listeningPort}/${page.token}/"
        onDispose { page?.stop() }
    }
    return url
}

@Composable
private fun GoogleSignOutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.reshaped_sync_sign_out_title),
        subtitle = stringResource(R.string.reshaped_sync_sign_out_description),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DialogButton(stringResource(R.string.action_cancel), onDismiss)
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            DialogButton(stringResource(R.string.reshaped_sync_sign_out)) {
                scope.launch {
                    GoogleAccount.signOut(context)
                    ReshapedSync.onSignedOut(context)
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            contentColor = NuvioTheme.colors.TextPrimary,
        ),
    ) {
        Text(label)
    }
}
