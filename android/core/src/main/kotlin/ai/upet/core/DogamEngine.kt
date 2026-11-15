package ai.upet.core

import java.time.Clock
import java.time.LocalDate
import kotlinx.serialization.json.*

data class DogamDiscovery(val no: Int, val metDate: String, val seasonStamp: String?)
data class DogamDispatch(val accepted: Boolean, val discovery: DogamDiscovery? = null)
enum class DogamQuizStatus { CORRECT, WRONG, ALREADY_FOUND, UNAVAILABLE, BLOCKED }
data class DogamQuizResult(val status: DogamQuizStatus, val discovery: DogamDiscovery? = null) {
    val correct: Boolean get() = status == DogamQuizStatus.CORRECT || status == DogamQuizStatus.ALREADY_FOUND
}
data class DogamView(val slots: List<DogamCard>, val discoveries: Map<Int, DogamDiscovery>,
    val foundCount: Int, val totalCount: Int, val growthStage: Int, val todayRiddle: DogamCard?,
    val capabilities: Map<String, Boolean?>)

/**
 * Automatic collection wraps the existing state-change engine instead of duplicating its signal fences.
 * The underlying engine is exposed for adapter state reads; route all writes through dispatch/observeMode.
 * Only compact discoveries are persisted. Live values, capability, questions and the old engine's diary are not.
 */
