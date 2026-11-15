package ai.upet.vehicle

import ai.upet.core.AppEvent

/**
 * Foreground adapter boundary. The owner calls these methods and consumes their
 * synchronous callbacks on one thread. No background observation is promised.
 */
interface VehicleSignalAdapter {
    val isDemo: Boolean

    /** Stores the callback; it must not publish state or start a subscription. */
    fun initialize(listener: (AppEvent) -> Unit)

    /**
     * Publishes current values with [epoch] captured by the owner after its latest
     * lifecycle/restriction event. Gear is deliberately not part of this refresh:
     * changing it could invalidate the supplied epoch or create a parking event.
     */
    fun refresh(epoch: Long)

    /** Disconnects the callback. This interface never starts a background service. */
    fun release()
}
