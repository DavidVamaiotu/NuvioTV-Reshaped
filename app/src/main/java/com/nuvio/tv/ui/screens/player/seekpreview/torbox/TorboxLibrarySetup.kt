package com.nuvio.tv.ui.screens.player.seekpreview.torbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/** Account-scoped, serialized setup. Stores account digests, never API keys or download links. */
internal class TorboxLibrarySetup(private val now: () -> Long) {
    enum class Result { READY, ADDED, UNAVAILABLE, FAILED, RATE_LIMITED }
    private data class Attempt(val result: Result, val until: Long)
    private val mutex = Mutex()
    private val attempts = linkedMapOf<String, Attempt>()

    suspend fun ensure(
        account: String,
        blocked: () -> Boolean,
        published: suspend () -> List<Pair<String, String>>,
        existing: suspend (Set<String>) -> Set<String>,
        add: suspend (String, String) -> Boolean,
    ): Result = mutex.withLock {
        if (account.isBlank()) return@withLock Result.FAILED
        if (blocked()) return@withLock Result.RATE_LIMITED
        attempts[account]?.takeIf { now() < it.until }?.let { return@withLock it.result }
        val result = try {
            val wanted = published().distinctBy { it.first }
            if (wanted.isEmpty()) Result.UNAVAILABLE else {
                val present = existing(wanted.map { it.first }.toSet())
                var ready = true
                var added = false
                for ((hash, name) in wanted) {
                    coroutineContext.ensureActive()
                    if (hash !in present) {
                        if (add(hash, name)) added = true else ready = false
                    }
                }
                if (!ready) Result.UNAVAILABLE else if (added) Result.ADDED else Result.READY
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { if (blocked()) Result.RATE_LIMITED else Result.FAILED }
        attempts[account] = Attempt(result, now() + if (result == Result.READY) 600_000L else 60_000L)
        while (attempts.size > 8) attempts.remove(attempts.keys.first())
        result
    }
}
