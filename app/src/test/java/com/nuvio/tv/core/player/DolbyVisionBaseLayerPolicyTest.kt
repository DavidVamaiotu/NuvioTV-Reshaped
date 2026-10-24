package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.DolbyVisionBaseLayerPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DolbyVisionBaseLayerPolicyTest {

    private fun resolve(
        hdrCapsKnown: Boolean = true,
        displayDv: Boolean = false,
        displayHdr10: Boolean = false,
        displayHdr10Plus: Boolean = false,
        displayHlg: Boolean = false,
        codecSupportsDvheDtb: Boolean = false,
        codecSupportsDvheStn: Boolean = false,
        codecSupportsDvheSt: Boolean = false,
        isAmazonFireTv: Boolean = false,
        isSamsung: Boolean = false,
        isXiaomi: Boolean = false,
        bridgeReady: Boolean = false,
        apiLevel: Int = 30
    ) = DolbyVisionBaseLayerPolicy.resolveFromCapabilities(
        hdrCapsKnown = hdrCapsKnown,
        displayDv = displayDv,
        displayHdr10 = displayHdr10,
        displayHdr10Plus = displayHdr10Plus,
        displayHlg = displayHlg,
        codecSupportsDvheDtb = codecSupportsDvheDtb,
        codecSupportsDvheStn = codecSupportsDvheStn,
        codecSupportsDvheSt = codecSupportsDvheSt,
        isAmazonFireTv = isAmazonFireTv,
        isSamsung = isSamsung,
        isXiaomi = isXiaomi,
        bridgeReady = bridgeReady,
        apiLevel = apiLevel
    )

    // ── Unknown caps ──

    @Test
    fun `unknown caps returns STRIP_BEST_EFFORT regardless of other flags`() {
        val r = resolve(
            hdrCapsKnown = false,
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheDtb = true,
            codecSupportsDvheSt = true,
            isAmazonFireTv = true,
            bridgeReady = true
        )
        assertEquals(Decision.STRIP_BEST_EFFORT, r.decision)
        assertTrue(r.divertsFromNativeDv7)
        assertTrue(r.mapToHevc)
    }

    // ── DV display: always convert, a listed Profile 7 decoder does not change that ──

    @Test
    fun `DV display with a decoder listing Profile 7 converts`() {
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheDtb = true,
            bridgeReady = true
        )
        assertEquals(Decision.CONVERT_TO_DV81, r.decision)
        assertTrue(r.divertsFromNativeDv7)
        assertFalse(r.mapToHevc)
    }

    @Test
    fun `DV display with decoders listing Profile 7 and 8 converts`() {
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheDtb = true,
            codecSupportsDvheSt = true,
            isAmazonFireTv = true,
            bridgeReady = true
        )
        assertEquals(Decision.CONVERT_TO_DV81, r.decision)
    }

    @Test
    fun `built-in TV on DV display without a listed Profile 8 decoder converts`() {
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            displayHdr10Plus = true,
            codecSupportsDvheSt = false,
            bridgeReady = true,
            apiLevel = 34
        )
        assertEquals(Decision.CONVERT_TO_DV81, r.decision)
    }

    @Test
    fun `DV display without bridge strips to HDR10 instead of passing DV7 through`() {
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheDtb = true,
            codecSupportsDvheSt = true,
            bridgeReady = false
        )
        assertEquals(Decision.STRIP_TO_HDR10, r.decision)
        assertTrue(r.mapToHevc)
    }

    @Test
    fun `no capability combination returns NATIVE_DV7`() {
        val flags = listOf(true, false)
        for (caps in flags) for (dv in flags) for (hdr10 in flags) for (dtb in flags) for (st in flags)
            for (amazon in flags) for (samsung in flags) for (xiaomi in flags) for (bridge in flags) {
                val r = resolve(
                    hdrCapsKnown = caps,
                    displayDv = dv,
                    displayHdr10 = hdr10,
                    codecSupportsDvheDtb = dtb,
                    codecSupportsDvheSt = st,
                    isAmazonFireTv = amazon,
                    isSamsung = samsung,
                    isXiaomi = xiaomi,
                    bridgeReady = bridge
                )
                assertTrue(r.divertsFromNativeDv7)
                if (caps && dv && bridge) assertEquals(Decision.CONVERT_TO_DV81, r.decision)
            }
    }

    @Test
    fun `Samsung on DV display without a listed Profile 8 decoder converts`() {
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            isSamsung = true,
            bridgeReady = true
        )
        assertEquals(Decision.CONVERT_TO_DV81, r.decision)
    }

    // ── Which conversions take the manual Convert to DV8.1 path ──

    @Test
    fun `decoder listing Profile 8 alone keeps Auto conversion`() {
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheSt = true,
            isAmazonFireTv = true,
            bridgeReady = true
        )
        assertFalse(r.usesManualConversion)
    }

    @Test
    fun `Xiaomi without Profile 7 keeps Auto conversion`() {
        val r = resolve(displayDv = true, displayHdr10 = true, isXiaomi = true, bridgeReady = true)
        assertFalse(r.usesManualConversion)
    }

    @Test
    fun `decoder listing Profile 7 takes the manual path`() {
        val p7 = resolve(displayDv = true, displayHdr10 = true, codecSupportsDvheDtb = true, bridgeReady = true)
        val p7p8 = resolve(
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheDtb = true,
            codecSupportsDvheSt = true,
            bridgeReady = true
        )
        val xiaomiP7 = resolve(
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheDtb = true,
            isXiaomi = true,
            bridgeReady = true
        )
        assertTrue(p7.usesManualConversion)
        assertTrue(p7p8.usesManualConversion)
        assertTrue(xiaomiP7.usesManualConversion)
    }

    @Test
    fun `decoder listing no Profile 8 takes the manual path`() {
        val r = resolve(displayDv = true, displayHdr10 = true, bridgeReady = true, apiLevel = 34)
        assertTrue(r.usesManualConversion)
    }

    @Test
    fun `manual path is only for DV display conversion`() {
        val hdr10Convert = resolve(
            displayHdr10 = true,
            codecSupportsDvheDtb = true,
            codecSupportsDvheSt = true,
            isSamsung = true,
            bridgeReady = true
        )
        val noBridge = resolve(displayDv = true, displayHdr10 = true, codecSupportsDvheDtb = true)
        assertEquals(Decision.CONVERT_TO_DV81, hdr10Convert.decision)
        assertFalse(hdr10Convert.usesManualConversion)
        assertFalse(noBridge.usesManualConversion)
    }

    // ── CONVERT_TO_DV81: Fire TV Karat (Amazon-gated) ──

    @Test
    fun `Fire TV on DV display with DV81 decoder and bridge converts`() {
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            displayHdr10Plus = true,
            codecSupportsDvheSt = true,
            isAmazonFireTv = true,
            bridgeReady = true
        )
        assertEquals(Decision.CONVERT_TO_DV81, r.decision)
        assertTrue(r.divertsFromNativeDv7)
        assertFalse(r.mapToHevc)
    }

    @Test
    fun `Fire TV on DV display without bridge strips to HDR10`() {
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheSt = true,
            isAmazonFireTv = true,
            bridgeReady = false
        )
        assertEquals(Decision.STRIP_TO_HDR10, r.decision)
    }

    @Test
    fun `Fire TV on DV display without a listed DV81 decoder still converts`() {
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheSt = false,
            isAmazonFireTv = true,
            bridgeReady = true
        )
        assertEquals(Decision.CONVERT_TO_DV81, r.decision)
    }

    // ── CONVERT_TO_DV81: Xiaomi Mi Box (relaxed, no codec check) ──

    @Test
    fun `Xiaomi on DV display with bridge converts even without DvheSt`() {
        // Mi Box S: displayDv comes from LG/Samsung TV, codecSupportsDvheSt is false
        // because the Amlogic decoder doesn't advertise Profile 8. Relaxed branch
        // fires on manufacturer + bridge alone.
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheSt = false,
            isXiaomi = true,
            bridgeReady = true
        )
        assertEquals(Decision.CONVERT_TO_DV81, r.decision)
        assertTrue(r.divertsFromNativeDv7)
        assertFalse(r.mapToHevc)
    }

    @Test
    fun `Xiaomi on DV display with DvheSt also converts`() {
        // Future Xiaomi device that DOES advertise Profile 8. The Amazon-gated
        // branch won't fire (not Amazon), but the Xiaomi branch catches it.
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheSt = true,
            isXiaomi = true,
            bridgeReady = true
        )
        assertEquals(Decision.CONVERT_TO_DV81, r.decision)
    }

    @Test
    fun `Xiaomi on DV display without bridge strips to HDR10`() {
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            isXiaomi = true,
            bridgeReady = false
        )
        assertEquals(Decision.STRIP_TO_HDR10, r.decision)
    }

    @Test
    fun `Xiaomi on HDR10 display with bridge converts`() {
        // Mi Box connected to HDR10-only TV: bridge converts so the hidden
        // DV decoder can emit HDR10. Better than STRIP which may fail on
        // some Amlogic firmware.
        val r = resolve(
            displayDv = false,
            displayHdr10 = true,
            isXiaomi = true,
            bridgeReady = true
        )
        assertEquals(Decision.CONVERT_TO_DV81, r.decision)
    }

    @Test
    fun `Xiaomi on HDR10 display without bridge strips to HDR10`() {
        val r = resolve(
            displayDv = false,
            displayHdr10 = true,
            isXiaomi = true,
            bridgeReady = false
        )
        assertEquals(Decision.STRIP_TO_HDR10, r.decision)
    }

    // Current fork: eligible DV-display conversion is not manufacturer-gated.

    @Test
    fun `non-Amazon device on DV display with DV81 decoder and bridge converts`() {
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheSt = true,
            isAmazonFireTv = false,
            isSamsung = false,
            isXiaomi = false,
            bridgeReady = true
        )
        assertEquals(Decision.CONVERT_TO_DV81, r.decision)
        assertTrue(r.divertsFromNativeDv7)
        assertFalse(r.mapToHevc)
    }

    @Test
    fun `Samsung device on DV display with DV81 decoder and bridge converts`() {
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheSt = true,
            isSamsung = true,
            bridgeReady = true
        )
        assertEquals(Decision.CONVERT_TO_DV81, r.decision)
    }

    // ── CONVERT_TO_DV81: HDR10 fallback (Samsung + Amazon) ──

    @Test
    fun `Samsung HDR10 panel with DV81 decoder and bridge converts`() {
        val r = resolve(
            displayDv = false,
            displayHdr10 = true,
            codecSupportsDvheSt = true,
            isSamsung = true,
            bridgeReady = true
        )
        assertEquals(Decision.CONVERT_TO_DV81, r.decision)
        assertTrue(r.divertsFromNativeDv7)
        assertFalse(r.mapToHevc)
    }

    @Test
    fun `Samsung HDR10Plus panel with DV81 decoder and bridge converts`() {
        val r = resolve(
            displayDv = false,
            displayHdr10Plus = true,
            codecSupportsDvheSt = true,
            isSamsung = true,
            bridgeReady = true
        )
        assertEquals(Decision.CONVERT_TO_DV81, r.decision)
    }

    @Test
    fun `Fire TV on HDR10 panel with DV81 decoder and bridge converts`() {
        val r = resolve(
            displayDv = false,
            displayHdr10 = true,
            codecSupportsDvheSt = true,
            isAmazonFireTv = true,
            bridgeReady = true
        )
        assertEquals(Decision.CONVERT_TO_DV81, r.decision)
    }

    // ── STRIP_TO_HDR10: Google TV Streamer + Samsung TV (non-intervened) ──

    @Test
    fun `Generic HDR10 panel with DV81 decoder strips when non-Samsung non-Amazon non-Xiaomi`() {
        val r = resolve(
            displayDv = false,
            displayHdr10 = true,
            codecSupportsDvheSt = true,
            isAmazonFireTv = false,
            isSamsung = false,
            isXiaomi = false,
            bridgeReady = true
        )
        assertEquals(Decision.STRIP_TO_HDR10, r.decision)
        assertTrue(r.mapToHevc)
    }

    @Test
    fun `Samsung HDR10 panel without bridge strips to HDR10`() {
        val r = resolve(
            displayHdr10 = true,
            codecSupportsDvheSt = true,
            isSamsung = true,
            bridgeReady = false
        )
        assertEquals(Decision.STRIP_TO_HDR10, r.decision)
    }

    @Test
    fun `Samsung HDR10 panel with bridge but no DV81 decoder strips to HDR10`() {
        val r = resolve(
            displayHdr10 = true,
            codecSupportsDvheSt = false,
            isSamsung = true,
            bridgeReady = true
        )
        assertEquals(Decision.STRIP_TO_HDR10, r.decision)
    }

    @Test
    fun `HDR10 panel with nothing useful strips to HDR10`() {
        val r = resolve(displayHdr10 = true)
        assertEquals(Decision.STRIP_TO_HDR10, r.decision)
        assertTrue(r.mapToHevc)
    }

    @Test
    fun `HDR10Plus panel with nothing useful strips to HDR10`() {
        val r = resolve(displayHdr10Plus = true)
        assertEquals(Decision.STRIP_TO_HDR10, r.decision)
    }

    // ── STRIP_AND_TONEMAP ──

    @Test
    fun `SDR-only display returns STRIP_AND_TONEMAP`() {
        val r = resolve(codecSupportsDvheSt = true, bridgeReady = true)
        assertEquals(Decision.STRIP_AND_TONEMAP, r.decision)
        assertTrue(r.mapToHevc)
    }

    @Test
    fun `HLG-only display returns STRIP_AND_TONEMAP`() {
        val r = resolve(displayHlg = true, bridgeReady = true)
        assertEquals(Decision.STRIP_AND_TONEMAP, r.decision)
    }

    // ── Result field propagation ──

    @Test
    fun `result preserves all input fields`() {
        val r = resolve(
            hdrCapsKnown = true,
            displayDv = true,
            displayHdr10 = true,
            displayHdr10Plus = true,
            displayHlg = false,
            codecSupportsDvheDtb = false,
            codecSupportsDvheStn = true,
            codecSupportsDvheSt = true,
            isAmazonFireTv = true,
            isSamsung = false,
            isXiaomi = false,
            bridgeReady = true,
            apiLevel = 33
        )
        assertTrue(r.hdrCapsKnown)
        assertTrue(r.displayDv)
        assertTrue(r.displayHdr10)
        assertTrue(r.displayHdr10Plus)
        assertFalse(r.displayHlg)
        assertFalse(r.codecSupportsDvheDtb)
        assertTrue(r.codecSupportsDvheStn)
        assertTrue(r.codecSupportsDvheSt)
        assertTrue(r.isAmazonFireTv)
        assertFalse(r.isSamsung)
        assertFalse(r.isXiaomi)
        assertTrue(r.bridgeReady)
        assertEquals(33, r.apiLevel)
    }
}