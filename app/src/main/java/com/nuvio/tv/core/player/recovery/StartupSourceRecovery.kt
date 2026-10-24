package com.nuvio.tv.core.player.recovery

/** One foreground autoplay startup. Times come from a monotonic clock; player rebuilds do not reset them. */
class StartupSourceRecovery(startedAtMs: Long, state: State? = null) {
    enum class Decision { Wait, NextSource, Complete, Stop }
    data class State(val sessionStartedAtMs: Long, val attemptStartedAtMs: Long, val attempts: Int)

    private val sessionStartedAtMs = state?.sessionStartedAtMs ?: startedAtMs
    private var attemptStartedAtMs = state?.attemptStartedAtMs ?: startedAtMs
    private var attempts = state?.attempts ?: 1
    private var finished = false

    fun snapshot() = State(sessionStartedAtMs, attemptStartedAtMs, attempts)

    fun remainingMs(nowMs: Long): Long = minOf(
        ATTEMPT_TIMEOUT_MS - (nowMs - attemptStartedAtMs).coerceAtLeast(0),
        SESSION_TIMEOUT_MS - (nowMs - sessionStartedAtMs).coerceAtLeast(0)
    ).coerceAtLeast(0)

    fun evaluate(nowMs: Long, playbackStarted: Boolean, interrupted: Boolean, failed: Boolean): Decision {
        if (finished) return Decision.Stop
        if (interrupted) { finished = true; return Decision.Stop }
        if (playbackStarted) { finished = true; return Decision.Complete }
        if (!failed && remainingMs(nowMs) > 0) return Decision.Wait
        return if (attempts < MAX_ATTEMPTS && nowMs - sessionStartedAtMs < SESSION_TIMEOUT_MS) {
            Decision.NextSource
        } else {
            finished = true
            Decision.Stop
        }
    }

    fun beginNextAttempt(nowMs: Long): Boolean {
        if (finished || attempts >= MAX_ATTEMPTS || nowMs - sessionStartedAtMs >= SESSION_TIMEOUT_MS) return false
        attempts++
        attemptStartedAtMs = nowMs
        return true
    }

    companion object {
        const val ATTEMPT_TIMEOUT_MS = 15_000L
        const val SESSION_TIMEOUT_MS = 45_000L
        const val MAX_ATTEMPTS = 3
    }
}
