package ai.upet.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.listener.HvacAutoStateListener
import ai.pleos.playground.vehicle.listener.HvacAirCleanerStateListener
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacSteeringWheelHeatStateListener
import ai.upet.core.AppEvent
import ai.upet.core.ContentBundle
import ai.upet.core.EngineState
import ai.upet.core.Gear

/** Read-only integration: usable UX takes priority; approved B-prime uses confirmed Gear A only. */
class PleosVehicleAdapter(
    context: Context,
    private val content: ContentBundle,
    private val state: () -> EngineState,
    private val selectedFeature: () -> String? = { "steering_heat" },
    private val policy: VehiclePolicy = VehiclePolicy(),
) : VehicleSignalAdapter {
    override val isDemo = false
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var listener: ((AppEvent) -> Unit)? = null
    private var session: SdkSession? = null
    private var wanted = false
    @Volatile private var generation = 0L
    private var sequence = 0L
    @Volatile private var bindGeneration = 0L
    private val heatVersion = java.util.concurrent.atomic.AtomicLong()
    private val heatStartBoundary = java.util.concurrent.atomic.AtomicLong()
    private val heatCallbacksPending = java.util.concurrent.atomic.AtomicInteger()
    private var deliveredHeatVersion = -1L
    private val heatFailureVersion = java.util.concurrent.atomic.AtomicLong()
    private var practiceGeneration = 0L
    private var heatPreparing = false
    private val booleanPractices = mutableMapOf<String, BooleanPractice>()
    private var parkingRequest = 0L
    private var parkingKey: String? = null
    val preparingPractice: Boolean get() = heatPreparing || booleanPractices.values.any { it.preparing }
    private var heatSupported = false
    private var heatCapabilityResult: Boolean? = null
    private var dogamSessionFailed = false
    private var boundEpoch: Long? = null
    private var readingEpoch: Long? = null
    private var platformAllowed: Boolean? = null
    private var mapping: HeaterEvidence? = null
    private var pendingOff: Int? = null
    private var pendingLow: Int? = null
    private val preferences = this.context.getSharedPreferences("upet_vehicle_mapping", Context.MODE_PRIVATE)
    private var restrictions: PlatformUxGate? = null
    private val safety = InteractionSafety()
    private var lastSafetySnapshot: String? = null
    private var lastDrivingRestriction: Boolean? = null
    private var gearObservationVersion = -1L
    var health = ReadHealth.DISCONNECTED
        private set
    var rawHeat: Int? = null
        private set
    val evidence: HeaterEvidence? get() = mapping
    var heaterEvidenceOrigin = HeaterEvidenceOrigin.NONE
        private set
    val statusText: String get() = health.userMessage()
    val interactionAllowed: Boolean get() = platformAllowed == true
    val drivingRestricted: Boolean get() = safety.drivingRestricted
    /** Collection capability, not an initialization false or unverified value meaning. */
    val dogamCapabilities: Map<String, Boolean?> get() = mapOf(
        "steering_heat" to if (!wanted || dogamSessionFailed) null else if (heatCapabilityResult == true)
            true.takeIf { mapping?.valid == true } else heatCapabilityResult,
        "hvac_auto" to if (wanted && !dogamSessionFailed) booleanPractices["hvac_auto"]?.dogamCapability else null,
        "air_cleaner" to if (wanted && !dogamSessionFailed) booleanPractices["air_cleaner"]?.dogamCapability else null,
    )
    val canPrepare: Boolean get() = wanted && session?.closing == false &&
        boundEpoch == state().epoch && readingEpoch == null && !heatPreparing &&
        heatCallbacksPending.get() == 0 && mapping?.valid == true

    override fun initialize(listener: (AppEvent) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        this.listener = listener
        wanted = true
    }

    override fun refresh(epoch: Long) {
        if (!wanted || listener == null) return
        val current = session
        if (current == null) open()
        else if (current.closing) log("resume") { "waiting for previous callbacks" }
        else {
            val posted = state().signals["steering_heat"]
            if (heatSupported && readingEpoch != epoch && (boundEpoch != epoch ||
                posted?.supported != heatSupported || posted?.value != mapping?.normalize(rawHeat))) bindHeat(current, epoch)
            booleanPractices.values.forEach { it.refresh(epoch) }
            refreshCompanion()
        }
    }

    private fun open() {
        generation++
        boundEpoch = null; readingEpoch = null; heatSupported = false
        rawHeat = null; platformAllowed = null; heatCapabilityResult = null; dogamSessionFailed = false
        safety.begin(); lastSafetySnapshot = null; lastDrivingRestriction = null; gearObservationVersion = -1L
        publishSafety()
        booleanPractices.clear(); parkingKey = null; parkingRequest++
        mapping = null; heaterEvidenceOrigin = HeaterEvidenceOrigin.NONE; pendingOff = null; pendingLow = null
        content.features.forEach { emit(AppEvent.Capability(it.id, false)) }
        status(ReadHealth.DISCONNECTED)
        val source = SdkSession(context, main, ::logCallback) { old ->
            if (session === old) {
                session = null
                if (wanted && listener != null) open()
            }
        }
        session = source
        source.onFailure = { error ->
            dogamSessionFailed = true; heatCapabilityResult = null
            try {
                source.failure("session", error)
                blockPlatform("SDK_SESSION_FAILED")
                acceptGear(Gear.UNKNOWN)
                cancelPreparation()
                booleanPractices.values.toList().forEach { it.close() }
                booleanPractices.clear()
                status(ReadHealth.FAILED)
            } finally {
                // Even a failing UI observer must not cause an automatic reconnect loop.
                wanted = false
            }
        }

        source.initialize(complete = {
            if (session !== source || source.closing || !wanted) return@initialize
            restrictions = OptionalPlatformGate(
                create = { guardedChanged ->
                    requireCarUxClasses(context.classLoader)
                    DrivingRestrictions(context, main, ::logCallback) { ux ->
                        if (session === source && !source.closing) guardedChanged(ux)
                    }
                }, log = ::logCallback, changed = { ux ->
                    if (session === source && !source.closing) { safety.uxChanged(ux); publishSafety() }
                }).also { it.connect() }
            connectGear(source)
            connectHeat(source)
            connectBooleanFeatures(source)
            VehicleReadProbe(source, ::logCallback,
                distance = { raw -> emit(AppEvent.Odometer(normalizedDistance(raw))) },
                tpms = { value -> emit(AppEvent.TpmsChanged(value)) }).start()
        }, failed = { error ->
            dogamSessionFailed = true; heatCapabilityResult = null
            source.failure("initialize", error)
            status(ReadHealth.FAILED)
            acceptGear(Gear.UNKNOWN)
            blockPlatform("SDK_INITIALIZE_FAILED")
        })
    }

    @Suppress("DEPRECATION")
    private fun connectGear(source: SdkSession) {
        val gear = source.vehicle.getGear()
        var started = 0L
        diagnostic { started = SystemClock.elapsedRealtime() }
        val order = ObservationOrder()
        var subscribed = false
        val changes = object : GearSelectionListener {
            override fun onSelected(value: VehicleGear?) {
                val seen = order.capture()
                source.deliver("Gear.callback") {
                    log("Gear.callback") { "value=" + value + " version=" + seen }
                    if (subscribed && order.acceptCallback(seen))
                        acceptGear(normalizeGear(value), seen)
                }
            }
            override fun onFailed(e: Exception) {
                val seen = order.capture()
                source.deliver("Gear.callback") {
                    source.failure("Gear.callback", e)
                    gearFailure(e, "GEAR_CALLBACK_FAILED", seen) { subscribed && order.acceptCallback(seen) }
                }
            }
        }
        fun registerAndConfirm() {
            source.subscribe("Gear", { gear.registerGearSelection(changes) },
                { gear.unregisterGearSelection(changes) }) { registered ->
                subscribed = registered
                if (!registered) {
                    source.unsubscribe("Gear")
                    acceptGear(Gear.UNKNOWN); return@subscribe
                }
                val requested = order.version
                source.read<VehicleGear?>("getCurrentGearSelectionState.afterRegister",
                    { ok, fail -> gear.getCurrentGearSelectionState(ok, fail) }, { value ->
                        if (order.acceptRead(requested)) {
                            log("getCurrentGearSelectionState") { "firstForeground=" + value +
                                " delayMs=" + (SystemClock.elapsedRealtime() - started) }
                            acceptGear(normalizeGear(value), requested)
                        }
                    }, { error -> gearFailure(error, "GEAR_CONFIRM_FAILED", requested) { order.acceptRead(requested) } })
            }
        }

        source.read<VehicleGear?>("getCurrentGearSelectionState",
            { ok, fail -> gear.getCurrentGearSelectionState(ok, fail) }, { value ->
                log("getCurrentGearSelectionState") { "candidate=" + value }
                registerAndConfirm()
            }, { error ->
                // A failed candidate is not a value, but permission/service failure revokes this lifetime.
                gearFailure(error, "GEAR_INITIAL_FAILED", order.version) { false }
                registerAndConfirm()
            })
    }

    private fun connectBooleanFeatures(source: SdkSession) {
        val hvac = source.vehicle.getHVAC()
        fun create(id: String, verified: Boolean,
            check: ((CapabilityConfig, List<SeatArea>) -> Unit, (Exception) -> Unit) -> Unit,
            get: (SeatArea, (Boolean?) -> Unit, (Exception) -> Unit) -> Unit,
            subscription: (SeatArea, (Boolean?) -> Unit, (Exception) -> Unit) -> BooleanPractice.Subscription): BooleanPractice =
            BooleanPractice(id, content.features.single { it.id == id }.area, source, state, selectedFeature,
                check, get, subscription, { ++sequence }, ::emit, { emit(AppEvent.Unhandled("feature_status")) },
                ::logCallback, { blockPlatform("SDK_DRIVING_RESTRICTED") }, verified,
                collectPassiveChanges = policy.collectPassiveChanges)
        booleanPractices["hvac_auto"] = create("hvac_auto", policy.hvacAutoBooleanVerified,
            { ok, fail -> hvac.checkHvacAutoCapabilities(ok, fail) },
            { area, ok, fail -> hvac.getHvacAutoState(area, ok, fail) },
            { area, updated, failed ->
                val changes = object : HvacAutoStateListener {
                    override fun onHvacAutoStateUpdated(value: Boolean?) = updated(value)
                    override fun onFailed(e: Exception) = failed(e)
                }
                BooleanPractice.Subscription({ hvac.registerHvacAutoState(area, changes) },
                    { hvac.unregisterHvacAutoState(area, changes) })
            })
        booleanPractices["air_cleaner"] = create("air_cleaner", policy.airCleanerBooleanVerified,
            { ok, fail -> hvac.checkHvacAirCleanerCapabilities(ok, fail) },
            { area, ok, fail -> hvac.getHvacAirCleanerState(area, ok, fail) },
            { area, updated, failed ->
                val changes = object : HvacAirCleanerStateListener {
                    override fun onHvacAirCleanerStateUpdated(value: Boolean?) = updated(value)
                    override fun onFailed(e: Exception) = failed(e)
                }
                BooleanPractice.Subscription({ hvac.registerHvacAirCleanerState(area, changes) },
                    { hvac.unregisterHvacAirCleanerState(area, changes) })
            })
        booleanPractices.values.forEach { it.start() }
    }

    private fun normalizedDistance(raw: Float?): Double? = policy.odometerKmPerRaw?.let { scale ->
        raw?.toDouble()?.times(scale)?.takeIf { it.isFinite() && it in 0.0..1_000_000.0 }
    }

    /** A parking record is completed only by its own fresh read, not the last observed value. */
    fun refreshCompanion() {
        val source = session ?: return
        val captured = state()
        if (source.closing || !wanted || !captured.foreground || !captured.platformAllowed || captured.gear != Gear.P) return
        val episodeId = captured.data.pendingEpisode?.id
        val key = "${captured.epoch}/${captured.data.deletionEpoch}/$episodeId/${captured.data.settings.memoryEnabled}"
        if (key == parkingKey) return
        parkingKey = key
        val request = ++parkingRequest
        fun current(): Boolean {
            val now = state()
            return session === source && !source.closing && wanted && request == parkingRequest &&
                now.foreground && now.platformAllowed && now.gear == Gear.P && now.epoch == captured.epoch &&
                now.data.deletionEpoch == captured.data.deletionEpoch && now.data.pendingEpisode?.id == episodeId
        }
        fun finish(raw: Float?) {
            if (!current()) return
            val km = normalizedDistance(raw)
            log("ParkingOdometer.get") { "raw=$raw km=$km scale=${policy.odometerKmPerRaw} episode=$episodeId" }
            emit(AppEvent.ParkingSnapshot(km, captured.epoch, captured.data.deletionEpoch, episodeId))
        }
        val odometer = source.vehicle.getOdometer()
        source.read<CapabilityConfig>("ParkingOdometer.capability",
            { ok, fail -> odometer.checkCurrentDistanceDrivenCapabilities(ok, fail) }, { config ->
                if (!current()) return@read
                if (config.component1() !in setOf(AccessType.READ, AccessType.READ_WRITE)) finish(null)
                else source.read<Float?>("ParkingOdometer.fresh",
                    { ok, fail -> odometer.getCurrentDistanceDriven(ok, fail) }, { finish(it) }, { error ->
                        if (SdkFailure.health(SdkFailure.code(error)) == ReadHealth.RESTRICTED) blockPlatform("SDK_DRIVING_RESTRICTED")
                        finish(null)
                    })
            }, { error ->
                if (SdkFailure.health(SdkFailure.code(error)) == ReadHealth.RESTRICTED) blockPlatform("SDK_DRIVING_RESTRICTED")
                finish(null)
            })
    }

    private fun connectHeat(source: SdkSession) {
        val steeringWheel = source.vehicle.getSteeringWheel()
        source.read<Pair<CapabilityConfig, AreaBounds<Int>>>("checkHvacSteeringWheelHeatCapabilities",
            { ok, fail ->
                steeringWheel.checkHvacSteeringWheelHeatCapabilities(
                    { config: CapabilityConfig, bounds: AreaBounds<Int> -> ok(config to bounds) }, fail)
            }, { (config, bounds) ->
                val access = config.component1()
                val (minimum, maximum) = bounds
                log("checkHvacSteeringWheelHeatCapabilities") { "access=" + access +
                    " min=" + minimum + " max=" + maximum + " area=steering(no SDK area argument)" }
                val readable = access == AccessType.READ || access == AccessType.READ_WRITE
                heatSupported = readable && minimum <= maximum
                heatCapabilityResult = when { !readable -> false; minimum > maximum -> null; else -> true }
                emit(AppEvent.Capability("steering_heat", heatSupported))
                if (!readable) status(ReadHealth.UNSUPPORTED)
                else if (minimum > maximum) status(ReadHealth.UNKNOWN)
                else {
                    mapping = loadEvidence(minimum, maximum)
                    val observed = heaterEvidenceOrigin == HeaterEvidenceOrigin.STORED_OBSERVATION
                    pendingOff = mapping?.off?.takeIf { observed }; pendingLow = mapping?.low?.takeIf { observed }
                    bindHeat(source, state().epoch)
                }
            }, { error -> heatFailure(source, error) })
    }

    private fun bindHeat(source: SdkSession, epoch: Long) {
        if (source.closing || !heatSupported) return
        source.unsubscribe("SteeringWheelHeat")
        boundEpoch = null
        readingEpoch = epoch
        val binding = ++bindGeneration
        val latest = HeatObservations()
        val steeringWheel = source.vehicle.getSteeringWheel()
        val changes = object : HvacSteeringWheelHeatStateListener {
            override fun onHvacSteeringWheelHeatStateUpdated(value: Int?) {
                val observedStartBoundary = heatStartBoundary.get()
                heatCallbacksPending.incrementAndGet()
                val observedVersion = heatVersion.incrementAndGet()
                latest.record(value, observedVersion)
                // Capture before posting to the UI, never retag an old transition with a new session.
                val captured = state()
                source.deliver("HvacSteeringWheelHeatState.callback", finalized = ::heatCallbackFinished) {
                    log("HvacSteeringWheelHeatState.callback") { "raw=" + value +
                        " boundEpoch=" + epoch + " sourceEpoch=" + captured.epoch +
                        " session=" + captured.session?.id }
                    if ((observedStartBoundary and 1L) != 0L || heatStartBoundary.get() != observedStartBoundary) return@deliver
                    if (captured.epoch != epoch || boundEpoch != epoch || binding != bindGeneration) return@deliver
                    if (observedVersion <= deliveredHeatVersion) return@deliver
                    deliveredHeatVersion = observedVersion
                    rawHeat = value
                    val active = captured.session
                    if (active == null) {
                        if (canCollectPassiveChange(policy.collectPassiveChanges, captured, state(), epoch)) {
                            val fence = PassiveCollectionFence(epoch, observedVersion,
                                heatFailureVersion.get(), observedStartBoundary)
                            source.read<Int?>("getHvacSteeringWheelHeatState.verifyCollection",
                                { ok, fail -> steeringWheel.getHvacSteeringWheelHeatState(ok, fail) }, { now ->
                                    if (binding == bindGeneration && boundEpoch == epoch &&
                                        fence.mayPublish(policy.collectPassiveChanges, captured, state(),
                                            heatVersion.get(), heatFailureVersion.get(), heatStartBoundary.get(),
                                            heatCallbacksPending.get()))
                                        publish(now, epoch, snapshot = now != value)
                                }, { error ->
                                    // A failed current-lifetime read revokes its baseline even
                                    // when a newer callback made this read's value stale.
                                    if (binding == bindGeneration && state().epoch == epoch)
                                        heatFailure(source, error)
                                })
                        } else publish(value, epoch, true)
                    }
                    else {
                        // A delayed callback must still agree with a fresh read in this session.
                        source.read<Int?>("getHvacSteeringWheelHeatState.verify",
                            { ok, fail -> steeringWheel.getHvacSteeringWheelHeatState(ok, fail) }, { now ->
                                if (binding == bindGeneration && heatVersion.get() == observedVersion && now == value &&
                                    heatStartBoundary.get() == observedStartBoundary &&
                                    state().session?.id == active.id && state().epoch == epoch)
                                    publish(value, epoch, false, active.id)
                            }, { error ->
                                if (binding == bindGeneration && state().epoch == epoch && state().session?.id == active.id)
                                    heatFailure(source, error)
                            })
                    }
                }
            }
            override fun onFailed(e: Exception) {
                heatCallbacksPending.incrementAndGet()
                val observedVersion = heatVersion.incrementAndGet()
                latest.record(null, observedVersion, failed = true)
                source.deliver("HvacSteeringWheelHeatState.callback", finalized = ::heatCallbackFinished) {
                    if (binding == bindGeneration && state().epoch == epoch && observedVersion > deliveredHeatVersion) {
                        deliveredHeatVersion = observedVersion
                        heatFailure(source, e)
                    }
                }
            }
        }
        source.read<Int?>("getHvacSteeringWheelHeatState",
            { ok, fail -> steeringWheel.getHvacSteeringWheelHeatState(ok, fail) }, { raw ->
                if (binding != bindGeneration) return@read
                if (state().epoch != epoch) { readingEpoch = null; refresh(state().epoch); return@read }
                publish(raw, epoch, true)
                source.subscribe("SteeringWheelHeat",
                    { log("registerHvacSteeringWheelHeatState") { "call" }; steeringWheel.registerHvacSteeringWheelHeatState(changes) },
                    { log("unregisterHvacSteeringWheelHeatState") { "call" }; steeringWheel.unregisterHvacSteeringWheelHeatState(changes) }) { registered ->
                    if (binding != bindGeneration || state().epoch != epoch) return@subscribe
                if (!registered) {
                    heatCapabilityResult = null
                    readingEpoch = null; publish(null, epoch, true); status(ReadHealth.FAILED); return@subscribe
                }
                // Close the get/register gap without overwriting a newer callback.
                val confirmationVersion = heatVersion.get()
                val confirmationFailureVersion = heatFailureVersion.get()
                source.read<Int?>("getHvacSteeringWheelHeatState.afterRegister",
                    { ok, fail -> steeringWheel.getHvacSteeringWheelHeatState(ok, fail) }, { confirmed ->
                        if (binding != bindGeneration) return@read
                        readingEpoch = null
                        if (state().epoch == epoch) {
                            val observation = latest.confirm(confirmed, confirmationVersion,
                                heatFailureVersion.get() != confirmationFailureVersion)
                            if (observation == null) {
                                log("getHvacSteeringWheelHeatState.afterRegister") { "discarded after heat error" }
                                return@read
                            }
                            boundEpoch = epoch
                            deliveredHeatVersion = maxOf(deliveredHeatVersion, observation.version)
                            publish(observation.raw, epoch, true)
                        }
                        else refresh(state().epoch)
                    }, { error ->
                        if (binding == bindGeneration && state().epoch == epoch) {
                            readingEpoch = null; heatFailure(source, error)
                        }
                    })
                }
            }, { error ->
                        if (binding == bindGeneration && state().epoch == epoch) {
                            readingEpoch = null; heatFailure(source, error)
                        }
                    })
    }

    private fun heatCallbackFinished() {
        if (heatCallbacksPending.decrementAndGet() == 0 && wanted && session?.closing == false)
            status(health)
    }

    fun cancelPreparation() {
        booleanPractices.values.forEach { it.cancelPreparation() }
        practiceGeneration++
        heatPreparing = false
    }

    /** A single cancellable preparation read prevents an old request from arming a new practice. */
    fun canSelectPractice(featureId: String): Boolean = if (featureId == "steering_heat")
        canPrepare && state().session == null && state().foreground && state().gear == Gear.P && interactionAllowed
        else booleanPractices[featureId]?.readyForSelection == true

    fun canPrepare(featureId: String): Boolean = if (featureId == "steering_heat")
        canPrepare && selectedFeature() == featureId else booleanPractices[featureId]?.canPrepare == true

    fun healthFor(featureId: String): ReadHealth = if (featureId == "steering_heat") health
        else booleanPractices[featureId]?.health ?: ReadHealth.UNKNOWN

    fun statusTextFor(featureId: String): String = healthFor(featureId).userMessage()

    fun preparePractice(featureId: String, action: () -> Unit) {
        if (featureId == "steering_heat") prepareHeatPractice(action) else booleanPractices[featureId]?.preparePractice(action)
    }

    private fun prepareHeatPractice(action: () -> Unit) {
        val source = session ?: return
        val captured = state()
        if (heatPreparing || heatCallbacksPending.get() != 0 || source.closing || boundEpoch != captured.epoch || !captured.foreground ||
            !captured.platformAllowed || captured.gear != Gear.P) return
        val request = ++practiceGeneration
        val requestHeatVersion = heatVersion.get()
        heatPreparing = true
        status(health)
        val steeringWheel = source.vehicle.getSteeringWheel()
        source.read<Int?>("getHvacSteeringWheelHeatState.beforePractice",
            { ok, fail -> steeringWheel.getHvacSteeringWheelHeatState(ok, fail) }, { raw ->
                if (request != practiceGeneration) return@read
                heatPreparing = false
                if (heatVersion.get() != requestHeatVersion || heatCallbacksPending.get() != 0) {
                    log("preparePractice") { "cancelled newer heat observation" }; status(health); return@read
                }
                if (state().epoch == captured.epoch && state().session == null) {
                    publish(raw, captured.epoch, true)
                    val current = state()
                    if (request == practiceGeneration && !source.closing &&
                        heatVersion.get() == requestHeatVersion && heatCallbacksPending.get() == 0 &&
                        current.epoch == captured.epoch && current.session == null &&
                        current.foreground && current.platformAllowed && current.gear == Gear.P) {
                        heatStartBoundary.incrementAndGet()
                        try { action() } finally { heatStartBoundary.incrementAndGet() }
                    } else status(health)
                } else status(health)
            }, { error ->
                if (request == practiceGeneration) {
                    heatPreparing = false
                    if (state().epoch == captured.epoch) heatFailure(source, error)
                }
            })
    }

    /** Debug measurement control: label an actual fresh read, never set a vehicle value. */
    fun observeConsole(label: String) {
        if (label !in setOf("Off", "Low")) return
        val source = session ?: return
        val captured = state()
        if (!captured.foreground || !captured.platformAllowed || captured.gear != Gear.P) return
        val steeringWheel = source.vehicle.getSteeringWheel()
        source.read<Int?>("getHvacSteeringWheelHeatState.observe." + label,
            { ok, fail -> steeringWheel.getHvacSteeringWheelHeatState(ok, fail) }, { raw ->
                val old = mapping
                if (state().epoch != captured.epoch || raw == null || old == null ||
                    raw !in old.minimum..old.maximum) return@read
                if (label == "Off") pendingOff = raw else pendingLow = raw
                mapping = old.copy(off = pendingOff, low = pendingLow)
                heaterEvidenceOrigin = HeaterEvidenceOrigin.NONE
                val proof = mapping!!
                val saved = if (proof.valid) preferences.edit()
                    .putInt("minimum", proof.minimum).putInt("maximum", proof.maximum)
                    .putInt("off", proof.off!!).putInt("low", proof.low!!)
                    .putString("sdk", "2.3.2").commit() else false
                if (saved) heaterEvidenceOrigin = HeaterEvidenceOrigin.STORED_OBSERVATION
                log("HeaterEvidence") { "label=" + label + " raw=" + raw + " min=" + proof.minimum +
                    " max=" + proof.maximum + " off=" + proof.off + " low=" + proof.low +
                    " valid=" + proof.valid + " persisted=" + saved }
                if (proof.valid && !saved) {
                    mapping = proof.copy(off = null, low = null)
                    status(ReadHealth.FAILED)
                }
                publish(raw, state().epoch, true)
            }, { error -> heatFailure(source, error) })
    }

    private fun loadEvidence(minimum: Int, maximum: Int): HeaterEvidence {
        val stored = try { preferences.all } catch (_: Exception) { null }
        val resolved = resolveHeaterEvidence(minimum, maximum, stored, policy.heaterEvidence)
        heaterEvidenceOrigin = resolved.origin
        return resolved.evidence
    }

    private fun publish(raw: Int?, epoch: Long, snapshot: Boolean, sessionId: Long? = null) {
        rawHeat = raw
        val normalized = mapping?.normalize(raw)
        status(if (normalized == null) ReadHealth.UNKNOWN else ReadHealth.READY)
        log("Heat.normalized") { "raw=" + raw + " value=" + normalized + " snapshot=" + snapshot +
            " epoch=" + epoch + " session=" + sessionId }
        emit(AppEvent.Signal("steering_heat", "steering", normalized, ++sequence, epoch, snapshot, sessionId))
    }

    private fun heatFailure(source: SdkSession, error: Exception) {
        cancelPreparation()
        heatVersion.incrementAndGet()
        heatFailureVersion.incrementAndGet()
        val kind = SdkFailure.health(SdkFailure.code(error))
        heatCapabilityResult = if (kind == ReadHealth.UNSUPPORTED) false else null
        status(kind)
        if (kind == ReadHealth.UNSUPPORTED) {
            heatSupported = false
            source.unsubscribe("SteeringWheelHeat")
            emit(AppEvent.Capability("steering_heat", false))
        } else {
            emit(AppEvent.Signal("steering_heat", "steering", null, ++sequence, state().epoch, true))
            if (kind == ReadHealth.RESTRICTED) blockPlatform("SDK_DRIVING_RESTRICTED")
        }
    }

    private fun acceptGear(value: Gear, observedVersion: Long = -1L) {
        gearObservationVersion = observedVersion
        safety.acceptGear(value)
        // Revoke before delivering D/UNKNOWN; permit only after the engine also observes P.
        if (!safety.decision.allowed) publishSafety()
        emit(AppEvent.GearChanged(value))
        publishSafety()
    }
    private fun gearFailure(error: Exception, reason: String, observedVersion: Long, acceptVersion: () -> Boolean) {
        deliverGearFailure(SdkFailure.code(error), reason, acceptVersion, ::blockPlatform) {
            acceptGear(Gear.UNKNOWN, observedVersion)
        }
    }
    private fun blockPlatform(reason: String) {
        safety.block(reason)
        publishSafety()
    }
    private fun publishSafety() {
        val decision = safety.decision
        diagnostic {
            val snapshot = listOf(decision, safety.ux, safety.gear, safety.gearConfirmed, gearObservationVersion).toString()
            if (snapshot != lastSafetySnapshot) {
                lastSafetySnapshot = snapshot
                val json = org.json.JSONObject().put("allowed", decision.allowed)
                    .put("source", decision.source).put("reason", decision.reason)
                    .put("ux", safety.ux.name).put("gear", safety.gear.name)
                    .put("gearConfirmed", safety.gearConfirmed).put("gearVersion", gearObservationVersion)
                    .put("fallbackEnabled", policy.allowGearFallbackWithoutUx)
                    .put("generation", generation).put("epoch", state().epoch)
                    .put("timestampMs", System.currentTimeMillis())
                log("SafetyDecision") { json.toString() }
            }
        }
        val restrictionChanged = lastDrivingRestriction != safety.drivingRestricted
        lastDrivingRestriction = safety.drivingRestricted
        if (platformAllowed == decision.allowed) {
            // A pending(false) -> restricted(false) change must still reach the Activity.
            if (restrictionChanged) emit(AppEvent.Unhandled("vehicle_driving_restriction"))
            return
        }
        platformAllowed = decision.allowed
        log("Platform") { "allowed=" + decision.allowed }
        emit(AppEvent.Platform(decision.allowed))
    }
    private fun status(value: ReadHealth) {
        health = value
        emit(AppEvent.Unhandled("vehicle_status"))
    }
    private fun emit(event: AppEvent) { if (wanted) listener?.invoke(event) }

    override fun release() {
        wanted = false
        heatCapabilityResult = null; dogamSessionFailed = true
        safety.close(); gearObservationVersion = -1L; publishSafety()
        cancelPreparation()
        bindGeneration++
        listener = null
        booleanPractices.values.forEach { it.close() }; booleanPractices.clear(); parkingRequest++; parkingKey = null
        restrictions?.disconnect(); restrictions = null
        session?.close()
        boundEpoch = null; readingEpoch = null
        health = ReadHealth.DISCONNECTED
    }
    private fun logCallback(call: String, result: String) = log(call) { result }
    private inline fun log(call: String, result: () -> String) {
        diagnostic { Log.i("PetVehicle", "tMs=" + System.currentTimeMillis() + " elapsedMs=" +
            SystemClock.elapsedRealtime() + " generation=" + generation + " call=" + call + " " + result()) }
    }
    private fun normalizeGear(value: VehicleGear?): Gear = when (value) {
        VehicleGear.GEAR_PARK -> Gear.P
        VehicleGear.GEAR_DRIVE -> Gear.D
        VehicleGear.GEAR_REVERSE -> Gear.R
        VehicleGear.GEAR_NEUTRAL -> Gear.N
        else -> Gear.UNKNOWN
    }
}
