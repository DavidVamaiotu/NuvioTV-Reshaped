// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

/** A title's thumbnails are only made while the player screen is in front; Home or another app makes them wait. */
internal object ForegroundGate {
    /** The pass that finishes after the player closed is not tied to the screen. */
    fun waits(hostResumed: Boolean, playerClosed: Boolean): Boolean = !hostResumed && !playerClosed

    /**
     * Moves a generating-screen clock on by the time away from [awayFromMs] to [nowMs], so being away never counts as
     * a stall. 0 = not set.
     */
    fun shifted(atMs: Long, awayFromMs: Long, nowMs: Long): Long =
        if (atMs <= 0L || nowMs <= awayFromMs) atMs else atMs + nowMs - maxOf(atMs, awayFromMs)
}
