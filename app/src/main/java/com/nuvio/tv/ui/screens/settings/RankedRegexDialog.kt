@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.streams.ranking.RankedRegexRules
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun RankedRegexDialog(
    initialPatterns: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    // Stable row identities keep text input and D-pad focus attached to a rule after moving it.
    var nextId by remember(initialPatterns) { mutableStateOf(initialPatterns.size) }
    var rows by remember(initialPatterns) {
        mutableStateOf(initialPatterns.mapIndexed { index, pattern -> index to pattern })
    }
    var presetRowId by remember(initialPatterns) { mutableStateOf<Int?>(null) }
    val presets = streamRegexPresets()
    val patterns = rows.map { it.second.trim() }.filter { it.isNotEmpty() }
    val valid = patterns.isEmpty() || RankedRegexRules.create(patterns) != null

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.ranked_regex_title),
        subtitle = stringResource(R.string.ranked_regex_summary),
        width = 860.dp
    ) {
        Column(modifier = Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(stringResource(R.string.ranked_regex_help), style = MaterialTheme.typography.bodySmall)
                }
                itemsIndexed(rows, key = { _, row -> row.first }) { index, row ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.ranked_regex_priority, index + 1))
                            Button(onClick = { presetRowId = row.first }) {
                                Text(stringResource(R.string.autoplay_regex_presets))
                            }
                        }
                        RankedRegexInput(row.second) { value ->
                            rows = rows.map { if (it.first == row.first) it.first to value else it }
                        }
                        if (row.second.isNotBlank() && !RankedRegexRules.isValidPattern(row.second)) {
                            Text(
                                stringResource(R.string.ranked_regex_invalid),
                                color = NuvioTheme.colors.Error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(enabled = index > 0, onClick = {
                                rows = rows.toMutableList().apply { add(index - 1, removeAt(index)) }
                            }) { Text(stringResource(R.string.ranked_regex_up)) }
                            Button(enabled = index < rows.lastIndex, onClick = {
                                rows = rows.toMutableList().apply { add(index + 1, removeAt(index)) }
                            }) { Text(stringResource(R.string.ranked_regex_down)) }
                            Button(onClick = { rows = rows.filter { it.first != row.first } }) {
                                Text(stringResource(R.string.ranked_regex_remove))
                            }
                        }
                    }
                }
                item {
                    Button(enabled = rows.size < RankedRegexRules.MAX_RULES, onClick = {
                        rows = rows + (nextId++ to "")
                    }) { Text(stringResource(R.string.ranked_regex_add)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { rows = emptyList() }) {
                    Text(stringResource(R.string.ranked_regex_clear))
                }
                Button(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Button(enabled = valid, onClick = { onSave(patterns) }) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
    presetRowId?.let { rowId ->
        NuvioDialog(
            onDismiss = { presetRowId = null },
            title = stringResource(R.string.autoplay_regex_presets),
            subtitle = stringResource(R.string.ranked_regex_preset_help),
            width = 700.dp
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(presets, key = { index, _ -> index }) { _, preset ->
                    Card(
                        onClick = {
                            rows = rows.map { if (it.first == rowId) it.first to preset.second else it }
                            presetRowId = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        scale = CardDefaults.scale(focusedScale = 1f)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(preset.first)
                            Text(
                                preset.second,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RankedRegexInput(value: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Card(
        onClick = { focus.requestFocus(); keyboard?.show() },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(containerColor = NuvioTheme.colors.BackgroundElevated),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, NuvioTheme.colors.Border)),
            focusedBorder = Border(NuvioTheme.focusRing.border(2.dp))
        ),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        BasicTextField(
            value = value,
            onValueChange = { if (it.length <= RankedRegexRules.MAX_PATTERN_LENGTH) onChange(it) },
            modifier = Modifier.fillMaxWidth().padding(12.dp).focusRequester(focus),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = NuvioTheme.colors.TextPrimary,
                textDirection = TextDirection.Ltr
            ),
            cursorBrush = SolidColor(NuvioTheme.colors.Primary),
            decorationBox = { input ->
                if (value.isEmpty()) Text(
                    stringResource(R.string.ranked_regex_placeholder),
                    color = NuvioTheme.colors.TextTertiary
                )
                input()
            }
        )
    }
}
