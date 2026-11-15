package ai.upet.core

data class PetMotionFrame(val xPx: Float, val yPx: Float, val moving: Boolean,
    val nextFrameInMs: Long?, val reason: String, val resting: Boolean = false)

/** Product timing: move 2s, pause 3s, read new speech 3s, idle outside practice after 30s. */
class PetMotionPolicy {
    private var x = .5f
    private var y = .5f
    private var fromX = x
    private var fromY = y
    private var targetX = .18f
    private var targetY = .2f
    private var phaseStart = 0L
    private var cycle = 0
    private var moving = true
    private var stopped = true
    private var focusRequested = false
    private var heldSince: Long? = null
    private var heldReleaseAt: Long? = null

    fun focus() { focusRequested = true }

    fun sample(nowMs: Long, travelXPx: Int, travelYPx: Int, foreground: Boolean,
        allowed: Boolean, reducedMotion: Boolean, lastInputMs: Long,
        practiceActive: Boolean = false, readingUntilMs: Long = 0L,
        reactionActive: Boolean = false): PetMotionFrame {
        val sinceInput = (nowMs - lastInputMs).coerceAtLeast(0)
        val reason = when {
            !foreground -> "background"
            !allowed -> "restricted"
            reducedMotion -> "reduced_motion"
            !practiceActive && sinceInput >= IDLE_MS -> "idle"
            else -> null
        }
        fun frame(active: Boolean, delay: Long?, why: String, rest: Boolean = false) =
            PetMotionFrame(x * travelXPx.coerceAtLeast(0), y * travelYPx.coerceAtLeast(0), active, delay, why, rest)
        if (reason != null) {
            stopped = true; heldSince = null
            if (reason != "idle") { x = .5f; y = .5f }
            return frame(false, null, reason, reason != "reduced_motion")
        }
        if (stopped || focusRequested) {
            phaseStart = nowMs; fromX = x; fromY = y; moving = true; heldSince = null
            if (focusRequested) { targetX = .5f; targetY = .85f; focusRequested = false }
            stopped = false
        }
        val noRoom = travelXPx <= 0 && travelYPx <= 0
        val holding = reactionActive || nowMs < readingUntilMs || noRoom
        if (holding) {
            if (heldSince == null) { advance(nowMs); heldSince = nowMs }
            heldReleaseAt = if (reactionActive || noRoom) null else readingUntilMs
            val delay = when {
                noRoom -> if (practiceActive) null else (IDLE_MS - sinceInput).coerceAtLeast(1L)
                reactionActive -> 16L
                else -> minOf(1000L, (readingUntilMs - nowMs).coerceAtLeast(1L))
            }
            val bounded = if (practiceActive || delay == null) delay else minOf(delay, IDLE_MS - sinceInput)
            return frame(false, bounded, if (noRoom) "no_room" else if (reactionActive) "reaction" else "reading")
        }
        heldSince?.let {
            val end = heldReleaseAt?.coerceAtMost(nowMs) ?: nowMs
            phaseStart += (end - it).coerceAtLeast(0)
            heldSince = null; heldReleaseAt = null
        }
        val remaining = advance(nowMs)
        val untilIdle = if (practiceActive) Long.MAX_VALUE else IDLE_MS - sinceInput
        return frame(moving, minOf(if (moving) 16L else remaining, untilIdle), if (moving) "moving" else "pause")
    }

    private fun advance(nowMs: Long): Long {
        var elapsed = (nowMs - phaseStart).coerceAtLeast(0)
        while (elapsed >= if (moving) MOVE_MS else PAUSE_MS) {
            val duration = if (moving) MOVE_MS else PAUSE_MS
            phaseStart += duration; elapsed -= duration
            if (moving) { x = targetX; y = targetY; moving = false }
            else {
                fromX = x; fromY = y; cycle++
                targetX = if (cycle % 2 == 0) .18f else .82f
                targetY = if (cycle % 3 == 0) .2f else .75f
                moving = true
            }
        }
        if (moving) {
            val t = elapsed.toFloat() / MOVE_MS
            val eased = t * t * (3f - 2f * t)
            x = fromX + (targetX - fromX) * eased
            y = fromY + (targetY - fromY) * eased
        }
        return (if (moving) MOVE_MS else PAUSE_MS) - elapsed
    }
    companion object {
        const val IDLE_MS = 30_000L
        const val READ_MS = 3_000L
        private const val MOVE_MS = 2_000L
        private const val PAUSE_MS = 3_000L
    }
}
