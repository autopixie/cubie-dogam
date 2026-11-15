package ai.upet.vehicle

/** Off/On labels must come from observed values, never an ordinal or bounds alone. */
data class DogamStageEvidence(val minimum: Int, val maximum: Int, val off: Int, val on: Set<Int>) {
    val valid: Boolean get() = minimum <= maximum && off in minimum..maximum && on.isNotEmpty() &&
        on.all { it in minimum..maximum && it != off }
    fun normalize(value: Int?, actualMin: Int, actualMax: Int): Boolean? = when {
        !valid || actualMin != minimum || actualMax != maximum || value == null -> null
        value == off -> false
        value in on -> true
        else -> null
    }
}

data class DogamVehiclePolicy(
    val seatHeat: DogamStageEvidence? = null,
    val seatVent: DogamStageEvidence? = null,
    val sideMirrorHeat: DogamStageEvidence? = null,
    val frontDefog: FrontDefog = FrontDefog.UNVERIFIED,
) {
    enum class FrontDefog { UNVERIFIED, DEFROSTER_FRONT_WINDSHIELD, MAX_DEFROST_DRIVER }
    init { require(listOfNotNull(seatHeat, seatVent, sideMirrorHeat).all { it.valid }) }
}

/** Public SDK enum names, not enum ordinals. Other light meanings are not cabin On. */
fun dogamCabinLight(value: String?): Boolean? = when (value) { "OFF" -> false; "ON" -> true; else -> null }
fun dogamDrivingMode(value: String?): String? = value?.takeIf { it in setOf("NORMAL", "ECO", "SPORT") }

/** Pure bind fence used by each observer to reject old connection/epoch callbacks. */
class DogamObservationFence {
    data class Ticket(val generation: Long, val epoch: Long)
    private var generation = 0L
    private var ticket: Ticket? = null
    private var delivered = -1L
    fun begin(epoch: Long): Ticket { generation++; delivered = -1L; return Ticket(generation, epoch).also { ticket = it } }
    fun accept(candidate: Ticket, currentEpoch: Long, version: Long): Boolean {
        if (candidate != ticket || currentEpoch != candidate.epoch || version <= delivered || version < 0L) return false
        delivered = version; return true
    }
    fun close() { generation++; ticket = null; delivered = -1L }
}
