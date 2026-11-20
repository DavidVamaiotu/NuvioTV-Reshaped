package com.nuvio.tv.ui.reshaped

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

private const val PREFS = "reshaped_discontinued_notice"
private const val SEEN_KEY = "seen"

/** Big one-time notice that Nuvio Reshaped is discontinued, shown on the first open after updating. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DiscontinuedNotice() {
    val prefs = LocalContext.current.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    var visible by remember { mutableStateOf(!prefs.getBoolean(SEEN_KEY, false)) }
    if (!visible) return

    val dismiss = {
        prefs.edit().putBoolean(SEEN_KEY, true).apply()
        visible = false
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    NuvioDialog(
        onDismiss = dismiss,
        title = "",
        width = 760.dp,
        usePlatformDefaultWidth = false
    ) {
        Text(
            text = "Nuvio Reshaped is being discontinued",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary
        )
        Text(
            text = "Audio sync and AutoSync are now available in the official Nuvio app, " +
                "so this fork is no longer needed. This is the final Nuvio Reshaped build " +
                "and no further updates will be released.",
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextPrimary
        )
        Text(
            text = "Please switch to the official Nuvio app:\ngithub.com/NuvioMedia/NuvioTV/releases",
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextPrimary
        )
        Text(
            text = "Thanks to everyone who took part in this challenge. " +
                "I'm glad it was a successful experiment.",
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextSecondary
        )
        Button(
            onClick = dismiss,
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
        ) {
            Text("Got it")
        }
    }
}
