package com.nuvio.tv.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.media3.common.text.Cue
import com.nuvio.tv.ui.screens.player.StyledSubtitleBlock
import com.nuvio.tv.ui.screens.player.StyledSubtitleCues
import com.nuvio.tv.ui.screens.player.styledSubtitleCues

/** Trailer captions use the player's full Reshaped style, rather than the source's placement/colours. */
internal fun trailerStyledSubtitleCues(cues: List<Cue>): StyledSubtitleCues = styledSubtitleCues(
    cues.mapNotNull { cue ->
        val text = cue.text?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        StyledSubtitleBlock(AnnotatedString(text))
    }
)
