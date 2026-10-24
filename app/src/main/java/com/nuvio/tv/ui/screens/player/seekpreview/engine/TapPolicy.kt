// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

/** Which keyframes playback's tap copies to the spool. */
internal object TapPolicy {
    /**
     * [holdsUntilPause]: a small box playing a 4K remux decodes nothing until a pause or the player closes, so a copy
     * waits on flash. Then only coverage slots are copied (the coverage pass would fetch them anyway), none once the
     * coverage is finished (no decode would be funded) and none while the spool is full (the oldest would be deleted
     * unused). Otherwise every slot without a thumbnail is copied: it is decoded soon after.
     */
    fun copies(slot: Int, holdsUntilPause: Boolean, coverageStride: Int, coverageFinished: Boolean, spoolFull: Boolean): Boolean {
        if (!holdsUntilPause) return true
        return !coverageFinished && !spoolFull && slot % coverageStride.coerceAtLeast(1) == 0
    }
}
