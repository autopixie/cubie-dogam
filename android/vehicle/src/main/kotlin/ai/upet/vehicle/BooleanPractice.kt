package ai.upet.vehicle

import android.os.Looper
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.upet.core.AppEvent
import ai.upet.core.EngineState
import ai.upet.core.Gear
import ai.upet.core.PracticeSession
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** One Boolean feature in one foreground connection. Public entry points run on main. */
internal class BooleanPractice(
    val featureId: String,
    private val engineArea: String,
    private val source: ReadSession,
    private val state: () -> EngineState,
    private val selectedFeature: () -> String?,
    private val checkCapabilities: ((CapabilityConfig, List<SeatArea>) -> Unit, (Exception) -> Unit) -> Unit,
    private val readValue: (SeatArea, (Boolean?) -> Unit, (Exception) -> Unit) -> Unit,
    private val createSubscription: (SeatArea, (Boolean?) -> Unit, (Exception) -> Unit) -> Subscription,
    private val nextSequence: () -> Long,
    private val emit: (AppEvent) -> Unit,
    private val changed: () -> Unit,
    private val log: (String, String) -> Unit,
    private val restricted: () -> Unit,
    private val meaningVerified: Boolean = true,
    private val collectPassiveChanges: Boolean = false,
    private val mainThreadCheck: () -> Unit = { check(Looper.myLooper() == Looper.getMainLooper()) },
) {
    data class Subscription(val register: () -> Unit, val unregister: () -> Unit)
    private data class CapabilityResult(val config: CapabilityConfig, val areas: List<SeatArea>)
    private data class Observation(val raw: Boolean?, val version: Long, val failed: Boolean)
    private val closed = AtomicBoolean()
    private val binding = AtomicLong()
    private val version = AtomicLong()
    private val startBoundary = AtomicLong()
    private val failureFence = AtomicLong()
    private val callbacksPending = AtomicInteger()
    private val subscriptionName = "BooleanPractice.$featureId"
    private var started = false
    private var readsPending = 0
    private var capabilityFailedEpoch: Long? = null
    private var deliveredVersion = -1L
    private var preparationGeneration = 0L
    private var boundEpoch: Long? = null
    private var readingEpoch: Long? = null
    var supported = false
        private set
    var capabilityResult: Boolean? = null
        private set
    /** Collection availability: confirmed API capability and confirmed Boolean meaning. */
    val dogamCapability: Boolean? get() = if (!live() || (capabilityResult == true && !meaningVerified)) null else capabilityResult
    var selectedArea: SeatArea? = null
        private set
    var rawValue: Boolean? = null
        private set
    var value: Boolean? = null
        private set
    var health = ReadHealth.DISCONNECTED
        private set
    var preparing = false
        private set

    val canPrepare: Boolean get() = readyForSelection && selectedFeature() == featureId

    val readyForSelection: Boolean get() {
        assertMain()
        val current = state()
        return live() && meaningVerified && supported && selectedArea != null &&
            boundEpoch == current.epoch && readingEpoch == null && !preparing && readsPending == 0 &&
            callbacksPending.get() == 0 && health == ReadHealth.READY && value != null &&
            current.session == null && allowed(current)
    }

    init { assertMain(); require(featureId.isNotBlank() && engineArea.isNotBlank()) }

    fun start() {
        assertMain()
        if (!live() || started) return
        started = true
        capabilityResult = null
        emit(AppEvent.Capability(featureId, false)); notifyChanged()
        val token = binding.get()
        request<CapabilityResult>(token, "capability", { ok, fail ->
            checkCapabilities({ config, areas -> ok(CapabilityResult(config, areas.toList())) }, fail)
        }, { result ->
            val access = result.config.component1()
            val area = result.areas.firstOrNull()
            supported = access in setOf(AccessType.READ, AccessType.READ_WRITE) && area != null
            selectedArea = area.takeIf { supported }
            capabilityResult = supported
            log("$featureId.capability", "access=$access areas=${result.areas} selected=$selectedArea")
            emit(AppEvent.Capability(featureId, supported))
            if (supported) bind(state().epoch)
            else { rawValue = null; value = null; health = ReadHealth.UNSUPPORTED; notifyChanged() }
        }, { error ->
            capabilityFailedEpoch = state().epoch
            fail(token, state().epoch, error)
        })
    }

    fun refresh(epoch: Long) {
        assertMain()
        if (!live() || state().epoch != epoch) return
        if (!started) { start(); return }
        if (!supported && health != ReadHealth.UNSUPPORTED && capabilityFailedEpoch != null &&
            capabilityFailedEpoch != epoch && readsPending == 0) { started = false; start(); return }
        if (!supported || selectedArea == null || readingEpoch == epoch) return
        val posted = state().signals[featureId]
        if (boundEpoch == epoch && (health == ReadHealth.READY || !meaningVerified) &&
            posted != null && posted.supported == supported && posted.value == value) return
        bind(epoch)
    }

    fun cancelPreparation() {
        assertMain()
        val wasPreparing = preparing
        preparationGeneration++; preparing = false
        if (wasPreparing) notifyChanged()
    }

    /** The owner synchronously starts this feature after the fresh snapshot is accepted. */
    fun preparePractice(action: () -> Unit) {
        assertMain()
        if (!canPrepare) return
        val area = selectedArea ?: return
        val captured = state()
        val token = binding.get()
        val requestId = ++preparationGeneration
        val requestedVersion = version.get()
        val requestedFailure = failureFence.get()
        preparing = true; notifyChanged()
        fun stillCurrent(): Boolean {
            val current = state()
            return valid(token, captured.epoch) && requestId == preparationGeneration && preparing &&
                meaningVerified && selectedFeature() == featureId && current.session == null && allowed(current) &&
                version.get() == requestedVersion && failureFence.get() == requestedFailure &&
                callbacksPending.get() == 0 && readsPending == 1
        }
        request<Boolean?>(token, "beforePractice", { ok, fail -> readValue(area, ok, fail) }, prepared@{ raw ->
            if (requestId != preparationGeneration) return@prepared
            try {
                if (!stillCurrent()) return@prepared
                publish(raw, captured.epoch, snapshot = true)
                if (raw != null && stillCurrent()) {
                    startBoundary.incrementAndGet()
                    try { action() } finally { startBoundary.incrementAndGet() }
                }
            } finally {
                if (requestId == preparationGeneration) { preparing = false; notifyChanged() }
            }
        }, { error ->
            if (requestId == preparationGeneration) {
                preparing = false
                if (valid(token, captured.epoch)) fail(token, captured.epoch, error) else notifyChanged()
            }
        })
    }

    fun close() {
        assertMain()
        if (!closed.compareAndSet(false, true)) return
        binding.incrementAndGet(); preparationGeneration++; preparing = false
        source.unsubscribe(subscriptionName)
        boundEpoch = null; readingEpoch = null; supported = false; selectedArea = null; capabilityResult = null
        rawValue = null; value = null; health = ReadHealth.DISCONNECTED
    }

    private fun bind(epoch: Long) {
        val area = selectedArea ?: return
        if (!live() || !supported || state().epoch != epoch) return
        preparationGeneration++; preparing = false
        val token = binding.incrementAndGet()
        source.unsubscribe(subscriptionName); boundEpoch = null; readingEpoch = epoch
        val latest = AtomicReference<Observation?>()
        publish(null, epoch, snapshot = true)
        request<Boolean?>(token, "get", { ok, fail -> readValue(area, ok, fail) }, initial@{ raw ->
            if (!valid(token, epoch)) return@initial
            publish(raw, epoch, snapshot = true)
            val subscription = createSubscription(area,
                { observed -> enqueue(token, epoch, area, observed, null, latest) },
                { error -> enqueue(token, epoch, area, null, error, latest) })
            source.subscribe(subscriptionName, subscription.register, subscription.unregister) { registered ->
                if (!valid(token, epoch)) return@subscribe
                if (!registered) {
                source.unsubscribe(subscriptionName)
                fail(token, epoch, IllegalStateException("$featureId registration failed")); return@subscribe
            }
            val requestedVersion = version.get()
            val requestedFailure = failureFence.get()
            request<Boolean?>(token, "afterRegister", { ok, fail -> readValue(area, ok, fail) }, confirmed@{ confirmed ->
                if (!valid(token, epoch)) return@confirmed
                readingEpoch = null
                val observation = latest.get()
                if (failureFence.get() != requestedFailure || observation?.failed == true) {
                    log("$featureId.afterRegister", "discard after error"); notifyChanged(); return@confirmed
                }
                val newer = observation?.takeIf { it.version > requestedVersion }
                boundEpoch = epoch
                deliveredVersion = maxOf(deliveredVersion, newer?.version ?: requestedVersion)
                // A newer null is an observation, not permission to reuse the getter value.
                publish(if (newer != null) newer.raw else confirmed, epoch, snapshot = true)
            }, { error -> if (valid(token, epoch)) fail(token, epoch, error) })
            }
        }, { error -> if (valid(token, epoch)) fail(token, epoch, error) })
    }

    /** Callback thread: capture only atomics and the engine's synchronized state. */
    private fun enqueue(token: Long, epoch: Long, area: SeatArea, raw: Boolean?, error: Exception?,
        latest: AtomicReference<Observation?>) {
        val observedStartBoundary = startBoundary.get()
        if (closed.get() || binding.get() != token) return
        callbacksPending.incrementAndGet()
        val observedVersion = version.incrementAndGet()
        if (error != null) failureFence.incrementAndGet()
        val captured = state()
        val observation = Observation(raw, observedVersion, error != null)
        latest.updateAndGet { old -> if (old == null || old.version < observedVersion) observation else old }
        source.deliver("$featureId.callback", finalized = {
            if (callbacksPending.decrementAndGet() == 0) notifyChanged()
        }, action = delivery@{
            if (!valid(token, epoch) || captured.epoch != epoch || observedVersion <= deliveredVersion) return@delivery
            deliveredVersion = observedVersion
            log("$featureId.callback", "raw=$raw failed=${error != null} area=$area epoch=$epoch session=${captured.session?.id} version=$observedVersion")
            if (error != null) { fail(token, epoch, error); return@delivery }
            if ((observedStartBoundary and 1L) != 0L || startBoundary.get() != observedStartBoundary) return@delivery
            if (boundEpoch != epoch) return@delivery
            val practice = ownPractice(captured)
            val current = ownPractice(state())
            if (practice == null) {
                if (current == null) {
                    if (meaningVerified && canCollectPassiveChange(collectPassiveChanges, captured, state(), epoch))
                        verifyCollection(token, epoch, area, raw, observedVersion, observedStartBoundary, captured)
                    else publish(raw, epoch, snapshot = true)
                }
                return@delivery
            }
            if (current?.id == practice.id && selectedFeature() == featureId)
                verify(token, epoch, area, raw, observedVersion, observedStartBoundary, practice)
        })
    }

    /** A live collector edge needs the same fresh-value and lifetime fences as explicit practice. */
    private fun verifyCollection(token: Long, epoch: Long, area: SeatArea, observed: Boolean?,
        observedVersion: Long, observedStartBoundary: Long, captured: EngineState) {
        val fence = PassiveCollectionFence(epoch, observedVersion, failureFence.get(), observedStartBoundary)
        request<Boolean?>(token, "verifyCollection", { ok, fail -> readValue(area, ok, fail) }, verified@{ currentValue ->
            if (!valid(token, epoch) || boundEpoch != epoch || !meaningVerified ||
                !fence.mayPublish(collectPassiveChanges, captured, state(), version.get(), failureFence.get(),
                    startBoundary.get(), callbacksPending.get())) return@verified
            // A disagreement is a new baseline, never a fabricated Off -> On edge.
            publish(currentValue, epoch, snapshot = currentValue != observed)
        }, { error ->
            // request.reject records the failure and advances version before this handler.
            // A newer value cannot restore trust in a failed current binding/epoch read.
            if (valid(token, epoch)) fail(token, epoch, error)
        })
    }

    private fun verify(token: Long, epoch: Long, area: SeatArea, observed: Boolean?, observedVersion: Long, observedStartBoundary: Long,
        practice: PracticeSession) {
        val requestedFailure = failureFence.get()
        request<Boolean?>(token, "verify", { ok, fail -> readValue(area, ok, fail) }, verified@{ currentValue ->
            val current = state()
            if (!valid(token, epoch) || boundEpoch != epoch || ownPractice(current)?.id != practice.id ||
                selectedFeature() != featureId || !allowed(current) || version.get() != observedVersion ||
                failureFence.get() != requestedFailure || callbacksPending.get() != 0 ||
                startBoundary.get() != observedStartBoundary) return@verified
            if (currentValue != observed) {
                log("$featureId.verify", "mismatch; publish fresh snapshot")
                publish(currentValue, epoch, snapshot = true)
            } else publish(currentValue, epoch, snapshot = false, sessionId = practice.id)
        }, { error ->
            if (valid(token, epoch) && ownPractice(state())?.id == practice.id) fail(token, epoch, error)
        })
    }

    /** Separate from SdkSession's drain, to keep unfinished old reads from arming a new practice. */
    private fun <T> request(token: Long, name: String,
        invoke: ((T) -> Unit, (Exception) -> Unit) -> Unit,
        complete: (T) -> Unit, failed: (Exception) -> Unit) {
        assertMain()
        if (!currentBinding(token)) return
        readsPending++
        val settled = AtomicBoolean()
        fun settle(action: () -> Unit) {
            if (!settled.compareAndSet(false, true)) return
            try {
                if (currentBinding(token)) {
                    try { action() } catch (error: Exception) { fail(token, state().epoch, error) }
                }
            } finally { readsPending--; notifyChanged() }
        }
        source.read<T>("$featureId.$name", invoke = { ok, fail ->
            val terminal = AtomicBoolean()
            val reject: (Exception) -> Unit = { error ->
                if (terminal.compareAndSet(false, true)) { noteFailure(token); fail(error) }
            }
            try { invoke({ result -> if (terminal.compareAndSet(false, true)) ok(result) }, reject) }
            catch (error: Exception) { reject(error) }
        }, complete = { result -> settle { complete(result) } }, failed = { error -> settle { failed(error) } })
    }

    private fun noteFailure(token: Long) {
        if (closed.get() || binding.get() != token) return
        failureFence.incrementAndGet(); version.incrementAndGet()
    }
    private fun fail(token: Long, epoch: Long, error: Exception) {
        assertMain()
        if (!valid(token, epoch)) return
        noteFailure(token); preparationGeneration++; preparing = false; readingEpoch = null
        rawValue = null; value = null
        source.failure("$featureId.failure", error)
        health = SdkFailure.health(SdkFailure.code(error))
        capabilityResult = if (health == ReadHealth.UNSUPPORTED) false else null
        if (health == ReadHealth.UNSUPPORTED) {
            supported = false; boundEpoch = null; source.unsubscribe(subscriptionName)
            emit(AppEvent.Capability(featureId, false))
        } else {
            emit(AppEvent.Signal(featureId, engineArea, null, nextSequence(), epoch, snapshot = true))
            if (health == ReadHealth.RESTRICTED) restricted()
        }
        notifyChanged()
    }
    private fun publish(raw: Boolean?, epoch: Long, snapshot: Boolean, sessionId: Long? = null) {
        assertMain()
        if (!live() || state().epoch != epoch) return
        rawValue = raw; value = if (meaningVerified) raw else null
        health = if (value == null) ReadHealth.UNKNOWN else ReadHealth.READY
        log("$featureId.value", "raw=$raw normalized=$value meaningVerified=$meaningVerified area=$selectedArea epoch=$epoch snapshot=$snapshot session=$sessionId")
        emit(AppEvent.Signal(featureId, engineArea, value, nextSequence(), epoch, snapshot || value == null, sessionId))
        notifyChanged()
    }
    private fun ownPractice(current: EngineState) = current.session?.takeIf {
        it.featureId == featureId && it.area == engineArea && it.epoch == current.epoch
    }
    private fun allowed(current: EngineState) = current.foreground && current.platformAllowed && current.gear == Gear.P
    private fun live() = !closed.get() && !source.closing
    private fun currentBinding(token: Long) = live() && binding.get() == token
    private fun valid(token: Long, epoch: Long) = currentBinding(token) && state().epoch == epoch
    private fun notifyChanged() { assertMain(); if (live()) changed() }
    private fun assertMain() { mainThreadCheck() }
}
