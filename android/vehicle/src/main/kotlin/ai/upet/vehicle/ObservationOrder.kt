package ai.upet.vehicle

import java.util.concurrent.atomic.AtomicLong

/** Capture on the SDK thread; accept on the session's serialized delivery thread. */
internal class ObservationOrder {
    private val latest = AtomicLong()
    private var delivered = -1L
    val version: Long get() = latest.get()
    fun capture(): Long = latest.incrementAndGet()
    fun acceptCallback(observed: Long): Boolean {
        if (observed <= delivered) return false
        delivered = observed
        return true
    }
    fun acceptRead(requested: Long): Boolean {
        if (latest.get() != requested) return false
        delivered = maxOf(delivered, requested)
        return true
    }
}
