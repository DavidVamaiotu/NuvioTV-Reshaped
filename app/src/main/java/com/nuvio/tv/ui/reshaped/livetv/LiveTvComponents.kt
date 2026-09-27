@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.reshaped.livetv

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
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
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.livetv.LiveTvClock
import com.nuvio.tv.reshaped.livetv.LiveTvProgramme
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

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
 * OK opens it. The arrows always leave the field (left and right once the cursor is at that
 * end): Compose only does this for input devices that report a D-pad, so remotes that arrive
 * as a keyboard or through HDMI-CEC used to be stuck in the field.
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
    val focusManager = LocalFocusManager.current
    // The field keeps its own cursor; the text itself follows [value].
    var fieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (fieldValue.text != value) fieldValue = TextFieldValue(value, TextRange(value.length))
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
                value = fieldValue,
                onValueChange = { next ->
                    fieldValue = next
                    if (next.text != value) onValueChange(next.text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(inputFocusRequester)
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.action != KeyEvent.ACTION_DOWN) {
                            // The matching key-up of a handled press must not reach the field either.
                            return@onPreviewKeyEvent native.keyCode in DPAD_ARROWS
                        }
                        val selection = fieldValue.selection
                        val direction = when (native.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                            KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                            KeyEvent.KEYCODE_DPAD_LEFT -> if (selection.collapsed && selection.start == 0) FocusDirection.Left else null
                            KeyEvent.KEYCODE_DPAD_RIGHT ->
                                if (selection.collapsed && selection.end == fieldValue.text.length) FocusDirection.Right else null
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                keyboardController?.show()
                                return@onPreviewKeyEvent true
                            }
                            else -> return@onPreviewKeyEvent false
                        } ?: return@onPreviewKeyEvent false
                        keyboardController?.hide()
                        // Nothing that way (the top of the screen): stay, rather than typing an arrow.
                        focusManager.moveFocus(direction)
                        true
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

private val DPAD_ARROWS = intArrayOf(
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
)

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

/** The time, updated on each minute. */
@Composable
internal fun rememberLiveTvMinuteClock(): State<Long> = produceState(LiveTvClock.nowEpochMs()) {
    while (true) {
        delay(60_000L - value % 60_000L)
        value = LiveTvClock.nowEpochMs()
    }
}

/** "23 min left" or "1 h 5 min left" for the programme on now; recomposes with the minute clock. */
@Composable
internal fun liveTvTimeLeft(programme: LiveTvProgramme, clock: State<Long>): String {
    val minutes = ((programme.stopEpochMs - clock.value).coerceAtLeast(0L) + 59_999L) / 60_000L
    return if (minutes < 60) {
        stringResource(R.string.live_tv_minutes_left, minutes.toInt())
    } else {
        stringResource(R.string.live_tv_hours_minutes_left, (minutes / 60).toInt(), (minutes % 60).toInt())
    }
}

/** How far the programme on now has got, as a thin bar. Read at draw time: the minute tick redraws it without recomposing. */
@Composable
internal fun LiveTvProgressBar(
    programme: LiveTvProgramme,
    clock: State<Long>,
    fill: Color,
    track: Color,
    modifier: Modifier = Modifier,
) {
    val span = (programme.stopEpochMs - programme.startEpochMs).coerceAtLeast(1L)
    Box(
        modifier = modifier
            .height(3.dp)
            .clip(LiveTvPillShape)
            .background(track)
            .drawBehind {
                val fraction = ((clock.value - programme.startEpochMs).toFloat() / span).coerceIn(0f, 1f)
                drawRect(fill, size = Size(size.width * fraction, size.height))
            },
    )
}
