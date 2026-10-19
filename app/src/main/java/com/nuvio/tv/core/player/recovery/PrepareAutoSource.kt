package com.nuvio.tv.core.player.recovery

import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.withTimeoutOrNull

data class PreparedAutoSource<T>(val value: T, val plan: AutoSourceRecoveryPlan)

/** URL resolution and player startup share one deadline and source-attempt count. */
suspend fun <T : Any> prepareAutoSource(
    plan: AutoSourceRecoveryPlan,
    nowMs: () -> Long,
    selectNext: suspend (Set<String>) -> Stream? = { plan.next(it) },
    resolve: suspend (Stream) -> T?
): PreparedAutoSource<T>? {
    val policy = StartupSourceRecovery(nowMs(), plan.startupState)
    val attempted = plan.attempted.toMutableSet()
    var candidate = plan.initialSource ?: return null
    while (true) {
        attempted += recoverySourceKey(candidate)
        val value = withTimeoutOrNull(policy.remainingMs(nowMs())) { resolve(candidate) }
        if (value != null) {
            return PreparedAutoSource(value, plan.copy(
                initialSource = candidate, attempted = attempted.toSet(), startupState = policy.snapshot()
            ))
        }
        if (!policy.beginNextAttempt(nowMs())) return null
        candidate = withTimeoutOrNull(policy.remainingMs(nowMs())) { selectNext(attempted) } ?: return null
    }
}
