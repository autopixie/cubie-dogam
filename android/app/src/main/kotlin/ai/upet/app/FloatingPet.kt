package ai.upet.app

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import ai.upet.core.PetMotionFrame
import kotlin.math.min

/** A picture or the complete speech/tail/picture group moves only inside its measured container. */
internal class FloatingPet(
    context: Context,
    private val picture: View,
    private val ui: DesignUi,
    private val pictureSizeDp: Int,
    private val horizontal: Boolean,
    enabled: Boolean,
    private val grouped: Boolean = false,
    inputTarget: View = picture,
    private val sample: (Long, Int, Int, Boolean) -> PetMotionFrame,
    private val input: () -> Unit,
    private val restChanged: (Boolean) -> Unit,
    private val report: (String) -> Unit,
) : FrameLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    private var constructed = false
    private var attached = false
    private var paused = false
    private var reduced = true
    private var observationFailed = false
    private var previousResting: Boolean? = null
    private var previousReport = ""
    private val tick = Runnable { drawFrame() }
    private val scales = arrayOf(Settings.Global.ANIMATOR_DURATION_SCALE,
        Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.WINDOW_ANIMATION_SCALE)
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { updateEnvironment(); recheck() }
    }
    private val explorationListener = AccessibilityManager.TouchExplorationStateChangeListener {
        updateEnvironment(); recheck()
    }
    private var accessibilityRegistered = false

    init {
        require(picture.parent == null && pictureSizeDp > 0)
        clipChildren = true; clipToPadding = true
        // Grouped speech gets the full hero inner width; vertical reaction safety stays intact.
        val horizontalInset = if (grouped) 0 else ui.dp(12)
        setPadding(horizontalInset, ui.dp(12), horizontalInset, ui.dp(12))
        addView(picture, LayoutParams(0, 0))
        ui.bindAction(inputTarget, ui.color(R.color.upet_surface_1), 32, enabled) { input(); recheck() }
        constructed = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = ui.dp(pictureSizeDp)
        val w = resolveSize(side + paddingLeft + paddingRight, widthMeasureSpec)
        if (grouped) {
            val maxWidth = (w - paddingLeft - paddingRight).coerceAtLeast(0)
            picture.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            // A fixed display budget leaves no invented travel when long text fills it.
            val natural = picture.measuredHeight + paddingTop + paddingBottom
            val nominal = side + ui.dp(280) + paddingTop + paddingBottom
            setMeasuredDimension(w, resolveSize(maxOf(natural, nominal), heightMeasureSpec))
            return
        }
        val h = resolveSize(side + paddingTop + paddingBottom + ui.dp(24), heightMeasureSpec)
        setMeasuredDimension(w, h)
        val fit = min(side, min((w - paddingLeft - paddingRight).coerceAtLeast(0),
            (h - paddingTop - paddingBottom).coerceAtLeast(0)))
        picture.measure(MeasureSpec.makeMeasureSpec(fit, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(fit, MeasureSpec.EXACTLY))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        picture.layout(paddingLeft, paddingTop, paddingLeft + picture.measuredWidth, paddingTop + picture.measuredHeight)
        drawFrame()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); attached = true; observationFailed = false
        try {
            scales.forEach { context.contentResolver.registerContentObserver(Settings.Global.getUriFor(it), false, observer) }
            accessibilityRegistered = accessibility?.addTouchExplorationStateChangeListener(explorationListener) == true
        } catch (_: Exception) { observationFailed = true; unregisterEnvironment() }
        updateEnvironment(); recheck()
    }
    override fun onDetachedFromWindow() {
        attached = false; stop("detached"); unregisterEnvironment(); super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility); if (constructed) recheck()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (constructed) { updateEnvironment(); recheck() }
    }
    fun recheck() { removeCallbacks(tick); if (attached) drawFrame() }
    fun pauseMotion() { paused = true; stop("background") }
    fun resumeMotion() { paused = false; updateEnvironment(); recheck() }

    private fun updateEnvironment() {
        reduced = try {
            observationFailed || !ValueAnimator.areAnimatorsEnabled() ||
                scales.any { Settings.Global.getFloat(context.contentResolver, it, 1f) <= 0f } ||
                accessibility?.isTouchExplorationEnabled == true
        } catch (_: Exception) { true }
    }
    private fun drawFrame() {
        removeCallbacks(tick)
        if (!attached) return
        if (paused) { stop("background"); return }
        if (windowVisibility != VISIBLE || !hasWindowFocus() || !isShown) { stop("window_hidden"); return }
        if (width <= 0 || height <= 0 || picture.measuredWidth <= 0 || picture.measuredHeight <= 0) return
        val bounds = ai.upet.core.PetGroupBounds.measure(width - paddingLeft - paddingRight,
            height - paddingTop - paddingBottom, picture.measuredWidth, picture.measuredHeight,
            horizontal, ui.dp(24), ui.dp(4))
        if (!bounds.fits) { stop("group_does_not_fit"); return }
        val travelX = bounds.travelX
        val travelY = bounds.travelY
        val now = SystemClock.uptimeMillis()
        val frame = sample(now, travelX, travelY, reduced || !ValueAnimator.areAnimatorsEnabled())
        if (!frame.xPx.isFinite() || !frame.yPx.isFinite()) { stop("invalid_frame"); return }
        picture.translationX = bounds.baseX + frame.xPx.coerceIn(0f, travelX.toFloat())
        picture.translationY = bounds.baseY + frame.yPx.coerceIn(0f, travelY.toFloat())
        if (previousResting != frame.resting) { previousResting = frame.resting; restChanged(frame.resting) }
        diagnostic { emit("moving=${frame.moving} reason=${frame.reason} travelX=$travelX travelY=$travelY") }
        val delay = frame.nextFrameInMs
        if (reduced || delay == null) { picture.animate().cancel(); return }
        postDelayed(tick, delay.coerceAtLeast(16L))
    }
    private fun stop(reason: String) {
        removeCallbacks(tick); picture.animate().cancel()
        picture.translationX = (width - paddingLeft - paddingRight - picture.measuredWidth).coerceAtLeast(0) / 2f
        picture.translationY = (height - paddingTop - paddingBottom - picture.measuredHeight).coerceAtLeast(0) / 2f
        diagnostic { emit("moving=false reason=$reason") }
    }
    private fun emit(value: String) {
        if (previousReport == value) return
        previousReport = value
        report("tMs=${System.currentTimeMillis()} uptimeMs=${SystemClock.uptimeMillis()} $value")
    }
    private fun unregisterEnvironment() {
        try { context.contentResolver.unregisterContentObserver(observer) } catch (_: Exception) { }
        if (accessibilityRegistered) accessibility?.removeTouchExplorationStateChangeListener(explorationListener)
        accessibilityRegistered = false
    }
}
