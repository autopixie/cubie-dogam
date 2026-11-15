package ai.upet.vehicle

/** Verified Off/Low evidence identifies Off; every other supported stage is On. Bounds alone never do. */
data class HeaterEvidence(val minimum: Int, val maximum: Int, val off: Int? = null, val low: Int? = null) {
    val valid: Boolean get() = minimum <= maximum && off != null && low != null &&
        off != low && off in minimum..maximum && low in minimum..maximum
    fun normalize(raw: Int?): Boolean? = when {
        !valid || raw == null || raw !in minimum..maximum -> null
        raw == off -> false
        else -> true
    }
}

/** Bundled evidence never becomes a stored observation merely by being used. */
enum class HeaterEvidenceOrigin { NONE, BUNDLED_20261101, STORED_OBSERVATION }

data class HeaterEvidenceResolution(val evidence: HeaterEvidence, val origin: HeaterEvidenceOrigin)

/** Null storage means a read failure. Only an entirely absent record permits the approved fallback. */
fun resolveHeaterEvidence(minimum: Int, maximum: Int, stored: Map<String, *>?,
    bundled: HeaterEvidence? = null): HeaterEvidenceResolution {
    val unknown = HeaterEvidenceResolution(HeaterEvidence(minimum, maximum), HeaterEvidenceOrigin.NONE)
    if (stored == null) return unknown
    if (stored.isNotEmpty()) {
        if (stored["sdk"] != "2.3.2" || stored["minimum"] != minimum || stored["maximum"] != maximum) return unknown
        val off = stored["off"] as? Int ?: return unknown
        val low = stored["low"] as? Int ?: return unknown
        val observed = HeaterEvidence(minimum, maximum, off, low)
        return if (observed.valid) HeaterEvidenceResolution(observed, HeaterEvidenceOrigin.STORED_OBSERVATION) else unknown
    }
    return if (minimum == 0 && maximum == 3 && bundled == HeaterEvidence(0, 3, 0, 1))
        HeaterEvidenceResolution(bundled, HeaterEvidenceOrigin.BUNDLED_20261101) else unknown
}

/** Latest callback and read-error fences also cover the get/register confirmation gap. */
internal class HeatObservations {
    data class Observation(val raw: Int?, val version: Long, val failed: Boolean = false)
    private val latest = java.util.concurrent.atomic.AtomicReference<Observation?>()

    fun record(raw: Int?, version: Long, failed: Boolean = false) {
        val observed = Observation(raw, version, failed)
        latest.updateAndGet { old -> if (old == null || old.version < version) observed else old }
    }
    fun confirm(raw: Int?, requestedVersion: Long, readErrorSinceRequest: Boolean = false): Observation? {
        val observed = latest.get()
        if (readErrorSinceRequest || observed?.failed == true) return null
        return observed?.takeIf { it.version > requestedVersion } ?: Observation(raw, requestedVersion)
    }
}

enum class ReadHealth { DISCONNECTED, UNSUPPORTED, UNKNOWN, FAILED, RESTRICTED, READY }

fun ReadHealth.userMessage(): String = when (this) {
    ReadHealth.DISCONNECTED -> "차량 연결을 기다리고 있어요."
    ReadHealth.UNSUPPORTED -> "이 차에서 지원하지 않는 기능이에요."
    ReadHealth.UNKNOWN -> "현재 상태를 확인하지 못했어요."
    ReadHealth.FAILED -> "차 상태를 읽는 중 오류가 생겼어요."
    ReadHealth.RESTRICTED -> "지금은 실습을 쉴게요."
    ReadHealth.READY -> "차에서 받은 상태예요."
}

/** A release request waits for all issued asynchronous callbacks, including failures. */
internal class CallbackDrain(private val drained: () -> Unit) {
    var pending: Int = 0
        private set
    var closing: Boolean = false
        private set
    private var finished = false

    fun begin(): () -> Unit {
        check(!closing)
        pending++
        var completed = false
        return {
            if (!completed) {
                completed = true
                pending--
                finishIfReady()
            }
        }
    }
    fun close() { closing = true; finishIfReady() }
    private fun finishIfReady() {
        if (closing && pending == 0 && !finished) { finished = true; drained() }
    }
}
