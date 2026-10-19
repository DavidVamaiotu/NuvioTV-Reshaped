package com.nuvio.tv.ui.screens.player.seekpreview.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/** One outstanding image, including the one being written. Backpressure suspends only the preview worker. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
internal class PreviewFrameWriter(dispatcher: CoroutineDispatcher, private val onFailure: (Throwable) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val permit = Semaphore(1)
    private var pending: Job? = null
    private var closed = false

    suspend fun <T> submit(publish: () -> T, persist: (T) -> Unit) {
        check(!closed) { "preview writer is closed" }
        permit.acquire()
        val frame = try { publish() } catch (failure: Throwable) { permit.release(); throw failure }
        // ATOMIC guarantees release even if shutdown races dispatch. No queue of captured bitmaps.
        pending = scope.launch(start = CoroutineStart.ATOMIC) {
            try { persist(frame) } catch (failure: Throwable) { onFailure(failure) }
            finally { permit.release() }
        }
    }

    /** Called by the owning session in its NonCancellable cleanup, before flushing or releasing its store. */
    suspend fun drain() { pending?.join() }

    suspend fun finish() {
        closed = true
        try { drain() } finally { scope.cancel(); pending = null }
    }
}
