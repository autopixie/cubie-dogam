package ai.upet.app

/** Lifecycle token only. The Android owner supplies the actual draw and SDK-start callbacks. */
internal class FirstDrawStartup {
    private var generation = 0L
    private var active = false
    private var drawn = false
    private var started = false
    fun begin(): Long { generation++; active=true; drawn=false; started=false; return generation }
    fun drawn(token: Long): Boolean {
        if (!active || token != generation || drawn || started) return false
        drawn=true; return true
    }
    fun claim(token: Long): Boolean {
        if (!active || token != generation || !drawn || started) return false
        started=true; return true
    }
    fun cancel() { active=false; generation++ }
}
