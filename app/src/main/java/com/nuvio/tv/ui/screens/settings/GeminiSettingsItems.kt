@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.gemini.GeminiTranslationPreferences
import com.nuvio.tv.gemini.SupportedLanguage
import com.nuvio.tv.reshaped.phoneentry.PhoneEntryPage
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.reshaped.phoneentry.PhoneEntryQr
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

internal fun LazyListScope.geminiSettingsItems(onFocused: () -> Unit = {}) {
    item(key = "gemini_api_key") {
        val context = LocalContext.current
        GeminiTranslationPreferences.ensureLoaded(context)
        val apiKey by GeminiTranslationPreferences.apiKey.collectAsStateWithLifecycle()
        var showKeyDialog by remember { mutableStateOf(false) }

        NavigationSettingsItem(
            icon = Icons.Default.Key,
            title = stringResource(R.string.settings_gemini_api_key),
            subtitle = stringResource(
                if (apiKey.isBlank()) R.string.settings_gemini_api_key_missing
                else R.string.settings_gemini_api_key_configured
            ),
            onClick = { showKeyDialog = true },
            onFocused = onFocused,
        )

        if (showKeyDialog) {
            GeminiApiKeyDialog(currentValue = apiKey, onDismiss = { showKeyDialog = false })
        }
    }

    item(key = "gemini_target_language") {
        val context = LocalContext.current
        GeminiTranslationPreferences.ensureLoaded(context)
        val targetLang by GeminiTranslationPreferences.targetLanguage.collectAsStateWithLifecycle()
        var showLangDialog by remember { mutableStateOf(false) }

        val currentLangName = GeminiTranslationPreferences.getLanguageName(targetLang)

        NavigationSettingsItem(
            icon = Icons.Default.Language,
            title = stringResource(R.string.settings_gemini_target_language),
            subtitle = currentLangName,
            onClick = { showLangDialog = true },
            onFocused = onFocused,
        )

        if (showLangDialog) {
            GeminiLanguagePickerDialog(
                currentLang = targetLang,
                onSelect = { selected ->
                    GeminiTranslationPreferences.setTargetLanguage(context, selected)
                    showLangDialog = false
                },
                onDismiss = { showLangDialog = false }
            )
        }
    }

    item(key = "gemini_model") {
        val context = LocalContext.current
        GeminiTranslationPreferences.ensureLoaded(context)
        val currentModel by GeminiTranslationPreferences.model.collectAsStateWithLifecycle()
        var showModelDialog by remember { mutableStateOf(false) }

        NavigationSettingsItem(
            icon = Icons.Default.Psychology,
            title = stringResource(R.string.settings_gemini_model),
            subtitle = currentModel,
            onClick = { showModelDialog = true },
            onFocused = onFocused,
        )

        if (showModelDialog) {
            GeminiModelPickerDialog(
                currentModel = currentModel,
                onSelect = { selected ->
                    GeminiTranslationPreferences.setModel(context, selected)
                    showModelDialog = false
                },
                onDismiss = { showModelDialog = false }
            )
        }
    }

    item(key = "gemini_auto_translate") {
        val context = LocalContext.current
        GeminiTranslationPreferences.ensureLoaded(context)
        val autoTranslate by GeminiTranslationPreferences.autoTranslate.collectAsStateWithLifecycle()

        ToggleSettingsItem(
            icon = Icons.Default.AutoAwesome,
            title = stringResource(R.string.settings_gemini_auto_translate),
            subtitle = stringResource(R.string.settings_gemini_auto_translate_desc),
            isChecked = autoTranslate,
            onCheckedChange = { GeminiTranslationPreferences.setAutoTranslate(context, it) },
            onFocused = onFocused,
        )
    }

    item(key = "gemini_clear_cache") {
        val context = LocalContext.current
        var cacheSize by remember { mutableLongStateOf(0L) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            cacheSize = GeminiTranslationPreferences.getCacheSize(context)
        }

        val cacheKb = cacheSize / 1024
        val subtitle = if (cacheKb > 1024) "${cacheKb / 1024} MB cached" else "$cacheKb KB cached"

        NavigationSettingsItem(
            icon = Icons.Default.DeleteSweep,
            title = stringResource(R.string.settings_gemini_clear_cache),
            subtitle = subtitle,
            onClick = {
                scope.launch {
                    GeminiTranslationPreferences.clearCache(context)
                    cacheSize = 0L
                    Toast.makeText(context, R.string.settings_gemini_clear_cache_done, Toast.LENGTH_SHORT).show()
                }
            },
            onFocused = onFocused,
        )
    }
}

