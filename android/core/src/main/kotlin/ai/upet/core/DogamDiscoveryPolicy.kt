package ai.upet.core

/** A one-shot visible lifetime measured from the discovery screen's first actual draw. */
class DogamDiscoveryPolicy {
    data class Ticket(val generation: Long, val key: String)
    private var generation = 0L
    private var active: Ticket? = null
    private var deadline: Long? = null
    private var claimed = false

    /** Receiving a discovery does not start its display clock. Repeated renders reuse this ticket. */
    @Synchronized
    fun begin(key: String): Ticket {
        require(key.isNotBlank())
        active?.takeIf { it.key == key }?.let { return it }
        return Ticket(++generation, key).also {
            active = it; deadline = null; claimed = false
        }
    }

    /** Only the first draw can create a deadline. A stale or repeated draw schedules nothing. */
    @Synchronized
    fun drawn(ticket: Ticket, nowMs: Long): Long? {
        if (active != ticket || deadline != null || claimed || nowMs < 0L ||
            nowMs > Long.MAX_VALUE - DISPLAY_MILLIS) return null
        return (nowMs + DISPLAY_MILLIS).also { deadline = it }
    }

    /** The host must also cancel on pause/destroy; a background callback cannot dismiss a screen. */
    @Synchronized
    fun claim(ticket: Ticket, foreground: Boolean, nowMs: Long): Boolean {
        val due = deadline ?: return false
        if (!foreground || active != ticket || claimed || nowMs < due) return false
        claimed = true
        return true
    }

    @Synchronized
    fun cancel() {
        generation++; active = null; deadline = null; claimed = false
    }

    companion object { const val DISPLAY_MILLIS = 1_500L }
}
