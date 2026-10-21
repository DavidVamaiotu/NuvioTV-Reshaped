package com.nuvio.tv.ui.screens.player

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import com.nuvio.tv.core.player.AudioPassthroughPolicy
import com.nuvio.tv.core.player.SurroundFormatResolver
import com.nuvio.tv.data.local.AudioOutputChannels
import com.nuvio.tv.ui.screens.player.SurroundRecheckGuard.Decision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurroundRecheckGuardTest {

    @Test
    fun eventWithOnlyInputDevicesIsIgnored() {
        assertFalse(SurroundRecheckGuard.eventHasOutput(listOf(false)))
        assertFalse(SurroundRecheckGuard.eventHasOutput(listOf(false, false)))
        assertFalse(SurroundRecheckGuard.eventHasOutput(emptyList()))
    }

    @Test
    fun eventWithAnOutputDeviceIsHandled() {
        assertTrue(SurroundRecheckGuard.eventHasOutput(listOf(true)))
        assertTrue(SurroundRecheckGuard.eventHasOutput(listOf(false, true)))
    }

    @Test
    fun aChangeIsActedOnOnlyWhenTwoReadingsAgree() = runBlocking<Unit> {
        val later = ArrayDeque(listOf("pcm"))
        var waits = 0
        val settled = SurroundRecheckGuard.settledReading(
            first = "pcm",
            read = { later.removeFirst() },
            wait = { waits++ }
        )
        assertEquals("pcm", settled)
        assertEquals(1, waits)
    }

    @Test
    fun aReadingThatFlipsBackIsNotActedOn() = runBlocking<Unit> {
        val later = ArrayDeque(listOf("truehd", "pcm", "truehd"))
        val settled = SurroundRecheckGuard.settledReading(
            first = "pcm",
            read = { later.removeFirst() },
            wait = {}
        )
        assertNull(settled)
        assertTrue(later.isEmpty())
    }

    @Test
    fun aReceiverThatReportsLateSettlesOnTheLaterReading() = runBlocking<Unit> {
        val later = ArrayDeque(listOf("all", "all"))
        val settled = SurroundRecheckGuard.settledReading(
            first = "ac3 only",
            read = { later.removeFirst() },
            wait = {}
        )
        assertEquals("all", settled)
    }

    @Test
    fun everyFurtherReadingComesAfterAWait() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val later = ArrayDeque(listOf("truehd", "pcm", "truehd"))
        SurroundRecheckGuard.settledReading(
            first = "pcm",
            read = { calls += "read"; later.removeFirst() },
            wait = { calls += "wait" }
        )
        assertEquals(listOf("wait", "read", "wait", "read", "wait", "read"), calls)
    }

    @Test
    fun readingsAreSpacedTwoToThreeSecondsApart() {
        assertTrue(SurroundRecheckGuard.CONFIRM_DELAY_MS in 2_000L..3_000L)
    }

    private val everything = result(AudioPassthroughPolicy.ALLOW_ALL)
    private val nothing = result(
        AudioPassthroughPolicy(
            allowAc3 = false, allowEac3 = false, allowTrueHd = false, allowDts = false, allowDtsHd = false
        ),
        channelTarget = 8
    )
    private val arcSoundbar = result(
        AudioPassthroughPolicy(allowTrueHd = false, allowDtsHd = false),
        channelTarget = 2
    )

    @Test
    fun bitstreamKeepsItsFormatWhileHdmiStaysPlugged() {
        assertEquals(Decision.KEEP_FOR_BITSTREAM, decide(everything, nothing, bitstream = true, unplugged = false))
        assertEquals(Decision.KEEP_FOR_BITSTREAM, decide(everything, arcSoundbar, bitstream = true, unplugged = false))
    }

    @Test
    fun bitstreamKeepsFormatsWhenOnlyTheChannelCountReadsDifferently() {
        val sixChannels = everything.copy(targetChannels = 6, downmixEnabled = true)
        assertEquals(Decision.KEEP_FOR_BITSTREAM, decide(everything, sixChannels, bitstream = true, unplugged = false))
    }

    @Test
    fun bitstreamTakesAReadingThatOnlyAddsFormats() {
        assertEquals(Decision.APPLY, decide(arcSoundbar, everything, bitstream = true, unplugged = false))
    }

    @Test
    fun bitstreamKeepsAReadingThatAddsOneFormatAndDropsAnother() {
        val noDts = result(AudioPassthroughPolicy(allowDts = false, allowDtsHd = false))
        val noTrueHd = result(AudioPassthroughPolicy(allowTrueHd = false))
        assertEquals(Decision.KEEP_FOR_BITSTREAM, decide(noDts, noTrueHd, bitstream = true, unplugged = false))
    }

    @Test
    fun noFormatsInUseYetMeansTheReadingIsApplied() {
        assertEquals(
            Decision.APPLY,
            SurroundRecheckGuard.decide(null, nothing, bitstreamPlaying = true, hdmiUnplugged = false)
        )
    }

    @Test
    fun realUnplugIsStillApplied() {
        assertEquals(Decision.APPLY, decide(everything, nothing, bitstream = true, unplugged = true))
    }

    @Test
    fun decodedPlaybackFollowsEveryReading() {
        assertEquals(Decision.APPLY, decide(everything, nothing, bitstream = false, unplugged = false))
        assertEquals(Decision.APPLY, decide(everything, arcSoundbar, bitstream = false, unplugged = false))
    }

    @Test
    fun hdmiPlugReportDecidesUnplugged() {
        assertTrue(SurroundRecheckGuard.hdmiUnplugged(plugState = 0, hdmiOutputListed = true))
        assertFalse(SurroundRecheckGuard.hdmiUnplugged(plugState = 1, hdmiOutputListed = false))
        assertFalse(SurroundRecheckGuard.hdmiUnplugged(plugState = -1, hdmiOutputListed = true))
        assertTrue(SurroundRecheckGuard.hdmiUnplugged(plugState = -1, hdmiOutputListed = false))
    }

    @Test
    fun tvInputSwitchAwayAndBackBringsTheBitstreamBack() {
        val away = decide(everything, nothing, bitstream = true, unplugged = true)
        assertEquals(Decision.APPLY, away)
        val back = decide(nothing, everything, bitstream = false, unplugged = false)
        assertEquals(Decision.APPLY, back)
        assertFalse(everything.resolution.policy.deniesPassthrough(MimeTypes.AUDIO_TRUEHD))
    }

    @Test
    fun playerBuiltWhileHdmiWasGoneStillRebuildsWhenItReturns() {
        assertEquals(Decision.APPLY, decide(nothing, everything, bitstream = false, unplugged = false))
        val hdmi = setOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_DOLBY_TRUEHD)
        assertTrue(
            PassthroughOutputReturn.shouldRebuild(
                encoding = C.ENCODING_AC3,
                pinned = emptySet(),
                live = hdmi,
                alreadyBitstream = false,
                claimedByIec = false,
                policyDenies = everything.resolution.policy.deniesPassthrough(MimeTypes.AUDIO_AC3),
                forcedPcm = false,
                rebuildsSoFar = 0
            )
        )
    }

    @Test
    fun passableGroupsFollowLearnedRefusals() {
        val learned = AudioPassthroughPolicy(learnedDeniedGroups = setOf(AudioPassthroughPolicy.Group.TRUEHD))
        assertFalse(AudioPassthroughPolicy.Group.TRUEHD in SurroundRecheckGuard.passableGroups(learned))
        assertEquals(5, SurroundRecheckGuard.passableGroups(AudioPassthroughPolicy.ALLOW_ALL).size)
    }

    private val receiverList = setOf(
        AudioFormat.ENCODING_AC3,
        AudioFormat.ENCODING_E_AC3,
        AudioFormat.ENCODING_DOLBY_TRUEHD,
        AudioFormat.ENCODING_DTS,
        AudioFormat.ENCODING_DTS_HD
    )
    private val tvList = setOf(
        AudioFormat.ENCODING_AC3,
        AudioFormat.ENCODING_E_AC3
    )
    private val tvOnly = result(AudioPassthroughPolicy(allowTrueHd = false, allowDts = false, allowDtsHd = false))

    @Test
    fun receiverSwitchedOffWithTheCableInFallsBack() {
        val lost = SurroundRecheckGuard.plugListLostFormats(atApply = receiverList, now = tvList)
        assertTrue(lost)
        assertEquals(
            Decision.APPLY,
            SurroundRecheckGuard.decide(
                everything, tvOnly, bitstreamPlaying = true, hdmiUnplugged = false, plugListLostFormats = lost
            )
        )
    }

    @Test
    fun blipWithAnUnchangedPlugReportKeepsTheBitstream() {
        val lost = SurroundRecheckGuard.plugListLostFormats(atApply = receiverList, now = receiverList)
        assertFalse(lost)
        assertEquals(
            Decision.KEEP_FOR_BITSTREAM,
            SurroundRecheckGuard.decide(
                everything, tvOnly, bitstreamPlaying = true, hdmiUnplugged = false, plugListLostFormats = lost
            )
        )
    }

    @Test
    fun receiverSwitchedOnAddsItsFormats() {
        val lost = SurroundRecheckGuard.plugListLostFormats(atApply = tvList, now = receiverList)
        assertFalse(lost)
        assertEquals(
            Decision.APPLY,
            SurroundRecheckGuard.decide(
                tvOnly, everything, bitstreamPlaying = true, hdmiUnplugged = false, plugListLostFormats = lost
            )
        )
        assertEquals(Decision.APPLY, decide(tvOnly, everything, bitstream = false, unplugged = false))
    }

    @Test
    fun aPcmEntryGoneFromThePlugListIsNotALostFormat() {
        val withPcm = receiverList + AudioFormat.ENCODING_PCM_16BIT + AudioFormat.ENCODING_PCM_FLOAT
        assertFalse(SurroundRecheckGuard.plugListLostFormats(atApply = withPcm, now = receiverList))
        assertTrue(SurroundRecheckGuard.plugListLostFormats(atApply = withPcm, now = tvList))
    }

    @Test
    fun missingPlugListNeverCountsAsLost() {
        assertFalse(SurroundRecheckGuard.plugListLostFormats(atApply = null, now = tvList))
        assertFalse(SurroundRecheckGuard.plugListLostFormats(atApply = receiverList, now = null))
        assertFalse(SurroundRecheckGuard.plugListLostFormats(atApply = null, now = null))
    }

    @Test
    fun tvInputReturnIsConfirmedAfterOneSecond() {
        val delay = SurroundRecheckGuard.firstConfirmDelayMs(
            appliedWhileUnplugged = true,
            pluggedNow = true,
            onlyAddsFormats = SurroundRecheckGuard.onlyAddsFormats(nothing, everything)
        )
        assertEquals(SurroundRecheckGuard.RETURN_CONFIRM_DELAY_MS, delay)
        val eventToFirstReadingMs = 700L
        assertTrue(eventToFirstReadingMs + delay <= 2_000L)
    }

    @Test
    fun everyOtherChangeWaitsTheFullInterval() {
        assertEquals(
            SurroundRecheckGuard.CONFIRM_DELAY_MS,
            SurroundRecheckGuard.firstConfirmDelayMs(appliedWhileUnplugged = false, pluggedNow = true, onlyAddsFormats = true)
        )
        assertEquals(
            SurroundRecheckGuard.CONFIRM_DELAY_MS,
            SurroundRecheckGuard.firstConfirmDelayMs(appliedWhileUnplugged = true, pluggedNow = false, onlyAddsFormats = true)
        )
        assertEquals(
            SurroundRecheckGuard.CONFIRM_DELAY_MS,
            SurroundRecheckGuard.firstConfirmDelayMs(
                appliedWhileUnplugged = true,
                pluggedNow = true,
                onlyAddsFormats = SurroundRecheckGuard.onlyAddsFormats(everything, tvOnly)
            )
        )
    }

    @Test
    fun onlyTheFirstConfirmingReadingIsShortened() = runBlocking<Unit> {
        val waits = mutableListOf<Long>()
        var delayMs = SurroundRecheckGuard.RETURN_CONFIRM_DELAY_MS
        val later = ArrayDeque(listOf("ac3 only", "all", "all"))
        val settled = SurroundRecheckGuard.settledReading(
            first = "all",
            read = { later.removeFirst() },
            wait = {
                waits += delayMs
                delayMs = SurroundRecheckGuard.CONFIRM_DELAY_MS
            }
        )
        assertEquals("all", settled)
        assertEquals(
            listOf(
                SurroundRecheckGuard.RETURN_CONFIRM_DELAY_MS,
                SurroundRecheckGuard.CONFIRM_DELAY_MS,
                SurroundRecheckGuard.CONFIRM_DELAY_MS
            ),
            waits
        )
    }

    private fun decide(
        current: SurroundResolveResult,
        next: SurroundResolveResult,
        bitstream: Boolean,
        unplugged: Boolean
    ): Decision = SurroundRecheckGuard.decide(current, next, bitstream, unplugged)

    private fun result(policy: AudioPassthroughPolicy, channelTarget: Int? = null) = SurroundResolveResult(
        resolution = SurroundFormatResolver.Resolution(
            policy = policy,
            transcodePreferred = false,
            inferredChannelTarget = channelTarget
        ),
        targetChannels = channelTarget,
        downmixEnabled = channelTarget != null,
        audioOutputChannels = AudioOutputChannels.CHANNELS_5_1,
        deniedTranscodeMimes = emptySet()
    )
}
