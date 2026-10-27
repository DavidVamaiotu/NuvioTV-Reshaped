package com.nuvio.tv.ui.screens.player.seekpreview.torbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TorboxLibrarySetupTest {
    private val hash = "a".repeat(40)
    private val library = listOf(hash to "NuvioPreviews-test")
    @Test fun onlyMissingPublishedLibrariesAreAdded() = runBlocking {
        var added = emptyList<String>()
        val setup = TorboxLibrarySetup { 0L }
        assertEquals(TorboxLibrarySetup.Result.READY, setup.ensure("account", { false }, { library },
            { setOf(hash) }, { h, _ -> added = added + h; true }))
        assertTrue(added.isEmpty())
        assertEquals(TorboxLibrarySetup.Result.ADDED, setup.ensure("another", { false }, { library + library },
            { emptySet() }, { h, _ -> added = added + h; true }))
        assertEquals(listOf(hash), added)
    }
    @Test fun concurrentSettingsAndMovieSetupShareOneAttempt() = runBlocking {
        var reads = 0; var additions = 0
        val setup = TorboxLibrarySetup { 0L }
        suspend fun ensure() = setup.ensure("account", { false }, { reads++; delay(10); library },
            { emptySet() }, { _, _ -> additions++; true })
        val a = async { ensure() }; val b = async { ensure() }
        assertEquals(TorboxLibrarySetup.Result.ADDED, a.await())
        assertEquals(TorboxLibrarySetup.Result.ADDED, b.await())
        assertEquals(1, reads); assertEquals(1, additions)
    }
    @Test fun successCacheExpiresAndAccountsRemainIndependent() = runBlocking {
        var now = 0L; var reads = 0
        val setup = TorboxLibrarySetup { now }
        suspend fun ensure(account: String) = setup.ensure(account, { false }, { reads++; library },
            { setOf(hash) }, { _, _ -> fail("Already present"); false })
        ensure("a"); ensure("a"); ensure("b"); assertEquals(2, reads)
        now = 600_001; ensure("a"); assertEquals(3, reads)
    }
    @Test fun failedSetupIsThrottledThenRetries() = runBlocking {
        var now = 0L; var tries = 0
        val setup = TorboxLibrarySetup { now }
        suspend fun ensure() = setup.ensure("a", { false }, { tries++; error("Network") },
            { emptySet() }, { _, _ -> true })
        assertEquals(TorboxLibrarySetup.Result.FAILED, ensure())
        now = 59_999; ensure(); assertEquals(1, tries)
        now = 60_001; ensure(); assertEquals(2, tries)
    }
    @Test fun retryAfterGatePreventsAnyRequestEvenAfterSuccessfulSetup() = runBlocking {
        var blocked = true; var reads = 0
        val setup = TorboxLibrarySetup { 0L }
        suspend fun ensure() = setup.ensure("a", { blocked }, { reads++; library },
            { setOf(hash) }, { _, _ -> true })
        assertEquals(TorboxLibrarySetup.Result.RATE_LIMITED, ensure()); assertEquals(0, reads)
        blocked = false; assertEquals(TorboxLibrarySetup.Result.READY, ensure())
        blocked = true; assertEquals(TorboxLibrarySetup.Result.RATE_LIMITED, ensure()); assertEquals(1, reads)
    }
    @Test fun cancellationDoesNotRememberAnIncompleteSetup() = runBlocking {
        val setup = TorboxLibrarySetup { 0L }
        try { setup.ensure("a", { false }, { throw CancellationException() }, { emptySet() }, { _, _ -> true }); fail() }
        catch (_: CancellationException) { }
        assertEquals(TorboxLibrarySetup.Result.ADDED, setup.ensure("a", { false }, { library },
            { emptySet() }, { _, _ -> true }))
    }
}
