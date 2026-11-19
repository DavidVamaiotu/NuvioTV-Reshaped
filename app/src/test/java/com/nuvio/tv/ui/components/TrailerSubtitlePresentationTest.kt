package com.nuvio.tv.ui.components

import android.graphics.Color
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.media3.common.text.Cue
import com.nuvio.tv.ui.screens.player.SubtitleHorizontalPosition
import com.nuvio.tv.ui.screens.player.SubtitleVerticalPosition
import org.junit.Assert.*
import org.junit.Test
import io.mockk.every
import io.mockk.mockk

class TrailerSubtitlePresentationTest {
    @Test fun sourceCaptionPlacementAndColoursCannotOverrideTheSharedPlayerStyle() {
        val colour = mockk<ForegroundColorSpan>()
        every { colour.foregroundColor } returns Color.RED
        val text = mockk<Spanned>()
        every { text.length } returns 5
        every { text.toString() } returns "Hello"
        every { text.getSpans(0, 5, Any::class.java) } returns arrayOf<Any>(colour)
        every { text.getSpanStart(colour) } returns 0
        every { text.getSpanEnd(colour) } returns 5
        val cue = Cue.Builder().setText("Hello").setPosition(0f)
            .setLine(0f, Cue.LINE_TYPE_FRACTION).build()
        // Media3 copies Android spans through SpannedString. Seed an already decoded
        // cue so the existing JVM suite can test real span handling without Android constructors.
        Cue::class.java.getField("text").apply { isAccessible = true }.set(cue, text)
        val block = trailerStyledSubtitleCues(listOf(cue)).blocks.single()
        assertEquals("Hello", block.text.text)
        assertTrue(block.text.spanStyles.isEmpty())
        assertEquals(SubtitleVerticalPosition.BOTTOM, block.vertical)
        assertEquals(SubtitleHorizontalPosition.CENTER, block.horizontal)
    }

    @Test fun emptyCaptionUpdatesClearTheOverlayAndMultilineTextIsPreserved() {
        val cues = listOf(Cue.Builder().setText("First\nline").build(),
            Cue.Builder().setText(" ").build(), Cue.Builder().setText("Second").build())
        assertEquals("First\nline\nSecond", trailerStyledSubtitleCues(cues).blocks.single().text.text)
        assertTrue(trailerStyledSubtitleCues(emptyList()).isEmpty)
        assertTrue(trailerStyledSubtitleCues(listOf(Cue.Builder().setText("").build())).isEmpty)
    }
}
