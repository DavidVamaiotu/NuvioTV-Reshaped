package com.nuvio.tv.core.player.recovery

import java.util.UUID

/** Small, single-use navigation handoff. No source lists or URLs are serialized into routes/state. */
object AutoSourceRecoveryHandoff {
    private data class Entry(val plan: AutoSourceRecoveryPlan, val createdAt: Long)
    private val entries = LinkedHashMap<String, Entry>()
    private fun nowMs() = System.nanoTime() / 1_000_000L

    @Synchronized fun put(plan: AutoSourceRecoveryPlan): String {
        val now = nowMs()
        entries.entries.removeAll { now - it.value.createdAt >= 120_000L }
        while (entries.size >= 4) entries.remove(entries.keys.first())
        return UUID.randomUUID().toString().also { entries[it] = Entry(plan, now) }
    }

    @Synchronized fun discard(token: String?) { entries.remove(token) }

    @Synchronized fun take(token: String?, profileId: Int, videoId: String?): AutoSourceRecoveryPlan? {
        val entry = entries.remove(token) ?: return null
        val pendingMs = nowMs() - entry.createdAt
        val plan = entry.plan.takeIf {
            pendingMs < 120_000L && it.profileId == profileId && it.videoId == videoId
        } ?: return null
        // Waiting for the user's player-choice dialog/navigation is not playback startup time.
        val state = plan.startupState ?: return plan
        return plan.copy(startupState = state.copy(
            sessionStartedAtMs = state.sessionStartedAtMs + pendingMs,
            attemptStartedAtMs = state.attemptStartedAtMs + pendingMs
        ))
    }
}
