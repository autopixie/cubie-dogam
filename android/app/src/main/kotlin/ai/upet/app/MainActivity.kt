package ai.upet.app

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.upet.core.PetMotionPolicy
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import ai.upet.core.AppEvent
import ai.upet.core.ContentBundle
import ai.upet.core.EngineState
import ai.upet.core.EngineView
import ai.upet.core.Gear
import ai.upet.core.MemoryEntry
import ai.upet.core.PetRuleEngine
import ai.upet.core.Tpms
import ai.upet.vehicle.FakeVehicleAdapter
import ai.upet.vehicle.PleosVehicleAdapter
import ai.upet.vehicle.VehicleSignalAdapter
import ai.upet.vehicle.userMessage
import java.util.Locale

/** Framework Views only. Vehicle values are read-only; fake controls exist in debug. */
class MainActivity : Activity() {
    private enum class Screen { HOME, FEATURES, CARDS, RECORDS, MEMORY, ALERT, LICENSE }
    private lateinit var content: ContentBundle
    private lateinit var copy: ContentCopy
    private lateinit var engine: PetRuleEngine
    private lateinit var store: StateStore
    private lateinit var featureCards: ai.upet.core.FeatureCards
    private lateinit var suggestionStore: SuggestionStore
    private var suggestionOpportunity = true
    private var suggestionPicture: PetView? = null
    private lateinit var questions: ai.upet.core.LocalQuestionAnswer
    private val conversation = ai.upet.core.ConversationState()
    private var questionDraft = ""
    private var questionPicture: PetView? = null
    private lateinit var adapter: VehicleSignalAdapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private var screen = Screen.HOME
    private var placeTab = false
    private var selectedId = ""
    private var feedback: String? = null
    private var feedbackKind: String? = null
    private var recoveryNotice = ""
    private var resumed = false
    private var ready = false
    private var listenerGeneration = 0L
    private var batchDepth = 0
    private var demoMode = false
    private var demoPlatformAllowed = true
    private var dialog: AlertDialog? = null
    private var appScroll: ScrollView? = null
    private var consoleScroll: ScrollView? = null
    private var rememberedAppY = 0
    private var rememberedConsoleY = 0
    private var resetAppScroll = false
    private val ui by lazy { DesignUi(this) }
    private var debugOpen = false
    private var licenseExpanded = false
    private var dismissedWarning: Tpms? = null
    private var windowWidth = 0
    private var windowHeight = 0
    private var lastInputAt = SystemClock.uptimeMillis()
    private val motionPolicy = PetMotionPolicy()
    private var floatingPet: FloatingPet? = null
    private var dialogFloating: FloatingPet? = null
    private var lastMotionReport = ""
    private val firstDrawStartup = FirstDrawStartup()
    private var startupObserver: android.view.ViewTreeObserver? = null
    private var startupDraw: android.view.ViewTreeObserver.OnDrawListener? = null
    private var startupJob: Runnable? = null
    private val heroOccurrences = PetSpeechOccurrences()
    private val dialogOccurrences = PetSpeechOccurrences()
    private var heroSpeech: PetSpeechView? = null
    private var dialogSpeech: PetSpeechView? = null
    private var heroBuilt = false
    private var speechSerial = 0L
    private val drivingSleep = ai.upet.core.DrivingSleepPolicy()
    private var drivingSleepRetained: ai.upet.core.DrivingSleepPolicy.Saved? = null
    private var drivingSleepJob: Runnable? = null
    private var drivingSleepTicket: ai.upet.core.DrivingSleepPolicy.Ticket? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        val light = newBase.getSharedPreferences("upet_display", MODE_PRIVATE).getBoolean("light", false)
        val config = Configuration(newBase.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (light) Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES)
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ProductPolicy.dogamEnabled) {
            startActivity(android.content.Intent(this, DogamActivity::class.java).addFlags(
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
            return
        }
        try {
            content = ContentBundle.fromResources()
            copy = ContentCopy(content)
            featureCards = ai.upet.core.FeatureCards.fromResources()
            questions = ai.upet.core.LocalQuestionAnswer(featureCards)
            suggestionStore = SuggestionStore(applicationContext)
            suggestionOpportunity = savedInstanceState == null
            demoMode = BuildConfig.DEBUG && (savedInstanceState?.getBoolean("wo03_demo")
                ?: intent.getBooleanExtra("wo03_demo", false))
            store = StateStore(applicationContext, if (demoMode) "upet_state" else "upet_real_state")
            engine = PetRuleEngine(content, store.read(), policy = ProductPolicy.engine(demoMode, content))
            lastInputAt = savedInstanceState?.getLong("pet_last_input")?.takeIf { it in 0..SystemClock.uptimeMillis() } ?: lastInputAt
            // Boot snapshots may replace the engine notice; retain corruption evidence separately.
            recoveryNotice = savedInstanceState?.getString("recovery_notice").orEmpty()
                .ifBlank { engine.state.notice }
            selectedId = savedInstanceState?.getString("selected_feature")
                ?.takeIf { id -> id in ProductPolicy.basicFeatureIds } ?: ProductPolicy.basicFeatureIds.first()
            screen = savedInstanceState?.getString("screen")?.let { name ->
                Screen.entries.firstOrNull { it.name == name }
            } ?: Screen.HOME
            placeTab = savedInstanceState?.getBoolean("place_tab") ?: false
            demoPlatformAllowed = savedInstanceState?.getBoolean("demo_platform", true) ?: true
            rememberedAppY = savedInstanceState?.getInt("app_scroll") ?: 0
            rememberedConsoleY = savedInstanceState?.getInt("console_scroll") ?: 0
            adapter = if (demoMode) FakeVehicleAdapter(content) else PleosVehicleAdapter(applicationContext, content, { engine.state }, { selected(engine.state)?.id }, ProductPolicy.vehicle)
            if (demoMode) restoreFakeState(savedInstanceState?.getBundle("fake"))
            // RAM-only across rotation; a background/process restore cannot replay an old HOME job.
            drivingSleep.restore(lastNonConfigurationInstance as? ai.upet.core.DrivingSleepPolicy.Saved,
                SystemClock.uptimeMillis())
            ready = true
        } catch (_: Exception) {
            showStartupFailure()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        // A peer Activity may have changed consent, deletion epoch or progress while this one paused.
        // Pending adoption is not a disk commit; StateStore keeps its failure/storage gate locked.
        store.reloadForResume()?.takeIf { it.success }?.let { restored ->
            engine = PetRuleEngine(content, restored.json, policy = ProductPolicy.engine(demoMode, content))
            if (engine.state.notice.isNotBlank()) recoveryNotice = engine.state.notice
        }
        resumed = true
        floatingPet?.resumeMotion()
        batch {
            feedback = null; feedbackKind = null
            engine.dispatch(AppEvent.Visibility(true))
            engine.dispatch(AppEvent.Platform(demoMode && demoPlatformAllowed && store.available))
            if (!demoMode) engine.dispatch(AppEvent.GearChanged(Gear.UNKNOWN))
            if (demoMode) startAdapter()
            if (feedback == null) feedback = content.rules.getValue("welcome").lines.first()
        }
        if (!demoMode) afterFirstDraw()
    }

    private fun startAdapter() {
        connectAdapterListener()
        // Keep existing main-thread ownership, epoch and read-before-restoring-P order.
        adapter.refresh(engine.state.epoch)
        fake()?.let { it.setGear(it.currentGear) }
        adapter.refresh(engine.state.epoch)
    }

    private fun cancelStartup() {
        firstDrawStartup.cancel()
        startupJob?.let { mainHandler.removeCallbacks(it) }; startupJob = null
        val observer = startupObserver
        val draw = startupDraw
        if (draw != null) {
            val live = if (observer?.isAlive == true) observer else window.decorView.viewTreeObserver
            if (live.isAlive) live.removeOnDrawListener(draw)
        }
        startupObserver = null; startupDraw = null
    }

    private fun afterFirstDraw() {
        cancelStartup()
        val token = firstDrawStartup.begin()
        val decor = window.decorView
        val observer = decor.viewTreeObserver
        startupObserver = observer
        val job = Runnable {
            val draw = startupDraw
            if (draw != null) {
                val live = if (observer.isAlive) observer else decor.viewTreeObserver
                if (live.isAlive) live.removeOnDrawListener(draw)
            }
            startupObserver = null; startupDraw = null; startupJob = null
            if (resumed && !isFinishing && !isDestroyed && firstDrawStartup.claim(token)) {
                diagnostic { Log.i("PetStartup", "tMs=" + System.currentTimeMillis() + " phase=sdk_start_after_draw") }
                batch { startAdapter() }
            }
        }
        startupJob = job
        val draw = android.view.ViewTreeObserver.OnDrawListener {
            if (firstDrawStartup.drawn(token)) {
                diagnostic { Log.i("PetStartup", "tMs=" + System.currentTimeMillis() + " phase=first_draw_callback") }
                // Runs after this draw traversal. App state stays on main; SDK work has its own queue.
                mainHandler.post(job)
            }
        }
        startupDraw = draw
        observer.addOnDrawListener(draw)
        decor.invalidate()
    }

    override fun onPause() {
        if (ready) {
            cancelStartup()
            drivingSleepRetained = drivingSleep.saved()
            cancelDrivingSleepJob()
            drivingSleep.pause()
            suggestionOpportunity = !isChangingConfigurations
            resumed = false
            floatingPet?.pauseMotion()
            dialogFloating?.pauseMotion()
            heroSpeech?.syncSafety(); dialogSpeech?.syncSafety()
            motionPolicy.sample(SystemClock.uptimeMillis(), 0, 0, false, false, true, lastInputAt)
            listenerGeneration++
            dialog?.dismiss()
            dialog = null
            adapter.release()
            batch {
                engine.dispatch(AppEvent.Visibility(false))
                feedback = null
                feedbackKind = null
            }
        }
        super.onPause()
    }

    @Deprecated("Framework Activity retention is used only for this RAM-only deadline.")
    override fun onRetainNonConfigurationInstance(): Any? = drivingSleepRetained ?: drivingSleep.saved()

    override fun onSaveInstanceState(outState: Bundle) {
        if (ready) {
            saveState()
            outState.putBoolean("wo03_demo", demoMode)
            outState.putLong("pet_last_input", lastInputAt)
            outState.putString("selected_feature", selectedId)
            outState.putString("screen", screen.name)
            outState.putBoolean("place_tab", placeTab)
            outState.putBoolean("demo_platform", demoPlatformAllowed)
            outState.putString("recovery_notice", recoveryNotice)
            outState.putInt("app_scroll", appScroll?.scrollY ?: rememberedAppY)
            outState.putInt("console_scroll", consoleScroll?.scrollY ?: rememberedConsoleY)
            fake()?.let { source ->
                outState.putBundle("fake", Bundle().apply {
                    putString("gear", source.currentGear.name)
                    putDouble("odometer", source.odometerKm)
                    if (content.fuelDemoEnabled) putDouble("fuel", source.fuelPercent)
                    putString("tpms", source.tpms?.name ?: "UNKNOWN")
                    source.featureValues.forEach { (id, value) ->
                        putInt("feature_$id", when (value) { true -> 1; false -> 0; null -> -1 })
                    }
                })
            }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        cancelDrivingSleepJob()
        drivingSleep.pause()
        cancelStartup()
        if (::adapter.isInitialized) adapter.release()
        dialog?.dismiss()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun restoreFakeState(saved: Bundle?) {
        if (saved == null) return
        val source = fake() ?: return
        // No listener is installed yet: restoring mock values cannot create engine events.
        val distance = saved.getDouble("odometer", 1200.0)
        if (distance.isFinite() && distance in 1200.0..1_000_000.0) source.addDistance(distance - 1200.0)
        val fuel = saved.getDouble("fuel", 35.0)
        if (content.fuelDemoEnabled && fuel.isFinite() && fuel in 0.0..100.0) source.setFuelPercent(fuel)
        source.setTpms(Tpms.entries.firstOrNull { it.name == saved.getString("tpms") })
        content.features.forEach { feature ->
            val value = when (saved.getInt("feature_${feature.id}", 0)) { 1 -> true; 0 -> false; else -> null }
            source.setFeature(feature.id, value, engine.state.epoch)
        }
        source.setGear(Gear.entries.firstOrNull { it.name == saved.getString("gear") } ?: Gear.UNKNOWN)
    }

    private fun real(): PleosVehicleAdapter? = adapter as? PleosVehicleAdapter

    private fun fake(): FakeVehicleAdapter? = if (demoMode) adapter as? FakeVehicleAdapter else null

    private fun connectAdapterListener() {
        val generation = ++listenerGeneration
        adapter.initialize { event ->
            val deliver = {
                if (resumed && generation == listenerGeneration) {
                    if (batchDepth > 0) receive(event) else batch { receive(event) }
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) deliver() else mainHandler.post { deliver() }
        }
    }

    private fun receive(event: AppEvent) {
        if (event is AppEvent.FuelDemo && !content.fuelDemoEnabled) return
        val before = engine.state
        val accepted = if (event is AppEvent.Platform) AppEvent.Platform(event.allowed && store.available) else event
        if (accepted is AppEvent.Platform && accepted.allowed == before.platformAllowed) return
        if (accepted is AppEvent.GearChanged && accepted.value == before.gear) return
        if (accepted is AppEvent.Platform || accepted is AppEvent.GearChanged) real()?.cancelPreparation()
        engine.dispatch(accepted)
        val after = engine.state
        if (ai.upet.core.PetEngagement.vehicleResult(event, before, after) ||
            (before.session != null && after.session == null)) {
            lastInputAt = SystemClock.uptimeMillis()
            if (ai.upet.core.PetEngagement.vehicleResult(event, before, after)) speechSerial++
        }
        heroSpeech?.syncSafety(); dialogSpeech?.syncSafety()
        floatingPet?.recheck(); dialogFloating?.recheck()
        if (!demoMode && ((accepted is AppEvent.Platform && accepted.allowed) ||
                (accepted is AppEvent.GearChanged && accepted.value == Gear.P))) adapter.refresh(after.epoch)
        if (event is AppEvent.TpmsChanged && event.value != before.tpms) {
            dismissedWarning = null
            if (event.value == Tpms.Off && screen == Screen.ALERT) screen = Screen.HOME
        }
        when {
            (accepted is AppEvent.GearChanged && after.gear != Gear.P) ||
                (accepted is AppEvent.Platform && !after.platformAllowed) -> {
                feedback = null; feedbackKind = null
                dialog?.dismiss(); dialog = null
                resetAppScroll = true
            }
            (event is AppEvent.GearChanged || event is AppEvent.ParkingSnapshot) && after.lastMemoryId != before.lastMemoryId && after.lastEvent == "parking" -> {
                captureFeedback(); screen = Screen.HOME; resetAppScroll = true
            }
            event is AppEvent.Signal && !event.snapshot && before.session != null && after.session == null &&
                after.lastEvent in setOf("success", "repeat") -> {
                captureFeedback(); screen = Screen.HOME; resetAppScroll = true
            }
            content.fuelDemoEnabled && demoMode && event is AppEvent.FuelDemo && !event.snapshot && before.fuel != null &&
                event.value > before.fuel!! && after.lastEvent == "fuel_increase" -> {
                captureFeedback(); screen = Screen.HOME; resetAppScroll = true
            }
            event is AppEvent.TpmsChanged && event.value != null && event.value != Tpms.Off -> {
                screen = Screen.HOME; resetAppScroll = true
            }
        }
    }

    private fun captureFeedback() {
        val state = engine.state
        feedbackKind = state.lastEvent
        feedback = state.notice.ifBlank { content.rules[state.lastEvent]?.lines?.first().orEmpty() }
    }

    private fun dispatch(event: AppEvent) {
        engine.dispatch(event)
        if (event is AppEvent.MemorySetting || event is AppEvent.DeleteRecords) real()?.refreshCompanion()
        captureFeedback()
    }

    private fun batch(action: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        batchDepth++
        try { action() } finally {
            batchDepth--
            if (batchDepth == 0) { saveState(); logState(); render() }
        }
    }

    private fun saveState() {
        if (!store.commit(engine.persist()) && engine.state.platformAllowed) {
            // Never call an uncommitted reward/deletion durable, or grant further rewards.
            engine.dispatch(AppEvent.Platform(false))
            feedback = null
            feedbackKind = null
        }
    }

    private fun appAction(action: () -> Unit) {
        if (!resumed || !engine.view().allowed || !store.available) return
        batch { real()?.cancelPreparation(); feedback = null; feedbackKind = null; action() }
    }

    private fun demoAction(action: (FakeVehicleAdapter) -> Unit) {
        if (!demoMode || !resumed) return
        val source = fake() ?: return
        batch { feedback = null; feedbackKind = null; action(source) }
    }

    private fun canRecoverStorage(): Boolean = resumed && engine.state.foreground &&
        engine.state.gear == Gear.P && (if (demoMode) demoPlatformAllowed else real()?.interactionAllowed == true)

    private fun recoverStorage() {
        if (!canRecoverStorage() || store.available) return
        var requiresCommit = false
        if (store.needsReload) {
            val restored = store.retryRead()
            if (!restored.success) { render(); return }
            requiresCommit = restored.requiresCommit
            engine = PetRuleEngine(content, restored.json, policy = ProductPolicy.engine(demoMode, content))
            if (engine.state.notice.isNotBlank()) recoveryNotice = engine.state.notice
        } else if (!store.retryCommit(engine.persist())) {
            render()
            return
        }
        batch {
            adapter.release()
            connectAdapterListener()
            engine.dispatch(AppEvent.Visibility(true))
            engine.dispatch(AppEvent.Platform(demoMode && demoPlatformAllowed && store.available))
            if (!demoMode) engine.dispatch(AppEvent.GearChanged(Gear.UNKNOWN))
            adapter.refresh(engine.state.epoch)
            fake()?.let { it.setGear(it.currentGear) }
            adapter.refresh(engine.state.epoch)
            feedbackKind = null
            feedback = if (requiresCommit) "저장하지 못한 변경을 가져왔어요. 다시 저장해 주세요."
                else "저장 상태를 다시 확인했어요. 진행 중이던 실습은 취소했어요."
            resetAppScroll = true
        }
    }

    private fun navigate(target: Screen) = appAction {
        if (engine.state.session != null) dispatch(AppEvent.Cancel)
        screen = target
        resetAppScroll = true
    }


    private fun explicitDrivingRestriction(): Boolean = engine.state.gear in setOf(Gear.D, Gear.R, Gear.N) ||
        (if (demoMode) !demoPlatformAllowed else real()?.drivingRestricted == true)

    private fun cancelDrivingSleepJob() {
        drivingSleepJob?.let { mainHandler.removeCallbacks(it) }
        drivingSleepJob = null; drivingSleepTicket = null
    }

    private fun updateDrivingSleep() {
        val wasActive = drivingSleep.active
        val ticket = drivingSleep.observe(explicitDrivingRestriction(),
            engine.state.gear == Gear.P && (if (demoMode) demoPlatformAllowed else real()?.interactionAllowed == true),
            resumed && engine.state.foreground, SystemClock.uptimeMillis())
        if (ticket == null) { cancelDrivingSleepJob(); return }
        if (!wasActive && drivingSleep.active) {
            real()?.cancelPreparation()
            if (engine.state.session != null) engine.dispatch(AppEvent.Cancel)
            conversation.close(); questionDraft = ""
            dialog?.dismiss(); dialog = null
            questionPicture = null; dialogSpeech = null; dialogFloating = null
            floatingPet?.pauseMotion()
            heroSpeech?.cancelReaction("driving_sleep")
            heroOccurrences.show(null); dialogOccurrences.show(null)
            debugOpen = false; screen = Screen.HOME; placeTab = false
            feedback = null; feedbackKind = null; resetAppScroll = true
        }
        if (drivingSleepTicket == ticket) return
        cancelDrivingSleepJob()
        drivingSleepTicket = ticket
        val job = Runnable {
            drivingSleepJob = null
            if (!resumed || isFinishing || isDestroyed ||
                !drivingSleep.claim(ticket, engine.state.foreground, SystemClock.uptimeMillis())) return@Runnable
            screen = Screen.HOME
            diagnostic { Log.i("PetSleep", "tMs=" + System.currentTimeMillis() + " phase=home_once") }
            try {
                startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (error: android.content.ActivityNotFoundException) {
                backAfterHomeFailure(error)
            } catch (error: SecurityException) {
                backAfterHomeFailure(error)
            }
        }
        drivingSleepJob = job
        mainHandler.postDelayed(job, (ticket.deadlineMs - SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    private fun backAfterHomeFailure(error: RuntimeException) {
        diagnostic { Log.w("PetSleep", "home unavailable type=" + error.javaClass.simpleName) }
        // A vehicle OS may prevent HOME. Keep the noninteractive farewell rather than crash or retry.
        try { moveTaskToBack(true) }
        catch (blocked: RuntimeException) {
            diagnostic { Log.w("PetSleep", "background unavailable type=" + blocked.javaClass.simpleName) }
        }
    }

    private fun buildDrivingSleep(parent: LinearLayout, state: EngineState) {
        val panel = ui.screenPanel().apply {
            setPadding(dp(40), dp(44), dp(40), dp(36))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        // This is a static, noninteractive safety farewell, not the interactive speech/reaction layer.
        val bubble = ui.column().apply {
            setPadding(dp(32), dp(24), dp(32), dp(24))
            background = ui.shape(ui.color(R.color.upet_surface_2), 28)
            addView(ui.untruncatedLabel("큐비가 잠드는 시간이에요.", 40f, 700))
            addView(ui.untruncatedLabel("주차하면 다시 만나요.", 30f, tone = R.color.upet_text_2), ui.block(10))
        }
        panel.addView(bubble, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val picture = PetView(this, PetFace.REST, ProductPolicy.evolution(state.data.awarded))
        panel.addView(petPictureBox(picture, if (wide()) 400 else 260), ui.block(24))
        parent.addView(panel, ui.fill())
    }

    private fun render() {
        updateDrivingSleep()
        heroSpeech?.preserveForReplacement()
        heroBuilt = false
        val state = engine.state
        val view = engine.view()
        if (!view.allowed && (conversation.open || suggestionPicture != null)) dialog?.dismiss()
        suggestionPicture?.showFace(presentFace(view.allowed,state.gear,state.tpms,null,talking=true))
        updateQuestionFace()
        val oldY = if (resetAppScroll) 0 else appScroll?.scrollY ?: rememberedAppY
        resetAppScroll = false
        val root = ui.column().apply { setBackgroundColor(ui.color(R.color.upet_bg)) }
        installInsets(root)
        val board = ui.column().apply { setPadding(dp(44), dp(36), dp(44), dp(32)) }
        board.addView(header(view, state), ui.fill(dp(64)))
        val page = ui.column()
        if (drivingSleep.active) buildDrivingSleep(page, state)
        else if (BuildConfig.DEBUG && debugOpen) {
            VariantUi.panel(ui, previewHost())?.let { page.addView(it, ui.fill()) }
        } else if (!view.allowed) buildRest(page, state)
        else {
            if (recoveryNotice.isNotBlank()) notice(page, recoveryNotice)
            if (store.notice.isNotBlank()) notice(page, store.notice)
            // Feature read status is represented once, in its neutral feature chip.
            fake()?.readFailure(selectedId)?.let { notice(page, "시연용 · " + it.userMessage()) }
            val warning = view.warning != null && dismissedWarning != state.tpms && screen != Screen.LICENSE
            when {
                warning || screen == Screen.ALERT -> buildAlert(page, view, state)
                screen == Screen.LICENSE -> buildLicense(page)
                screen == Screen.FEATURES -> buildFeatures(page, state)
                screen == Screen.CARDS -> buildCards(page, state)
                screen == Screen.RECORDS -> buildRecords(page, state)
                screen == Screen.MEMORY -> buildMemory(page, state)
                feedbackKind == "parking" && state.data.memories.any { it.id == state.lastMemoryId } -> buildMemory(page, state)
                feedbackKind in setOf("success", "repeat", "already_on") -> buildResult(page, state)
                else -> buildHome(page, view, state)
            }
        }
        val scroller = ui.scroll(page)
        appScroll = scroller
        if (demoMode) board.addView(ui.label(if (content.fuelDemoEnabled) "시연용 신호 · 시연용 연료 · 실제 차량과 연결되지 않아요" else "시연용 신호 · 실제 차량과 연결되지 않아요",
            DesignUi.SMALL, tone = R.color.upet_text_3), ui.fill())
        board.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(28) })
        if (view.allowed && !debugOpen && !drivingSleep.active) navigation(board)
        root.addView(board, LinearLayout.LayoutParams(-1, -1))
        root.addOnLayoutChangeListener { target, _, _, _, _, _, _, _, _ ->
            val density = resources.displayMetrics.density
            val width = ((target.width - target.paddingLeft - target.paddingRight) / density).toInt()
            val height = ((target.height - target.paddingTop - target.paddingBottom) / density).toInt()
            if (width > 0 && height > 0 && (windowWidth != width || windowHeight != height)) {
                windowWidth = width; windowHeight = height
                target.post { if (resumed && !isFinishing) render() }
            }
        }
        if (!heroBuilt) { heroOccurrences.show(null); heroSpeech = null }
        setContentView(root)
        root.requestApplyInsets()
        scroller.post { scroller.scrollTo(0, oldY) }
        mainHandler.post { if (resumed) maybeSuggest() }
    }

    private fun header(view: EngineView, state: EngineState): LinearLayout = ui.row().apply {
        val identity = ui.row()
        val title = ui.label(getString(R.string.app_name), DesignUi.TITLE, 700)
        identity.addView(title)
        identity.addView(ui.label(if (view.stage.mode == "companion") "함께 추억 쌓기" else "새 차 적응기", 26f, tone = R.color.upet_text_2),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(24) })
        addView(identity, LinearLayout.LayoutParams(0, dp(64), 1f))
        val gear = if (state.gear == Gear.UNKNOWN) "상태 미확인"
            else "${state.gear.name} · ${if (state.gear == Gear.P) "주차 중" else if (state.gear == Gear.D) "주행 중" else "쉬는 중"}"
        val chip = ui.row().apply {
            setPadding(dp(28), 0, dp(28), 0)
            background = ui.shape(ui.color(R.color.upet_surface_2), 24)
            addView(View(this@MainActivity).apply {
                background = ui.shape(if (state.gear == Gear.P) ui.color(R.color.upet_parked) else ui.color(R.color.upet_text_3), 7)
            }, LinearLayout.LayoutParams(dp(14), dp(14)))
            addView(ui.label(gear, 26f, 600), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
        }
        addView(chip, LinearLayout.LayoutParams(-2, dp(64)))
        if (BuildConfig.DEBUG && view.allowed && !drivingSleep.active) VariantUi.bindTitle(title) { debugOpen = true; render() }
    }

    private fun wide(): Boolean = PetPresentation.horizontal(
        windowWidth.takeIf { it > 0 } ?: resources.configuration.screenWidthDp,
        windowHeight.takeIf { it > 0 } ?: resources.configuration.screenHeightDp)

    private fun split(parent: LinearLayout, left: View, right: View) {
        if (wide()) {
            val minHeight = ((windowHeight.takeIf { it > 0 } ?: resources.configuration.screenHeightDp) - 272).coerceAtLeast(0)
            left.minimumHeight = dp(minHeight)
            right.minimumHeight = dp(minHeight)
            val line = ui.row().apply { gravity = Gravity.TOP }
            val available = (windowWidth.takeIf { it > 0 } ?: resources.configuration.screenWidthDp) - 88
            val leftWidth = PetPresentation.heroWidth(available)
            line.addView(left, LinearLayout.LayoutParams(dp(leftWidth), -2))
            val remaining = android.widget.FrameLayout(this)
            val rightWidth = (available - leftWidth - 32).coerceAtLeast(1)
            remaining.addView(right, android.widget.FrameLayout.LayoutParams(dp(minOf(1100, rightWidth)), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            line.addView(remaining, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(32) })
            parent.addView(line, ui.fill())
        } else {
            parent.addView(left, ui.fill()); parent.addView(right, ui.block(24))
        }
    }

    private fun presentFace(allowed: Boolean, gear: Gear, tpms: Tpms?, outcome: String?,
        waiting: Boolean = false, talking: Boolean = false): PetFace =
        PetPresentation.face(allowed, gear, tpms, outcome, waiting, talking, content.fuelDemoEnabled)

    private fun reactionLog(line: String) = diagnostic { Log.i("PetReaction", "tMs=" + System.currentTimeMillis() + " " + line) }

    private fun showSpeech(view: PetSpeechView, speech: PetSpeech, key: String, occurrences: PetSpeechOccurrences) {
        if (!ProductPolicy.conversationSpeechEnabled) { view.visibility = View.GONE; return }
        val kind = PetSpeechPolicy.reaction(speech, true, resumed, engine.view().allowed, false)
        occurrences.show(key.takeIf { speech.visible }, kind)
        view.show(speech, occurrences.ticket()) { now ->
            if (occurrences.markShown(key, now)) {
                lastInputAt = now
                diagnostic { Log.i("PetSpeech", "tMs=" + System.currentTimeMillis() + " face=" + speech.face + " source=" + speech.source) }
                floatingPet?.recheck(); dialogFloating?.recheck()
            }
        }
    }

    private fun hero(face: PetFace, title: String, detail: String, size: Int,
        situation: SpeechSituation = when (face) {
            PetFace.JOY -> SpeechSituation.SUCCESS
            PetFace.WORRIED -> SpeechSituation.WARNING
            PetFace.REST -> SpeechSituation.REST
            PetFace.WAITING -> SpeechSituation.PRACTICE
            PetFace.TALKING -> SpeechSituation.OFFER
            else -> SpeechSituation.HOME
        }, source: String = "MainActivity.hero"): LinearLayout = ui.screenPanel().apply {
        setPadding(dp(40), dp(44), dp(40), dp(36))
        heroBuilt = true
        val state = engine.state
        val speech = PetSpeechPolicy.speech(situation, engine.view().allowed, state.gear, state.tpms,
            listOf(title, detail), source)
        val picture = PetView(this@MainActivity, if (speech.visible) speech.face else face, ProductPolicy.evolution(state.data.awarded))
        val pictureBox = petPictureBox(picture, size)
        val speechView = if (speech.visible) PetSpeechView(this@MainActivity, ui, pictureBox, picture,
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL },
            allowed = { qaSafe() }, report = ::reactionLog) else null
        val payload = speechView ?: pictureBox
        val floating = FloatingPet(this@MainActivity, payload, ui, size, wide(), engine.view().allowed,
            grouped = speech.visible, inputTarget = picture,
            sample = { now, x, y, reduced -> motionPolicy.sample(now, x, y, resumed && engine.state.foreground,
                engine.view().allowed, reduced, lastInputAt, engine.state.session != null || real()?.preparingPractice == true,
                if (speech.visible) heroOccurrences.readingUntilMs() else 0L,
                speech.visible && heroOccurrences.ticket().blocksTravel()) },
            input = { if (resumed && engine.view().allowed) { lastInputAt = SystemClock.uptimeMillis(); motionPolicy.focus() } },
            restChanged = { resting ->
                picture.showFace(if (!resumed || !engine.view().allowed || (resting && engine.state.tpms !in setOf(Tpms.On, Tpms.Blink))) PetFace.REST else if (speech.visible) speech.face else face)
                if (resting) speechView?.cancelReaction("idle_or_restricted")
            },
            report = { line -> diagnostic {
                val key = line.substringAfter("moving=")
                if (key != lastMotionReport) { lastMotionReport = key; Log.i("PetMotion", line) }
            } })
        floatingPet = floating
        if (speechView != null) {
            heroSpeech = speechView
            speechView.motionChanged = { floating.recheck() }
            val key = screen.name + ":" + state.session?.id + ":" + feedbackKind + ":" + speechSerial + ":" + speech.lines.joinToString("\n")
            showSpeech(speechView, speech, key, heroOccurrences)
        } else { heroSpeech = null; heroOccurrences.show(null) }
        addView(floating, ui.fill())
        if (!speech.visible) {
            addView(ui.label(title, DesignUi.TITLE, 700).apply { gravity = Gravity.CENTER }, ui.block())
            addView(ui.label(detail, DesignUi.BODY, tone = R.color.upet_text_2).apply { gravity = Gravity.CENTER }, ui.block(16))
        }
    }

    private fun petPictureBox(picture: PetView, size: Int) = object : android.widget.FrameLayout(this) {
        init { clipChildren = false; addView(picture, LayoutParams(-1, -1)) }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = resolveSize(dp(size), widthMeasureSpec)
            val h = resolveSize(dp(size + 24), heightMeasureSpec)
            picture.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            setMeasuredDimension(w, h)
        }
    }

    private fun dialogSpeech(picture: PetView, size: Int = 180): Pair<PetSpeechView, View> {
        val localMotion = PetMotionPolicy()
        val speechView = PetSpeechView(this, ui, petPictureBox(picture, size), picture,
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL },
            allowed = { qaSafe() }, report = ::reactionLog)
        val floating = FloatingPet(this, speechView, ui, size, wide(), engine.view().allowed,
            grouped = true, inputTarget = picture,
            sample = { now, x, y, reduced -> localMotion.sample(now, x, y, resumed && engine.state.foreground,
                engine.view().allowed, reduced, lastInputAt, false, dialogOccurrences.readingUntilMs(), dialogOccurrences.ticket().blocksTravel()) },
            input = { lastInputAt = SystemClock.uptimeMillis(); localMotion.focus() },
            restChanged = { resting ->
                if (!qaSafe() || resting) picture.showFace(PetFace.REST)
                else if (suggestionPicture === picture) picture.showFace(presentFace(true,engine.state.gear,engine.state.tpms,null,talking=true))
                else updateQuestionFace()
            },
            report = { diagnostic { Log.i("PetMotion", "dialog " + it) } })
        speechView.motionChanged = { floating.recheck() }
        dialogSpeech = speechView; dialogFloating = floating
        return speechView to floating
    }

    private fun selected(state: EngineState) = content.features.firstOrNull { it.id in ProductPolicy.basicFeatureIds && it.id == selectedId && state.signals[it.id]?.supported == true }
        ?: content.features.firstOrNull { it.id in ProductPolicy.basicFeatureIds && state.signals[it.id]?.supported == true }

    private fun addGrowth(parent: LinearLayout, state: EngineState) {
        val count = ProductPolicy.basicFeatureIds.count { it in state.data.awarded }
        val line = ui.row()
        line.addView(ui.label("새싹 ${ProductPolicy.evolution(state.data.awarded)}단계", DesignUi.LABEL, tone = R.color.upet_text_2))
        val segments = ui.row()
        repeat(3) { index ->
            segments.addView(View(this).apply {
                background = ui.shape(ui.color(if (index < count) R.color.upet_accent else R.color.upet_surface_2), 5)
            }, LinearLayout.LayoutParams(0, dp(10), 1f).apply { if (index > 0) marginStart = dp(8) })
        }
        line.addView(segments, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(18); marginEnd = dp(18) })
        line.addView(ui.label("카드 $count / 3", DesignUi.LABEL, tone = R.color.upet_text_2))
        parent.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        parent.addView(line, ui.block(24))
    }

    private fun factRow(parent: LinearLayout, title: String, text: String, gap: Int = 18, size: Float = 30f, step: Boolean = false) {
        if (text.isBlank()) return
        val row = ui.row().apply { gravity = Gravity.TOP }
        val label = ui.label(title, DesignUi.LABEL, tone = R.color.upet_text_3)
        if (step) {
            label.gravity = Gravity.CENTER
            label.background = ui.shape(ui.color(R.color.upet_surface_2), 22)
        }
        row.addView(label, LinearLayout.LayoutParams(dp(if (step) 44 else if (wide()) 150 else 110), if (step) dp(44) else -2).apply {
            if (step) marginEnd = dp(20)
        })
        row.addView(if (size == 32f) ui.untruncatedLabel(text, 32f) else ui.untruncatedLabel(text, 30f), LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(row, ui.block(gap))
    }

    private fun applicableLocation(card: ai.upet.core.FeatureCard): Boolean =
        (card.model == null && !card.referenceOnly && !card.practiceId.isNullOrBlank() && card.locationSource?.approvedInstructionSource == true) ||
            (card.referenceOnly && !card.model.isNullOrBlank() && card.locationSource?.vehicleControlSource == true)

    private fun applicableOperation(card: ai.upet.core.FeatureCard): Boolean = applicableLocation(card) &&
        ((card.locationSource?.approvedInstructionSource == true && card.operationSource?.approvedInstructionSource == true) ||
            (card.locationSource?.vehicleControlSource == true && card.operationSource?.vehicleControlSource == true))

    private fun buildHome(parent: LinearLayout, view: EngineView, state: EngineState) {
        val feature = selected(state)
        val waiting = state.session != null || real()?.preparingPractice == true
        val face = presentFace(view.allowed, state.gear, state.tpms, feedbackKind, waiting = waiting)
        val left = hero(face, if (waiting) content.rules.getValue("waiting").lines[0] else copy.homeTitle(feature?.name),
            if (waiting) content.rules.getValue("waiting").lines[1] else feedback?.takeIf { feedbackKind !in setOf(null, "welcome") } ?: copy.homeDetail,
            if (wide()) 400 else 260, if (waiting) SpeechSituation.PRACTICE else SpeechSituation.HOME,
            if (waiting) "content.pet_rules.waiting" else "ContentCopy.homeTitle/homeDetail")
        addGrowth(left, state)
        val right = ui.column()
        val today = ui.screenPanel().apply {
            if (waiting && wide()) minimumHeight = dp((windowHeight - 272).coerceAtLeast(0))
        }
        val card = featureCards.cards.firstOrNull { it.practiceId == feature?.id }
        if (waiting && feature != null) {
            today.addView(ui.label("실습 중 · ${feature.name}", DesignUi.LABEL, 600, R.color.upet_text_2))
            today.addView(ui.untruncatedLabel("${feature.name}을 직접 켜 보세요", DesignUi.DISPLAY, 700), ui.block(10))
            if (card != null && applicableLocation(card) && applicableOperation(card) && card.location.isNotBlank() && card.operation.isNotBlank()) {
                listOf(card.location, card.operation, "켜지면 큐비가 차 신호로 바로 알아차려요.").forEachIndexed { index, text ->
                    factRow(today, "${index + 1}", text, if (index == 0) 28 else 18, 32f, step = true)
                }
            } else today.addView(ui.untruncatedLabel(feature.instruction), ui.block(28))
            val live = (if (wide()) ui.row() else ui.column()).apply {
                setPadding(dp(32), dp(28), dp(32), dp(28))
                background = ui.shape(ui.color(R.color.upet_bg), 24)
            }
            val current = ui.column()
            current.addView(ui.label("지금", DesignUi.LABEL, tone = R.color.upet_text_3))
            current.addView(ui.label(when (state.signals[feature.id]?.value) { false -> "꺼짐"; true -> "켜짐"; null -> "확인 중" }, DesignUi.TITLE, 700))
            live.addView(current)
            live.addView(ui.label("→", 40f, tone = R.color.upet_text_3), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(28); marginEnd = dp(28) })
            val expected = ui.column()
            expected.addView(ui.label("기다리는 중", DesignUi.LABEL, tone = R.color.upet_text_3))
            expected.addView(ui.label("켜짐", DesignUi.TITLE, 700, R.color.upet_text_3))
            live.addView(expected)
            live.addView(ui.untruncatedLabel("앱은 버튼을 대신 누르지 않아요.\n차 신호만 읽어요.", DesignUi.LABEL, tone = R.color.upet_text_2).apply { gravity = Gravity.END },
                if (wide()) LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(28) } else ui.block(16))
            today.addView(live, ui.block(32))
            today.addView(ui.quietButton("실습 취소") { appAction { dispatch(AppEvent.Cancel) } }, ui.block(34))
        } else {
            today.addView(ui.label("오늘 익힐 기능", DesignUi.LABEL, 600, R.color.upet_text_2))
            if (feature == null) today.addView(ui.untruncatedLabel("지원되는 기능이 없어요."), ui.block())
            else {
                today.addView(ui.untruncatedLabel(feature.name, DesignUi.DISPLAY, 700), ui.block(10))
                val value = state.signals.getValue(feature.id).value
                val chip = ui.badge(when (value) { true -> "●  지금 켜짐"; false -> "●  지금 꺼짐"; null -> "●  확인 중" }).apply {
                    setTextColor(ui.color(if (value == null) R.color.upet_text_2 else R.color.upet_text_1))
                    if (value != null) {
                        val marked = android.text.SpannableString(text)
                        marked.setSpan(android.text.style.ForegroundColorSpan(ui.color(R.color.upet_accent)), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        text = marked
                    }
                }
                today.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
                if (feature.id == "steering_heat" && !demoMode) today.addView(ui.untruncatedLabel(when (real()?.heaterEvidenceOrigin) {
                    ai.upet.vehicle.HeaterEvidenceOrigin.BUNDLED_20261101 -> "기본값 · 11월 1일 실측 Off 0 / Low 1"
                    ai.upet.vehicle.HeaterEvidenceOrigin.STORED_OBSERVATION -> "이 기기에 저장한 관측값 적용"
                    else -> "열선값 확인 필요 · 실습 잠금"
                }, DesignUi.SMALL, tone = R.color.upet_text_3), ui.block(10))
                today.addView(ui.untruncatedLabel(feature.description, tone = R.color.upet_text_2), ui.block(20))
                if (card != null && applicableLocation(card)) factRow(today, "어디에", card.location, 26)
                if (!state.data.introduced) {
                    today.addView(ui.button("함께 시작하기", true) { appAction { dispatch(AppEvent.Intro) } }, ui.block(30))
                } else today.addView(ui.button("실습 시작", true, value != null && (real()?.canPrepare(feature.id) ?: true)) {
                    appAction {
                        selectedId = feature.id
                        val source = real()
                        if (source == null) dispatch(AppEvent.Start(feature.id, feature.area))
                        else source.preparePractice(feature.id) { appAction { dispatch(AppEvent.Start(feature.id, feature.area)) } }
                    }
                }, ui.block(30))
                val quiet = ui.row()
                quiet.addView(ui.quietButton("다른 기능 보기") { navigate(Screen.FEATURES) }, ui.weighted())
                quiet.addView(ui.quietButton("이번엔 건너뛰기") { appAction { dispatch(AppEvent.Skip) } }, ui.weighted(16))
                today.addView(quiet, ui.block(22))
            }
        }
        right.addView(today, ui.fill())
        if (!waiting && ProductPolicy.questionAnswerEntryEnabled) {
            val ask = ui.screenPanel().apply { setPadding(dp(40), dp(28), dp(40), dp(28)) }
            val line = ui.row()
            line.addView(ui.badge("?").apply { setTextColor(ui.color(R.color.upet_accent)) }, LinearLayout.LayoutParams(dp(64), dp(64)))
            val words = ui.column()
            words.addView(ui.label("큐비에게 물어보기", 30f, 700))
            words.addView(ui.untruncatedLabel("기능 이름으로 물어보세요 · 예: 공기 청정은 어디서 켜?", DesignUi.LABEL, tone = R.color.upet_text_2), ui.block(6))
            line.addView(words, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(24) })
            line.addView(ui.label("›", 36f, tone = R.color.upet_text_3))
            ask.addView(line)
            ui.bindAction(ask, ui.color(R.color.upet_surface_1), 28, action = ::openQuestions)
            ask.contentDescription = "큐비에게 물어보기. 기능 이름으로 질문하기"
            right.addView(ask, ui.block(24))
        }
        split(parent, left, right)
        if (view.warning != null) parent.addView(ui.button("차 상태 알림") { appAction { screen = Screen.ALERT } }, ui.block())
    }

    private fun buildResult(parent: LinearLayout, state: EngineState) {
        val feature = content.features.firstOrNull { it.id == selectedId } ?: content.features.first()
        val first = feedbackKind == "success"
        val words = copy.result(feedbackKind, feature.name)
        val left = hero(presentFace(true, state.gear, state.tpms, feedbackKind), words[0], words[1], if (wide()) 400 else 260,
            when (feedbackKind) { "success" -> SpeechSituation.SUCCESS; "repeat" -> SpeechSituation.REPEAT; else -> SpeechSituation.ALREADY_ON }, "ContentCopy.result")
        addGrowth(left, state)
        val right = ui.screenPanel()
        right.addView(ui.label(if (first) "새 기능 카드" else "기능 카드", DesignUi.LABEL, 600, R.color.upet_text_2))
        val card = ui.column().apply {
            setPadding(dp(38), dp(34), dp(38), dp(34))
            background = ui.shape(ui.color(R.color.upet_surface_2), 24)
        }
        card.addView(ui.label(when (feedbackKind) { "success" -> "첫 실습 완료"; "repeat" -> "반복 실습 완료"; else -> "이미 켜져 있어요" }, DesignUi.LABEL, 700, R.color.upet_accent))
        card.addView(ui.label(feature.name, 52f, 700), ui.block(8))
        card.addView(ui.untruncatedLabel(if (feedbackKind == "already_on") "지금 상태를 함께 확인했어요. 새 카드는 늘어나지 않아요." else "꺼짐 → 켜짐을 차 신호로 확인했어요.", 28f, tone = R.color.upet_text_2), ui.block(12))
        val date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
        val count = ProductPolicy.basicFeatureIds.count { it in state.data.awarded }
        card.addView(View(this).apply { setBackgroundColor(ui.color(R.color.upet_line)) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(26) })
        val footer = ui.row()
        footer.addView(ui.label("${date.monthValue}월 ${date.dayOfMonth}일", DesignUi.LABEL, tone = R.color.upet_text_3), LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(ui.label("이 차의 카드 $count / 3", DesignUi.LABEL, tone = R.color.upet_text_3))
        card.addView(footer, ui.block(18))
        right.addView(card, ui.block(22))
        right.addView(ui.untruncatedLabel("같은 기능을 다시 켜도 카드는 한 장만 남아요. 새싹은 처음 익힌 기능으로만 자라요.", 28f, tone = R.color.upet_text_2), ui.block(20))
        right.addView(ui.button("다음 기능 보기", true) { appAction { feedbackKind = null; feedback = null; screen = Screen.FEATURES } }, ui.block(30))
        right.addView(ui.quietButton("홈으로") { appAction { feedbackKind = null; feedback = null; screen = Screen.HOME } }, ui.block(22))
        split(parent, left, right)
    }

    private fun memoryDistance(memory: MemoryEntry): String = when (memory.distanceStatus) {
        "valid" -> memory.distanceKm?.let { "${copy.distanceLabel} ${number(it)} km" } ?: copy.unknownDistance
        "invalid" -> copy.invalidDistance
        else -> copy.unknownDistance
    }

    private fun buildMemory(parent: LinearLayout, state: EngineState) {
        val memory = state.data.memories.lastOrNull { it.id == state.lastMemoryId } ?: state.data.memories.lastOrNull()
        if (memory == null) { notice(parent, "아직 남긴 추억이 없어요."); return }
        val left = hero(presentFace(true, state.gear, state.tpms, "parking"), "오늘도 수고했어요.", "이 차 안에만 저장돼요", 320)
        val right = ui.panel()
        right.addView(ui.label("${copy.memoryTitle} · ${memory.date}", DesignUi.TITLE, 700))
        val distance = memory.distanceKm
        if (memory.distanceStatus == "valid" && distance != null) {
            right.addView(ui.label(number(distance), DesignUi.DISPLAY, 700), ui.block(32))
            right.addView(ui.label(copy.distanceLabel.removeSuffix(" ·") + " (km)"), ui.block(10))
        } else right.addView(ui.label(memoryDistance(memory)), ui.block(32))
        right.addView(ui.label(when(memory.distanceStatus) { "valid" -> copy.distanceExplanation; "invalid" -> copy.invalidExplanation; else -> copy.unknownExplanation }, DesignUi.SMALL, tone = R.color.upet_text_2), ui.block(16))
        right.addView(ui.label("들른 곳 / 시연용 장소", DesignUi.LABEL, 600), ui.block(32))
        right.addView(ui.label("시연용 장소 카드는 전체 카드 모음에서 봐요.", DesignUi.SMALL, tone = R.color.upet_text_2), ui.block(16))
        right.addView(ui.label("익힌 기능", DesignUi.LABEL, 600), ui.block(24))
        right.addView(ui.label("기능 완료 날짜는 기록하지 않아요.", DesignUi.SMALL, tone = R.color.upet_text_2), ui.block(16))
        right.addView(ui.label("이 추억은 이미 이 차에 저장됐어요.", DesignUi.SMALL, tone = R.color.upet_text_2), ui.block())
        right.addView(ui.button("건너뛰기") { appAction { feedbackKind = null; screen = Screen.HOME } }, ui.block(32))
        right.addView(ui.button("일기에 남기기", true) { appAction { feedbackKind = null; screen = Screen.RECORDS } }, ui.block(16))
        split(parent, left, right)
    }

    private fun buildAlert(parent: LinearLayout, view: EngineView, state: EngineState) {
        val left = hero(presentFace(view.allowed, state.gear, state.tpms, null), copy.warningSpeech[0], copy.warningSpeech[1], 320, SpeechSituation.WARNING, "ContentCopy.warningSpeech")
        val right = ui.panel(3)
        val warning = view.warning
        val on = state.tpms == Tpms.On
        right.addView(ui.label(if (on) "타이어 공기압 표시가 켜졌어요" else warning?.meaning ?: "차 상태를 읽지 못했어요", DesignUi.TITLE, 700, R.color.upet_caution))
        right.addView(ui.label(if (on) "안전한 곳에 세운 뒤 계기판 안내를 확인해 주세요." else warning?.action ?: "현재 상태를 정상으로 판단하지 않아요.", tone = R.color.upet_text_2), ui.block())
        right.addView(ui.label("지금 할 일", DesignUi.LABEL, 600), ui.block(32))
        right.addView(ui.label("1  계기판의 타이어 안내 확인"), ui.block(16))
        right.addView(ui.label("2  필요하면 가까운 곳에서 점검"), ui.block(16))
        val acknowledge = { appAction { dismissedWarning = state.tpms; screen = Screen.HOME; feedbackKind = null } }
        right.addView(ui.button("나중에 다시", action = acknowledge), ui.block(32))
        right.addView(ui.button("확인했어요", true, action = acknowledge), ui.block(16))
        if (wide()) split(parent, left, right)
        else {
            parent.addView(right, ui.block(32))
            parent.addView(left, ui.block(32))
        }
    }

    private fun buildRest(parent: LinearLayout, state: EngineState) {
        val title = when {
            state.gear in setOf(Gear.D, Gear.R, Gear.N) -> "운전하는 동안 쉬고 있을게요"
            state.gear == Gear.UNKNOWN -> "차 상태를 기다리고 있어요"
            !store.available -> "저장 상태를 확인해 주세요"
            else -> "지금은 앱을 쉴게요"
        }
        val detail = if (state.gear in setOf(Gear.D, Gear.R, Gear.N)) "P로 멈추면 다시 함께해요." else "주차 상태와 사용 가능 여부를 확인해 주세요."
        parent.addView(hero(PetFace.REST, title, detail, 300), ui.block(32))
        real()?.let { if (it.health != ai.upet.vehicle.ReadHealth.READY) notice(parent, it.statusText) }
        // No clickable controls or pet animation in a moving state.
        if (state.gear == Gear.P && canRecoverStorage() && !store.available)
            parent.addView(ui.button(if (store.needsReload) "저장 내용 다시 읽기" else "저장 다시 시도", action = ::recoverStorage), ui.block())
    }

    private fun navigation(parent: LinearLayout) {
        val nav = ui.row()
        val items = listOf("홈" to Screen.HOME, "기능 선택" to Screen.FEATURES, "카드 모음" to Screen.CARDS, "기록·설정" to Screen.RECORDS)
        items.forEachIndexed { index, pair ->
            nav.addView(ui.navigationButton(pair.first, screen == pair.second) { navigate(pair.second) },
                LinearLayout.LayoutParams(0, dp(88), 1f).apply { if (index > 0) marginStart = dp(12) })
        }
        parent.addView(nav, LinearLayout.LayoutParams(-1, dp(88)).apply { topMargin = dp(24) })
    }

    private fun buildFeatures(parent: LinearLayout, state: EngineState) {
        parent.addView(ui.label("함께 익힐 코스", DesignUi.TITLE, 700), ui.block(32))
        ProductPolicy.courses.courses.sortedBy { it.displayOrder }.forEach { course ->
            val section = ui.panel(1,true)
            section.addView(ui.label(course.title,DesignUi.TITLE,700))
            if (course.id != "first_week" || !course.practiceEnabled || course.releaseScope != "first_submission" || course.unitIds.none { it in ProductPolicy.basicFeatureIds }) {
                section.addView(ui.label(course.lockedCopy,DesignUi.LABEL,tone=R.color.upet_text_2),ui.block())
            } else course.unitIds.mapNotNull { id -> content.features.firstOrNull { it.id==id } }.forEach { feature ->
                section.addView(ui.label(feature.name,DesignUi.LABEL,600),ui.block())
                section.addView(ui.label(feature.description,tone=R.color.upet_text_2),ui.block())
                val supported=state.signals[feature.id]?.supported==true
                section.addView(ui.button(if(feature.id in state.data.awarded) "카드 있음 · 선택" else if(supported) "이 기능 선택" else "지원 상태 확인 필요",true,supported) {
                    appAction {
                        if(feature.id !in ProductPolicy.basicFeatureIds) return@appAction
                        if(engine.state.session!=null) dispatch(AppEvent.Cancel)
                        selectedId=feature.id;feedbackKind=null;feedback=null;screen=Screen.HOME;resetAppScroll=true
                    }
                },ui.block())
            }
            parent.addView(section,ui.block(32))
        }
    }

    private fun buildCards(parent: LinearLayout, state: EngineState) {
        parent.addView(ui.label("이 차의 카드 모음", DesignUi.TITLE, 700), ui.block(32))
        parent.addView(ui.label("같은 기능·장소 카드는 한 장씩만 모아요.", DesignUi.LABEL, tone = R.color.upet_text_2), ui.block())
        parent.addView(ui.button(if (placeTab) "기능 카드 보기" else "장소 카드 보기") { appAction { placeTab = !placeTab } }, ui.block())
        if (placeTab) {
            parent.addView(ui.label("시연용 예시 · 실제 방문 기록이 아니에요.", DesignUi.SMALL, tone = R.color.upet_text_2), ui.block())
            copy.places.forEach { place ->
                val card = ui.panel(1, true)
                card.addView(ui.label("시연용 · ${place.name}", DesignUi.TITLE, 700))
                card.addView(ui.label(if (state.data.places.any { it.id == place.id }) place.description else "아직 모으지 않았어요.", tone = R.color.upet_text_2), ui.block())
                parent.addView(card, ui.block(32))
            }
        } else content.features.filter { (it.id in ProductPolicy.basicFeatureIds && state.signals[it.id]?.supported == true) || it.id in state.data.awarded }.forEach { feature ->
            val card = ui.panel(1, true)
            card.addView(ui.label(feature.name, DesignUi.TITLE, 700))
            card.addView(ui.label(if (feature.id in state.data.awarded) "첫 실습 기록" else "아직 모으지 않았어요.", DesignUi.LABEL, tone = R.color.upet_text_2), ui.block())
            parent.addView(card, ui.block(32))
        }
    }

    private fun buildRecords(parent: LinearLayout, state: EngineState) {
        parent.addView(ui.label("이 차의 기록", DesignUi.TITLE, 700), ui.block(32))
        parent.addView(ui.label("함께 쓰는 차의 공동 기록이에요.", tone = R.color.upet_text_2), ui.block())
        parent.addView(ui.button("주차 일기 저장 · ${if (state.data.settings.memoryEnabled) "켬" else "끔"}") {
            appAction { dispatch(AppEvent.MemorySetting(!engine.state.data.settings.memoryEnabled)) }
        }, ui.block(32))
        parent.addView(ui.label("켜고 나서 D → P로 돌아오면 일기 한 장을 남겨요.", DesignUi.SMALL, tone = R.color.upet_text_2), ui.block(16))
        parent.addView(ui.button("시연용 장소 저장 · ${if (state.data.settings.locationEnabled) "켬" else "끔"}", enabled = demoMode) {
            appAction { dispatch(AppEvent.LocationSetting(!engine.state.data.settings.locationEnabled)) }
        }, ui.block())
        parent.addView(ui.label("실제 위치는 읽지 않아요. 선택한 예시 카드만 남겨요.", DesignUi.SMALL, tone = R.color.upet_text_2), ui.block(16))
        parent.addView(ui.label("끄면 새 기록을 남기지 않아요. 기존 기록은 전체 삭제로 지워요.", DesignUi.SMALL, tone = R.color.upet_text_2), ui.block())
        parent.addView(ui.button("기록 전체 삭제", action = ::confirmDelete), ui.block())
        parent.addView(ui.button(if (ui.dark) "밝은 화면으로" else "어두운 화면으로") {
            appAction { if (getSharedPreferences("upet_display", MODE_PRIVATE).edit().putBoolean("light", ui.dark).commit()) recreate() }
        }, ui.block())
        parent.addView(ui.button("오픈소스 고지") { navigate(Screen.LICENSE) }, ui.block())
        parent.addView(ui.label("추억 일기 · ${state.data.memories.size}장", DesignUi.TITLE, 700), ui.block(32))
        if (state.data.memories.isEmpty()) notice(parent, "아직 남긴 일기가 없어요.")
        state.data.memories.asReversed().forEach { memory ->
            val card = ui.panel(1, true)
            card.addView(ui.label(memory.date, DesignUi.LABEL, 600))
            card.addView(ui.label(memoryDistance(memory)), ui.block())
            card.addView(ui.label(if (memory.origin == "demo") "시연용 기록 · 이 차 안에만 저장돼요" else "이 차 안에만 저장돼요", DesignUi.SMALL, tone = R.color.upet_text_2), ui.block())
            parent.addView(card, ui.block(32))
        }
    }

    private fun buildLicense(parent: LinearLayout) {
        parent.addView(ui.label("오픈소스 고지", DesignUi.TITLE, 700), ui.block(32))
        parent.addView(ui.label("Asta Sans (OFL-1.1)"), ui.block())
        val license = assets.open("licenses/AstaSans_OFL.txt").bufferedReader().use { it.readText() }
        parent.addView(ui.label(license.lineSequence().first(), DesignUi.SMALL, tone = R.color.upet_text_2), ui.block())
        parent.addView(ui.button(if (licenseExpanded) "원문 접기" else "원문 보기") { appAction { licenseExpanded = !licenseExpanded } }, ui.block())
        if (licenseExpanded) {
            val lines = ui.column()
            license.lineSequence().forEach { line ->
                lines.addView(ui.label(line, DesignUi.SMALL, tone = R.color.upet_text_2).apply {
                    setSingleLine(true); maxLines = 1; ellipsize = null
                }, ui.block(4))
            }
            parent.addView(android.widget.HorizontalScrollView(this).apply {
                addView(lines, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }, ui.block())
        }
        parent.addView(ui.button("홈으로") { navigate(Screen.HOME) }, ui.block(32))
    }

    private fun confirmDelete() {
        if (!resumed || !engine.view().allowed || !store.available) return
        dialog?.dismiss()
        val body = ui.panel(3)
        body.addView(ui.label("일기와 장소 기록을 모두 지울까요?", DesignUi.TITLE, 700))
        body.addView(ui.label("기능 카드는 남고 저장 선택은 유지돼요.", tone = R.color.upet_text_2), ui.block())
        body.addView(ui.button("취소") { dialog?.dismiss() }, ui.block(32))
        body.addView(ui.button("전체 삭제 확인", true) {
            appAction { dispatch(AppEvent.DeleteRecords); dialog?.dismiss(); dialog = null }
        }, ui.block(16))
        dialog = AlertDialog.Builder(this).setView(body).create().also { it.show() }
    }

    private fun maybeSuggest() {
        if (!suggestionOpportunity || !qaSafe() || dialog?.isShowing == true || debugOpen || screen != Screen.HOME ||
            engine.state.session != null || real()?.preparingPractice == true || engine.view().warning != null) return
        suggestionOpportunity = false
        val scope = if (demoMode) "demo" else ProductPolicy.verifiedVehicleScope ?: return
        val day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString()
        val state = engine.state
        val candidates = ProductPolicy.basicFeatureIds.filterNot { suggestionStore.excluded(scope,it,day) }.map { id ->
            // Fake proof is scoped to the explicit demo only. Actual capability/temperature integration awaits measurement.
            ai.upet.core.SuggestionCandidate(id, demoMode && fake() != null && state.signals[id]?.supported == true, id in state.data.awarded)
        }
        if(suggestionStore.notice.isNotBlank()) { recoveryNotice=suggestionStore.notice;render();return }
        val proposal = ai.upet.core.ContextSuggestion.choose(candidates, qaSafe(), state.session != null, ProductPolicy.k1, null) ?: return
        val card = featureCards.cards.firstOrNull { it.id == proposal.featureId } ?: return
        val offer = suggestionStore.reserve(scope,card.id,day) ?: run {
            if(suggestionStore.notice.isNotBlank()) { recoveryNotice=suggestionStore.notice;render() };return
        }
        // Reservation already consumes today's budget, even if the screen becomes unsafe before showing.
        if (!qaSafe() || engine.state.epoch != state.epoch || engine.state.session != null) return
        val body = ui.panel(3)
        val picture = PetView(this, PetFace.TALKING, ProductPolicy.evolution(state.data.awarded))
        suggestionPicture = picture
        val (speechView, speechContainer) = dialogSpeech(picture)
        body.addView(speechContainer,ui.fill())
        dialogOccurrences.show(null)
        showSpeech(speechView, PetSpeechPolicy.speech(SpeechSituation.OFFER,qaSafe(),state.gear,state.tpms,
            listOf(proposal.text),"ContextSuggestion.text"), "offer:" + offer.id, dialogOccurrences)
        if(demoMode) body.addView(ui.label("시연용 신호 · 실제 차량과 연결되지 않아요",DesignUi.SMALL),ui.block())
        body.addView(ui.label(card.name),ui.block())
        fun reply(kind: ai.upet.core.SuggestionReply, action: ()->Unit = {}) {
            if(!qaSafe()) { dialog?.dismiss(); return }
            if(suggestionStore.reply(offer.id,kind)) { dialog?.dismiss();action() }
            else {
                dialog?.dismiss()
                recoveryNotice=suggestionStore.notice.ifBlank { "제안 응답을 저장하지 못했어요. 같은 기능을 다시 권하지 않도록 보류했어요." }
                render()
            }
        }
        body.addView(ui.button("알아볼게요",true) { reply(ai.upet.core.SuggestionReply.OPEN) { openQuestionsFor(card) } },ui.block())
        body.addView(ui.button("이미 알아요") { reply(ai.upet.core.SuggestionReply.ALREADY_KNOW) },ui.block())
        body.addView(ui.button("필요 없어요") { reply(ai.upet.core.SuggestionReply.NOT_NEEDED) },ui.block())
        body.addView(ui.button("나중에") { reply(ai.upet.core.SuggestionReply.LATER) },ui.block())
        dialog=object: AlertDialog(this) {
            override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean { lastInputAt=SystemClock.uptimeMillis();dialogFloating?.recheck();return super.dispatchTouchEvent(event) }
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean { lastInputAt=SystemClock.uptimeMillis();dialogFloating?.recheck();return super.dispatchKeyEvent(event) }
        }.also { d -> d.setView(ui.scroll(body));d.setOnDismissListener {
                speechView.cancelReaction("dismissed")
                if (suggestionPicture === picture) suggestionPicture = null
                if (dialogSpeech === speechView) { dialogSpeech = null; dialogFloating = null; dialogOccurrences.show(null) }
            };d.show() }
        lastInputAt=SystemClock.uptimeMillis()
        mainHandler.post(object: Runnable {
            override fun run() {
                if(suggestionPicture!==picture) return
                if(!qaSafe() || SystemClock.uptimeMillis()-lastInputAt>=30_000L) dialog?.dismiss()
                else mainHandler.postDelayed(this,1000L)
            }
        })
    }

    private fun qaSafe(): Boolean = resumed && !isFinishing && !isDestroyed && engine.view().allowed && store.available

    private fun openQuestions() = openQuestionsFor(null)

    private fun openQuestionsFor(initial: ai.upet.core.FeatureCard?) = appAction {
        if (!ProductPolicy.questionAnswerEntryEnabled) return@appAction
        if (!qaSafe()) return@appAction
        if (engine.state.session != null) dispatch(AppEvent.Cancel)
        dialog?.dismiss()
        conversation.begin()
        questionDraft = ""
        val board = ui.column().apply {
            setBackgroundColor(ui.color(R.color.upet_bg))
            setPadding(dp(44), dp(36), dp(44), dp(32))
        }
        board.addView(header(engine.view(), engine.state), ui.fill(dp(64)))
        val body = ui.column()
        val left = ui.screenPanel().apply { setPadding(dp(40), dp(44), dp(40), dp(36)) }
        val picture = PetView(this, PetFace.WAITING, ProductPolicy.evolution(engine.state.data.awarded))
        questionPicture = picture
        val (speechView, speechContainer) = dialogSpeech(picture, if (wide()) 400 else 260)
        left.addView(speechContainer, ui.fill())
        addGrowth(left, engine.state)
        dialogOccurrences.show(null)
        showSpeech(speechView, PetSpeechPolicy.speech(SpeechSituation.PRACTICE, qaSafe(), engine.state.gear, engine.state.tpms,
            listOf("어떤 기능이 궁금해요?", "기능 이름을 적어 주세요."), "ContentCopy.questionHelp"), "question:help", dialogOccurrences)
        val right = ui.screenPanel()
        right.addView(ui.label("큐비에게 물어보기", DesignUi.LABEL, 600, R.color.upet_text_2))
        val answers = ui.column()
        right.addView(answers, ui.block(12))
        val input = ui.questionInput().apply {
            background = ui.shape(ui.color(R.color.upet_bg), 22); backgroundTintList = null
            setPadding(dp(28), dp(12), dp(28), dp(12))
        }
        fun show(result: ai.upet.core.QuestionResult) {
            answers.removeAllViews()
            val message = copy.questionMessage(result)
            val answer = result as? ai.upet.core.QuestionResult.Answer
            val id = answer?.card?.practiceId
            val state = engine.state
            val canPractice = id != null && id in ProductPolicy.basicFeatureIds && state.signals[id]?.value == false &&
                state.signals[id]?.supported == true && (real()?.canSelectPractice(id) ?: true)
            val proven = answer?.card?.takeIf { applicableLocation(it) && it.location.isNotBlank() }
            val summary = when {
                proven?.locationSource?.approvedManualSource == true && proven.id == "steering_heat" ->
                    listOf("공조 화면에서 켜요.", "취급설명서 기준이에요.")
                proven?.locationSource?.approvedScreenSource == true && proven.id == "steering_heat" ->
                    listOf("화면 맨 아래 왼쪽이에요.", if (applicableOperation(proven) && proven.operation.isNotBlank()) proven.operation else "핸들 모양 아이콘을 찾아보세요.")
                proven != null -> listOf(proven.name, "오른쪽에서 위치와 출처를 확인해 주세요.")
                else -> listOf(message.substringBefore(".") + if (message.contains(".")) "." else "")
            }
            showSpeech(speechView, PetSpeechPolicy.speech(SpeechSituation.ANSWER,qaSafe(),state.gear,state.tpms,
                summary,"LocalQuestionAnswer.result"), "question:" + summary.joinToString("\n"), dialogOccurrences)
            if (questionDraft.isNotBlank()) answers.addView(ui.untruncatedLabel("“$questionDraft”", 30f, tone = R.color.upet_text_2))
            if (result is ai.upet.core.QuestionResult.Clarify) result.candidates.distinctBy { it.id }.forEach { card ->
                answers.addView(ui.quietButton(card.name) {
                    if (qaSafe() && conversation.open) {
                        lastInputAt = SystemClock.uptimeMillis()
                        val clarified = questions.answer(card)
                        if (conversation.complete(conversation.token(), qaSafe(), clarified)) { show(clarified); updateQuestionFace() }
                    }
                }, ui.block())
            }
            if (answer != null) {
                val card = answer.card
                answers.addView(ui.untruncatedLabel(if (card.referenceOnly) "${card.model} 자료 · ${card.name}" else card.name, 48f, 700), ui.block(10))
                val manual = proven?.locationSource?.approvedManualSource == true
                // The official-manual answer is shown once, instead of repeating its location/method rows.
                if (manual) answers.addView(ui.untruncatedLabel(message, 32f), ui.block(24))
                else {
                    if (applicableLocation(card)) factRow(answers, "어디에", card.location, 24, 32f)
                    if (applicableOperation(card)) factRow(answers, "어떻게", card.operation, size = 32f)
                }
                factRow(answers, "언제 좋아요", card.goodWhen, size = 32f)
                if (!manual) {
                    val explanation = if (proven?.locationSource?.approvedScreenSource == true)
                        "실제 차의" + message.substringAfter("실제 차의") else message
                    answers.addView(ui.untruncatedLabel(explanation, DesignUi.SMALL, tone = R.color.upet_text_2), ui.block(22))
                }
                card.locationSource?.let { source ->
                    val qualifier = if (source.approvedScreenSource) message.substringBefore("으로 ", "") + " · " else ""
                    val sourceLine = if (source.approvedManualSource) "${source.model} · ${source.section}"
                        else "${qualifier}출처: ${source.section}"
                    answers.addView(ui.untruncatedLabel("$sourceLine · ${source.url}", DesignUi.SMALL, tone = R.color.upet_text_3), ui.block(14))
                }
                if (canPractice && id != null) {
                    answers.addView(ui.button(copy.questionPracticePrompt, true) {
                        appAction {
                            val feature = content.features.firstOrNull { it.id == id } ?: return@appAction
                            selectedId = id
                            if (id !in ProductPolicy.basicFeatureIds || engine.state.signals[id]?.value != false ||
                                engine.state.signals[id]?.supported != true || real()?.canPrepare(id) == false) return@appAction
                            selectedId = id; screen = Screen.HOME
                            dialog?.dismiss()
                            val source = real()
                            if (source == null) dispatch(AppEvent.Start(id, feature.area))
                            else source.preparePractice(id) { appAction { dispatch(AppEvent.Start(id, feature.area)) } }
                        }
                    }, ui.block(30))
                }
            } else answers.addView(ui.untruncatedLabel(message), ui.block(18))
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                questionDraft = s?.toString().orEmpty()
                conversation.typing(); answers.removeAllViews()
                dialogOccurrences.show(null)
                speechView.show(PetSpeech(PetFace.WAITING, emptyList(), "LocalQuestionAnswer.typing", false), dialogOccurrences.ticket())
                lastInputAt = SystemClock.uptimeMillis(); updateQuestionFace()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        val inputRow = ui.row()
        inputRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        inputRow.addView(ui.button("묻기") {
            if (qaSafe() && conversation.open) {
                lastInputAt = SystemClock.uptimeMillis()
                val raw = questionDraft
                val token = conversation.typing()
                val epoch = engine.state.epoch
                updateQuestionFace()
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
                input.clearFocus()
                mainHandler.post {
                    if (conversation.open && conversation.token() == token && qaSafe() && engine.state.epoch == epoch) {
                        val result = questions.ask(raw)
                        if (conversation.complete(token, qaSafe() && engine.state.epoch == epoch, result)) { show(result); updateQuestionFace() }
                    }
                }
            }
        }, LinearLayout.LayoutParams(dp(160), dp(88)).apply { marginStart = dp(16) })
        right.addView(inputRow, ui.block(24))
        right.addView(ui.label("질문은 저장하지 않아요.", DesignUi.SMALL, tone = R.color.upet_text_3), ui.block(12))
        initial?.let { card ->
            val answer = questions.answer(card)
            if (conversation.complete(conversation.token(),qaSafe(),answer)) show(answer)
        }
        split(body, left, right)
        val scroll = ui.scroll(body)
        board.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(28) })
        val nav = ui.row()
        listOf("홈" to Screen.HOME, "기능 선택" to Screen.FEATURES, "카드 모음" to Screen.CARDS, "기록·설정" to Screen.RECORDS).forEachIndexed { index, item ->
            nav.addView(ui.navigationButton(item.first, item.second == screen) { dialog?.dismiss(); navigate(item.second) },
                LinearLayout.LayoutParams(0, dp(88), 1f).apply { if (index > 0) marginStart = dp(12) })
        }
        board.addView(nav, LinearLayout.LayoutParams(-1, dp(88)).apply { topMargin = dp(24) })
        dialog = object : AlertDialog(this) {
            override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                lastInputAt = SystemClock.uptimeMillis(); dialogFloating?.recheck(); return super.dispatchTouchEvent(event)
            }
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                lastInputAt = SystemClock.uptimeMillis(); dialogFloating?.recheck(); return super.dispatchKeyEvent(event)
            }
        }.also { d ->
            d.setView(board, 0, 0, 0, 0)
            d.setOnDismissListener {
                speechView.cancelReaction("dismissed")
                if (dialogSpeech === speechView) {
                    input.text?.clear(); questionDraft = ""; conversation.close(); questionPicture = null
                    dialogSpeech = null; dialogFloating = null; dialogOccurrences.show(null)
                }
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
            }
            d.show()
            d.window?.apply {
                setBackgroundDrawableResource(R.color.upet_bg)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
        updateQuestionFace()
        mainHandler.post(object : Runnable {
            override fun run() {
                if (!conversation.open) return
                if (!qaSafe() || SystemClock.uptimeMillis() - lastInputAt >= 30_000L) dialog?.dismiss()
                else mainHandler.postDelayed(this, 1000L)
            }
        })
    }

    private fun updateQuestionFace() {
        if (!conversation.open) return
        questionPicture?.showFace(presentFace(qaSafe(), engine.state.gear, engine.state.tpms, null,
            waiting = conversation.answer == null || conversation.answer == ai.upet.core.QuestionResult.Empty,
            talking = conversation.answer != null && conversation.answer != ai.upet.core.QuestionResult.Empty))
    }

    private fun notice(parent: LinearLayout, value: String) {
        val card = ui.panel(1, true)
        card.addView(ui.label(value, DesignUi.SMALL, tone = R.color.upet_caution).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
        parent.addView(card, ui.block())
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (ready && drivingSleep.active) return true
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (ready && drivingSleep.active) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInputAt = SystemClock.uptimeMillis()
        floatingPet?.recheck(); dialogFloating?.recheck()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (ready && drivingSleep.active) return true
        // Debug hardware shortcut: no user-screen driving controls.
        if (BuildConfig.DEBUG && keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP && event.repeatCount > 0 && resumed) {
            debugOpen = true; render(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun previewHost() = object : PreviewHost {
        override val source get() = fake()
        override val realSource get() = real()
        override val state get() = engine.state
        override val available get() = engine.view().allowed && store.available
        override val fuelDemoEnabled get() = content.fuelDemoEnabled
        override val growth get() = engine.view().growth
        override val diagnosticAvailable get() = !demoMode && resumed && real()?.interactionAllowed == true
        override val features get() = content.features.filter { it.id in ProductPolicy.basicFeatureIds }
        override val selectedId get() = this@MainActivity.selectedId
        override val labels get() = copy.signals
        override val places get() = copy.places
        override val platformAllowed get() = demoPlatformAllowed
        override fun change(action: (FakeVehicleAdapter) -> Unit) = demoAction(action)
        override fun choose(id: String) = demoAction {
            if (id !in ProductPolicy.basicFeatureIds) return@demoAction
            if (engine.state.session != null && engine.view().allowed) dispatch(AppEvent.Cancel)
            this@MainActivity.selectedId = id
        }
        override fun place(id: String) = appAction {
            dispatch(AppEvent.PlaceDemo(id, engine.state.data.deletionEpoch))
            screen = Screen.CARDS; placeTab = true; debugOpen = false; resetAppScroll = true
        }
        override fun platform() = demoAction {
            demoPlatformAllowed = !demoPlatformAllowed
            engine.dispatch(AppEvent.Platform(demoPlatformAllowed && store.available))
            it.refresh(engine.state.epoch)
        }
        override fun observe(label: String) = appAction { real()?.observeConsole(label) }
        override fun routeDiagnostic() {
            if (diagnosticAvailable) VariantUi.diagnose(this@MainActivity)
        }
        override fun close() { real()?.cancelPreparation(); debugOpen = false; resetAppScroll = true; render() }
    }

    private fun logState() {
        diagnostic {
            val state = engine.state
            Log.i("PetApp", "tMs=" + System.currentTimeMillis() + " mode=" + (if (demoMode) "demo" else "real") +
            " foreground=" + state.foreground + " gear=" + state.gear + " platform=" + state.platformAllowed +
            " epoch=" + state.epoch + " session=" + state.session?.id +
            " awarded=" + state.data.awarded.joinToString(",") + " cards=" + state.data.awarded.size +
            " growth=" + engine.view().growth + " stored=" + store.available) }
    }

    private fun dp(value: Int) = ui.dp(value)
    private fun number(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.ROOT, "%.1f", value)

    @Suppress("DEPRECATION")
    private fun installInsets(root: View) {
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            } else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
    }

    private fun showStartupFailure() {
        val root = ui.column().apply {
            setBackgroundColor(ui.color(R.color.upet_bg)); setPadding(dp(44), dp(36), dp(44), dp(44))
            addView(ui.label("앱 내용을 읽지 못했어요.", DesignUi.TITLE, 700))
            addView(ui.label("앱 조작을 잠갔어요. 설치된 앱의 내용 파일을 확인해 주세요."), ui.block())
        }
        installInsets(root); setContentView(root); root.requestApplyInsets()
    }
}
