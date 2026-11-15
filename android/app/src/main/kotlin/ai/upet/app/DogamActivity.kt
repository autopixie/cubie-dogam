package ai.upet.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import ai.upet.core.*
import ai.upet.vehicle.DogamVehicleBridge
import ai.upet.vehicle.PleosVehicleAdapter

/** The dogam route owns only its compact local discoveries. Legacy progress remains untouched. */
class DogamActivity : Activity() {
    private lateinit var catalog: DogamCatalog
    private lateinit var dogam: DogamEngine
    private lateinit var store: StateStore
    private lateinit var baseVehicle: PleosVehicleAdapter
    private var extra: DogamVehicleBridge? = null
    private val handler = Handler(Looper.getMainLooper())
    private val design by lazy { DesignUi(this) }
    private val painter by lazy { DogamUi(this, design) }
    private val sleep = DrivingSleepPolicy()
    private val firstDraw = FirstDrawStartup()
    private var drawObserver: ViewTreeObserver? = null
    private var drawListener: ViewTreeObserver.OnDrawListener? = null
    private var startJob: Runnable? = null
    private var homeJob: Runnable? = null
    private var homeTicket: DrivingSleepPolicy.Ticket? = null
    private var retainedSleep: DrivingSleepPolicy.Saved? = null
    private var discoveryJob: Runnable? = null
    private var currentDiscovery: DogamDiscovery? = null
    private val discoveries = java.util.ArrayDeque<DogamDiscovery>()
    private val baseCapabilities = mutableMapOf<String, Boolean?>()
    private var selected: Int? = null
    private var riddle: Int? = null
    private var hintVisible = false
    private var backFirst = false
    private var wrong = false
    private var tab = DogamUi.Tab.ALL
    private var resumed = false
    private var ready = false
    private var resumedBefore = false
    private var generation = 0L
    private var malformedStore = false
    private var storageNotice = ""
    private var lastSaved = ""
    private val baseIds = setOf("steering_heat", "hvac_auto", "air_cleaner")
    private var readersActive = false
    private var readersConnected = false
    private val discoveryLifetime = DogamDiscoveryPolicy()
    private var discoveryTicket: DogamDiscoveryPolicy.Ticket? = null
    private var discoveryDrawView: View? = null
    private var discoveryDrawListener: ViewTreeObserver.OnDrawListener? = null
    private data class RenderKey(val route: String, val model: DogamUi.Model? = null, val parts: List<Any?> = emptyList())
    private var renderedKey: RenderKey? = null
    private var lastAvailabilityTrace: String? = null

    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            catalog = DogamCatalog.fromResources()
            store = StateStore(applicationContext, "upet_dogam_state")
            dogam = DogamEngine(catalog, store.read())
            malformedStore = dogam.restoredInvalid
            if (malformedStore) storageNotice = "도감 기록의 형식을 읽지 못했어요. 기존 기록을 보존했어요."
            lastSaved = dogam.persist()
            baseVehicle = PleosVehicleAdapter(applicationContext, dogam.engine.content, { dogam.state },
                { "steering_heat" }, ProductPolicy.dogamVehicle)
            selected = savedInstanceState?.getInt("dogam_selected", -1)?.takeIf { it in 1..100 }
            tab = DogamUi.Tab.entries.firstOrNull { it.name == savedInstanceState?.getString("dogam_tab") } ?: DogamUi.Tab.ALL
            sleep.restore(lastNonConfigurationInstance as? DrivingSleepPolicy.Saved, SystemClock.uptimeMillis())
            ready = true
        } catch (error: Exception) {
            Log.e("Dogam", "startup type=" + error.javaClass.simpleName)
            setContentView(design.untruncatedLabel("도감 자료를 읽지 못했어요.", 32f))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (ready) { cancelDiscoveries(); goBinder(); render() }
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        store.reloadForResume()?.takeIf { it.success }?.let {
            dogam = DogamEngine(catalog, it.json)
            malformedStore = dogam.restoredInvalid
            storageNotice = if (malformedStore) "도감 기록의 형식을 읽지 못했어요. 기존 기록을 보존했어요." else store.notice
            lastSaved = dogam.persist()
        }
        if (resumedBefore) goBinder()
        resumedBefore = true; resumed = true
        render(); activateReaders()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        diagnostic { Log.i("PetApp", "dogamLifecycle focus=$hasFocus resumed=$resumed uptimeMs=${SystemClock.uptimeMillis()}") }
        if (!ready || !resumed) return
        if (hasFocus) activateReaders()
        else { retainedSleep = sleep.saved() ?: retainedSleep; stopReaders(); render() }
    }

    private fun activateReaders() {
        if (!ready || !resumed || !hasWindowFocus() || readersActive) return
        retainedSleep = null; readersActive = true; readersConnected = false; generation++
        baseCapabilities.clear()
        dogam.dispatch(AppEvent.Visibility(true))
        dogam.dispatch(AppEvent.GearChanged(Gear.UNKNOWN))
        render(); startAfterDraw()
    }

    private fun stopReaders() {
        generation++; cancelStartup(); cancelDiscoveries(); cancelHome(); sleep.pause()
        baseVehicle.release(); extra?.close(); extra = null
        if (readersActive) dogam.dispatch(AppEvent.Visibility(false))
        readersActive = false; readersConnected = false
        persist()
    }

    private fun startAfterDraw() {
        cancelStartup()
        val token = firstDraw.begin()
        val run = generation
        val decor = window.decorView
        val observer = decor.viewTreeObserver
        drawObserver = observer
        val job = Runnable {
            removeDrawListener(); startJob = null
            if (!resumed || !readersActive || !hasWindowFocus() || run != generation || isFinishing || isDestroyed || !firstDraw.claim(token)) return@Runnable
            extra = DogamVehicleBridge(applicationContext, { dogam.state },
                { event -> deliver(run) { receiveExtra(event) } },
                { id, support, _ -> deliver(run) { dogam.setCapability(id, support); render() } },
                { raw, seq, epoch, snapshot -> deliver(run) {
                    handle(dogam.observeMode(raw, seq, epoch, snapshot)); render()
                } }, ProductPolicy.dogamExtraVehicle, { name, detail ->
                    diagnostic { Log.i("DogamVehicle", "$name $detail") }
                })
            readersConnected = true
            baseVehicle.initialize { event -> deliver(run) { receiveBase(event) } }
            baseVehicle.refresh(dogam.state.epoch)
            extra?.start()
        }
        startJob = job
        val listener = ViewTreeObserver.OnDrawListener {
            if (firstDraw.drawn(token)) decor.post(job)
        }
        drawListener = listener
        observer.addOnDrawListener(listener)
        decor.invalidate()
    }

    private fun deliver(run: Long, action: () -> Unit) {
        val job = { if (resumed && readersActive && generation == run && !isFinishing && !isDestroyed) action() }
        if (Looper.myLooper() == Looper.getMainLooper()) job() else handler.post { job() }
    }

    private fun receiveBase(event: AppEvent) {
        syncBaseCapabilities()
        if (event is AppEvent.Capability) { render(); return }
        if (event is AppEvent.Signal && event.featureId !in baseIds) return
        if (event is AppEvent.Odometer || event is AppEvent.ParkingSnapshot || event is AppEvent.FuelDemo) return
        receive(event)
    }

    private fun receiveExtra(event: AppEvent) {
        if (event is AppEvent.Capability) return
        receive(event)
    }

    private fun syncBaseCapabilities() {
        baseVehicle.dogamCapabilities.forEach { (id, value) ->
            if (!baseCapabilities.containsKey(id) || baseCapabilities[id] != value) {
                baseCapabilities[id] = value
                dogam.setCapability(id, value)
            }
        }
    }

    private fun receive(event: AppEvent) {
        val before = dogam.state
        val accepted = if (event is AppEvent.Platform)
            AppEvent.Platform(event.allowed && storageReady() && extra?.drivingRestricted != true) else event
        if (accepted is AppEvent.Platform && before.platformAllowed == accepted.allowed) return
        if (accepted is AppEvent.GearChanged && before.gear == accepted.value) return
        handle(dogam.dispatch(accepted))
        if (accepted is AppEvent.GearChanged) diagnostic {
            Log.i("PetApp", "dogamGear value=${accepted.value} epoch=${dogam.state.epoch} uptimeMs=${SystemClock.uptimeMillis()}")
        }
        if (event is AppEvent.TpmsChanged && event.value in setOf(Tpms.On, Tpms.Blink)) {
            cancelDiscoveries(); goBinder()
        }
        if (extra?.drivingRestricted == true && dogam.state.platformAllowed) dogam.dispatch(AppEvent.Platform(false))
        if ((accepted is AppEvent.Platform && accepted.allowed) ||
            (accepted is AppEvent.GearChanged && accepted.value == Gear.P)) {
            baseVehicle.refresh(dogam.state.epoch); extra?.refresh()
        }
        if (!safe()) cancelDiscoveries()
        render()
    }

    private fun storageReady() = store.available && !malformedStore
    private fun safe() = resumed && readersActive && hasWindowFocus() && storageReady() && dogam.state.foreground &&
        dogam.state.gear == Gear.P && dogam.state.platformAllowed && extra?.drivingRestricted != true

    private fun handle(result: DogamDispatch) {
        result.discovery?.let { discovery ->
            persist()
            if (safe()) { discoveries.addLast(discovery); nextDiscovery() }
        }
    }

    private fun persist() {
        if (!ready || malformedStore) return
        val value = dogam.persist()
        if (value == lastSaved) return
        if (store.commit(value)) lastSaved = value
        else {
            storageNotice = store.notice
            dogam.dispatch(AppEvent.Platform(false))
            cancelDiscoveries()
        }
    }

    private fun nextDiscovery() {
        if (currentDiscovery != null || discoveries.isEmpty() || !safe()) return
        currentDiscovery = discoveries.removeFirst()
        val current = currentDiscovery!!
        discoveryTicket = discoveryLifetime.begin("${current.no}:${current.metDate}:${current.seasonStamp}")
    }

    private fun armDiscoveryAfterDraw(root: View) {
        val ticket = discoveryTicket ?: return
        val run = generation
        val listener = ViewTreeObserver.OnDrawListener {
            val due = discoveryLifetime.drawn(ticket, SystemClock.uptimeMillis())
            if (due != null) root.post {
                if (run != generation || ticket != discoveryTicket || !safe()) return@post
                removeDiscoveryDraw()
                discoveryJob = Runnable {
                    if (run != generation || ticket != discoveryTicket ||
                        !discoveryLifetime.claim(ticket, safe(), SystemClock.uptimeMillis())) return@Runnable
                    painter.cancelAnimations(); currentDiscovery = null; discoveryTicket = null; discoveryJob = null
                    goBinder(); nextDiscovery(); render()
                }.also { handler.postDelayed(it, (due-SystemClock.uptimeMillis()).coerceAtLeast(0L)) }
            }
        }
        discoveryDrawView = root; discoveryDrawListener = listener
        root.viewTreeObserver.addOnDrawListener(listener)
    }

    private fun removeDiscoveryDraw() {
        val view = discoveryDrawView; val listener = discoveryDrawListener
        if (view != null && listener != null && view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener)
        discoveryDrawView = null; discoveryDrawListener = null
    }

    private fun cancelDiscoveries() {
        discoveryJob?.let { handler.removeCallbacks(it) }; discoveryJob = null
        currentDiscovery = null; discoveryTicket = null; discoveries.clear(); discoveryLifetime.cancel(); removeDiscoveryDraw(); painter.cancelAnimations()
    }

    private fun goBinder() { selected = null; riddle = null; hintVisible = false; wrong = false; backFirst = false }
    private fun act(action: () -> Unit) {
        diagnostic { Log.i("PetApp", "dogamAction safe=${safe()} sleep=${sleep.active} discovery=${currentDiscovery != null} focus=${hasWindowFocus()} uptimeMs=${SystemClock.uptimeMillis()}") }
        if (!safe() || sleep.active || currentDiscovery != null) return
        action(); render()
    }

    private fun traceAvailability() {
        diagnostic {
            val current = dogam.state
            val parts = listOf(resumed, readersActive, readersConnected, hasWindowFocus(),
                storageReady(), current.foreground, current.gear, current.platformAllowed,
                baseVehicle.interactionAllowed, baseVehicle.drivingRestricted,
                extra?.drivingRestricted, sleep.active, generation, current.epoch)
            val signature = parts.toString()
            if (lastAvailabilityTrace != signature) {
                lastAvailabilityTrace = signature
                val detail = org.json.JSONObject().put("resumed", resumed)
                    .put("readersActive", readersActive).put("readersConnected", readersConnected)
                    .put("focus", hasWindowFocus()).put("storageReady", storageReady())
                    .put("foreground", current.foreground).put("gear", current.gear.name)
                    .put("platformAllowed", current.platformAllowed)
                    .put("adapterAllowed", baseVehicle.interactionAllowed)
                    .put("adapterDrivingRestricted", baseVehicle.drivingRestricted)
                    .put("extraDrivingRestricted", extra?.drivingRestricted)
                    .put("sleep", sleep.active).put("generation", generation).put("epoch", current.epoch)
                    .put("timestampMs", System.currentTimeMillis()).put("uptimeMs", SystemClock.uptimeMillis())
                Log.i("PetApp", "dogamAvailability " + detail)
            }
        }
    }

    private fun model(): DogamUi.Model {
        val view = dogam.view()
        val slots = view.slots.map { card ->
            val found = view.discoveries[card.no]
            val source = card.back?.source
            DogamUi.Slot(card.no, card.id.orEmpty(), card.kind, card.name, catalog.types[card.type].orEmpty(),
                if (found?.seasonStamp != null) card.seasonVariant?.rarity ?: card.rarity else card.rarity,
                found != null, card.reserved, card.riddle, card.hint2, found?.metDate, found?.seasonStamp,
                card.back?.where.orEmpty(), card.back?.how.orEmpty(), card.back?.goodDay.orEmpty(), card.back?.note.orEmpty(),
                source?.let { it.model + " 「" + it.section + "」" }.orEmpty(), source?.url.orEmpty(),
                card.quiz?.q.orEmpty(), card.quiz?.choices.orEmpty(),
                feature = card.feature, sourceSection = source?.section.orEmpty())
        }
        val warning = when (dogam.state.tpms) {
            Tpms.On, Tpms.Blink -> {
                val id = if (dogam.state.tpms == Tpms.Blink) "tpms_blink" else "tpms_on"
                dogam.engine.content.rules.getValue(id).let { it.meaning.orEmpty() + " " + it.action.orEmpty() }
            }
            else -> null
        }
        return DogamUi.Model(slots, view.foundCount, view.totalCount, view.growthStage, catalog.title, warning,
            interactive = safe(), hintCards = catalog.cards.filterNot { it.reserved }.map { card ->
                DogamPresentationPolicy.Card(card.no, feature = card.feature, friendName = card.name,
                    kind = card.kind, found = card.no in view.discoveries)
            })
    }

    private fun render() {
        if (!ready) return
        updateSleep()
        traceAvailability()
        if (sleep.active) {
            val key = RenderKey("sleep", parts = listOf(dogam.view().growthStage))
            if (renderedKey != key) { renderedKey = key; renderSleep() }
            return
        }
        val value = model()
        if (!safe()) {
            val key = RenderKey("unavailable", parts = listOf(value.growthStage, value.warning, storageNotice, store.notice, rawParkSafe()))
            if (renderedKey != key) { renderedKey = key; renderUnavailable(value) }
            return
        }
        if (currentDiscovery != null && value.slots.none { it.no == currentDiscovery?.no && it.found }) cancelDiscoveries()
        val key = if (currentDiscovery != null) RenderKey("discovery", parts = listOf(currentDiscovery))
            else RenderKey("scene", value, listOf(selected, tab, riddle, hintVisible, backFirst, wrong))
        if (renderedKey == key) return // Preserve scroll position and the same discovery's native one-shot animation.
        renderedKey = key
        val actions = DogamUi.Actions(
            onCard = { no -> act {
                val slot = value.slots.firstOrNull { it.no == no } ?: return@act
                if (DogamPresentationPolicy.destination(slot.presentation()) == DogamPresentationPolicy.Destination.BINDER) return@act
                hintVisible = false; wrong = false; backFirst = false
                selected = no; riddle = null
            } },
            onHint = { no -> act {
                if (DogamPresentationPolicy.today(value.slots.map(DogamUi.Slot::presentation)) == no) {
                    riddle = no; hintVisible = true
                }
            } },
            onTab = { next -> act { tab = next; goBinder() } },
            onHome = { act { goBinder() } },
            onFlip = { act { backFirst = !backFirst } },
            onAnswer = { no, choice -> act {
                val result = dogam.answerQuiz(no, choice)
                wrong = result.status == DogamQuizStatus.WRONG
                result.discovery?.let { handle(DogamDispatch(true, it)) }
            } })
        val discovered = currentDiscovery
        val slot = selected?.let { no -> value.slots.firstOrNull { it.no == no } }
        val root = when {
            discovered != null -> {
                val card = value.slots.firstOrNull { it.no == discovered.no }
                if (card == null) painter.binder(value, actions, tab, riddle, hintVisible)
                else painter.discovery(value, card, DogamKorean.encounter(card.name),
                    if (card.kind == "quiz") "설명서 퀴즈를 맞혔어요" else discoveryEvidence(card.id),
                    reducedMotion = !android.animation.ValueAnimator.areAnimatorsEnabled())
            }
            slot != null -> when (DogamPresentationPolicy.destination(slot.presentation())) {
                DogamPresentationPolicy.Destination.DETAIL -> painter.detail(value, slot, actions, backFirst)
                DogamPresentationPolicy.Destination.QUIZ -> painter.quiz(value, slot, actions, wrong)
                DogamPresentationPolicy.Destination.GUIDE -> painter.guide(value, slot, actions)
                DogamPresentationPolicy.Destination.BINDER -> painter.binder(value, actions, tab, riddle, hintVisible)
            }
            else -> painter.binder(value, actions, tab, riddle, hintVisible)
        }
        setContentView(root)
        if (discovered != null) armDiscoveryAfterDraw(root)
    }

    private fun updateSleep() {
        val restricted = dogam.state.gear in setOf(Gear.D, Gear.R, Gear.N) ||
            (readersConnected && (baseVehicle.drivingRestricted || extra?.drivingRestricted == true))
        val was = sleep.active
        val ticket = sleep.observe(restricted, dogam.state.gear == Gear.P && baseVehicle.interactionAllowed &&
            extra?.drivingRestricted != true, resumed && dogam.state.foreground, SystemClock.uptimeMillis())
        if (!was && sleep.active) { cancelDiscoveries(); goBinder() }
        if (ticket == null) { cancelHome(); return }
        if (ticket == homeTicket) return
        cancelHome(); homeTicket = ticket
        homeJob = Runnable {
            if (!resumed || isFinishing || isDestroyed || !sleep.claim(ticket, dogam.state.foreground, SystemClock.uptimeMillis())) return@Runnable
            diagnostic { Log.i("PetApp", "dogamHome claimed deadlineMs=${ticket.deadlineMs} uptimeMs=${SystemClock.uptimeMillis()}") }
            goBinder()
            try { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: android.content.ActivityNotFoundException) { backToHome() }
            catch (_: SecurityException) { backToHome() }
            finally {
                // Close only this app's activity stack so the vehicle host cannot re-front an older Dogam.
                if (!isFinishing && !isDestroyed) finishAffinity()
            }
        }.also { handler.postDelayed(it, (ticket.deadlineMs-SystemClock.uptimeMillis()).coerceAtLeast(0)) }
    }

    private fun backToHome() { try { moveTaskToBack(true) } catch (_: RuntimeException) { /* No retry or security change. */ } }
    private fun cancelHome() { homeJob?.let { handler.removeCallbacks(it) }; homeJob = null; homeTicket = null }

    private fun renderSleep() {
        val column = design.column().apply { gravity = Gravity.CENTER; setPadding(32, 32, 32, 32) }
        column.addView(design.untruncatedLabel("큐비가 잠드는 시간이에요.", 40f, 700))
        column.addView(design.untruncatedLabel("주차하면 다시 만나요.", 30f))
        column.addView(PetView(this, PetFace.REST, dogam.view().growthStage), LinearLayout.LayoutParams(design.dp(400), design.dp(400)))
        setContentView(column)
    }

    private fun renderUnavailable(value: DogamUi.Model) {
        val column = design.column().apply { gravity = Gravity.CENTER; setPadding(40, 40, 40, 40) }
        column.addView(PetView(this, if (value.warning != null) PetFace.WORRIED else PetFace.REST, value.growthStage),
            LinearLayout.LayoutParams(design.dp(300), design.dp(300)))
        column.addView(design.untruncatedLabel(storageNotice.ifBlank {
            store.notice.ifBlank { "주차 상태와 차의 연결을 확인하고 있어요." }
        }, 30f))
        value.warning?.let { column.addView(design.untruncatedLabel(it, 30f, tone = R.color.upet_caution)) }
        if (rawParkSafe() && (!storageReady() || storageNotice.isNotBlank())) {
            column.addView(design.quietButton("저장 다시 시도", true) { retryStorage() })
        }
        setContentView(column)
    }

    private fun rawParkSafe() = resumed && readersConnected && hasWindowFocus() && dogam.state.gear == Gear.P &&
        baseVehicle.interactionAllowed && !baseVehicle.drivingRestricted && extra?.drivingRestricted != true && !sleep.active

    private fun retryStorage() {
        if (!rawParkSafe()) return
        val success = if (store.readFailed || malformedStore || store.needsReload) {
            val result = store.retryRead()
            if (!result.success) false else {
                val candidate = DogamEngine(catalog, result.json)
                malformedStore = candidate.restoredInvalid
                if (malformedStore) false
                else if (result.requiresCommit && !store.retryCommit(candidate.persist())) false
                else { dogam = candidate; lastSaved = candidate.persist(); true }
            }
        } else store.retryCommit(dogam.persist()).also { if (it) lastSaved = dogam.persist() }
        storageNotice = if (malformedStore) "도감 기록의 형식을 읽지 못했어요. 기존 기록을 보존했어요." else store.notice
        if (success) { storageNotice = ""; stopReaders(); activateReaders() }
        renderedKey = null; render()
    }

    private fun discoveryEvidence(id: String): String = "차가 확인했어요 · " + when (id) {
        "steering_heat" -> "핸들 열선 켜짐"
        "hvac_auto" -> "자동 공조 켜짐"
        "air_cleaner" -> "공기 청정 켜짐"
        "seat_heat" -> "좌석 열선 켜짐"
        "seat_vent" -> "좌석 통풍 켜짐"
        "front_defog" -> "앞유리 습기 제거 켜짐"
        "cabin_light" -> "실내등 켜짐"
        "drive_mode" -> "드라이브 모드 변경"
        "side_mirror_heat" -> "사이드 미러 열선 켜짐"
        else -> "상태 변화"
    }

    private fun removeDrawListener() {
        drawListener?.let { listener ->
            val observer = drawObserver?.takeIf { it.isAlive } ?: window.decorView.viewTreeObserver
            if (observer.isAlive) observer.removeOnDrawListener(listener)
        }
        drawListener = null; drawObserver = null
    }
    private fun cancelStartup() { firstDraw.cancel(); startJob?.let { handler.removeCallbacks(it) }; startJob = null; removeDrawListener() }

    override fun onPause() {
        diagnostic { Log.i("PetApp", "dogamLifecycle pause uptimeMs=${SystemClock.uptimeMillis()}") }
        if (ready) {
            retainedSleep = sleep.saved() ?: retainedSleep
            resumed = false; stopReaders()
            if (!isChangingConfigurations) retainedSleep = null
        }
        super.onPause()
    }
    @Deprecated("RAM-only rotation deadline")
    override fun onRetainNonConfigurationInstance(): Any? = retainedSleep ?: sleep.saved()
    override fun onSaveInstanceState(outState: Bundle) {
        if (ready) { persist(); outState.putInt("dogam_selected", selected ?: -1); outState.putString("dogam_tab", tab.name) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { if (ready) { cancelStartup(); cancelHome(); cancelDiscoveries(); baseVehicle.release(); extra?.close() }; handler.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = if (ready && (sleep.active || currentDiscovery != null)) true else super.dispatchTouchEvent(event)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = if (ready && sleep.active) true else super.dispatchKeyEvent(event)
    @Deprecated("Framework back dispatch")
    override fun onBackPressed() { if (ready && (sleep.active || currentDiscovery != null)) return; if (ready && safe() && (selected != null || riddle != null)) { goBinder(); render() } else super.onBackPressed() }
}
