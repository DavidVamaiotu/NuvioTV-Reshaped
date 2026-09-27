@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.reshaped.livetv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.livetv.LiveTvRepository
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Picks the categories the list shows. For people who only watch a few: Hide all, then turn on
 * the ones they want. Hidden categories leave the list, search and channel switching; favorites
 * always stay.
 */
@Composable
internal fun LiveTvCategoryDialog(onDismiss: () -> Unit) {
    val uiState by LiveTvRepository.uiState.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.live_tv_categories_title),
        subtitle = stringResource(R.string.live_tv_categories_description),
        width = 640.dp,
        usePlatformDefaultWidth = false,
        contentSpacing = NuvioTheme.spacing.md,
    ) {
        val shown = uiState.groups.count { it !in uiState.hiddenGroups }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.live_tv_categories_shown, shown, uiState.groups.size),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            LiveTvPillButton(text = stringResource(R.string.live_tv_categories_show_all), onClick = { LiveTvRepository.setAllGroupsHidden(false) })
            LiveTvPillButton(text = stringResource(R.string.live_tv_categories_hide_all), onClick = { LiveTvRepository.setAllGroupsHidden(true) })
            LiveTvPillButton(text = stringResource(R.string.live_tv_done), onClick = onDismiss)
        }
        if (uiState.groups.isEmpty()) {
            Text(
                text = stringResource(R.string.live_tv_categories_none),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(uiState.groups, key = { _, group -> group }) { index, group ->
                    val visible = group !in uiState.hiddenGroups
                    LiveTvCategoryToggle(
                        label = group,
                        count = uiState.groupCounts[group] ?: 0,
                        visible = visible,
                        onToggle = { LiveTvRepository.setGroupHidden(group, visible) },
                        modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveTvCategoryToggle(
    label: String,
    count: Int,
    visible: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val content = when {
        focused -> Color.Black
        visible -> NuvioTheme.colors.TextPrimary
        else -> NuvioTheme.colors.TextTertiary
    }
    Card(
        onClick = onToggle,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        colors = CardDefaults.colors(
            containerColor = if (visible) NuvioTheme.colors.TextPrimary.copy(alpha = 0.08f) else Color.Transparent,
            focusedContainerColor = NuvioTheme.colors.TextPrimary,
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (focused) Color.Black.copy(alpha = 0.55f) else NuvioTheme.colors.TextTertiary,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.sm),
            )
            // A check for shown categories; the same space stays empty for hidden ones.
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = if (visible) content else Color.Transparent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
