package com.nuvio.tv.ui.reshaped.netflix

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NetflixEnrichmentTest {
    private val item = MetaPreview(
        id = "tt1", type = ContentType.MOVIE, name = "Title", poster = "custom-poster",
        posterShape = PosterShape.POSTER, background = null, logo = null, description = null,
        releaseInfo = "2026", imdbRating = null, genres = emptyList(),
        landscapePoster = "custom-landscape", sourceAddonBaseUrl = "catalog-addon"
    )

    @Test
    fun `new enrichment updates details and artwork without replacing catalog poster or source`() {
        val enriched = item.copy(
            poster = "other-poster", landscapePoster = "other-landscape", background = "backdrop",
            logo = "logo", description = "Synopsis", ageRating = "PG", seasonCount = 2,
            sourceAddonBaseUrl = "meta-addon"
        )
        val shown = netflixEnrichedPreview(item, mapOf(item.id to enriched))
        assertEquals("Synopsis", shown.description)
        assertEquals("backdrop", shown.background)
        assertEquals("logo", shown.logo)
        assertEquals("PG", shown.ageRating)
        assertEquals(2, shown.seasonCount)
        assertEquals(item.poster, shown.poster)
        assertEquals(item.landscapePoster, shown.landscapePoster)
        assertEquals(item.sourceAddonBaseUrl, shown.sourceAddonBaseUrl)
    }

    @Test
    fun `a later enrichment replaces the focused title details`() {
        val first = item.copy(description = "First")
        val second = item.copy(description = "Second")
        assertEquals("First", netflixEnrichedPreview(item, mapOf(item.id to first)).description)
        assertEquals("Second", netflixEnrichedPreview(item, mapOf(item.id to second)).description)
    }

    @Test
    fun `missing or different media type enrichment leaves the catalog item intact`() {
        assertSame(item, netflixEnrichedPreview(item, emptyMap()))
        assertSame(item, netflixEnrichedPreview(item, mapOf(item.id to item.copy(type = ContentType.SERIES, rawType = "series"))))
    }
}
