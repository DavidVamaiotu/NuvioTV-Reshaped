package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTunnelAvSyncPolicyTest {

    private fun baseInput() = PlayerTunnelAvSyncPolicy.Input(
        isTunnelingActive = true,
        isTunnelingApplied = true,
        hasVideoTrack = true,
        isReady = true,
        playWhenReady = true,
        userPausedManually = false,
        positionMs = 0L,
        bufferedPositionMs = 50_000L,
        lastPositionMs = 0L,
        stalledMs = 0L,
        intervalMs = 1_000L,
        stallThresholdMs = 5_000L,
        renderedOutputBufferCount = 1,
        frameReportsTrusted = true,
        readyMs = 0L,
        noFrameThresholdMs = 8_000L,
        tunnelingAlreadyDisarmed = false,
    )

    private fun advancingInput() = baseInput().copy(positionMs = 1_000L, lastPositionMs = 0L)

    @Test
    fun frozenPositionWithDataBuffered_accumulatesUntilThreshold() {
        var stalled = 0L
        repeat(4) {
            val result = PlayerTunnelAvSyncPolicy.evaluate(baseInput().copy(stalledMs = stalled))
            assertEquals(PlayerTunnelAvSyncPolicy.Decision.None, result.decision)
            stalled = result.stalledMs
        }
        assertEquals(4_000L, stalled)
        val fifth = PlayerTunnelAvSyncPolicy.evaluate(baseInput().copy(stalledMs = stalled))
        assertEquals(PlayerTunnelAvSyncPolicy.Decision.DisableTunnelingAndRebuild, fifth.decision)
        assertEquals(5_000L, fifth.stalledMs)
        assertEquals(PlayerTunnelAvSyncPolicy.Reason.PositionFrozen, fifth.reason)
    }

    @Test
    fun advancingPosition_resetsTheStall() {
        val result = PlayerTunnelAvSyncPolicy.evaluate(
            baseInput().copy(positionMs = 1_200L, lastPositionMs = 0L, stalledMs = 4_000L)
        )
        assertEquals(PlayerTunnelAvSyncPolicy.Decision.None, result.decision)
        assertEquals(0L, result.stalledMs)
    }

    @Test
    fun firstSample_hasNothingToCompare() {
        val result = PlayerTunnelAvSyncPolicy.evaluate(baseInput().copy(lastPositionMs = null))
        assertEquals(PlayerTunnelAvSyncPolicy.Decision.None, result.decision)
        assertEquals(0L, result.stalledMs)
    }

    @Test
    fun frozenPositionWithoutDataAhead_isANetworkStallNotTheTunnel() {
        val result = PlayerTunnelAvSyncPolicy.evaluate(
            baseInput().copy(bufferedPositionMs = 0L, stalledMs = 4_000L)
        )
        assertEquals(PlayerTunnelAvSyncPolicy.Decision.None, result.decision)
        assertEquals(0L, result.stalledMs)
    }

    @Test
    fun notReadyOrNotPlaying_resetsWithoutDeciding() {
        for (input in listOf(
            baseInput().copy(isReady = false, stalledMs = 4_000L, readyMs = 7_000L),
            baseInput().copy(playWhenReady = false, stalledMs = 4_000L, readyMs = 7_000L),
            baseInput().copy(userPausedManually = true, stalledMs = 4_000L, readyMs = 7_000L),
        )) {
            val result = PlayerTunnelAvSyncPolicy.evaluate(input)
            assertEquals(PlayerTunnelAvSyncPolicy.Decision.None, result.decision)
            assertEquals(0L, result.stalledMs)
            assertEquals(0L, result.readyMs)
        }
    }

    @Test
    fun tunnelingOffOrNoVideoOrDisarmed_stopsTheWatchdog() {
        for (input in listOf(
            baseInput().copy(isTunnelingActive = false),
            baseInput().copy(hasVideoTrack = false),
            baseInput().copy(tunnelingAlreadyDisarmed = true),
        )) {
            assertEquals(PlayerTunnelAvSyncPolicy.Decision.Stop, PlayerTunnelAvSyncPolicy.evaluate(input).decision)
        }
    }

    @Test
    fun noRenderedFramesWhileReady_accumulatesUntilThreshold() {
        var ready = 0L
        repeat(7) {
            val result = PlayerTunnelAvSyncPolicy.evaluate(
                advancingInput().copy(renderedOutputBufferCount = 0, readyMs = ready)
            )
            assertEquals(PlayerTunnelAvSyncPolicy.Decision.None, result.decision)
            ready = result.readyMs
        }
        assertEquals(7_000L, ready)
        val eighth = PlayerTunnelAvSyncPolicy.evaluate(
            advancingInput().copy(renderedOutputBufferCount = 0, readyMs = ready)
        )
        assertEquals(PlayerTunnelAvSyncPolicy.Decision.DisableTunnelingAndRebuild, eighth.decision)
        assertEquals(8_000L, eighth.readyMs)
        assertEquals(PlayerTunnelAvSyncPolicy.Reason.NoFramesRendered, eighth.reason)
    }

    @Test
    fun renderedFramesOrUnknownCounters_keepTheFrameLegQuiet() {
        for (count in listOf(1, 250, null)) {
            val result = PlayerTunnelAvSyncPolicy.evaluate(
                advancingInput().copy(renderedOutputBufferCount = count, readyMs = 20_000L)
            )
            assertEquals(PlayerTunnelAvSyncPolicy.Decision.None, result.decision)
            assertEquals(21_000L, result.readyMs)
            assertNull(result.reason)
        }
    }

    @Test
    fun frozenPosition_isReportedAheadOfMissingFrames() {
        val result = PlayerTunnelAvSyncPolicy.evaluate(
            baseInput().copy(renderedOutputBufferCount = 0, stalledMs = 4_000L, readyMs = 9_000L)
        )
        assertEquals(PlayerTunnelAvSyncPolicy.Decision.DisableTunnelingAndRebuild, result.decision)
        assertEquals(PlayerTunnelAvSyncPolicy.Reason.PositionFrozen, result.reason)
    }

    @Test
    fun tunnelingRequestedButNotApplied_stopsWithoutDisablingAnything() {
        val frozen = PlayerTunnelAvSyncPolicy.evaluate(
            baseInput().copy(isTunnelingApplied = false, stalledMs = 4_000L)
        )
        assertEquals(PlayerTunnelAvSyncPolicy.Decision.Stop, frozen.decision)
        assertNull(frozen.reason)

        val noFrames = PlayerTunnelAvSyncPolicy.evaluate(
            advancingInput().copy(isTunnelingApplied = false, renderedOutputBufferCount = 0, readyMs = 20_000L)
        )
        assertEquals(PlayerTunnelAvSyncPolicy.Decision.Stop, noFrames.decision)
        assertNull(noFrames.reason)
    }

    @Test
    fun noFramesReported_isIgnoredUntilTheDeviceHasReportedATunnelledFrame() {
        val result = PlayerTunnelAvSyncPolicy.evaluate(
            advancingInput().copy(renderedOutputBufferCount = 0, readyMs = 20_000L, frameReportsTrusted = false)
        )
        assertEquals(PlayerTunnelAvSyncPolicy.Decision.None, result.decision)
        assertNull(result.reason)
    }

    @Test
    fun frozenPosition_stillDisablesTunnelingWithoutFrameReports() {
        val result = PlayerTunnelAvSyncPolicy.evaluate(
            baseInput().copy(stalledMs = 4_000L, renderedOutputBufferCount = 0, frameReportsTrusted = false)
        )
        assertEquals(PlayerTunnelAvSyncPolicy.Decision.DisableTunnelingAndRebuild, result.decision)
        assertEquals(PlayerTunnelAvSyncPolicy.Reason.PositionFrozen, result.reason)
    }

    @Test
    fun stallMemo_needsTenSecondsOfPlainUntunnelledPlayback() {
        var played = 0L
        var position = 60_000L
        repeat(9) {
            played += PlayerTunnelAvSyncPolicy.memoAdvanceMs(position, position + 1_000L, 1_000L)
            position += 1_000L
        }
        assertFalse(PlayerTunnelAvSyncPolicy.memoConfirmed(played))
        played += PlayerTunnelAvSyncPolicy.memoAdvanceMs(position, position + 1_000L, 1_000L)
        assertTrue(PlayerTunnelAvSyncPolicy.memoConfirmed(played))
    }

    @Test
    fun stallMemo_seeksAndFreezesDoNotCount() {
        assertEquals(0L, PlayerTunnelAvSyncPolicy.memoAdvanceMs(60_000L, 90_000L, 1_000L))
        assertEquals(0L, PlayerTunnelAvSyncPolicy.memoAdvanceMs(60_000L, 30_000L, 1_000L))
        assertEquals(0L, PlayerTunnelAvSyncPolicy.memoAdvanceMs(60_000L, 60_000L, 1_000L))
        assertEquals(1_900L, PlayerTunnelAvSyncPolicy.memoAdvanceMs(60_000L, 61_900L, 1_000L))
    }

    @Test
    fun frameReportKey_needsADecoderName() {
        assertEquals("fp|OMX.amlogic.hevc", PlayerTunnelAvSyncPolicy.frameReportKey("fp", "OMX.amlogic.hevc"))
        assertNull(PlayerTunnelAvSyncPolicy.frameReportKey("fp", null))
        assertNull(PlayerTunnelAvSyncPolicy.frameReportKey("fp", " "))
    }

    @Test
    fun stallRightAfterAnOutputChange_isNotRemembered() {
        assertTrue(PlayerTunnelAvSyncPolicy.remembersStall(nowMs = 100_000L, lastOutputChangeMs = 0L))
        assertFalse(PlayerTunnelAvSyncPolicy.remembersStall(nowMs = 100_000L, lastOutputChangeMs = 95_000L))
        assertFalse(PlayerTunnelAvSyncPolicy.remembersStall(nowMs = 100_000L, lastOutputChangeMs = 87_001L))
        assertTrue(PlayerTunnelAvSyncPolicy.remembersStall(nowMs = 100_000L, lastOutputChangeMs = 87_000L))
    }

    @Test
    fun firstFrozenStallInAPlay_reloadsWithTunnelKept_secondTurnsItOff() {
        val frozen = PlayerTunnelAvSyncPolicy.Reason.PositionFrozen
        assertEquals(
            PlayerTunnelAvSyncPolicy.StallResponse.ReloadKeepingTunnel,
            PlayerTunnelAvSyncPolicy.stallResponse(frozen, "https://a", null)
        )
        val strike = PlayerTunnelAvSyncPolicy.StallStrike("https://a", 0L)
        assertEquals(
            PlayerTunnelAvSyncPolicy.StallResponse.DisableTunneling,
            PlayerTunnelAvSyncPolicy.stallResponse(frozen, "https://a", strike)
        )
        assertEquals(
            PlayerTunnelAvSyncPolicy.StallResponse.ReloadKeepingTunnel,
            PlayerTunnelAvSyncPolicy.stallResponse(frozen, "https://b", strike)
        )
    }

    @Test
    fun noFramesRendered_turnsTunnelOffStraightAway() {
        val noFrames = PlayerTunnelAvSyncPolicy.Reason.NoFramesRendered
        assertEquals(
            PlayerTunnelAvSyncPolicy.StallResponse.DisableTunneling,
            PlayerTunnelAvSyncPolicy.stallResponse(noFrames, "https://a", null)
        )
        assertEquals(
            PlayerTunnelAvSyncPolicy.StallResponse.DisableTunneling,
            PlayerTunnelAvSyncPolicy.stallResponse(null, "https://a", null)
        )
    }

    @Test
    fun strike_survivesReloadOfTheSameStream_andAnotherStreamStartsFresh() {
        val strike = PlayerTunnelAvSyncPolicy.StallStrike("https://a", 12_000L)
        assertEquals(strike, PlayerTunnelAvSyncPolicy.strikeForStream("https://a", strike))
        assertEquals(12_000L, PlayerTunnelAvSyncPolicy.strikeForStream("https://a", strike)?.firstReadyPositionMs)
        assertNull(PlayerTunnelAvSyncPolicy.strikeForStream("https://b", strike))
        assertNull(PlayerTunnelAvSyncPolicy.strikeForStream("https://a", null))
        val afterSwitchBack = PlayerTunnelAvSyncPolicy.strikeForStream(
            "https://a", PlayerTunnelAvSyncPolicy.strikeForStream("https://b", strike)
        )
        assertEquals(
            PlayerTunnelAvSyncPolicy.StallResponse.ReloadKeepingTunnel,
            PlayerTunnelAvSyncPolicy.stallResponse(
                PlayerTunnelAvSyncPolicy.Reason.PositionFrozen, "https://a", afterSwitchBack
            )
        )
    }
}
