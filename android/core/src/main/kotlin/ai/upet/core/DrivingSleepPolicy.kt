package ai.upet.core

/** A foreground-only, one-shot HOME deadline. Unknown safety never starts a sleep. */
class DrivingSleepPolicy {
    data class Ticket(val generation: Long, val deadlineMs: Long)
    data class Saved(val deadlineMs: Long, val homeClaimed: Boolean)
    private var generation = 0L
    private var deadline: Long? = null
    private var homeClaimed = false
    var active = false
        private set

    fun observe(restricted: Boolean, confirmedParked: Boolean, foreground: Boolean, nowMs: Long): Ticket? {
        if (!foreground) return null
        if (!restricted && confirmedParked) { clear(); return null }
        // Focus can return after Pleos moves HOME behind this task. Do not send HOME again
        // until an unrestricted P reading confirms the previous driving episode has ended.
        if (homeClaimed) {
            active = restricted || deadline != null
            return null
        }
        if (restricted && deadline == null) {
            generation++
            deadline = nowMs + 2_000L
            homeClaimed = false
        }
        // A configuration restore can retain an already confirmed restriction during fresh reads.
        active = deadline != null
        return deadline?.let { Ticket(generation, it) }
    }

    fun claim(ticket: Ticket, foreground: Boolean, nowMs: Long): Boolean {
        if (!foreground || !active || homeClaimed || ticket.generation != generation ||
            ticket.deadlineMs != deadline || nowMs < ticket.deadlineMs) return false
        homeClaimed = true
        return true
    }

    // Zero is an already expired deadline, used only to retain a claimed episode after pause.
    fun saved(): Saved? = deadline?.let { Saved(it, homeClaimed) }
        ?: if (homeClaimed) Saved(0L, true) else null

    fun restore(saved: Saved?, nowMs: Long) {
        pause()
        if (saved == null || saved.deadlineMs < 0L || saved.deadlineMs > nowMs + 2_000L) return
        deadline = saved.deadlineMs
        homeClaimed = homeClaimed || saved.homeClaimed
    }

    /** Invalidate every callback without rearming HOME for an already handled driving episode. */
    fun pause() {
        generation++
        deadline = null
        active = false
    }

    private fun clear() {
        generation++
        deadline = null
        homeClaimed = false
        active = false
    }
}
