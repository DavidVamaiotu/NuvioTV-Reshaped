package com.nuvio.tv.ui.screens.player

import android.annotation.SuppressLint
import android.media.AudioFormat
import androidx.media3.common.MimeTypes
import com.nuvio.tv.core.player.AudioPassthroughPolicy
import com.nuvio.tv.core.player.AudioPassthroughPolicy.Group

/**
 * Rules for re-reading the TV's formats while a title plays. Every change in place makes media3
 * switch all renderers off and on, which on some boxes restarts the Dolby stream and the picture.
 */
internal object SurroundRecheckGuard {
    const val CONFIRM_DELAY_MS = 2_500L
    const val RETURN_CONFIRM_DELAY_MS = 1_000L
    const val MAX_READINGS = 4

    enum class Decision { APPLY, KEEP_FOR_BITSTREAM }

    private val groupMimes = mapOf(
        Group.AC3 to MimeTypes.AUDIO_AC3,
        Group.EAC3 to MimeTypes.AUDIO_E_AC3,
        Group.TRUEHD to MimeTypes.AUDIO_TRUEHD,
        Group.DTS to MimeTypes.AUDIO_DTS,
        Group.DTS_HD to MimeTypes.AUDIO_DTS_HD
    )

    /** Remotes, keyboards and microphones are inputs and say nothing about the audio output. */
    fun eventHasOutput(sinkFlags: List<Boolean>): Boolean = sinkFlags.any { it }

    /**
     * Reads again until two readings in a row agree, so a value that flips while the box
     * switches its own output is never acted on. Null when no two readings agreed.
     */
    suspend fun <T> settledReading(
        first: T,
        read: suspend () -> T,
        wait: suspend () -> Unit,
        maxReadings: Int = MAX_READINGS
    ): T? {
        var last = first
        repeat(maxReadings - 1) {
            wait()
            val next = read()
            if (next == last) return next
            last = next
        }
        return null
    }

    /**
     * Android's HDMI plug report (1 plugged, 0 unplugged). Without a report, an HDMI output
     * missing from the device list counts as unplugged.
     */
    fun hdmiUnplugged(plugState: Int, hdmiOutputListed: Boolean): Boolean =
        plugState == 0 || (plugState != 1 && !hdmiOutputListed)

    fun passableGroups(policy: AudioPassthroughPolicy): Set<Group> =
        groupMimes.filterTo(mutableMapOf()) { (_, mime) -> !policy.deniesPassthrough(mime) }.keys

    fun onlyAddsFormats(current: SurroundResolveResult, next: SurroundResolveResult): Boolean {
        val before = passableGroups(current.resolution.policy)
        val after = passableGroups(next.resolution.policy)
        return after.containsAll(before) && after != before
    }

    /**
     * HDMI back after a real unplug, with a reading that only adds formats: the formats in use
     * were set while nothing was connected, so the second reading can come sooner.
     */
    fun firstConfirmDelayMs(appliedWhileUnplugged: Boolean, pluggedNow: Boolean, onlyAddsFormats: Boolean): Long =
        if (appliedWhileUnplugged && pluggedNow && onlyAddsFormats) RETURN_CONFIRM_DELAY_MS else CONFIRM_DELAY_MS

    @SuppressLint("InlinedApi")
    private val pcmEncodings = setOf(
        AudioFormat.ENCODING_INVALID,
        AudioFormat.ENCODING_DEFAULT,
        AudioFormat.ENCODING_PCM_8BIT,
        AudioFormat.ENCODING_PCM_16BIT,
        AudioFormat.ENCODING_PCM_FLOAT,
        AudioFormat.ENCODING_PCM_24BIT_PACKED,
        AudioFormat.ENCODING_PCM_32BIT
    )

    /**
     * Whether the format list in Android's HDMI plug report, which comes from the TV or
     * receiver itself, lost a passthrough format since the formats in use were applied. A
     * receiver switched off with the cable still in does that; a passing blip in the box's own
     * answer does not. PCM entries are left out.
     */
    fun plugListLostFormats(atApply: Set<Int>?, now: Set<Int>?): Boolean =
        atApply != null && now != null && !now.containsAll(atApply - pcmEncodings)

    /**
     * While a bitstream plays and HDMI stays plugged in, the formats in use stay for the title
     * unless the new reading only adds formats. A real unplug, such as the TV switching to
     * another input and the box going to sleep, or a format gone from the HDMI plug report, is
     * applied as before.
     */
    fun decide(
        current: SurroundResolveResult?,
        next: SurroundResolveResult,
        bitstreamPlaying: Boolean,
        hdmiUnplugged: Boolean,
        plugListLostFormats: Boolean = false
    ): Decision {
        if (current == null || !bitstreamPlaying || hdmiUnplugged || plugListLostFormats) return Decision.APPLY
        return if (onlyAddsFormats(current, next)) Decision.APPLY else Decision.KEEP_FOR_BITSTREAM
    }
}
