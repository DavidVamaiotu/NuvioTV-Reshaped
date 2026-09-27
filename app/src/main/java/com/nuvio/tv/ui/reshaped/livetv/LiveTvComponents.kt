@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.reshaped.livetv

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.nuvio.tv.ui.theme.NuvioTheme

internal val LiveTvPillShape = RoundedCornerShape(100.dp)

/** A pill button in the app's quiet style: elevated surface, white when focused. */
@Composable
internal fun LiveTvPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = ButtonDefaults.shape(LiveTvPillShape),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.TextPrimary.copy(alpha = 0.16f) else NuvioTheme.colors.BackgroundElevated,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.TextPrimary,
            focusedContentColor = Color.Black,
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.04f),
    ) {
        Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A one-line text field for the remote: the card takes focus without opening the keyboard;
 * OK opens it.
 */
@Composable
internal fun LiveTvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Uri,
    password: Boolean = false,
    onDone: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(12.dp)
    Card(
        onClick = { inputFocusRequester.requestFocus(); keyboardController?.show() },
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused || it.hasFocus },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            focusedContainerColor = NuvioTheme.colors.BackgroundElevated,
        ),
        border = CardDefaults.border(
            border = Border(BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border), shape = shape),
            focusedBorder = Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape),
        ),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f),
    ) {
        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(inputFocusRequester)
                    .onKeyEvent { event ->
                        val isCenterDown = event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                            event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        if (isCenterDown) keyboardController?.show()
                        isCenterDown
                    },
                singleLine = true,
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboardType,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide(); onDone() }),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                cursorBrush = SolidColor(if (focused) NuvioTheme.colors.Primary else Color.Transparent),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextTertiary,
                            maxLines = 1,
                        )
                    }
                    inner()
                },
            )
        }
    }
}

/**
 * A channel logo, decoded at the size it is drawn (channel logos are often large PNGs). Falls back
 * to the channel's initials.
 */
@Composable
internal fun LiveTvLogo(
    url: String?,
    name: String,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .size(width, height)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.initials(),
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.TextTertiary,
        )
        if (!url.isNullOrBlank()) {
            val context = LocalContext.current
            val density = LocalDensity.current
            val request = remember(url, width, height) {
                with(density) {
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(width.roundToPx(), height.roundToPx())
                        .build()
                }
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
            )
        }
    }
}

private fun String.initials(): String =
    split(' ', '-', '_', '.').filter { it.isNotBlank() && it.first().isLetterOrDigit() }
        .take(2).joinToString("") { it.first().uppercase() }
