package ai.upet.vehicle

import ai.upet.core.AppEvent
import ai.upet.core.ContentBundle
import ai.upet.core.Gear
import ai.upet.core.Tpms

/** Explicit demo controls only; none of these methods writes to a vehicle SDK. */
class FakeVehicleAdapter(content: ContentBundle) : VehicleSignalAdapter {
    override val isDemo = true

    private val features = content.features.associateBy { it.id }
    private val values = features.keys.associateWith<String, Boolean?> { false }.toMutableMap()
    private val sequences = features.keys.associateWith { -1L }.toMutableMap()
    private var listener: ((AppEvent) -> Unit)? = null
    private val failures = mutableMapOf<String, ReadHealth>()
    fun readFailure(featureId: String): ReadHealth? = failures[featureId]
    private var distance = 1200.0
    private var fuel = 35.0
    private var tireWarning: Tpms? = Tpms.Off

    var currentGear: Gear = Gear.P
        private set
    val odometerKm: Double get() = distance
    val fuelPercent: Double get() = fuel
    val tpms: Tpms? get() = tireWarning
    val featureValues: Map<String, Boolean?> get() = values.toMap()

    override fun initialize(listener: (AppEvent) -> Unit) {
        this.listener = listener
    }

    override fun refresh(epoch: Long) {
        if (listener == null) return
        emit(AppEvent.Odometer(distance))
        emit(AppEvent.TpmsChanged(tireWarning))
        emit(AppEvent.FuelDemo(fuel, snapshot = true))
        features.values.forEach { feature ->
            emit(AppEvent.Capability(feature.id, supported = failures[feature.id] != ReadHealth.UNSUPPORTED))
            publishFeature(feature.id, values.getValue(feature.id), epoch, snapshot = true)
        }
    }

    override fun release() {
        listener = null
    }

    /** The owner refreshes with the engine's resulting epoch after entering P. */
    fun setGear(value: Gear) {
        currentGear = value
        emit(AppEvent.GearChanged(value))
    }

    /** [epoch] and [sessionId] belong to the event source, not its delivery time. */
    fun setFeature(featureId: String, value: Boolean?, epoch: Long, sessionId: Long? = null) {
        require(featureId in features) { "Unknown demo feature: $featureId" }
        if (failures.remove(featureId) != null) emit(AppEvent.Capability(featureId, true))
        if (value == true) {
            val opposite = when (featureId) {
                "seat_heat" -> "seat_vent"
                "seat_vent" -> "seat_heat"
                else -> null
            }
            if (opposite != null && opposite in features && values[opposite] != false) {
                publishFeature(opposite, false, epoch, sessionId = sessionId)
            }
        }
        publishFeature(featureId, value, epoch, sessionId = sessionId)
    }

    /** Explicit demo-only error injection; never touches the real SDK. */
    fun injectReadFailure(featureId: String, kind: ReadHealth, epoch: Long) {
        require(featureId in features)
        require(kind in setOf(ReadHealth.UNSUPPORTED, ReadHealth.UNKNOWN, ReadHealth.FAILED))
        failures[featureId] = kind
        emit(AppEvent.Capability(featureId, kind != ReadHealth.UNSUPPORTED))
        publishFeature(featureId, null, epoch, snapshot = true)
    }

    fun setTpms(value: Tpms?) {
        tireWarning = value
        emit(AppEvent.TpmsChanged(value))
    }

    fun addDistance(km: Double) {
        val next = distance + km
        require(km.isFinite() && km >= 0.0 && next in 0.0..1_000_000.0) {
            "Demo distance must remain within the core odometer range"
        }
        distance = next
        emit(AppEvent.Odometer(distance))
    }

    fun setFuelPercent(value: Double) {
        require(value.isFinite() && value in 0.0..100.0) { "Demo fuel must be a percentage" }
        fuel = value
        emit(AppEvent.FuelDemo(value))
    }

    private fun publishFeature(
        featureId: String,
        value: Boolean?,
        epoch: Long,
        snapshot: Boolean = false,
        sessionId: Long? = null,
    ) {
        val feature = features.getValue(featureId)
        val sequence = sequences.getValue(featureId) + 1
        check(sequence <= 9_007_199_254_740_991L) { "Demo signal sequence exhausted" }
        values[featureId] = value
        sequences[featureId] = sequence
        emit(AppEvent.Signal(featureId, feature.area, value, sequence, epoch, snapshot, sessionId))
    }

    private fun emit(event: AppEvent) {
        listener?.invoke(event)
    }
}
