package com.nuvio.tv.ui.reshaped.netflix

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.Density
import coil3.ImageLoader
import coil3.memory.MemoryCache
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NetflixArtPreloaderTest {
    @Test
    fun `leaving a row and disposing it never evicts artwork already in the shared cache`() = runTest {
        val cache = mockk<MemoryCache>(relaxed = true)
        every { cache[any()] } returns mockk(relaxed = true)
        val loader = mockk<ImageLoader> { every { memoryCache } returns cache }
        val center = mutableStateOf<Int?>(1)
        val items = (0..2).map { index ->
            MetaPreview(
                id = "$index", type = ContentType.MOVIE, name = "Title", poster = "poster",
                posterShape = PosterShape.POSTER, background = "art-$index", logo = null,
                description = null, releaseInfo = null, imdbRating = null, genres = emptyList()
            )
        }
        val job = launch {
            netflixPreloadRowArt(mockk<Context>(), Density(1f), { items }, { center.value }, loader)
        }
        runCurrent()
        advanceTimeBy(NetflixTokens.PRELOAD_DWELL_MS + 1)
        runCurrent()
        // Both neighbors really were visited; this would fail if the collector never ran.
        verify(exactly = 2) { cache[any()] }
        center.value = null
        Snapshot.sendApplyNotifications()
        runCurrent()
        job.cancelAndJoin()
        verify(exactly = 0) { cache.remove(any()) }
        verify(exactly = 0) { loader.enqueue(any()) }
    }
}