@Composable
private fun GeminiApiKeyDialog(currentValue: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var validating by remember { mutableStateOf(false) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val invalidMessage = stringResource(R.string.settings_gemini_api_key_invalid)

    fun save(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty() || trimmed == currentValue) {
            GeminiTranslationPreferences.setApiKey(context, trimmed)
            onDismiss()
            return
        }
        validating = true
        scope.launch {
            val valid = GeminiTranslationPreferences.validateApiKey(trimmed)
            validating = false
            if (valid) {
                GeminiTranslationPreferences.setApiKey(context, trimmed)
                onDismiss()
            } else {
                Toast.makeText(context, invalidMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_gemini_api_key_dialog_title),
        subtitle = stringResource(R.string.settings_gemini_api_key_dialog_subtitle),
        width = 860.dp,
        usePlatformDefaultWidth = false,
    ) {
        val phonePage = PhoneEntryPage(
            title = stringResource(R.string.settings_gemini_api_key_dialog_title),
            subtitle = "Paste your Google Gemini API key from Google AI Studio",
            fieldLabel = "Gemini API Key",
            send = "Save Key",
            sending = "Saving...",
            sent = "Saved!",
            failed = "Failed to send",
            secret = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)) {
            PhoneEntryQr(
                page = phonePage,
                instruction = "Scan with your phone to paste your key directly",
                onValue = { sent ->
                    value = sent
                    if (!validating) save(sent)
                },
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
            ) {
                Card(
                    onClick = { inputFocusRequester.requestFocus() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
                    colors = CardDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated,
                        focusedContainerColor = NuvioTheme.colors.BackgroundElevated
                    ),
                    border = CardDefaults.border(
                        border = Border(
                            border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                            shape = RoundedCornerShape(10.dp)
                        ),
                        focusedBorder = Border(
                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                            shape = RoundedCornerShape(10.dp)
                        )
                    )
                ) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        BasicTextField(
                            value = value,
                            onValueChange = { value = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(inputFocusRequester)
                                .onKeyEvent { event ->
                                    if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER) {
                                        keyboardController?.hide()
                                        save(value)
                                        true
                                    } else false
                                },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                            cursorBrush = SolidColor(Color.White),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                keyboardController?.hide()
                                save(value)
                            })
                        )
                        if (value.isEmpty() && !isInputFocused) {
                            Text(
                                text = stringResource(R.string.settings_gemini_api_key_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { save(value) },
                        enabled = !validating,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.Primary,
                            contentColor = Color.White,
                        )
                    ) {
                        Text(if (validating) "Validating..." else "Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun GeminiLanguagePickerDialog(
    currentLang: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_gemini_target_language),
        subtitle = "Select the target language for translated subtitles",
        width = 540.dp,
    ) {
        val languages = GeminiTranslationPreferences.SUPPORTED_LANGUAGES
        val initialFocusRequester = remember { FocusRequester() }

        LaunchedEffect(Unit) {
            initialFocusRequester.requestFocus()
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
        ) {
            items(languages, key = { it.code }) { item ->
                val isSelected = item.code.equals(currentLang, ignoreCase = true)
                Card(
                    onClick = { onSelect(item.code) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (isSelected) Modifier.focusRequester(initialFocusRequester) else Modifier),
                    colors = CardDefaults.colors(
                        containerColor = if (isSelected) NuvioTheme.colors.Primary.copy(alpha = 0.2f) else NuvioTheme.colors.BackgroundElevated,
                        focusedContainerColor = NuvioTheme.colors.Primary
                    ),
                    border = CardDefaults.border(
                        border = Border(
                            border = BorderStroke(NuvioTheme.spacing.hairline, if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.Border),
                            shape = RoundedCornerShape(8.dp)
                        )
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeminiModelPickerDialog(
    currentModel: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_gemini_model),
        subtitle = "Choose the Gemini model for subtitle translation",
        width = 540.dp,
    ) {
        val models = GeminiTranslationPreferences.AVAILABLE_MODELS
        val initialFocusRequester = remember { FocusRequester() }

        LaunchedEffect(Unit) {
            initialFocusRequester.requestFocus()
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        ) {
            models.forEach { modelName ->
                val isSelected = modelName == currentModel
                Card(
                    onClick = { onSelect(modelName) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (isSelected) Modifier.focusRequester(initialFocusRequester) else Modifier),
                    colors = CardDefaults.colors(
                        containerColor = if (isSelected) NuvioTheme.colors.Primary.copy(alpha = 0.2f) else NuvioTheme.colors.BackgroundElevated,
                        focusedContainerColor = NuvioTheme.colors.Primary
                    ),
                    border = CardDefaults.border(
                        border = Border(
                            border = BorderStroke(NuvioTheme.spacing.hairline, if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.Border),
                            shape = RoundedCornerShape(8.dp)
                        )
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = modelName,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            val desc = when {
                                modelName.contains("2.0") -> "Next-gen experimental Flash model, ultra fast"
                                modelName.contains("pro") -> "Highest quality, slower and lower rate limits"
                                else -> "Recommended: Fast, accurate, high rate limits"
                            }
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}
