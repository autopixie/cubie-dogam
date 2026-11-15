package ai.upet.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.vehicle.api.model.area.MirrorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.MirrorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.DrivingModeType
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.listener.DrivingModeStateListener
import ai.pleos.playground.vehicle.listener.HvacDefrosterStateListener
import ai.pleos.playground.vehicle.listener.HvacMaxDefrostStateListener
import ai.pleos.playground.vehicle.listener.HvacSeatTemperatureListener
import ai.pleos.playground.vehicle.listener.HvacSeatVentilationListener
import ai.pleos.playground.vehicle.listener.HvacSideMirrorHeatStateListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.upet.core.AppEvent
import ai.upet.core.EngineState
import java.util.concurrent.atomic.AtomicLong

/** Additional read-only dogam observers. Gear/UX/TPMS and cards001..003 stay in the existing adapter. */
class DogamVehicleBridge(
    context: Context,
    private val state: () -> EngineState,
    private val emit: (AppEvent) -> Unit,
    private val capability: (String, Boolean?, String) -> Unit,
    private val mode: (String?, Long, Long, Boolean) -> Unit,
    private val policy: DogamVehiclePolicy = DogamVehiclePolicy(),
    private val trace: (String, String) -> Unit = { _, _ -> },
) {
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var sequence = 0L
    private var started = false
    private val nodes = linkedMapOf<String, Observer>()
    private val source = SdkSession(context.applicationContext, main, trace, {})
    var drivingRestricted = false
        private set
    private interface Observer { fun refresh(); fun close() }
    private fun live() = !closed && !source.closing
    private fun failure(id: String, error: Exception) {
        val code = SdkFailure.code(error)
        trace("$id.failure", "code=$code type=${error.javaClass.simpleName}")
        capability(id, if (code in setOf("ERROR_NOT_SUPPORTED_PROPERTY", "ERROR_NOT_SUPPORTED_AREA")) false else null,
            code ?: "READ_FAILED")
        if (code == "ERROR_DRIVING_RESTRICTED") {
            drivingRestricted = true
            emit(AppEvent.Unhandled("dogam_vehicle_restriction"))
        }
    }
    fun start() {
        check(Looper.myLooper() == main.looper)
        if (started || closed) return
        started = true
        ids.forEach { capability(it, null, "CONNECTING") }
        source.onFailure = { error -> ids.forEach { failure(it, error) }; close() }
        source.initialize({ if (live()) configure() }, { error -> ids.forEach { failure(it, error) } })
    }
    fun refresh() { if (live()) nodes.values.toList().forEach { it.refresh() } }
    fun close() {
        if (closed) return
        closed = true
        nodes.values.forEach { it.close() }; nodes.clear()
        source.close()
    }
    private fun driver(areas: Collection<SeatArea>) = areas.singleOrNull { it.values.toSet() == setOf(SeatValue.ROW_1_LEFT) }
    private fun readable(config: CapabilityConfig) = config.component1() in setOf(AccessType.READ, AccessType.READ_WRITE)
    private fun <A> cap(id: String,
        invoke: ((CapabilityConfig, A) -> Unit, (Exception) -> Unit) -> Unit,
        next: (A) -> Unit) {
        source.read<Pair<CapabilityConfig, A>>("$id.cap", { ok, fail -> invoke({ config, value -> ok(config to value) }, fail) }, { result ->
            if (!live()) return@read
            trace("$id.cap", "access=${result.first.component1()} value=${result.second}")
            if (readable(result.first)) next(result.second) else capability(id, false, "NO_READ_ACCESS")
        }, { failure(id, it) })
    }
    private inner class Node<T>(
        private val id: String,
        private val engineArea: String,
        private val get: ((T) -> Unit, (Exception) -> Unit) -> Unit,
        private val listener: ((T) -> Unit, (Exception) -> Unit) -> Pair<() -> Unit, () -> Unit>,
        private val normalize: (T) -> Boolean?,
        private val modeValue: ((T) -> String?)? = null,
    ) : Observer {
        private val fence = DogamObservationFence()
        private val version = AtomicLong()
        private var boundEpoch: Long? = null
        private var active = true
        private var ready = false
        private var latest: Pair<Long, T>? = null
        private val key = "Dogam.$id"
        override fun refresh() {
            val epoch = state().epoch
            if (!live() || !active || boundEpoch == epoch) return
            source.unsubscribe(key)
            boundEpoch = epoch; ready = false; latest = null
            val ticket = fence.begin(epoch)
            val pair = listener({ value ->
                val observed = version.incrementAndGet()
                source.deliver(key, {}) {
                    if (live() && active && boundEpoch == epoch && state().epoch == epoch) {
                        if (latest?.first?.let { observed > it } != false) latest = observed to value
                        if (ready && fence.accept(ticket, state().epoch, observed)) publish(value, epoch, false)
                    }
                }
            }, { error -> source.deliver(key, {}) {
                if (live() && active && boundEpoch == epoch) { ready = false; fence.close(); failure(id, error) }
            } })
            source.subscribe(key, pair.first, pair.second) { registered ->
                if (!live() || !active || boundEpoch != epoch) return@subscribe
                if (!registered) { fence.close(); capability(id, null, "REGISTER_FAILED"); return@subscribe }
                val readVersion = version.get()
                source.read<Pair<Long, T>>("$id.snapshot", { ok, fail -> get({ raw -> ok(readVersion to raw) }, fail) }, { result ->
                    if (!live() || !active || boundEpoch != epoch || state().epoch != epoch) return@read
                    val chosen = latest?.takeIf { it.first >= result.first } ?: result
                    if (fence.accept(ticket, epoch, chosen.first)) publish(chosen.second, epoch, true)
                    ready = true
                }, { error -> if (boundEpoch == epoch) { ready = false; fence.close(); failure(id, error) } })
            }
        }
        private fun publish(raw: T, epoch: Long, snapshot: Boolean) {
            trace("$id.raw", "raw=$raw snapshot=$snapshot epoch=$epoch")
            if (modeValue != null) mode(modeValue.invoke(raw), ++sequence, epoch, snapshot)
            else emit(AppEvent.Signal(id, engineArea, normalize(raw), ++sequence, epoch, snapshot))
        }
        override fun close() { active = false; ready = false; fence.close(); source.unsubscribe(key) }
    }
    private fun <T> observe(id: String, engineArea: String,
        get: ((T) -> Unit, (Exception) -> Unit) -> Unit,
        listener: ((T) -> Unit, (Exception) -> Unit) -> Pair<() -> Unit, () -> Unit>,
        normalize: (T) -> Boolean?, modeValue: ((T) -> String?)? = null) {
        nodes[id]?.close()
        val node = Node(id, engineArea, get, listener, normalize, modeValue)
        nodes[id] = node
        node.refresh()
        capability(id, true, "READABLE_REGISTERING")
    }
    private fun configure() {
        val seat = source.vehicle.getSeat()
        cap<Map<SeatArea, AreaBounds<Int>>>("seat_heat", { ok, fail -> seat.checkSeatTemperatureCapabilities(ok, fail) }) { values ->
            val area = driver(values.keys)
            if (area == null) capability("seat_heat", false, "NO_EXACT_DRIVER_LEFT_AREA") else {
                val bounds = values.getValue(area); val evidence = policy.seatHeat
                if (evidence == null || !evidence.valid || evidence.minimum != bounds.component1() || evidence.maximum != bounds.component2())
                    capability("seat_heat", null, "OFF_ON_EVIDENCE_REQUIRED")
                else observe<Int?>("seat_heat", "front_left", { ok, fail -> seat.getHvacSeatTemperature(area, ok, fail) }, { changed, failed ->
                    val l = object : HvacSeatTemperatureListener {
                        override fun onHvacSeatTemperatureUpdated(value: Int?) = changed(value)
                        override fun onFailed(e: Exception) = failed(e)
                    }
                    ({ seat.registerHvacSeatTemperature(area, l) }) to ({ seat.unregisterHvacSeatTemperature(area, l) })
                }, { evidence.normalize(it, bounds.component1(), bounds.component2()) })
            }
        }
        cap<Map<SeatArea, AreaBounds<Int>>>("seat_vent", { ok, fail -> seat.checkSeatVentilationCapabilities(ok, fail) }) { values ->
            val area = driver(values.keys)
            if (area == null) capability("seat_vent", false, "NO_EXACT_DRIVER_LEFT_AREA") else {
                val bounds = values.getValue(area); val evidence = policy.seatVent
                if (evidence == null || !evidence.valid || evidence.minimum != bounds.component1() || evidence.maximum != bounds.component2())
                    capability("seat_vent", null, "OFF_ON_EVIDENCE_REQUIRED")
                else observe<Int?>("seat_vent", "front_left", { ok, fail -> seat.getHvacSeatVentilationState(area, ok, fail) }, { changed, failed ->
                    val l = object : HvacSeatVentilationListener {
                        override fun onHvacSeatVentilationUpdated(value: Int?) = changed(value)
                        override fun onFailed(e: Exception) = failed(e)
                    }
                    ({ seat.registerHvacSeatVentilation(area, l) }) to ({ seat.unregisterHvacSeatVentilation(area, l) })
                }, { evidence.normalize(it, bounds.component1(), bounds.component2()) })
            }
        }
        val hvac = source.vehicle.getHVAC()
        when (policy.frontDefog) {
            DogamVehiclePolicy.FrontDefog.UNVERIFIED -> {
                // Query both candidates, but do not turn a candidate into front-windshield meaning.
                var defroster: Boolean? = null; var maxDefrost: Boolean? = null
                var defrosterDone = false; var maxDone = false
                fun completed() {
                    if (defrosterDone && maxDone) capability("front_defog",
                        if (defroster == false && maxDefrost == false) false else null,
                        "FRONT_API_EVIDENCE_REQUIRED")
                }
                source.read<Pair<CapabilityConfig, List<WindowArea>>>("front_defog.Defroster.cap", { ok, fail ->
                    hvac.checkHvacDefrosterCapabilities({ config: CapabilityConfig, areas: List<WindowArea> -> ok(config to areas) }, fail)
                }, { result ->
                    trace("front_defog.Defroster.cap", "access=${result.first.component1()} areas=${result.second}")
                    defroster = readable(result.first) && result.second.any { it.values.toSet() == setOf(WindowValue.FRONT_WINDSHIELD) }
                    defrosterDone = true; completed()
                }, { error ->
                    val code = SdkFailure.code(error)
                    trace("front_defog.Defroster.failure", "code=$code")
                    defroster = if (code in setOf("ERROR_NOT_SUPPORTED_PROPERTY", "ERROR_NOT_SUPPORTED_AREA")) false else null
                    defrosterDone = true; completed()
                    if (code == "ERROR_DRIVING_RESTRICTED") failure("front_defog", error)
                })
                source.read<Pair<CapabilityConfig, List<SeatArea>>>("front_defog.MaxDefrost.cap", { ok, fail ->
                    hvac.checkHvacMaxDefrostCapabilities({ config: CapabilityConfig, areas: List<SeatArea> -> ok(config to areas) }, fail)
                }, { result ->
                    trace("front_defog.MaxDefrost.cap", "access=${result.first.component1()} areas=${result.second}")
                    maxDefrost = readable(result.first) && driver(result.second) != null
                    maxDone = true; completed()
                }, { error ->
                    val code = SdkFailure.code(error)
                    trace("front_defog.MaxDefrost.failure", "code=$code")
                    maxDefrost = if (code in setOf("ERROR_NOT_SUPPORTED_PROPERTY", "ERROR_NOT_SUPPORTED_AREA")) false else null
                    maxDone = true; completed()
                    if (code == "ERROR_DRIVING_RESTRICTED") failure("front_defog", error)
                })
            }
            DogamVehiclePolicy.FrontDefog.DEFROSTER_FRONT_WINDSHIELD -> cap<List<WindowArea>>("front_defog", { ok, fail -> hvac.checkHvacDefrosterCapabilities(ok, fail) }) { areas ->
                val area = areas.singleOrNull { it.values.toSet() == setOf(WindowValue.FRONT_WINDSHIELD) }
                if (area == null) capability("front_defog", false, "NO_EXACT_FRONT_WINDSHIELD")
                else observe<Boolean?>("front_defog", "front", { ok, fail -> hvac.getHvacDefrosterState(area, ok, fail) }, { changed, failed ->
                    val l = object : HvacDefrosterStateListener {
                        override fun onHvacDefrosterStateUpdated(value: Boolean?) = changed(value)
                        override fun onFailed(e: Exception) = failed(e)
                    }
                    ({ hvac.registerHvacDefrosterState(area, l) }) to ({ hvac.unregisterHvacDefrosterState(area, l) })
                }, { it })
            }
            DogamVehiclePolicy.FrontDefog.MAX_DEFROST_DRIVER -> cap<List<SeatArea>>("front_defog", { ok, fail -> hvac.checkHvacMaxDefrostCapabilities(ok, fail) }) { areas ->
                val area = driver(areas)
                if (area == null) capability("front_defog", false, "NO_EXACT_DRIVER_LEFT_AREA")
                else observe<Boolean?>("front_defog", "front", { ok, fail -> hvac.getHvacMaxDefrostState(area, ok, fail) }, { changed, failed ->
                    val l = object : HvacMaxDefrostStateListener {
                        override fun onHvacMaxDefrostStateUpdated(value: Boolean?) = changed(value)
                        override fun onFailed(e: Exception) = failed(e)
                    }
                    ({ hvac.registerHvacMaxDefrostState(area, l) }) to ({ hvac.unregisterHvacMaxDefrostState(area, l) })
                }, { it })
            }
        }
        val light = source.vehicle.getLight()
        cap<List<LightState>>("cabin_light", { ok, fail -> light.checkCabinLightStateCapabilities(ok, fail) }) { allowed ->
            if (!allowed.containsAll(listOf(LightState.OFF, LightState.ON))) capability("cabin_light", false, "OFF_AND_ON_NOT_BOTH_SUPPORTED")
            else observe<LightState?>("cabin_light", "cabin", { ok, fail -> light.getCurrentCabinLightState(ok, fail) }, { changed, failed ->
                val l = object : LightStateListener {
                    override fun onLightStateUpdated(value: LightState?) = changed(value)
                    override fun onFailed(e: Exception) = failed(e)
                }
                ({ light.registerCabinLightState(l) }) to ({ light.unregisterCabinLightState(l) })
            }, { dogamCabinLight(it?.name) })
        }
        val driving = source.vehicle.getDrivingMode()
        source.read<CapabilityConfig>("drive_mode.cap", { ok, fail -> driving.checkDrivingModeStateCapabilities(ok, fail) }, { config ->
            trace("drive_mode.cap", "access=${config.component1()}")
            if (!readable(config)) capability("drive_mode", false, "NO_READ_ACCESS")
            else observe<DrivingModeType?>("drive_mode", "vehicle", { ok, fail -> driving.getCurrentDrivingModeState(ok, fail) }, { changed, failed ->
                val l = object : DrivingModeStateListener {
                    override fun onDrivingModeStateUpdated(value: DrivingModeType?) = changed(value)
                    override fun onFailed(e: Exception) = failed(e)
                }
                ({ driving.registerDrivingModeState(l) }) to ({ driving.unregisterDrivingModeState(l) })
            }, { null }, { dogamDrivingMode(it?.name) })
        }, { failure("drive_mode", it) })
        val mirror = source.vehicle.getSideMirror()
        cap<Map<MirrorArea, AreaBounds<Int>>>("side_mirror_heat", { ok, fail -> mirror.checkHvacSideMirrorHeatCapabilities(ok, fail) }) { values ->
            val area = values.keys.singleOrNull { it.values.toSet() == setOf(MirrorValue.DRIVER_LEFT) }
            if (area == null) capability("side_mirror_heat", false, "NO_EXACT_DRIVER_LEFT_MIRROR") else {
                val bounds = values.getValue(area); val evidence = policy.sideMirrorHeat
                if (evidence == null || !evidence.valid || evidence.minimum != bounds.component1() || evidence.maximum != bounds.component2())
                    capability("side_mirror_heat", null, "OFF_ON_EVIDENCE_REQUIRED")
                else observe<Int?>("side_mirror_heat", "mirrors", { ok, fail -> mirror.getHvacSideMirrorHeatState(area, ok, fail) }, { changed, failed ->
                    val l = object : HvacSideMirrorHeatStateListener {
                        override fun onHvacSideMirrorHeatStateUpdated(value: Int?) = changed(value)
                        override fun onFailed(e: Exception) = failed(e)
                    }
                    ({ mirror.registerHvacSideMirrorHeatState(area, l) }) to ({ mirror.unregisterHvacSideMirrorHeatState(area, l) })
                }, { evidence.normalize(it, bounds.component1(), bounds.component2()) })
            }
        }
    }
    companion object { val ids = setOf("seat_heat", "seat_vent", "front_defog", "cabin_light", "drive_mode", "side_mirror_heat") }
}
