package ai.upet.vehicle

import ai.upet.core.Gear

/** Unavailable is distinct from usable-but-restricted, unknown, pending, and explicitly closed. */
internal enum class UxState { CLOSED, PENDING, ALLOWED, RESTRICTED, UNKNOWN, UNAVAILABLE }
internal data class SafetyDecision(val allowed: Boolean, val source: String, val reason: String) {
    init { require(source == if (allowed) "UX" else "LOCKED") }
}

/** Receives only Gear A observations that passed its existing registration/version fences. */
internal class InteractionSafety {
    var ux = UxState.CLOSED
        private set
    var gear = Gear.UNKNOWN
        private set
    var gearConfirmed = false
        private set
    private var sdkBlock: String? = null

    fun begin() { ux = UxState.PENDING; gear = Gear.UNKNOWN; gearConfirmed = false; sdkBlock = null }
    fun close() { ux = UxState.CLOSED; gear = Gear.UNKNOWN; gearConfirmed = false }
    fun uxChanged(value: UxState) { ux = value }
    fun acceptGear(value: Gear) { gear = value; gearConfirmed = value != Gear.UNKNOWN }
    // A current SDK restriction is not an unavailable UX manager and cannot be bypassed by P.
    fun block(reason: String) { sdkBlock = reason }

    /** Explicit driving facts only; pending/unavailable UX and storage failures are not driving. */
    val drivingRestricted: Boolean get() = (gearConfirmed && gear in setOf(Gear.D, Gear.R, Gear.N)) ||
        ux == UxState.RESTRICTED || sdkBlock == "SDK_DRIVING_RESTRICTED" ||
        sdkBlock?.endsWith(":ERROR_DRIVING_RESTRICTED") == true

    val decision: SafetyDecision get() {
        fun locked(reason: String) = SafetyDecision(false, "LOCKED", reason)
        sdkBlock?.let { return locked(it) }
        when (ux) {
            UxState.CLOSED -> return locked("SESSION_CLOSED")
            UxState.PENDING -> return locked("UX_PENDING")
            UxState.RESTRICTED -> return locked("UX_RESTRICTED")
            UxState.UNKNOWN -> return locked("UX_UNKNOWN")
            UxState.UNAVAILABLE -> return locked("UX_UNAVAILABLE")
            UxState.ALLOWED -> Unit
        }
        if (!gearConfirmed || gear == Gear.UNKNOWN) return locked("GEAR_UNCONFIRMED")
        if (gear != Gear.P) return locked("GEAR_NOT_P")
        return SafetyDecision(true, "UX", "UX_BASELINE_AND_GEAR_P")
    }
}

/** Permission/service failures revoke trust even when an older value response is fenced out. */
internal fun deliverGearFailure(code: String?, reason: String, acceptVersion: () -> Boolean,
    block: (String) -> Unit, unknown: () -> Unit) {
    if (code in setOf("ERROR_SECURITY_PERMISSION", "ERROR_SERVICE_CONNECTION_FAILURE", "ERROR_INTERNAL_SERVICE",
        "ERROR_DRIVING_RESTRICTED", "ERROR_NOT_SUPPORTED_PROPERTY", "ERROR_NOT_SUPPORTED_AREA",
        "ERROR_NOT_SUPPORTED_VALUE_TYPE")) block("$reason:$code")
    if (acceptVersion()) unknown()
}
