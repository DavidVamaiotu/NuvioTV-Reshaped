package com.nuvio.tv.ui.screens.player.seekpreview.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes native original/copy decoding on small devices; waiting never occupies a thread. */
internal class PreviewSessionDecodeGate {
    private val mutex = Mutex()

    suspend fun <T> run(serial: Boolean, block: () -> T): T =
        if (serial) mutex.withLock { block() } else block()
}
