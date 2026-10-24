package com.nuvio.tv.core.streams.ranking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Keeps expensive custom matching off the UI thread; cancellation belongs to the caller. */
object RankedRegexWork {
    suspend fun <T> run(enabled: Boolean, work: (checkpoint: () -> Unit) -> T): T {
        if (!enabled) return work {}
        return withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            context.ensureActive()
            val result = work { context.ensureActive() }
            context.ensureActive()
            result
        }
    }
}
