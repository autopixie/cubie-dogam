package ai.upet.vehicle

import ai.upet.core.Tpms
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TirePressureListener

/** Raw observation logs; only explicit distance/TPMS sinks reach the product. Fuel stays log-only. */
internal class VehicleReadProbe(private val source: SdkSession, private val log: (String, String) -> Unit,
    private val distance: (Float?) -> Unit = {}, private val tpms: (Tpms?) -> Unit = {}) {
    private fun wheelLabel(area: WheelArea): String = area.values.joinToString(",") { it.name }
    private fun readable(config: CapabilityConfig) =
        config.component1() in setOf(AccessType.READ, AccessType.READ_WRITE)
    private fun capability(name: String, check: ((CapabilityConfig) -> Unit, (Exception) -> Unit) -> Unit,
        ready: () -> Unit, failed: () -> Unit = {}) {
        source.read<CapabilityConfig>(name + ".capability", check, { config ->
            log(name + ".capability", "access=" + config.component1())
            if (readable(config)) ready() else { log(name, "unsupported read access"); failed() }
        }, { failed() })
    }

    fun start() {
        val odometer = source.vehicle.getOdometer()
        distance("Odometer", odometer::checkCurrentDistanceDrivenCapabilities,
            odometer::getCurrentDistanceDriven, odometer::registerDistanceDriven, odometer::unregisterDistanceDriven, distance)
        distance("TripCurrent", odometer::checkTripCurrentDrivingDistanceCapabilities,
            odometer::getTripCurrentDrivingDistance, odometer::registerTripCurrentDrivingDistance,
            odometer::unregisterTripCurrentDrivingDistance)
        distance("TripCumulative", odometer::checkTripCumulativeDrivingDistanceCapabilities,
            odometer::getTripCumulativeDrivingDistance, odometer::registerTripCumulativeDrivingDistance,
            odometer::unregisterTripCumulativeDrivingDistance)
        distance("TripSinceRefill", odometer::checkTripSinceRefillDrivingDistanceCapabilities,
            odometer::getTripSinceRefillDrivingDistance, odometer::registerTripSinceRefillDrivingDistance,
            odometer::unregisterTripSinceRefillDrivingDistance)
        telltales()
        tires()
        fuelAndBattery()
    }

    private fun distance(name: String,
        check: ((CapabilityConfig) -> Unit, (Exception) -> Unit) -> Unit,
        get: ((Float?) -> Unit, (Exception) -> Unit) -> Unit,
        register: (DistanceDrivenListener) -> Unit, unregister: (DistanceDrivenListener) -> Unit,
        onValue: (Float?) -> Unit = {}) {
        capability(name, check, {
            source.read<Float?>(name + ".get", get, { value ->
                log(name + ".get", "raw=" + value + " unit=UNVERIFIED"); onValue(value)
                val changes = object : DistanceDrivenListener {
                    override fun onDistanceDrivenUpdated(value: Float?) {
                        source.deliver(name) { log(name + ".callback", "raw=" + value + " unit=UNVERIFIED"); onValue(value) }
                    }
                    override fun onFailed(e: Exception) { source.deliver(name) { source.failure(name, e); onValue(null) } }
                }
                source.subscribe(name, { register(changes) }, { unregister(changes) })
            }, { onValue(null) })
        }, { onValue(null) })
    }

    private fun telltales() {
        val safety = source.vehicle.getSafety()
        val order = ObservationOrder()
        fun snapshot(subscribe: Boolean) {
            source.read<Telltales?>("getTelltalesInfo",
                { ok, fail -> safety.getTelltalesInfo(ok, fail) }, { value ->
                    logTelltales(value, "get"); tpms(readTpms(value))
                    if (subscribe) {
                        val changes = object : TelltaleInfoListener {
                            override fun onTelltaleInfoUpdated(value: Telltales?) {
                                val observed = order.capture()
                                source.deliver("Telltales") {
                                    if (order.acceptCallback(observed)) {
                                        logTelltales(value, "callback"); tpms(readTpms(value))
                                    }
                                }
                            }
                            override fun onFailed(e: Exception) {
                                val observed = order.capture()
                                source.deliver("Telltales") {
                                    source.failure("Telltales", e)
                                    if (order.acceptCallback(observed)) tpms(null)
                                }
                            }
                        }
                        source.subscribe("Telltales", { safety.registerTelltales(changes) },
                            { safety.unregisterTelltales(changes) }) { registered ->
                        if (!registered) { source.unsubscribe("Telltales"); tpms(null); return@subscribe }
                        val requested = order.version
                        source.read<Telltales?>("getTelltalesInfo.afterRegister",
                            { ok, fail -> safety.getTelltalesInfo(ok, fail) }, { confirmed ->
                                if (order.acceptRead(requested)) {
                                    logTelltales(confirmed, "afterRegister"); tpms(readTpms(confirmed))
                                }
                            }, { if (order.acceptRead(requested)) tpms(null) })
                        }
                    }
                }, { tpms(null) })
        }
        // The capability/subscription requires PRIVATE_CAR_SAFETY; get uses CAR_SAFETY.
        capability("Telltales", { ok, fail -> safety.checkTelltalesCapabilities(ok, fail) },
            { snapshot(true) }, { snapshot(false) })
    }

    private fun logTelltales(value: Telltales?, sourceName: String) {
        val readings = value?.component1()
        listOf(TelltaleType.PARKING_BRAKE, TelltaleType.ELECTRONIC_PARKING_BRAKE, TelltaleType.TPMS)
            .forEach { wanted ->
                val states = readings?.filter { it.component1() == wanted }?.map { it.component2().name }
                log("Telltales." + sourceName, "type=" + wanted +
                    " value=" + if (states.isNullOrEmpty()) "UNKNOWN" else states.joinToString(","))
            }
    }

    private fun tires() {
        val tire = source.vehicle.getTire()
        source.read<Pair<CapabilityConfig, List<WheelArea>>>("checkTirePressureCapabilities",
            { ok, fail -> tire.checkTirePressureCapabilities(
                { config: CapabilityConfig, areas: List<WheelArea> -> ok(config to areas) }, fail) }, { (config, areas) ->
                log("checkTirePressureCapabilities", "access=" + config.component1() + " areas=" + areas.joinToString(";") { wheelLabel(it) })
                if (readable(config)) areas.forEach { area ->
                    source.read<Float?>("getTirePressure." + wheelLabel(area),
                        { ok, fail -> tire.getTirePressure(area, ok, fail) }, { value ->
                            log("getTirePressure", "area=" + wheelLabel(area) + " raw=" + value + " unit=UNVERIFIED")
                            val changes = object : TirePressureListener {
                                override fun onTirePressureUpdated(value: Float?) {
                                    source.deliver("TirePressure") {
                                        log("TirePressure.callback", "area=" + wheelLabel(area) + " raw=" + value + " unit=UNVERIFIED")
                                    }
                                }
                                override fun onFailed(e: Exception) {
                                    source.deliver("TirePressure") { source.failure("TirePressure", e) }
                                }
                            }
                            source.subscribe("TirePressure." + wheelLabel(area), { tire.registerTirePressure(area, changes) },
                                { tire.unregisterTirePressure(area, changes) })
                        })
                }
            })
        source.read<Pair<CapabilityConfig, List<WheelArea>>>("checkLowTirePressureCapabilities",
            { ok, fail -> tire.checkLowTirePressureCapabilities(
                { config: CapabilityConfig, areas: List<WheelArea> -> ok(config to areas) }, fail) }, { (config, areas) ->
                log("checkLowTirePressureCapabilities", "access=" + config.component1() + " areas=" + areas.joinToString(";") { wheelLabel(it) })
                if (readable(config)) areas.forEach { area ->
                    source.read<Float?>("getLowTirePressure." + wheelLabel(area),
                        { ok, fail -> tire.getLowTirePressure(area, ok, fail) }, { value ->
                            log("getLowTirePressure", "area=" + wheelLabel(area) + " raw=" + value + " unit=UNVERIFIED")
                        })
                }
            })
    }

    private fun fuelAndBattery() {
        val fuel = source.vehicle.getFuel()
        capability("FuelPercent", { ok, fail -> fuel.checkFuelPercentCapabilities(ok, fail) }, {
            source.read<Int?>("getFuelPercent", { ok, fail -> fuel.getFuelPercent(ok, fail) }, { value ->
                log("getFuelPercent", "raw=" + value + " unit=UNVERIFIED consoleCard=NONE")
                // No Console fuel card; deliberately no live fuel/refuelling UI binding.
            })
        })
        val evBattery = source.vehicle.getEvBattery()
        capability("EvBatterySOC", { ok, fail -> evBattery.checkEvBatteryStateOfChargeCapabilities(ok, fail) }, {
            source.read<Int?>("getEvBatteryStateOfCharge",
                { ok, fail -> evBattery.getEvBatteryStateOfCharge(ok, fail) }, { value ->
                    log("getEvBatteryStateOfCharge", "raw=" + value + " unit=UNVERIFIED")
                    val changes = object : EvBatteryStateOfChargeListener {
                        override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
                            source.deliver("EvBatterySOC") { log("EvBatterySOC.callback", "raw=" + value) }
                        }
                        override fun onFailed(e: Exception) {
                            source.deliver("EvBatterySOC") { source.failure("EvBatterySOC", e) }
                        }
                    }
                    source.subscribe("EvBatterySOC", { evBattery.registerEvBatteryStateOfCharge(changes) },
                        { evBattery.unregisterEvBatteryStateOfCharge(changes) })
                })
        })
    }
}
