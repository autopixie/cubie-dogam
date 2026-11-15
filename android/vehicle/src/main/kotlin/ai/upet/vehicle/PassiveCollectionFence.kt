package ai.upet.vehicle

import ai.upet.core.EngineState
import ai.upet.core.Gear

/** An opt-in collection read cannot supply UX permission, a parked gear, or a practice session. */
internal fun canCollectPassiveChange(enabled: Boolean, captured: EngineState, current: EngineState,
    epoch: Long): Boolean {
    fun ready(value: EngineState) = value.epoch == epoch && value.session == null &&
        value.foreground && value.platformAllowed && value.gear == Gear.P
    return enabled && ready(captured) && ready(current)
}

/** Shared by heat and Boolean callbacks after their existing registered-binding checks. */
internal data class PassiveCollectionFence(val epoch: Long, val callbackVersion: Long,
    val failureVersion: Long, val startBoundary: Long) {
    fun mayPublish(enabled: Boolean, captured: EngineState, current: EngineState,
        latestVersion: Long, latestFailureVersion: Long, currentStartBoundary: Long,
        pendingCallbacks: Int): Boolean =
        canCollectPassiveChange(enabled, captured, current, epoch) &&
            callbackVersion == latestVersion && failureVersion == latestFailureVersion &&
            (startBoundary and 1L) == 0L && startBoundary == currentStartBoundary && pendingCallbacks == 0
}
