@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Edit
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
import com.nuvio.tv.gemini.GeminiKeyValidationResult
import com.nuvio.tv.gemini.GeminiModelOption
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
    var validationMessage by remember { mutableStateOf<String?>(null) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    fun save(key: String, force: Boolean = false) {
        val cleaned = GeminiTranslationPreferences.cleanApiKey(key)
        if (cleaned.isEmpty()) {
            GeminiTranslationPreferences.setApiKey(context, "")
            Toast.makeText(context, R.string.settings_gemini_api_key_clear, Toast.LENGTH_SHORT).show()
            onDismiss()
            return
        }

        if (force) {
            GeminiTranslationPreferences.setApiKey(context, cleaned)
            Toast.makeText(context, R.string.settings_gemini_api_key_saved, Toast.LENGTH_SHORT).show()
            onDismiss()
            return
        }

        validating = true
        validationMessage = null
        scope.launch {
            val result = GeminiTranslationPreferences.validateApiKeyDetailed(cleaned)
            validating = false
            when (result) {
                is GeminiKeyValidationResult.Success -> {
                    GeminiTranslationPreferences.setApiKey(context, cleaned)
                    Toast.makeText(context, R.string.settings_gemini_api_key_saved, Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
                is GeminiKeyValidationResult.InvalidKey -> {
                    validationMessage = result.message
                }
                is GeminiKeyValidationResult.NetworkIssue -> {
                    validationMessage = result.message
                }
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
                    val cleaned = GeminiTranslationPreferences.cleanApiKey(sent)
                    value = cleaned
                    if (!validating) save(cleaned)
                },
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
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
                            border = BorderStroke(NuvioTheme.spacing.hairline, if (validationMessage != null) Color(0xFFE57373) else NuvioTheme.colors.Border),
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
                            onValueChange = {
                                value = it
                                validationMessage = null
                            },
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

                if (validating) {
                    Text(
                        text = stringResource(R.string.settings_gemini_api_key_verifying),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.Primary
                    )
                } else if (validationMessage != null) {
                    Text(
                        text = "⚠️ Validation warning: ${validationMessage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFB74D)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentValue.isNotBlank()) {
                        Button(
                            onClick = { save("", force = true) },
                            enabled = !validating,
                            colors = ButtonDefaults.colors(
                                containerColor = Color(0xFF333333),
                                contentColor = Color.LightGray,
                            )
                        ) {
                            Text(stringResource(R.string.settings_gemini_api_key_clear))
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (validationMessage != null) {
                            Button(
                                onClick = { save(value, force = true) },
                                colors = ButtonDefaults.colors(
                                    containerColor = Color(0xFF424242),
                                    contentColor = Color.White,
                                )
                            ) {
                                Text(stringResource(R.string.settings_gemini_api_key_save_anyway))
                            }
                        }

                        Button(
                            onClick = { save(value) },
                            enabled = !validating,
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioTheme.colors.Primary,
                                contentColor = Color.White,
                            )
                        ) {
                            Text(if (validating) "Verifying..." else "Verify & Save")
                        }
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
        subtitle = "Choose the Gemini or Gemma model for subtitle translation",
        width = 620.dp,
    ) {
        val models = GeminiTranslationPreferences.MODEL_OPTIONS
        val initialFocusRequester = remember { FocusRequester() }

        LaunchedEffect(Unit) {
            initialFocusRequester.requestFocus()
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        ) {
            items(models, key = { it.id }) { item ->
                val isSelected = item.id.equals(currentModel.removePrefix("models/"), ignoreCase = true)
                Card(
                    onClick = { onSelect(item.id) },
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
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = item.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                                item.badge?.let { badgeText ->
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                color = if (badgeText.contains("Open")) Color(0xFF1E88E5).copy(alpha = 0.35f) else NuvioTheme.colors.Primary.copy(alpha = 0.3f),
                                                shape = RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = badgeText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                            Text(
                                text = item.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary
                            )
                            Text(
                                text = item.id,
                                style = MaterialTheme.typography.labelSmall,
                                color = NuvioTheme.colors.TextSecondary.copy(alpha = 0.7f)
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
            item(key = "custom_model_btn") {
                val isCustomSelected = models.none { it.id.equals(currentModel.removePrefix("models/"), ignoreCase = true) }
                var showCustomInput by remember { mutableStateOf(false) }

                Card(
                    onClick = { showCustomInput = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.colors(
                        containerColor = if (isCustomSelected) NuvioTheme.colors.Primary.copy(alpha = 0.2f) else NuvioTheme.colors.BackgroundElevated,
                        focusedContainerColor = NuvioTheme.colors.Primary
                    ),
                    border = CardDefaults.border(
                        border = Border(
                            border = BorderStroke(NuvioTheme.spacing.hairline, if (isCustomSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.Border),
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
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = if (isCustomSelected) currentModel else "Custom Model",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                                Box(
                                    modifier = Modifier
                                        .background(
                                            color = Color(0xFF757575).copy(alpha = 0.3f),
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Manual Entry",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White
                                    )
                                }
                            }
                            Text(
                                text = "Enter a custom model ID",
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                        Icon(
                            imageVector = if (isCustomSelected) Icons.Default.Check else Icons.Default.Edit,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                }

                if (showCustomInput) {
                    GeminiCustomModelDialog(
                        currentValue = if (isCustomSelected) currentModel else "",
                        onDismiss = { showCustomInput = false },
                        onSave = { 
                            onSelect(it)
                            showCustomInput = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GeminiCustomModelDialog(
    currentValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }

    NuvioDialog(
        onDismiss = onDismiss,
        title = "Custom Model",
        subtitle = "Enter a valid Gemini or Gemma model ID (e.g. gemini-2.5-flash)",
        width = 540.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                                    if (value.isNotBlank()) {
                                        onSave(value.trim())
                                    }
                                    true
                                } else false
                            },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                        cursorBrush = SolidColor(Color.White),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            keyboardController?.hide()
                            if (value.isNotBlank()) {
                                onSave(value.trim())
                            }
                        })
                    )
                    if (value.isEmpty() && !isInputFocused) {
                        Text(
                            text = "Model ID",
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
                    onClick = {
                        keyboardController?.hide()
                        if (value.isNotBlank()) {
                            onSave(value.trim())
                        }
                    },
                    enabled = value.isNotBlank(),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.Primary,
                        contentColor = Color.White,
                    )
                ) {
                    Text("Save Custom Model")
                }
            }
        }
        
        LaunchedEffect(Unit) {
            inputFocusRequester.requestFocus()
        }
    }
}