class DogamEngine(
    val catalog: DogamCatalog = DogamCatalog.fromResources(), savedJson: String? = null,
    private val clock: Clock = Clock.systemDefaultZone(), recordOrigin: String = "vehicle",
) {
    private val capabilities = catalog.carCards.associate { it.id!! to null as Boolean? }.toMutableMap()
    private val found = linkedMapOf<Int, DogamDiscovery>()
    var restoredInvalid = false
        private set
    private var modeName: String? = null
    private var modeRawSeq = -1L
    private var modeEpoch = -1L
    private var modeInternalSeq = -1L

    val engine: PetRuleEngine = PetRuleEngine(contentFor(catalog), clock = clock,
        policy = EnginePolicy(basicFeatureIds = catalog.carCards.filter { it.active1st }.map { it.id!! }.toSet(),
            requireAllBasic = true, recordOrigin = recordOrigin, deferParkingSnapshot = true,
            allowFuelDemo = false, enabledPracticeIds = catalog.carCards.map { it.id!! }.toSet()))
    val state: EngineState get() = engine.state

    init {
        // The old engine's supported=true default is never capability proof for collection.
        engine.dispatch(AppEvent.Platform(false))
        restore(savedJson)
    }

    private fun safe(s: EngineState = state): Boolean = s.foreground && s.platformAllowed && s.gear == Gear.P
    private fun active(card: DogamCard): Boolean = when {
        card.reserved -> false
        card.kind == "quiz" -> card.active1st
        capabilities[card.id] != true -> false
        card.active1st -> true
        card.backup -> catalog.carCards.any { it.no in 4..8 && capabilities[it.id] == false }
        else -> false
    }

    @Synchronized
    fun view(): DogamView {
        val slots = catalog.cards.mapNotNull { card -> when {
            card.reserved -> card
            card.backup && !active(card) -> card.copy(reserved = true)
            active(card) -> card
            else -> null
        } }
        val available = slots.filterNot { it.reserved }
        val visibleNumbers = available.map { it.no }.toSet()
        return DogamView(slots, found.toMap(), found.keys.count { it in visibleNumbers }, available.size,
            when { found.size >= 4 -> 3; found.isNotEmpty() -> 2; else -> 1 },
            available.filter { it.no !in found }.minByOrNull { it.no }, capabilities.toMap())
    }

    /** null is unconfirmed, distinct from a proven unsupported(false) SDK capability. */
    @Synchronized
    fun setCapability(featureId: String, supported: Boolean?): DogamDispatch {
        if (featureId !in capabilities) return DogamDispatch(false)
        capabilities[featureId] = supported
        if (featureId == "drive_mode") clearModeBaseline(resetExternalSequence = false)
        // This false is only the underlying collector's eligibility gate. view.capabilities retains null.
        return DogamDispatch(engine.dispatch(AppEvent.Capability(featureId, supported == true)))
    }

    /** A Boolean signal for drive_mode is deliberately not a raw-mode observation. */
    @Synchronized
    fun dispatch(event: AppEvent): DogamDispatch {
        if (event is AppEvent.Signal && (event.featureId == "drive_mode" ||
            catalog.byId[event.featureId]?.kind != "car")) return DogamDispatch(false)
        if (event is AppEvent.Start || event is AppEvent.MemorySetting || event is AppEvent.LocationSetting ||
            event is AppEvent.PlaceDemo || event is AppEvent.FuelDemo) return DogamDispatch(false)
        if (event is AppEvent.Capability) return setCapability(event.featureId, event.supported)
        if (event is AppEvent.Visibility) capabilities.keys.forEach { capabilities[it] = null }
        val before = state
        val card = (event as? AppEvent.Signal)?.let { catalog.byId[it.featureId] }
        val armed = event is AppEvent.Signal && card != null && card.kind == "car" && card.no !in found && active(card) &&
            !event.snapshot && event.value == true && event.sessionId == null && safe(before) &&
            event.epoch == before.epoch && event.area == areaFor(event.featureId) &&
            event.seq in 0..MAX_SAFE_INTEGER && event.seq > (before.signals[event.featureId]?.seq ?: -1L) &&
            before.signals[event.featureId]?.supported == true && before.signals[event.featureId]?.value == false
        if (armed) engine.dispatch(AppEvent.Start((event as AppEvent.Signal).featureId, event.area))
        val accepted = engine.dispatch(event)
        // Visibility is not fresh UX authorization. Resume must receive an explicit current permit.
        if (event is AppEvent.Visibility) engine.dispatch(AppEvent.Platform(false))
        val after = state
        val discovery = if (armed && accepted && card != null &&
            card.id !in before.data.awarded && card.id in after.data.awarded) remember(card) else null
        if (armed && state.session != null) engine.dispatch(AppEvent.Cancel)
        if (after.epoch != before.epoch || event is AppEvent.GearChanged || event is AppEvent.Visibility ||
            event is AppEvent.Platform) clearModeBaseline()
        return DogamDispatch(accepted, discovery)
    }

    /**
     * Card 008's explicit rule: a live change to a non-NORMAL mode, including ECO -> SPORT.
     * The Boolean fed to PetRuleEngine is an INTERNAL eligible-change edge, never a vehicle Off/On claim.
     */
    @Synchronized
    fun observeMode(rawName: String?, seq: Long, epoch: Long, snapshot: Boolean = false): DogamDispatch {
        val current = state
        val card = catalog.byId.getValue("drive_mode")
        if (epoch != current.epoch || seq !in 0..MAX_SAFE_INTEGER || !safe(current) ||
            capabilities[card.id] != true || !active(card)) return DogamDispatch(false)
        if (modeEpoch != epoch) { clearModeBaseline(); modeEpoch = epoch }
        if (seq <= modeRawSeq) return DogamDispatch(false)
        modeRawSeq = seq
        val previous = modeName
        modeName = rawName?.takeIf { it in setOf("NORMAL", "ECO", "SPORT") }
        if (modeName == null) {
            // Match the verified bridge vocabulary and break the baseline on malformed input.
            engine.dispatch(AppEvent.Signal("drive_mode", areaFor("drive_mode"), null,
                ++modeInternalSeq, epoch, snapshot = true))
            return DogamDispatch(false)
        }
        val changed = !snapshot && previous != null && modeName != null && modeName != previous && modeName != "NORMAL"
        val before = state
        // Fresh raw baselines are represented only as a false internal predicate snapshot.
        val baseline = AppEvent.Signal("drive_mode", areaFor("drive_mode"), false, ++modeInternalSeq,
            epoch, snapshot = true)
        if (!engine.dispatch(baseline)) return DogamDispatch(false)
        if (!changed || card.no in found) return DogamDispatch(true)
        engine.dispatch(AppEvent.Start("drive_mode", areaFor("drive_mode")))
        val accepted = engine.dispatch(AppEvent.Signal("drive_mode", areaFor("drive_mode"), true,
            ++modeInternalSeq, epoch, snapshot = false))
        val discovery = if (accepted && card.id !in before.data.awarded && card.id in state.data.awarded)
            remember(card) else null
        if (state.session != null) engine.dispatch(AppEvent.Cancel)
        return DogamDispatch(accepted, discovery)
    }

    @Synchronized
    fun answerQuiz(no: Int, choice: Int): DogamQuizResult {
        if (!safe()) return DogamQuizResult(DogamQuizStatus.BLOCKED)
        val card = catalog.byNo[no]?.takeIf { active(it) && it.kind == "quiz" }
            ?: return DogamQuizResult(DogamQuizStatus.UNAVAILABLE)
        val quiz = card.quiz ?: return DogamQuizResult(DogamQuizStatus.UNAVAILABLE)
        if (choice !in quiz.choices.indices) return DogamQuizResult(DogamQuizStatus.UNAVAILABLE)
        if (no in found) return DogamQuizResult(DogamQuizStatus.ALREADY_FOUND)
        if (choice != quiz.answer) return DogamQuizResult(DogamQuizStatus.WRONG)
        return DogamQuizResult(DogamQuizStatus.CORRECT, remember(card))
    }

    private fun remember(card: DogamCard): DogamDiscovery? {
        if (card.no in found) return null
        val date = LocalDate.now(clock)
        return DogamDiscovery(card.no, date.toString(),
            if (card.seasonVariant?.months?.contains(date.monthValue) == true) "겨울" else null)
            .also { found[card.no] = it }
    }

    private fun clearModeBaseline(resetExternalSequence: Boolean = true) {
        modeName = null
        if (resetExternalSequence) { modeRawSeq = -1L; modeEpoch = -1L }
        // The internal engine's sequence is retained across Platform/gear fences.
        modeInternalSeq = state.signals["drive_mode"]?.seq ?: -1L
    }

    @Synchronized
    fun persist(): String = buildJsonObject {
        put("schemaVersion", 1)
        putJsonArray("cards") { found.values.sortedBy { it.no }.forEach { record -> add(buildJsonObject {
            put("no", record.no); put("metDate", record.metDate)
            put("seasonStamp", record.seasonStamp?.let(::JsonPrimitive) ?: JsonNull)
        }) } }
    }.toString()

    private fun restore(savedJson: String?) {
        if (savedJson == null) return
        try {
            val raw = Json.parseToJsonElement(savedJson).jsonObject
            require(raw.keys == setOf("schemaVersion", "cards") && raw["schemaVersion"] == JsonPrimitive(1))
            val records = raw.array("cards")
            require(records.size <= catalog.cards.count { !it.reserved })
            val restored = records.map { element ->
                val r = element.jsonObject
                require(r.keys == setOf("no", "metDate", "seasonStamp"))
                val noValue = r.getValue("no").jsonPrimitive
                require(!noValue.isString)
                val card = catalog.byNo[noValue.int]?.takeUnless { it.reserved }
                    ?: error("Unknown discovery card")
                val dateValue = r.getValue("metDate").jsonPrimitive
                require(dateValue.isString)
                val date = LocalDate.parse(dateValue.content)
                require(date.toString() == dateValue.content)
                val stampValue = r.getValue("seasonStamp")
                val stamp = if (stampValue == JsonNull) null else stampValue.jsonPrimitive.let {
                    require(it.isString); it.content
                }
                require(stamp == null || (stamp == "겨울" && card.seasonVariant?.months?.contains(date.monthValue) == true))
                DogamDiscovery(card.no, date.toString(), stamp)
            }
            require(restored.map { it.no }.distinct().size == restored.size)
            restored.forEach { found[it.no] = it }
        } catch (_: Exception) { found.clear(); restoredInvalid = true }
    }

    companion object {
        fun areaFor(id: String): String = when (id) {
            "steering_heat" -> "steering"
            "seat_heat", "seat_vent" -> "front_left"
            "front_defog" -> "front"
            "drive_mode" -> "vehicle"
            "side_mirror_heat" -> "mirrors"
            "hvac_auto", "air_cleaner", "cabin_light" -> "cabin"
            else -> error("Unknown catalog feature: $id")
        }
        private fun contentFor(catalog: DogamCatalog): ContentBundle = ContentBundle.fromResources().copy(
            features = catalog.carCards.map { card -> FeatureContent(card.id!!, card.name, card.active1st,
                areaFor(card.id), card.riddle, card.back!!.how) }, basicTarget = 8)
    }
}
