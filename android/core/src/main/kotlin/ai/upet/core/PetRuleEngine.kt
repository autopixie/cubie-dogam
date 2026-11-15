package ai.upet.core

import java.time.Clock

/** Pure JVM fake-signal engine. No Android, Pleos, network, storage or background subscription. */
class PetRuleEngine @JvmOverloads constructor(
    val content: ContentBundle = ContentBundle.fromResources(),
    savedJson: String? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val policy: EnginePolicy = EnginePolicy(),
) {
    private val restored = restoreState(savedJson, content, policy.recordOrigin)
    private var data = restored.first
    private var gear = Gear.UNKNOWN
    private var foreground = true
    private var platformAllowed = true
    private val signals = content.features.associate { it.id to FeatureSignal() }.toMutableMap()
    private val areas = content.features.associate { it.id to it.area }
    private var odometerKm: Double? = null
    private var tpms: Tpms? = null
    private var fuel: Double? = null
    private var session: PracticeSession? = null
    private var epoch = 0L
    private var sessionCounter = 0L
    private var lastEvent = "welcome"
    private var notice = if (restored.second) "저장 내용 형식을 읽지 못해 새 상태로 시작했어요." else ""
    private var lastMemoryId: String? = null
    private var eventCount = 0L

    val state: EngineState get() = synchronized(this) {
        EngineState(data.copy(awarded = data.awarded.toList(), memories = data.memories.toList(), places = data.places.toList()),
            gear, foreground, platformAllowed, signals.toMap(), odometerKm, tpms, fuel, session,
            epoch, sessionCounter, lastEvent, notice, lastMemoryId, eventCount)
    }

    private fun allowed() = foreground && platformAllowed && gear == Gear.P
    private fun message(id: String, text: String = "") { lastEvent = id; notice = text }
    private fun clearSignals(resetSequence: Boolean = false) {
        signals.replaceAll { _, s -> s.copy(value = null, seq = if (resetSequence) -1 else s.seq) }
    }

    private fun completeParking(at: java.time.Instant?) {
        message("welcome")
        val pending = data.pendingEpisode
        if (data.settings.memoryEnabled && pending != null && pending.deletionEpoch == data.deletionEpoch &&
            data.memories.none { it.id == pending.id }) {
            val baseline = pending.baselineKm
            val current = odometerKm
            val status = when {
                !finiteKm(baseline) || !finiteKm(current) -> "unknown"
                current!! < baseline!! -> "invalid"
                else -> "valid"
            }
            val distance = if (status == "valid") kotlin.math.floor((current!! - baseline!!) * 10 + .5) / 10 else null
            val memory = MemoryEntry(pending.id, (at ?: clock.instant()).atZone(clock.zone).toLocalDate().toString(),
                origin = policy.recordOrigin, distanceStatus = status, distanceKm = distance)
            data = data.copy(memories = (data.memories + memory).takeLast(50))
            lastMemoryId = memory.id; message("parking")
        }
        data = data.copy(pendingEpisode = null, baselineKm = odometerKm.takeIf { data.settings.memoryEnabled && finiteKm(it) })
    }

    @Synchronized
    fun dispatch(event: AppEvent): Boolean {
        if (event is AppEvent.FuelDemo && !(policy.allowFuelDemo ?: content.fuelDemoEnabled)) return false
        if (event is AppEvent.Start && policy.enabledPracticeIds?.contains(event.featureId) == false) return false
        eventCount++
        val interactive = event is AppEvent.Intro || event is AppEvent.Start || event is AppEvent.Cancel ||
            event is AppEvent.Skip || event is AppEvent.MemorySetting || event is AppEvent.LocationSetting ||
            event is AppEvent.DeleteRecords || event is AppEvent.PlaceDemo
        if (interactive && !allowed()) return false
        when (event) {
            AppEvent.Intro -> { data = data.copy(introduced = true); message("welcome") }
            is AppEvent.Start -> {
                val s = signals[event.featureId]
                session = null
                if (s == null || !s.supported || s.value == null || areas[event.featureId] != event.area) {
                    message("welcome", "현재 상태를 읽지 못했어요. 상태를 받은 뒤 시작해요.")
                } else if (s.value == true) message("already_on")
                else {
                    session = PracticeSession(++sessionCounter, event.featureId, event.area, s.seq, epoch)
                    message("waiting")
                }
            }
            AppEvent.Cancel -> { session = null; message("welcome", "실습을 취소했어요. 원할 때 다시 시작해요.") }
            AppEvent.Skip -> { session = null; message("welcome", "필요한 기능만 골라도 괜찮아요. 진도·보상은 바뀌지 않았어요.") }
            is AppEvent.Signal -> {
                val s = signals[event.featureId] ?: return false
                if (areas[event.featureId] != event.area || event.seq <= s.seq || event.seq > MAX_SAFE_INTEGER || event.epoch != epoch) return false
                if (event.sessionId != null && event.sessionId != session?.id) return false
                signals[event.featureId] = s.copy(value = event.value, seq = event.seq)
                if (event.snapshot || event.value == null) {
                    if (session?.featureId == event.featureId) session = null
                } else {
                    val armed = session
                    if (allowed() && s.supported && armed?.featureId == event.featureId && armed.area == event.area &&
                        armed.epoch == epoch && event.seq > armed.startSeq && s.value == false && event.value == true) {
                        session = null
                        if (event.featureId in data.awarded) message("repeat")
                        else { data = data.copy(awarded = data.awarded + event.featureId); message("success") }
                    }
                }
            }
            is AppEvent.Capability -> {
                val s = signals[event.featureId] ?: return false
                signals[event.featureId] = s.copy(supported = event.supported, value = null)
                if (session?.featureId == event.featureId) session = null
                message("welcome", if (event.supported) "현재 상태를 다시 받아 주세요." else "미지원 기능을 목록에서 숨겼어요.")
            }
            is AppEvent.GearChanged -> {
                val previousGear = gear
                gear = event.value
                if (gear == Gear.P && previousGear != Gear.P) { session = null; clearSignals() }
                if (!allowed()) { session = null; epoch++; message("locked") }
                if (gear == Gear.D && data.settings.memoryEnabled && data.pendingEpisode == null) {
                    val counter = data.episodeCounter + 1
                    data = data.copy(episodeCounter = counter,
                        pendingEpisode = ParkingEpisode("parking-${data.deletionEpoch}-$counter", data.deletionEpoch, data.baselineKm))
                }
                if (gear == Gear.P && foreground && !policy.deferParkingSnapshot) completeParking(event.now)
            }
            is AppEvent.Odometer -> odometerKm = event.value.takeIf(::finiteKm)
            is AppEvent.ParkingSnapshot -> {
                if (!policy.deferParkingSnapshot || !allowed() || event.epoch != epoch ||
                    event.deletionEpoch != data.deletionEpoch || event.episodeId != data.pendingEpisode?.id) return false
                odometerKm = event.value.takeIf(::finiteKm)
                completeParking(event.now)
            }
            is AppEvent.TpmsChanged -> tpms = event.value
            is AppEvent.FuelDemo -> {
                if (!event.value.isFinite() || event.value !in 0.0..100.0) return false
                val previous = fuel
                if (allowed() && previous != null && event.value > previous && !event.snapshot) message("fuel_increase")
                fuel = event.value
            }
            is AppEvent.Visibility -> {
                foreground = event.foreground; session = null; epoch++; clearSignals(true)
                if (policy.deferParkingSnapshot) odometerKm = null
                message(if (allowed()) "welcome" else "locked")
            }
            is AppEvent.Platform -> {
                platformAllowed = event.allowed; session = null; epoch++; clearSignals()
                message(if (allowed()) "welcome" else "locked")
            }
            is AppEvent.MemorySetting -> {
                data = data.copy(settings = data.settings.copy(memoryEnabled = event.enabled), pendingEpisode = null,
                    baselineKm = odometerKm.takeIf { event.enabled && finiteKm(it) })
                message("welcome", if (event.enabled) "앞으로 주차 때 일기를 이 기기에 저장해요." else "새 일기는 저장하지 않아요. 기존 기록은 전체 삭제에서 지울 수 있어요.")
            }
            is AppEvent.LocationSetting -> {
                data = data.copy(settings = data.settings.copy(locationEnabled = event.enabled))
                message("welcome", if (event.enabled) "선택한 시연용 장소 카드를 이 기기에 저장해요." else "새 장소 카드는 저장하지 않아요.")
            }
            is AppEvent.PlaceDemo -> {
                if (!data.settings.locationEnabled || event.deletionEpoch != data.deletionEpoch || event.placeId !in content.placeIds) return false
                if (data.places.any { it.id == event.placeId }) message("welcome", "이미 모은 시연용 장소 카드예요.")
                else { data = data.copy(places = data.places + PlaceCard(event.placeId)); message("place") }
            }
            AppEvent.DeleteRecords -> {
                data = data.copy(deletionEpoch = data.deletionEpoch + 1, pendingEpisode = null, baselineKm = null, memories = emptyList(), places = emptyList())
                lastMemoryId = null
                message("welcome", "일기·장소·대기 기록을 모두 지웠어요. 기능 진도는 남아 있어요.")
            }
            is AppEvent.Unhandled -> return false
        }
        return true
    }

    @Synchronized
    fun view(): EngineView {
        val basic = content.features.filter {
            (policy.basicFeatureIds?.contains(it.id) ?: it.basic) &&
                (policy.requireAllBasic || signals.getValue(it.id).supported)
        }.map { it.id }
        val target = minOf(content.basicTarget, basic.size)
        val count = basic.count { it in data.awarded }
        val stage = Stage(target, count, if (target > 0 && count >= target) "companion" else "adaptation")
        val warning = when (tpms) { Tpms.On -> content.rules.getValue("tpms_on"); Tpms.Blink -> content.rules.getValue("tpms_blink"); else -> null }
        val rule = if (!allowed()) content.rules.getValue("locked") else warning ?: content.rules[lastEvent] ?: content.rules.getValue("welcome")
        return EngineView(allowed(), stage, data.awarded.size * content.rules.getValue("success").growth,
            rule, warning, tpms == null, if (!allowed() || warning != null) rule.lines.first() else notice.ifEmpty { rule.lines.first() })
    }

    /** Serializes progress/consent only. The caller must report the result of its own disk commit. */
    @Synchronized
    fun persist(): String = serializeState(data)
}
