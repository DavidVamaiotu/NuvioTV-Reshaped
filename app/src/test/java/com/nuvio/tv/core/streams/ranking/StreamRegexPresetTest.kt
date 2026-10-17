package com.nuvio.tv.core.streams.ranking

import org.junit.Assert.*
import org.junit.Test

class StreamRegexPresetTest {
    @Test fun `all shared presets compile and survive ordered storage`() {
        val patterns = StreamRegexPreset.entries.map { it.pattern }
        assertEquals(13, patterns.size)
        assertNotNull(RankedRegexRules.create(patterns))
        assertEquals(patterns, RankedRegexSettingsCodec.decode(RankedRegexSettingsCodec.encode(patterns)))
    }

    @Test fun `quality codec and language presets match their advertised examples`() {
        val examples = listOf(
            StreamRegexPreset.ANY_1080P to "2160p",
            StreamRegexPreset.QUALITY_4K_REMUX to "REMUX",
            StreamRegexPreset.FULL_HD_STANDARD to "FULL HD",
            StreamRegexPreset.HD_SMALLER to "720p",
            StreamRegexPreset.WEB_SOURCES to "WEB-DL",
            StreamRegexPreset.BLURAY_QUALITY to "BluRay",
            StreamRegexPreset.HEVC_X265 to "H.265",
            StreamRegexPreset.AVC_X264 to "H.264",
            StreamRegexPreset.HDR_DV to "Dolby Vision",
            StreamRegexPreset.DOLBY_ATMOS_DTS to "DTS-HD",
            StreamRegexPreset.ENGLISH to "eng"
        )
        for ((preset, sample) in examples) {
            assertEquals(preset.name, listOf(sample), RankedRegexRules.create(listOf(preset.pattern))!!.rank(listOf(sample)) { it })
        }
    }

    @Test fun `exclusion presets reject unwanted sources when used as a tier`() {
        val noCam = RankedRegexRules.create(listOf(StreamRegexPreset.NO_CAM_TS.pattern))!!
        assertEquals(listOf("1080p WEB-DL"), noCam.rank(listOf("1080p HDCAM", "1080p WEB-DL")) { it })
        val noHdr = RankedRegexRules.create(listOf(StreamRegexPreset.NO_REMUX_HDR.pattern))!!
        assertEquals(listOf("1080p AVC"), noHdr.rank(listOf("2160p HDR", "1080p AVC")) { it })
    }
}
